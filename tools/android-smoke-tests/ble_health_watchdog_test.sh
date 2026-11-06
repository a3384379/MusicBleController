#!/usr/bin/env bash
set -euo pipefail

LOG_PATH="${1:-}"
PACKAGE_NAME="${PACKAGE_NAME:-com.example.playeragent}"
DEBUG_CONTROL_ENABLED="${DEBUG_CONTROL_ENABLED:-true}"
source "$(cd "$(dirname "$0")" && pwd)/logcat_snapshot.sh"
OUT_DIR="${OUT_DIR:-${LOG_PATH:+$(dirname "$LOG_PATH")}}"
OUT_DIR="${OUT_DIR:-/tmp/music_ble_android_smoke_ble_health}"
mkdir -p "$OUT_DIR"

if [[ -z "$LOG_PATH" || ! -f "$LOG_PATH" ]]; then
  python3 - "$OUT_DIR/ble_health_watchdog.json" "$DEBUG_CONTROL_ENABLED" "${ADB_BIN:-}" "${DEVICE_ID:-}" <<'PY'
import json
import sys
from pathlib import Path

enabled = sys.argv[2].lower() == "true"
payload = {
    "result": "WARN",
    "reason": "logcat missing; cannot evaluate BLE health watchdog",
    "adbAvailable": bool(sys.argv[3] and sys.argv[4]),
    "debugControlEnabled": enabled,
    "recoverCommandAttempted": False,
    "recoverCommandRc": None,
    "recoverCommandSkipReason": "logcat_missing" if enabled else "debug_control_disabled",
}
Path(sys.argv[1]).write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
PY
  printf 'optional\tBLE Health Watchdog\tWARN\t0\tlogcat missing; cannot evaluate BLE health watchdog\n'
  exit 0
fi

ADB_AVAILABLE=false
if [[ -n "${ADB_BIN:-}" && -n "${DEVICE_ID:-}" ]]; then
  ADB_AVAILABLE=true
fi

recover_attempted=false
recover_rc=""
if [[ "$DEBUG_CONTROL_ENABLED" == true && "$ADB_AVAILABLE" == true ]]; then
  recover_attempted=true
  set +e
  "$ADB_BIN" -s "$DEVICE_ID" shell am broadcast \
    -a "$PACKAGE_NAME.debug.RECOVER_BLE_STACK" \
    -p "$PACKAGE_NAME" \
    > "$OUT_DIR/ble_health_recover_stdout.log" \
    2> "$OUT_DIR/ble_health_recover_stderr.log"
  recover_rc="$?"
  set -e
  sleep 4
  refresh_logcat_snapshot "$LOG_PATH" || true
fi

JSON_PATH="$OUT_DIR/ble_health_watchdog.json"

python3 - "$LOG_PATH" "$JSON_PATH" "$ADB_AVAILABLE" "$DEBUG_CONTROL_ENABLED" "$recover_attempted" "$recover_rc" <<'PY'
import json
import re
import sys
from pathlib import Path

log_path = Path(sys.argv[1])
json_path = Path(sys.argv[2])
adb_available = sys.argv[3].lower() == "true"
debug_control_enabled = sys.argv[4].lower() == "true"
recover_attempted = sys.argv[5].lower() == "true"
recover_rc = int(sys.argv[6]) if sys.argv[6] else None
skip_reason = None if recover_attempted else ("debug_control_disabled" if not debug_control_enabled else "adb_device_missing")
text = log_path.read_text(encoding="utf-8", errors="replace")

fatal = bool(re.search(r"FATAL EXCEPTION|\bANR\b", text, re.I))
health_states = re.findall(r"\[BleHealth\] state=([A-Z_]+)", text)
watchdog_actions = re.findall(r"\[BleHealth\] watchdog action=([a-z_]+)", text)
recover_start = "[BleHealth] recover start" in text
recover_done = "[BleHealth] recover done" in text
cooldown = "[BleHealth] recover skip reason=cooldown" in text
ble_diag_health = "[BLE-DIAG] healthState=" in text
gatt_started = "[BleGattServer] started" in text or "[BleGattServer] already started" in text
advertising = bool(re.search(r"advertising (started|restored)|advertisingState=STARTED|state=ADVERTISING", text, re.I))
recover_skipped = "[DebugControl] skipped action=RECOVER_BLE_STACK reason=permission_missing" in text
recover_denied = "[DebugControl] failed action=RECOVER_BLE_STACK reason=permission_denied" in text

result = "PASS"
reason = "BLE health state logs and manual recovery path observed"

if fatal:
    result = "FAIL"
    reason = "FATAL/ANR found in logcat"
elif recover_attempted and recover_rc != 0:
    result = "WARN"
    reason = f"manual recover debug broadcast failed rc={recover_rc}"
elif recover_attempted and recover_skipped:
    result = "WARN"
    reason = "manual recover skipped: permission_missing"
elif recover_attempted and recover_denied:
    result = "WARN"
    reason = "manual recover failed: permission_denied"
elif not health_states and not ble_diag_health:
    result = "WARN"
    reason = "BLE health snapshot logs missing"
elif not gatt_started:
    result = "WARN"
    reason = "GATT start/already-started logs missing"
elif not advertising:
    result = "WARN"
    reason = "advertising state/start logs missing"
elif recover_attempted and recover_rc == 0 and not (recover_start and recover_done):
    result = "WARN"
    reason = "manual recover command sent but recover start/done logs missing"
elif not debug_control_enabled:
    result = "WARN"
    reason = "debug control disabled; analyzed passive watchdog logs only"
elif not adb_available:
    result = "WARN"
    reason = "adb/device env missing; analyzed passive watchdog logs only"

payload = {
    "result": result,
    "reason": reason,
    "healthStates": health_states[-10:],
    "watchdogActions": watchdog_actions[-10:],
    "recoverStart": recover_start,
    "recoverDone": recover_done,
    "cooldownObserved": cooldown,
    "gattStarted": gatt_started,
    "advertisingObserved": advertising,
    "adbAvailable": adb_available,
    "debugControlEnabled": debug_control_enabled,
    "recoverCommandAttempted": recover_attempted,
    "recoverCommandRc": recover_rc,
    "recoverCommandSkipReason": skip_reason,
}
json_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
detail = (
    f"{reason}; states={','.join(health_states[-5:]) or 'none'} "
    f"watchdogActions={','.join(watchdog_actions[-5:]) or 'none'} "
    f"recoverStart={recover_start} recoverDone={recover_done} "
    f"cooldown={cooldown}"
)
print(f"optional\tBLE Health Watchdog\t{result}\t0\t{detail}")
PY
