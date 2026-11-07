#!/bin/bash
set -euo pipefail
lab_root="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$lab_root/../.." && pwd)"
python3 "$lab_root/prepare_native.py" "$@"
bash "$repo_root/gradlew" -p "$lab_root/receiver" :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
mkdir -p "$lab_root/artifacts"
cp "$lab_root/receiver/app/build/outputs/apk/debug/app-debug.apk" "$lab_root/artifacts/PhoneAudioLab-debug.apk"
shasum -a 256 "$lab_root/artifacts/PhoneAudioLab-debug.apk" > "$lab_root/artifacts/PhoneAudioLab-debug.apk.sha256"
