#pragma once

#include <cstdint>

class GfxRenderer;

// Calendar sleep screen: redrawn on a deep-sleep timer wake without starting
// the reader UI. Timer-wake skeleton adapted from asmithdots-debug/crosspoint-calendar
// (MIT).
namespace CalendarSleep {

// Draws the calendar sleep screen and refreshes the panel.
void render(GfxRenderer& renderer);

// Delay before the sync wake that follows the user putting the X3 to sleep
// (power button or inactivity timeout): waking and sleeping again is the way
// to force a sync. That wake then returns to the regular schedule.
constexpr uint64_t SYNC_ON_SLEEP_S = 5;

// Seconds until the next redraw boundary (hh:55 by default). Never 0:
// without a valid clock it still returns the interval so a later wake can pick
// up the time.
uint64_t secondsUntilNextRedraw();

// Records a timer wake (wake count in the log; keeps the next-wake schedule
// from double-booking a boundary). Call before render().
void noteTimerWake();

}  // namespace CalendarSleep
