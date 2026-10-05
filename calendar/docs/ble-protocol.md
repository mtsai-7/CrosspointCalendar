# XCAL BLE Protocol — v1.0 (draft)

Transport between the **phone app** (Android, GATT server / BLE peripheral) and the
**Xteink X3 firmware** (GATT client / BLE central) for the calendar sleep screen.

Status: draft, reviewed 2026-09-29. Phone side written (`android/`, codec
unit-tested against §12); X3 side not started.
Reference codec and test vectors: [`tools/xcal_protocol.py`](../tools/xcal_protocol.py).

**Review log (2026-09-29).** Changes made after the first draft:
- Cross-language determinism: explicit White_Space set for text cleanup; title
  tie-break on encoded UTF-8 bytes (Kotlin compares UTF-16 by default, which
  orders supplementary characters differently); `eventCount` capped at 255
  (6-byte untitled events could otherwise exceed a u8 within 2048 B).
- Long writes: STATUS can exceed MTU − 3 at the default MTU; phone must accept
  prepared writes, X3 shortens `fwVersion`.
- Chunk reads past `chunkCount` defined (empty); header re-read defines the
  retry path; service discovery limited to the XCAL service.
- Security: IRK distribution made explicit; privacy note on the constant UUID;
  Android pairing prompt may be a notification.
- Timezone: `utcOffsetMin` demoted to informational; boot-order note for the
  firmware (Settings zone applied first, phone rule must be re-applied).
- Risk register (§11) with the bonded-RPA reconnect ranked first, backed by
  two NimBLE issue reports, and a concrete Plan B (Appendix A).
- Test vectors added (§12), hand-decoded once against the tables.

## 1. Goals and non-goals

Goals
- Once an hour the X3 wakes, fetches **today's and tomorrow's agenda** plus the
  **current time and timezone rule**, and goes back to sleep — radio on for a few
  seconds at most.
- Calendar contents are only readable by the user's own bonded X3.
- The phone side is cheap enough to run as a permanent foreground service.
- Both implementations can be tested against shared byte-exact test vectors.

Non-goals (v1)
- Images, pushing data to the X3 at arbitrary times (the X3 always initiates),
  multiple phones per X3, editing calendars from the X3, event details beyond
  title/location.

## 2. Roles and radio behaviour

| | Phone (Android app) | X3 (firmware) |
|---|---|---|
| GAP role | Peripheral, connectable legacy advertising | Central |
| GATT role | Server | Client |
| Initiates | never | every sync |

### 2.1 Advertising (phone)
- Legacy, connectable, scannable-undirected advertising, running continuously
  while the app's foreground service runs.
- AD payload: **only** the Complete List of 128-bit Service UUIDs containing the
  XCAL service UUID (Android adds Flags). 3 + 18 = 21 bytes ≤ 31. No device name
  (privacy, and it would not fit reliably).
- Android `AdvertiseSettings`: `ADVERTISE_MODE_LOW_POWER` (~1 s interval),
  `ADVERTISE_TX_POWER_MEDIUM`, connectable, no timeout.
- The phone uses a resolvable private address that rotates; the X3 never
  identifies the phone by address, only by service UUID + bond.

### 2.2 Scanning and connection (X3)
- Passive scan, filtered on the XCAL service UUID, **max 8 s total** (≥ 7
  advertising events at ~1 s). Stop scanning at the first match; if that peer
  fails security (e.g. another phone running the app), resume scanning within
  the remaining budget, skipping addresses already tried.
- Connect (timeout 5 s). Request ATT MTU **247**. The protocol works at any MTU
  ≥ 23 (long reads), only slower.
- Immediately start security (see §5). Every characteristic requires an
  encrypted, authenticated (MITM) link.
- One sync session should complete in ≤ 15 s wall time, hard-capped; on timeout
  the X3 disconnects and treats the sync as failed.
- Transient failures (no advertiser found, connect timeout, service missing,
  read failed) are retried **twice**, 3 s apart, within the same wake. Security
  failures are not retried (§5 handles them). Observed on hardware: the phone's
  advertising occasionally goes unseen for a few minutes, and a connect can
  time out right after a successful scan.

## 3. GATT database

Base UUID `c588xxxx-361f-49c4-9e41-fc31c01fab0c`.

| Name | UUID | Properties | Permission | Max length |
|---|---|---|---|---|
| XCAL service | `c5880001-361f-49c4-9e41-fc31c01fab0c` | primary service | — | — |
| HEADER | `c5880002-361f-49c4-9e41-fc31c01fab0c` | Read | Read, encrypted + MITM | 65 B |
| CHUNK0 | `c5880010-361f-49c4-9e41-fc31c01fab0c` | Read | Read, encrypted + MITM | 512 B |
| CHUNK1 | `c5880011-361f-49c4-9e41-fc31c01fab0c` | Read | Read, encrypted + MITM | 512 B |
| CHUNK2 | `c5880012-361f-49c4-9e41-fc31c01fab0c` | Read | Read, encrypted + MITM | 512 B |
| CHUNK3 | `c5880013-361f-49c4-9e41-fc31c01fab0c` | Read | Read, encrypted + MITM | 512 B |
| STATUS | `c5880020-361f-49c4-9e41-fc31c01fab0c` | Write (with response) | Write, encrypted + MITM | 64 B |

- 512 B is the ATT maximum attribute value length, hence the fixed chunk
  characteristics: the **payload** (§4.2) is at most 4 × 512 = **2048 B** and is
  split across CHUNK0..CHUNK3 in order.
- Reads use ATT Read and Read Blob (long reads). The server must honour the
  `offset` of every read request: return `value[offset:]` (the stack truncates
  to MTU − 1), `GATT_INVALID_OFFSET` if `offset > len(value)`, and an empty value
  if `offset == len(value)`. CHUNKn with `n >= chunkCount` reads as empty.
- STATUS writes may arrive as a single Write Request or as Prepare/Execute
  Write (long write) when the value exceeds MTU − 3; the phone must accept both.
  The X3 should keep STATUS ≤ MTU − 3 by shortening `fwVersion`.
- The X3 discovers only the XCAL service by UUID (no full discovery, no GATT
  caching needed).
- All multi-byte integers are **little-endian**. Strings are **UTF-8**, not
  NUL-terminated, length-prefixed.

### 3.1 Session snapshot (consistency rule)
The phone may rebuild the payload at any time (calendar edits, midnight). To
keep one session self-consistent:

- When the server receives a HEADER read with `offset == 0` from a device, it
  builds the header bytes **once** (including `nowUtc`) and **pins** that header
  and the current payload snapshot to the connection (keyed by
  `BluetoothDevice`).
- All further HEADER (offset > 0) and CHUNKn reads on that connection are
  served from the pinned snapshot. The pin is released on disconnect.
- A CHUNKn read before any HEADER read on a connection pins the current snapshot
  too (defensive; the X3 always reads HEADER first).

The payload CRC (below) is still verified by the X3 as an integrity check. A
new HEADER read at `offset == 0` on the same connection re-pins (fresh time and
latest payload); the X3 uses this for its single retry after a CRC failure.
Implementation note: the phone builds payload snapshots as immutable byte
arrays and swaps the "current" reference atomically.

## 4. Data formats

### 4.1 HEADER (17 + tzLen bytes, ≤ 65)

| Off | Size | Field | Notes |
|---|---|---|---|
| 0 | 1 | `protoMajor` | `1`. X3 rejects any other major version. |
| 1 | 1 | `protoMinor` | `0`. Minor bumps are backward compatible (fields only appended). |
| 2 | 1 | `flags` | bit0 `PAYLOAD_VALID`, bit1 `NO_CALENDAR_PERMISSION`, bits 2–7 reserved (0; ignore on read) |
| 3 | 1 | `chunkCount` | 0..4 = ceil(payloadLen / 512) |
| 4 | 2 | `payloadLen` | 0..2048 |
| 6 | 4 | `payloadCrc32` | CRC-32/ISO-HDLC (zlib/`java.util.zip.CRC32`) over the payload bytes; 0 if `payloadLen == 0` |
| 10 | 4 | `nowUtc` | phone time, seconds since 1970-01-01T00:00Z (uint32, valid to 2106), sampled when the pin is created |
| 14 | 2 | `utcOffsetMin` | int16, phone's current UTC offset in minutes (e.g. −420 for PDT) |
| 16 | 1 | `tzLen` | 0..48; 0 = no timezone rule supplied |
| 17 | tzLen | `tzPosix` | ASCII POSIX TZ rule for the phone's zone (§6) |

`PAYLOAD_VALID` is clear when the phone has no agenda to offer (e.g. calendar
permission missing); `chunkCount`/`payloadLen`/`payloadCrc32` are then 0 and the
X3 keeps its cached agenda. `NO_CALENDAR_PERMISSION` lets the X3 show a hint.

### 4.2 PAYLOAD (10 + events, ≤ 2048 bytes)

| Off | Size | Field | Notes |
|---|---|---|---|
| 0 | 1 | `payloadVersion` | `1` |
| 1 | 4 | `generatedUtc` | when the phone built this snapshot (seconds, uint32) |
| 5 | 2 | `day0` | local date of the first day, days since 1970-01-01 (uint16) |
| 7 | 1 | `dayCount` | days covered starting at `day0`; phone sends **2** (today, tomorrow); 1..7 |
| 8 | 1 | `eventCount` | events that follow |
| 9 | 1 | `omittedCount` | events in the window left out because of the 2048 B limit (saturates at 255) |
| 10 | … | events | `eventCount` × EVENT |

EVENT

| Size | Field | Notes |
|---|---|---|
| 1 | `flags` | bit0 `ALL_DAY`, bit1 `TENTATIVE`, bit2 `HAS_LOCATION`, bits 3–7 reserved (0) |
| 2 | `startMin` | int16, **local wall-clock minutes** relative to `day0` 00:00 (`dayIndex*1440 + hh*60 + mm`); may be negative |
| 2 | `endMin` | int16, same scale, `endMin >= startMin` |
| 1 | `titleLen` | 0..64 |
| titleLen | `title` | UTF-8; 0 bytes = untitled (X3 shows its own placeholder) |
| 1 | `locLen` | only if `HAS_LOCATION`; 1..48 |
| locLen | `location` | UTF-8 |

Rules (phone):
- Window = local days `[day0, day0 + dayCount)`. Include every non-excluded
  instance that **overlaps** the window, clamping `startMin`/`endMin` to
  `[-20160, (dayCount + 14) * 1440]` (±14 days) so they fit int16.
- All-day events: `ALL_DAY` set, `startMin`/`endMin` are multiples of 1440
  (local midnights, end exclusive), computed from the event's calendar dates —
  not from UTC millis.
- Exclude: instances with event status *canceled*, instances the user
  *declined*, and calendars that are not visible. `TENTATIVE` = the user's
  attendee status is tentative or the event status is tentative.
- Bad source data with `end < start` is encoded with `endMin = startMin`.
- Text (must be byte-identical across implementations):
  0. Normalize to **NFKC**, so styled letters (𝐁𝐨𝐥𝐝, 𝓼𝓬𝓻𝓲𝓹𝓽, fullwidth,
     circled) become plain characters the X3's Latin fonts can draw. Then
     fold styled letters NFKC leaves alone: small capitals ᴀ ʙ ᴄ ᴅ ᴇ ꜰ ɢ ʜ ɪ ᴊ
     ᴋ ʟ ᴍ ɴ ᴏ ᴘ ꞯ ʀ ꜱ ᴛ ᴜ ᴠ ᴡ ʏ ᴢ → a–z (`SMALL_CAPS` in the script), and
     U+1F150–U+1F169 / U+1F170–U+1F189 (negative circled / squared) → A–Z.
  1. Replace each control character U+0000–U+001F and U+007F with U+0020.
     Drop (remove, not replace) emoji and pictographic symbols the X3 cannot
     draw: U+200D, U+20E3, U+FE00–U+FE0F, U+E0020–U+E007F (sequence glue),
     U+2190–U+21FF, U+2300–U+23FF, U+25A0–U+27BF, U+2900–U+297F,
     U+2B00–U+2BFF and U+1F000–U+1FAFF.
  2. Split on runs of **Unicode White_Space** characters — exactly U+0009–U+000D,
     U+0020, U+0085, U+00A0, U+1680, U+2000–U+200A, U+2028, U+2029, U+202F,
     U+205F, U+3000 — drop empty pieces, join with a single U+0020.
  3. UTF-8 encode and truncate to the byte limit **on a code-point boundary**
     (title 64 B, location 48 B). Empty location ⇒ `HAS_LOCATION` clear.
- Order: by `startMin` ascending; on ties all-day before timed; then `endMin`
  ascending; then the encoded (sanitized, truncated) title bytes compared as
  unsigned bytes, lexicographically (= code-point order); remaining ties keep
  input order (stable sort).
- Size: append events in that order while the payload stays ≤ 2048 B **and**
  `eventCount` ≤ 255; count the rest in `omittedCount` (saturating at 255).

Rules (X3):
- Reject the payload if `payloadVersion != 1`, the length or CRC mismatch the
  header, any length runs past the end, or `eventCount` records don't consume
  exactly `payloadLen` bytes.
- Local "now" in minutes relative to `day0` =
  `(localDate(now) - day0) * 1440 + hh*60 + mm` using the X3's own clock and
  TZ rule. If `localDate(now)` is outside `[day0, day0 + dayCount)`, the agenda
  is stale: show a "no agenda for today" state with the last sync time.
  (The two-day window also covers the midnight sync racing the phone's own
  date change: a payload built for "yesterday" still contains today.)
- If `omittedCount > 0`, show "+N more" after the last event that fits.
- Untitled events (`titleLen == 0`) get a translated placeholder on the X3.

### 4.3 STATUS (written by X3, 10 + fwLen bytes, ≤ 64)

| Off | Size | Field | Notes |
|---|---|---|---|
| 0 | 1 | `statusVersion` | `1` |
| 1 | 1 | `batteryPercent` | 0..100, 255 = unknown |
| 2 | 4 | `shownCrc32` | CRC of the payload the X3 is displaying (0 = none) |
| 6 | 2 | `wakeCount` | timer wakes since the X3's last power-on (saturating) |
| 8 | 1 | `flags` | bit0 `CLOCK_ADJUSTED` (this sync corrected the RTC), bit1 `TZ_APPLIED`, rest reserved |
| 9 | 1 | `fwLen` | 0..54 |
| 10 | fwLen | `fwVersion` | ASCII firmware version string |

The phone shows battery / last sync in its notification. Writes with an unknown
`statusVersion` are acknowledged and ignored.

## 5. Security and pairing

- Link requirements: LE Secure Connections, MITM protection, bonding. Every
  characteristic is `…_ENCRYPTED_MITM` on the phone, so an unbonded or
  Just-Works-bonded client gets *Insufficient Authentication* and cannot read.
- **Pairing is user-initiated on the X3** (awake, Settings → Calendar → Pair
  phone) while the phone app is running:
  1. X3 scans/connects as in §2.2 and starts pairing with IO capability
     **DisplayOnly**, SC + MITM + bond.
  2. Passkey Entry: the X3 shows a 6-digit passkey; Android shows its system
     pairing dialog (on some versions first as a "Pairing request"
     notification the user taps) and the user types the passkey. Key
     distribution must include the **identity key (IRK)** both ways, so the X3
     can resolve the phone's rotating private address later.
  3. On success the X3 reads HEADER once to confirm, stores the bond (NimBLE,
     NVS) and shows "Paired".
- Timer-wake syncs never pair: if the link cannot be encrypted with a stored
  bond, the X3 disconnects and treats the sync as failed (it may try the next
  scan result within the 8 s budget).
- "Forget phone" on the X3 deletes its bond(s); the user also removes the X3
  from Android's Bluetooth settings.
- The app never exposes calendar data over any other channel; no data is sent
  off the phone.
- Privacy trade-off: the phone advertises a constant 128-bit UUID, so a nearby
  scanner can tell "a phone running XCAL is here" even though the address
  rotates. It reveals nothing about calendar contents. Acceptable for a
  personal device; a later version could advertise only during a short window
  around `:00`.

## 6. Time and timezone

- `nowUtc` and `utcOffsetMin` reflect the phone clock (network-synced).
- `tzPosix` is a POSIX TZ string generated from the phone's `ZoneId`
  (`java.time.zone.ZoneRules`), e.g. `STD8DST,M3.2.0,M11.1.0` for
  America/Los_Angeles. Names are always the placeholders `STD`/`DST` (valid
  POSIX, never displayed). Fixed-offset zones have no DST part, e.g.
  `STD-5:30` for Asia/Kolkata. The DST offset is written explicitly only when it
  is not standard + 1 h.
- X3 handling on each successful HEADER read:
  - If `|nowUtc − rtcUtc| > 2 s`, set the DS3231 to `nowUtc` (plus half the
    measured read round-trip, optional).
  - If `tzLen > 0` and it differs from the stored rule: apply with
    `HalClock::setTimezone()` and persist it in the calendar cache. The X3 keeps
    using the last received rule when the phone is absent, so DST changes are
    handled offline.
- If `tzLen == 0` the X3 uses its own Settings → Clock timezone.
  `utcOffsetMin` is informational (logging, diagnostics); the X3 never derives
  local time from it.
- Firmware note: CrossPoint applies the Settings timezone at boot
  (`timezones::applyToClock()`); the calendar path must re-apply the cached
  phone rule afterwards. The awake UI's status-bar clock keeps using the
  Settings zone — the pairing screen should suggest picking the matching zone.

## 7. X3 sync procedure (timer wake)

1. Wake at local `hh:00:05`. Init NimBLE (only on this path and in the pairing UI).
2. Scan ≤ 8 s; connect; MTU 247; secure with stored bond (§5).
3. Read HEADER; check `protoMajor == 1`.
4. Apply time/timezone (§6).
5. If `PAYLOAD_VALID` is clear, keep the cache (note `NO_CALENDAR_PERMISSION`
   for display) and skip to 7. If `payloadCrc32` equals the cached payload's
   CRC, skip to 7. Otherwise read CHUNK0..CHUNK(chunkCount−1), concatenate,
   verify length and CRC, parse (§4.2), and persist to the SD cache.
6. On a CRC/length/parse failure: re-read HEADER once (re-pins, §3.1) and
   repeat step 5; if it fails again keep the previous cache (never apply a
   partial payload).
7. Write STATUS.
8. Disconnect; deinit NimBLE to release its heap.
9. Render from cache (updates the "now" marker even when nothing changed),
   show "synced HH:MM" of the last successful sync, deep-sleep to next `:00:05`.

Failures (no phone found, security failure, timeout, bad CRC): render from
cache, mark stale, retry **once** 5 min later; after that, back to hourly.

## 8. Phone service behaviour

- Foreground service (`connectedDevice` type) with a persistent notification;
  starts on app launch and on boot.
- Payload rebuild triggers: calendar provider change (`ContentObserver` on
  `CalendarContract.CONTENT_URI`, descendants), local date change, time/timezone
  change broadcasts. Rebuilds are debounced (~2 s). A HEADER read also rebuilds
  lazily if the snapshot's `day0` is not today.
- Build cost is a single `CalendarContract.Instances` query for the window
  (±1 day margin, filtered in code).
- Advertising restarts automatically if Bluetooth is toggled.

## 9. Size and power budget

- Typical day: 10 events × (6 + ~25 B title) ≈ 320 B → one chunk, one or two
  reads at MTU 247. Worst case: 4 chunks ≈ 9 reads.
- X3 radio time per hourly sync: scan ~1 s typical + ~1 s connected; with the
  phone away, three 8 s scans plus 6 s of retry delay (radio idle). At
  ~80–100 mA while scanning/connected this is ≈ 0.05 mAh per sync (≈ 0.7 mAh
  when the phone is away) → ~1–17 mAh/day, small next to deep-sleep drain (to
  be measured).
- Phone: low-power advertising + idle GATT server; expected negligible, to be
  measured on the target phone.

## 10. Versioning

- `protoMajor` changes on incompatible changes to the GATT layout, HEADER, or
  security model. `protoMinor` changes add fields to the end of HEADER/STATUS;
  readers must ignore unknown trailing bytes.
- `payloadVersion` versions the PAYLOAD encoding independently.

## 11. Risks and hardware verification (ranked)

The first firmware BLE milestone should be a **spike that retires R1–R3**
before building the agenda UI: pair once, then reconnect on 3+ consecutive
timer wakes with the phone's address rotating in between (toggle phone
Bluetooth off/on to force a new address).

| # | Risk | Why it matters | Mitigation / fallback |
|---|---|---|---|
| R1 | **Bonded reconnect to a rotating (RPA) phone address.** NimBLE must resolve the phone's RPA to its identity (IRK in the controller resolving list or host-based resolution) to find the LTK. Related reports: [esp-nimble #10](https://github.com/espressif/esp-nimble/issues/10) (client re-dials the ID address — we avoid this by connecting to the scanned address), [NimBLE-Arduino #1187](https://github.com/h2zero/NimBLE-Arduino/issues/1187) (inspecting scan results broke bonded reconnect security). | Every hourly sync depends on it. | **Retired 2026-09-30** (spike, NimBLE-Arduino 2.5.1 on the X3, Android 16 phone): after the phone rotated its RPA, the X3 resolved it to the bonded identity, reconnected, and read HEADER + payload over the stored bond (~4 s per sync). Needed: ID-key distribution; **host-based privacy on** (`setOwnAddrType(BLE_OWN_ADDR_RPA_PUBLIC_DEFAULT)`) — once a bond holds the IRK, NimBLE rewrites scan results to the identity address, and only with host privacy does `ble_gap_connect()` map it back to the current RPA (otherwise connects time out). See §13 for the other pitfalls. Appendix A no longer needed. |
| R2 | Android shows a passkey-entry prompt when a **remote central** starts Passkey Entry pairing with the phone as peripheral. | Needed once, at setup. | **Retired 2026-09-30:** Android showed its PIN-entry prompt when the X3 (DisplayOnly) started SC + MITM pairing; the passkey drawn on the X3 bonded both sides. |
| R3 | `PERMISSION_READ_ENCRYPTED_MITM` on the Android GATT server answers an unbonded read with *Insufficient Authentication* (not silently). | Confidentiality of plan A. | **Retired 2026-09-29:** unpaired read of HEADER from a Windows PC (bleak) against an Android 16 phone was refused with ATT 0x05 *Insufficient Authentication*; advertising seen from an RPA. |
| R4 | NimBLE heap/flash on ESP32-C3: timer-wake path (no UI loaded) is fine; the pairing screen runs inside the reader UI (~135 KB free, 115 KB largest block). Flash headroom ~930 KB. | OOM in pairing UI; image too big. | **Open.** Timer-wake path fine (NimBLE up costs ~47 KB, all returned on deinit). Flash +221 KB (≈700 KB headroom left). But normal reading boots now show ~114 KB free / 61 KB largest block vs 155 KB / 115 KB on stock 1.6.5 — investigate whether the controller-RAM release takes effect. |
| R5 | Firmware compiled Bluetooth out: `lib_ignore = BLE` also drops the ESP-IDF `bt` component in the `custom_sdkconfig` core build. | No BLE at all. | **Done:** calendar envs clear `lib_ignore`, build the core **controller-only** (`CONFIG_BT_CONTROLLER_ONLY=y`, `CONFIG_BT_NIMBLE_ENABLED=n`, `CONFIG_BLE_MESH=n`) because NimBLE-Arduino brings its own host (§13). |
| R6 | ASUS/Android background limits kill the service or advertising. | Missed syncs. | Foreground service + battery-optimisation exemption; the app surfaces "last served X3 at HH:MM". |

## 12. Test vectors

Generated by `python tools/xcal_protocol.py` (which also runs the reference
self-tests). Implementations must reproduce these bytes exactly. Inputs are in
`vector_basic()` etc.: `day0` = 20725 (2026-09-29), `generatedUtc` =
1790665200, `nowUtc` = 1790690400, `utcOffsetMin` = −420,
`tzPosix` = `STD8DST,M3.2.0,M11.1.0`.

```
basic.header    0100010184008efdf9c960c4bb6a5cfe16535444384453542c4d332e322e302c4d31312e312e30
basic.payload   01f061bb6af5500205000088ff3c00114c617465206e69676874206465706c6f79010000a0050f
                4d61726b2773206269727468646179041c023a02075374616e647570045a6f6f6d060c03480317
                44656e7469737420e280942044722e204dc3bc6c6c65720b313233204d61696e20537400f80734
                080d546f6d6f72726f773a20313a31
basic.crc32     c9f9fd8e
trunc.payload   01f061bb6af55002010000580294023fe69c83…(21 × e69c83)   title cut at 63 B
overflow.header 01000104f8070088644460c4bb6a5cfe16535444384453542c4d332e322e302c4d31312e312e30
overflow        40 events → 29 included, omittedCount 11, payloadLen 2040, crc32 44648800
noperm.header   0100020000000000000060c4bb6a5cfe16535444384453542c4d332e322e302c4d31312e312e30
status          01578efdf9c90c00011b312e362e352d6465762d63616c656e6461722d3933653938626237
```

Text-rule cases (`SANITIZE_CASES` in the script) cover NBSP, zero-width space
(kept), CR/LF, NUL/DEL, U+3000 and U+0085.

POSIX TZ expectations for the Android generator (§6):

| Zone | Expected `tzPosix` |
|---|---|
| America/Los_Angeles | `STD8DST,M3.2.0,M11.1.0` |
| Europe/Berlin | `STD-1DST,M3.5.0,M10.5.0/3` |
| Australia/Sydney | `STD-10DST,M10.1.0,M4.1.0/3` |
| Europe/Dublin | `STD0DST,M3.5.0/1,M10.5.0` |
| Asia/Kolkata | `STD-5:30` |
| UTC | `STD0` |
| Australia/Lord_Howe | `STD-10:30DST-11,M10.1.0,M4.1.0` |

## 13. Implementation notes from the hardware spike (2026-09-30)

X3 firmware: NimBLE-Arduino 2.5.1 on arduino-esp32 3.3 (pioarduino); phone:
Android 16. Each item below was a real failure seen in the logs.

1. **One NimBLE host only.** Arduino 3.3's C3 core is built with the ESP-IDF
   NimBLE host (`CONFIG_BT_NIMBLE_ENABLED=y`) and NimBLE-Arduino always compiles
   its own → duplicate `ble_*` symbols at link. Build the core controller-only
   (+ `CONFIG_BLE_MESH=n`, which needs a host). With no host in the core,
   Arduino's `esp32-hal-bt.c` compiles out, so the firmware must define
   `_bleLibraryInUse` itself and do the boot-time controller-RAM release.
2. **Stay connected after a new bond.** Android keeps "bonding" open while it
   runs its own post-bond discovery; a disconnect within ~1 s makes it delete
   the new bond ("ACL DISCONNECTED during Bonding"). The X3 stays connected
   10 s after pairing.
3. **Host privacy is required for reconnects** (R1 above).
4. **Peer-initiated encryption.** A bonded phone starts encryption itself right
   after the connection. NimBLE-Arduino 2.5.1's `secureConnection()` then gets
   `BLE_HS_EALREADY`, counts it as started and waits forever
   (`BLE_NPL_TIME_FOREVER`) for an event that already fired. The X3 waits up to
   2 s for `isEncrypted()` and only calls `secureConnection()` if needed.
5. **Stale bonds.** If the phone lost its keys (it answers encryption by
   requesting pairing, or reports PIN/key missing), the X3 deletes its bond so
   the next wake re-pairs; other security errors keep the bond.
6. **No unbounded waits.** Several NimBLE-Arduino calls block without a
   timeout, so each X3 run has a watchdog (60 s sync / 90 s pairing) that
   restarts the chip; the restart completes the wake without BLE.

## Appendix A — Plan B: app-layer encryption (not in v1 unless R1 fails)

If bonded LE reconnection proves unreliable, keep the GATT layout and framing
but move confidentiality to the application layer:

- **Setup (once):** the X3 pairs with Passkey Entry exactly as in §5 and reads a
  new `KEY` characteristic (encrypted + MITM) holding a random 128-bit key `K`
  generated by the phone. The X3 stores `K` in NVS. After that neither side
  needs the LE bond.
- **Sessions:** characteristics become plain-readable. The phone encrypts each
  pinned snapshot `HEADER ‖ PAYLOAD` with AES-128-CCM under `K` (nonce = 8-byte
  random + 4-byte counter, 8-byte tag), exposed through the same HEADER/CHUNK
  characteristics with a new `protoMajor = 2`. The X3 decrypts with mbedTLS
  (built into ESP-IDF) and rejects snapshots whose `nowUtc` is older than the
  last accepted one (replay).
- STATUS is AES-CCM-encrypted the same way.
- Cost: a key-exchange characteristic, ~40 lines of crypto per side; removes
  R1 and R3 from the steady state.
