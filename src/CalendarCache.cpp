#include "CalendarCache.h"

#include <CalendarAgenda.h>
#include <HalStorage.h>
#include <Logging.h>
#include <Memory.h>

#include <cstring>

namespace CalendarCache {
namespace {

constexpr const char* PATH = "/.crosspoint/calendar.bin";
constexpr const char* TMP_PATH = "/.crosspoint/calendar.tmp";
constexpr char MAGIC[4] = {'X', 'C', 'A', '1'};
constexpr size_t HEADER_LEN = 14;

void put16(uint8_t* p, const uint16_t v) {
  p[0] = v & 0xFF;
  p[1] = v >> 8;
}
void put32(uint8_t* p, const uint32_t v) {
  for (int i = 0; i < 4; i++) p[i] = (v >> (8 * i)) & 0xFF;
}
uint16_t get16(const uint8_t* p) { return static_cast<uint16_t>(p[0] | (p[1] << 8)); }
uint32_t get32(const uint8_t* p) {
  return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) | (static_cast<uint32_t>(p[2]) << 16) |
         (static_cast<uint32_t>(p[3]) << 24);
}

// Reads and checks the header; false if missing or not ours.
bool readHeader(HalFile& file, uint32_t& syncedUtc, uint32_t& crc, uint16_t& len) {
  uint8_t h[HEADER_LEN];
  if (file.read(h, HEADER_LEN) != static_cast<int>(HEADER_LEN) || memcmp(h, MAGIC, sizeof(MAGIC)) != 0) return false;
  syncedUtc = get32(h + 4);
  crc = get32(h + 8);
  len = get16(h + 12);
  return len > 0 && len <= calendar::MAX_PAYLOAD;
}

}  // namespace

bool save(const uint8_t* payload, const size_t len, const uint32_t crc, const uint32_t syncedUtc) {
  if (!payload || len == 0 || len > calendar::MAX_PAYLOAD) return false;
  uint8_t h[HEADER_LEN];
  memcpy(h, MAGIC, sizeof(MAGIC));
  put32(h + 4, syncedUtc);
  put32(h + 8, crc);
  put16(h + 12, static_cast<uint16_t>(len));
  {
    HalFile file;
    if (!Storage.openFileForWrite("CAL", TMP_PATH, file)) return false;
    const bool ok = file.write(h, HEADER_LEN) == HEADER_LEN && file.write(payload, len) == len;
    file.close();
    if (!ok) {
      Storage.remove(TMP_PATH);
      return false;
    }
  }
  if (Storage.exists(PATH)) Storage.remove(PATH);
  HalFile tmp;
  if (!Storage.openFileForRead("CAL", TMP_PATH, tmp) || !tmp.rename(PATH)) {
    LOG_ERR("CAL", "Could not move agenda cache into place");
    return false;
  }
  return true;
}

uint32_t storedCrc() {
  HalFile file;
  if (!Storage.openFileForRead("CAL", PATH, file)) return 0;
  uint32_t syncedUtc = 0, crc = 0;
  uint16_t len = 0;
  return readHeader(file, syncedUtc, crc, len) ? crc : 0;
}

Loaded load() {
  Loaded out;
  HalFile file;
  if (!Storage.openFileForRead("CAL", PATH, file)) return out;
  uint32_t syncedUtc = 0, crc = 0;
  uint16_t len = 0;
  if (!readHeader(file, syncedUtc, crc, len)) return out;
  auto buf = makeUniqueNoThrow<uint8_t[]>(len);
  if (!buf) {
    LOG_ERR("CAL", "OOM loading %u-byte agenda cache", len);
    return out;
  }
  if (file.read(buf.get(), len) != static_cast<int>(len) || calendar::crc32(buf.get(), len) != crc) {
    LOG_ERR("CAL", "Agenda cache corrupt, ignoring");
    return out;
  }
  out.payload = std::move(buf);
  out.len = len;
  out.crc = crc;
  out.syncedUtc = syncedUtc;
  return out;
}

}  // namespace CalendarCache
