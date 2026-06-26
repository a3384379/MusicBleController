# Android/Sony Smoke Test Suite

This suite validates the Sony `PlayerAgentApp` from a Mac using `adb`.

It is Android-only:

- It does not use `devicectl`.
- It does not operate iPhone.
- It does not require iOS to be online.
- It does not assert the cross-device BLE connection.

## Prerequisites

- Sony device connected over USB.
- USB debugging enabled and authorized.
- Android platform-tools `adb` available in `PATH`, `ADB_BIN`, `ANDROID_HOME`, `ANDROID_SDK_ROOT`, or `~/Library/Android/sdk/platform-tools/adb`.
- For full build, Gradle must be able to build `:PlayerAgentApp:assembleDebug`.

If `JAVA_HOME` is not set, the build script uses Android Studio JBR when it exists:

```text
/Volumes/雷电/Android Studio.app/Contents/jbr/Contents/Home
```

Existing `JAVA_HOME` always wins.

## Commands

Full test:

```bash
./tools/android-smoke-tests/run_android_smoke_tests.sh --json
```

Quick test:

```bash
./tools/android-smoke-tests/run_android_smoke_tests.sh --quick --json
```

Disable debug service control:

```bash
./tools/android-smoke-tests/run_android_smoke_tests.sh --quick --json --no-debug-control
```

Specified device:

```bash
ANDROID_DEVICE_ID=001045e392002101 ./tools/android-smoke-tests/run_android_smoke_tests.sh --json
```

Equivalent:

```bash
./tools/android-smoke-tests/run_android_smoke_tests.sh --device 001045e392002101 --json
```

## Required Tests

- Android Build
- Device Check
- APK Install
- App Launch
- Crash Check
- Logcat
- App Data
- Cache Dirs

In quick mode, build/install are marked PASS as skipped by request.

## Optional Tests

- BLE Service
- Control Service Auto-start
- PlaybackDiff Flow
- CurrentWord Flow
- QRC Cache
- QQMusic Dir

Optional WARN means the device may not have started the foreground service, may be missing runtime permissions, or may not have QQMusic/QRC cache ready. This does not necessarily mean PlayerAgent is broken.

Optional FAIL is reserved for severe evidence such as FATAL/ANR or GATT/advertising failure without recovery success.

`Control Service Auto-start` reads Sony logcat after app launch and checks that
the main app requested the foreground control service without the user pressing
the start button. It verifies:

- `[ControlServiceAutoStart] enabled=true`
- `[ControlServiceAutoStart] start requested` or `skip reason=already_started`
- `Foreground service started`
- `[BleGattServer] started` or `already started`
- `[MediaSessionReader] registered` or `already registered`

If Bluetooth/notification permissions are missing it returns WARN with
`permission_missing`. If no adb device is online, Device Check fails with
`adb_device_missing`.

`PlaybackDiff Flow` reads Sony logcat only. It reports:

- snapshotBuildCount
- diffCount
- pushCount
- skipCount
- skipRatio
- trackChangedCount / wordChangedCount / positionJumpCount

If no iPhone subscriber is visible in logcat, it returns SKIPPED. If a subscriber is visible but there are no PlaybackDiff decisions yet, it returns WARN. During a real 2-3 minute playback session with an iPhone subscriber, `skipCount` should be greater than `pushCount`.

`CurrentWord Flow` reads Sony logcat only. It reports lightweight current-word push count, skip count, average interval, and last push cost. If no iPhone subscriber is visible it returns SKIPPED; with a subscriber and a fast lyric song it should show `pushCount > 0`.

## Debug-only Service Control

Debug builds include `PlayerAgentDebugControlReceiver` from `PlayerAgentApp/src/debug`.

The smoke suite attempts:

```bash
adb shell am broadcast \
  -a com.example.playeragent.debug.START_BLE_SERVICE \
  -p com.example.playeragent
```

Then it waits briefly and checks logcat for:

- `[DebugControl] received action=...`
- `[BLE-A] GATT server started`
- `[BLE-A] service added success`
- `BLE advertising started`

Release builds do not include this receiver. The formal `PlayerAgentForegroundService` remains `exported=false`.

Use `--no-debug-control` to keep the previous passive behavior.

## Reports

Each run creates:

```text
/tmp/music_ble_android_smoke/<timestamp>/
```

Important files:

- `report.md`: human-readable summary
- `report.json`: machine-readable summary for Codex
- `sony_logcat.log`: full captured logcat
- `sony_filtered.log`: filtered PlayerAgent/BLE/QRC/lyrics/artwork logs
- `failure_excerpt.log`: key excerpt when failures or warnings exist
- `file_checks.tsv`: app/cache/QQMusic path checks
- `debug_control.json`: whether debug control receiver was available and whether START was attempted
- `playback_diff_flow.json`: parsed PlaybackDiff metrics from Sony logcat

## Why It Does Not Start The Service Directly

`PlayerAgentForegroundService` is `exported=false`. The smoke suite launches the main app with `monkey` and only treats service/GATT/advertising logs as optional. If the user has not tapped the service start button, BLE service checks may WARN rather than fail Required tests.

## Why It Does Not Test iPhone BLE

This suite is for Sony-only regression. Cross-device BLE behavior still requires iOS smoke plus real-device manual validation when protocol or connection behavior changes.

## Common Failures

- `adb not found`: install Android platform-tools or set `ADB_BIN`.
- `unauthorized`: confirm USB debugging authorization on Sony.
- multiple devices: pass `--device <id>` or set `ANDROID_DEVICE_ID`.
- build failed: inspect `android_build_stderr.log`.
- install failed: inspect `android_install_stderr.log`.
- launch failed: inspect `launch_logcat.log`.
- BLE Service WARN: PlayerAgent BLE foreground service may not be started.
- Debug control unavailable: installed app may not be a debug build, or the receiver is not present.
