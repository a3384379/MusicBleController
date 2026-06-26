#!/usr/bin/env bash
set -euo pipefail

OUT_DIR="${OUT_DIR:?OUT_DIR is required}"
ADB_BIN="${ADB_BIN:?ADB_BIN is required}"
DEVICE_ID="${DEVICE_ID:?DEVICE_ID is required}"

"$ADB_BIN" -s "$DEVICE_ID" logcat -d > "$OUT_DIR/sony_logcat.log"
grep -E "PlayerAgent|ControlServiceAutoStart|MediaSessionReader|BleGattServer|BLE-A|BLE-ADV|BLE-GATT|BLE-RECOVERY|BLE-DIAG|Qrc|Lyric|AlbumArt|FullLyrics|FATAL EXCEPTION|ANR|AndroidRuntime" \
  "$OUT_DIR/sony_logcat.log" > "$OUT_DIR/sony_filtered.log" || true

if [[ ! -s "$OUT_DIR/sony_logcat.log" ]]; then
  echo "logcat output is empty" >&2
  exit 1
fi
