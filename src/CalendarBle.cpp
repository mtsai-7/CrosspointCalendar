#include "CalendarBle.h"

#include <Arduino.h>
#include <CalendarAgenda.h>
#include <GfxRenderer.h>
#include <HalClock.h>
#include <HalDisplay.h>
#include <HalPowerManager.h>
#include <I18n.h>
#include <Logging.h>
#include <Memory.h>
#include <NimBLEDevice.h>
#include <esp_attr.h>
#include <esp_bt.h>
#include <esp_heap_caps.h>
#include <esp_random.h>
#include <esp_timer.h>

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <cstring>

#include "CalendarCache.h"
#include "fontIds.h"

// Arduino's esp32-hal-bt.c defines this flag (and reclaims controller RAM at
// boot when no BLE library sets it), but only when the core has a Bluetooth
// host. Our core is controller-only (NimBLE-Arduino brings the host), so that
// file compiles out while NimBLE-Arduino's esp32-hal-alloc-ble-mem.h still
// sets the flag. Define it here; releaseMemoryUnlessNeeded() does the reclaim.
extern "C" {
bool _bleLibraryInUse = false;
}

namespace CalendarBle {
namespace {

// UUIDs and limits from calendar/docs/ble-protocol.md §3-4.
const char* const SERVICE_UUID = "c5880001-361f-49c4-9e41-fc31c01fab0c";
const char* const HEADER_UUID = "c5880002-361f-49c4-9e41-fc31c01fab0c";
const char* const CHUNK_UUIDS[4] = {"c5880010-361f-49c4-9e41-fc31c01fab0c", "c5880011-361f-49c4-9e41-fc31c01fab0c",
                                    "c5880012-361f-49c4-9e41-fc31c01fab0c", "c5880013-361f-49c4-9e41-fc31c01fab0c"};
const char* const STATUS_UUID = "c5880020-361f-49c4-9e41-fc31c01fab0c";

constexpr uint8_t PROTO_MAJOR = 1;
constexpr size_t HEADER_FIXED_LEN = 17;
constexpr size_t MAX_TZ_LEN = 48;
constexpr size_t CHUNK_SIZE = 512;
constexpr size_t MAX_PAYLOAD = 4 * CHUNK_SIZE;
constexpr uint8_t HDR_PAYLOAD_VALID = 0x01;
constexpr uint8_t ST_CLOCK_ADJUSTED = 0x01;
constexpr uint8_t ST_TZ_APPLIED = 0x02;

constexpr uint32_t SCAN_MS = 8000;  // spec §2.2
constexpr uint32_t CONNECT_TIMEOUT_MS = 5000;
// A sync wake retries transient failures (phone not found, connect timeout,
// failed read) this many times, RETRY_DELAY_MS apart; see retryable().
constexpr int SYNC_RETRIES = 2;
constexpr uint32_t RETRY_DELAY_MS = 3000;
// After a new bond, Android keeps "bonding" open while it runs its own
// discovery on the new device; if the link drops within ~1 s it deletes the
// bond ("ACL DISCONNECTED during Bonding"). Stay connected a while.
constexpr uint32_t PAIRING_LINGER_MS = 10000;
// A bonded phone often starts encryption itself right after the connection.
// NimBLE-Arduino 2.5's secureConnection() then gets BLE_HS_EALREADY, treats it
// as started, and waits forever for an event that already happened — so give
// the phone's encryption this long and skip secureConnection() if it's done.
constexpr uint32_t PEER_ENCRYPT_WAIT_MS = 2000;
// Hard cap on one run (several NimBLE-Arduino calls wait without a timeout).
// Sync: up to three attempts of scan (8.5 s) + connect (5 s) + encryption wait
// (2 s) + reads, plus the retry delays.
constexpr uint64_t WATCHDOG_SYNC_US = 100ULL * 1000 * 1000;
constexpr uint64_t WATCHDOG_PAIRING_US = 90ULL * 1000 * 1000;
constexpr uint32_t WATCHDOG_MAGIC = 0x58434C57;  // "XCLW"
constexpr int32_t CLOCK_TOLERANCE_S = 2;         // spec §6

// RTC slow memory: survives deep sleep, zeroed on power-on.
RTC_DATA_ATTR Status lastStatus;
RTC_DATA_ATTR char phoneTz[MAX_TZ_LEN + 1];
RTC_DATA_ATTR uint32_t shownCrc;
// Phone time of the last successful sync (0 until one succeeds since power-on;
// the SD cache keeps the time its payload was stored as a fallback).
RTC_DATA_ATTR uint32_t lastSyncUtc;
RTC_DATA_ATTR uint16_t syncCount;
// Set just before a watchdog restart; RTC slow memory survives esp_restart().
RTC_DATA_ATTR uint32_t watchdogMagic;

esp_timer_handle_t watchdogTimer = nullptr;

void onWatchdog(void*) {
  // Runs in the esp_timer task while the main task is stuck inside NimBLE.
  watchdogMagic = WATCHDOG_MAGIC;
  LOG_ERR("BLE", "Run exceeded its deadline; restarting");
  delay(50);
  esp_restart();
}

void armWatchdog(const uint64_t timeoutUs) {
  if (!watchdogTimer) {
    const esp_timer_create_args_t args = {.callback = &onWatchdog,
                                          .arg = nullptr,
                                          .dispatch_method = ESP_TIMER_TASK,
                                          .name = "ble-wdt",
                                          .skip_unhandled_events = true};
    if (esp_timer_create(&args, &watchdogTimer) != ESP_OK) return;
  }
  esp_timer_start_once(watchdogTimer, timeoutUs);
}

void disarmWatchdog() {
  if (watchdogTimer) esp_timer_stop(watchdogTimer);
}

// Outcome of the boot-time controller RAM release, logged later from the main
// loop: the release runs before USB CDC is up, so its own log line is lost.
esp_err_t releaseErr = ESP_OK;
bool releaseAttempted = false;
size_t releaseTotalBefore = 0;
size_t releaseTotalAfter = 0;

// Scan/pairing state shared with NimBLE callbacks (host task).
volatile bool found = false;
NimBLEAddress foundAddr;
volatile bool pairingAllowed = false;
volatile bool unexpectedPairing = false;
uint32_t passkey = 0;

class ScanCallbacks : public NimBLEScanCallbacks {
  void onResult(const NimBLEAdvertisedDevice* dev) override {
    // Keep this cheap (runs in the host task); the caller stops the scan.
    if (found || !dev || !dev->isAdvertisingService(NimBLEUUID(SERVICE_UUID))) return;
    foundAddr = dev->getAddress();
    found = true;
  }
};

class ClientCallbacks : public NimBLEClientCallbacks {
  uint32_t onPassKeyDisplay(NimBLEConnInfo& info) override {
    if (!pairingAllowed) {
      // A timer-wake sync must never pair (spec §5): drop the link instead of
      // letting the phone show a pairing prompt.
      unexpectedPairing = true;
      if (NimBLEClient* c = NimBLEDevice::getClientByHandle(info.getConnHandle())) c->disconnect();
    }
    return passkey;
  }
  void onConfirmPasskey(NimBLEConnInfo& info, uint32_t) override {
    NimBLEDevice::injectConfirmPasskey(info, pairingAllowed);
  }
};

ScanCallbacks scanCallbacks;
ClientCallbacks clientCallbacks;

uint16_t rd16(const uint8_t* p) { return static_cast<uint16_t>(p[0] | (p[1] << 8)); }
uint32_t rd32(const uint8_t* p) {
  return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) | (static_cast<uint32_t>(p[2]) << 16) |
         (static_cast<uint32_t>(p[3]) << 24);
}

bool bleBegin() {
  if (NimBLEDevice::isInitialized()) {
    NimBLEDevice::deinit(true);
    delay(20);
  }
  if (!NimBLEDevice::init("")) return false;
  // Host-based privacy (spec R1). Once a bond holds the phone's IRK, NimBLE
  // rewrites every scan result from the phone's rotating private address to
  // its identity address (ble_hs_hci_evt.c), but ble_gap_connect() maps that
  // identity back to the current private address only while host privacy is
  // enabled. Without this, reconnects dial the identity address and time out.
  if (!NimBLEDevice::setOwnAddrType(BLE_OWN_ADDR_RPA_PUBLIC_DEFAULT)) {
    LOG_ERR("BLE", "Could not enable host-based privacy");
  }
  // LE Secure Connections + MITM + bonding, X3 displays the passkey (spec §5).
  // Distribute identity keys both ways so the phone's rotating private address
  // can be resolved to its bond on later wakes (spec R1).
  NimBLEDevice::setSecurityAuth(/*bonding=*/true, /*mitm=*/true, /*sc=*/true);
  NimBLEDevice::setSecurityIOCap(BLE_HS_IO_DISPLAY_ONLY);
  NimBLEDevice::setSecurityInitKey(BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID);
  NimBLEDevice::setSecurityRespKey(BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID);
  NimBLEDevice::setMTU(247);
  return true;
}

// Delete the client only once DISCONNECTED: a deferred delete leaks NimBLE's
// client slot across deinit (see freeink-sdk BleKeyboardHost::end).
void releaseClient(NimBLEClient*& client) {
  if (!client) return;
  if (client->isConnected()) client->disconnect();
  for (int i = 0; i < 60 && client->isConnected(); ++i) delay(10);
  delay(150);
  NimBLEDevice::deleteClient(client);
  client = nullptr;
}

void bleEnd(NimBLEClient*& client) {
  NimBLEScan* scan = NimBLEDevice::getScan();
  if (scan && scan->isScanning()) scan->stop();
  releaseClient(client);
  NimBLEDevice::deinit(true);
}

bool scanForPhone() {
  found = false;
  NimBLEScan* scan = NimBLEDevice::getScan();
  scan->setScanCallbacks(&scanCallbacks, /*wantDuplicates=*/false);
  scan->setActiveScan(false);  // the UUID is in the advertisement itself
  scan->setInterval(100);
  scan->setWindow(100);
  scan->setMaxResults(0);  // don't store results (NimBLE-Arduino #1187 workaround)
  if (!scan->start(SCAN_MS, false, true)) return false;
  const uint32_t start = millis();
  while (!found && millis() - start < SCAN_MS + 500) delay(20);
  scan->stop();
  return found;
}

void renderPairingScreen(GfxRenderer& r, uint32_t code) {
  r.setOrientation(GfxRenderer::Orientation::Portrait);
  r.clearScreen();
  const int h = r.getScreenHeight();
  char digits[8];
  snprintf(digits, sizeof(digits), "%03lu %03lu", static_cast<unsigned long>(code / 1000),
           static_cast<unsigned long>(code % 1000));
  r.drawCenteredText(UI_12_FONT_ID, h / 2 - 90, tr(STR_CAL_PAIR_TITLE), true, EpdFontFamily::BOLD);
  r.drawCenteredText(NOTOSANS_18_FONT_ID, h / 2 - 30, digits, true, EpdFontFamily::BOLD);
  r.drawCenteredText(UI_12_FONT_ID, h / 2 + 30, tr(STR_CAL_PAIR_HINT));
  r.displayBuffer(HalDisplay::HALF_REFRESH);
}

// Reads HEADER (+ chunks when the payload changed), applies time and timezone,
// writes STATUS. Assumes an encrypted link. Fills everything but duration/at.
void syncSession(NimBLEClient* client, Status& st) {
  NimBLERemoteService* svc = client->getService(NimBLEUUID(SERVICE_UUID));
  if (!svc) {
    st.result = Result::NoService;
    return;
  }
  NimBLERemoteCharacteristic* hdrChr = svc->getCharacteristic(NimBLEUUID(HEADER_UUID));
  if (!hdrChr) {
    st.result = Result::NoService;
    return;
  }
  const NimBLEAttValue hdr = hdrChr->readValue();
  if (hdr.size() < HEADER_FIXED_LEN) {
    st.result = hdr.size() == 0 ? Result::ReadFailed : Result::BadHeader;
    st.error = static_cast<int16_t>(client->getLastError());
    return;
  }
  const uint8_t* h = hdr.data();
  const uint8_t flags = h[2];
  const uint8_t chunkCount = h[3];
  const uint16_t payloadLen = rd16(h + 4);
  const uint32_t payloadCrc = rd32(h + 6);
  const uint32_t nowUtc = rd32(h + 10);
  const uint8_t tzLen = h[16];
  if (h[0] != PROTO_MAJOR || tzLen > MAX_TZ_LEN || HEADER_FIXED_LEN + tzLen > hdr.size() || payloadLen > MAX_PAYLOAD ||
      chunkCount != (payloadLen + CHUNK_SIZE - 1) / CHUNK_SIZE) {
    st.result = Result::BadHeader;
    return;
  }
  st.payloadLen = payloadLen;
  st.payloadCrc = payloadCrc;
  uint8_t statusFlags = 0;

  // Time (spec §6): correct the DS3231 when it is off by more than 2 s.
  time_t rtcNow = 0;
  if (halClock.utcEpoch(rtcNow)) {
    st.clockDelta = static_cast<int32_t>(static_cast<int64_t>(nowUtc) - static_cast<int64_t>(rtcNow));
  }
  if (std::abs(st.clockDelta) > CLOCK_TOLERANCE_S || rtcNow == 0) {
    if (halClock.setUtcEpoch(static_cast<time_t>(nowUtc))) statusFlags |= ST_CLOCK_ADJUSTED;
  }
  if (tzLen > 0) {
    char tz[MAX_TZ_LEN + 1];
    memcpy(tz, h + HEADER_FIXED_LEN, tzLen);
    tz[tzLen] = '\0';
    if (strcmp(tz, phoneTz) != 0) {
      memcpy(phoneTz, tz, tzLen + 1);
      statusFlags |= ST_TZ_APPLIED;
    }
    halClock.setTimezone(phoneTz);
  }

  // Payload (spec §7 step 5): only when it differs from the cached one (the
  // SD cache is what the sleep screen draws, so it is the source of truth).
  shownCrc = CalendarCache::storedCrc();
  if ((flags & HDR_PAYLOAD_VALID) && payloadCrc != shownCrc) {
    // Heap, not .bss: only calendar wakes need it, and a static buffer would
    // cost every reading session 2 KB. Freed when this scope ends.
    const auto payloadBuf = makeUniqueNoThrow<uint8_t[]>(MAX_PAYLOAD);
    if (!payloadBuf) {
      LOG_ERR("BLE", "OOM for %u-byte payload buffer", static_cast<unsigned>(MAX_PAYLOAD));
      st.result = Result::BadPayload;
      return;
    }
    size_t got = 0;
    for (uint8_t i = 0; i < chunkCount; i++) {
      NimBLERemoteCharacteristic* chunk = svc->getCharacteristic(NimBLEUUID(CHUNK_UUIDS[i]));
      const NimBLEAttValue v = chunk ? chunk->readValue() : NimBLEAttValue();
      if (v.size() == 0 || got + v.size() > MAX_PAYLOAD) break;
      memcpy(payloadBuf.get() + got, v.data(), v.size());
      got += v.size();
      st.chunksRead++;
    }
    calendar::Agenda agenda;
    if (got != payloadLen || calendar::crc32(payloadBuf.get(), got) != payloadCrc ||
        !agenda.parse(payloadBuf.get(), got)) {
      LOG_ERR("BLE", "Payload rejected: got %u of %u bytes", static_cast<unsigned>(got), payloadLen);
      st.result = Result::BadPayload;
      return;
    }
    if (!CalendarCache::save(payloadBuf.get(), got, payloadCrc, nowUtc)) {
      LOG_ERR("BLE", "Could not store the agenda on the SD card");
    }
    shownCrc = payloadCrc;
    st.eventCount = agenda.eventCount();
  } else if (payloadCrc == shownCrc) {
    st.eventCount = lastStatus.eventCount;
  }
  lastSyncUtc = nowUtc;

  // STATUS write-back (spec §4.3), kept within one ATT write at MTU 247.
  NimBLERemoteCharacteristic* statusChr = svc->getCharacteristic(NimBLEUUID(STATUS_UUID));
  if (statusChr) {
    uint8_t buf[64];
    const char* fw = CROSSPOINT_VERSION;
    const size_t mtuRoom = client->getMTU() > 13 ? client->getMTU() - 3 : 10;
    const size_t fwLen = std::min(strlen(fw), std::min<size_t>(54, mtuRoom - 10));
    const uint16_t battery = powerManager.getBatteryPercentage();
    const uint16_t wakes = syncCount;
    buf[0] = 1;
    buf[1] = static_cast<uint8_t>(battery <= 100 ? battery : 255);
    buf[2] = shownCrc & 0xFF;
    buf[3] = (shownCrc >> 8) & 0xFF;
    buf[4] = (shownCrc >> 16) & 0xFF;
    buf[5] = (shownCrc >> 24) & 0xFF;
    buf[6] = wakes & 0xFF;
    buf[7] = wakes >> 8;
    buf[8] = statusFlags;
    buf[9] = static_cast<uint8_t>(fwLen);
    memcpy(buf + 10, fw, fwLen);
    if (!statusChr->writeValue(buf, 10 + fwLen, true)) LOG_ERR("BLE", "STATUS write failed");
  }
  st.result = Result::Ok;
}

}  // namespace

bool consumeWatchdogRestart() {
  if (watchdogMagic != WATCHDOG_MAGIC) return false;
  watchdogMagic = 0;
  Status st{};
  st.result = Result::Hung;
  st.eventCount = lastStatus.eventCount;
  time_t now = 0;
  halClock.utcEpoch(now);
  st.at = now;
  lastStatus = st;
  return true;
}

void releaseMemoryUnlessNeeded(const bool bleNeeded) {
  if (bleNeeded) return;
  releaseAttempted = true;
  releaseTotalBefore = heap_caps_get_total_size(MALLOC_CAP_INTERNAL);
  releaseErr = esp_bt_controller_mem_release(ESP_BT_MODE_BLE);
  releaseTotalAfter = heap_caps_get_total_size(MALLOC_CAP_INTERNAL);
}

void logMemoryReleaseOnce() {
  static bool logged = false;
  if (logged) return;
  logged = true;
  if (!releaseAttempted) {
    LOG_INF("BLE", "Controller RAM kept (BLE boot)");
    return;
  }
  LOG_INF("BLE", "Controller RAM release at boot: err=%d, internal heap total %u -> %u (+%d)", releaseErr,
          static_cast<unsigned>(releaseTotalBefore), static_cast<unsigned>(releaseTotalAfter),
          static_cast<int>(releaseTotalAfter) - static_cast<int>(releaseTotalBefore));
}

void restoreTimezone() {
  if (phoneTz[0] != '\0') halClock.setTimezone(phoneTz);
}

const Status& last() { return lastStatus; }

uint32_t lastSyncedUtc() { return lastSyncUtc; }

const char* resultName(const Result result) {
  switch (result) {
    case Result::None:
      return "none";
    case Result::Ok:
      return "ok";
    case Result::Paired:
      return "paired";
    case Result::InitFailed:
      return "init";
    case Result::NotFound:
      return "notfound";
    case Result::ConnectFailed:
      return "connect";
    case Result::UnknownPeer:
      return "unknownpeer";
    case Result::SecurityFailed:
      return "security";
    case Result::NoService:
      return "noservice";
    case Result::ReadFailed:
      return "read";
    case Result::BadHeader:
      return "header";
    case Result::BadPayload:
      return "payload";
    case Result::Hung:
      return "hung";
  }
  return "?";
}

namespace {

// Failures worth another scan/connect within the same wake: the phone's
// advertising or radio was briefly unavailable. Security failures are not
// retried (they carry the bond-repair logic in attempt()).
bool retryable(const Result r) {
  return r == Result::NotFound || r == Result::ConnectFailed || r == Result::NoService || r == Result::ReadFailed;
}

// One scan -> connect -> secure -> read attempt. Leaves `client` for the
// caller to release.
void attempt(GfxRenderer& renderer, NimBLEClient*& client, Status& st, const bool pairing) {
  st = Status{};
  if (!scanForPhone()) {
    st.result = Result::NotFound;
    return;
  }
  LOG_INF("BLE", "Found phone at %s", foundAddr.toString().c_str());
  if (pairing) {
    passkey = 100000 + esp_random() % 900000;
    NimBLEDevice::setSecurityPasskey(passkey);
    renderPairingScreen(renderer, passkey);
  }
  client = NimBLEDevice::createClient();
  if (client) {
    client->setClientCallbacks(&clientCallbacks, false);
    client->setConnectTimeout(CONNECT_TIMEOUT_MS);
  }
  if (!client || !client->connect(foundAddr)) {
    st.result = Result::ConnectFailed;
    st.error = client ? static_cast<int16_t>(client->getLastError()) : -1;
    return;
  }

  const NimBLEConnInfo info = client->getConnInfo();
  const bool knownPeer = NimBLEDevice::isBonded(info.getIdAddress());
  LOG_INF("BLE", "Connected ota=%s id=%s bonded=%d mtu=%u", info.getAddress().toString().c_str(),
          info.getIdAddress().toString().c_str(), knownPeer ? 1 : 0, client->getMTU());
  bool secured = false;
  if (!pairing && knownPeer) {
    const uint32_t waitStart = millis();
    while (client->isConnected() && !client->getConnInfo().isEncrypted() &&
           millis() - waitStart < PEER_ENCRYPT_WAIT_MS) {
      delay(20);
    }
    secured = client->getConnInfo().isEncrypted();
    LOG_INF("BLE", "Link %s after %lums", secured ? "encrypted by peer" : "not yet encrypted",
            static_cast<unsigned long>(millis() - waitStart));
  }
  if (!pairing && !knownPeer) {
    // Spec R1: the phone's private address did not resolve to our bond.
    st.result = Result::UnknownPeer;
    return;
  }
  if (!(secured || client->secureConnection()) || unexpectedPairing) {
    st.result = Result::SecurityFailed;
    st.error = static_cast<int16_t>(client->getLastError());
    const bool phoneLostKeys = unexpectedPairing || st.error == BLE_HS_HCI_ERR(BLE_ERR_PINKEY_MISSING);
    if (!pairing && phoneLostKeys) {
      // The phone no longer has our keys (bond removed on its side; it
      // asks to pair or reports the key missing): drop ours so the next
      // wake re-pairs instead of failing forever. Other security errors
      // (e.g. radio trouble) keep the bond.
      LOG_ERR("BLE", "Encryption with stored bond failed (err=%d); deleting bond %s", st.error,
              info.getIdAddress().toString().c_str());
      NimBLEDevice::deleteBond(info.getIdAddress());
    }
    return;
  }
  LOG_INF("BLE", "Secured (encrypted=%d bonded=%d), reading", client->getConnInfo().isEncrypted() ? 1 : 0,
          client->getConnInfo().isBonded() ? 1 : 0);
  syncSession(client, st);
  if (pairing && st.result == Result::Ok) {
    st.result = Result::Paired;
    const uint32_t lingerStart = millis();
    while (client->isConnected() && millis() - lingerStart < PAIRING_LINGER_MS) delay(100);
  }
}

}  // namespace

const Status& run(GfxRenderer& renderer) {
  const uint32_t start = millis();
  Status st{};
  st.result = Result::None;
  syncCount++;
  LOG_INF("BLE", "Run #%u, free heap %u", syncCount, static_cast<unsigned>(ESP.getFreeHeap()));
  armWatchdog(WATCHDOG_SYNC_US);

  NimBLEClient* client = nullptr;
  if (!bleBegin()) {
    st.result = Result::InitFailed;
  } else {
    const bool pairing = NimBLEDevice::getNumBonds() == 0;
    if (pairing) {
      disarmWatchdog();
      armWatchdog(WATCHDOG_PAIRING_US);  // the user has to type the passkey
    }
    pairingAllowed = pairing;
    unexpectedPairing = false;
    LOG_INF("BLE", "NimBLE up (bonds=%d, %s), free heap %u", NimBLEDevice::getNumBonds(), pairing ? "pairing" : "sync",
            static_cast<unsigned>(ESP.getFreeHeap()));

    // Pairing shows a passkey for this attempt only, so it is not retried.
    const int attempts = pairing ? 1 : 1 + SYNC_RETRIES;
    for (int i = 0; i < attempts; ++i) {
      if (i > 0) {
        LOG_INF("BLE", "Attempt %d failed (%s, err=%d); retrying in %lus", i, resultName(st.result), st.error,
                static_cast<unsigned long>(RETRY_DELAY_MS / 1000));
        releaseClient(client);
        delay(RETRY_DELAY_MS);
      }
      attempt(renderer, client, st, pairing);
      if (!retryable(st.result)) break;
    }
  }
  bleEnd(client);
  disarmWatchdog();

  st.durationMs = static_cast<uint16_t>(std::min<uint32_t>(millis() - start, 0xFFFF));
  time_t now = 0;
  halClock.utcEpoch(now);
  st.at = now;
  if (st.result != Result::Ok && st.result != Result::Paired) {
    // Keep the last verified payload's numbers visible next to the failure.
    st.eventCount = lastStatus.eventCount;
  }
  lastStatus = st;
  LOG_INF("BLE", "Result %s err=%d len=%u events=%u chunks=%u dClock=%ld %ums, free heap %u", resultName(st.result),
          st.error, st.payloadLen, st.eventCount, st.chunksRead, static_cast<long>(st.clockDelta), st.durationMs,
          static_cast<unsigned>(ESP.getFreeHeap()));
  return lastStatus;
}

}  // namespace CalendarBle
