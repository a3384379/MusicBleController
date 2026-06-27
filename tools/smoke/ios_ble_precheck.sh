#!/usr/bin/env bash
set -euo pipefail

OUT_DIR="${OUT_DIR:?OUT_DIR is required}"
IOS_DEVICE_ID="${IOS_DEVICE_ID:?IOS_DEVICE_ID is required}"
BUNDLE_ID="${BUNDLE_ID:-com.sqz.IOSBleFeasibility}"
TIMEOUT_SEC="${IOS_BLE_PRECHECK_TIMEOUT_SEC:-5}"
JSON_OUTPUT=false
LAUNCH_ARGS=()

usage() {
  cat <<'EOF'
Usage: ios_ble_precheck.sh [--timeout seconds] [--launch-arg value] [--json]

Required environment:
  OUT_DIR
  IOS_DEVICE_ID
  BUNDLE_ID

Hard requirements:
  - iOS app launches
  - BLE connects
  - status notify subscribes
  - playbackState arrives within timeout
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --timeout)
      TIMEOUT_SEC="${2:?--timeout requires seconds}"
      shift 2
      ;;
    --json)
      JSON_OUTPUT=true
      shift
      ;;
    --launch-arg)
      LAUNCH_ARGS+=("${2:?--launch-arg requires a value}")
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

mkdir -p "$OUT_DIR"
PRECHECK_LOG="$OUT_DIR/ios_ble_precheck.log"
PRECHECK_JSON="$OUT_DIR/ios_ble_precheck.json"
PRECHECK_ENV="$OUT_DIR/ios_ble_precheck.env"
PRECHECK_IOS_LOG="$OUT_DIR/ios_ble_precheck_ios.log"

log() {
  echo "[iOSBLEPrecheck] $*" | tee -a "$PRECHECK_LOG" >&2
}

copy_ios_log() {
  local dest="$1"
  xcrun devicectl device copy from \
    --device "$IOS_DEVICE_ID" \
    --domain-type appDataContainer \
    --domain-identifier "$BUNDLE_ID" \
    --source Documents/Logs/ios_ble.log \
    --destination "$dest" \
    >>"$PRECHECK_LOG" 2>&1
}

write_result() {
  local launch_ok="$1"
  local log_path="$2"
  python3 - "$OUT_DIR" "$PRECHECK_JSON" "$PRECHECK_ENV" "$log_path" "$START_EPOCH_MS" "$launch_ok" "$TIMEOUT_SEC" <<'PY'
import json
import re
import sys
from datetime import datetime
from pathlib import Path

out_dir = Path(sys.argv[1])
json_path = Path(sys.argv[2])
env_path = Path(sys.argv[3])
log_path = Path(sys.argv[4])
start_ms = int(sys.argv[5])
launch_ok = sys.argv[6].lower() == "true"
timeout_sec = int(float(sys.argv[7]))
text = log_path.read_text(encoding="utf-8", errors="replace") if log_path.exists() else ""

def parse_ios_ms(line: str):
    match = re.match(r"(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})\.(\d{3})", line)
    if not match:
        return None
    try:
        dt = datetime.strptime(match.group(1), "%Y-%m-%d %H:%M:%S")
    except ValueError:
        return None
    return int(dt.timestamp() * 1000) + int(match.group(2))

window = []
for line in text.splitlines():
    ts = parse_ios_ms(line)
    if ts is None or ts >= start_ms:
        window.append(line)
window_text = "\n".join(window)

def first(pattern: str):
    for line in window:
        if re.search(pattern, line, re.I):
            return line, parse_ios_ms(line)
    return "", None

_, connected_at = first(r"\[BLE-iOS\]\s+didConnect|\[BLE\]\s+connected|\[Reconnect\]\s+connected")
_, notify_at = first(r"status notify subscribed|notify subscribed|\[Reconnect\]\s+subscribed")
_, playback_at = first(r'\{"type":"playbackState"|\[iOS\]\[Status\]\s+playbackState|\[Reconnect\]\s+playbackState accepted')

display_state = "unknown"
if re.search(r"didConnect|\[BLE\]\s+connected", window_text, re.I):
    display_state = "connected_no_status"
if re.search(r"notify subscribed", window_text, re.I):
    display_state = "subscribed_waiting_status"
if playback_at is not None:
    display_state = "connected"
if re.search(r"scan|scanning|searching|正在搜索", window_text, re.I) and playback_at is None:
    display_state = "scanning"
if re.search(r"hard reconnect|正在重连|connecting|连接中", window_text, re.I) and playback_at is None:
    display_state = "connecting"
if re.search(r"disconnected|未连接", window_text, re.I) and connected_at is None:
    display_state = "disconnected"

result = {
    "iosAppLaunched": launch_ok,
    "iosBleConnected": connected_at is not None,
    "notifySubscribed": notify_at is not None,
    "firstPlaybackStateReceived": playback_at is not None,
    "firstPlaybackStateLatencyMs": int(playback_at - start_ms) if playback_at is not None else 0,
    "precheckResult": "PASS",
    "precheckFailReason": "",
    "observedDisplayState": display_state,
    "timeoutSec": timeout_sec,
    "logPath": str(log_path),
}
if not launch_ok:
    result["precheckResult"] = "FAIL"
    result["precheckFailReason"] = "ios_app_launch_failed"
elif not (
    result["iosBleConnected"] and
    result["notifySubscribed"] and
    result["firstPlaybackStateReceived"]
):
    result["precheckResult"] = "FAIL"
    result["precheckFailReason"] = "ios_ble_not_connected"

json_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
env_path.write_text(
    "\n".join([
        f"iosAppLaunched={str(result['iosAppLaunched']).lower()}",
        f"iosBleConnected={str(result['iosBleConnected']).lower()}",
        f"notifySubscribed={str(result['notifySubscribed']).lower()}",
        f"firstPlaybackStateReceived={str(result['firstPlaybackStateReceived']).lower()}",
        f"firstPlaybackStateLatencyMs={result['firstPlaybackStateLatencyMs']}",
        f"precheckResult={result['precheckResult']}",
        f"precheckFailReason={result['precheckFailReason']}",
        f"observedDisplayState={result['observedDisplayState']}",
    ]) + "\n",
    encoding="utf-8",
)
print(json.dumps(result, ensure_ascii=False))
PY
}

START_EPOCH_MS="$(python3 - <<'PY'
import time
print(int(time.time() * 1000))
PY
)"

log "launch app for BLE precheck timeout=${TIMEOUT_SEC}s args=${LAUNCH_ARGS[*]:-none}"
LAUNCH_OK=true
if ! xcrun devicectl device process launch \
  --device "$IOS_DEVICE_ID" \
  --terminate-existing \
  "$BUNDLE_ID" \
  "${LAUNCH_ARGS[@]}" \
  >"$OUT_DIR/ios_ble_precheck_launch.out" \
  2>"$OUT_DIR/ios_ble_precheck_launch.err"; then
  LAUNCH_OK=false
fi

last_result=""
for _ in $(seq 1 "$TIMEOUT_SEC"); do
  sleep 1
  copy_ios_log "$PRECHECK_IOS_LOG" || true
  last_result="$(write_result "$LAUNCH_OK" "$PRECHECK_IOS_LOG")"
  if python3 - "$PRECHECK_JSON" <<'PY'
import json
import sys
from pathlib import Path
data = json.loads(Path(sys.argv[1]).read_text())
raise SystemExit(0 if data.get("precheckResult") == "PASS" else 1)
PY
  then
    log "PASS $last_result"
    if [[ "$JSON_OUTPUT" == true ]]; then
      cat "$PRECHECK_JSON"
    fi
    exit 0
  fi
done

copy_ios_log "$PRECHECK_IOS_LOG" || true
last_result="$(write_result "$LAUNCH_OK" "$PRECHECK_IOS_LOG")"
log "FAIL $last_result"
if [[ "$JSON_OUTPUT" == true ]]; then
  cat "$PRECHECK_JSON"
fi
exit 1
