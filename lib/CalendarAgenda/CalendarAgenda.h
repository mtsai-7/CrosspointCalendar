#pragma once

#include <cstddef>
#include <cstdint>

// Parsing and formatting for the XCAL agenda PAYLOAD (calendar/docs/ble-protocol.md
// §4.2). Pure C++ with no Arduino dependencies so it can be host-tested against
// the spec's test vectors.
namespace calendar {

constexpr uint8_t EV_ALL_DAY = 0x01;
constexpr uint8_t EV_TENTATIVE = 0x02;
constexpr uint8_t EV_HAS_LOCATION = 0x04;

constexpr int MINUTES_PER_DAY = 1440;
constexpr size_t MAX_PAYLOAD = 2048;

// One event, pointing into the payload buffer (not NUL-terminated).
struct AgendaEvent {
  uint8_t flags = 0;
  int16_t startMin = 0;  // local wall-clock minutes relative to day0 00:00
  int16_t endMin = 0;
  const char* title = nullptr;
  uint8_t titleLen = 0;
  const char* location = nullptr;
  uint8_t locationLen = 0;

  bool allDay() const { return (flags & EV_ALL_DAY) != 0; }
  bool tentative() const { return (flags & EV_TENTATIVE) != 0; }
};

// A validated view over a PAYLOAD buffer, which must outlive it.
class Agenda {
 public:
  // Validates the whole payload (spec §4.2 "Rules (X3)"): version, lengths,
  // event records consuming exactly `len` bytes. False leaves the view empty.
  bool parse(const uint8_t* data, size_t len);

  bool valid() const { return data_ != nullptr; }
  uint32_t generatedUtc() const { return generatedUtc_; }
  uint16_t day0() const { return day0_; }  // days since 1970-01-01
  uint8_t dayCount() const { return dayCount_; }
  uint8_t eventCount() const { return eventCount_; }
  uint8_t omittedCount() const { return omittedCount_; }

  // Local day index of `epochDay` relative to day0, or -1 if the payload does
  // not cover that day (stale agenda).
  int dayIndexOf(int32_t epochDay) const;

  // Sequential access to the events in payload order.
  class Iterator {
   public:
    bool next(AgendaEvent& out);

   private:
    friend class Agenda;
    const uint8_t* pos_ = nullptr;
    const uint8_t* end_ = nullptr;
    uint8_t remaining_ = 0;
  };
  Iterator events() const;

 private:
  const uint8_t* data_ = nullptr;
  size_t len_ = 0;
  uint32_t generatedUtc_ = 0;
  uint16_t day0_ = 0;
  uint8_t dayCount_ = 0;
  uint8_t eventCount_ = 0;
  uint8_t omittedCount_ = 0;
};

// True if the event overlaps local day `dayIndex` (0 = day0). Zero-length
// events count when they start inside the day.
bool overlapsDay(const AgendaEvent& event, int dayIndex);

// True if the event starts on local day `dayIndex` (a "next day" section lists
// only these, so events continuing from the day before aren't repeated).
bool startsOnDay(const AgendaEvent& event, int dayIndex);

// True unless the event ended more than `keepPastMin` minutes before `nowMin`
// (both on the day0-relative minute scale). In-progress, upcoming and all-day
// events of the current day are always kept.
bool isCurrentOrUpcoming(const AgendaEvent& event, int nowMin, int keepPastMin);

// Formats the event's time range as seen on day `dayIndex`, e.g. "09:00 - 09:30"
// or "9:00 AM - 9:30 AM". A start before that day or an end after it is shown
// as "..."; a zero-length event shows only its start. All-day events are the
// caller's job (translated label). Returns false if `size` is too small.
bool formatTimeRange(const AgendaEvent& event, int dayIndex, bool use12Hour, char* out, size_t size);

// Start and end times as seen on day `dayIndex`, for layouts that stack them
// ("..." outside the day, same rules as formatTimeRange). `endOut` is left
// empty for zero-length events. Each buffer needs >= 12 bytes.
bool formatEventTimes(const AgendaEvent& event, int dayIndex, bool use12Hour, char* startOut, size_t startSize,
                      char* endOut, size_t endSize);

// Days since 1970-01-01 for a proleptic Gregorian date (month 1-12).
int32_t daysFromCivil(int year, unsigned month, unsigned day);

// CRC-32/ISO-HDLC (zlib, java.util.zip.CRC32).
uint32_t crc32(const uint8_t* data, size_t len);

}  // namespace calendar
