#pragma once

#include <cstdint>
#include <ctime>

class GfxRenderer;

// X3 side of the XCAL BLE protocol (calendar/docs/ble-protocol.md): fetches the
// agenda header/payload plus time and timezone from the phone app.
//
// SPIKE: when no phone is bonded, a timer wake runs pairing (passkey shown on
// the e-ink screen) instead of the spec's Settings -> Pair phone flow. This
// exists to verify spec risks R1/R2 on hardware before building the real UI.
namespace CalendarBle {

enum class Result : uint8_t {
  None = 0,
  Ok,          // synced (header read, time/timezone applied)
  Paired,      // new bond created and verified with a HEADER read
  InitFailed,  // NimBLE could not start
  NotFound,    // no XCAL advertiser within the scan window
  ConnectFailed,
  UnknownPeer,     // connected, but the address did not resolve to our bond (spec R1)
  SecurityFailed,  // encryption / pairing failed
  NoService,
  ReadFailed,
  BadHeader,
  BadPayload,  // chunk length/CRC mismatch
  Hung,        // the run overran its watchdog and the chip was restarted
};

// Outcome of the last run; kept in RTC memory so the sleep screen can show it.
struct Status {
  Result result;
  int16_t error;        // NimBLE error code where relevant
  uint16_t payloadLen;  // from HEADER
  uint8_t eventCount;   // from the last verified payload
  uint8_t chunksRead;   // 0 when the cached CRC matched
  int32_t clockDelta;   // phone time - RTC time before correction (s)
  uint16_t durationMs;  // radio-on time for the whole run
  uint32_t payloadCrc;
  time_t at;  // RTC time when the run finished (UTC)
};

// True once after a restart forced by the BLE watchdog (a NimBLE call blocked
// past its deadline). The caller should redraw the calendar and go back to
// sleep without BLE; the result is recorded as Result::Hung.
bool consumeWatchdogRestart();

// Returns the Bluetooth controller's reserved RAM to the heap for boots that
// won't use BLE (everything but calendar timer wakes). Call early in setup().
// Afterwards BLE cannot start until the next reboot.
void releaseMemoryUnlessNeeded(bool bleNeeded);

// Re-applies the phone's POSIX TZ rule from the last sync (CrossPoint applies
// the Settings zone at boot). No-op until a sync has provided one.
void restoreTimezone();

// One timer-wake run: sync with the bonded phone, or (spike) pair if there is
// no bond yet. Renders the pairing screen itself when pairing.
const Status& run(GfxRenderer& renderer);

const Status& last();
const char* resultName(Result result);

}  // namespace CalendarBle
