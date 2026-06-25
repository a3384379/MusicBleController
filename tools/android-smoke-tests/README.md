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
- QRC Cache
- QQMusic Dir

Optional WARN means the device may not have started the foreground service or may not have QQMusic/QRC cache ready. This does not necessarily mean PlayerAgent is broken.

Optional FAIL is reserved for severe evidence such as FATAL/ANR or GATT/advertising failure without recovery success.

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
