package io.github.mtsai7.xcal

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import io.github.mtsai7.xcal.calendar.AgendaSource
import io.github.mtsai7.xcal.protocol.PayloadCodec
import io.github.mtsai7.xcal.protocol.PosixTz
import io.github.mtsai7.xcal.protocol.Xcal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Immutable payload snapshot (spec §3.1). [payload] is null when there is
 * nothing to offer (calendar permission missing or the build failed).
 */
class Snapshot(
    val payload: ByteArray?,
    val day0: LocalDate,
    val zone: ZoneId,
    val eventCount: Int,
    val noCalendarPermission: Boolean,
    val builtAt: Instant,
) {
    val crc32: Long = payload?.let { PayloadCodec.crc32(it) } ?: 0L
}

/**
 * Owns the current snapshot. Rebuilds run on a private thread, debounced;
 * [current] is swapped atomically so GATT reads always see a complete value.
 */
class SnapshotStore(private val context: Context) {
    private val source = AgendaSource(context)
    private val thread = HandlerThread("xcal-snapshot").apply { start() }
    private val handler = Handler(thread.looper)
    private val rebuildRunnable = Runnable { rebuildNow() }

    @Volatile
    var current: Snapshot = build()
        private set

    /** Debounced rebuild (calendar edits arrive in bursts). */
    fun requestRebuild(delayMs: Long = DEBOUNCE_MS) {
        handler.removeCallbacks(rebuildRunnable)
        handler.postDelayed(rebuildRunnable, delayMs)
    }

    /** Called on HEADER reads: rebuild synchronously if the local date or zone moved on. */
    fun ensureFresh(): Snapshot {
        val zone = ZoneId.systemDefault()
        val snap = current
        if (snap.day0 == LocalDate.now(zone) && snap.zone == zone) return snap
        return rebuildNow()
    }

    @Synchronized
    private fun rebuildNow(): Snapshot {
        val snap = build()
        current = snap
        Log.i(TAG, "Snapshot day0=${snap.day0} events=${snap.eventCount} bytes=${snap.payload?.size} crc=%08x".format(snap.crc32))
        return snap
    }

    private fun build(): Snapshot {
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val day0 = now.atZone(zone).toLocalDate() // LocalDate.ofInstant is API 34+
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return Snapshot(null, day0, zone, 0, noCalendarPermission = true, builtAt = now)
        }
        return try {
            val events = source.load(zone, day0, Xcal.DAY_COUNT)
            val payload = PayloadCodec.encodePayload(now.epochSecond, day0.toEpochDay().toInt(), Xcal.DAY_COUNT, events)
            Snapshot(payload, day0, zone, payload[8].toInt() and 0xFF, noCalendarPermission = false, builtAt = now)
        } catch (e: RuntimeException) {
            // SecurityException if permission was revoked mid-run, provider errors, etc.
            Log.e(TAG, "Snapshot build failed", e)
            Snapshot(null, day0, zone, 0, noCalendarPermission = e is SecurityException, builtAt = now)
        }
    }

    /** HEADER bytes for a new session pin (spec §4.1), sampled now. */
    fun headerFor(snap: Snapshot): ByteArray {
        val now = Instant.now()
        val offsetMin = snap.zone.rules.getOffset(now).totalSeconds / 60
        return PayloadCodec.encodeHeader(
            payload = snap.payload,
            nowUtc = now.epochSecond,
            utcOffsetMin = offsetMin,
            tzPosix = PosixTz.forZone(snap.zone, now),
            noCalendarPermission = snap.noCalendarPermission,
        )
    }

    fun close() {
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }

    companion object {
        private const val TAG = "SnapshotStore"
        private const val DEBOUNCE_MS = 2000L
    }
}
