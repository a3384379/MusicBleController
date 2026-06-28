#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
IOS_DEVICE_ID="${IOS_DEVICE_ID:-}"
ANDROID_DEVICE_ID="${ANDROID_DEVICE_ID:-}"
OUTPUT_DIR_ARG=""
JSON_OUTPUT=false
SONY_ONLY=false
SKIP_IOS_FILE_LOGS=false
BUNDLE_ID="${BUNDLE_ID:-com.sqz.IOSBleFeasibility}"
DURATION_SEC=145
EXPECTED_TRACK_COUNT=10

usage() {
  cat <<'EOF'
Usage: lyrics_timeline_v34_test.sh [options]

Options:
  --duration <seconds>       Collection window. Default: 145.
  --ios-device <id>          iPhone devicectl identifier.
  --android-device <id>      Sony adb serial.
  --output <dir>             Output directory.
  --sony-only                Only validate Sony-side LyricTrace coverage.
  --skip-ios-file-logs       Do not require iOS app-container log copy.
  --json                     Print report JSON summary.
  -h, --help                 Show help.

The script first runs a hard BLE precheck without test actions. After
precheck passes it launches the DEBUG iOS app with --smoke-track-matrix-v31,
performs the same 10-track sampling flow, and then builds a lyrics
timeline report for READY_SLOW / failed tracks from Sony and iOS logs.

Hard precheck:
  iOS launched, BLE connected, notify subscribed, and playbackState
  received within 5 seconds. If it fails, no commands are sent.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --duration)
      DURATION_SEC="${2:?--duration requires seconds}"
      shift 2
      ;;
    --ios-device)
      IOS_DEVICE_ID="${2:?--ios-device requires id}"
      shift 2
      ;;
    --android-device)
      ANDROID_DEVICE_ID="${2:?--android-device requires id}"
      shift 2
      ;;
    --output)
      OUTPUT_DIR_ARG="${2:?--output requires directory}"
      shift 2
      ;;
    --sony-only)
      SONY_ONLY=true
      SKIP_IOS_FILE_LOGS=true
      shift
      ;;
    --skip-ios-file-logs)
      SKIP_IOS_FILE_LOGS=true
      shift
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
      exit 1
      ;;
  esac
done

timestamp="$(date +%Y%m%d_%H%M%S)"
OUT_DIR="${OUTPUT_DIR_ARG:-/tmp/lyrics_timeline_v34/$timestamp}"
mkdir -p "$OUT_DIR"

log() {
  echo "[LyricsTimelineV34] $*" >&2
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
  local candidates=(
    "${ANDROID_HOME:-}/platform-tools/adb"
    "${ANDROID_SDK_ROOT:-}/platform-tools/adb"
    "$HOME/Library/Android/sdk/platform-tools/adb"
    "/opt/homebrew/bin/adb"
    "/usr/local/bin/adb"
  )
  local candidate
  for candidate in "${candidates[@]}"; do
    if [[ -n "$candidate" && -x "$candidate" ]]; then
      echo "$candidate"
      return
    fi
  done
  return 1
}

detect_ios() {
  if [[ -n "$IOS_DEVICE_ID" ]]; then
    return 0
  fi
  local list_file="$OUT_DIR/devicectl_devices.txt"
  if ! xcrun devicectl list devices > "$list_file" 2>"$OUT_DIR/devicectl_list_stderr.log"; then
    echo "devicectl list failed" > "$OUT_DIR/ios_detect_reason.txt"
    return 1
  fi
  IOS_DEVICE_ID="$(
    python3 - "$list_file" "$OUT_DIR/ios_detect_reason.txt" <<'PY'
import re
import sys
from pathlib import Path
rows = []
uuid_re = re.compile(r"[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}")
for raw in Path(sys.argv[1]).read_text(encoding="utf-8", errors="replace").splitlines():
    match = uuid_re.search(raw)
    if not match or "iPhone" not in raw:
        continue
    right = raw[match.end():].strip().lower()
    if right.startswith("connected") or right.startswith("available"):
        rows.append(match.group(0))
if len(rows) == 1:
    print(rows[0])
    raise SystemExit(0)
Path(sys.argv[2]).write_text(
    "no connected iPhone found\n" if not rows else "multiple connected iPhones found; pass --ios-device\n",
    encoding="utf-8"
)
raise SystemExit(1)
PY
  )" || return 1
}

detect_android() {
  ADB_BIN="$(find_adb)" || {
    echo "adb not found" > "$OUT_DIR/android_detect_reason.txt"
    return 1
  }
  export ADB_BIN
  if [[ -n "$ANDROID_DEVICE_ID" ]]; then
    return 0
  fi
  "$ADB_BIN" devices > "$OUT_DIR/adb_devices.txt"
  ANDROID_DEVICE_ID="$(
    python3 - "$OUT_DIR/adb_devices.txt" "$OUT_DIR/android_detect_reason.txt" <<'PY'
import sys
from pathlib import Path
rows = []
for raw in Path(sys.argv[1]).read_text(encoding="utf-8", errors="replace").splitlines()[1:]:
    parts = raw.split()
    if len(parts) >= 2 and parts[1] == "device":
        rows.append(parts[0])
if len(rows) == 1:
    print(rows[0])
    raise SystemExit(0)
Path(sys.argv[2]).write_text(
    "adb_device_missing\n" if not rows else "multiple adb devices; pass --android-device\n",
    encoding="utf-8"
)
raise SystemExit(1)
PY
  )" || return 1
}

run_ios_ble_precheck() {
  local attempt
  for attempt in 1 2 3; do
    if OUT_DIR="$OUT_DIR" IOS_DEVICE_ID="$IOS_DEVICE_ID" BUNDLE_ID="$BUNDLE_ID" \
      "$SCRIPT_DIR/ios_ble_precheck.sh" --timeout 5 \
      --json \
      >"$OUT_DIR/ios_ble_precheck_stdout.json" \
      2>"$OUT_DIR/ios_ble_precheck_stderr.log"; then
      echo "$attempt" >"$OUT_DIR/ios_ble_precheck_attempts.txt"
      return 0
    fi
    cp "$OUT_DIR/ios_ble_precheck.json" "$OUT_DIR/ios_ble_precheck_attempt_${attempt}.json" 2>/dev/null || true
    sleep 2
  done
  echo "3" >"$OUT_DIR/ios_ble_precheck_attempts.txt"
  return 1
}

launch_ios_matrix() {
  xcrun devicectl device process launch \
    --device "$IOS_DEVICE_ID" \
    --terminate-existing \
    "$BUNDLE_ID" \
    --smoke-track-matrix-v31 \
    >"$OUT_DIR/ios_matrix_launch.out" \
    2>"$OUT_DIR/ios_matrix_launch.err"
}

copy_ios_log_with_timeout() {
  local destination="$1"
  local timeout_sec="${2:-8}"
  local source_path="${3:-Documents/Logs/ios_ble.log}"
  if [[ "$timeout_sec" -lt 5 ]]; then
    timeout_sec=5
  fi
  rm -f "$destination"
  if xcrun devicectl --timeout "$timeout_sec" device copy from \
    --device "$IOS_DEVICE_ID" \
    --domain-type appDataContainer \
    --domain-identifier "$BUNDLE_ID" \
    --source "$source_path" \
    --destination "$destination" \
    >"${destination}.copy.out" 2>"${destination}.copy.err"; then
    echo "ok" > "${destination}.copy.status"
    return 0
  fi
  echo "copy_failed" > "${destination}.copy.status"
  return 1
}

start_sony_trace_collector() {
  SONY_TRACE_LOG="$OUT_DIR/sony_lyric_trace.log"
  SONY_TRACE_COLLECTOR_ERR="$OUT_DIR/sony_lyric_trace.err"
  : >"$SONY_TRACE_LOG"
  : >"$SONY_TRACE_COLLECTOR_ERR"
  "$ADB_BIN" -s "$ANDROID_DEVICE_ID" logcat -v epoch LyricTrace:I '*:S' \
    >"$SONY_TRACE_LOG" 2>"$SONY_TRACE_COLLECTOR_ERR" &
  SONY_TRACE_PID="$!"
  echo "$SONY_TRACE_PID" >"$OUT_DIR/sony_lyric_trace.pid"
  sleep 1
  if ! kill -0 "$SONY_TRACE_PID" 2>/dev/null; then
    echo "collector_failed" >"$OUT_DIR/sony_lyric_trace.collector_status"
    return 1
  fi
  echo "started" >"$OUT_DIR/sony_lyric_trace.collector_status"
  return 0
}

stop_sony_trace_collector() {
  local exit_code=0
  if [[ -n "${SONY_TRACE_PID:-}" ]] && kill -0 "$SONY_TRACE_PID" 2>/dev/null; then
    kill "$SONY_TRACE_PID" 2>/dev/null || true
    wait "$SONY_TRACE_PID" 2>/dev/null || exit_code="$?"
  fi
  echo "$exit_code" >"$OUT_DIR/sony_lyric_trace.collector_exit_code"
}

fail_report() {
  local reason="$1"
  python3 - "$OUT_DIR" "$reason" <<'PY'
import json
import sys
from pathlib import Path
out = Path(sys.argv[1])
reason = sys.argv[2]
precheck_path = out / "ios_ble_precheck.json"
precheck = json.loads(precheck_path.read_text()) if precheck_path.exists() else {}
precheck_reason = precheck.get("precheckFailReason", reason)
issue = precheck_reason or reason
summary = {
    "result": "FAIL",
    "precheckResult": precheck.get("precheckResult", "FAIL"),
    "precheckFailReason": precheck_reason,
    "iosAppLaunched": precheck.get("iosAppLaunched", False),
    "iosBleConnected": precheck.get("iosBleConnected", False),
    "notifySubscribed": precheck.get("notifySubscribed", False),
    "firstPlaybackStateReceived": precheck.get("firstPlaybackStateReceived", False),
    "firstPlaybackStateLatencyMs": precheck.get("firstPlaybackStateLatencyMs", 0),
    "samplingStarted": False,
    "actionsExecuted": False,
    "trackCount": 0,
    "readySlowCount": 0,
    "issues": [issue],
}
report = {
    "summary": summary,
    "precheck": precheck,
    "tracks": [],
    "artifacts": {
        "report_json": str(out / "report.json"),
        "report_md": str(out / "report.md"),
    },
}
(out / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
(out / "report.md").write_text(f"# Lyrics Timeline V3.4\n\nResult: FAIL\n\nreason={reason}\n", encoding="utf-8")
print(json.dumps({"report": str(out / "report.json"), "summary": summary}, ensure_ascii=False))
PY
}

if ! detect_android; then
  echo "Sony adb unavailable: $(cat "$OUT_DIR/android_detect_reason.txt" 2>/dev/null || true)" >&2
  exit 1
fi
IOS_AVAILABLE=true
if ! detect_ios; then
  IOS_AVAILABLE=false
  if [[ "$SONY_ONLY" != true ]]; then
    echo "iPhone unavailable: $(cat "$OUT_DIR/ios_detect_reason.txt" 2>/dev/null || true)" >&2
    exit 1
  fi
  echo "iPhone unavailable: $(cat "$OUT_DIR/ios_detect_reason.txt" 2>/dev/null || true)" >"$OUT_DIR/ios_detect_warn.txt"
fi

log "output=$OUT_DIR"
log "mode=$([[ "$SONY_ONLY" == true ]] && echo sony_only || echo full) iPhone=${IOS_DEVICE_ID:-unavailable} Sony=$ANDROID_DEVICE_ID duration=${DURATION_SEC}s"

START_EPOCH_MS="$(python3 - <<'PY'
import time
print(int(time.time() * 1000))
PY
)"

"$ADB_BIN" -s "$ANDROID_DEVICE_ID" logcat -c || true
"$ADB_BIN" -s "$ANDROID_DEVICE_ID" shell monkey -p com.example.playeragent 1 \
  >"$OUT_DIR/android_launch.out" 2>"$OUT_DIR/android_launch.err" || true
sleep 2

if [[ "$SONY_ONLY" == true ]]; then
  python3 - "$OUT_DIR" "$IOS_AVAILABLE" <<'PY'
import json
import sys
from pathlib import Path
out = Path(sys.argv[1])
ios_available = sys.argv[2].lower() == "true"
data = {
    "iosAppLaunched": False,
    "iosBleConnected": False,
    "notifySubscribed": False,
    "firstPlaybackStateReceived": False,
    "firstPlaybackStateLatencyMs": 0,
    "precheckResult": "WARN",
    "precheckFailReason": "ios_file_log_skipped_sony_only",
    "iosAppLaunchPrecheck": "SKIPPED" if not ios_available else "WARN",
    "iosBlePrecheck": "SKIPPED",
    "iosFileLogPrecheck": "WARN",
    "sonyTracePrecheck": "PENDING",
    "observedDisplayState": "unknown",
    "timeoutSec": 0,
    "logPath": "",
}
(out / "ios_ble_precheck.json").write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
(out / "ios_ble_precheck.env").write_text(
    "\n".join([
        "precheckResult=WARN",
        "precheckFailReason=ios_file_log_skipped_sony_only",
        "iosFileLogPrecheck=WARN",
    ]) + "\n",
    encoding="utf-8",
)
print(json.dumps(data, ensure_ascii=False))
PY
else
  if ! run_ios_ble_precheck; then
    fail_report "ios_ble_not_connected"
    exit 1
  fi
fi

"$ADB_BIN" -s "$ANDROID_DEVICE_ID" logcat -c || true
if ! start_sony_trace_collector; then
  fail_report "sony_logcat_collector_failed"
  exit 1
fi
if [[ "$SONY_ONLY" == true ]]; then
  if [[ "$IOS_AVAILABLE" == true ]]; then
    launch_ios_matrix || true
  fi
else
  launch_ios_matrix || {
    fail_report "ios_matrix_launch_failed"
    exit 1
  }
fi

SONY_NEXT_PID=""
if [[ "$SONY_ONLY" == true ]]; then
  (
    sleep 5
    interval=$(( DURATION_SEC / (EXPECTED_TRACK_COUNT + 1) ))
    if [[ "$interval" -lt 8 ]]; then
      interval=8
    fi
    for _ in $(seq 1 "$EXPECTED_TRACK_COUNT"); do
      "$ADB_BIN" -s "$ANDROID_DEVICE_ID" shell input keyevent 87 || true
      sleep 1
      "$ADB_BIN" -s "$ANDROID_DEVICE_ID" shell am start-foreground-service \
        -n com.example.playeragent/.service.PlayerAgentForegroundService \
        -a com.example.playeragent.ACTION_REFRESH_CURRENT_LYRIC || true
      sleep "$interval"
    done
  ) >"$OUT_DIR/sony_next_driver.out" 2>"$OUT_DIR/sony_next_driver.err" &
  SONY_NEXT_PID="$!"
fi

IOS_SNAPSHOT_DIR="$OUT_DIR/ios_snapshots"
mkdir -p "$IOS_SNAPSHOT_DIR"
SNAPSHOT_STOP_FILE="$OUT_DIR/ios_snapshot_stop"
rm -f "$SNAPSHOT_STOP_FILE"
SNAPSHOT_PID=""
if [[ "$SKIP_IOS_FILE_LOGS" != true ]]; then
  (
    snapshot_index=0
    while [[ ! -f "$SNAPSHOT_STOP_FILE" ]]; do
      copy_ios_log_with_timeout \
        "$IOS_SNAPSHOT_DIR/ios_timeline_snapshot_${snapshot_index}.log" \
        5 \
        Documents/Logs/ios_lyrics_timeline.log || true
      snapshot_index=$((snapshot_index + 1))
      sleep 6
    done
  ) &
  SNAPSHOT_PID="$!"
fi

NO_GROWTH_WARNINGS=0
LAST_TRACE_SIZE=0
NO_GROWTH_ELAPSED=0
ELAPSED=0
while [[ "$ELAPSED" -lt "$DURATION_SEC" ]]; do
  sleep 15
  ELAPSED=$((ELAPSED + 15))
  if [[ "$ELAPSED" -gt "$DURATION_SEC" ]]; then
    ELAPSED="$DURATION_SEC"
  fi
  if ! kill -0 "$SONY_TRACE_PID" 2>/dev/null; then
    echo "collector_died_at_sec=$ELAPSED" >>"$OUT_DIR/sony_lyric_trace.collector_status"
    break
  fi
  CURRENT_TRACE_SIZE="$(wc -c <"$SONY_TRACE_LOG" 2>/dev/null || echo 0)"
  if [[ "$CURRENT_TRACE_SIZE" -le "$LAST_TRACE_SIZE" ]]; then
    NO_GROWTH_ELAPSED=$((NO_GROWTH_ELAPSED + 15))
    if [[ "$NO_GROWTH_ELAPSED" -ge 30 ]]; then
      NO_GROWTH_WARNINGS=$((NO_GROWTH_WARNINGS + 1))
      echo "no_growth_at_sec=$ELAPSED bytes=$CURRENT_TRACE_SIZE" >>"$OUT_DIR/sony_lyric_trace.no_growth"
      NO_GROWTH_ELAPSED=0
    fi
  else
    NO_GROWTH_ELAPSED=0
    LAST_TRACE_SIZE="$CURRENT_TRACE_SIZE"
  fi
done
echo "$NO_GROWTH_WARNINGS" >"$OUT_DIR/sony_lyric_trace.no_growth_count"
touch "$SNAPSHOT_STOP_FILE"
if [[ -n "$SNAPSHOT_PID" ]]; then
  wait "$SNAPSHOT_PID" 2>/dev/null || true
fi
if [[ -n "$SONY_NEXT_PID" ]]; then
  kill "$SONY_NEXT_PID" 2>/dev/null || true
  wait "$SONY_NEXT_PID" 2>/dev/null || true
fi
stop_sony_trace_collector
cp "$SONY_TRACE_LOG" "$OUT_DIR/sony_logcat.log" 2>/dev/null || : >"$OUT_DIR/sony_logcat.log"
if [[ "$SKIP_IOS_FILE_LOGS" != true ]]; then
  copy_ios_log_with_timeout "$OUT_DIR/ios_ble.log" 8 || true
  copy_ios_log_with_timeout "$OUT_DIR/ios_lyrics_timeline.log" 5 Documents/Logs/ios_lyrics_timeline.log || true
else
  : > "$OUT_DIR/ios_ble.log"
  : > "$OUT_DIR/ios_lyrics_timeline.log"
fi
cp "$OUT_DIR/ios_ble.log" "$IOS_SNAPSHOT_DIR/ios_ble_final.log" 2>/dev/null || true
cp "$OUT_DIR/ios_lyrics_timeline.log" "$IOS_SNAPSHOT_DIR/ios_timeline_final.log" 2>/dev/null || true
python3 - "$OUT_DIR" "$IOS_SNAPSHOT_DIR" <<'PY'
import sys
from pathlib import Path
out = Path(sys.argv[1])
snapshots = Path(sys.argv[2])
seen = set()
lines = []
for pattern in ("ios_timeline_*.log", "ios_ble_*.log"):
  for path in sorted(snapshots.glob(pattern)):
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if line in seen:
            continue
        seen.add(line)
        lines.append(line)
(out / "ios_ble.log").write_text("\n".join(lines) + ("\n" if lines else ""), encoding="utf-8")
PY

MODE="$([[ "$SONY_ONLY" == true ]] && echo sony_only || echo full)"
python3 - "$OUT_DIR" "$START_EPOCH_MS" "$DURATION_SEC" "${IOS_DEVICE_ID:-}" "$ANDROID_DEVICE_ID" "$EXPECTED_TRACK_COUNT" "$MODE" <<'PY'
import json
import re
import statistics
import sys
import time
from collections import Counter
from datetime import datetime
from pathlib import Path

out = Path(sys.argv[1])
start_epoch_ms = int(sys.argv[2])
duration_sec = int(sys.argv[3])
ios_device = sys.argv[4]
android_device = sys.argv[5]
expected_track_count = int(sys.argv[6])
mode = sys.argv[7]
sony_only = mode == "sony_only"
precheck_path = out / "ios_ble_precheck.json"
precheck = json.loads(precheck_path.read_text()) if precheck_path.exists() else {}
sony_trace_path = out / "sony_lyric_trace.log"
sony_logcat_path = out / "sony_logcat.log"
sony_text = sony_trace_path.read_text(encoding="utf-8", errors="replace") if sony_trace_path.exists() else ""
if not sony_text and sony_logcat_path.exists():
    sony_text = sony_logcat_path.read_text(encoding="utf-8", errors="replace")
ios_text = (out / "ios_ble.log").read_text(encoding="utf-8", errors="replace") if (out / "ios_ble.log").exists() else ""

android_ts_re = re.compile(r"^(?P<date>\d{2}-\d{2})\s+(?P<time>\d{2}:\d{2}:\d{2}\.\d{3})")
android_epoch_re = re.compile(r"^\s*(?P<sec>\d{10})(?:\.(?P<fraction>\d+))?")
ios_ts_re = re.compile(r"^(?P<ts>\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})")
lyric_trace_re = re.compile(r"\[LyricTrace\]\s+(?P<detail>.*)")
ios_trace_re = re.compile(r"\[LyricTrace-iOS\]\s+id=(?P<id>\S+)\s+stage=(?P<stage>\S+)\s*(?P<detail>.*)")
matrix_run_re = re.compile(r"\[TrackMatrixV31\](?:\s+runId=(?P<runId>\S+))?\s+(?P<event>\S+)\s*(?P<detail>.*)")
matrix_sample_re = re.compile(r"\[TrackMatrixV31\](?:\s+runId=(?P<runId>\S+))?\s+sampleStart\s+(?P<detail>.*)")
kv_re = re.compile(r"([A-Za-z][A-Za-z0-9_]+)=((?:\"[^\"]*\")|(?:.*?)(?=\s+[A-Za-z][A-Za-z0-9_]+=|$))")

def parse_kv(detail: str):
    result = {}
    for key, value in kv_re.findall(detail):
        result[key] = value.strip().strip('"')
    return result

def clean_text_value(value: str):
    value = (value or "").strip()
    return re.sub(r"\s+t=\d+$", "", value).strip()

def android_ms(line: str):
    epoch_match = android_epoch_re.search(line)
    if epoch_match:
        fraction = (epoch_match.group("fraction") or "0")[:3].ljust(3, "0")
        return int(epoch_match.group("sec")) * 1000 + int(fraction)
    match = android_ts_re.search(line)
    if not match:
        return 0
    year = datetime.fromtimestamp(start_epoch_ms / 1000).year
    try:
        return int(datetime.strptime(f"{year}-{match.group('date')} {match.group('time')}", "%Y-%m-%d %H:%M:%S.%f").timestamp() * 1000)
    except ValueError:
        return 0

def ios_ms(line: str):
    match = ios_ts_re.search(line)
    if not match:
        return 0
    try:
        return int(datetime.strptime(match.group("ts"), "%Y-%m-%d %H:%M:%S.%f").timestamp() * 1000)
    except ValueError:
        return 0

def clean_id(value: str):
    value = (value or "").strip()
    if "@" in value:
        value = value.split("@", 1)[0]
    return value

def same_id(a: str, b: str):
    a = clean_id(a)
    b = clean_id(b)
    return bool(a and b and (a == b or a.startswith(b) or b.startswith(a)))

tracks = {}
aliases = {}
matrix = {
    "expectedTrackCount": expected_track_count,
    "runId": "",
    "sampleStarts": [],
    "trackChanged": [],
    "retryNext": [],
    "trackNotChanged": [],
    "abort": [],
    "end": [],
}
maintenance_patterns = (
    "maintenance busy",
    "lyric recovery active",
    "cache rebuild running",
    "warmup running",
    "qrc index rebuild running",
    "rebuild running",
)

def resolve(raw_id: str):
    raw = clean_id(raw_id)
    if raw in aliases:
        return aliases[raw]
    for key in tracks:
        if same_id(raw, key):
            return key
    return raw

def track(raw_id: str):
    key = resolve(raw_id)
    if key not in tracks:
        tracks[key] = {
            "trackId": key,
            "title": "",
            "artist": "",
            "sampleIndexes": [],
            "events": {},
            "details": {},
            "timeline": {},
            "durations": {},
            "classification": "",
            "maxSegment": "",
            "maxSegmentMs": 0,
            "optimization": "",
            "missingStages": [],
            "traceCompleteness": {},
            "maintenanceReasons": [],
            "maintenanceBusyDurationMs": 0,
        }
    return tracks[key]

def bind(key: str, *vals):
    key = clean_id(key)
    for val in vals:
        val = clean_id(val)
        if val:
            aliases[val] = key

def set_event(item, name, when, detail=None, prefer_first=True):
    if not when:
        when = int(time.time() * 1000)
    if prefer_first and item["events"].get(name):
        return
    item["events"][name] = when
    if detail is not None:
        item["details"][name] = detail

def remember_maintenance(item, reason, when=0):
    reason = clean_text_value(reason)
    if not reason:
        reason = "maintenance_task_active"
    if reason not in item["maintenanceReasons"]:
        item["maintenanceReasons"].append(reason)
    set_event(item, "maintenanceBusyAt", when, {"reason": reason}, prefer_first=True)

def contains_maintenance(text: str):
    lower = (text or "").lower()
    return next((pattern for pattern in maintenance_patterns if pattern in lower), "")

for line in ios_text.splitlines():
    ts = ios_ms(line)
    if ts and ts < start_epoch_ms - 2000:
        continue
    match = matrix_run_re.search(line)
    if not match:
        continue
    run_id = match.group("runId") or ""
    if run_id and match.group("event") == "start":
        matrix["runId"] = run_id

def is_current_matrix_line(line: str):
    match = matrix_run_re.search(line)
    if not match:
        return False
    run_id = match.group("runId") or ""
    return not matrix["runId"] or not run_id or run_id == matrix["runId"]

for line in ios_text.splitlines():
    ts = ios_ms(line)
    if ts and ts < start_epoch_ms - 2000:
        continue
    if "[TrackMatrixV31]" in line and not is_current_matrix_line(line):
        continue
    matrix_event = matrix_run_re.search(line)
    if matrix_event:
        event_name = matrix_event.group("event")
        if event_name == "trackChanged":
            matrix["trackChanged"].append({"timeMs": ts, "line": line})
        elif event_name == "retryNext":
            matrix["retryNext"].append({"timeMs": ts, "line": line})
        elif event_name == "track_not_changed":
            matrix["trackNotChanged"].append({"timeMs": ts, "line": line})
        elif event_name == "abort":
            matrix["abort"].append({"timeMs": ts, "line": line})
        elif event_name == "end":
            matrix["end"].append({"timeMs": ts, "line": line})
    sample = matrix_sample_re.search(line)
    if sample:
        detail = parse_kv(sample.group("detail"))
        item = track(detail.get("trackId", ""))
        bind(item["trackId"], detail.get("trackId", ""))
        item["title"] = clean_text_value(detail.get("title", item["title"]))
        item["artist"] = clean_text_value(detail.get("artist", item["artist"]))
        sample_index = int(detail.get("index") or 0)
        if sample_index and sample_index not in item["sampleIndexes"]:
            item["sampleIndexes"].append(sample_index)
        matrix["sampleStarts"].append({
            "index": sample_index,
            "trackId": item["trackId"],
            "timeMs": int(detail.get("timeMs") or ts or 0),
            "title": item["title"],
            "artist": item["artist"],
        })
        set_event(item, "trackChangedAt", int(detail.get("timeMs") or ts or 0), detail)
        continue
    match = ios_trace_re.search(line)
    if not match:
        continue
    detail = parse_kv(match.group("detail"))
    item = track(match.group("id"))
    bind(item["trackId"], match.group("id"), detail.get("trackId"))
    stage = match.group("stage")
    if stage == "trackInfoReceived":
        item["title"] = clean_text_value(detail.get("title", item["title"]))
        item["artist"] = clean_text_value(detail.get("artist", item["artist"]))
        set_event(item, "metadataChangedAt", int(detail.get("t") or ts or 0), detail)
    elif stage == "requestFullLyrics":
        set_event(item, "getFullLyricsRequestAt", ts, detail)
    elif stage == "fullLyricsStart":
        set_event(item, "iosFullLyricsStartAt", ts, detail)
    elif stage in ("fullLyricsFinal", "uiPublished"):
        set_event(item, "iosFullLyricsReceivedAt", ts, detail, prefer_first=False)
    elif stage == "currentWordAccepted":
        set_event(item, "iosFirstCurrentWordAcceptedAt", ts, detail)

for line in sony_text.splitlines():
    ts = android_ms(line)
    if ts and ts < start_epoch_ms - 2000:
        continue
    match = lyric_trace_re.search(line)
    if not match:
        continue
    detail = parse_kv(match.group("detail"))
    stage = detail.get("stage", "")
    trace_id = detail.get("trackId") or detail.get("id") or detail.get("songKey") or ""
    item = track(trace_id)
    bind(item["trackId"], detail.get("id"), detail.get("trackId"), detail.get("songKey"))
    stage_map = {
        "trackChanged": "trackChangedAt",
        "reactiveEvent": "reactiveEventReceivedAt",
        "reactiveEventReceived": "reactiveEventReceivedAt",
        "debounceFlush": "debounceFlushAt",
        "parseScheduled": "lyricsParseScheduledAt",
        "lyricsParseScheduled": "lyricsParseScheduledAt",
        "qrcLookupStart": "qrcLookupStartAt",
        "qrcFileFound": "qrcFileFoundAt",
        "qrcFileNotFound": "qrcFileNotFoundAt",
        "qrcLookupEnd": "qrcLookupEndAt",
        "qrcDecryptStart": "qrcDecryptStartAt",
        "qrcDecryptEnd": "qrcDecryptEndAt",
        "qrcParseStart": "qrcParseStartAt",
        "qrcParseEnd": "qrcParseEndAt",
        "indexBuildStart": "indexBuildStartAt",
        "indexBuildEnd": "indexBuildEndAt",
        "runtimeApplyStart": "runtimeApplyStartAt",
        "runtimeApplyEnd": "runtimeCacheApplyAt",
        "runtimeCacheUpdated": "runtimeCacheApplyAt",
        "lyricsReadyGateReady": "lyricsReadyGateReadyAt",
        "readyGateReady": "lyricsReadyGateReadyAt",
        "readyGateFailed": "lyricsReadyGateFailedAt",
        "fullLyricsRequest": "getFullLyricsRequestAt",
        "pendingQueued": "pendingQueuedAt",
        "pendingFlush": "pendingFlushAt",
        "fullLyricsSendStart": "fullLyricsSendStartAt",
        "fullLyricsSendEnd": "fullLyricsSendEndAt",
        "fullLyricsSendSkip": "fullLyricsSendSkipAt",
    }
    if stage == "trackChanged":
        item["title"] = clean_text_value(detail.get("title", item["title"]))
        item["artist"] = clean_text_value(detail.get("artist", item["artist"]))
        set_event(item, "trackChangedAt", int(detail.get("t") or ts or 0), detail)
    elif stage == "qrcLookup" and detail.get("result") == "start":
        set_event(item, "qrcLookupStartAt", ts, detail)
    elif stage == "qrcLookup" and detail.get("result") in ("hit", "miss"):
        set_event(item, "qrcLookupEndAt", ts, detail, prefer_first=False)
        if detail.get("result") == "miss":
            set_event(item, "qrcFileNotFoundAt", ts, detail, prefer_first=False)
    elif stage == "decrypt":
        if detail.get("result") == "success":
            set_event(item, "qrcDecryptEndAt", ts, detail, prefer_first=False)
        elif detail.get("result") in ("fail", "cancelled"):
            set_event(item, "qrcDecryptEndAt", ts, detail, prefer_first=False)
    elif stage == "ready":
        set_event(item, "lyricsReadyGateReadyAt", ts, detail, prefer_first=False)
    elif stage == "failed":
        set_event(item, "lyricsFailedAt", ts, detail, prefer_first=False)
    busy_reason = contains_maintenance(match.group("detail"))
    if busy_reason:
        remember_maintenance(item, detail.get("reason", busy_reason), ts)
    if stage in stage_map:
        set_event(item, stage_map[stage], ts, detail, prefer_first=stage not in ("qrcLookupEnd", "qrcDecryptEnd", "qrcParseEnd", "fullLyricsSendEnd"))

def delta(item, start, end):
    a = item["events"].get(start, 0)
    b = item["events"].get(end, 0)
    return max(0, b - a) if a and b else 0

segments = [
    ("trackChangedToQrcFileFoundMs", "trackChangedAt", "qrcFileFoundAt"),
    ("qrcFileFoundToParseDoneMs", "qrcFileFoundAt", "qrcParseEndAt"),
    ("parseDoneToIndexBuiltMs", "qrcParseEndAt", "indexBuildEndAt"),
    ("indexBuiltToRuntimeAppliedMs", "indexBuildEndAt", "runtimeCacheApplyAt"),
    ("runtimeAppliedToReadyGateMs", "runtimeCacheApplyAt", "lyricsReadyGateReadyAt"),
    ("readyGateToFullLyricsSendStartMs", "lyricsReadyGateReadyAt", "fullLyricsSendStartAt"),
    ("fullLyricsSendStartToIosReceivedMs", "fullLyricsSendStartAt", "iosFullLyricsReceivedAt"),
    ("trackChangedToIosFullLyricsMs", "trackChangedAt", "iosFullLyricsReceivedAt"),
    ("trackChangedToFirstCurrentWordMs", "trackChangedAt", "iosFirstCurrentWordAcceptedAt"),
    ("pendingDelayMs", "pendingQueuedAt", "pendingFlushAt"),
]

def trace_completeness(item):
    events = item["events"]
    details = item["details"]
    missing = []

    def require(event, label=None):
        if not events.get(event):
            missing.append(label or event.replace("At", ""))

    require("trackChangedAt", "trackChanged")
    require("reactiveEventReceivedAt", "reactiveEvent")
    require("debounceFlushAt", "debounceFlush")
    require("lyricsParseScheduledAt", "parseScheduled")
    require("qrcLookupStartAt", "qrcLookupStart")
    if not (events.get("qrcFileFoundAt") or events.get("qrcFileNotFoundAt") or details.get("qrcLookupEndAt", {}).get("result") == "miss"):
        missing.append("qrcFileFoundOrNotFound")

    qrc_found_detail = details.get("qrcFileFoundAt", {})
    qrc_found_source = qrc_found_detail.get("source", "")
    has_file = bool(events.get("qrcFileFoundAt"))
    requires_qrc_file_stages = has_file and qrc_found_source not in ("parsed_cache", "song_memory")
    parse_success = details.get("qrcParseEndAt", {}).get("result") not in ("failed", "fail", "miss")
    if requires_qrc_file_stages:
        require("qrcDecryptStartAt", "qrcDecryptStart")
        require("qrcDecryptEndAt", "qrcDecryptEnd")
        require("qrcParseStartAt", "qrcParseStart")
        require("qrcParseEndAt", "qrcParseEnd")
    if has_file and parse_success and (events.get("qrcParseEndAt") or not requires_qrc_file_stages):
        require("indexBuildStartAt", "indexBuildStart")
        require("indexBuildEndAt", "indexBuildEnd")
        require("runtimeCacheApplyAt", "runtimeApply")
        require("lyricsReadyGateReadyAt", "readyGate")
    if events.get("getFullLyricsRequestAt") or events.get("fullLyricsSendStartAt") or events.get("fullLyricsSendEndAt"):
        if not (events.get("fullLyricsSendStartAt") or events.get("fullLyricsSendSkipAt")):
            missing.append("fullLyricsSendStartOrSkip")
        if events.get("fullLyricsSendStartAt"):
            require("fullLyricsSendEndAt", "fullLyricsSendEnd")
    if not sony_only:
        require("iosFullLyricsReceivedAt", "iOSFullLyricsReceived")
        require("iosFirstCurrentWordAcceptedAt", "iOSFirstCurrentWordAccepted")
    return {
        "complete": not missing,
        "missingStages": missing,
        "hasQrcFile": has_file,
        "parseSuccess": has_file and parse_success and bool(events.get("qrcParseEndAt")),
    }

def classify(item):
    d = item["durations"]
    events = item["events"]
    details = item["details"]
    qrc_end = details.get("qrcLookupEndAt", {})
    parse_end = details.get("qrcParseEndAt", {})
    if item.get("missingStages"):
        return "TRACE_INCOMPLETE"
    if item.get("maintenanceReasons"):
        return "BLOCKED_BY_MAINTENANCE"
    if details.get("qrcLookupEndAt", {}).get("reason", "").lower().find("cooldown") >= 0:
        return "COOLDOWN_BLOCKED"
    if not events.get("qrcFileFoundAt") and not events.get("iosFullLyricsReceivedAt"):
        return "SOURCE_NOT_PROVIDED"
    if not events.get("qrcFileFoundAt") or d.get("trackChangedToQrcFileFoundMs", 0) > 1000:
        return "SOURCE_WAIT"
    decrypt_ms = delta(item, "qrcDecryptStartAt", "qrcDecryptEndAt")
    parse_ms = delta(item, "qrcParseStartAt", "qrcParseEndAt")
    if max(decrypt_ms, parse_ms, d.get("qrcFileFoundToParseDoneMs", 0)) > 500:
        return "PARSE_SLOW"
    if delta(item, "indexBuildStartAt", "indexBuildEndAt") > 300:
        return "INDEX_SLOW"
    if d.get("runtimeAppliedToReadyGateMs", 0) > 300:
        return "READY_GATE_DELAY"
    if d.get("pendingDelayMs", 0) > 1000:
        return "PENDING_DELAY"
    if not sony_only and d.get("fullLyricsSendStartToIosReceivedMs", 0) > 1000:
        return "BLE_SEND_SLOW"
    if (
        not sony_only and
        events.get("fullLyricsSendEndAt") and
        events.get("iosFullLyricsReceivedAt") and
        delta(item, "fullLyricsSendEndAt", "iosFullLyricsReceivedAt") > 1000
    ):
        return "IOS_RECEIVE_DELAY"
    if parse_end.get("result") == "failed" or qrc_end.get("result") == "miss":
        return "SOURCE_NOT_PROVIDED"
    return "READY_FAST"

track_rows = []
sony_track_stage_events = (
    "reactiveEventReceivedAt",
    "debounceFlushAt",
    "lyricsParseScheduledAt",
    "qrcLookupStartAt",
    "qrcFileFoundAt",
    "qrcFileNotFoundAt",
    "qrcDecryptStartAt",
    "qrcDecryptEndAt",
    "qrcParseStartAt",
    "qrcParseEndAt",
    "indexBuildStartAt",
    "indexBuildEndAt",
    "runtimeApplyStartAt",
    "runtimeCacheApplyAt",
    "lyricsReadyGateReadyAt",
    "lyricsReadyGateFailedAt",
    "getFullLyricsRequestAt",
    "pendingQueuedAt",
    "pendingFlushAt",
    "fullLyricsSendStartAt",
    "fullLyricsSendEndAt",
    "fullLyricsSendSkipAt",
)
for item in tracks.values():
    if not item["trackId"] or item["trackId"] in ("-", "unknown"):
        continue
    has_sony_trace = any(item["events"].get(event) for event in sony_track_stage_events)
    if (
        not sony_only and
        not item["events"].get("trackChangedAt") and
        not item["events"].get("metadataChangedAt")
    ):
        continue
    if sony_only and not has_sony_trace:
        continue
    for name, start, end in segments:
        item["durations"][name] = delta(item, start, end)
    completeness = trace_completeness(item)
    item["traceCompleteness"] = completeness
    item["missingStages"] = completeness["missingStages"]
    if item["events"].get("maintenanceBusyAt"):
        end_event = item["events"].get("lyricsFailedAt") or item["events"].get("iosFullLyricsReceivedAt") or item["events"].get("maintenanceBusyAt")
        item["maintenanceBusyDurationMs"] = max(0, end_event - item["events"]["maintenanceBusyAt"])
    item["classification"] = classify(item)
    candidate_segments = {k: v for k, v in item["durations"].items() if v > 0}
    if candidate_segments:
        item["maxSegment"], item["maxSegmentMs"] = max(candidate_segments.items(), key=lambda kv: kv[1])
    if item["classification"] == "TRACE_INCOMPLETE":
        item["optimization"] = "不可判断：Sony/iOS timeline trace 不完整"
    elif item["classification"] == "BLOCKED_BY_MAINTENANCE":
        item["optimization"] = "可优化测试环境：先停止维护任务后复测"
    elif item["classification"] in ("SOURCE_NOT_PROVIDED", "COOLDOWN_BLOCKED"):
        item["optimization"] = "不可优化：源不可用或 retry/cooldown 状态"
    elif item["classification"] in ("SOURCE_WAIT",):
        item["optimization"] = "低收益：等待 QQ音乐/QRC 源生成"
    elif item["classification"] in ("PARSE_SLOW", "INDEX_SLOW", "READY_GATE_DELAY", "PENDING_DELAY", "BLE_SEND_SLOW", "IOS_RECEIVE_DELAY"):
        item["optimization"] = "可优化：优先分析最大耗时段"
    else:
        item["optimization"] = "无需优化"
    track_rows.append(item)

ready_slow = [
    t for t in track_rows
    if t["events"].get("iosFullLyricsReceivedAt") and t["durations"].get("trackChangedToIosFullLyricsMs", 0) > 2000
]
failed = [t for t in track_rows if not t["events"].get("iosFullLyricsReceivedAt")]
class_counts = Counter(t["classification"] for t in track_rows)
slow_counts = Counter(t["classification"] for t in ready_slow + failed)
optimizable = [t for t in track_rows if t["optimization"].startswith("可优化")]

deduped_samples = []
sample_keys = set()
for sample in matrix["sampleStarts"]:
    sample_key = (sample.get("index", 0), sample.get("trackId", ""), sample.get("timeMs", 0))
    if sample_key in sample_keys:
        continue
    sample_keys.add(sample_key)
    deduped_samples.append(sample)
matrix["sampleStarts"] = deduped_samples

def dedupe_matrix_events(events):
    deduped = []
    seen = set()
    for event in events:
        line = event.get("line", "")
        key = line.split("[TrackMatrixV31]", 1)[1] if "[TrackMatrixV31]" in line else line
        if key in seen:
            continue
        seen.add(key)
        deduped.append(event)
    return deduped

for event_key in ("trackChanged", "retryNext", "trackNotChanged", "abort", "end"):
    matrix[event_key] = dedupe_matrix_events(matrix[event_key])

seen_ids = set()
duplicate_count = 0
for sample in matrix["sampleStarts"]:
    track_id = sample.get("trackId", "")
    if track_id in seen_ids:
        duplicate_count += 1
    elif track_id:
        seen_ids.add(track_id)
actual_track_count = len(seen_ids)
if sony_only:
    sony_seen_ids = {
        t["trackId"]
        for t in track_rows
        if t.get("events", {}).get("trackChangedAt") or t.get("events", {}).get("qrcLookupStartAt")
    }
    actual_track_count = len(sony_seen_ids)
    duplicate_count = 0
skipped_track_count = max(0, expected_track_count - actual_track_count)
maintenance_tracks = [t for t in track_rows if t.get("maintenanceReasons")]
sample_result = "PASS" if actual_track_count >= expected_track_count and duplicate_count == 0 else "FAIL"
sample_fail_reason = ""
if actual_track_count < expected_track_count:
    sample_fail_reason = "sample_incomplete_timeout"
elif duplicate_count > 0:
    sample_fail_reason = "duplicate_tracks_observed"
if sony_only and actual_track_count > 0:
    sample_result = "WARN" if actual_track_count < expected_track_count else "PASS"
trace_stage_counts = Counter(
    stage
    for t in track_rows
    for stage in (
        "qrcLookupStartAt",
        "qrcFileFoundAt",
        "qrcFileNotFoundAt",
        "qrcDecryptStartAt",
        "qrcDecryptEndAt",
        "qrcParseStartAt",
        "qrcParseEndAt",
        "indexBuildStartAt",
        "indexBuildEndAt",
        "runtimeCacheApplyAt",
        "lyricsReadyGateReadyAt",
        "lyricsReadyGateFailedAt",
        "fullLyricsSendStartAt",
        "fullLyricsSendEndAt",
        "fullLyricsSendSkipAt",
    )
    if t["events"].get(stage)
)
sony_lyric_trace_line_count = sum(1 for line in sony_text.splitlines() if "[LyricTrace]" in line)
trace_file = out / "sony_lyric_trace.log"
trace_lines = [line for line in sony_text.splitlines() if "[LyricTrace]" in line]
trace_file_bytes = trace_file.stat().st_size if trace_file.exists() else 0
collector_exit_code_path = out / "sony_lyric_trace.collector_exit_code"
collector_status_path = out / "sony_lyric_trace.collector_status"
collector_pid_path = out / "sony_lyric_trace.pid"
collector_no_growth_path = out / "sony_lyric_trace.no_growth_count"
collector_exit_code = collector_exit_code_path.read_text(encoding="utf-8", errors="replace").strip() if collector_exit_code_path.exists() else ""
collector_status = collector_status_path.read_text(encoding="utf-8", errors="replace").strip() if collector_status_path.exists() else ""
collector_pid = collector_pid_path.read_text(encoding="utf-8", errors="replace").strip() if collector_pid_path.exists() else ""
collector_no_growth = int((collector_no_growth_path.read_text(encoding="utf-8", errors="replace").strip() or "0")) if collector_no_growth_path.exists() else 0
first_trace_epoch = android_ms(trace_lines[0]) if trace_lines else 0
last_trace_epoch = android_ms(trace_lines[-1]) if trace_lines else 0
collector_failed = "collector_failed" in collector_status or (collector_exit_code not in ("", "0", "143"))
trace_coverage_warning = ""
if track_rows and not trace_stage_counts:
    trace_coverage_warning = "sony_internal_lyric_trace_missing"

def p95(values):
    values = sorted(v for v in values if v > 0)
    if not values:
        return 0
    return values[min(len(values) - 1, int(len(values) * 0.95))]

summary = {
    "result": "PASS",
    "mode": mode,
    "status": "SONY_TIMELINE_ONLY" if sony_only else "FULL_TIMELINE",
    "durationSec": duration_sec,
    "trackCount": len(track_rows),
    "readySlowCount": len(ready_slow),
    "failedCount": len(failed),
    "classificationCounts": dict(class_counts),
    "slowClassificationCounts": dict(slow_counts),
    "optimizableCount": len(optimizable),
    "trackChangedToIosFullLyricsAvgMs": int(statistics.mean([t["durations"].get("trackChangedToIosFullLyricsMs", 0) for t in track_rows if t["durations"].get("trackChangedToIosFullLyricsMs", 0)] or [0])),
    "trackChangedToIosFullLyricsP95Ms": p95([t["durations"].get("trackChangedToIosFullLyricsMs", 0) for t in track_rows]),
    "iosAppLaunched": precheck.get("iosAppLaunched", False),
    "iosBleConnected": precheck.get("iosBleConnected", False),
    "notifySubscribed": precheck.get("notifySubscribed", False),
    "firstPlaybackStateReceived": precheck.get("firstPlaybackStateReceived", False),
    "firstPlaybackStateLatencyMs": precheck.get("firstPlaybackStateLatencyMs", 0),
    "precheckResult": precheck.get("precheckResult", "UNKNOWN"),
    "precheckFailReason": precheck.get("precheckFailReason", ""),
    "iosAppLaunchPrecheck": precheck.get("iosAppLaunchPrecheck", "UNKNOWN"),
    "iosBlePrecheck": precheck.get("iosBlePrecheck", "UNKNOWN"),
    "iosFileLogPrecheck": precheck.get("iosFileLogPrecheck", "UNKNOWN"),
    "sonyTracePrecheck": "PASS" if sony_lyric_trace_line_count > 0 else "FAIL",
    "expectedTrackCount": expected_track_count,
    "actualTrackCount": actual_track_count,
    "duplicateTrackCount": duplicate_count,
    "skippedTrackCount": skipped_track_count,
    "trackNotChangedCount": len(matrix["trackNotChanged"]),
    "sampleResult": sample_result,
    "sampleFailReason": sample_fail_reason,
    "matrixEndSeen": bool(matrix["end"]),
    "matrixAbortCount": len(matrix["abort"]),
    "maintenanceBusyTrackCount": len(maintenance_tracks),
    "affectedTracks": [
        {
            "trackId": t["trackId"],
            "title": t["title"],
            "artist": t["artist"],
            "busyReason": "; ".join(t.get("maintenanceReasons", [])),
            "busyDurationMs": t.get("maintenanceBusyDurationMs", 0),
        }
        for t in maintenance_tracks
    ],
    "sonyLyricTraceLineCount": sony_lyric_trace_line_count,
    "sonyInternalTraceStageCounts": dict(trace_stage_counts),
    "traceCoverageWarning": trace_coverage_warning,
    "collector": {
        "mode": "realtime",
        "started": "started" in collector_status,
        "pid": collector_pid,
        "traceFile": str(trace_file),
        "traceFileBytes": trace_file_bytes,
        "lyricTraceLineCount": sony_lyric_trace_line_count,
        "firstTraceEpoch": first_trace_epoch,
        "lastTraceEpoch": last_trace_epoch,
        "noGrowthWarnings": collector_no_growth,
        "collectorExitCode": collector_exit_code,
        "collectorFailed": collector_failed,
    },
}
if not sony_only and summary["precheckResult"] != "PASS":
    summary["result"] = "FAIL"
elif not sony_only and sample_result != "PASS":
    summary["result"] = "FAIL"
    summary["precheckFailReason"] = sample_fail_reason
elif sony_only and sony_lyric_trace_line_count <= 0:
    summary["result"] = "FAIL"
    summary["precheckFailReason"] = "sony_lyric_trace_empty"
elif collector_failed:
    summary["result"] = "FAIL"
    summary["precheckFailReason"] = "sony_logcat_collector_failed"
elif sony_only and not trace_stage_counts:
    summary["result"] = "FAIL"
    summary["precheckFailReason"] = "sony_internal_trace_stage_missing"
elif sony_only and sample_result == "WARN":
    summary["result"] = "WARN"
    summary["precheckFailReason"] = sample_fail_reason

report_tracks = []
for item in track_rows:
    report_tracks.append({
        "trackId": item["trackId"],
        "title": item["title"],
        "artist": item["artist"],
        "classification": item["classification"],
        "maxSegment": item["maxSegment"],
        "maxSegmentMs": item["maxSegmentMs"],
        "optimization": item["optimization"],
        "sampleIndexes": item["sampleIndexes"],
        "traceCompleteness": item["traceCompleteness"],
        "missingStages": item["missingStages"],
        "maintenanceReasons": item["maintenanceReasons"],
        "maintenanceBusyDurationMs": item["maintenanceBusyDurationMs"],
        "timeline": item["events"],
        "durations": item["durations"],
        "details": item["details"],
    })

payload = {
    "summary": summary,
    "collector": summary["collector"],
    "precheck": precheck,
    "readySlowTracks": [
        t for t in report_tracks
        if t["timeline"].get("iosFullLyricsReceivedAt") and t["durations"].get("trackChangedToIosFullLyricsMs", 0) > 2000
    ],
    "tracks": report_tracks,
    "optimizable": [t for t in report_tracks if t["optimization"].startswith("可优化")],
    "notOptimizable": [t for t in report_tracks if t["optimization"].startswith("不可优化")],
    "traceIncompleteTracks": [t for t in report_tracks if t["classification"] == "TRACE_INCOMPLETE"],
    "maintenanceBusyTracks": [t for t in report_tracks if t["classification"] == "BLOCKED_BY_MAINTENANCE"],
    "matrix": matrix,
    "artifacts": {
        "report_json": str(out / "report.json"),
        "report_md": str(out / "report.md"),
        "ios_ble_log": str(out / "ios_ble.log"),
        "sony_logcat": str(out / "sony_logcat.log"),
    },
    "devices": {
        "ios": ios_device,
        "android": android_device,
    },
    "warnings": [value for value in [
        trace_coverage_warning,
        "maintenance_task_active" if maintenance_tracks else "",
    ] if value],
}
(out / "report.json").write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")

lines = [
    "# Lyrics Timeline V3.4",
    "",
    f"Result: {summary['result']}",
    f"Mode: {summary['mode']}",
    f"Status: {summary['status']}",
    f"Tracks: {summary['trackCount']}",
    f"Expected tracks: {summary['expectedTrackCount']}",
    f"Actual unique tracks: {summary['actualTrackCount']}",
    f"Duplicate samples: {summary['duplicateTrackCount']}",
    f"Skipped tracks: {summary['skippedTrackCount']}",
    f"Track not changed: {summary['trackNotChangedCount']}",
    f"Sample result: {summary['sampleResult']} {summary['sampleFailReason']}",
    f"READY_SLOW: {summary['readySlowCount']}",
    f"FAILED: {summary['failedCount']}",
    f"Maintenance busy tracks: {summary['maintenanceBusyTrackCount']}",
    f"Classification counts: `{summary['classificationCounts']}`",
    f"Sony LyricTrace lines: {summary['sonyLyricTraceLineCount']}",
    f"Sony stage counts: `{summary['sonyInternalTraceStageCounts']}`",
    f"Collector: `{summary['collector']}`",
    f"Trace coverage warning: `{summary['traceCoverageWarning']}`",
    "",
    "## READY_SLOW / Failed Timeline",
    "",
    "| track | class | max segment | max ms | track->fullLyrics | track->currentWord | optimization |",
    "|---|---|---|---:|---:|---:|---|",
]
focus = [t for t in report_tracks if t in payload["readySlowTracks"] or not t["timeline"].get("iosFullLyricsReceivedAt")]
for item in focus:
    name = f"{item['title']} / {item['artist']}".strip(" /").replace("|", "/")
    d = item["durations"]
    lines.append(
        f"| {name or item['trackId']} | {item['classification']} | {item['maxSegment']} | "
        f"{item['maxSegmentMs']} | {d.get('trackChangedToIosFullLyricsMs', 0)} | "
        f"{d.get('trackChangedToFirstCurrentWordMs', 0)} | {item['optimization']} |"
    )
if not focus:
    lines.append("| - | - | - | 0 | 0 | 0 | no slow tracks |")
lines.extend([
    "",
    "## Trace Incomplete Tracks",
    "",
    "| track | missing stages |",
    "|---|---|",
])
trace_incomplete = [t for t in report_tracks if t["classification"] == "TRACE_INCOMPLETE"]
if trace_incomplete:
    for item in trace_incomplete:
        name = f"{item['title']} / {item['artist']}".strip(" /").replace("|", "/")
        lines.append(f"| {name or item['trackId']} | {', '.join(item['missingStages'])} |")
else:
    lines.append("| - | - |")

lines.extend([
    "",
    "## Maintenance Busy Tracks",
    "",
    "| track | reason | busy ms |",
    "|---|---|---:|",
])
if maintenance_tracks:
    for item in maintenance_tracks:
        name = f"{item['title']} / {item['artist']}".strip(" /").replace("|", "/")
        lines.append(
            f"| {name or item['trackId']} | {'; '.join(item.get('maintenanceReasons', []))} | "
            f"{item.get('maintenanceBusyDurationMs', 0)} |"
        )
else:
    lines.append("| - | - | 0 |")

lines.extend([
    "",
    "## Segment Definitions",
    "",
    "- SOURCE_WAIT: QRC file found late or missing.",
    "- PARSE_SLOW: decrypt/parse over 500ms.",
    "- INDEX_SLOW: index build over 300ms.",
    "- READY_GATE_DELAY: runtime apply to ready gate over 300ms.",
    "- PENDING_DELAY: pending request queued to flush over 1000ms.",
    "- BLE_SEND_SLOW: FullLyrics send start to iOS received over 1000ms.",
    "- SOURCE_NOT_PROVIDED: no QRC / no FullLyrics result.",
    "",
    "## Artifacts",
    "",
    f"- report.json: `{out / 'report.json'}`",
    f"- ios_ble.log: `{out / 'ios_ble.log'}`",
    f"- sony_logcat.log: `{out / 'sony_logcat.log'}`",
])
(out / "report.md").write_text("\n".join(lines), encoding="utf-8")

print(json.dumps({"report": str(out / "report.json"), "summary": summary}, ensure_ascii=False))
PY

if [[ "$JSON_OUTPUT" == true ]]; then
  cat "$OUT_DIR/report.json"
else
  cat "$OUT_DIR/report.md"
fi
