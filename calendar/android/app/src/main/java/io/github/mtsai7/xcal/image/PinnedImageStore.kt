package io.github.mtsai7.xcal.image

import android.content.Context
import android.util.Log
import io.github.mtsai7.xcal.protocol.PayloadCodec
import java.io.File
import java.time.Instant

/**
 * The image pinned for the X3 (spec §4.4): the zlib image stream in app-private
 * storage, plus its expiry. An expired pin reads as unpinned.
 */
object PinnedImageStore {
    /** A pinned image stream and what identifies it on the wire. */
    class Pinned(val stream: ByteArray, val crc32: Long, val pinnedAt: Instant, val expiresAt: Instant?)

    private const val TAG = "PinnedImageStore"
    private const val FILE = "pinned_image.z"
    private const val PREFS = "xcal_image"
    private const val KEY_CRC = "crc"
    private const val KEY_AT = "pinned_at"
    private const val KEY_EXPIRES = "expires_at" // epoch ms, 0 = never

    @Volatile
    private var cached: Pinned? = null

    @Volatile
    private var loaded = false

    /** The current pin, or null when nothing is pinned or it has expired. */
    @Synchronized
    fun current(context: Context, now: Instant = Instant.now()): Pinned? {
        if (!loaded) {
            cached = load(context)
            loaded = true
        }
        val pin = cached ?: return null
        val expires = pin.expiresAt
        if (expires != null && !now.isBefore(expires)) return null
        return pin
    }

    @Synchronized
    fun pin(context: Context, bmp: ByteArray, expiresAt: Instant?): Pinned {
        val stream = XcalBitmap.encodeStream(bmp)
        val pin = Pinned(stream, PayloadCodec.crc32(stream), Instant.now(), expiresAt)
        val tmp = File(context.filesDir, "$FILE.tmp")
        tmp.writeBytes(stream)
        if (!tmp.renameTo(File(context.filesDir, FILE))) error("could not store the image")
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_CRC, pin.crc32)
            .putLong(KEY_AT, pin.pinnedAt.toEpochMilli())
            .putLong(KEY_EXPIRES, expiresAt?.toEpochMilli() ?: 0)
            .commit()
        cached = pin
        loaded = true
        Log.i(TAG, "Pinned image: %d B stream, crc %08x".format(stream.size, pin.crc32))
        return pin
    }

    /** Unpins (the user's "Show calendar", or a dismissal reported by the X3). */
    @Synchronized
    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        cached = null
        loaded = true
        Log.i(TAG, "Image unpinned")
    }

    private fun load(context: Context): Pinned? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val crc = prefs.getLong(KEY_CRC, 0)
        val file = File(context.filesDir, FILE)
        if (crc == 0L || !file.isFile) return null
        val stream = file.readBytes()
        if (PayloadCodec.crc32(stream) != crc) {
            Log.w(TAG, "Stored image does not match its CRC; dropping it")
            return null
        }
        val expires = prefs.getLong(KEY_EXPIRES, 0)
        return Pinned(
            stream,
            crc,
            Instant.ofEpochMilli(prefs.getLong(KEY_AT, 0)),
            if (expires == 0L) null else Instant.ofEpochMilli(expires),
        )
    }
}
