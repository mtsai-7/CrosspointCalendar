package io.github.mtsai7.xcal

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.mtsai7.xcal.protocol.PosixTz
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Single screen: status readout plus the few actions the user needs. */
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        status = TextView(this).apply { setTextIsSelectable(true) }
        column.addView(status)
        column.addView(button(R.string.btn_permissions) { requestPermissions(Permissions.all(), REQUEST_PERMISSIONS) })
        column.addView(button(R.string.btn_start) {
            Prefs.setEnabled(this, true)
            XcalService.start(this)
            refresh()
        })
        column.addView(button(R.string.btn_stop) {
            Prefs.setEnabled(this, false)
            XcalService.stop(this)
            refresh()
        })
        column.addView(button(R.string.btn_battery) { requestBatteryExemption() })
        column.addView(button(R.string.btn_bluetooth_settings) {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        })
        val root = ScrollView(this).apply { addView(column) }
        // targetSdk 35+ draws edge-to-edge: keep content clear of the system bars.
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            column.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom)
            insets
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Restart so a newly granted calendar permission takes effect immediately.
        if (requestCode == REQUEST_PERMISSIONS && Prefs.enabled(this)) XcalService.start(this)
        refresh()
    }

    private fun button(label: Int, onClick: (View) -> Unit) = Button(this).apply {
        setText(label)
        setOnClickListener(onClick)
    }

    @SuppressLint("BatteryLife") // Sideloaded personal app; the exemption is the point.
    private fun requestBatteryExemption() {
        val power = getSystemService(PowerManager::class.java)
        val intent = if (power?.isIgnoringBatteryOptimizations(packageName) == true) {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        } else {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        }
        startActivity(intent)
    }

    private fun refresh() {
        val zone = ZoneId.systemDefault()
        val time = DateTimeFormatter.ofPattern("MMM d HH:mm").withZone(zone)
        val yes = getString(R.string.yes)
        val no = getString(R.string.no)
        val power = getSystemService(PowerManager::class.java)
        val snap = XcalState.snapshot
        val lastSession = XcalState.lastSessionAt
        val x3 = XcalState.lastStatus
        val lines = buildList {
            add(getString(R.string.st_bluetooth_perm, if (Permissions.canRunService(this@MainActivity)) yes else no))
            add(getString(R.string.st_calendar_perm,
                if (Permissions.granted(this@MainActivity, android.Manifest.permission.READ_CALENDAR)) yes else no))
            add(getString(R.string.st_battery_exempt,
                if (power?.isIgnoringBatteryOptimizations(packageName) == true) yes else no))
            add(getString(R.string.st_service, if (XcalState.serviceRunning) yes else no))
            add(getString(R.string.st_advertising, if (XcalState.advertising) yes else (XcalState.advertisingError ?: no)))
            add(getString(R.string.st_timezone, zone.id, PosixTz.forZone(zone)))
            if (snap != null) {
                add(getString(R.string.st_agenda, snap.day0.toString(), snap.eventCount, snap.payload?.size ?: 0))
            }
            add(getString(R.string.st_last_session, lastSession?.let { time.format(it) } ?: "—"))
            if (x3 != null) {
                val battery = if (x3.batteryPercent <= 100) "${x3.batteryPercent}%" else "?"
                add(getString(R.string.st_x3, battery, x3.wakeCount, x3.firmwareVersion))
            }
        }
        status.text = lines.joinToString("\n")
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 1
        private const val REFRESH_MS = 2000L
    }
}
