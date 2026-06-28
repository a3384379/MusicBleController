#!/usr/bin/env bash
set -euo pipefail

OUT_DIR="${OUT_DIR:-/tmp/music_ble_ios_smoke/manual}"
IOS_DEVICE_ID="${IOS_DEVICE_ID:?IOS_DEVICE_ID is required}"
BUNDLE_ID="${BUNDLE_ID:-com.sqz.IOSBleFeasibility}"

mkdir -p "$OUT_DIR/file_checks"
TABLE="$OUT_DIR/file_checks.tsv"
DETAILS="$OUT_DIR/file_checks_details.env"
: > "$TABLE"
: > "$DETAILS"

record() {
  local path="$1"
  local exists="$2"
  local bytes="$3"
  printf '%s\t%s\t%s\n' "$path" "$exists" "$bytes" >> "$TABLE"
}

detail() {
  local key="$1"
  local value="$2"
  printf '%s=%s\n' "$key" "$value" >> "$DETAILS"
}

copy_path() {
  local source="$1"
  local dest="$2"
  xcrun devicectl device copy from \
    --device "$IOS_DEVICE_ID" \
    --domain-type appDataContainer \
    --domain-identifier "$BUNDLE_ID" \
    --source "$source" \
    --destination "$dest" \
    >/tmp/ios_smoke_copy_path.out 2>/tmp/ios_smoke_copy_path.err &
  local copy_pid="$!"
  local remaining="${IOS_FILE_COPY_TIMEOUT_SEC:-5}"
  while kill -0 "$copy_pid" 2>/dev/null; do
    if [[ "$remaining" -le 0 ]]; then
      pkill -P "$copy_pid" 2>/dev/null || true
      kill "$copy_pid" 2>/dev/null || true
      wait "$copy_pid" 2>/dev/null || true
      return 124
    fi
    sleep 1
    remaining=$((remaining - 1))
  done
  wait "$copy_pid"
}

file_bytes() {
  local path="$1"
  if [[ -s "$path" ]]; then
    wc -c < "$path" | tr -d ' '
  else
    echo "0"
  fi
}

list_path() {
  local source="$1"
  local dest="$2"
  xcrun devicectl device info files \
    --device "$IOS_DEVICE_ID" \
    --domain-type appDataContainer \
    --domain-identifier "$BUNDLE_ID" \
    --subdirectory "$source" \
    >"$dest" 2>"$dest.err" &
  local list_pid="$!"
  local remaining="${IOS_FILE_LIST_TIMEOUT_SEC:-5}"
  while kill -0 "$list_pid" 2>/dev/null; do
    if [[ "$remaining" -le 0 ]]; then
      pkill -P "$list_pid" 2>/dev/null || true
      kill "$list_pid" 2>/dev/null || true
      wait "$list_pid" 2>/dev/null || true
      echo "info files timed out for $source" >>"$dest.err"
      return 124
    fi
    sleep 1
    remaining=$((remaining - 1))
  done
  wait "$list_pid"
}

logs_dir_exists="false"
if list_path "Documents/Logs" "$OUT_DIR/file_checks/Logs.list"; then
  logs_dir_exists="true"
fi
detail "logsDirExists" "$logs_dir_exists"

ios_log_copied="false"
ios_log_local_path="$OUT_DIR/ios_ble.log"
ios_log_local_bytes="$(file_bytes "$ios_log_local_path")"
ios_log_device_copy_attempted="false"
ios_log_device_copy_result="not_attempted"

for attempt in 1 2 3; do
  ios_log_local_bytes="$(file_bytes "$ios_log_local_path")"
  if [[ "$ios_log_local_bytes" -gt 0 ]]; then
    ios_log_copied="true"
    ios_log_device_copy_result="local_artifact"
    break
  fi

  ios_log_device_copy_attempted="true"
  if copy_path "Documents/Logs/ios_ble.log" "$OUT_DIR/file_checks/ios_ble.log"; then
    copied_bytes="$(file_bytes "$OUT_DIR/file_checks/ios_ble.log")"
    if [[ "$copied_bytes" -gt 0 ]]; then
      cp "$OUT_DIR/file_checks/ios_ble.log" "$ios_log_local_path"
      ios_log_local_bytes="$copied_bytes"
      ios_log_copied="true"
      ios_log_device_copy_result="copy_success_attempt_$attempt"
      break
    fi
    ios_log_device_copy_result="copy_empty_attempt_$attempt"
  else
    ios_log_device_copy_result="copy_failed_or_timeout_attempt_$attempt"
  fi
  sleep 1
done

detail "iosLogCopied" "$ios_log_copied"
detail "iosLogLocalPath" "$ios_log_local_path"
detail "iosLogLocalBytes" "$ios_log_local_bytes"
detail "iosLogDeviceCopyAttempted" "$ios_log_device_copy_attempted"
detail "iosLogDeviceCopyResult" "$ios_log_device_copy_result"

if [[ "$ios_log_copied" == "true" ]]; then
  record "Documents/Logs/ios_ble.log" "true" "$ios_log_local_bytes"
else
  record "Documents/Logs/ios_ble.log" "false" "0"
fi

if [[ "$logs_dir_exists" == "true" ]]; then
  bytes="$(wc -c < "$OUT_DIR/file_checks/Logs.list" | tr -d ' ')"
  record "Documents/Logs/" "true" "$bytes"
else
  record "Documents/Logs/" "false" "0"
fi

if list_path "Documents/AlbumArtCache" "$OUT_DIR/file_checks/AlbumArtCache.list"; then
  bytes="$(wc -c < "$OUT_DIR/file_checks/AlbumArtCache.list" | tr -d ' ')"
  record "Documents/AlbumArtCache/" "true" "$bytes"
else
  record "Documents/AlbumArtCache/" "false" "0"
fi

if list_path "Documents/AlbumArtCache/Enhanced" "$OUT_DIR/file_checks/Enhanced.list"; then
  bytes="$(wc -c < "$OUT_DIR/file_checks/Enhanced.list" | tr -d ' ')"
  record "Documents/AlbumArtCache/Enhanced/" "true" "$bytes"
else
  record "Documents/AlbumArtCache/Enhanced/" "false" "0"
fi

if copy_path "Library/Preferences/$BUNDLE_ID.plist" "$OUT_DIR/file_checks/user_defaults.plist"; then
  bytes="$(wc -c < "$OUT_DIR/file_checks/user_defaults.plist" | tr -d ' ')"
  record "Library/Preferences/$BUNDLE_ID.plist" "true" "$bytes"
else
  record "Library/Preferences/$BUNDLE_ID.plist" "false" "0"
fi

cat "$TABLE"
