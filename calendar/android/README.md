# X3 Calendar — Android app

Phone side of the XCAL protocol ([docs/ble-protocol.md](../docs/ble-protocol.md)):
a foreground service that reads today's and tomorrow's agenda from the phone's
calendar provider and serves it to the Xteink X3 over BLE (GATT server +
low-power advertising). Kotlin, platform APIs only (no AndroidX), minSdk 31,
targetSdk 36, compileSdk 37.

## Status

Builds and passes its unit tests (2026-09-29): AGP 9.4.1 (built-in Kotlin),
Gradle 9.8.0 wrapper, Android Studio 2026.1's bundled JDK 25, SDK at
`D:\Android\Sdk`. 20/20 JVM tests reproduce the spec's test vectors.
Not yet run on the phone.

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
4. Pairing is started from the X3 (Settings → Calendar → Pair phone, firmware
   still to be written); Android then prompts for the passkey the X3 shows.

## Layout

| File | Role |
|---|---|
| `protocol/Xcal.kt` | UUIDs and constants (spec §3) |
| `protocol/TextRules.kt` | text sanitising / UTF-8 truncation (spec §4.2) |
| `protocol/PayloadCodec.kt` | HEADER / PAYLOAD encoders, STATUS decoder, CRC |
| `protocol/PosixTz.kt` | `ZoneId` → POSIX TZ rule (spec §6), with a fallback for platforms that expose no transition rules |
| `calendar/AgendaSource.kt` | `CalendarContract.Instances` query and inclusion rules |
| `SnapshotStore.kt` | immutable payload snapshots, debounced rebuilds |
| `XcalGattServer.kt` | GATT service, per-connection pinning, long reads/writes, advertising |
| `XcalService.kt` | foreground service, triggers, notification |
| `MainActivity.kt` | status + actions |
| `BootReceiver.kt` | restart after boot/update |

## Known gaps / to verify on the device

- Spec §11 risks R2 (passkey prompt when the X3 initiates pairing) and R3
  (`PERMISSION_READ_ENCRYPTED_MITM` rejects unbonded reads). Check R3 early with
  nRF Connect from another phone: reading HEADER without pairing must fail.
- Android's `ZoneRules.getTransitionRules()` may be empty on some builds; the
  app then derives the rule from upcoming transitions (covered by a unit test on
  the JVM, not yet on Android).
- No launcher icon yet (system default).
