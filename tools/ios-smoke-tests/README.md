# iOS Smoke Test Suite

This suite verifies the iPhone app side of MusicBleController without controlling Sony Android.

## What It Checks

- iOS app build and install
- iPhone app launch
- app container log access
- smoke-test preferences persistence
- app container file access
- optional BLE activity based only on iOS logs
- optional AlbumArt flow validation based only on iOS logs
- Markdown and JSON reports for Codex-friendly parsing

## Requirements

- Xcode command line tools
- `xcrun devicectl`
- a paired and available iPhone
- an already configured signing profile for the iOS app

These tests are iOS-only. They do not use Android Debug Bridge, Android device logs, Gradle Android builds, or Sony app automation.

## Commands

Codex workflow shortcut:

```bash
./tools/ios-smoke-tests/codex_check.sh
```

Full test:

```bash
./tools/ios-smoke-tests/run_ios_smoke_tests.sh
```

Quick test against the currently installed app:

```bash
./tools/ios-smoke-tests/run_ios_smoke_tests.sh --quick
```

Machine-readable output:

```bash
./tools/ios-smoke-tests/run_ios_smoke_tests.sh --quick --json
```

Specify an iPhone:

```bash
IOS_DEVICE_ID=<device-id> ./tools/ios-smoke-tests/run_ios_smoke_tests.sh
./tools/ios-smoke-tests/run_ios_smoke_tests.sh --device <device-id>
```

Skip build or install:

```bash
./tools/ios-smoke-tests/run_ios_smoke_tests.sh --skip-build
./tools/ios-smoke-tests/run_ios_smoke_tests.sh --skip-install
```

Disable optional BLE checks:

```bash
./tools/ios-smoke-tests/run_ios_smoke_tests.sh --no-ble-optional
```

Analyze AlbumArt flow from an existing iOS log:

```bash
./tools/ios-smoke-tests/ios_album_art_flow_test.sh /tmp/ios_ble.log
./tools/ios-smoke-tests/ios_album_art_flow_test.sh /tmp/ios_ble.log --album-art-id <id>
```

Write artifacts to a known directory:

```bash
./tools/ios-smoke-tests/run_ios_smoke_tests.sh --output /tmp/music_ble_ios_smoke/manual
```

## Required vs Optional

Required tests decide the process exit code:

- iOS Build
- iPhone Install
- App Launch
- Log File
- Preferences
- File Checks

Optional BLE tests inspect existing iOS logs for scan, discover, connect, notify subscription, playbackState, and health signals. Optional `WARN` or `SKIPPED` results do not fail the suite because Sony may be offline or not advertising during an iOS-only run.

AlbumArt Flow is also optional. It inspects only iOS logs for `albumArtOffer`, preview/HQ requests, binary start/chunk/end, cache saves, enhancement, fallback, timeout cleanup, and final display quality. Sony offline or missing album art logs produce `WARN`/`SKIPPED`, not a required failure. A stuck binary transfer is reported as optional `FAIL` in the report for diagnosis.

## Reports

Each run creates a timestamped directory under:

```text
/tmp/music_ble_ios_smoke/<timestamp>/
```

Important files:

- `report.md`: human-readable summary
- `report.json`: machine-readable summary for Codex
- `ios_ble.log`: copied iOS app log
- `failure_excerpt.log`: key log excerpt when a required test fails or an optional test warns
- `album_art_flow.json`: AlbumArt flow summary, if collected
- `required_results.tsv`: required test rows
- `optional_results.tsv`: optional test rows
- `file_checks.tsv`: app container file access summary

## Codex Workflow

Quick:

```bash
./tools/ios-smoke-tests/run_ios_smoke_tests.sh --quick --json
```

Full:

```bash
./tools/ios-smoke-tests/run_ios_smoke_tests.sh --json
```

Use quick after normal iOS UI, BLE manager, lyric, album art, diagnostics, reconnect, health, settings, or Live Activity changes.

Use full after changes to app launch, install behavior, UserDefaults, Preferences persistence, app container files, logging, build settings, or `project.pbxproj`.

Codex should read `report.json`. Required tests must pass. Optional BLE warnings do not fail the workflow.

## Common Failures

- No iPhone found: pass `--device <id>` or set `IOS_DEVICE_ID`.
- Multiple iPhones found: pass `--device <id>`.
- Build failed: open `ios_build.log`.
- Install failed: check signing and device trust.
- Log file missing: launch the app once, then rerun.
- Preferences failed: the installed app may not include the DEBUG smoke-test launch hook.
- Optional BLE warnings: Sony may be offline, not advertising, or not connected.
- AlbumArt Flow skipped/warned: Sony may be offline, no recent `albumArtOffer` was logged, or the current song has no artwork.
