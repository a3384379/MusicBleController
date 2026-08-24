#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
IOS_DEVICE_ID="${IOS_DEVICE_ID:-}"
ANDROID_DEVICE_ID="${ANDROID_DEVICE_ID:-}"
BUNDLE_ID="${BUNDLE_ID:-com.sqz.IOSBleFeasibility}"
RUNS=30
FAST_SWITCH=false
MODE="next"
OUTPUT_DIR_ARG=""
JSON_OUTPUT=false

usage() {
  cat <<'EOF'
Usage: realtime_latency_v4_test.sh [options]

Options:
  --runs <count>          Number of samples. Default: 30.
  --fast-switch           Alternate NEXT/PREVIOUS at 650 ms intervals.
  --previous              Use PREVIOUS for the normal scenario.
  --auto                  Equivalent automatic scenario via Sony media key events.
  --ios-device <id>       iPhone devicectl identifier.
  --android-device <id>   Sony adb serial.
  --output <dir>          Output directory.
  --json                  Print report.json.
  -h, --help              Show this help.

Examples:
  realtime_latency_v4_test.sh --runs 30 --json
  realtime_latency_v4_test.sh --runs 100 --fast-switch --json

The app and PlayerAgent debug builds must already be installed. The script performs
the hard iOS BLE precheck before sending any command.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --runs)
      RUNS="${2:?--runs requires a count}"
      shift 2
      ;;
    --fast-switch)
      FAST_SWITCH=true
      MODE="fast"
      shift
      ;;
    --previous)
      MODE="previous"
      shift
      ;;
    --auto)
      MODE="auto"
      shift
      ;;
    --ios-device)
      IOS_DEVICE_ID="${2:?--ios-device requires an id}"
      shift 2
      ;;
    --android-device)
      ANDROID_DEVICE_ID="${2:?--android-device requires an id}"
      shift 2
      ;;
    --output)
      OUTPUT_DIR_ARG="${2:?--output requires a directory}"
      shift 2
      ;;
    --json)
      JSON_OUTPUT=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if ! [[ "$RUNS" =~ ^[1-9][0-9]*$ ]] || (( RUNS > 500 )); then
  echo "--runs must be between 1 and 500" >&2
  exit 2
fi

timestamp="$(date +%Y%m%d_%H%M%S)"
OUT_DIR="${OUTPUT_DIR_ARG:-/tmp/musicble_realtime_v4/$timestamp}"
mkdir -p "$OUT_DIR"

log() {
  echo "[RealtimeV4] $*" >&2
}

find_adb() {
  if [[ -n "${ADB_BIN:-}" && -x "${ADB_BIN:-}" ]]; then
    echo "$ADB_BIN"
    return
  fi
  if command -v adb >/dev/null 2>&1; then
    command -v adb
    return
  fi
  local candidate
  for candidate in \
    "${ANDROID_HOME:-}/platform-tools/adb" \
    "${ANDROID_SDK_ROOT:-}/platform-tools/adb" \
    "$HOME/Library/Android/sdk/platform-tools/adb" \
    /opt/homebrew/bin/adb \
    /usr/local/bin/adb; do
    if [[ -n "$candidate" && -x "$candidate" ]]; then
      echo "$candidate"
      return
    fi
  done
  return 1
}

detect_ios() {
  if [[ -n "$IOS_DEVICE_ID" ]]; then
    return
  fi
  local list_file="$OUT_DIR/devicectl_devices.txt"
  xcrun devicectl list devices >"$list_file"
  IOS_DEVICE_ID="$(python3 - "$list_file" <<'PY'
import re
import sys
from pathlib import Path
ids = []
pattern = re.compile(r"[0-9A-Fa-f]{8}(?:-[0-9A-Fa-f]{4}){3}-[0-9A-Fa-f]{12}")
for line in Path(sys.argv[1]).read_text(errors="replace").splitlines():
    match = pattern.search(line)
    if match and "iphone" in line.lower() and "connected" in line.lower():
        ids.append(match.group(0))
if len(ids) == 1:
    print(ids[0])
else:
    raise SystemExit(1)
PY
  )" || return 1
}

detect_android() {
  ADB_BIN="$(find_adb)" || return 1
  export ADB_BIN
  if [[ -n "$ANDROID_DEVICE_ID" ]]; then
    return
  fi
  "$ADB_BIN" devices >"$OUT_DIR/adb_devices.txt"
  ANDROID_DEVICE_ID="$(python3 - "$OUT_DIR/adb_devices.txt" <<'PY'
import sys
from pathlib import Path
rows = []
for line in Path(sys.argv[1]).read_text(errors="replace").splitlines()[1:]:
    parts = line.split()
    if len(parts) >= 2 and parts[1] == "device":
        rows.append(parts[0])
if len(rows) == 1:
    print(rows[0])
else:
    raise SystemExit(1)
PY
  )" || return 1
}

copy_ios_log() {
  local destination="$1"
  local current_log="${destination}.current"
  local old_log="${destination}.old"
  rm -f "$current_log" "$old_log"
  xcrun devicectl --timeout 12 device copy from \
    --device "$IOS_DEVICE_ID" \
    --domain-type appDataContainer \
    --domain-identifier "$BUNDLE_ID" \
    --source Documents/Logs/ios_ble.log \
    --destination "$current_log" \
    >"$OUT_DIR/devicectl_copy.out" 2>"$OUT_DIR/devicectl_copy.err"
  xcrun devicectl --timeout 12 device copy from \
    --device "$IOS_DEVICE_ID" \
    --domain-type appDataContainer \
    --domain-identifier "$BUNDLE_ID" \
    --source Documents/Logs/ios_ble.old.log \
    --destination "$old_log" \
    >"$OUT_DIR/devicectl_old_copy.out" 2>"$OUT_DIR/devicectl_old_copy.err" || true
  if [[ -s "$old_log" ]]; then
    cat "$old_log" "$current_log" >"$destination"
  else
    mv "$current_log" "$destination"
  fi
}

emit_failure() {
  local reason="$1"
  : >"$OUT_DIR/sony_trace.log"
  : >"$OUT_DIR/ios_trace.log"
  python3 "$SCRIPT_DIR/realtime_latency_report.py" \
    --sony-trace "$OUT_DIR/sony_trace.log" \
    --ios-trace "$OUT_DIR/ios_trace.log" \
    --output-dir "$OUT_DIR"
  python3 - "$OUT_DIR/report.json" "$reason" <<'PY'
import json
import sys
from pathlib import Path
path = Path(sys.argv[1])
data = json.loads(path.read_text())
data["result"] = "FAIL"
data["failureReason"] = sys.argv[2]
path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n")
PY
  if [[ "$JSON_OUTPUT" == true ]]; then
    cat "$OUT_DIR/report.json"
  fi
  exit 1
}

detect_ios || emit_failure "connected_iPhone_not_found"
detect_android || emit_failure "connected_Sony_not_found"

SONY_FULL_LOG="$OUT_DIR/sony_full.log"
IOS_FULL_LOG="$OUT_DIR/ios_full.log"
SONY_TRACE_LOG="$OUT_DIR/sony_trace.log"
IOS_TRACE_LOG="$OUT_DIR/ios_trace.log"

"$ADB_BIN" -s "$ANDROID_DEVICE_ID" logcat -c || true
"$ADB_BIN" -s "$ANDROID_DEVICE_ID" logcat -v threadtime >"$SONY_FULL_LOG" &
LOGCAT_PID=$!
cleanup() {
  if kill -0 "$LOGCAT_PID" 2>/dev/null; then
    kill "$LOGCAT_PID" 2>/dev/null || true
  fi
  wait "$LOGCAT_PID" 2>/dev/null || true
}
trap cleanup EXIT

launch_args=(
  --launch-arg --smoke-realtime-v4
  --launch-arg "--smoke-realtime-runs=$RUNS"
  --launch-arg "--smoke-realtime-mode=$MODE"
)
if [[ "$FAST_SWITCH" == true ]]; then
  launch_args+=(--launch-arg --smoke-realtime-fast-switch)
fi

log "precheck ios=$IOS_DEVICE_ID sony=$ANDROID_DEVICE_ID mode=$MODE runs=$RUNS"
if ! OUT_DIR="$OUT_DIR" IOS_DEVICE_ID="$IOS_DEVICE_ID" BUNDLE_ID="$BUNDLE_ID" \
  "$SCRIPT_DIR/ios_ble_precheck.sh" --timeout 8 "${launch_args[@]}" --json \
  >"$OUT_DIR/ios_ble_precheck_stdout.json" \
  2>"$OUT_DIR/ios_ble_precheck_stderr.log"; then
  emit_failure "ios_ble_precheck_failed"
fi

if [[ "$MODE" == "auto" ]]; then
  "$ADB_BIN" -s "$ANDROID_DEVICE_ID" shell log -t RealtimeV4 \
    "measurement_start mode=auto" >/dev/null 2>&1 || true
  log "running automatic-equivalent Sony media events"
  for _ in $(seq 1 "$RUNS"); do
    "$ADB_BIN" -s "$ANDROID_DEVICE_ID" shell input keyevent 87
    sleep 3
  done
  sleep 12
else
  duration="$(python3 - "$RUNS" "$FAST_SWITCH" <<'PY'
import math
import sys
runs = int(sys.argv[1])
fast = sys.argv[2].lower() == "true"
print(math.ceil(runs * (0.65 if fast else 3.0) + 20))
PY
  )"
  log "collecting for ${duration}s"
  sleep "$duration"
fi

copy_ios_log "$IOS_FULL_LOG" || emit_failure "ios_log_copy_failed"
cleanup
trap - EXIT

if [[ "$MODE" == "auto" ]]; then
  LC_ALL=C awk '
    /RealtimeV4: measurement_start mode=auto/ { capture=1; next }
    capture && /\[RealtimeTrace\]/ { print }
  ' "$SONY_FULL_LOG" >"$SONY_TRACE_LOG"
else
  grep -F '[RealtimeTrace]' "$SONY_FULL_LOG" >"$SONY_TRACE_LOG" || true
fi
grep -E '\[RealtimeTrace\]|\[ClockSync\].*pong' "$IOS_FULL_LOG" >"$IOS_TRACE_LOG" || true

python3 "$SCRIPT_DIR/realtime_latency_report.py" \
  --sony-trace "$SONY_TRACE_LOG" \
  --ios-trace "$IOS_TRACE_LOG" \
  --output-dir "$OUT_DIR"

COMMIT="$(git -C "$ROOT_DIR" rev-parse HEAD)"
BRANCH="$(git -C "$ROOT_DIR" branch --show-current)"
python3 - "$OUT_DIR/report.json" "$MODE" "$RUNS" "$FAST_SWITCH" "$IOS_DEVICE_ID" "$ANDROID_DEVICE_ID" "$COMMIT" "$BRANCH" "$SONY_FULL_LOG" "$IOS_FULL_LOG" "$OUT_DIR/summary.md" <<'PY'
import json
import re
import sys
from pathlib import Path
path = Path(sys.argv[1])
data = json.loads(path.read_text())
sony_text = Path(sys.argv[9]).read_text(encoding="utf-8", errors="replace")
ios_text = Path(sys.argv[10]).read_text(encoding="utf-8", errors="replace")
protocol_matches = re.findall(r"protocolVersion=(\d+)", ios_text)
mtu_matches = re.findall(r"\bMTU changed:.*\bmtu=(\d+)", sony_text)
ios_mtu_matches = re.findall(r"\[RealtimeV4\].*\bmtu=(\d+)", ios_text)
clock_matches = re.findall(
    r"\[ClockSync\].*\bsamples=(\d+)\b.*\bconfident=(true|false)",
    ios_text,
    flags=re.IGNORECASE,
)
stage_counts = data.get("stageCounts", {})
mode = sys.argv[2]
requested_runs = int(sys.argv[3])
fast_switch = sys.argv[4].lower() == "true"
control_attempt_count = len(re.findall(
    r"\[RealtimeTrace\][^\n]*\bside=sony\b[^\n]*\bstage=commandReceived\b"
    r"[^\n]*\bcommandType=(?:NEXT|PREVIOUS)\b",
    sony_text,
))
observed_transition_count = stage_counts.get("mediaSessionTrackChanged", 0)
sample_count = (
    control_attempt_count
    if fast_switch
    else observed_transition_count
    if mode == "auto"
    else stage_counts.get("commandIntent", 0)
)
complete = data.get("eventCount", 0) > 0 and sample_count >= requested_runs
data.update({
    "result": "PASS" if complete else "FAIL",
    "failureReason": None if complete else "trace_sample_incomplete",
    "scenario": {
        "mode": mode,
        "runs": requested_runs,
        "fastSwitch": fast_switch,
    },
    "devices": {"ios": sys.argv[5], "sony": sys.argv[6]},
    "basicInfo": {
        "commit": sys.argv[7],
        "branch": sys.argv[8],
        "protocolVersion": int(protocol_matches[-1]) if protocol_matches else None,
        "mtu": int((mtu_matches or ios_mtu_matches)[-1])
        if (mtu_matches or ios_mtu_matches) else None,
        "clockSyncTrusted": data.get("clock", {}).get("crossDeviceTrusted", False),
        "clockSyncSampleCount": int(clock_matches[-1][0]) if clock_matches else 0,
        "sampleCount": sample_count,
        "controlAttemptCount": control_attempt_count,
        "observedTransitionCount": observed_transition_count,
        "cacheState": {
            "runtimeHit": data.get("stageCounts", {}).get("runtimeCacheHit", 0),
            "runtimeMiss": data.get("stageCounts", {}).get("runtimeCacheMiss", 0),
            "artworkHit": data.get("stageCounts", {}).get("albumArtCacheHit", 0),
        },
    },
})
path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n")
summary_path = Path(sys.argv[11])
summary = summary_path.read_text(encoding="utf-8")
title, body = summary.split("\n\n", 1)
basic = data["basicInfo"]
summary_path.write_text(
    title + "\n\n## Basic information\n\n"
    + f"- Commit: {basic['commit']}\n"
    + f"- Branch: {basic['branch']}\n"
    + f"- iOS device: {data['devices']['ios']}\n"
    + f"- Sony device: {data['devices']['sony']}\n"
    + f"- Protocol version: {basic['protocolVersion']}\n"
    + f"- MTU: {basic['mtu']}\n"
    + f"- Clock sync trusted: {basic['clockSyncTrusted']}\n"
    + f"- Samples: {sample_count}/{requested_runs}\n\n"
    + f"- Observed transitions: {observed_transition_count}\n\n"
    + body,
    encoding="utf-8",
)
PY

log "report=$OUT_DIR/report.json"
if [[ "$JSON_OUTPUT" == true ]]; then
  cat "$OUT_DIR/report.json"
else
  cat "$OUT_DIR/summary.md"
fi

python3 - "$OUT_DIR/report.json" <<'PY'
import json
import sys
from pathlib import Path
data = json.loads(Path(sys.argv[1]).read_text())
raise SystemExit(0 if data.get("result") == "PASS" else 1)
PY
