package io.github.mtsai7.xcal

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.github.mtsai7.xcal.protocol.StatusReport
import java.time.Instant

/** Process-wide state shown by the UI and the notification (single process app). */
object XcalState {
    @Volatile var serviceRunning = false
    @Volatile var advertising = false
    @Volatile var advertisingError: String? = null
    @Volatile var snapshot: Snapshot? = null
    @Volatile var lastSessionAt: Instant? = null
    @Volatile var lastStatus: StatusReport? = null
    @Volatile var lastStatusAt: Instant? = null
}

object Permissions {
    /** Needed for the GATT server/advertiser and the connectedDevice FGS type. */
    val BLUETOOTH = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)

    fun all(): Array<String> {
        val list = mutableListOf(*BLUETOOTH, Manifest.permission.READ_CALENDAR)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) list += Manifest.permission.POST_NOTIFICATIONS
        return list.toTypedArray()
    }

    fun granted(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Starting the foreground service without these throws on Android 14+. */
    fun canRunService(context: Context): Boolean = BLUETOOTH.all { granted(context, it) }
}

object Prefs {
    private const val FILE = "xcal"
    private const val KEY_ENABLED = "enabled"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, value).apply()
    }
}
