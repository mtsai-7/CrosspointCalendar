package io.github.mtsai7.xcal.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** One agenda entry, in local wall-clock minutes relative to day0 00:00 (spec §4.2). */
data class AgendaEvent(
    val startMin: Int,
    val endMin: Int,
    val title: String,
    val location: String = "",
    val allDay: Boolean = false,
    val tentative: Boolean = false,
) {
    /** Spec §4.2: end < start is encoded as end = start. */
    val fixedEnd: Int get() = maxOf(endMin, startMin)
}

data class StatusReport(
    val batteryPercent: Int,
    val shownCrc32: Long,
    val wakeCount: Int,
    val flags: Int,
    val firmwareVersion: String,
)

object PayloadCodec {
    fun crc32(data: ByteArray): Long = CRC32().apply { update(data) }.value

    private class Encoded(val event: AgendaEvent, val index: Int, val title: ByteArray, val record: ByteArray)

    private fun encodeEvent(e: AgendaEvent, title: ByteArray, dayCount: Int): ByteArray {
        val hi = (dayCount + 14) * 1440
        val start = e.startMin.coerceIn(Xcal.MIN_CLAMP, hi)
        val end = e.fixedEnd.coerceIn(Xcal.MIN_CLAMP, hi)
        val location = TextRules.truncateUtf8(TextRules.sanitize(e.location), Xcal.MAX_LOCATION)
        var flags = 0
        if (e.allDay) flags = flags or Xcal.EV_ALL_DAY
        if (e.tentative) flags = flags or Xcal.EV_TENTATIVE
        if (location.isNotEmpty()) flags = flags or Xcal.EV_HAS_LOCATION
        val size = 6 + title.size + if (location.isNotEmpty()) 1 + location.size else 0
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(flags.toByte())
        buf.putShort(start.toShort())
        buf.putShort(end.toShort())
        buf.put(title.size.toByte())
        buf.put(title)
        if (location.isNotEmpty()) {
            buf.put(location.size.toByte())
            buf.put(location)
        }
        return buf.array()
    }

    /** Builds the PAYLOAD (spec §4.2): ordering, size and count limits applied here. */
    fun encodePayload(generatedUtc: Long, day0: Int, dayCount: Int, events: List<AgendaEvent>): ByteArray {
        require(dayCount in 1..7) { "dayCount out of range" }
        require(day0 in 0..0xFFFF) { "day0 out of range" }
        val encoded = events.mapIndexed { i, e ->
            val title = TextRules.truncateUtf8(TextRules.sanitize(e.title), Xcal.MAX_TITLE)
            Encoded(e, i, title, encodeEvent(e, title, dayCount))
        }
        val ordered = encoded.sortedWith(
            compareBy<Encoded>({ it.event.startMin }, { if (it.event.allDay) 0 else 1 }, { it.event.fixedEnd })
                .thenComparator { a, b -> TextRules.compareBytes(a.title, b.title) }
                .thenBy { it.index },
        )
        val body = ByteArrayOutputStream()
        var included = 0
        for (enc in ordered) {
            if (included == Xcal.MAX_EVENTS ||
                Xcal.PAYLOAD_HEADER_LEN + body.size() + enc.record.size > Xcal.MAX_PAYLOAD
            ) break
            body.write(enc.record)
            included++
        }
        val omitted = minOf(255, ordered.size - included)
        val head = ByteBuffer.allocate(Xcal.PAYLOAD_HEADER_LEN).order(ByteOrder.LITTLE_ENDIAN)
        head.put(Xcal.PAYLOAD_VERSION.toByte())
        head.putInt(generatedUtc.toInt())
        head.putShort(day0.toShort())
        head.put(dayCount.toByte())
        head.put(included.toByte())
        head.put(omitted.toByte())
        return head.array() + body.toByteArray()
    }

    /** Builds the HEADER (spec §4.1). [payload] null = nothing to offer (PAYLOAD_VALID clear). */
    fun encodeHeader(
        payload: ByteArray?,
        nowUtc: Long,
        utcOffsetMin: Int,
        tzPosix: String,
        noCalendarPermission: Boolean,
    ): ByteArray {
        val tz = tzPosix.toByteArray(Charsets.US_ASCII)
        require(tz.size <= Xcal.MAX_TZ_LEN) { "tz too long" }
        var flags = if (noCalendarPermission) Xcal.HDR_NO_CALENDAR_PERMISSION else 0
        var len = 0
        var crc = 0L
        var chunks = 0
        if (payload != null) {
            require(payload.size <= Xcal.MAX_PAYLOAD) { "payload too long" }
            flags = flags or Xcal.HDR_PAYLOAD_VALID
            len = payload.size
            crc = crc32(payload)
            chunks = (len + Xcal.CHUNK_SIZE - 1) / Xcal.CHUNK_SIZE
        }
        val buf = ByteBuffer.allocate(Xcal.HEADER_FIXED_LEN + tz.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(Xcal.PROTO_MAJOR.toByte())
        buf.put(Xcal.PROTO_MINOR.toByte())
        buf.put(flags.toByte())
        buf.put(chunks.toByte())
        buf.putShort(len.toShort())
        buf.putInt(crc.toInt())
        buf.putInt(nowUtc.toInt())
        buf.putShort(utcOffsetMin.toShort())
        buf.put(tz.size.toByte())
        buf.put(tz)
        return buf.array()
    }

    /** Slice [index] of the payload for CHUNKn; empty past the end (spec §3). */
    fun chunk(payload: ByteArray?, index: Int): ByteArray {
        if (payload == null) return ByteArray(0)
        val from = index * Xcal.CHUNK_SIZE
        if (from >= payload.size) return ByteArray(0)
        return payload.copyOfRange(from, minOf(payload.size, from + Xcal.CHUNK_SIZE))
    }

    /** Parses a STATUS write (spec §4.3); null for unknown versions or truncated data. */
    fun decodeStatus(data: ByteArray): StatusReport? {
        if (data.size < 10 || data[0].toInt() != Xcal.STATUS_VERSION) return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(1)
        val battery = buf.get().toInt() and 0xFF
        val crc = buf.int.toLong() and 0xFFFFFFFFL
        val wakes = buf.short.toInt() and 0xFFFF
        val flags = buf.get().toInt() and 0xFF
        val fwLen = buf.get().toInt() and 0xFF
        if (10 + fwLen > data.size) return null
        val fw = String(data, 10, fwLen, Charsets.US_ASCII)
        return StatusReport(battery, crc, wakes, flags, fw)
    }
}
