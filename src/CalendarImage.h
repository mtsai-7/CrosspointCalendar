#pragma once

#include <cstddef>
#include <cstdint>

class GfxRenderer;

// Pinned image (calendar/docs/ble-protocol.md §4.4): a full-screen BMP the
// phone pins (e.g. a boarding pass) that replaces the agenda on the sleep
// screen until the phone unpins it or the user dismisses it on the X3. Stored
// on the SD card next to the agenda cache; the agenda keeps syncing underneath.
namespace CalendarImage {

// imageCrc32 of the stored image, 0 = none.
uint32_t storedCrc();

// imageCrc32 of an image the user dismissed on the X3, 0 = none.
uint32_t dismissedCrc();

// True when the stored image should replace the agenda.
bool active();

// The phone unpinned: forget the image and any dismissal.
void unpin();

// The user chose the agenda on the X3. False when no image was showing.
bool dismiss();

// Reads up to `max` bytes of the phone's image stream at `offset` into `out`
// (spec: write IMAGE_OFFSET, read IMAGE_DATA); returns the bytes read, 0 on error.
using FetchFn = size_t (*)(void* ctx, uint32_t offset, uint8_t* out, size_t max);

// Downloads the phone's image (`len` bytes, CRC `crc`) window by window,
// inflating it straight to the SD card, then validates and stores it.
// A failure keeps the previous state.
bool download(uint32_t len, uint32_t crc, FetchFn fetch, void* ctx);

// Draws the stored image full screen and refreshes the panel. False when it
// can't be read (the caller then draws the agenda).
bool render(GfxRenderer& renderer);

}  // namespace CalendarImage
