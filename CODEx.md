# MusicBleController Development Workflow

This file defines the local Codex workflow for MusicBleController changes.

## Before Any Code Change

Before editing business code, Codex must identify the task type and read the
matching architecture documents. This is mandatory because this repository has
cross-device state machines and BLE payload formats that are easy to break by
local-only changes.

Codex must state, before modifying code:

1. task type
2. documents read
3. boundaries that must not be changed
4. smoke tests or builds required after the change

Docs-only changes do not require build or smoke tests. They still require:

```bash
git diff --check
```

## Required Architecture Reading

| Task type | Required docs |
|---|---|
| iOS BLE connection / auto reconnect / Health Check | `docs/IOS_ARCHITECTURE.md`, `docs/RECONNECT_HEALTH_ARCHITECTURE.md`, `docs/BLE_PROTOCOL.md`, `docs/SMOKE_TEST_GUIDE.md` |
| AlbumArt / artwork / HQ / enhanced / cache / transfer timeout | `docs/ALBUM_ART_ARCHITECTURE.md`, `docs/BLE_PROTOCOL.md`, `docs/IOS_ARCHITECTURE.md`, `docs/SMOKE_TEST_GUIDE.md` |
| Lyrics / FullLyrics / LyricSecondary / translation / romanization / QRC / Recovery | `docs/LYRICS_ARCHITECTURE.md`, `docs/BLE_PROTOCOL.md`, `docs/PROJECT_OVERVIEW.md` |
| Settings / Preferences / UserDefaults | `docs/IOS_ARCHITECTURE.md`, `docs/SMOKE_TEST_GUIDE.md` |
| Smoke Test / automated test tooling | `docs/SMOKE_TEST_GUIDE.md`, `docs/CODEX_WORKFLOW.md` |
| Sony Android / GATT / advertising / BLE recovery | `docs/PROJECT_OVERVIEW.md`, `docs/BLE_PROTOCOL.md`, `docs/RECONNECT_HEALTH_ARCHITECTURE.md` |
| Cross-device protocol changes | `docs/BLE_PROTOCOL.md`, `docs/PROJECT_OVERVIEW.md` |

If a task spans multiple rows, read the union of all required docs.

## Protocol Change Policy

Protocol changes are forbidden by default unless the user explicitly asks for
them. This includes:

1. BLE UUIDs
2. command/status characteristic behavior
3. AlbumArt binary protocol
4. FullLyrics and LyricSecondary payload formats
5. playback, volume, and Live Activity control command names

If a task explicitly changes BLE protocol, FullLyrics, or AlbumArt binary
payloads, Codex must state whether the change is backward compatible with older
iOS/Sony builds.

## iOS Changes

After any iOS change that touches one of these areas, run the quick smoke test:

1. `BLETestManager.swift`
2. `ContentView.swift`
3. `FullLyricsView.swift`
4. `PreferencesView.swift`
5. Album art
6. Diagnostics
7. AutoReconnect
8. Health Check
9. Settings
10. Live Activity

Command:

```bash
./tools/ios-smoke-tests/run_ios_smoke_tests.sh --quick --json
```

Then read:

```text
report.json
```

## Pass Criteria

Quick smoke test pass criteria:

- Required: `PASS 6/6`
- Optional: `WARN` is allowed
- `summary.overall_result` must be `PASS`

If `summary.overall_result != "PASS"`, do not:

1. commit
2. push
3. merge

Analyze the failure first.

## When To Run Full Smoke Test

Run the full test when a change touches:

1. App launch flow
2. Install flow
3. UserDefaults
4. Preferences
5. App container files
6. Logging system
7. Build configuration
8. `project.pbxproj`

Command:

```bash
./tools/ios-smoke-tests/run_ios_smoke_tests.sh --json
```

## Android/Sony Changes

After Sony Android changes, run the Android-only smoke suite.

For normal Sony app changes:

```bash
./tools/android-smoke-tests/run_android_smoke_tests.sh --quick --json
```

For changes touching `build.gradle`, `AndroidManifest.xml`, app launch,
foreground service startup, permissions, or install behavior:

```bash
./tools/android-smoke-tests/run_android_smoke_tests.sh --json
```

If both iOS and Sony are changed, run both iOS smoke and Android smoke. If a
cross-device BLE protocol changed, smoke tests are only basic validation; real
iPhone + Sony manual validation is still required.

## Cross-device Smoke Entry

When a change spans iOS and Sony, or when both devices are connected and a broad
regression pass is useful, prefer the unified entry:

```bash
./tools/smoke/run_all_smoke_tests.sh --quick --json
```

For full cross-device smoke orchestration:

```bash
./tools/smoke/run_all_smoke_tests.sh --json
```

The unified entry automatically degrades when only iPhone or only Sony is
connected. A missing device is WARN/SKIPPED unless the suite was explicitly
requested with `--ios-only` or `--android-only`.

## Codex Result Analysis

Read `report.json`.

If `summary.overall_result == "PASS"`, output:

```text
Smoke Test PASS
Required x/x
Optional pass=<n> warn=<n> skipped=<n>
```

Then continue.

If `summary.overall_result == "FAIL"`, read:

```text
failure_excerpt.log
```

Identify which layer failed:

1. Build
2. Install
3. Launch
4. Logs
5. Preferences
6. BLE Optional

Fix the failure before committing.

## Git Workflow

After code changes:

1. Run Quick Smoke Test.
2. Analyze `report.json`.
3. If PASS, run:

```bash
git diff --check
```

4. Generate a concise commit message.
5. Commit.
6. Push only after the required checks pass.

Before the final response after code changes, Codex must report:

1. modified files
2. whether any protocol changed
3. whether iOS quick smoke was required and run
4. whether iOS full smoke was required and run
5. whether Android build was required and run
6. whether real-device manual validation is still needed

## Shortcut

Codex can use:

```bash
./tools/ios-smoke-tests/codex_check.sh
```

It runs quick smoke, reads `report.json`, prints `PASS` or `FAIL`, and exits:

- `0` for PASS
- `1` for FAIL
