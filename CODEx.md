# MusicBleController Development Workflow

This file defines the local Codex workflow for MusicBleController changes.

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

## Shortcut

Codex can use:

```bash
./tools/ios-smoke-tests/codex_check.sh
```

It runs quick smoke, reads `report.json`, prints `PASS` or `FAIL`, and exits:

- `0` for PASS
- `1` for FAIL
