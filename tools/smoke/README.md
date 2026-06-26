# Cross-device Smoke Test Suite

This is the unified smoke entrypoint for MusicBleController.

It orchestrates:

- iOS-only smoke: `tools/ios-smoke-tests/run_ios_smoke_tests.sh`
- Android/Sony-only smoke: `tools/android-smoke-tests/run_android_smoke_tests.sh`

It does not replace manual iPhone + Sony validation for subjective UI behavior or full cross-device BLE protocol checks.

## Commands

Full:

```bash
./tools/smoke/run_all_smoke_tests.sh --json
```

Quick:

```bash
./tools/smoke/run_all_smoke_tests.sh --quick --json
```

iOS only:

```bash
./tools/smoke/run_all_smoke_tests.sh --ios-only --quick --json
```

Android only:

```bash
./tools/smoke/run_all_smoke_tests.sh --android-only --quick --json
```

Android without debug service control:

```bash
./tools/android-smoke-tests/run_android_smoke_tests.sh --quick --json --no-debug-control
```

Specified devices:

```bash
./tools/smoke/run_all_smoke_tests.sh \
  --ios-device <iphone-id> \
  --android-device <adb-serial> \
  --quick --json
```

CurrentWord long-play validation:

```bash
./tools/smoke/current_word_long_play_test.sh --duration 90 --json
```

Use this when validating Latency Optimization V2.3. It requires both iPhone and Sony over USB, the iOS app connected to Sony, and QQMusic actively playing a track with word timing. It is intentionally not part of the normal quick/full smoke suites because it is a manual 60-120 second performance window.

Reconnect Sync V2.8 validation:

```bash
./tools/smoke/reconnect_sync_v28_test.sh --duration 30 --json
```

Use this when validating reconnect recovery. It relaunches the iOS app to trigger a reconnect window, then reads only iOS `ios_ble.log` and Sony `logcat` to verify reconnect `playbackState`, `currentWord`, and `albumArtOffer` recovery. It does not change BLE UUIDs or payload protocols.

## Device Behavior

When neither `--ios-only` nor `--android-only` is used:

- iPhone + Sony connected: run both suites.
- only iPhone connected: run iOS, mark Android `SKIPPED`.
- only Sony connected: run Android, mark iOS `SKIPPED`.
- neither connected: overall `FAIL`.

When a suite is explicitly requested, missing that device is `FAIL`.

## Arguments

- `--quick`: pass `--quick` to child suites.
- `--json`: print machine-readable final summary.
- `--ios-only`: equivalent to `--skip-android`; missing iPhone fails.
- `--android-only`: equivalent to `--skip-ios`; missing Android/Sony fails.
- `--skip-ios`: skip iOS suite.
- `--skip-android`: skip Android suite.
- `--ios-device <id>`: pass specific iPhone id.
- `--android-device <id>`: pass specific adb serial.
- `--output <dir>`: use a fixed output directory.

## Reports

Default output:

```text
/tmp/music_ble_smoke/<timestamp>/
```

CurrentWord long-play output:

```text
/tmp/current_word_long_play/<timestamp>/
```

AlbumArt V2.7 long-play command:

```bash
./tools/smoke/album_art_v27_long_play_test.sh --duration 120 --json
```

AlbumArt V2.7 output:

```text
/tmp/album_art_v27/<timestamp>/
```

Reconnect Sync V2.8 output:

```text
/tmp/reconnect_sync_v28/<timestamp>/
```

Files:

- `report.md`: human-readable CurrentWord or AlbumArt stability report.
- `report.json`: machine-readable CurrentWord or AlbumArt metrics.
- `ios_ble.log`: copied iOS app log after the test window.
- `sony_logcat.log`: Sony logcat captured after the test window.
- `ios_current_word_filtered.log`: iOS filtered CurrentWord/Lyrics log.
- `sony_current_word_filtered.log`: Sony filtered CurrentWord/PlaybackDiff log.
- `ios_reconnect_filtered.log`: iOS filtered reconnect recovery log.
- `sony_reconnect_filtered.log`: Sony filtered reconnect sync log.

Files:

- `report.md`: human-readable aggregate report.
- `report.json`: machine-readable aggregate report.
- `ios/report.json`: iOS child report when iOS suite ran.
- `android/report.json`: Android child report when Android suite ran.

## Result Rules

PASS:

- All executed suites have Required tests passing.
- No Optional serious FAIL.

WARN:

- Required tests pass.
- A suite is skipped because only one device is connected.
- Or optional WARN exists.

FAIL:

- Any executed suite has Required FAIL.
- Explicitly requested suite cannot run because its device is missing.
- Child script exits abnormally without a report.

Exit code:

- `0`: PASS or WARN.
- `1`: FAIL.

## Relationship To Platform-specific Smoke

Use this suite after cross-end or broad workflow changes, especially when both iPhone and Sony are connected.

Use platform-specific suites when only one side changed:

- iOS-only: `tools/ios-smoke-tests/run_ios_smoke_tests.sh`
- Android/Sony-only: `tools/android-smoke-tests/run_android_smoke_tests.sh`

## Non-goals

- It does not delete caches.
- It does not operate QQMusic.
- It does not force-stop user apps.
- It does not prove Live Activity visual layout, BLE subjective latency, or lyrics/artwork UX correctness.

## Debug-only Sony Service Control

Android/Sony smoke uses the debug-only receiver in debug builds to start the
PlayerAgent BLE foreground service before checking GATT/advertising logs.

This is not a BLE protocol feature and is not present in release builds.
