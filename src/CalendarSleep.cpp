#include "CalendarSleep.h"

#include <GfxRenderer.h>
#include <HalClock.h>
#include <HalDisplay.h>
#include <HalPowerManager.h>
#include <I18n.h>
#include <Logging.h>
#include <esp_attr.h>

#include <algorithm>
#include <cstdio>
#include <ctime>

#include "fontIds.h"

// Redraw cadence in seconds; must divide 3600. Test builds shorten it (e.g.
// -DCALENDAR_REDRAW_INTERVAL_S=120) to exercise the timer wake quickly.
#ifndef CALENDAR_REDRAW_INTERVAL_S
#define CALENDAR_REDRAW_INTERVAL_S 3600
#endif

namespace CalendarSleep {
namespace {

constexpr int REDRAW_INTERVAL_S = CALENDAR_REDRAW_INTERVAL_S;
static_assert(REDRAW_INTERVAL_S > 0 && 3600 % REDRAW_INTERVAL_S == 0, "redraw interval must divide one hour");

// Aim a few seconds past the boundary so a slightly slow sleep clock still
// lands on the new hour; a timer wake this close before a boundary counts as
// that boundary's wake instead of scheduling a second one seconds later.
constexpr int LATE_MARGIN_S = 5;
constexpr int EARLY_WAKE_TOLERANCE_S = std::min(60, REDRAW_INTERVAL_S / 4);

// RTC slow memory: survives deep sleep, zeroed on power-on.
RTC_DATA_ATTR uint32_t sleepRenderCount = 0;
RTC_DATA_ATTR uint32_t timerWakeCount = 0;

// This boot is a timer wake (plain RAM: reset on every boot).
bool inTimerWake = false;

// Seven-segment digit built from filled rectangles: crisp at any size, where the
// largest built-in font is only 18pt.
void drawSegmentDigit(const GfxRenderer& r, int x, int y, int w, int h, int t, int digit) {
  // Segment bits: a b c d e f g
  static constexpr uint8_t SEGMENTS[10] = {0x7E, 0x30, 0x6D, 0x79, 0x33, 0x5B, 0x5F, 0x70, 0x7F, 0x7B};
  const uint8_t s = SEGMENTS[digit % 10];
  const int half = h / 2;
  if (s & 0x40) r.fillRect(x, y, w, t);                                        // a
  if (s & 0x20) r.fillRect(x + w - t, y, t, half + t / 2);                     // b
  if (s & 0x10) r.fillRect(x + w - t, y + half - t / 2, t, h - half + t / 2);  // c
  if (s & 0x08) r.fillRect(x, y + h - t, w, t);                                // d
  if (s & 0x04) r.fillRect(x, y + half - t / 2, t, h - half + t / 2);          // e
  if (s & 0x02) r.fillRect(x, y, t, half + t / 2);                             // f
  if (s & 0x01) r.fillRect(x, y + half - t / 2, w, t);                         // g
}

// Large HH:MM clock. Placeholder layout until the agenda view replaces it.
void renderClock(const GfxRenderer& r, const struct tm& now) {
  constexpr int DIGIT_W = 80, DIGIT_H = 140, DIGIT_T = 16, GAP = 20, COLON_W = 16, CLOCK_Y = 120;
  const int totalW = 4 * DIGIT_W + 4 * GAP + COLON_W;
  int x = (r.getScreenWidth() - totalW) / 2;
  const int digits[4] = {now.tm_hour / 10, now.tm_hour % 10, now.tm_min / 10, now.tm_min % 10};
  for (int i = 0; i < 4; i++) {
    drawSegmentDigit(r, x, CLOCK_Y, DIGIT_W, DIGIT_H, DIGIT_T, digits[i]);
    x += DIGIT_W + GAP;
    if (i == 1) {
      r.fillRect(x, CLOCK_Y + DIGIT_H / 3 - COLON_W / 2, COLON_W, COLON_W);
      r.fillRect(x, CLOCK_Y + 2 * DIGIT_H / 3 - COLON_W / 2, COLON_W, COLON_W);
      x += COLON_W + GAP;
    }
  }

  // ISO date needs no translation tables.
  char buf[16];
  snprintf(buf, sizeof(buf), "%04d-%02d-%02d", now.tm_year + 1900, now.tm_mon + 1, now.tm_mday);
  r.drawCenteredText(UI_12_FONT_ID, CLOCK_Y + DIGIT_H + 40, buf, true, EpdFontFamily::BOLD);
}

void renderNoTime(const GfxRenderer& r) {
  const int h = r.getScreenHeight();
  r.drawCenteredText(UI_12_FONT_ID, h / 2 - 30, tr(STR_CAL_NO_TIME), true, EpdFontFamily::BOLD);
  r.drawCenteredText(UI_12_FONT_ID, h / 2 + 10, tr(STR_CAL_NO_TIME_HINT));
}

#ifdef CALENDAR_DEBUG_OVERLAY
// Development-only diagnostics (not user-facing, so not translated):
// render count, timer wakes, battery, seconds to the next scheduled wake.
void renderDebugLine(const GfxRenderer& r) {
  char buf[64];
  snprintf(buf, sizeof(buf), "#%lu T%lu %u%% +%llus", static_cast<unsigned long>(sleepRenderCount),
           static_cast<unsigned long>(timerWakeCount), powerManager.getBatteryPercentage(),
           static_cast<unsigned long long>(secondsUntilNextRedraw()));
  r.drawCenteredText(SMALL_FONT_ID, r.getScreenHeight() - 40, buf);
}
#endif

}  // namespace

void noteTimerWake() {
  timerWakeCount++;
  inTimerWake = true;
}

void render(GfxRenderer& renderer) {
  sleepRenderCount++;
  renderer.setOrientation(GfxRenderer::Orientation::Portrait);
  renderer.clearScreen();
  struct tm now;
  if (halClock.localTime(now)) {
    renderClock(renderer, now);
  } else {
    renderNoTime(renderer);
  }
#ifdef CALENDAR_DEBUG_OVERLAY
  renderDebugLine(renderer);
#endif
  renderer.displayBuffer(HalDisplay::HALF_REFRESH);
  LOG_INF("CAL", "Rendered calendar sleep screen (#%lu, timer wakes %lu)", static_cast<unsigned long>(sleepRenderCount),
          static_cast<unsigned long>(timerWakeCount));
}

uint64_t secondsUntilNextRedraw() {
  struct tm now;
  if (!halClock.localTime(now)) return REDRAW_INTERVAL_S;
  const int intoInterval = (now.tm_min * 60 + now.tm_sec) % REDRAW_INTERVAL_S;
  int wait = REDRAW_INTERVAL_S - intoInterval;
  if (inTimerWake && wait <= EARLY_WAKE_TOLERANCE_S) wait += REDRAW_INTERVAL_S;
  return static_cast<uint64_t>(wait + LATE_MARGIN_S);
}

}  // namespace CalendarSleep
