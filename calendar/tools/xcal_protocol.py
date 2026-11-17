#!/usr/bin/env python3
"""Reference codec for the XCAL BLE protocol v1 (docs/ble-protocol.md).

Executable spec: encoders/decoders for HEADER, PAYLOAD and STATUS plus the
phone-side text and ordering rules. `python xcal_protocol.py` runs the
self-tests and prints the shared test vectors that the Android app and the X3
firmware unit tests must reproduce byte for byte.
"""

from __future__ import annotations

import struct
import zlib
import unicodedata
import zlib
from dataclasses import dataclass, field

PROTO_MAJOR = 1
PROTO_MINOR = 1  # 1: pinned image (spec 4.4)
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
MAX_FW = 46  # keeps STATUS (with the v1.1 image fields) <= 64 B

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

IMG_PINNED = 0x01  # HEADER imageFlags
IMAGE_W, IMAGE_H = 528, 792  # X3 screen, portrait
IMAGE_WINDOW = 512  # IMAGE_DATA window
MAX_IMAGE_BMP = 110_000
GRAY_LEVELS = (0, 85, 170, 255)  # the X3's native grays

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


# Combining marks used only as decoration (strike/underline/overline, "frame"
# and swirl effects); dropped like pictographs.
DECORATIVE_MARK_RANGES = [
    (0x0305, 0x0305), (0x0332, 0x0338), (0x033F, 0x033F), (0x0488, 0x0489),
    (0x1AB0, 0x1AFF), (0x20D0, 0x20F0), (0xFE20, 0xFE2F),
]


def is_decorative_mark(cp: int) -> bool:
    return any(lo <= cp <= hi for lo, hi in DECORATIVE_MARK_RANGES)


# What the X3 can draw in titles and locations: the intersection of its
# NotoSans 11 regular/bold and Ubuntu 12 glyph sets (firmware builtinFonts).
DRAWABLE_RANGES = [
    (0x0020, 0x007E), (0x00A0, 0x017F), (0x01A0, 0x01A1), (0x01AF, 0x01B0),
    (0x01C4, 0x021F), (0x0300, 0x036F), (0x0400, 0x04FF), (0x1EA0, 0x1EF9),
    (0x2000, 0x2064), (0x2066, 0x2071), (0x2074, 0x208E), (0x2090, 0x209C),
    (0x20A0, 0x20C0), (0x2212, 0x2212), (0xFB00, 0xFB06), (0xFFFD, 0xFFFD),
]


def is_drawable(cp: int) -> bool:
    return any(lo <= cp <= hi for lo, hi in DRAWABLE_RANGES)


def _pairs(table: str) -> dict[int, str]:
    """"Xy Zw ..." -> {X: "y", Z: "w"}; a lone key maps to "" (deleted)."""
    return {ord(item[0]): item[1:] for item in table.split()}


# "Fancy text" generator lookalikes -> Latin, applied to a whole text only
# when every character the X3 cannot draw is one of these keys (so genuine
# Greek, Thai, CJK... text, which uses many other letters, is left alone).
# Drawable keys (Cyrillic, a few Latin) are only rewritten in such texts.
LOOKALIKES = _pairs(
    # Canadian syllabics / runic ("ᗯᗝᖇᛕ")
    "ᗩA ᗅA ᗷB ᑕC ᗪD ᗴE ᖴF ᘜG ᕼH ᒍJ ᛕK ᒪL ᗰM ᑎN ᗝO ᑭP ᑫQ ᖇR ᔕS ᑌU ᐯV ᗯW ᙭X ᘔZ "
    # CJK ("山ㄖ尺ズ")
    "卂A 乃B 匚C 乇E 千F Ꮆg 卄H 丨I ﾌJ ズK ㄥL 爪M 几N ㄖO 卩P Ɋq 尺R 丂S ㄒT ㄩU 山W 乂X ㄚY 乙Z "
    # Thai and friends ("ฬ๏гк")
    "คa ๒b ςc ๔d єe Ŧf ﻮg ђh เi ןj кk ɭl ๓m ภn ๏o קp ợq гr รs Շt ยu שv ฬw אx ץy չz "
    # Greek
    "αa βB γy δd εe ζz ηn ιi κk νv οo πn ρp σo τt υu χx ωw "
    "ΑA ΒB ΕE ΖZ ΗH ΙI ΚK ΜM ΝN ΟO ΡP ΤT ΥY ΧX "
    # Cyrillic
    "аa вb дd еe ѕs зz иu йu кk лn мm нh оo пn рp сc тt уy хx шw щw ъb ьb яr ёe іi їi јj ћh ҝk ԝw "
    "АA ВB ЕE ЗE КK МM НH ОO РP СC ТT УY ХX ШW ЩW ЯR ЬB ЪB "
    # decorative brackets ("『w』『o』")
    "『 』 「 」 【 】 〖 〗 〔 〕 《 》"
)

# Upside-down text ("ʞɔoꞁq ʞɹoʍ") is also written in reverse: decoded by
# reversing the text, then mapping these keys (plus the ASCII swaps below).
FLIPPED = _pairs(
    "ɐa ɔc ǝe ɟf ƃg ɥh ᴉi ıi ɾj ʞk ꞁl ɯm ɹr ʇt ʌv ʍw ʎy "
    "∀A ꓭB ƆC ꓷD ƎE ℲF ⅁G ꓘK ˥L ԀP ꓤR ⊥T ∩U ΛV ⅄Y ¿? ¡! ˙."
)
FLIPPED_ASCII = _pairs("bq qb dp pd nu un MW WM")


def decode_lookalikes(text: str) -> str:
    """Rewrites fancy-text lookalikes the X3 cannot draw back to Latin."""
    undrawable = {ord(c) for c in text if not is_drawable(ord(c))}
    if not undrawable:
        return text
    if undrawable <= FLIPPED.keys():
        out = []
        for c in reversed(text):
            cp = ord(c)
            out.append(FLIPPED.get(cp, FLIPPED_ASCII.get(cp, c)))
        decoded = "".join(out)
    elif undrawable <= LOOKALIKES.keys():
        decoded = "".join(LOOKALIKES.get(ord(c), c) for c in text)
    else:
        return text
    # Deleted brackets can leave empty words.
    return " ".join(w for w in decoded.split(" ") if w)


def sanitize_text(text: str | None) -> str:
    """NFKC, fold styled letters, drop pictographs and decorative marks,
    controls -> space, split on White_Space runs, rejoin with one space, then
    decode fancy-text lookalikes the X3 cannot draw."""
    if not text:
        return ""
    words, cur = [], []
    for c in unicodedata.normalize("NFKC", text):
        c = fold_letter(ord(c)) or c
        if is_pictograph(ord(c)) or is_decorative_mark(ord(c)):
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
    return decode_lookalikes(" ".join(words))


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
                  no_calendar_permission: bool = False, image: bytes | None = None) -> bytes:
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
    head = struct.pack("<BBBBHIIhB", PROTO_MAJOR, PROTO_MINOR, flags, chunks, plen, crc, now_utc,
                       utc_offset_min, len(tz)) + tz
    if image:
        return head + struct.pack("<BII", IMG_PINNED, len(image), crc32(image))
    return head + struct.pack("<BII", 0, 0, 0)


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
    out = {"minor": minor, "flags": flags, "chunk_count": chunks, "payload_len": plen, "crc": crc,
           "now_utc": now, "utc_offset_min": off, "tz": tz, "image_flags": 0, "image_len": 0, "image_crc": 0}
    # v1.1 image fields; absent in v1.0. Further trailing bytes are allowed.
    at = HEADER_FIXED_LEN + tzlen
    if len(data) >= at + 9:
        out["image_flags"], out["image_len"], out["image_crc"] = struct.unpack_from("<BII", data, at)
    return out


def chunk(payload: bytes, index: int) -> bytes:
    return payload[index * CHUNK_SIZE : (index + 1) * CHUNK_SIZE]


# --- Status ------------------------------------------------------------------


def encode_status(battery: int, shown_crc: int, wake_count: int, flags: int, fw: str,
                  shown_image_crc: int = 0, dismissed_image_crc: int = 0) -> bytes:
    fwb = fw.encode("ascii")[:MAX_FW]
    return struct.pack("<BBIHBB", STATUS_VERSION, battery, shown_crc, min(wake_count, 0xFFFF), flags,
                       len(fwb)) + fwb + struct.pack("<II", shown_image_crc, dismissed_image_crc)


def decode_status(data: bytes) -> dict | None:
    if len(data) < 10 or data[0] != STATUS_VERSION:
        return None
    ver, bat, crc, wakes, flags, fwlen = struct.unpack_from("<BBIHBB", data, 0)
    if 10 + fwlen > len(data):
        return None
    out = {"battery": bat, "shown_crc": crc, "wake_count": wakes, "flags": flags,
           "fw": data[10 : 10 + fwlen].decode("ascii", "replace"),
           "shown_image_crc": 0, "dismissed_image_crc": 0}
    if len(data) >= 18 + fwlen:  # v1.1
        out["shown_image_crc"], out["dismissed_image_crc"] = struct.unpack_from("<II", data, 10 + fwlen)
    return out


# --- Pinned image (spec 4.4) -------------------------------------------------


def encode_bmp(levels: list[list[int]], bpp: int) -> bytes:
    """BMP for the X3: rows of gray levels (0..1 for 1 bpp, 0..3 for 2 bpp, 0 = black)."""
    if bpp not in (1, 2):
        raise ProtocolError("bpp must be 1 or 2")
    height, width = len(levels), len(levels[0])
    palette = b"".join(bytes((v, v, v, 0)) for v in ((0, 255) if bpp == 1 else GRAY_LEVELS))
    row_bytes = (width * bpp + 31) // 32 * 4
    rows = []
    for row in reversed(levels):  # bottom-up
        out = bytearray()
        bits = nbits = 0
        for v in row:
            bits = (bits << bpp) | v
            nbits += bpp
            if nbits == 8:
                out.append(bits)
                bits = nbits = 0
        if nbits:
            out.append(bits << (8 - nbits))
        rows.append(bytes(out).ljust(row_bytes, b"\x00"))
    pixels = b"".join(rows)
    off = 14 + 40 + len(palette)
    file_header = struct.pack("<2sIHHI", b"BM", off + len(pixels), 0, 0, off)
    info = struct.pack("<IiiHHIIiiII", 40, width, height, 1, bpp, 0, len(pixels), 2835, 2835,
                       len(palette) // 4, 0)
    return file_header + info + palette + pixels


def check_image_bmp(bmp: bytes) -> int:
    """Validates a spec 4.4 BMP; returns its bpp."""
    if len(bmp) > MAX_IMAGE_BMP or len(bmp) < 62 or bmp[:2] != b"BM":
        raise ProtocolError("not a BMP")
    _, w, h, planes, bpp, comp = struct.unpack_from("<IiiHHI", bmp, 14)
    if (w, h) != (IMAGE_W, IMAGE_H) or bpp not in (1, 2) or comp != 0 or planes != 1:
        raise ProtocolError(f"unsupported image {w}x{h} {bpp} bpp")
    return bpp


def encode_image_stream(bmp: bytes) -> bytes:
    check_image_bmp(bmp)
    return zlib.compress(bmp, 9)


def decode_image_stream(stream: bytes, image_len: int, image_crc: int) -> bytes:
    if len(stream) != image_len or crc32(stream) != image_crc:
        raise ProtocolError("image length/CRC mismatch")
    bmp = zlib.decompress(stream)
    check_image_bmp(bmp)
    return bmp


def image_window(stream: bytes, offset: int) -> bytes:
    """IMAGE_DATA value after IMAGE_OFFSET = offset."""
    return stream[offset : offset + IMAGE_WINDOW]


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
    # Fancy-text lookalikes the X3 cannot draw (decode_lookalikes)
    ("ʞɔoꞁq ʞɹoʍ", "work block"),  # upside down, reversed
    ("山ㄖ尺ズ 乃ㄥㄖ匚ズ", "WORK BLOCK"),
    ("ฬ๏гк ๒ɭ๏ςк", "work block"),
    ("ᗯᗝᖇᛕ ᗷᒪᗝᑕᛕ", "WORK BLOCK"),
    ("Щöяк βłöск", "Wörk Błöck"),
    ("『w』『o』『r』『k』 『b』", "work b"),
    ("w҉o҉r҉k҉ ҉b҉", "work b"),  # decorative marks
    ("s̶t̶r̶i̶k̶e̶", "strike"),
    ("ᵂᵒʳᵏ ᴮˡᵒᶜᵏ", "Work Block"),  # NFKC
    # Genuine text is left alone, drawable or not
    ("Встреча с командой", "Встреча с командой"),
    ("Καλημέρα ομάδα", "Καλημέρα ομάδα"),
    ("東京 会議", "東京 会議"),
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


def vector_image_bmp(bpp: int) -> bytes:
    """Deterministic spec 4.4 test image: 8 px checker blocks over a level ramp."""
    top = (1 << bpp) - 1
    rows = []
    for y in range(IMAGE_H):
        band = min(top, y * (top + 1) // IMAGE_H)
        rows.append([band if (x // 8 + y // 8) % 2 else top - band for x in range(IMAGE_W)])
    return encode_bmp(rows, bpp)


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
    assert len(encode_status(0, 0, 0, 0, "x" * 100)) == 64  # longest STATUS
    v10 = s[:-8]  # a v1.0 STATUS (no image fields) still decodes
    assert decode_status(v10)["dismissed_image_crc"] == 0 and decode_status(v10)["fw"] == ds["fw"]
    si = decode_status(encode_status(50, 1, 2, 0, "fw", shown_image_crc=0xAABBCCDD, dismissed_image_crc=7))
    assert si["shown_image_crc"] == 0xAABBCCDD and si["dismissed_image_crc"] == 7

    # Pinned image: BMP layout, stream round trip, windows, header fields.
    bmp1, bmp2 = vector_image_bmp(1), vector_image_bmp(2)
    assert len(bmp1) == 53918 and len(bmp2) == 104614
    assert check_image_bmp(bmp1) == 1 and check_image_bmp(bmp2) == 2
    stream = encode_image_stream(bmp2)
    assert decode_image_stream(stream, len(stream), crc32(stream)) == bmp2
    windows = [image_window(stream, o) for o in range(0, len(stream), IMAGE_WINDOW)]
    assert b"".join(windows) == stream and image_window(stream, len(stream)) == b""
    hi = decode_header(encode_header(payload, NOW, -420, TZ_LA, image=stream))
    assert hi["image_flags"] == IMG_PINNED and hi["image_len"] == len(stream)
    assert hi["image_crc"] == crc32(stream)
    assert decode_header(header)["image_flags"] == 0
    assert len(encode_header(payload, NOW, -420, "x" * 48)) == 74  # longest HEADER
    assert decode_header(header[:-9])["image_len"] == 0  # a v1.0 header still decodes
    try:
        decode_image_stream(stream, len(stream), crc32(stream) ^ 1)
    except ProtocolError:
        pass
    else:
        raise AssertionError("bad image CRC accepted")

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
    print("image1.bmp crc32 = %08x (%d B)" % (crc32(vector_image_bmp(1)), len(vector_image_bmp(1))))
    print("image2.bmp crc32 = %08x (%d B)" % (crc32(vector_image_bmp(2)), len(vector_image_bmp(2))))
