#include <gtest/gtest.h>

#include <cstring>
#include <string>
#include <vector>

#include "CalendarAgenda.h"

// Vectors from calendar/docs/ble-protocol.md §12 (tools/xcal_protocol.py).
namespace {

std::vector<uint8_t> hex(const std::string& s) {
  std::vector<uint8_t> out;
  for (size_t i = 0; i + 1 < s.size(); i += 2)
    out.push_back(static_cast<uint8_t>(std::stoi(s.substr(i, 2), nullptr, 16)));
  return out;
}

const std::string BASIC =
    "01f061bb6af5500205000088ff3c00114c617465206e69676874206465706c6f79010000a0050f"
    "4d61726b2773206269727468646179041c023a02075374616e647570045a6f6f6d060c03480317"
    "44656e7469737420e280942044722e204dc3bc6c6c65720b313233204d61696e20537400f80734"
    "080d546f6d6f72726f773a20313a31";

std::string str(const char* p, size_t n) { return p ? std::string(p, n) : std::string(); }

std::vector<calendar::AgendaEvent> all(const calendar::Agenda& agenda) {
  std::vector<calendar::AgendaEvent> out;
  auto it = agenda.events();
  calendar::AgendaEvent ev;
  while (it.next(ev)) out.push_back(ev);
  return out;
}

}  // namespace

TEST(CalendarAgenda, ParsesBasicVector) {
  const auto data = hex(BASIC);
  EXPECT_EQ(0xc9f9fd8eu, calendar::crc32(data.data(), data.size()));

  calendar::Agenda agenda;
  ASSERT_TRUE(agenda.parse(data.data(), data.size()));
  EXPECT_EQ(1790665200u, agenda.generatedUtc());
  EXPECT_EQ(20725, agenda.day0());
  EXPECT_EQ(2, agenda.dayCount());
  EXPECT_EQ(5, agenda.eventCount());
  EXPECT_EQ(0, agenda.omittedCount());

  const auto events = all(agenda);
  ASSERT_EQ(5u, events.size());
  EXPECT_EQ("Late night deploy", str(events[0].title, events[0].titleLen));
  EXPECT_EQ(-120, events[0].startMin);
  EXPECT_EQ(60, events[0].endMin);
  EXPECT_TRUE(events[1].allDay());
  EXPECT_EQ("Mark's birthday", str(events[1].title, events[1].titleLen));
  EXPECT_EQ("Standup", str(events[2].title, events[2].titleLen));
  EXPECT_EQ("Zoom", str(events[2].location, events[2].locationLen));
  EXPECT_TRUE(events[3].tentative());
  EXPECT_EQ("Dentist — Dr. Müller", str(events[3].title, events[3].titleLen));
  EXPECT_EQ("123 Main St", str(events[3].location, events[3].locationLen));
  EXPECT_EQ("Tomorrow: 1:1", str(events[4].title, events[4].titleLen));
  EXPECT_EQ(nullptr, events[4].location);
}

TEST(CalendarAgenda, RejectsCorruptPayloads) {
  const auto good = hex(BASIC);
  calendar::Agenda agenda;

  auto truncated = good;
  truncated.pop_back();
  EXPECT_FALSE(agenda.parse(truncated.data(), truncated.size()));

  auto trailing = good;
  trailing.push_back(0);
  EXPECT_FALSE(agenda.parse(trailing.data(), trailing.size()));

  auto badVersion = good;
  badVersion[0] = 2;
  EXPECT_FALSE(agenda.parse(badVersion.data(), badVersion.size()));

  auto badDays = good;
  badDays[7] = 0;
  EXPECT_FALSE(agenda.parse(badDays.data(), badDays.size()));

  EXPECT_FALSE(agenda.parse(nullptr, 0));
  EXPECT_FALSE(agenda.valid());
}

TEST(CalendarAgenda, DayIndexCoversWindowOnly) {
  const auto data = hex(BASIC);
  calendar::Agenda agenda;
  ASSERT_TRUE(agenda.parse(data.data(), data.size()));
  EXPECT_EQ(-1, agenda.dayIndexOf(20724));
  EXPECT_EQ(0, agenda.dayIndexOf(20725));
  EXPECT_EQ(1, agenda.dayIndexOf(20726));
  EXPECT_EQ(-1, agenda.dayIndexOf(20727));
}

TEST(CalendarAgenda, OverlapsDay) {
  calendar::AgendaEvent ev;
  ev.startMin = -120;
  ev.endMin = 60;  // yesterday 22:00 - today 01:00
  EXPECT_TRUE(calendar::overlapsDay(ev, 0));
  EXPECT_FALSE(calendar::overlapsDay(ev, 1));

  ev.startMin = 1440;
  ev.endMin = 1440;  // zero-length at tomorrow 00:00
  EXPECT_FALSE(calendar::overlapsDay(ev, 0));
  EXPECT_TRUE(calendar::overlapsDay(ev, 1));

  ev.startMin = 1380;
  ev.endMin = 1440;  // 23:00 - midnight
  EXPECT_TRUE(calendar::overlapsDay(ev, 0));
  EXPECT_FALSE(calendar::overlapsDay(ev, 1));
}

TEST(CalendarAgenda, StartsOnDay) {
  calendar::AgendaEvent ev;
  ev.startMin = 1440 + 10 * 60;
  ev.endMin = 1440 + 11 * 60;  // tomorrow 10:00
  EXPECT_FALSE(calendar::startsOnDay(ev, 0));
  EXPECT_TRUE(calendar::startsOnDay(ev, 1));

  ev.startMin = 0;
  ev.endMin = 2 * 1440;  // two-day all-day event starting today
  EXPECT_TRUE(calendar::startsOnDay(ev, 0));
  EXPECT_FALSE(calendar::startsOnDay(ev, 1));  // listed today, not repeated tomorrow

  ev.startMin = 1440;
  ev.endMin = 1440;  // tomorrow 00:00 reminder
  EXPECT_TRUE(calendar::startsOnDay(ev, 1));
}

TEST(CalendarAgenda, KeepsOnlyTheLastHourOfPastEvents) {
  const int now = 11 * 60 + 15;  // 11:15 on day 0
  calendar::AgendaEvent ev;

  ev.startMin = 9 * 60;
  ev.endMin = 9 * 60 + 30;  // ended 09:30, 1 h 45 min ago
  EXPECT_FALSE(calendar::isCurrentOrUpcoming(ev, now, 60));

  ev.startMin = 10 * 60;
  ev.endMin = 10 * 60 + 30;  // ended 10:30, 45 min ago
  EXPECT_TRUE(calendar::isCurrentOrUpcoming(ev, now, 60));

  ev.startMin = 9 * 60;
  ev.endMin = 10 * 60 + 15;  // ended exactly 60 min ago
  EXPECT_FALSE(calendar::isCurrentOrUpcoming(ev, now, 60));

  ev.startMin = 11 * 60;
  ev.endMin = 12 * 60;  // in progress
  EXPECT_TRUE(calendar::isCurrentOrUpcoming(ev, now, 60));

  ev.startMin = 0;
  ev.endMin = 1440;  // all day
  EXPECT_TRUE(calendar::isCurrentOrUpcoming(ev, 23 * 60 + 59, 60));

  ev.startMin = 8 * 60;
  ev.endMin = 8 * 60;  // zero-length reminder at 08:00
  EXPECT_FALSE(calendar::isCurrentOrUpcoming(ev, now, 60));
}

TEST(CalendarAgenda, FormatsTimeRanges) {
  char buf[32];
  calendar::AgendaEvent ev;

  ev.startMin = 9 * 60;
  ev.endMin = 9 * 60 + 30;
  ASSERT_TRUE(calendar::formatTimeRange(ev, 0, false, buf, sizeof(buf)));
  EXPECT_STREQ("09:00 - 09:30", buf);
  ASSERT_TRUE(calendar::formatTimeRange(ev, 0, true, buf, sizeof(buf)));
  EXPECT_STREQ("9:00 AM - 9:30 AM", buf);

  ev.startMin = 12 * 60 + 5;
  ev.endMin = 13 * 60;
  ASSERT_TRUE(calendar::formatTimeRange(ev, 0, true, buf, sizeof(buf)));
  EXPECT_STREQ("12:05 PM - 1:00 PM", buf);

  ev.startMin = -120;
  ev.endMin = 60;  // started yesterday
  ASSERT_TRUE(calendar::formatTimeRange(ev, 0, false, buf, sizeof(buf)));
  EXPECT_STREQ("... - 01:00", buf);

  ev.startMin = 23 * 60;
  ev.endMin = 1440 + 60;  // runs into tomorrow
  ASSERT_TRUE(calendar::formatTimeRange(ev, 0, false, buf, sizeof(buf)));
  EXPECT_STREQ("23:00 - ...", buf);

  ev.startMin = 23 * 60;
  ev.endMin = 1440;  // ends exactly at midnight
  ASSERT_TRUE(calendar::formatTimeRange(ev, 0, true, buf, sizeof(buf)));
  EXPECT_STREQ("11:00 PM - 12:00 AM", buf);

  ev.startMin = 1440 + 10 * 60;
  ev.endMin = 1440 + 10 * 60;  // zero-length, tomorrow
  ASSERT_TRUE(calendar::formatTimeRange(ev, 1, false, buf, sizeof(buf)));
  EXPECT_STREQ("10:00", buf);

  EXPECT_FALSE(calendar::formatTimeRange(ev, 1, false, buf, 3));
}

TEST(CalendarAgenda, FormatsStackedTimes) {
  char start[12];
  char end[12];
  calendar::AgendaEvent ev;

  ev.startMin = 1440 + 12 * 60 + 5;
  ev.endMin = 1440 + 13 * 60;  // tomorrow 12:05 - 13:00, seen on day 1
  ASSERT_TRUE(calendar::formatEventTimes(ev, 1, true, start, sizeof(start), end, sizeof(end)));
  EXPECT_STREQ("12:05 PM", start);
  EXPECT_STREQ("1:00 PM", end);

  ev.startMin = -30;
  ev.endMin = 1440 + 30;  // spans the whole day
  ASSERT_TRUE(calendar::formatEventTimes(ev, 0, false, start, sizeof(start), end, sizeof(end)));
  EXPECT_STREQ("...", start);
  EXPECT_STREQ("...", end);

  ev.startMin = 600;
  ev.endMin = 600;  // zero-length: no end
  ASSERT_TRUE(calendar::formatEventTimes(ev, 0, false, start, sizeof(start), end, sizeof(end)));
  EXPECT_STREQ("10:00", start);
  EXPECT_STREQ("", end);

  EXPECT_FALSE(calendar::formatEventTimes(ev, 0, false, start, 4, end, sizeof(end)));
}

TEST(CalendarAgenda, DaysFromCivil) {
  EXPECT_EQ(0, calendar::daysFromCivil(1970, 1, 1));
  EXPECT_EQ(20725, calendar::daysFromCivil(2026, 9, 29));
  EXPECT_EQ(20513, calendar::daysFromCivil(2026, 3, 1));  // after a non-leap February
  EXPECT_EQ(20147, calendar::daysFromCivil(2025, 2, 28));
}
