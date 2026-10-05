#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>

// SD-card cache of the last verified agenda PAYLOAD, so the calendar sleep
// screen can redraw every hour (and after power loss) without the phone.
// File: /.crosspoint/calendar.bin = "XCA1", syncedUtc u32, crc32 u32, len u16,
// payload (little-endian).
namespace CalendarCache {

struct Loaded {
  std::unique_ptr<uint8_t[]> payload;
  size_t len = 0;
  uint32_t crc = 0;
  uint32_t syncedUtc = 0;  // phone time of the sync that stored this payload
};

// Stores a verified payload (written to a temp file, then renamed).
bool save(const uint8_t* payload, size_t len, uint32_t crc, uint32_t syncedUtc);

// CRC of the cached payload without reading it, or 0 if there is none.
uint32_t storedCrc();

// Loads and re-verifies the cached payload; empty result if missing/corrupt.
Loaded load();

}  // namespace CalendarCache
