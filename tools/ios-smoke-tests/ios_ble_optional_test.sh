#!/usr/bin/env bash
set -euo pipefail

OUT_DIR="${OUT_DIR:-/tmp/music_ble_ios_smoke/manual}"
LOG_FILE="${1:-$OUT_DIR/ios_ble.log}"
RESULT_FILE="${OUT_DIR}/optional_results.tsv"

: > "$RESULT_FILE"

record() {
  local test="$1"
  local result="$2"
  local detail="$3"
  printf 'optional\t%s\t%s\t%s\n' "$test" "$result" "$detail" >> "$RESULT_FILE"
}

if [[ ! -s "$LOG_FILE" ]]; then
  record "BLE Log" "SKIPPED" "ios_ble.log missing"
  cat "$RESULT_FILE"
  exit 0
fi

if grep -Eq 'scanSony called|start scan|scan start|\\[BLE-Reconnect\\] start scan|\\[BLE-iOS\\] start scan' "$LOG_FILE"; then
  record "BLE Scan" "PASS" "scan activity found"
else
  record "BLE Scan" "WARN" "iOS did not start scan"
fi

if grep -q 'didDiscover' "$LOG_FILE"; then
  record "BLE Discover" "PASS" "Sony advertisement discovered"
else
  record "BLE Discover" "WARN" "Sony not discovered or not advertising"
fi

if grep -q 'didConnect' "$LOG_FILE"; then
  record "BLE Connect" "PASS" "didConnect found"
else
  record "BLE Connect" "WARN" "didConnect not found"
fi

if grep -Eq 'notify subscribed|notify.*subscribed|subscribed' "$LOG_FILE"; then
  record "Notify Subscribed" "PASS" "notify subscription found"
else
  record "Notify Subscribed" "WARN" "notify subscription not found"
fi

if grep -Eq 'playbackState|PlaybackState' "$LOG_FILE"; then
  record "PlaybackState" "PASS" "playbackState found"
else
  record "PlaybackState" "WARN" "playbackState not found"
fi

if grep -Eq 'connectionDisplayState connected|displayState=connected|state=healthy|health=healthy' "$LOG_FILE"; then
  record "Connection Healthy" "PASS" "connected/healthy state found"
else
  record "Connection Healthy" "WARN" "connected/healthy state not found"
fi

if grep -Eq 'NowPlayingDiagnosticSnapshot|NowDiag|SystemHealth|GET_LYRIC_DIAGNOSTIC|LyricsDiag-iOS' "$LOG_FILE"; then
  record "Diagnostics Snapshot" "PASS" "diagnostic activity found"
else
  record "Diagnostics Snapshot" "SKIPPED" "no diagnostic activity observed"
fi

cat "$RESULT_FILE"
