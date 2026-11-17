#include "CalendarImage.h"

#include <Bitmap.h>
#include <CalendarAgenda.h>
#include <GfxRenderer.h>
#include <HalStorage.h>
#include <InflateStream.h>
#include <Logging.h>
#include <Memory.h>

#include <algorithm>
#include <cstring>

#include "activities/boot_sleep/SleepActivity.h"

namespace CalendarImage {
namespace {

constexpr const char* IMAGE_PATH = "/.crosspoint/pinned.bmp";
constexpr const char* TMP_PATH = "/.crosspoint/pinned.tmp";
constexpr const char* STATE_PATH = "/.crosspoint/pinned.st";
constexpr char MAGIC[4] = {'X', 'P', 'I', '1'};
constexpr size_t STATE_LEN = 12;  // magic, storedCrc, dismissedCrc

constexpr int IMAGE_W = 528;  // spec §4.4: the X3 screen, portrait
constexpr int IMAGE_H = 792;
constexpr size_t MAX_BMP = 110000;
constexpr size_t MAX_STREAM = 120000;  // zlib never grows a BMP this small past it
constexpr size_t WINDOW = 512;
constexpr size_t OUT_CHUNK = 1024;

struct State {
  uint32_t stored = 0;
  uint32_t dismissed = 0;
};

void put32(uint8_t* p, const uint32_t v) {
  for (int i = 0; i < 4; i++) p[i] = (v >> (8 * i)) & 0xFF;
}
uint32_t get32(const uint8_t* p) {
  return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) | (static_cast<uint32_t>(p[2]) << 16) |
         (static_cast<uint32_t>(p[3]) << 24);
}

State readState() {
  State st;
  // No state file is the normal "nothing pinned" case; check first so the
  // storage layer doesn't log it as an error.
  if (!Storage.exists(STATE_PATH)) return st;
  HalFile file;
  if (!Storage.openFileForRead("IMG", STATE_PATH, file)) return st;
  uint8_t b[STATE_LEN];
  if (file.read(b, STATE_LEN) == static_cast<int>(STATE_LEN) && memcmp(b, MAGIC, sizeof(MAGIC)) == 0) {
    st.stored = get32(b + 4);
    st.dismissed = get32(b + 8);
  }
  return st;
}

void writeState(const State& st) {
  if (st.stored == 0 && st.dismissed == 0) {
    Storage.remove(STATE_PATH);
    return;
  }
  uint8_t b[STATE_LEN];
  memcpy(b, MAGIC, sizeof(MAGIC));
  put32(b + 4, st.stored);
  put32(b + 8, st.dismissed);
  HalFile file;
  if (!Storage.openFileForWrite("IMG", STATE_PATH, file) || file.write(b, STATE_LEN) != STATE_LEN) {
    LOG_ERR("IMG", "Could not save the pinned-image state");
  }
}

// Opens `path` and checks it is a spec §4.4 BMP.
bool validBmp(const char* path) {
  HalFile file;
  if (!Storage.openFileForRead("IMG", path, file)) return false;
  Bitmap bitmap(file);
  if (bitmap.parseHeaders() != BmpReaderError::Ok) return false;
  return bitmap.getWidth() == IMAGE_W && bitmap.getHeight() == IMAGE_H &&
         (bitmap.getBpp() == 1 || bitmap.getBpp() == 2);
}

// Pulls the compressed stream from the phone one IMAGE_DATA window at a time.
struct Download {
  FetchFn fetch;
  void* ctx;
  uint32_t len;
  uint32_t offset = 0;
  uint32_t crc = 0;
  bool failed = false;
  uint8_t window[WINDOW];
};

size_t fill(void* p, const uint8_t** data) {
  auto* d = static_cast<Download*>(p);
  if (d->failed || d->offset >= d->len) return 0;
  size_t n = d->fetch(d->ctx, d->offset, d->window, WINDOW);
  if (n == 0) {
    LOG_ERR("IMG", "Image read failed at offset %lu", static_cast<unsigned long>(d->offset));
    d->failed = true;
    return 0;
  }
  n = std::min<size_t>(n, d->len - d->offset);
  d->crc = calendar::crc32Update(d->crc, d->window, n);
  d->offset += n;
  *data = d->window;
  return n;
}

}  // namespace

uint32_t storedCrc() { return readState().stored; }

uint32_t dismissedCrc() { return readState().dismissed; }

bool active() {
  const State st = readState();
  return st.stored != 0 && st.stored != st.dismissed && Storage.exists(IMAGE_PATH);
}

void unpin() {
  Storage.remove(IMAGE_PATH);
  writeState(State{});
  LOG_INF("IMG", "Image unpinned by the phone");
}

bool dismiss() {
  State st = readState();
  if (st.stored == 0 || st.stored == st.dismissed) return false;
  st.dismissed = st.stored;
  writeState(st);
  Storage.remove(IMAGE_PATH);
  LOG_INF("IMG", "Image %08lx dismissed on the X3", static_cast<unsigned long>(st.dismissed));
  return true;
}

bool download(const uint32_t len, const uint32_t crc, const FetchFn fetch, void* ctx) {
  if (len == 0 || len > MAX_STREAM || !fetch) {
    LOG_ERR("IMG", "Refusing image of %lu bytes", static_cast<unsigned long>(len));
    return false;
  }
  // The 32 KB inflate window first: the largest block, before the heap fragments.
  InflateStream inflate;
  if (!inflate.init(true)) {
    LOG_ERR("IMG", "OOM for the inflate window");
    return false;
  }
  auto dl = makeUniqueNoThrow<Download>();
  auto out = makeUniqueNoThrow<uint8_t[]>(OUT_CHUNK);
  if (!dl || !out) {
    LOG_ERR("IMG", "OOM for image download buffers");
    return false;
  }
  dl->fetch = fetch;
  dl->ctx = ctx;
  dl->len = len;
  inflate.setZlibWrapped();
  inflate.setFill(&fill, dl.get());

  size_t written = 0;
  bool ok = false;
  {
    HalFile file;
    if (!Storage.openFileForWrite("IMG", TMP_PATH, file)) {
      LOG_ERR("IMG", "Cannot create %s", TMP_PATH);
      return false;
    }
    for (;;) {
      size_t produced = 0;
      const auto status = inflate.readAtMost(out.get(), OUT_CHUNK, &produced);
      if (produced > 0) {
        if (written + produced > MAX_BMP || file.write(out.get(), produced) != produced) break;
        written += produced;
      }
      if (status == InflateStream::Status::Done) {
        ok = true;
        break;
      }
      if (status == InflateStream::Status::Error) break;
    }
    file.close();
  }
  ok = ok && !dl->failed && dl->offset == len && dl->crc == crc && validBmp(TMP_PATH);
  if (!ok) {
    LOG_ERR("IMG", "Image rejected: %lu of %lu bytes, crc %08lx (want %08lx), bmp %u bytes",
            static_cast<unsigned long>(dl->offset), static_cast<unsigned long>(len),
            static_cast<unsigned long>(dl->crc), static_cast<unsigned long>(crc), static_cast<unsigned>(written));
    Storage.remove(TMP_PATH);
    return false;
  }
  Storage.remove(IMAGE_PATH);
  if (!Storage.rename(TMP_PATH, IMAGE_PATH)) {
    LOG_ERR("IMG", "Could not move the image into place");
    return false;
  }
  writeState(State{crc, 0});
  LOG_INF("IMG", "Pinned image %08lx stored (%lu -> %u bytes)", static_cast<unsigned long>(crc),
          static_cast<unsigned long>(len), static_cast<unsigned>(written));
  return true;
}

bool render(GfxRenderer& renderer) {
  HalFile file;
  if (!Storage.openFileForRead("IMG", IMAGE_PATH, file)) return false;
  Bitmap bitmap(file);
  if (bitmap.parseHeaders() != BmpReaderError::Ok) {
    LOG_ERR("IMG", "Stored image unreadable");
    return false;
  }
  renderSleepBitmap(renderer, bitmap);
  return true;
}

}  // namespace CalendarImage
