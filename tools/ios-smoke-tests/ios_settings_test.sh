#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUT_DIR="${OUT_DIR:-/tmp/music_ble_ios_smoke/manual}"
IOS_DEVICE_ID="${IOS_DEVICE_ID:?IOS_DEVICE_ID is required}"
BUNDLE_ID="${BUNDLE_ID:-com.sqz.IOSBleFeasibility}"

mkdir -p "$OUT_DIR"

WAIT_SECONDS=3 "$SCRIPT_DIR/ios_launch.sh" --smoke-test-preferences
"$SCRIPT_DIR/ios_collect_logs.sh" ios_ble_after_preferences.log >/dev/null

if ! grep -q '\[SmokeTest\] preferences written' "$OUT_DIR/ios_ble_after_preferences.log"; then
  echo "Missing preferences written log" >&2
  exit 1
fi

if ! grep -q '\[SmokeTest\] preferences verified mode=debug artworkDisplaySize=200 lyricOffsetMs=300 autoReconnect=true' "$OUT_DIR/ios_ble_after_preferences.log"; then
  echo "Missing preferences verified log" >&2
  exit 1
fi

WAIT_SECONDS=3 "$SCRIPT_DIR/ios_launch.sh"
"$SCRIPT_DIR/ios_collect_logs.sh" ios_ble_after_preferences_restart.log >/dev/null

if ! grep -q '\[SmokeTest\] preferences persisted' "$OUT_DIR/ios_ble_after_preferences_restart.log"; then
  echo "Missing preferences persisted log" >&2
  exit 1
fi

echo "Preferences persisted"
