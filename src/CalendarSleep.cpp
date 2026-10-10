#include "CalendarSleep.h"

#include <CalendarAgenda.h>
#include <GfxRenderer.h>
#include <HalClock.h>
#include <HalDisplay.h>
#include <HalPowerManager.h>
#include <I18n.h>
#include <Logging.h>
#include <esp_attr.h>

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <ctime>

#include "CalendarBle.h"
#include "CalendarCache.h"
#include "CrossPointSettings.h"
#include "fontIds.h"

// Redraw cadence in seconds; must divide 3600. Test builds shorten it (e.g.
// -DCALENDAR_REDRAW_INTERVAL_S=120) to exercise the timer wake quickly.
#ifndef CALENDAR_REDRAW_INTERVAL_S
#define CALENDAR_REDRAW_INTERVAL_S 3600
#endif
// Seconds past the hour to sync: :55 refreshes the agenda just before the
// next hour's meetings start (taken modulo the interval for test builds).
#ifndef CALENDAR_REDRAW_OFFSET_S
#define CALENDAR_REDRAW_OFFSET_S (55 * 60)
#endif

namespace CalendarSleep {
namespace {

constexpr int REDRAW_INTERVAL_S = CALENDAR_REDRAW_INTERVAL_S;
static_assert(REDRAW_INTERVAL_S > 0 && 3600 % REDRAW_INTERVAL_S == 0, "redraw interval must divide one hour");
constexpr int REDRAW_PHASE_S = CALENDAR_REDRAW_OFFSET_S % REDRAW_INTERVAL_S;
static_assert(CALENDAR_REDRAW_OFFSET_S >= 0 && CALENDAR_REDRAW_OFFSET_S < 3600, "redraw offset must be within an hour");

// Aim a few seconds past the boundary so a slightly slow sleep clock still
// lands after the boundary; a timer wake this close before a boundary counts as
// that boundary's wake instead of scheduling a second one seconds later.
constexpr int LATE_MARGIN_S = 5;
constexpr int EARLY_WAKE_TOLERANCE_S = std::min(60, REDRAW_INTERVAL_S / 4);

// RTC slow memory: survives deep sleep, zeroed on power-on.
RTC_DATA_ATTR uint32_t sleepRenderCount = 0;
RTC_DATA_ATTR uint32_t timerWakeCount = 0;

// This boot is a timer wake (plain RAM: reset on every boot).
bool inTimerWake = false;

// --- Agenda layout (portrait) ---------------------------------------------------
constexpr int MARGIN_X = 28;
constexpr int HEADER_Y = 28;
constexpr int HEADER_RULE_GAP = 12;  // header text -> rule
constexpr int RULE_THICKNESS = 2;
constexpr int CONTENT_GAP = 22;  // rule -> first event
constexpr int ITEM_GAP = 20;     // whitespace between events
constexpr int LINE_GAP = 4;      // between lines inside one event
constexpr int COLUMN_GAP = 16;   // time column -> title column
constexpr int TITLE_MAX_LINES = 2;
constexpr int KEEP_PAST_MIN = 60;  // past events stay visible this long after they end
constexpr int SECTION_GAP = 4;           // last event's gap -> next-day separator
constexpr int SECTION_HEADING_GAP = 12;  // around the next-day date heading
constexpr int FOOTER_BOTTOM = 28;

constexpr int HEADER_FONT = NOTOSANS_14_FONT_ID;
constexpr int TIME_FONT = UI_12_FONT_ID;
constexpr int TITLE_FONT = CALENDAR_TITLE_FONT_ID;
constexpr int NEXT_DAY_FONT = NOTOSANS_12_FONT_ID;  // bold; between the header and titles
constexpr int LOCATION_FONT = UI_12_FONT_ID;
constexpr int NOTE_FONT = UI_12_FONT_ID;
constexpr int FOOTER_FONT = SMALL_FONT_ID;

constexpr StrId WEEKDAYS[7] = {StrId::STR_CAL_WEEKDAY_0, StrId::STR_CAL_WEEKDAY_1, StrId::STR_CAL_WEEKDAY_2,
                               StrId::STR_CAL_WEEKDAY_3, StrId::STR_CAL_WEEKDAY_4, StrId::STR_CAL_WEEKDAY_5,
                               StrId::STR_CAL_WEEKDAY_6};
constexpr StrId MONTHS[12] = {StrId::STR_CAL_MONTH_1,  StrId::STR_CAL_MONTH_2,  StrId::STR_CAL_MONTH_3,
                              StrId::STR_CAL_MONTH_4,  StrId::STR_CAL_MONTH_5,  StrId::STR_CAL_MONTH_6,
                              StrId::STR_CAL_MONTH_7,  StrId::STR_CAL_MONTH_8,  StrId::STR_CAL_MONTH_9,
                              StrId::STR_CAL_MONTH_10, StrId::STR_CAL_MONTH_11, StrId::STR_CAL_MONTH_12};

const char* trId(const StrId id) { return I18n::getInstance().get(id); }

// Index just past the UTF-8 code point starting at s[i].
size_t nextCodePoint(const char* s, size_t i, const size_t len) {
  i++;
  while (i < len && (static_cast<uint8_t>(s[i]) & 0xC0) == 0x80) i++;
  return i;
}

// Longest code-point-aligned prefix of s[0..len) that fits `maxWidth` with
// `suffix` appended. Texts are <= 64 bytes, so re-measuring per code point is cheap.
size_t fittingPrefix(const GfxRenderer& r, const int font, const EpdFontFamily::Style style, const char* s,
                     const size_t len, const int maxWidth, const char* suffix) {
  char buf[96];
  const size_t suffixLen = strlen(suffix);
  size_t best = 0;
  for (size_t i = 0; i < len;) {
    const size_t n = nextCodePoint(s, i, len);
    if (n + suffixLen >= sizeof(buf)) break;
    memcpy(buf, s, n);
    memcpy(buf + n, suffix, suffixLen + 1);
    if (r.getTextWidth(font, buf, style) > maxWidth) break;
    best = n;
    i = n;
  }
  return best;
}

// Word-wraps s[0..len) into at most `maxLines` lines of `maxWidth`, ending
// with "..." if it doesn't fit. Draws at (x, y) when `draw`; returns the lines used.
int wrapText(const GfxRenderer& r, const int font, const EpdFontFamily::Style style, const char* s, size_t len,
             const int x, int y, const int maxWidth, const int maxLines, const bool draw) {
  char line[96];
  int lines = 0;
  const int lineHeight = r.getLineHeight(font);
  while (len > 0 && lines < maxLines) {
    while (len > 0 && *s == ' ') {
      s++;
      len--;
    }
    if (len == 0) break;
    const size_t whole = fittingPrefix(r, font, style, s, len, maxWidth, "");
    size_t take;
    size_t skip;
    const char* suffix = "";
    if (whole == len) {
      take = skip = len;
    } else if (lines + 1 == maxLines) {
      take = skip = fittingPrefix(r, font, style, s, len, maxWidth, "...");
      suffix = "...";
    } else {
      // Break after the last space that fits, else mid-word.
      size_t space = whole;
      while (space > 0 && s[space] != ' ') space--;
      take = space > 0 ? space : std::max<size_t>(whole, nextCodePoint(s, 0, len));
      skip = take;
    }
    if (draw) {
      const size_t n = std::min(take, sizeof(line) - 4);
      memcpy(line, s, n);
      strcpy(line + n, suffix);
      r.drawText(font, x, y, line, true, style);
    }
    s += skip;
    len -= skip;
    y += lineHeight + LINE_GAP;
    lines++;
  }
  return lines;
}

// Two columns per event: start over end time on the left; title (bold,
// regular when tentative) and location on the right.
struct Columns {
  int timeX;
  int textX;
  int textWidth;
};

// End times sit two "i" widths further right than start times, so the pair
// reads as start -> end at a glance.
int endTimeIndent(const GfxRenderer& r) { return 2 * r.getTextWidth(TIME_FONT, "i"); }

Columns columnsFor(const GfxRenderer& r, const int width) {
  // Widest time label: "00:00" or "12:00 PM" (plus the end-time indent), or
  // the all-day label.
  const int timeW =
      std::max(r.getTextWidth(TIME_FONT, SETTINGS.clockFormat == 1 ? "12:00 PM" : "00:00") + endTimeIndent(r),
               r.getTextWidth(TIME_FONT, tr(STR_CAL_ALL_DAY)));
  const int textX = MARGIN_X + timeW + COLUMN_GAP;
  return {MARGIN_X, textX, MARGIN_X + width - textX};
}

const char* titleOf(const calendar::AgendaEvent& ev, size_t& len) {
  if (ev.titleLen) {
    len = ev.titleLen;
    return ev.title;
  }
  const char* untitled = trId(StrId::STR_CAL_UNTITLED);
  len = strlen(untitled);
  return untitled;
}

EpdFontFamily::Style titleStyleOf(const calendar::AgendaEvent& ev) {
  return ev.tentative() ? EpdFontFamily::REGULAR : EpdFontFamily::BOLD;
}

// Shifts the time column down so its first line shares the title's baseline.
int timeBaselineOffset(const GfxRenderer& r) {
  return std::max(0, r.getFontAscenderSize(TITLE_FONT) - r.getFontAscenderSize(TIME_FONT));
}

int blockHeight(const GfxRenderer& r, const calendar::AgendaEvent& ev, const Columns& cols) {
  const int timeLine = r.getLineHeight(TIME_FONT);
  const int leftH = timeBaselineOffset(r) + (ev.allDay() ? timeLine : 2 * timeLine + LINE_GAP);
  size_t titleLen = 0;
  const char* title = titleOf(ev, titleLen);
  const int titleLines =
      wrapText(r, TITLE_FONT, titleStyleOf(ev), title, titleLen, 0, 0, cols.textWidth, TITLE_MAX_LINES, false);
  int rightH = titleLines * (r.getLineHeight(TITLE_FONT) + LINE_GAP);
  if (ev.locationLen) rightH += r.getLineHeight(LOCATION_FONT);
  return std::max(leftH, rightH);
}

void drawEvent(const GfxRenderer& r, const calendar::AgendaEvent& ev, const int dayIndex, const Columns& cols,
               const int y) {
  const int timeY = y + timeBaselineOffset(r);
  if (ev.allDay()) {
    r.drawText(TIME_FONT, cols.timeX, timeY, tr(STR_CAL_ALL_DAY));
  } else {
    char start[12];
    char end[12];
    calendar::formatEventTimes(ev, dayIndex, SETTINGS.clockFormat == 1, start, sizeof(start), end, sizeof(end));
    r.drawText(TIME_FONT, cols.timeX, timeY, start);
    if (end[0]) {
      r.drawText(TIME_FONT, cols.timeX + endTimeIndent(r), timeY + r.getLineHeight(TIME_FONT) + LINE_GAP, end);
    }
  }

  size_t titleLen = 0;
  const char* title = titleOf(ev, titleLen);
  const int titleLines =
      wrapText(r, TITLE_FONT, titleStyleOf(ev), title, titleLen, cols.textX, y, cols.textWidth, TITLE_MAX_LINES, true);
  if (ev.locationLen) {
    const int locY = y + titleLines * (r.getLineHeight(TITLE_FONT) + LINE_GAP);
    wrapText(r, LOCATION_FONT, EpdFontFamily::REGULAR, ev.location, ev.locationLen, cols.textX, locY, cols.textWidth, 1,
             true);
  }
}

// "Tuesday, September 30".
void formatDate(const struct tm& day, char* buf, const size_t size) {
  snprintf(buf, size, tr(STR_CAL_DATE_FORMAT), trId(WEEKDAYS[day.tm_wday % 7]), trId(MONTHS[day.tm_mon % 12]),
           day.tm_mday);
}

// Today's date and the rule under it; returns the y below the rule.
int drawHeader(const GfxRenderer& r, const struct tm& now) {
  char buf[64];
  formatDate(now, buf, sizeof(buf));
  r.drawText(HEADER_FONT, MARGIN_X, HEADER_Y, buf, true, EpdFontFamily::BOLD);
  const int ruleY = HEADER_Y + r.getLineHeight(HEADER_FONT) + HEADER_RULE_GAP;
  r.fillRect(MARGIN_X, ruleY, r.getScreenWidth() - 2 * MARGIN_X, RULE_THICKNESS);
  return ruleY + RULE_THICKNESS + CONTENT_GAP;
}

// "14:00" / "2:00 PM", prefixed "9/29 " when `t` is not on `now`'s day.
void formatClock(const time_t t, const struct tm& now, char* buf, const size_t size) {
  struct tm at;
  localtime_r(&t, &at);
  const bool use12Hour = SETTINGS.clockFormat == 1;
  const int hour = use12Hour ? (at.tm_hour % 12 == 0 ? 12 : at.tm_hour % 12) : at.tm_hour;
  const char* ampm = use12Hour ? (at.tm_hour < 12 ? " AM" : " PM") : "";
  if (at.tm_yday == now.tm_yday && at.tm_year == now.tm_year) {
    snprintf(buf, size, use12Hour ? "%d:%02d%s" : "%02d:%02d%s", hour, at.tm_min, ampm);
  } else {
    snprintf(buf, size, use12Hour ? "%d/%d %d:%02d%s" : "%d/%d %02d:%02d%s", at.tm_mon + 1, at.tm_mday, hour, at.tm_min,
             ampm);
  }
}

// Plain-language reason the last sync failed, or nullptr when it succeeded
// (or never ran).
const char* syncProblem(const CalendarBle::Result result) {
  using R = CalendarBle::Result;
  switch (result) {
    case R::None:
    case R::Ok:
    case R::Paired:
      return nullptr;
    case R::NotFound:
      return tr(STR_CAL_SYNC_NOT_FOUND);
    case R::ConnectFailed:
    case R::Hung:
      return tr(STR_CAL_SYNC_NO_CONNECT);
    case R::UnknownPeer:
    case R::SecurityFailed:
      return tr(STR_CAL_SYNC_PAIRING);
    case R::InitFailed:
      return tr(STR_CAL_SYNC_BLUETOOTH);
    default:
      return tr(STR_CAL_SYNC_ERROR);
  }
}

// One line at the bottom: "Updated 14:00 · Phone not found" on the left,
// "Next 15:00 · Battery 87%" on the right.
void drawFooter(const GfxRenderer& r, const uint32_t syncedUtc, const struct tm& now) {
  const int y = r.getScreenHeight() - FOOTER_BOTTOM;
  char clock[24];
  char left[96] = "";
  if (syncedUtc != 0) {
    formatClock(syncedUtc, now, clock, sizeof(clock));
    snprintf(left, sizeof(left), tr(STR_CAL_UPDATED_FORMAT), clock);
  }
  if (const char* problem = syncProblem(CalendarBle::last().result)) {
    const size_t used = strlen(left);
    snprintf(left + used, sizeof(left) - used, used ? " \xC2\xB7 %s" : "%s", problem);
  }
  if (left[0]) r.drawText(FOOTER_FONT, MARGIN_X, y, left);

  time_t nowUtc = 0;
  char right[80];
  if (halClock.utcEpoch(nowUtc)) {
    formatClock(nowUtc + static_cast<time_t>(secondsUntilNextRedraw()), now, clock, sizeof(clock));
    snprintf(right, sizeof(right), tr(STR_CAL_NEXT_BATTERY_FORMAT), clock, powerManager.getBatteryPercentage());
  } else {
    snprintf(right, sizeof(right), tr(STR_CAL_BATTERY_FORMAT), powerManager.getBatteryPercentage());
  }
  r.drawText(FOOTER_FONT, r.getScreenWidth() - MARGIN_X - r.getTextWidth(FOOTER_FONT, right), y, right);
}

void drawNote(const GfxRenderer& r, const int y, const char* text) { r.drawText(NOTE_FONT, MARGIN_X, y, text); }

struct Section {
  int y;        // next free y
  int matched;  // events belonging to the section (before the `show` filter)
  int shown;
  int hidden;   // shown events that did not fit ("+N more" was drawn)
};

// Draws the events for which `belongs` and `show` hold, from `y` down to
// `bottom`, ending with "+N more" if they don't all fit.
template <typename Belongs, typename Show>
Section drawSection(const GfxRenderer& r, const calendar::Agenda& agenda, const Columns& cols, int y,
                    const int bottom, Belongs belongs, Show show, const int dayIndex) {
  Section s{y, 0, 0, 0};
  const int noteLine = r.getLineHeight(NOTE_FONT);
  auto it = agenda.events();
  calendar::AgendaEvent ev;
  while (it.next(ev)) {
    if (!belongs(ev)) continue;
    s.matched++;
    if (!show(ev)) continue;
    const int h = blockHeight(r, ev, cols);
    // Keep one note line free for "+N more" below the last event that fits.
    if (s.hidden > 0 || s.y + h > bottom - noteLine) {
      s.hidden++;
      continue;
    }
    drawEvent(r, ev, dayIndex, cols, s.y);
    s.y += h + ITEM_GAP;
    s.shown++;
  }
  if (s.hidden > 0) {
    char buf[32];
    snprintf(buf, sizeof(buf), tr(STR_CAL_MORE_FORMAT), s.hidden);
    drawNote(r, s.y, buf);
    s.y += noteLine + ITEM_GAP;
  }
  return s;
}

void renderAgenda(const GfxRenderer& r, const struct tm& now) {
  const int width = r.getScreenWidth() - 2 * MARGIN_X;
  const int contentBottom = r.getScreenHeight() - FOOTER_BOTTOM - 16;
  int y = drawHeader(r, now);

  // The agenda buffer must outlive `agenda`, which points into it.
  const CalendarCache::Loaded cache = CalendarCache::load();
  calendar::Agenda agenda;
  const bool haveAgenda = cache.payload && agenda.parse(cache.payload.get(), cache.len);
  const uint32_t syncedUtc = std::max(CalendarBle::lastSyncedUtc(), cache.syncedUtc);
  drawFooter(r, syncedUtc, now);
  if (!haveAgenda) {
    drawNote(r, y, tr(STR_CAL_WAITING));
    return;
  }
  const int dayIndex = agenda.dayIndexOf(calendar::daysFromCivil(now.tm_year + 1900, now.tm_mon + 1, now.tm_mday));
  if (dayIndex < 0) {
    drawNote(r, y, tr(STR_CAL_NO_AGENDA));
    return;
  }

  const Columns cols = columnsFor(r, width);
  const int noteLine = r.getLineHeight(NOTE_FONT);

  // Today: everything overlapping the day, minus events that ended over an
  // hour ago, so each hourly redraw rolls the list forward.
  const int nowMin = dayIndex * calendar::MINUTES_PER_DAY + now.tm_hour * 60 + now.tm_min;
  const Section today =
      drawSection(r, agenda, cols, y, contentBottom, [&](const calendar::AgendaEvent& e) {
        return calendar::overlapsDay(e, dayIndex);
      }, [&](const calendar::AgendaEvent& e) {
        return calendar::isCurrentOrUpcoming(e, nowMin, KEEP_PAST_MIN);
      }, dayIndex);
  y = today.y;
  if (today.hidden > 0) return;  // today alone fills the screen
  if (today.shown == 0) {
    drawNote(r, y, today.matched > 0 ? tr(STR_CAL_NO_MORE_EVENTS) : tr(STR_CAL_NO_EVENTS));
    y += noteLine + ITEM_GAP;
  }

  // Tomorrow, under a separator and its date: only events that start then
  // (a multi-day event that began today is already listed above).
  const int next = dayIndex + 1;
  if (next >= agenda.dayCount()) return;
  const auto startsNext = [&](const calendar::AgendaEvent& e) { return calendar::startsOnDay(e, next); };
  int nextCount = 0;
  {
    auto it = agenda.events();
    calendar::AgendaEvent e;
    while (it.next(e)) nextCount += startsNext(e) ? 1 : 0;
  }
  const int headingH = SECTION_GAP + RULE_THICKNESS + SECTION_HEADING_GAP + r.getLineHeight(NEXT_DAY_FONT);
  if (nextCount == 0 || y + headingH + SECTION_HEADING_GAP + noteLine > contentBottom) return;

  y += SECTION_GAP;
  r.fillRect(MARGIN_X, y, width, RULE_THICKNESS);
  y += RULE_THICKNESS + SECTION_HEADING_GAP;
  struct tm tomorrow = now;
  tomorrow.tm_mday += 1;
  tomorrow.tm_hour = 12;  // midday: immune to DST shifts when normalising
  mktime(&tomorrow);
  char date[64];
  formatDate(tomorrow, date, sizeof(date));
  r.drawText(NEXT_DAY_FONT, MARGIN_X, y, date, true, EpdFontFamily::BOLD);
  y += r.getLineHeight(NEXT_DAY_FONT) + SECTION_HEADING_GAP;
  drawSection(r, agenda, cols, y, contentBottom, startsNext, [](const calendar::AgendaEvent&) { return true; }, next);
}

void renderNoTime(const GfxRenderer& r) {
  const int h = r.getScreenHeight();
  r.drawCenteredText(UI_12_FONT_ID, h / 2 - 30, tr(STR_CAL_NO_TIME), true, EpdFontFamily::BOLD);
  r.drawCenteredText(UI_12_FONT_ID, h / 2 + 10, tr(STR_CAL_NO_TIME_HINT));
}

}  // namespace

void noteTimerWake() {
  timerWakeCount++;
  inTimerWake = true;
}

void render(GfxRenderer& renderer) {
  sleepRenderCount++;
  CalendarBle::restoreTimezone();  // phone's rule from the last sync, if any
  renderer.setOrientation(GfxRenderer::Orientation::Portrait);
  renderer.clearScreen();
  struct tm now;
  if (halClock.localTime(now)) {
    renderAgenda(renderer, now);
  } else {
    renderNoTime(renderer);
  }
  // Dark option: drawn as usual, then inverted to white on black.
  if (SETTINGS.calendarDark) renderer.invertScreen();
  renderer.displayBuffer(HalDisplay::HALF_REFRESH);
  LOG_INF("CAL", "Rendered calendar sleep screen (#%lu, timer wakes %lu)", static_cast<unsigned long>(sleepRenderCount),
          static_cast<unsigned long>(timerWakeCount));
}

uint64_t secondsUntilNextRedraw() {
  struct tm now;
  if (!halClock.localTime(now)) return REDRAW_INTERVAL_S;
  // Seconds since the last scheduled wake (+3600 keeps it non-negative; the
  // interval divides 3600).
  const int intoInterval = (now.tm_min * 60 + now.tm_sec - REDRAW_PHASE_S + 3600) % REDRAW_INTERVAL_S;
  int wait = REDRAW_INTERVAL_S - intoInterval;
  if (inTimerWake && wait <= EARLY_WAKE_TOLERANCE_S) wait += REDRAW_INTERVAL_S;
  return static_cast<uint64_t>(wait + LATE_MARGIN_S);
}

}  // namespace CalendarSleep
