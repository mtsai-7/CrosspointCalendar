#!/usr/bin/env python3
"""Reference codec for the XCAL BLE protocol v1 (docs/ble-protocol.md).

Executable spec: encoders/decoders for HEADER, PAYLOAD and STATUS plus the
phone-side text and ordering rules. `python xcal_protocol.py` runs the
self-tests and prints the shared test vectors that the Android app and the X3
firmware unit tests must reproduce byte for byte.
"""

from __future__ import annotations

import struct
import unicodedata
import zlib
from dataclasses import dataclass, field

PROTO_MAJOR = 1
PROTO_MINOR = 0
PAYLOAD_VERSION = 1
STATUS_VERSION = 1

CHUNK_SIZE = 512
MAX_CHUNKS = 4
MAX_PAYLOAD = CHUNK_SIZE * MAX_CHUNKS  # 2048
PAYLOAD_HEADER_LEN = 10
HEADER_FIXED_LEN = 17
MAX_TZ_LEN = 48
MAX_TITLE = 64
MAX_LOCATION = 48
MAX_FW = 54

MIN_CLAMP = -14 * 1440  # -20160
MAX_EVENTS = 255

# Unicode White_Space (spec §4.2 text rule 2), listed explicitly so every
# implementation splits on exactly the same characters.
WHITESPACE = frozenset(
    [chr(c) for c in range(0x09, 0x0E)]
    + [" ", "\u0085", " ", " "]
    + [chr(c) for c in range(0x2000, 0x200B)]
    + [" ", " ", " ", " ", "　"]
)

HDR_PAYLOAD_VALID = 0x01
HDR_NO_CALENDAR_PERMISSION = 0x02

EV_ALL_DAY = 0x01
EV_TENTATIVE = 0x02
EV_HAS_LOCATION = 0x04

ST_CLOCK_ADJUSTED = 0x01
ST_TZ_APPLIED = 0x02


class ProtocolError(ValueError):
    pass


def crc32(data: bytes) -> int:
    """CRC-32/ISO-HDLC, identical to java.util.zip.CRC32."""
    return zlib.crc32(data) & 0xFFFFFFFF


# --- Text rules (phone side) -------------------------------------------------


PICTOGRAPH_RANGES = [
    (0x200D, 0x200D), (0x20E3, 0x20E3), (0xFE00, 0xFE0F), (0xE0020, 0xE007F),
    (0x2190, 0x21FF), (0x2300, 0x23FF), (0x25A0, 0x27BF), (0x2900, 0x297F),
    (0x2B00, 0x2BFF), (0x1F000, 0x1FAFF),
]


def is_pictograph(cp: int) -> bool:
    return any(lo <= cp <= hi for lo, hi in PICTOGRAPH_RANGES)


# "Fancy text" letters NFKC leaves alone: small capitals -> lowercase.
SMALL_CAPS = "ᴀa ʙb ᴄc ᴅd ᴇe ꜰf ɢg ʜh ɪi ᴊj ᴋk ʟl ᴍm ɴn ᴏo ᴘp ꞯq ʀr ꜱs ᴛt ᴜu ᴠv ᴡw ʏy ᴢz"
FOLD = {ord(pair[0]): pair[1] for pair in SMALL_CAPS.split()}


def fold_letter(cp: int) -> str | None:
    """Plain letter for a styled letter NFKC does not decompose, else None."""
    if cp in FOLD:
        return FOLD[cp]
    if 0x1F150 <= cp <= 0x1F169:  # negative circled capitals
        return chr(ord("A") + cp - 0x1F150)
    if 0x1F170 <= cp <= 0x1F189:  # negative squared capitals
        return chr(ord("A") + cp - 0x1F170)
    return None


def sanitize_text(text: str | None) -> str:
    """NFKC, drop pictographs, controls -> space, split on White_Space runs, rejoin with one space."""
    if not text:
        return ""
    words, cur = [], []
    for c in unicodedata.normalize("NFKC", text):
        c = fold_letter(ord(c)) or c
        if is_pictograph(ord(c)):
            continue
        if ord(c) < 0x20 or ord(c) == 0x7F:
            c = " "
        if c in WHITESPACE:
            if cur:
                words.append("".join(cur))
                cur = []
        else:
            cur.append(c)
    if cur:
        words.append("".join(cur))
    return " ".join(words)


def truncate_utf8(text: str, max_bytes: int) -> bytes:
    """UTF-8 encode, cutting on a code-point boundary to at most max_bytes."""
    out = bytearray()
    for ch in text:
        enc = ch.encode("utf-8")
        if len(out) + len(enc) > max_bytes:
            break
        out += enc
    return bytes(out)


# --- Events and payload ------------------------------------------------------


@dataclass
class Event:
    start_min: int
    end_min: int
    title: str = ""
    location: str = ""
    all_day: bool = False
    tentative: bool = False

    @property
    def fixed_end(self) -> int:
        # Spec §4.2: bad source data with end < start is encoded with endMin = startMin.
        return max(self.end_min, self.start_min)

    def encode(self, day_count: int) -> bytes:
        hi = (day_count + 14) * 1440
        start = max(MIN_CLAMP, min(hi, self.start_min))
        end = max(MIN_CLAMP, min(hi, self.fixed_end))
        title = truncate_utf8(sanitize_text(self.title), MAX_TITLE)
        loc = truncate_utf8(sanitize_text(self.location), MAX_LOCATION)
        flags = (EV_ALL_DAY if self.all_day else 0) | (EV_TENTATIVE if self.tentative else 0)
        if loc:
            flags |= EV_HAS_LOCATION
        out = struct.pack("<BhhB", flags, start, end, len(title)) + title
        if loc:
            out += struct.pack("<B", len(loc)) + loc
        return out


def sort_key(e: Event):
    # startMin asc, all-day first on ties, then endMin, then encoded title bytes
    # (unsigned lexicographic); Python's stable sort keeps input order last.
    return (e.start_min, 0 if e.all_day else 1, e.fixed_end, truncate_utf8(sanitize_text(e.title), MAX_TITLE))


@dataclass
class Payload:
    generated_utc: int
    day0: int
    day_count: int
    events: list[Event] = field(default_factory=list)

    def encode(self) -> bytes:
        if not 1 <= self.day_count <= 7:
            raise ProtocolError("dayCount out of range")
        body = bytearray()
        included = 0
        ordered = sorted(self.events, key=sort_key)
        for ev in ordered:
            rec = ev.encode(self.day_count)
            if included == MAX_EVENTS or PAYLOAD_HEADER_LEN + len(body) + len(rec) > MAX_PAYLOAD:
                break
            body += rec
            included += 1
        omitted = min(255, len(ordered) - included)
        head = struct.pack(
            "<BIHBBB", PAYLOAD_VERSION, self.generated_utc, self.day0, self.day_count, included, omitted
        )
        return head + bytes(body)


@dataclass
class DecodedEvent:
    flags: int
    start_min: int
    end_min: int
    title: str
    location: str | None


def decode_payload(data: bytes) -> dict:
    if len(data) < PAYLOAD_HEADER_LEN:
        raise ProtocolError("payload too short")
    version, gen, day0, day_count, count, omitted = struct.unpack_from("<BIHBBB", data, 0)
    if version != PAYLOAD_VERSION:
        raise ProtocolError(f"payloadVersion {version}")
    if not 1 <= day_count <= 7:
        raise ProtocolError("dayCount out of range")
    pos = PAYLOAD_HEADER_LEN
    events = []
    for _ in range(count):
        if pos + 6 > len(data):
            raise ProtocolError("event header past end")
        flags, start, end, tlen = struct.unpack_from("<BhhB", data, pos)
        pos += 6
        if end < start or tlen > MAX_TITLE or pos + tlen > len(data):
            raise ProtocolError("bad event")
        title = data[pos : pos + tlen].decode("utf-8")
        pos += tlen
        loc = None
        if flags & EV_HAS_LOCATION:
            if pos + 1 > len(data):
                raise ProtocolError("location length past end")
            llen = data[pos]
            pos += 1
            if llen == 0 or llen > MAX_LOCATION or pos + llen > len(data):
                raise ProtocolError("bad location")
            loc = data[pos : pos + llen].decode("utf-8")
            pos += llen
        events.append(DecodedEvent(flags, start, end, title, loc))
    if pos != len(data):
        raise ProtocolError("trailing bytes after events")
    return {"generated_utc": gen, "day0": day0, "day_count": day_count, "omitted": omitted, "events": events}


# --- Header ------------------------------------------------------------------


def encode_header(payload: bytes | None, now_utc: int, utc_offset_min: int, tz_posix: str,
                  no_calendar_permission: bool = False) -> bytes:
    tz = tz_posix.encode("ascii")
    if len(tz) > MAX_TZ_LEN:
        raise ProtocolError("tz too long")
    flags = HDR_NO_CALENDAR_PERMISSION if no_calendar_permission else 0
    if payload:
        if len(payload) > MAX_PAYLOAD:
            raise ProtocolError("payload too long")
        flags |= HDR_PAYLOAD_VALID
        plen, crc = len(payload), crc32(payload)
        chunks = (plen + CHUNK_SIZE - 1) // CHUNK_SIZE
    else:
        plen = crc = chunks = 0
    return struct.pack("<BBBBHIIhB", PROTO_MAJOR, PROTO_MINOR, flags, chunks, plen, crc, now_utc,
                       utc_offset_min, len(tz)) + tz


def decode_header(data: bytes) -> dict:
    if len(data) < HEADER_FIXED_LEN:
        raise ProtocolError("header too short")
    major, minor, flags, chunks, plen, crc, now, off, tzlen = struct.unpack_from("<BBBBHIIhB", data, 0)
    if major != PROTO_MAJOR:
        raise ProtocolError(f"protoMajor {major}")
    if tzlen > MAX_TZ_LEN or HEADER_FIXED_LEN + tzlen > len(data):
        raise ProtocolError("bad tzLen")
    if plen > MAX_PAYLOAD or chunks != (plen + CHUNK_SIZE - 1) // CHUNK_SIZE:
        raise ProtocolError("chunkCount/payloadLen mismatch")
    tz = data[HEADER_FIXED_LEN : HEADER_FIXED_LEN + tzlen].decode("ascii")
    # Trailing bytes beyond tz are allowed (future minor versions).
    return {"minor": minor, "flags": flags, "chunk_count": chunks, "payload_len": plen, "crc": crc,
            "now_utc": now, "utc_offset_min": off, "tz": tz}


def chunk(payload: bytes, index: int) -> bytes:
    return payload[index * CHUNK_SIZE : (index + 1) * CHUNK_SIZE]


# --- Status ------------------------------------------------------------------


def encode_status(battery: int, shown_crc: int, wake_count: int, flags: int, fw: str) -> bytes:
    fwb = fw.encode("ascii")[:MAX_FW]
    return struct.pack("<BBIHBB", STATUS_VERSION, battery, shown_crc, min(wake_count, 0xFFFF), flags,
                       len(fwb)) + fwb


def decode_status(data: bytes) -> dict | None:
    if len(data) < 10 or data[0] != STATUS_VERSION:
        return None
    ver, bat, crc, wakes, flags, fwlen = struct.unpack_from("<BBIHBB", data, 0)
    if 10 + fwlen > len(data):
        return None
    return {"battery": bat, "shown_crc": crc, "wake_count": wakes, "flags": flags,
            "fw": data[10 : 10 + fwlen].decode("ascii", "replace")}


# --- Test vectors ------------------------------------------------------------

# 2026-09-29 is day 20725 since 1970-01-01.
DAY0 = 20725
GENERATED = 1790665200  # 2026-09-29T07:00:00Z (00:00 PDT)
NOW = 1790690400  # 2026-09-29T14:00:00Z (07:00 PDT)
TZ_LA = "STD8DST,M3.2.0,M11.1.0"


SANITIZE_CASES = [
    ("", ""),
    ("  Standup  ", "Standup"),
    ("Late\tnight  deploy", "Late night deploy"),
    ("a  b", "a b"),  # NBSP is White_Space
    ("a​b", "a​b"),  # ZERO WIDTH SPACE is not White_Space
    ("line1\r\nline2", "line1 line2"),
    ("x\u0000y\u007fz", "x y z"),
    ("　Tokyo　", "Tokyo"),
    ("tab\u0085nel", "tab nel"),
    ("\U0001d401\U0001d428\U0001d425\U0001d41d \U0001d4fc\U0001d4ec\U0001d4fb\U0001d4f2\U0001d4f9\U0001d4fd", "Bold script"),
    ("Ｔｅａｍ Ⓐ", "Team A"),  # fullwidth, circled letter
    ("Team \U0001f389 lunch \U0001f355", "Team lunch"),
    ("\U0001f468‍\U0001f4bb Dev sync ✅️", "Dev sync"),
    ("\U0001f1fa\U0001f1f8 Holiday", "Holiday"),  # regional-indicator flag
    ("Café → office", "Café office"),  # NFKC composes; arrow dropped
    ("\U0001f600", ""),
    ("ʙᴇᴘ ʀᴇᴠɪᴇᴡ \U0001f697", "bep review"),  # small capitals
    ("\U0001f150\U0001f151 \U0001f17e\U0001f17f", "AB OP"),  # negative circled / squared
]


def vector_basic() -> tuple[bytes, bytes]:
    events = [
        Event(9 * 60, 9 * 60 + 30, "Standup", location="Zoom"),
        Event(0, 1440, "Mark's birthday", all_day=True),
        Event(-120, 60, "Late\tnight  deploy"),  # started yesterday 22:00, control char + spaces
        Event(13 * 60, 14 * 60, "Dentist — Dr. Müller", location="  123 Main St  ", tentative=True),
        Event(1440 + 10 * 60, 1440 + 11 * 60, "Tomorrow: 1:1"),
    ]
    payload = Payload(GENERATED, DAY0, 2, events).encode()
    header = encode_header(payload, NOW, -420, TZ_LA)
    return header, payload


def vector_truncation() -> bytes:
    # 70 x 3-byte CJK chars: title must cut at 63 bytes (21 chars), never mid-character.
    return Payload(GENERATED, DAY0, 2, [Event(600, 660, "會" * 70)]).encode()


def vector_overflow() -> tuple[bytes, bytes]:
    # 40 events with 64-byte titles (70 B each): only 29 fit in 2048 B.
    events = [Event(480 + i, 540 + i, f"Event {i:02d} " + "x" * 60) for i in range(40)]
    payload = Payload(GENERATED, DAY0, 2, events).encode()
    header = encode_header(payload, NOW, -420, TZ_LA)
    return header, payload


def vector_no_permission() -> bytes:
    return encode_header(None, NOW, -420, TZ_LA, no_calendar_permission=True)


def _self_test() -> None:
    header, payload = vector_basic()
    h = decode_header(header)
    assert h["payload_len"] == len(payload) and h["crc"] == crc32(payload) and h["chunk_count"] == 1
    assert h["tz"] == TZ_LA and h["utc_offset_min"] == -420 and h["flags"] == HDR_PAYLOAD_VALID
    p = decode_payload(payload)
    titles = [e.title for e in p["events"]]
    assert titles == ["Late night deploy", "Mark's birthday", "Standup", "Dentist — Dr. Müller",
                      "Tomorrow: 1:1"], titles
    assert p["events"][0].start_min == -120
    assert p["events"][1].flags == EV_ALL_DAY
    assert p["events"][3].flags == EV_TENTATIVE | EV_HAS_LOCATION and p["events"][3].location == "123 Main St"

    t = decode_payload(vector_truncation())
    assert len(t["events"][0].title.encode()) == 63 and t["events"][0].title == "會" * 21

    oh, op = vector_overflow()
    o = decode_payload(op)
    assert len(op) <= MAX_PAYLOAD and len(o["events"]) == 29 and o["omitted"] == 11
    assert decode_header(oh)["chunk_count"] == 4
    assert b"".join(chunk(op, i) for i in range(4)) == op

    # eventCount cap: 300 untitled events are 6 B each and would fit by size alone.
    c = decode_payload(Payload(GENERATED, DAY0, 2, [Event(i, i) for i in range(300)]).encode())
    assert len(c["events"]) == 255 and c["omitted"] == 45

    # end < start is repaired, not rejected.
    r = decode_payload(Payload(GENERATED, DAY0, 1, [Event(600, 500, "bad")]).encode())
    assert r["events"][0].start_min == 600 and r["events"][0].end_min == 600

    # Text rules shared with the Kotlin/C++ tests.
    for raw, want in SANITIZE_CASES:
        got = sanitize_text(raw)
        assert got == want, (raw, got, want)

    # Tie-break on encoded title bytes (code-point order), independent of input order.
    tied = [Event(60, 120, "b"), Event(60, 120, "é"), Event(60, 120, "a"), Event(60, 120, "B")]
    order = [e.title for e in decode_payload(Payload(GENERATED, DAY0, 1, tied).encode())["events"]]
    assert order == ["B", "a", "b", "é"], order

    n = decode_header(vector_no_permission())
    assert n["flags"] == HDR_NO_CALENDAR_PERMISSION and n["payload_len"] == 0 and n["crc"] == 0

    s = encode_status(87, crc32(payload), 12, ST_CLOCK_ADJUSTED, "1.6.5-dev-calendar-93e98bb7")
    ds = decode_status(s)
    assert ds["battery"] == 87 and ds["fw"] == "1.6.5-dev-calendar-93e98bb7" and len(s) <= 64

    # Decoder must reject corruption.
    for bad in (payload[:-1], payload + b"\x00", b"\x02" + payload[1:]):
        try:
            decode_payload(bad)
        except (ProtocolError, UnicodeDecodeError):
            pass
        else:
            raise AssertionError("corrupt payload accepted")


if __name__ == "__main__":
    _self_test()
    header, payload = vector_basic()
    oh, op = vector_overflow()
    status = encode_status(87, crc32(payload), 12, ST_CLOCK_ADJUSTED, "1.6.5-dev-calendar-93e98bb7")
    print("self-test OK\n")
    print("basic.header  =", header.hex())
    print("basic.payload =", payload.hex())
    print("basic.crc32   = %08x" % crc32(payload))
    print("trunc.payload =", vector_truncation().hex())
    print("overflow.header =", oh.hex())
    print("overflow.payload.len = %d, crc32 = %08x" % (len(op), crc32(op)))
    print("noperm.header =", vector_no_permission().hex())
    print("status        =", status.hex())
