package io.github.mtsai7.xcal.protocol

import java.util.Locale
import java.util.UUID

/** Constants of the XCAL BLE protocol v1 (docs/ble-protocol.md). */
object Xcal {
    private fun uuid(short: Int): UUID =
        UUID.fromString(String.format(Locale.ROOT, "c588%04x-361f-49c4-9e41-fc31c01fab0c", short))

    val SERVICE: UUID = uuid(0x0001)
    val HEADER: UUID = uuid(0x0002)
    val CHUNKS: List<UUID> = listOf(uuid(0x0010), uuid(0x0011), uuid(0x0012), uuid(0x0013))
    val STATUS: UUID = uuid(0x0020)

    const val PROTO_MAJOR = 1
    const val PROTO_MINOR = 0
    const val PAYLOAD_VERSION = 1
    const val STATUS_VERSION = 1

    const val CHUNK_SIZE = 512
    const val MAX_CHUNKS = 4
    const val MAX_PAYLOAD = CHUNK_SIZE * MAX_CHUNKS
    const val PAYLOAD_HEADER_LEN = 10
    const val HEADER_FIXED_LEN = 17
    const val MAX_TZ_LEN = 48
    const val MAX_TITLE = 64
    const val MAX_LOCATION = 48
    const val MAX_EVENTS = 255
    const val MIN_CLAMP = -14 * 1440

    /** Days of agenda the phone sends: today and tomorrow. */
    const val DAY_COUNT = 2

    const val HDR_PAYLOAD_VALID = 0x01
    const val HDR_NO_CALENDAR_PERMISSION = 0x02

    const val EV_ALL_DAY = 0x01
    const val EV_TENTATIVE = 0x02
    const val EV_HAS_LOCATION = 0x04

    const val ST_CLOCK_ADJUSTED = 0x01
    const val ST_TZ_APPLIED = 0x02
}
