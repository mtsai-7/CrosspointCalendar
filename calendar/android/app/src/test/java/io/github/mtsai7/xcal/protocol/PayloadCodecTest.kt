package io.github.mtsai7.xcal.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Byte-exact checks against the vectors of docs/ble-protocol.md §12 (tools/xcal_protocol.py). */
class PayloadCodecTest {
    private val day0 = 20725 // 2026-09-29
    private val generated = 1790665200L
    private val now = 1790690400L
    private val tzLa = "STD8DST,M3.2.0,M11.1.0"

    private fun hex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun basicEvents() = listOf(
        AgendaEvent(9 * 60, 9 * 60 + 30, "Standup", location = "Zoom"),
        AgendaEvent(0, 1440, "Mark's birthday", allDay = true),
        AgendaEvent(-120, 60, "Late\tnight  deploy"),
        AgendaEvent(13 * 60, 14 * 60, "Dentist — Dr. Müller", location = "  123 Main St  ", tentative = true),
        AgendaEvent(1440 + 10 * 60, 1440 + 11 * 60, "Tomorrow: 1:1"),
    )

    @Test
    fun basicVector() {
        val payload = PayloadCodec.encodePayload(generated, day0, 2, basicEvents())
        assertEquals(
            "01f061bb6af5500205000088ff3c00114c617465206e69676874206465706c6f79010000a0050f" +
                "4d61726b2773206269727468646179041c023a02075374616e647570045a6f6f6d060c03480317" +
                "44656e7469737420e280942044722e204dc3bc6c6c65720b313233204d61696e20537400f80734" +
                "080d546f6d6f72726f773a20313a31",
            payload.toHex(),
        )
        assertEquals(0xc9f9fd8eL, PayloadCodec.crc32(payload))
        val header = PayloadCodec.encodeHeader(payload, now, -420, tzLa, noCalendarPermission = false)
        assertEquals(
            "0100010184008efdf9c960c4bb6a5cfe16535444384453542c4d332e322e302c4d31312e312e30",
            header.toHex(),
        )
    }

    @Test
    fun truncationCutsOnCodePointBoundary() {
        val payload = PayloadCodec.encodePayload(generated, day0, 2, listOf(AgendaEvent(600, 660, "會".repeat(70))))
        assertEquals("01f061bb6af55002010000580294023f" + "e69c83".repeat(21), payload.toHex())
    }

    @Test
    fun overflowVector() {
        val events = (0 until 40).map { i ->
            AgendaEvent(480 + i, 540 + i, "Event %02d ".format(i) + "x".repeat(60))
        }
        val payload = PayloadCodec.encodePayload(generated, day0, 2, events)
        assertEquals(2040, payload.size)
        assertEquals(29, payload[8].toInt() and 0xFF)
        assertEquals(11, payload[9].toInt() and 0xFF)
        assertEquals(0x44648800L, PayloadCodec.crc32(payload))
        val header = PayloadCodec.encodeHeader(payload, now, -420, tzLa, noCalendarPermission = false)
        assertEquals(
            "01000104f8070088644460c4bb6a5cfe16535444384453542c4d332e322e302c4d31312e312e30",
            header.toHex(),
        )
        val joined = (0 until 4).map { PayloadCodec.chunk(payload, it) }.reduce { a, b -> a + b }
        assertArrayEquals(payload, joined)
        assertEquals(0, PayloadCodec.chunk(payload, 4).size)
    }

    @Test
    fun noPermissionHeader() {
        val header = PayloadCodec.encodeHeader(null, now, -420, tzLa, noCalendarPermission = true)
        assertEquals(
            "0100020000000000000060c4bb6a5cfe16535444384453542c4d332e322e302c4d31312e312e30",
            header.toHex(),
        )
    }

    @Test
    fun eventCountCappedAt255() {
        val payload = PayloadCodec.encodePayload(generated, day0, 2, (0 until 300).map { AgendaEvent(it, it, "") })
        assertEquals(255, payload[8].toInt() and 0xFF)
        assertEquals(45, payload[9].toInt() and 0xFF)
    }

    @Test
    fun endBeforeStartIsRepaired() {
        val payload = PayloadCodec.encodePayload(generated, day0, 1, listOf(AgendaEvent(600, 500, "bad")))
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(600, buf.getShort(11).toInt())
        assertEquals(600, buf.getShort(13).toInt())
    }

    @Test
    fun tieBreakOnEncodedTitleBytes() {
        val tied = listOf("b", "é", "a", "B").map { AgendaEvent(60, 120, it) }
        val payload = PayloadCodec.encodePayload(generated, day0, 1, tied)
        assertEquals(listOf("B", "a", "b", "é"), titles(payload))
    }

    @Test
    fun statusVector() {
        val status = PayloadCodec.decodeStatus(
            hex("01578efdf9c90c00011b312e362e352d6465762d63616c656e6461722d3933653938626237"),
        )
        assertNotNull(status)
        assertEquals(87, status!!.batteryPercent)
        assertEquals(0xc9f9fd8eL, status.shownCrc32)
        assertEquals(12, status.wakeCount)
        assertEquals(Xcal.ST_CLOCK_ADJUSTED, status.flags)
        assertEquals("1.6.5-dev-calendar-93e98bb7", status.firmwareVersion)
    }

    @Test
    fun statusUnknownVersionIgnored() {
        assertEquals(null, PayloadCodec.decodeStatus(hex("02570000000000000000")))
    }

    /** Minimal decoder for assertions (the X3 firmware owns the real one). */
    private fun titles(payload: ByteArray): List<String> {
        val count = payload[8].toInt() and 0xFF
        var pos = Xcal.PAYLOAD_HEADER_LEN
        return List(count) {
            val flags = payload[pos].toInt()
            val titleLen = payload[pos + 5].toInt() and 0xFF
            val title = String(payload, pos + 6, titleLen, Charsets.UTF_8)
            pos += 6 + titleLen
            if (flags and Xcal.EV_HAS_LOCATION != 0) pos += 1 + (payload[pos].toInt() and 0xFF)
            title
        }
    }
}
