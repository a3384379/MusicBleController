#!/usr/bin/env bash
set -euo pipefail

OUT_DIR="${OUT_DIR:?OUT_DIR is required}"
ADB_BIN="${ADB_BIN:?ADB_BIN is required}"
DEVICE_ID="${DEVICE_ID:?DEVICE_ID is required}"

paths=(
  "/sdcard/Android/data/com.example.playeragent/files"
  "/sdcard/Android/data/com.example.playeragent/files/QrcLyricCache"
  "/sdcard/Android/data/com.example.playeragent/files/ArtworkDiscovery"
  "/sdcard/Android/data/com.example.playeragent/files/Logs"
  "/sdcard/QQMusic"
  "/sdcard/qqmusic"
)

: > "$OUT_DIR/file_checks.tsv"

for path in "${paths[@]}"; do
  result="$("$ADB_BIN" -s "$DEVICE_ID" shell "p='$path'; if [ -e \"\$p\" ]; then fc=\$(find \"\$p\" -type f 2>/dev/null | wc -l | tr -d ' '); sz=\$(du -s \"\$p\" 2>/dev/null | awk '{print \$1}'); echo true:\${fc:-0}:\${sz:-0}:OK; else echo false:0:0:MISSING; fi" | tr -d '\r' | tail -1)"
  IFS=: read -r exists file_count bytes status <<< "$result"
  printf '%s\t%s\t%s\t%s\t%s\n' "$path" "${exists:-false}" "${file_count:-0}" "${bytes:-0}" "${status:-ERROR}" >> "$OUT_DIR/file_checks.tsv"
done

cat "$OUT_DIR/file_checks.tsv"
