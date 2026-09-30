package io.github.mtsai7.xcal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the service after boot or an app update, if the user left it enabled. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                if (Prefs.enabled(context)) XcalService.start(context)
        }
    }
}
