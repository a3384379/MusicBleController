#!/usr/bin/env bash
set -euo pipefail

OUT_DIR="${OUT_DIR:?OUT_DIR is required}"
ADB_BIN="${ADB_BIN:?ADB_BIN is required}"
DEVICE_ID="${DEVICE_ID:?DEVICE_ID is required}"
PACKAGE_NAME="${PACKAGE_NAME:-com.example.playeragent}"

"$ADB_BIN" -s "$DEVICE_ID" logcat -c || true
"$ADB_BIN" -s "$DEVICE_ID" shell monkey -p "$PACKAGE_NAME" 1
sleep 5

pid="$("$ADB_BIN" -s "$DEVICE_ID" shell pidof "$PACKAGE_NAME" 2>/dev/null | tr -d '\r' | awk '{print $1}')"
if [[ -z "$pid" ]]; then
  "$ADB_BIN" -s "$DEVICE_ID" logcat -d -t 500 > "$OUT_DIR/launch_logcat.log" || true
  echo "Process not found for $PACKAGE_NAME" >&2
  exit 1
fi
echo "$pid" > "$OUT_DIR/app_pid.txt"
"$ADB_BIN" -s "$DEVICE_ID" logcat -d -t 1000 > "$OUT_DIR/launch_logcat.log"

if grep -Eiq "FATAL EXCEPTION|\\bANR\\b" "$OUT_DIR/launch_logcat.log"; then
  echo "FATAL/ANR found after launch" >&2
  exit 1
fi
