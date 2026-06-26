#!/usr/bin/env bash
set -euo pipefail

LOG_PATH="${1:-}"
if [[ -z "$LOG_PATH" || ! -f "$LOG_PATH" ]]; then
  printf 'optional\tBLE Service\tWARN\t0\tlogcat missing; cannot evaluate BLE service\n'
  exit 0
fi

OUT_DIR="${OUT_DIR:-$(dirname "$LOG_PATH")}"
DEBUG_CONTROL_ENABLED="${DEBUG_CONTROL_ENABLED:-true}"
DEBUG_CONTROL_AVAILABLE=false
DEBUG_CONTROL_START_ATTEMPTED=false
DEBUG_CONTROL_START_RESULT="skipped"

write_debug_control_json() {
  python3 - "$OUT_DIR/debug_control.json" \
    "$DEBUG_CONTROL_AVAILABLE" \
    "$DEBUG_CONTROL_START_ATTEMPTED" \
    "$DEBUG_CONTROL_START_RESULT" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
payload = {
    "debugControlAvailable": sys.argv[2].lower() == "true",
    "debugControlStartAttempted": sys.argv[3].lower() == "true",
    "debugControlStartResult": sys.argv[4],
}
path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
PY
}

if [[ "$DEBUG_CONTROL_ENABLED" == true ]]; then
  DEBUG_CONTROL_START_ATTEMPTED=true
  if [[ -n "${ADB_BIN:-}" && -n "${DEVICE_ID:-}" ]]; then
    set +e
    "$ADB_BIN" -s "$DEVICE_ID" shell am broadcast \
      -a com.example.playeragent.debug.START_BLE_SERVICE \
      -p com.example.playeragent \
      > "$OUT_DIR/debug_control_start_stdout.log" \
      2> "$OUT_DIR/debug_control_start_stderr.log"
    broadcast_rc="$?"
    set -e
    sleep 3
    "$ADB_BIN" -s "$DEVICE_ID" logcat -d > "$LOG_PATH" || true
    grep -E "PlayerAgent|ControlServiceAutoStart|MediaSessionReader|BleGattServer|BLE-A|BLE-ADV|BLE-GATT|BLE-RECOVERY|BLE-DIAG|DebugControl|Qrc|Lyric|AlbumArt|FullLyrics|FATAL EXCEPTION|ANR|AndroidRuntime" \
      "$LOG_PATH" > "$OUT_DIR/sony_filtered.log" || true
    if grep -q "\[DebugControl\] received action=com.example.playeragent.debug.START_BLE_SERVICE" "$LOG_PATH"; then
      DEBUG_CONTROL_AVAILABLE=true
      DEBUG_CONTROL_START_RESULT="started"
    elif [[ "$broadcast_rc" -ne 0 ]]; then
      DEBUG_CONTROL_START_RESULT="broadcast failed rc=$broadcast_rc"
    else
      DEBUG_CONTROL_START_RESULT="receiver unavailable"
    fi
  else
    DEBUG_CONTROL_START_RESULT="missing adb/device env"
  fi
fi
write_debug_control_json

lower="$(mktemp)"
tr '[:upper:]' '[:lower:]' < "$LOG_PATH" > "$lower"
trap 'rm -f "$lower"' EXIT

if grep -Eq "fatal exception|\\banr\\b" "$lower"; then
  printf 'optional\tBLE Service\tFAIL\t0\tFATAL/ANR found in logcat\n'
  exit 0
fi

recovery_success=false
if grep -Eq "recovery.*success|advertising restored|ble recovery.*success" "$lower"; then
  recovery_success=true
fi

if grep -Eq "gatt.*failed|advertising.*failed|advertise.*failed" "$lower" && [[ "$recovery_success" != true ]]; then
  printf 'optional\tBLE Service\tFAIL\t0\tGATT/advertising failure without recovery success\n'
  exit 0
fi

gatt_started=false
service_added=false
advertising_started=false
healthy_diag=false
if grep -Eq "gatt server started|ble-gatt.*started|gatt.*started" "$lower"; then
  gatt_started=true
fi
if grep -Eq "service added success|service.*added.*success|gatt service.*success" "$lower"; then
  service_added=true
fi
if grep -Eq "advertising started|ble-adv.*started|advertise.*started" "$lower"; then
  advertising_started=true
fi
if grep -Eq "ble-diag.*gattstarted=true.*advertisingstate=started" "$lower"; then
  healthy_diag=true
fi

if [[ "$gatt_started" == true && "$service_added" == true && "$advertising_started" == true ]]; then
  printf 'optional\tBLE Service\tPASS\t0\tGATT server, service add, and advertising logs found\n'
elif [[ "$DEBUG_CONTROL_AVAILABLE" == true && "$healthy_diag" == true ]]; then
  printf 'optional\tBLE Service\tPASS\t0\tdebug control available and BLE diagnostics report ready\n'
elif [[ "$DEBUG_CONTROL_ENABLED" == true && "$DEBUG_CONTROL_AVAILABLE" != true ]]; then
  printf 'optional\tBLE Service\tWARN\t0\tdebug control receiver unavailable; GATT/advertising logs incomplete\n'
else
  printf 'optional\tBLE Service\tWARN\t0\tGATT/advertising logs incomplete; service may not have been started\n'
fi
