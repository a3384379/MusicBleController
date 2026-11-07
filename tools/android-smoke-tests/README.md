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

The build script prefers Android Studio JBR when it exists, including when the
parent shell still exports an incompatible Java 8 runtime:

```text
/Volumes/雷电/Android Studio.app/Contents/jbr/Contents/Home
```

On CI or machines without that local JBR path, the existing `JAVA_HOME` remains
in effect.

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
- BLE Health Watchdog
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

Sony-only歌词矩阵还会使用
`com.example.playeragent.debug.REFRESH_CURRENT_LYRIC`，经同一个 Debug Receiver
转发给非导出的前台服务；测试脚本不得直接从 shell 启动该服务。

The BLE optional check sends `START_BLE_SERVICE`; the health watchdog also sends
`com.example.playeragent.debug.RECOVER_BLE_STACK`. Both respect
`--no-debug-control`. With that option, those checks only analyze existing logs:
they do not send debug broadcasts, wait for debug actions, or refresh logcat.
The suite still performs its normal device checks, app launch and log collection
(and build/install in full mode). The app may auto-start its service itself.
This option does not make the entire suite read-only.

The receiver rejects cold-start operations with `permission_missing` unless BLE
runtime permissions are granted: CONNECT and ADVERTISE on Android 12+, or fine
location on older supported versions. STOP calls `stopService` without starting
a new foreground service and remains available without BLE permissions.

The service also handles a platform `SecurityException` during foreground
promotion. That failed instance stops, returns `START_NOT_STICKY` if a command
arrives, and does not initialize BLE/history workers or execute normal teardown
for resources it never created. Recovery protects permission checks and adapter
reads against a permission race. This is a local lifecycle change; BLE messages
and the non-exported service boundary remain unchanged.

`POST_NOTIFICATIONS` is not a system prerequisite for launching an FGS; the
service still supplies its notification. The Activity retains its own existing
permission workflow. See Android's [FGS runtime checks](https://developer.android.com/about/versions/14/changes/fgs-types-required#system-runtime-checks)
and [notification permission guidance](https://developer.android.com/develop/ui/compose/notifications/notification-permission).

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
- `ble_health_watchdog.json`: passive health evidence and whether RECOVER was attempted
- `playback_diff_flow.json`: parsed PlaybackDiff metrics from Sony logcat

`debugControlStartResult` distinguishes `skipped: permission_missing`,
`failed: permission_denied`, `received; forwarding not confirmed`, and
`forwarded to service`. Receiving a broadcast alone does not prove the service
started; GATT/advertising logs are evaluated separately.

The watchdog records `debugControlEnabled`, `recoverCommandAttempted`,
`recoverCommandRc` and `recoverCommandSkipReason`. An unattempted command has
`recoverCommandRc: null`; skip reasons include `debug_control_disabled`,
`adb_device_missing`, and `logcat_missing`. A broadcast return code of zero only
means the broadcast command completed, not that BLE recovery succeeded. FATAL/ANR
evidence remains a failure even when debug control is disabled. Snapshot refresh
preserves previously captured severe lines and retains the old snapshot if adb
collection fails, without duplicating healthy events and GATT-start counts.

## Regression Tests

Device-free tests exercise the real scripts with a fake adb, including suite flag
propagation, command attempts, permission refusals, passive mode and severe-log
preservation:

```bash
python3 -m unittest discover -s tools/android-smoke-tests/tests -p 'test_*.py' -v
```

`BleServicePermissionTest` runs real Android lifecycle cases on API 34+. Revoke
CONNECT, ADVERTISE and SCAN **before instrumentation** for the two `denied*`
cases; grant them and leave POST_NOTIFICATIONS denied for the `granted*` case.
Permission revocation during instrumentation can kill the target process, so the
test deliberately does not change device permission state itself. Use a
disposable emulator or a dedicated test device. The direct FGS case bypasses the
receiver and waits beyond the device's configured foreground-promotion timeout.
When running the whole class, unmatched permission scenarios are skipped using
Assume. Acceptance requires explicitly running both permission groups and checking
that all three selected cases pass without assumption skips.

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
