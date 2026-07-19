# Contributing to MusicBleController

Thank you for helping improve MusicBleController. This project spans a Sony
Android device, an iPhone, Bluetooth Low Energy, QQ Music metadata and local QRC
lyrics, so reproducible device information is especially valuable.

## Before opening an issue

1. Search existing issues and the architecture documents in `docs/`.
2. Confirm the problem occurs with QQ Music for Android; other music sources are
   currently outside the supported scope.
3. Record the Sony model, Android version, iPhone model, iOS version and QQ Music
   version.
4. Remove account details, device identifiers and unrelated personal data from
   logs before attaching them.

Use the bug-report or device-compatibility issue form so the required context is
captured consistently.

## Development setup

```bash
# Android tests and debug build
bash gradlew :PlayerAgentApp:testDebugUnitTest :PlayerAgentApp:assembleDebug

# Fast checks against already installed apps
./tools/smoke/run_all_smoke_tests.sh --quick --json
```

The iOS app must be built with Xcode and tested on a physical iPhone. Update the
sample bundle identifiers, signing team and App Group before installing it.

## Architecture boundaries

Before changing code, read `CODEx.md` and the matching architecture documents.
In particular, do not change BLE UUIDs, existing command names, AlbumArt headers,
FullLyrics payloads or the QRC decryption algorithm without an explicit,
backward-compatible protocol plan.

`PlayerAgentApp` is the active Sony-side implementation. `ControllerApp` is a
legacy compatibility module and should not be changed unless the task explicitly
requires it.

## Pull requests

- Keep each pull request focused on one problem.
- Explain the root cause, user-visible impact and compatibility implications.
- Add or update tests for protocol, cache and queue-policy changes.
- Include the exact smoke-test report summary and real-device validation when a
  change affects BLE, lyrics, artwork, reconnect behavior or Live Activities.
- Do not commit device logs, provisioning profiles, signing keys, deployment
  state files or user media caches.

By contributing, you confirm that you have the right to submit the code. A
project-wide license has not yet been selected by the owner, so external code
contributions should not be merged until the licensing terms are clarified.
