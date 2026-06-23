#!/usr/bin/env bash
set -euo pipefail

OUT_DIR="${OUT_DIR:-/tmp/music_ble_ios_smoke/manual}"
IOS_DEVICE_ID="${IOS_DEVICE_ID:?IOS_DEVICE_ID is required}"
BUNDLE_ID="${BUNDLE_ID:-com.sqz.IOSBleFeasibility}"

mkdir -p "$OUT_DIR/file_checks"
TABLE="$OUT_DIR/file_checks.tsv"
: > "$TABLE"

record() {
  local path="$1"
  local exists="$2"
  local bytes="$3"
  printf '%s\t%s\t%s\n' "$path" "$exists" "$bytes" >> "$TABLE"
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
    >/tmp/ios_smoke_copy_path.out 2>/tmp/ios_smoke_copy_path.err
}

list_path() {
  local source="$1"
  local dest="$2"
  xcrun devicectl device info files \
    --device "$IOS_DEVICE_ID" \
    --domain-type appDataContainer \
    --domain-identifier "$BUNDLE_ID" \
    --subdirectory "$source" \
    >"$dest" 2>"$dest.err"
}

if copy_path "Documents/Logs/ios_ble.log" "$OUT_DIR/file_checks/ios_ble.log"; then
  bytes="$(wc -c < "$OUT_DIR/file_checks/ios_ble.log" | tr -d ' ')"
  record "Documents/Logs/ios_ble.log" "true" "$bytes"
else
  record "Documents/Logs/ios_ble.log" "false" "0"
fi

if list_path "Documents/Logs" "$OUT_DIR/file_checks/Logs.list"; then
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
