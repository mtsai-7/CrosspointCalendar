package io.github.mtsai7.xcal

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import io.github.mtsai7.xcal.protocol.PayloadCodec
import io.github.mtsai7.xcal.protocol.StatusReport
import io.github.mtsai7.xcal.protocol.Xcal
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * GATT server + advertiser for the XCAL service (spec §2.1, §3).
 * All Bluetooth calls need BLUETOOTH_CONNECT / BLUETOOTH_ADVERTISE, which the
 * service checks before calling [start].
 */
@SuppressLint("MissingPermission")
class XcalGattServer(
    private val context: Context,
    private val store: SnapshotStore,
    private val listener: Listener,
) {
    interface Listener {
        fun onSessionServed(device: String, snapshot: Snapshot)
        fun onStatus(device: String, status: StatusReport)
        fun onAdvertisingChanged(active: Boolean, error: String?)
    }

    /** Header + payload pinned to one connection (spec §3.1). */
    private class Pin(val header: ByteArray, val snapshot: Snapshot)

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val pins = ConcurrentHashMap<String, Pin>()
    private val preparedWrites = ConcurrentHashMap<String, ByteArray>()
    @Volatile
    private var server: BluetoothGattServer? = null

    @Volatile
    private var advertising = false

    fun start(): Boolean {
        val mgr = manager
        val adapter = mgr?.adapter
        if (mgr == null || adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "Bluetooth unavailable or off")
            return false
        }
        if (server != null) return true
        val gatt = mgr.openGattServer(context, callback) ?: run {
            Log.e(TAG, "openGattServer returned null")
            return false
        }
        server = gatt
        // Advertising starts in onServiceAdded, once the service is registered.
        if (!gatt.addService(buildService())) {
            Log.e(TAG, "addService rejected")
            stop()
            return false
        }
        return true
    }

    fun stop() {
        stopAdvertising()
        server?.let {
            it.clearServices()
            it.close()
        }
        server = null
        pins.clear()
        preparedWrites.clear()
    }

    private fun buildService(): BluetoothGattService {
        val service = BluetoothGattService(Xcal.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        fun readOnly(uuid: java.util.UUID) = BluetoothGattCharacteristic(
            uuid,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED_MITM,
        )
        service.addCharacteristic(readOnly(Xcal.HEADER))
        Xcal.CHUNKS.forEach { service.addCharacteristic(readOnly(it)) }
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                Xcal.STATUS,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED_MITM,
            ),
        )
        return service
    }

    // --- Advertising (spec §2.1) --------------------------------------------

    private fun startAdvertising() {
        val advertiser = manager?.adapter?.bluetoothLeAdvertiser ?: run {
            listener.onAdvertisingChanged(false, "No LE advertiser")
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(Xcal.SERVICE))
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()
        advertiser.startAdvertising(settings, data, advertiseCallback)
    }

    private fun stopAdvertising() {
        if (!advertising) return
        manager?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        advertising = false
        listener.onAdvertisingChanged(false, null)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            advertising = true
            listener.onAdvertisingChanged(true, null)
        }

        override fun onStartFailure(errorCode: Int) {
            // ALREADY_STARTED is harmless (restart after a Bluetooth toggle race).
            advertising = errorCode == ADVERTISE_FAILED_ALREADY_STARTED
            Log.e(TAG, "Advertising failed: $errorCode")
            listener.onAdvertisingChanged(advertising, if (advertising) null else "Advertising error $errorCode")
        }
    }

    // --- GATT callbacks (binder threads) -----------------------------------

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status == BluetoothGatt.GATT_SUCCESS && service.uuid == Xcal.SERVICE) {
                startAdvertising()
            } else {
                Log.e(TAG, "Service registration failed: $status")
                listener.onAdvertisingChanged(false, "GATT service error $status")
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Log.i(TAG, "Connection ${device.address}: state=$newState status=$status")
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                pins.remove(device.address)
                preparedWrites.remove(device.address)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            Log.i(TAG, "MTU ${device.address}: $mtu")
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val uuid = characteristic.uuid
            val value: ByteArray? = when {
                uuid == Xcal.HEADER -> {
                    val pin = if (offset == 0) newPin(device) else pins[device.address] ?: newPin(device)
                    pin.header
                }
                uuid in Xcal.CHUNKS -> {
                    val pin = pins[device.address] ?: newPin(device)
                    PayloadCodec.chunk(pin.snapshot.payload, Xcal.CHUNKS.indexOf(uuid))
                }
                else -> null
            }
            if (value == null) {
                respond(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null)
            } else if (offset > value.size) {
                respond(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
            } else {
                respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.copyOfRange(offset, value.size))
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            val data = value ?: ByteArray(0)
            var status = BluetoothGatt.GATT_SUCCESS
            if (characteristic.uuid != Xcal.STATUS) {
                status = BluetoothGatt.GATT_WRITE_NOT_PERMITTED
            } else if (preparedWrite) {
                // Long write (spec §3): assemble until onExecuteWrite.
                val buf = preparedWrites[device.address] ?: ByteArray(0)
                if (offset + data.size > MAX_STATUS_WRITE) {
                    status = BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH
                } else {
                    val grown = if (buf.size < offset + data.size) buf.copyOf(offset + data.size) else buf
                    data.copyInto(grown, offset)
                    preparedWrites[device.address] = grown
                }
            } else if (offset != 0) {
                status = BluetoothGatt.GATT_INVALID_OFFSET
            } else {
                handleStatus(device, data)
            }
            if (responseNeeded) respond(device, requestId, status, offset, data)
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            val buf = preparedWrites.remove(device.address)
            if (execute && buf != null) handleStatus(device, buf)
            respond(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }
    }

    private fun newPin(device: BluetoothDevice): Pin {
        val snapshot = store.ensureFresh()
        val pin = Pin(store.headerFor(snapshot), snapshot)
        pins[device.address] = pin
        listener.onSessionServed(device.address, snapshot)
        return pin
    }

    private fun handleStatus(device: BluetoothDevice, data: ByteArray) {
        // Unknown versions are acknowledged and ignored (spec §4.3).
        val status = PayloadCodec.decodeStatus(data) ?: return
        Log.i(TAG, "STATUS from ${device.address}: $status at ${Instant.now()}")
        listener.onStatus(device.address, status)
    }

    private fun respond(device: BluetoothDevice, requestId: Int, status: Int, offset: Int, value: ByteArray?) {
        server?.sendResponse(device, requestId, status, offset, value)
    }

    companion object {
        private const val TAG = "XcalGattServer"
        private const val MAX_STATUS_WRITE = 64
    }
}
