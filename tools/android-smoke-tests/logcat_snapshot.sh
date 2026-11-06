#!/usr/bin/env bash
# Refresh a snapshot without losing previously captured FATAL/ANR evidence.
refresh_logcat_snapshot() {
  local log_path="$1" snapshot severe line
  snapshot="$(mktemp "${log_path}.refresh.XXXXXX")" || return 1
  severe="$(mktemp "${log_path}.severe.XXXXXX")" || { rm -f "$snapshot"; return 1; }
  grep -Ei 'FATAL EXCEPTION|(^|[^[:alnum:]_])ANR([^[:alnum:]_]|$)' "$log_path" > "$severe" || true
  if ! "$ADB_BIN" -s "$DEVICE_ID" logcat -d > "$snapshot"; then
    rm -f "$snapshot" "$severe"
    return 1
  fi
  while IFS= read -r line || [[ -n "$line" ]]; do
    if ! grep -Fqx -- "$line" "$snapshot"; then
      printf '%s\n' "$line" >> "$snapshot"
    fi
  done < "$severe"
  cat "$snapshot" > "$log_path"
  rm -f "$snapshot" "$severe"
}
