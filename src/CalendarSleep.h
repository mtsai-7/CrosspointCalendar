#pragma once

#include <cstdint>

class GfxRenderer;

// Calendar sleep screen: redrawn on a deep-sleep timer wake without starting
// the reader UI. Timer-wake skeleton adapted from asmithdots-debug/crosspoint-calendar
// (MIT).
namespace CalendarSleep {

// Draws the calendar sleep screen and refreshes the panel.
void render(GfxRenderer& renderer);

// Seconds until the next redraw boundary (top of the hour by default). Never 0:
// without a valid clock it still returns the interval so a later wake can pick
// up the time.
uint64_t secondsUntilNextRedraw();

// Records a timer wake (wake count in the log; keeps the next-wake schedule
// from double-booking a boundary). Call before render().
void noteTimerWake();

}  // namespace CalendarSleep
