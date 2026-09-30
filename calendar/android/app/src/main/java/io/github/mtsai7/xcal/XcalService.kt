package io.github.mtsai7.xcal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.CalendarContract
import android.util.Log
import io.github.mtsai7.xcal.protocol.StatusReport
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Foreground service (type connectedDevice) that keeps the GATT server and
 * advertising alive (spec §8). Start it only when [Permissions.canRunService].
 */
class XcalService : Service(), XcalGattServer.Listener {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var store: SnapshotStore
    private lateinit var gatt: XcalGattServer
    private var observerRegistered = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        try {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: SecurityException) {
            // Bluetooth permissions missing; callers are supposed to check first.
            Log.e(TAG, "Cannot start foreground service", e)
            stopSelf()
            return
        }
        XcalState.serviceRunning = true
        store = SnapshotStore(this)
        XcalState.snapshot = store.current
        gatt = XcalGattServer(this, store, this)
        gatt.start()

        registerSystemReceiver(bluetoothReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        registerSystemReceiver(
            timeReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_DATE_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            },
        )
        registerCalendarObserver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A new start (e.g. after granting calendar access) refreshes the agenda.
        if (::store.isInitialized) {
            registerCalendarObserver()
            store.requestRebuild(0)
        }
        return START_STICKY
    }

    /** System broadcasts only; RECEIVER_NOT_EXPORTED exists from Android 13. */
    private fun registerSystemReceiver(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    private fun registerCalendarObserver() {
        if (observerRegistered) return
        try {
            contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, calendarObserver)
            observerRegistered = true
        } catch (e: SecurityException) {
            Log.w(TAG, "No calendar permission; agenda stays empty until granted", e)
        }
    }

    override fun onDestroy() {
        if (::gatt.isInitialized) {
            unregisterReceiver(bluetoothReceiver)
            unregisterReceiver(timeReceiver)
            if (observerRegistered) contentResolver.unregisterContentObserver(calendarObserver)
            gatt.stop()
            store.close()
        }
        XcalState.serviceRunning = false
        XcalState.advertising = false
        super.onDestroy()
    }

    // --- Triggers (spec §8) ------------------------------------------------

    private val calendarObserver = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            store.requestRebuild()
        }
    }

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            store.requestRebuild(0)
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> gatt.start()
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> gatt.stop()
            }
            updateNotification()
        }
    }

    // --- XcalGattServer.Listener (binder threads) ---------------------------

    override fun onSessionServed(device: String, snapshot: Snapshot) {
        XcalState.lastSessionAt = Instant.now()
        XcalState.snapshot = snapshot
        main.post { updateNotification() }
    }

    override fun onStatus(device: String, status: StatusReport) {
        XcalState.lastStatus = status
        XcalState.lastStatusAt = Instant.now()
        main.post { updateNotification() }
    }

    override fun onAdvertisingChanged(active: Boolean, error: String?) {
        XcalState.advertising = active
        XcalState.advertisingError = error
        main.post { updateNotification() }
    }

    // --- Notification ---------------------------------------------------------

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val time = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
        val error = XcalState.advertisingError
        val lastSession = XcalState.lastSessionAt
        val battery = XcalState.lastStatus?.batteryPercent?.takeIf { it <= 100 }
        val text = when {
            error != null -> getString(R.string.notif_error, error)
            !XcalState.advertising -> getString(R.string.notif_waiting_bluetooth)
            lastSession == null -> getString(R.string.notif_waiting_x3)
            battery != null -> getString(R.string.notif_synced_battery, time.format(lastSession), battery)
            else -> getString(R.string.notif_synced, time.format(lastSession))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification())
    }

    companion object {
        private const val TAG = "XcalService"
        private const val CHANNEL_ID = "xcal_service"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            if (!Permissions.canRunService(context)) return
            context.startForegroundService(Intent(context, XcalService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, XcalService::class.java))
        }
    }
}
