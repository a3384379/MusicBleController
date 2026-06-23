#!/usr/bin/env bash
set -euo pipefail

OUT_DIR="${OUT_DIR:-/tmp/music_ble_ios_smoke/manual}"
IOS_DEVICE_ID="${IOS_DEVICE_ID:?IOS_DEVICE_ID is required}"
BUNDLE_ID="${BUNDLE_ID:-com.sqz.IOSBleFeasibility}"
WAIT_SECONDS="${WAIT_SECONDS:-5}"

mkdir -p "$OUT_DIR"

xcrun devicectl device process launch \
  --device "$IOS_DEVICE_ID" \
  --terminate-existing \
  "$BUNDLE_ID" \
  "$@" | tee -a "$OUT_DIR/ios_launch.log"

sleep "$WAIT_SECONDS"
