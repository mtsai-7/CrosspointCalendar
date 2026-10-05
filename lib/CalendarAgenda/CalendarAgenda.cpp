#include "CalendarAgenda.h"

#include <cstdio>

namespace calendar {
namespace {

constexpr uint8_t PAYLOAD_VERSION = 1;
constexpr size_t PAYLOAD_HEADER_LEN = 10;
constexpr size_t EVENT_FIXED_LEN = 6;
constexpr uint8_t MAX_TITLE = 64;
constexpr uint8_t MAX_LOCATION = 48;

uint16_t rd16(const uint8_t* p) { return static_cast<uint16_t>(p[0] | (p[1] << 8)); }
int16_t rds16(const uint8_t* p) { return static_cast<int16_t>(rd16(p)); }
uint32_t rd32(const uint8_t* p) {
  return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) | (static_cast<uint32_t>(p[2]) << 16) |
         (static_cast<uint32_t>(p[3]) << 24);
}

// Decodes one EVENT at `pos`; returns the byte after it, or nullptr if it is
// malformed or runs past `end`.
const uint8_t* decodeEvent(const uint8_t* pos, const uint8_t* end, AgendaEvent& out) {
  if (end - pos < static_cast<ptrdiff_t>(EVENT_FIXED_LEN)) return nullptr;
  out.flags = pos[0];
  out.startMin = rds16(pos + 1);
  out.endMin = rds16(pos + 3);
  out.titleLen = pos[5];
  pos += EVENT_FIXED_LEN;
  if (out.endMin < out.startMin || out.titleLen > MAX_TITLE || end - pos < out.titleLen) return nullptr;
  out.title = reinterpret_cast<const char*>(pos);
  pos += out.titleLen;
  out.location = nullptr;
  out.locationLen = 0;
  if (out.flags & EV_HAS_LOCATION) {
    if (end - pos < 1) return nullptr;
    out.locationLen = pos[0];
    pos += 1;
    if (out.locationLen == 0 || out.locationLen > MAX_LOCATION || end - pos < out.locationLen) return nullptr;
    out.location = reinterpret_cast<const char*>(pos);
    pos += out.locationLen;
  }
  return pos;
}

// Appends "HH:MM" (24 h) or "H:MM AM" (12 h) for minute-of-day `m`.
int formatClock(int m, bool use12Hour, char* out, size_t size) {
  const int hour = m / 60;
  const int minute = m % 60;
  if (!use12Hour) return snprintf(out, size, "%02d:%02d", hour, minute);
  const int hour12 = hour % 12 == 0 ? 12 : hour % 12;
  return snprintf(out, size, "%d:%02d %s", hour12, minute, hour < 12 ? "AM" : "PM");
}

}  // namespace

bool Agenda::parse(const uint8_t* data, const size_t len) {
  *this = Agenda{};
  if (!data || len < PAYLOAD_HEADER_LEN || len > MAX_PAYLOAD || data[0] != PAYLOAD_VERSION) return false;
  const uint8_t dayCount = data[7];
  if (dayCount < 1 || dayCount > 7) return false;
  const uint8_t count = data[8];
  const uint8_t* pos = data + PAYLOAD_HEADER_LEN;
  const uint8_t* end = data + len;
  AgendaEvent ev;
  for (uint8_t i = 0; i < count; i++) {
    pos = decodeEvent(pos, end, ev);
    if (!pos) return false;
  }
  if (pos != end) return false;  // records must consume exactly len bytes

  data_ = data;
  len_ = len;
  generatedUtc_ = rd32(data + 1);
  day0_ = rd16(data + 5);
  dayCount_ = dayCount;
  eventCount_ = count;
  omittedCount_ = data[9];
  return true;
}

int Agenda::dayIndexOf(const int32_t epochDay) const {
  if (!valid()) return -1;
  const int32_t index = epochDay - static_cast<int32_t>(day0_);
  return index >= 0 && index < dayCount_ ? static_cast<int>(index) : -1;
}

Agenda::Iterator Agenda::events() const {
  Iterator it;
  if (valid()) {
    it.pos_ = data_ + PAYLOAD_HEADER_LEN;
    it.end_ = data_ + len_;
    it.remaining_ = eventCount_;
  }
  return it;
}

bool Agenda::Iterator::next(AgendaEvent& out) {
  if (remaining_ == 0 || !pos_) return false;
  pos_ = decodeEvent(pos_, end_, out);  // already validated by parse()
  if (!pos_) {
    remaining_ = 0;
    return false;
  }
  remaining_--;
  return true;
}

bool overlapsDay(const AgendaEvent& event, const int dayIndex) {
  const int dayStart = dayIndex * MINUTES_PER_DAY;
  const int dayEnd = dayStart + MINUTES_PER_DAY;
  if (event.endMin == event.startMin) return event.startMin >= dayStart && event.startMin < dayEnd;
  return event.endMin > dayStart && event.startMin < dayEnd;
}

bool startsOnDay(const AgendaEvent& event, const int dayIndex) {
  const int dayStart = dayIndex * MINUTES_PER_DAY;
  return event.startMin >= dayStart && event.startMin < dayStart + MINUTES_PER_DAY;
}

bool isCurrentOrUpcoming(const AgendaEvent& event, const int nowMin, const int keepPastMin) {
  return event.endMin > nowMin - keepPastMin;
}

bool formatEventTimes(const AgendaEvent& event, const int dayIndex, const bool use12Hour, char* startOut,
                      const size_t startSize, char* endOut, const size_t endSize) {
  if (!startOut || !endOut || startSize < 12 || endSize < 12) return false;
  const int dayStart = dayIndex * MINUTES_PER_DAY;
  const int dayEnd = dayStart + MINUTES_PER_DAY;
  if (event.startMin < dayStart) {
    snprintf(startOut, startSize, "...");
  } else {
    formatClock(event.startMin - dayStart, use12Hour, startOut, startSize);
  }
  if (event.endMin == event.startMin) {
    endOut[0] = '\0';
  } else if (event.endMin > dayEnd) {
    snprintf(endOut, endSize, "...");
  } else {
    // An end at exactly midnight is shown as 00:00 / 12:00 AM.
    formatClock((event.endMin - dayStart) % MINUTES_PER_DAY, use12Hour, endOut, endSize);
  }
  return true;
}

bool formatTimeRange(const AgendaEvent& event, const int dayIndex, const bool use12Hour, char* out, const size_t size) {
  if (!out || size == 0) return false;
  char start[12];
  char end[12];
  formatEventTimes(event, dayIndex, use12Hour, start, sizeof(start), end, sizeof(end));
  const int n = end[0] ? snprintf(out, size, "%s - %s", start, end) : snprintf(out, size, "%s", start);
  return n > 0 && static_cast<size_t>(n) < size;
}

int32_t daysFromCivil(int year, const unsigned month, const unsigned day) {
  year -= month <= 2;
  const int era = (year >= 0 ? year : year - 399) / 400;
  const unsigned yoe = static_cast<unsigned>(year - era * 400);
  const unsigned doy = (153 * (month > 2 ? month - 3 : month + 9) + 2) / 5 + day - 1;
  const unsigned doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
  return static_cast<int32_t>(era) * 146097 + static_cast<int32_t>(doe) - 719468;
}

uint32_t crc32(const uint8_t* data, const size_t len) {
  uint32_t crc = 0xFFFFFFFFu;
  for (size_t i = 0; i < len; i++) {
    crc ^= data[i];
    for (int b = 0; b < 8; b++) crc = (crc >> 1) ^ (0xEDB88320u & (0u - (crc & 1u)));
  }
  return ~crc;
}

}  // namespace calendar
