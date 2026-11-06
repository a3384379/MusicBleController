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
DURATION_MINUTES=0
INSTALLED_BUILDS=""
IOS_SYSLOG_UDID=""

usage() {
  cat <<'EOF'
Usage: realtime_latency_v4_test.sh [options]

Options:
  --runs <count>          Number of samples. Default: 30.
  --fast-switch           Alternate NEXT/PREVIOUS at 650 ms intervals.
  --previous              Use PREVIOUS for the normal scenario.
  --auto                  Equivalent automatic scenario via Sony MediaSession events.
  --natural               Observe real natural autoplay without sending controls.
  --duration-minutes <n>  Observation time for --natural.
  --ios-device <id>       iPhone devicectl identifier.
  --android-device <id>   Sony adb serial.
  --output <dir>          Output directory.
  --ios-syslog-udid <id>  Capture system stream with idevicesyslog for this UDID.
  --installed-builds <f> Build and device installation evidence manifest.
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
    --natural)
      MODE="natural"
      shift
      ;;
    --duration-minutes)
      DURATION_MINUTES="${2:?--duration-minutes requires a count}"
      shift 2
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
    --ios-syslog-udid)
      IOS_SYSLOG_UDID="${2:?--ios-syslog-udid requires a UDID}"
      shift 2
      ;;
    --installed-builds)
      INSTALLED_BUILDS="${2:?--installed-builds requires a file}"
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
OUT_DIR="${OUTPUT_DIR_ARG:-/tmp/musicble_phase4/$timestamp}"
mkdir -p "$OUT_DIR"
if [[ -n "$(ls -A "$OUT_DIR")" ]]; then
  echo "Output directory must be empty: $OUT_DIR" >&2
  exit 2
fi

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
    lowered = line.lower()
    usable = re.search(r"\b(?:connected|available)\b", lowered) is not None
    if match and "iphone" in lowered and usable:
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

emit_failure() {
  local reason="$1"
  trap - ERR INT TERM EXIT
  if [[ -n "${IOS_COLLECTOR_PID:-}" ]]; then
    cleanup
    trap - EXIT
  fi
  printf '%s\n' "$reason" >>"$OUT_DIR/collector_errors.log"
  touch "$OUT_DIR/sony_trace.log" "$OUT_DIR/ios_trace.log"
  python3 "$SCRIPT_DIR/realtime_latency_report.py" \
    --sony-trace "$OUT_DIR/sony_trace.log" --ios-trace "$OUT_DIR/ios_trace.log" \
    --output-dir "$OUT_DIR" || true
  python3 - "$OUT_DIR" "$reason" <<'PYFAIL'
import hashlib, json, sys
from pathlib import Path
root, reason = Path(sys.argv[1]), sys.argv[2]
report_path = root / 'report.json'
try:
    report = json.loads(report_path.read_text())
except (OSError, ValueError):
    report = {}
report.update(result='INCOMPLETE', failureReason=reason)
(root / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
sections = {name: {'status': 'INCOMPLETE', 'failures': [], 'missing': [reason]}
            for name in ('capture', 'trace', 'correctness', 'slo', 'stability')}
(root / 'acceptance.json').write_text(json.dumps({'schemaVersion': 1, **sections, 'overall': 'INCOMPLETE'}, indent=2) + '\n')
def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''): result.update(block)
    return result.hexdigest()
files = {str(p.relative_to(root)): digest(p)
         for p in sorted(root.rglob('*')) if p.is_file() and p.name != 'sha256.json'}
(root / 'sha256.json').write_text(json.dumps(files, indent=2) + '\n')
PYFAIL
  if [[ "$JSON_OUTPUT" == true ]]; then cat "$OUT_DIR/acceptance.json"; fi
  exit 2
}

detect_ios || emit_failure "connected_iPhone_not_found"
detect_android || emit_failure "connected_Sony_not_found"

SONY_FULL_LOG="$OUT_DIR/sony_full.log"
IOS_FULL_LOG="$OUT_DIR/ios_full.log"
SONY_TRACE_LOG="$OUT_DIR/sony_trace.log"
IOS_TRACE_LOG="$OUT_DIR/ios_trace.log"

"$ADB_BIN" -s "$ANDROID_DEVICE_ID" logcat -v threadtime >"$SONY_FULL_LOG" &
LOGCAT_PID=$!
python3 "$SCRIPT_DIR/realtime_capture.py" --output-dir "$OUT_DIR" \
  --device "$IOS_DEVICE_ID" --bundle "$BUNDLE_ID" \
  >"$OUT_DIR/ios_collector.stdout" 2>"$OUT_DIR/ios_collector.stderr" &
IOS_COLLECTOR_PID=$!
IOS_SYSTEM_PID=""
if [[ -n "$IOS_SYSLOG_UDID" ]] && command -v idevicesyslog >/dev/null 2>&1; then
  xcrun devicectl device info details --device "$IOS_DEVICE_ID" --json-output "$OUT_DIR/ios_device_details.json" \
    >"$OUT_DIR/ios_device_details.stdout" 2>"$OUT_DIR/ios_device_details.stderr" || true
  IOS_SYSTEM_BIN="$(command -v idevicesyslog)"
  IOS_SYSTEM_START="$(python3 -c 'import time; print(time.monotonic())')"
  "$IOS_SYSTEM_BIN" -u "$IOS_SYSLOG_UDID" >"$OUT_DIR/ios_system.log" 2>"$OUT_DIR/ios_system.stderr" &
  IOS_SYSTEM_PID=$!
fi
cleanup() {
  IOS_COLLECTOR_ALIVE=false
  SONY_COLLECTOR_ALIVE=false
  if kill -0 "$IOS_COLLECTOR_PID" 2>/dev/null; then
    IOS_COLLECTOR_ALIVE=true
    kill "$IOS_COLLECTOR_PID" 2>/dev/null || true
  fi
  IOS_COLLECTOR_EXIT=0
  wait "$IOS_COLLECTOR_PID" 2>/dev/null || IOS_COLLECTOR_EXIT=$?
  if kill -0 "$LOGCAT_PID" 2>/dev/null; then
    SONY_COLLECTOR_ALIVE=true
    kill "$LOGCAT_PID" 2>/dev/null || true
  fi
  SONY_COLLECTOR_EXIT=0
  wait "$LOGCAT_PID" 2>/dev/null || SONY_COLLECTOR_EXIT=$?
  IOS_SYSTEM_ALIVE=false
  IOS_SYSTEM_EXIT=0
  if [[ -n "$IOS_SYSTEM_PID" ]]; then
    if kill -0 "$IOS_SYSTEM_PID" 2>/dev/null; then
      IOS_SYSTEM_ALIVE=true
      kill "$IOS_SYSTEM_PID" 2>/dev/null || true
    fi
    wait "$IOS_SYSTEM_PID" 2>/dev/null || IOS_SYSTEM_EXIT=$?
    IOS_SYSTEM_END="$(python3 -c 'import time; print(time.monotonic())')"
  fi
}
trap cleanup EXIT
trap 'emit_failure "interrupted"' INT TERM
trap 'emit_failure "command_failed_line_${LINENO}"' ERR
# Establish the pre-launch log epoch before the smoke launch clears AppLogStore.
for _ in $(seq 1 90); do
  if [[ -s "$OUT_DIR/ios_capture_manifest.json" ]]; then break; fi
  if ! kill -0 "$IOS_COLLECTOR_PID" 2>/dev/null || ! kill -0 "$LOGCAT_PID" 2>/dev/null; then
    emit_failure "collector_exited_before_baseline"
  fi
  sleep 1
done
if [[ ! -s "$OUT_DIR/ios_capture_manifest.json" ]]; then
  emit_failure "ios_baseline_snapshot_timeout"
fi
python3 - "$OUT_DIR/ios_capture_manifest.json" "$OUT_DIR/launch_context.json" <<'PYBASE'
import json, sys
from pathlib import Path
data = json.loads(Path(sys.argv[1]).read_text())
snap = data['snapshots'][-1]
Path(sys.argv[2]).write_text(json.dumps({'baselineIndex': snap['index'], 'baselineRunId': snap.get('runId')}, indent=2) + '\n')
PYBASE
HOST_RUN_ID="$(python3 -c 'import uuid; print(uuid.uuid4().hex)')"
if ! "$ADB_BIN" -s "$ANDROID_DEVICE_ID" shell log -t RealtimeV4 "host_measurement_start runId=$HOST_RUN_ID"; then
  emit_failure "sony_measurement_marker_failed"
fi
MONITOR_ELAPSED=0
monitor_wait() {
  local duration="$1" elapsed=0
  while (( elapsed < duration )); do
    if ! kill -0 "$LOGCAT_PID" 2>/dev/null || ! kill -0 "$IOS_COLLECTOR_PID" 2>/dev/null ||
      { [[ -n "$IOS_SYSTEM_PID" ]] && ! kill -0 "$IOS_SYSTEM_PID" 2>/dev/null; }; then
      printf '%s\n' 'collector_exited_early' >>"$OUT_DIR/collector_errors.log"
      return 1
    fi
    sleep 1
    elapsed=$((elapsed + 1))
    MONITOR_ELAPSED=$((MONITOR_ELAPSED + 1))
    if (( MONITOR_ELAPSED % 10 == 0 )); then
      printf '%s %s\n' "$MONITOR_ELAPSED" "$(wc -c <"$SONY_FULL_LOG" | tr -d ' ')" >>"$OUT_DIR/collector_monitor.log"
    fi
  done
}

launch_args=(
  --launch-arg --smoke-realtime-v4
  --launch-arg "--smoke-realtime-runs=$RUNS"
  --launch-arg "--smoke-realtime-mode=$MODE"
)
if [[ "$FAST_SWITCH" == true ]]; then
  launch_args+=(--launch-arg --smoke-realtime-fast-switch)
fi

log "precheck ios=$IOS_DEVICE_ID sony=$ANDROID_DEVICE_ID mode=$MODE runs=$RUNS"
IOS_SYSTEM_START_OFFSET=0
if [[ -n "$IOS_SYSTEM_PID" ]]; then
  IOS_SYSTEM_START_OFFSET="$(wc -c <"$OUT_DIR/ios_system.log" | tr -d ' ')"
fi
RUN_START_MONO="$(python3 -c 'import time; print(time.monotonic())')"
if ! OUT_DIR="$OUT_DIR" IOS_DEVICE_ID="$IOS_DEVICE_ID" BUNDLE_ID="$BUNDLE_ID" \
  "$SCRIPT_DIR/ios_ble_precheck.sh" --timeout 8 "${launch_args[@]}" --json \
  >"$OUT_DIR/ios_ble_precheck_stdout.json" \
  2>"$OUT_DIR/ios_ble_precheck_stderr.log"; then
  emit_failure "ios_ble_precheck_failed"
fi

python3 - "$OUT_DIR/launch_context.json" "$OUT_DIR/ios_ble_precheck_ios.log" <<'PYRUN'
import hashlib, json, re, sys
from pathlib import Path
context, precheck = Path(sys.argv[1]), Path(sys.argv[2])
data = json.loads(context.read_text())
text = precheck.read_text(errors='replace') if precheck.exists() else ''
ids = re.findall(r'\[RealtimeV4\] scheduled runId=(\S+)', text)
new_ids = [run for run in ids if run != data['baselineRunId']]
data.update(precheckRunIds=new_ids, expectedRunId=new_ids[0] if new_ids else None,
            precheckSha256=hashlib.sha256(precheck.read_bytes()).hexdigest() if precheck.exists() else None)
context.write_text(json.dumps(data, indent=2) + '\n')
PYRUN
OBSERVATION_START_MONO="$(python3 -c 'import time; print(time.monotonic())')"
if [[ "$MODE" == "auto" ]]; then
  "$ADB_BIN" -s "$ANDROID_DEVICE_ID" shell log -t RealtimeV4 \
    "measurement_start mode=auto" >/dev/null 2>&1 || true
  log "running automatic-equivalent Sony MediaSession events"
  for _ in $(seq 1 "$RUNS"); do
    # `input keyevent 87` is routed through the foreground input target on some
    # Sony builds and can be silently dropped after the first event. The public
    # media_session shell command dispatches the same standard NEXT action to
    # the active MediaSession and is stable for repeatable automatic-equivalent
    # measurements. This does not call a QQ Music private API.
    "$ADB_BIN" -s "$ANDROID_DEVICE_ID" shell cmd media_session dispatch next
    monitor_wait 4 || emit_failure "collector_exited_early"
  done
  monitor_wait 12 || emit_failure "collector_exited_early"
elif [[ "$MODE" == "natural" ]]; then
  if ! [[ "$DURATION_MINUTES" =~ ^[1-9][0-9]*$ ]]; then
    emit_failure "natural_duration_minutes_required"
  fi
  "$ADB_BIN" -s "$ANDROID_DEVICE_ID" shell log -t RealtimeV4 \
    "measurement_start mode=natural" >/dev/null 2>&1 || true
  log "observing natural autoplay for ${DURATION_MINUTES} minutes"
  monitor_wait "$((DURATION_MINUTES * 60))" || emit_failure "collector_exited_early"
else
  duration="$(python3 - "$RUNS" "$FAST_SWITCH" <<'PY'
import math
import sys
runs = int(sys.argv[1])
fast = sys.argv[2].lower() == "true"
print(math.ceil(runs * (0.65 if fast else 4.0) + 20))
PY
  )"
  log "collecting for ${duration}s"
  monitor_wait "$duration" || emit_failure "collector_exited_early"
fi

RUN_END_MONO="$(python3 -c 'import time; print(time.monotonic())')"
IOS_SYSTEM_END_OFFSET=0
if [[ -n "$IOS_SYSTEM_PID" ]]; then
  IOS_SYSTEM_END_OFFSET="$(wc -c <"$OUT_DIR/ios_system.log" | tr -d ' ')"
fi
cleanup
IOS_SYSTEM_FINAL_OFFSET=0
if [[ -n "$IOS_SYSTEM_PID" ]]; then
  IOS_SYSTEM_FINAL_OFFSET="$(wc -c <"$OUT_DIR/ios_system.log" | tr -d ' ')"
fi
trap - EXIT
python3 - "$OUT_DIR/ios_capture_manifest.json" "$RUN_START_MONO" "$RUN_END_MONO" "$OBSERVATION_START_MONO" <<'PYTIME'
import json, sys
from pathlib import Path
path = Path(sys.argv[1])
if path.exists():
    data = json.loads(path.read_text())
    data['runStartedMonotonic'] = float(sys.argv[2])
    data['runEndedMonotonic'] = float(sys.argv[3])
    data['observationStartedMonotonic'] = float(sys.argv[4])
    path.write_text(json.dumps(data, indent=2) + '\n')
PYTIME
if [[ -f "$OUT_DIR/ios_captured.log" ]]; then
  cp "$OUT_DIR/ios_captured.log" "$IOS_FULL_LOG"
else
  : > "$IOS_FULL_LOG"
fi

# Isolate the Sony buffer using the host marker; preserve the untouched log.
awk -v run="$HOST_RUN_ID" '
  index($0, "RealtimeV4: host_measurement_start runId=" run) { capture=1; next }
  capture { print }
' "$SONY_FULL_LOG" >"$OUT_DIR/sony_run.log"
grep -F '[RealtimeTrace]' "$OUT_DIR/sony_run.log" >"$SONY_TRACE_LOG" || true
# Bind the compatibility report to the first run seen by this launch's
# precheck. Later runs remain in the full capture and invalidate acceptance.
python3 - "$IOS_FULL_LOG" "$IOS_TRACE_LOG" "$OUT_DIR/ios_run.log" "$OUT_DIR/launch_context.json" <<'PYWINDOW'
import json, re, sys
from pathlib import Path
expected = json.loads(Path(sys.argv[4]).read_text()).get('expectedRunId')
active = False
with Path(sys.argv[1]).open(errors='replace') as source, Path(sys.argv[2]).open('w') as trace, Path(sys.argv[3]).open('w') as run:
    for line in source:
        marker = re.search(r'\[RealtimeV4\] scheduled runId=(\S+)', line)
        if marker:
            if active:
                break
            if expected and marker.group(1) == expected:
                active = True
        if active:
            run.write(line)
            if '[RealtimeTrace]' in line or ('[ClockSync]' in line and 'pong' in line):
                trace.write(line)

PYWINDOW
python3 - "$OUT_DIR" "$HOST_RUN_ID" "$SONY_COLLECTOR_ALIVE" "$SONY_COLLECTOR_EXIT" "$IOS_COLLECTOR_ALIVE" "$IOS_COLLECTOR_EXIT" "$MODE" <<'PYLIFE'
import hashlib, json, re, sys
from pathlib import Path
root = Path(sys.argv[1])
path = root / 'ios_capture_manifest.json'
data = json.loads(path.read_text()) if path.exists() else {'errors': [{'reason': 'manifest_missing'}]}
def digest(name):
    file = root / name
    if not file.exists(): return None
    result = hashlib.sha256()
    with file.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            result.update(block)
    return result.hexdigest()
context = json.loads((root / 'launch_context.json').read_text())
ids = []
started = False
with (root / 'ios_captured.log').open(errors='replace') as source:
    for line in source:
        match = re.search(r'\[RealtimeV4\] scheduled runId=(\S+)', line)
        if match:
            if match.group(1) == context.get('expectedRunId'): started = True
            if started: ids.append(match.group(1))
data.update(hostRunId=sys.argv[2], iosRunIds=ids, mode=sys.argv[7],
    launchContextSha256=digest('launch_context.json'),
    sonyCollector={'aliveAtStop': sys.argv[3] == 'true', 'exitCode': int(sys.argv[4]),
                   'sha256': digest('sony_full.log'), 'runSha256': digest('sony_run.log'),
                   'monitorSha256': digest('collector_monitor.log')},
    iosCollector={'aliveAtStop': sys.argv[5] == 'true', 'exitCode': int(sys.argv[6]),
                  'sha256': digest('ios_captured.log'), 'runSha256': digest('ios_run.log')})
path.write_text(json.dumps(data, indent=2) + '\n')
PYLIFE
if [[ -n "$IOS_SYSTEM_PID" ]]; then
  python3 - "$OUT_DIR/ios_capture_manifest.json" "$OUT_DIR/ios_system.log" "$IOS_SYSTEM_ALIVE" "$IOS_SYSTEM_EXIT" "$IOS_SYSTEM_START" "$IOS_SYSTEM_END" "$IOS_SYSLOG_UDID" "$IOS_SYSTEM_START_OFFSET" "$IOS_SYSTEM_END_OFFSET" "$IOS_SYSTEM_BIN" "$IOS_SYSTEM_FINAL_OFFSET" "$SCRIPT_DIR" <<'PYSYS'
import hashlib, json, sys
from pathlib import Path
manifest, log = Path(sys.argv[1]), Path(sys.argv[2])
data = json.loads(manifest.read_text())
sys.path.insert(0, sys.argv[12])
from realtime_capture import copy_system_window
observed_start, run_end, collector_end = int(sys.argv[8]), int(sys.argv[9]), int(sys.argv[11])
run_log = log.parent / 'ios_system_run.log'
window = copy_system_window(log, run_log, observed_start, run_end, collector_end)
def digest(file):
    result = hashlib.sha256()
    with file.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''): result.update(block)
    return result.hexdigest()
details = log.parent / 'ios_device_details.json'
data['iosSystemCollector'] = {'file': log.name, 'sha256': digest(log),
    'runFile': run_log.name, 'runSha256': digest(run_log),
    **window, 'udid': sys.argv[7],
    'argv': [sys.argv[10], '-u', sys.argv[7]], 'executableSha256': digest(Path(sys.argv[10])),
    'deviceDetailsFile': details.name, 'deviceDetailsSha256': digest(details) if details.exists() else None,
    'aliveAtStop': sys.argv[3] == 'true', 'exitCode': int(sys.argv[4]),
    'startedMonotonic': float(sys.argv[5]), 'endedMonotonic': float(sys.argv[6])}
manifest.write_text(json.dumps(data, indent=2) + '\n')
PYSYS
fi

python3 "$SCRIPT_DIR/realtime_latency_report.py" \
  --sony-trace "$SONY_TRACE_LOG" \
  --ios-trace "$IOS_TRACE_LOG" \
  --output-dir "$OUT_DIR"

COMMIT="$(git -C "$ROOT_DIR" rev-parse HEAD)"
BRANCH="$(git -C "$ROOT_DIR" branch --show-current)"
python3 - "$OUT_DIR/report.json" "$MODE" "$RUNS" "$FAST_SWITCH" "$IOS_DEVICE_ID" "$ANDROID_DEVICE_ID" "$COMMIT" "$BRANCH" "$OUT_DIR/sony_run.log" "$OUT_DIR/ios_run.log" "$OUT_DIR/summary.md" <<'PY'
import json
import re
import sys
from pathlib import Path
path = Path(sys.argv[1])
data = json.loads(path.read_text())
protocol_matches, mtu_matches, ios_mtu_matches, clock_matches = [], [], [], []
control_attempt_count = 0
with Path(sys.argv[9]).open(encoding='utf-8', errors='replace') as sony:
    for line in sony:
        mtu_matches.extend(re.findall(r"\bMTU changed:.*\bmtu=(\d+)", line))
        if re.search(r"\[RealtimeTrace\].*\bside=sony\b.*\bstage=commandReceived\b.*\bcommandType=(?:NEXT|PREVIOUS)\b", line):
            control_attempt_count += 1
with Path(sys.argv[10]).open(encoding='utf-8', errors='replace') as ios:
    for line in ios:
        protocol_matches.extend(re.findall(r"protocolVersion=(\d+)", line))
        ios_mtu_matches.extend(re.findall(r"\[RealtimeV4\].*\bmtu=(\d+)", line))
        clock_matches.extend(re.findall(r"\[ClockSync\].*\bsamples=(\d+)\b.*\bconfident=(true|false)", line, re.IGNORECASE))
stage_counts = data.get("stageCounts", {})
mode = sys.argv[2]
requested_runs = int(sys.argv[3])
fast_switch = sys.argv[4].lower() == "true"
observed_transition_count = stage_counts.get("mediaSessionTrackChanged", 0)
expected_triggers = {
    "next": {"IOS_NEXT"},
    "previous": {"IOS_PREVIOUS"},
    "fast": {"IOS_NEXT", "IOS_PREVIOUS"},
    "auto": {"SONY_MEDIA_SESSION_NEXT"},
    "natural": {"NATURAL_AUTOPLAY"},
}.get(mode, set())
eligible_samples = [
    sample for sample in data.get("samples", [])
    if sample.get("triggerType") in expected_triggers
]
sample_count = sum(
    "COMPLETE" in sample.get("classifications", [])
    and "FAILED" not in sample.get("classifications", [])
    for sample in eligible_samples
)
track_change_count = sum(
    "NO_TRACK_CHANGE" not in sample.get("classifications", [])
    and "COMMAND_ONLY" not in sample.get("classifications", [])
    for sample in eligible_samples
)
complete_handoff_sample_count = sample_count
correctness_clean = (
    data.get("categories", {}).get("STALE_CONTENT", 0) == 0
    and data.get("diagnostics", {}).get("duplicate_control", 0) == 0
)
if fast_switch:
    # A 650 ms pressure command intentionally overlaps the normal handoff SLO.
    # Validate delivery and correctness for every requested control; report the
    # resulting transitions separately instead of demanding one settled track
    # publication per command.
    sample_count = control_attempt_count
    complete = (
        data.get("eventCount", 0) > 0
        and control_attempt_count >= requested_runs
        and correctness_clean
    )
else:
    complete = data.get("eventCount", 0) > 0 and sample_count >= requested_runs and correctness_clean
data.update({
    "traceResult": "PASS" if complete else "INCOMPLETE",
    "result": "INCOMPLETE",
    "failureReason": "strict_acceptance_not_evaluated",
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
        "completeHandoffSampleCount": complete_handoff_sample_count,
        "trackChangeSampleCount": track_change_count,
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
(path.parent / "sample_classification.json").write_text(
    json.dumps(data.get("samples", []), ensure_ascii=False, indent=2) + "\n"
)
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

acceptance_args=(--report "$OUT_DIR/report.json" --capture "$OUT_DIR/ios_capture_manifest.json"
  --mode "$MODE" --output "$OUT_DIR/acceptance.json" --system-log "$OUT_DIR/sony_run.log"
  --system-log "$OUT_DIR/ios_run.log"
  --raw-events "$OUT_DIR/raw_events.jsonl" --ios-log "$OUT_DIR/ios_run.log")
if [[ -n "$IOS_SYSTEM_PID" ]]; then
  acceptance_args+=(--system-log "$OUT_DIR/ios_system_run.log")
fi
if [[ -n "$INSTALLED_BUILDS" ]]; then
  cp "$INSTALLED_BUILDS" "$OUT_DIR/installed_builds.json"
  acceptance_args+=(--installed-builds "$OUT_DIR/installed_builds.json")
fi
acceptance_exit=0
python3 "$SCRIPT_DIR/realtime_acceptance.py" "${acceptance_args[@]}" || acceptance_exit=$?
python3 - "$OUT_DIR" <<'PYHASH'
import hashlib, json, sys
from pathlib import Path
root = Path(sys.argv[1])
report = json.loads((root / 'report.json').read_text())
acceptance = json.loads((root / 'acceptance.json').read_text())
report['result'] = acceptance['overall']
report['failureReason'] = None if report['result'] == 'PASS' else 'strict_acceptance_' + report['result'].lower()
(root / 'report.json').write_text(json.dumps(report, indent=2, ensure_ascii=False) + '\n')
def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''): result.update(block)
    return result.hexdigest()
files = {str(p.relative_to(root)): digest(p)
         for p in sorted(root.rglob('*')) if p.is_file() and p.name != 'sha256.json'}
(root / 'sha256.json').write_text(json.dumps(files, indent=2) + '\n')
PYHASH
log "report=$OUT_DIR/report.json acceptance=$OUT_DIR/acceptance.json"
if [[ "$JSON_OUTPUT" == true ]]; then
  cat "$OUT_DIR/acceptance.json"
else
  cat "$OUT_DIR/summary.md"
  cat "$OUT_DIR/acceptance.json"
fi
exit "$acceptance_exit"
