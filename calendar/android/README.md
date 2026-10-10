# X3 Calendar — Android app

Phone side of the XCAL protocol ([docs/ble-protocol.md](../docs/ble-protocol.md)):
a foreground service that reads today's and tomorrow's agenda from the phone's
calendar provider and serves it to the Xteink X3 over BLE (GATT server +
low-power advertising). Kotlin, platform APIs only (no AndroidX), minSdk 31,
targetSdk 36, compileSdk 37.

## Status

In daily use with an X3 since October 2026 (Android 16). Built with AGP 9.4.1
(built-in Kotlin), the Gradle 9.8.0 wrapper and Android Studio's bundled JDK;
the JVM unit tests reproduce the spec's test vectors.

## Features

- Serves today's and tomorrow's events (all calendars visible in the profile
  the app runs in; cancelled and declined events are left out).
- **Hidden events**: a box on the app's screen hides all-day events whose
  title or location contains any of the listed phrases (one per line,
  case-insensitive), e.g. a daily working-location event. Timed events are
  never hidden by it. The list is stored on the phone only.
- **Text cleanup** (spec §4.2): styled Unicode letters (bold/script
  mathematical letters, fullwidth, circled, small capitals) become plain
  letters, and emoji / pictographs the X3 fonts cannot draw are dropped.
- Keeps the X3's clock and time zone in step with the phone.

## Build

- Android Studio: *File → Open* → this folder.
- Command line (PowerShell):
  ```powershell
  $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
  .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
  .\gradlew.bat :app:installDebug   # phone connected with USB debugging
  ```
- `local.properties` (not checked in) points at the SDK; `ANDROID_HOME` is
  also set machine-wide.
- **Phones with a work profile:** calendars are per profile, so install the
  app in the profile whose calendars should appear on the X3. `installDebug`
  installs into *every* profile; list profiles with `adb shell pm list users`,
  then remove the unwanted copy with
  `adb uninstall --user <id> io.github.mtsai7.xcal`, or install into one
  profile only with
  `adb install -r --user <id> app/build/outputs/apk/debug/app-debug.apk`.
  Only one copy should run: they would both advertise.

## First run on the phone

1. Open **X3 Calendar** → *Grant permissions* (Nearby devices, Calendar,
   Notifications).
2. *Battery optimisation exemption* → allow. On ASUS phones also check
   Settings → Battery → app auto-start / "Mobile Manager" restrictions if the
   service gets killed.
3. *Start serving*. The notification shows "Waiting for the X3's first sync"
   once advertising is up.
4. Pairing is started by the X3: with no phone paired, its next calendar wake
   shows a six-digit passkey; Android then asks for it.
5. Optional: list phrases under *Hide all-day events…* and tap *Save hidden
   events*.

## Layout

| File | Role |
|---|---|
| `protocol/Xcal.kt` | UUIDs and constants (spec §3) |
| `protocol/TextRules.kt` | text sanitising / UTF-8 truncation (spec §4.2) |
| `protocol/PayloadCodec.kt` | HEADER / PAYLOAD encoders, STATUS decoder, CRC |
| `protocol/PosixTz.kt` | `ZoneId` → POSIX TZ rule (spec §6), with a fallback for platforms that expose no transition rules |
| `calendar/AgendaSource.kt` | `CalendarContract.Instances` query and inclusion rules |
| `calendar/HiddenEvents.kt` | the user's hide-list for all-day events |
| `SnapshotStore.kt` | immutable payload snapshots, debounced rebuilds |
| `XcalGattServer.kt` | GATT service, per-connection pinning, long reads/writes, advertising |
| `XcalService.kt` | foreground service, triggers, notification |
| `MainActivity.kt` | status + actions |
| `BootReceiver.kt` | restart after boot/update |

## Known gaps

- Android's `ZoneRules.getTransitionRules()` may be empty on some builds; the
  app then derives the rule from upcoming transitions (covered by a unit test on
  the JVM, not yet on Android).
- Some phones (ASUS among them) throttle background apps; without the battery
  optimisation exemption the X3 may intermittently report "Phone not found".
- No launcher icon yet (system default).
