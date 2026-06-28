#!/usr/bin/env bash
set -euo pipefail

OUT_DIR="${OUT_DIR:-/tmp/music_ble_ios_smoke/manual}"
IOS_DEVICE_ID="${IOS_DEVICE_ID:?IOS_DEVICE_ID is required}"
BUNDLE_ID="${BUNDLE_ID:-com.sqz.IOSBleFeasibility}"
DEST_NAME="${1:-ios_ble.log}"

mkdir -p "$OUT_DIR"

copy_log_once() {
  local source_path="$1"
  rm -f "$OUT_DIR/$DEST_NAME"
  xcrun devicectl --timeout "${IOS_LOG_COPY_TIMEOUT_SEC:-10}" device copy from \
    --device "$IOS_DEVICE_ID" \
    --domain-type appDataContainer \
    --domain-identifier "$BUNDLE_ID" \
    --source "$source_path" \
    --destination "$OUT_DIR/$DEST_NAME" \
    >/tmp/ios_smoke_copy_log.out 2>/tmp/ios_smoke_copy_log.err
}

copy_log() {
  local attempt
  for attempt in 1 2 3; do
    if copy_log_once "Documents/Logs/ios_ble.log" && [[ -s "$OUT_DIR/$DEST_NAME" ]] &&
      grep -Eq 'BLE-iOS|BLE-Reconnect|AppMode|Preferences|App launch|SmokeTest' "$OUT_DIR/$DEST_NAME"; then
      return 0
    fi
    if copy_log_once "Documents/Logs/ios_lyrics_timeline.log" && [[ -s "$OUT_DIR/$DEST_NAME" ]] &&
      grep -Eq 'BLE-iOS|BLE-Reconnect|AppMode|Preferences|App launch|SmokeTest' "$OUT_DIR/$DEST_NAME"; then
      return 0
    fi
    sleep 3
  done
  return 1
}

if ! copy_log; then
  echo "Unable to copy a non-empty iOS log with expected smoke keywords: $OUT_DIR/$DEST_NAME" >&2
  exit 1
fi

xcrun devicectl device copy from \
  --device "$IOS_DEVICE_ID" \
  --domain-type appDataContainer \
  --domain-identifier "$BUNDLE_ID" \
  --source Documents/Logs/ios_ble.old.log \
  --destination "$OUT_DIR/ios_ble.old.log" \
  >/tmp/ios_smoke_copy_old_log.out 2>/tmp/ios_smoke_copy_old_log.err &
old_copy_pid="$!"
old_remaining="${IOS_LOG_COPY_TIMEOUT_SEC:-5}"
while kill -0 "$old_copy_pid" 2>/dev/null; do
  if [[ "$old_remaining" -le 0 ]]; then
    pkill -P "$old_copy_pid" 2>/dev/null || true
    kill "$old_copy_pid" 2>/dev/null || true
    wait "$old_copy_pid" 2>/dev/null || true
    break
  fi
  sleep 1
  old_remaining=$((old_remaining - 1))
done
wait "$old_copy_pid" 2>/dev/null || true

if [[ ! -s "$OUT_DIR/$DEST_NAME" ]]; then
  echo "iOS log is missing or empty: $OUT_DIR/$DEST_NAME" >&2
  exit 1
fi

if ! grep -Eq 'BLE-iOS|BLE-Reconnect|AppMode|Preferences|App launch|SmokeTest' "$OUT_DIR/$DEST_NAME"; then
  echo "iOS log does not contain expected smoke keywords" >&2
  exit 1
fi

echo "$OUT_DIR/$DEST_NAME"
