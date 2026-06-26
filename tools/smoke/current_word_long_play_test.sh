#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
DURATION_SEC=90
IOS_DEVICE_ID="${IOS_DEVICE_ID:-}"
ANDROID_DEVICE_ID="${ANDROID_DEVICE_ID:-}"
OUTPUT_DIR_ARG=""
JSON_OUTPUT=false
CLEAR_LOGCAT=true
CLEAR_IOS_LOG=true
BUNDLE_ID="${BUNDLE_ID:-com.sqz.IOSBleFeasibility}"

usage() {
  cat <<'EOF'
Usage: current_word_long_play_test.sh [options]

Options:
  --duration <seconds>       Test window duration. Default: 90.
  --ios-device <id>          iPhone devicectl identifier.
  --android-device <id>      Sony adb serial.
  --output <dir>             Output directory.
  --json                     Print machine-readable summary only.
  --no-clear-logcat          Do not clear Sony logcat before the window.
  --no-clear-ios-log         Do not attempt iOS log clearing; mark by window only.
  -h, --help                 Show help.

Manual prerequisites:
  - iPhone is connected, unlocked, and the app is connected to Sony.
  - Sony is connected by USB and PlayerAgent BLE service is running.
  - QQMusic is playing a track with word timing.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --duration)
      DURATION_SEC="${2:?--duration requires seconds}"
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
    --json)
      JSON_OUTPUT=true
      shift
      ;;
    --no-clear-logcat)
      CLEAR_LOGCAT=false
      shift
      ;;
    --no-clear-ios-log)
      CLEAR_IOS_LOG=false
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
if [[ -n "$OUTPUT_DIR_ARG" ]]; then
  OUT_DIR="$OUTPUT_DIR_ARG"
else
  OUT_DIR="${OUT_DIR:-/tmp/current_word_long_play/$timestamp}"
fi
mkdir -p "$OUT_DIR"

log() {
  echo "[CurrentWordLong] $*" >&2
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
  if ! command -v xcrun >/dev/null 2>&1; then
    echo "xcrun not found" > "$OUT_DIR/ios_detect_reason.txt"
    return 1
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

list_path = Path(sys.argv[1])
reason_path = Path(sys.argv[2])
uuid_re = re.compile(r"[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}")
rows = []
for raw in list_path.read_text(encoding="utf-8", errors="replace").splitlines():
    match = uuid_re.search(raw)
    if not match or "iPhone" not in raw:
        continue
    right = raw[match.end():].strip().lower()
    if right.startswith("connected") or right.startswith("available"):
        rows.append(match.group(0))
if len(rows) == 1:
    print(rows[0])
    sys.exit(0)
if not rows:
    reason_path.write_text("no connected iPhone found\n", encoding="utf-8")
else:
    reason_path.write_text("multiple connected iPhones found; pass --ios-device\n", encoding="utf-8")
sys.exit(1)
PY
  )" || return 1
}

detect_android() {
  if ! ADB_BIN="$(find_adb)"; then
    echo "adb not found" > "$OUT_DIR/android_detect_reason.txt"
    return 1
  fi
  export ADB_BIN
  if [[ -n "$ANDROID_DEVICE_ID" ]]; then
    return 0
  fi
  "$ADB_BIN" devices -l > "$OUT_DIR/adb_devices.txt"
  ANDROID_DEVICE_ID="$(
    python3 - "$OUT_DIR/adb_devices.txt" "$OUT_DIR/android_detect_reason.txt" <<'PY'
import sys
from pathlib import Path

devices_path = Path(sys.argv[1])
reason_path = Path(sys.argv[2])
rows = []
unauthorized = []
for raw in devices_path.read_text(encoding="utf-8", errors="replace").splitlines():
    line = raw.strip()
    if not line or line.startswith("List of devices"):
        continue
    parts = line.split()
    if len(parts) < 2:
        continue
    if parts[1] == "device":
        rows.append(parts[0])
    elif parts[1] == "unauthorized":
        unauthorized.append(parts[0])
if len(rows) == 1:
    print(rows[0])
    sys.exit(0)
if unauthorized and not rows:
    reason_path.write_text("adb device unauthorized\n", encoding="utf-8")
elif not rows:
    reason_path.write_text("no online adb device found\n", encoding="utf-8")
else:
    reason_path.write_text("multiple adb devices found; pass --android-device\n", encoding="utf-8")
sys.exit(1)
PY
  )" || return 1
}

copy_ios_log() {
  local dest="$1"
  xcrun devicectl device copy from \
    --device "$IOS_DEVICE_ID" \
    --domain-type appDataContainer \
    --domain-identifier "$BUNDLE_ID" \
    --source Documents/Logs/ios_ble.log \
    --destination "$dest" \
    >"$OUT_DIR/devicectl_copy_$(basename "$dest").out" \
    2>"$OUT_DIR/devicectl_copy_$(basename "$dest").err"
}

fail_with_report() {
  local reason="$1"
  python3 - "$OUT_DIR" "$reason" <<'PY'
import json
import sys
from pathlib import Path

out_dir = Path(sys.argv[1])
reason = sys.argv[2]
payload = {
    "summary": {
        "result": "FAIL",
        "durationSec": 0,
        "track": "",
        "playing": False,
        "reason": reason,
    },
    "sony": {},
    "ios": {},
    "latency": {},
    "artifacts": {
        "report_md": str(out_dir / "report.md"),
        "report_json": str(out_dir / "report.json"),
    },
}
(out_dir / "report.json").write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
(out_dir / "report.md").write_text(f"# CurrentWord Long Play Test\n\nResult: FAIL\n\n{reason}\n", encoding="utf-8")
print(json.dumps({
    "report_json": str(out_dir / "report.json"),
    "report_md": str(out_dir / "report.md"),
    "summary": payload["summary"],
}, ensure_ascii=False))
PY
  exit 1
}

if ! detect_ios; then
  fail_with_report "$(cat "$OUT_DIR/ios_detect_reason.txt" 2>/dev/null || echo "iPhone unavailable")"
fi
if ! detect_android; then
  fail_with_report "$(cat "$OUT_DIR/android_detect_reason.txt" 2>/dev/null || echo "Android device unavailable")"
fi

log "output=$OUT_DIR"
log "ios=$IOS_DEVICE_ID android=$ANDROID_DEVICE_ID duration=${DURATION_SEC}s"

IOS_BEFORE="$OUT_DIR/ios_before.log"
IOS_AFTER="$OUT_DIR/ios_ble.log"
SONY_LOG="$OUT_DIR/sony_logcat.log"

copy_ios_log "$IOS_BEFORE" || true
if [[ "$CLEAR_LOGCAT" == true ]]; then
  "$ADB_BIN" -s "$ANDROID_DEVICE_ID" logcat -c || true
fi

START_EPOCH_MS="$(python3 - <<'PY'
import time
print(int(time.time() * 1000))
PY
)"
START_ISO="$(date '+%Y-%m-%d %H:%M:%S')"
log "window start=$START_ISO"

if [[ "$CLEAR_IOS_LOG" == true ]]; then
  echo "iOS log clear is not supported by this script; using before/after window marker." \
    > "$OUT_DIR/ios_clear_note.txt"
fi

sleep "$DURATION_SEC"

END_EPOCH_MS="$(python3 - <<'PY'
import time
print(int(time.time() * 1000))
PY
)"
END_ISO="$(date '+%Y-%m-%d %H:%M:%S')"
log "window end=$END_ISO"

copy_ios_log "$IOS_AFTER" || true
"$ADB_BIN" -s "$ANDROID_DEVICE_ID" logcat -v threadtime -d > "$SONY_LOG"

python3 - "$OUT_DIR" "$DURATION_SEC" "$START_EPOCH_MS" "$END_EPOCH_MS" "$IOS_BEFORE" "$IOS_AFTER" "$SONY_LOG" "$CLEAR_LOGCAT" "$IOS_DEVICE_ID" "$ANDROID_DEVICE_ID" <<'PY'
import json
import math
import re
import statistics
import sys
from datetime import datetime
from pathlib import Path

out_dir = Path(sys.argv[1])
duration_sec = int(float(sys.argv[2]))
start_epoch_ms = int(sys.argv[3])
end_epoch_ms = int(sys.argv[4])
ios_before_path = Path(sys.argv[5])
ios_after_path = Path(sys.argv[6])
sony_log_path = Path(sys.argv[7])
clear_logcat = sys.argv[8].lower() == "true"
ios_device = sys.argv[9]
android_device = sys.argv[10]

def read(path: Path) -> str:
    if not path.exists():
        return ""
    return path.read_text(encoding="utf-8", errors="replace")

ios_before = read(ios_before_path)
ios_after = read(ios_after_path)
sony_text = read(sony_log_path)

if ios_before and ios_after.startswith(ios_before):
    ios_window = ios_after[len(ios_before):]
else:
    ios_window = ios_after

ios_ts_re = re.compile(r"^(?P<ts>\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})")
android_ts_re = re.compile(r"^(?P<ts>\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})")

def parse_ios_ms(line: str):
    match = ios_ts_re.search(line)
    if not match:
        return None
    try:
        return int(datetime.strptime(match.group("ts"), "%Y-%m-%d %H:%M:%S.%f").timestamp() * 1000)
    except ValueError:
        return None

def parse_android_ms(line: str):
    match = android_ts_re.search(line)
    if not match:
        return None
    year = datetime.fromtimestamp(start_epoch_ms / 1000).year
    try:
        return int(datetime.strptime(f"{year}-{match.group('ts')}", "%Y-%m-%d %H:%M:%S.%f").timestamp() * 1000)
    except ValueError:
        return None

def filter_by_time(text: str, parser):
    kept = []
    for line in text.splitlines():
        ts = parser(line)
        if ts is None or (start_epoch_ms - 2000 <= ts <= end_epoch_ms + 5000):
            kept.append(line)
    return "\n".join(kept)

if not ios_window.strip():
    ios_window = filter_by_time(ios_after, parse_ios_ms)
if not clear_logcat:
    sony_text = filter_by_time(sony_text, parse_android_ms)

sony_keywords = re.compile(
    r"CurrentWordPush|currentWord|PlaybackDiff|PlaybackState|RuntimeCache|"
    r"AutoPush|notifyQueue|NotifyQueue|main stall|FullLyrics",
    re.I,
)
ios_keywords = re.compile(
    r"currentWord|Lyrics-iOS|playbackState|main stall|execution gap|"
    r"LiveActivity|NowPlayingDiagnostics|Karaoke",
    re.I,
)

sony_filtered = "\n".join(line for line in sony_text.splitlines() if sony_keywords.search(line))
ios_filtered = "\n".join(line for line in ios_window.splitlines() if ios_keywords.search(line))
(out_dir / "sony_current_word_filtered.log").write_text(sony_filtered, encoding="utf-8")
(out_dir / "ios_current_word_filtered.log").write_text(ios_filtered, encoding="utf-8")
(out_dir / "ios_window.log").write_text(ios_window, encoding="utf-8")

def percentile(values, pct):
    if not values:
        return 0
    ordered = sorted(values)
    index = math.ceil((pct / 100.0) * len(ordered)) - 1
    return ordered[max(0, min(index, len(ordered) - 1))]

def avg(values):
    return round(sum(values) / len(values), 2) if values else 0

sony_push_events = re.findall(r"\[CurrentWordPush\]\s+push\b", sony_text)
sony_skip_events = re.findall(r"\[CurrentWordPush\]\s+skip\b", sony_text)
sony_rate_limited = re.findall(r"\[CurrentWordPush\]\s+skip reason=rate limited", sony_text)
metrics_matches = re.findall(
    r"\[CurrentWordPush\]\s+metrics push=(\d+)\s+skip=(\d+)\s+avgIntervalMs=(\d+)\s+lastPushCostMs=(\d+)",
    sony_text,
)
last_metrics = tuple(map(int, metrics_matches[-1])) if metrics_matches else (0, 0, 0, 0)

playback_state_push = len(re.findall(r"\[PlaybackDiff\]\s+push playback", sony_text))
playback_diff_skip = len(re.findall(r"\[PlaybackDiff\]\s+skip identical", sony_text))
playback_diff_push = len(re.findall(r"\[PlaybackDiff\]\s+push playback", sony_text))
track_changed = len(re.findall(r"TrackChanged|track changed", sony_text, flags=re.I))
word_changed = len(re.findall(r"CurrentWordChanged|word changed", sony_text, flags=re.I))
position_jump = len(re.findall(r"PositionJump|position jump", sony_text, flags=re.I))
notify_queue_busy = len(re.findall(r"notifyQueue.*busy|NotifyQueue.*busy|long job", sony_text, flags=re.I))

raw_current_word_re = re.compile(r'\{"type":"currentWord","trackId":"(?P<track>[^"]+)"')
ios_raw_track_ids = [m.group("track") for m in raw_current_word_re.finditer(ios_window)]
ios_raw = len(ios_raw_track_ids)

accepted_re = re.compile(
    r"\[Lyrics-iOS\]\s+currentWord\s+line=(?P<line>-?\d+)\s+word=(?P<word>-?\d+)"
    r".*?latencyMs=(?P<latency>\d+)(?:.*?avgIntervalMs=(?P<avg>\d+))?",
    re.I,
)
accepted_lines = []
accepted_times = []
latencies = []
for line in ios_window.splitlines():
    match = accepted_re.search(line)
    if not match:
        continue
    accepted_lines.append(line)
    ts = parse_ios_ms(line)
    if ts is not None:
        accepted_times.append(ts)
    latencies.append(int(match.group("latency")))

receive_intervals = [
    accepted_times[index] - accepted_times[index - 1]
    for index in range(1, len(accepted_times))
    if accepted_times[index] >= accepted_times[index - 1]
]

stale_re = re.compile(
    r"currentWord discarded stale trackId=(?P<incoming>\S+)\s+current=(?P<current>\S+)",
    re.I,
)
stale_samples = []
for match in stale_re.finditer(ios_window):
    stale_samples.append({
        "incoming": match.group("incoming"),
        "current": match.group("current"),
    })
normalized_accepted = len(re.findall(r"currentWord accepted by normalized trackId", ios_window, flags=re.I))
playback_state_count = len(re.findall(r'\{"type":"playbackState"', ios_window))
main_stall_count = len(re.findall(r"main stall detected", ios_window, flags=re.I))
execution_gap_count = len(re.findall(r"execution gap", ios_window, flags=re.I))
live_activity_updates = len(re.findall(r"\[LiveActivity\].*update", ios_window))

playing = bool(re.search(r'"playing"\s*:\s*true|playing=true', ios_window, flags=re.I))
track = ""
track_info_matches = re.findall(
    r'\{"type":"trackInfo","title":"(?P<title>[^"]*)","artist":"(?P<artist>[^"]*)".*?"album":"(?P<album>[^"]*)"',
    ios_window,
)
if track_info_matches:
    title, artist, album = track_info_matches[-1]
    track = f"{title} / {artist}".strip(" /")
else:
    playback_matches = re.findall(
        r'"title"\s*:\s*"(?P<title>[^"]*)".*?"artist"\s*:\s*"(?P<artist>[^"]*)"',
        ios_window,
        flags=re.S,
    )
    if playback_matches:
        title, artist = playback_matches[-1]
        track = f"{title} / {artist}".strip(" /")
if not track:
    track_info_matches = re.findall(
        r'\{"type":"trackInfo","title":"(?P<title>[^"]*)","artist":"(?P<artist>[^"]*)".*?"album":"(?P<album>[^"]*)"',
        ios_after,
    )
    if track_info_matches:
        title, artist, album = track_info_matches[-1]
        track = f"{title} / {artist}".strip(" /")

sony_push_count = max(len(sony_push_events), last_metrics[0])
sony_skip_count = max(len(sony_skip_events), last_metrics[1])

ios_accepted = len(accepted_lines)
ios_stale = len(stale_samples)
latency_known = bool(latencies)

issues = []
warnings = []
if not playing:
    warnings.append("playing=true not detected in the test window")
if playing and sony_push_count > 0 and ios_accepted == 0:
    issues.append("Sony pushed currentWord but iOS accepted none")
elif not playing and sony_push_count > 0 and ios_accepted == 0:
    warnings.append("Sony currentWord push was seen, but playing=true was not detected")
if ios_stale > 0:
    stale_ratio = ios_stale / max(ios_raw, ios_stale, 1)
    if stale_ratio >= 0.1 or ios_stale >= 3:
        issues.append(f"stale discard ratio is high ({stale_ratio:.2f})")
    else:
        warnings.append(f"stale discard observed ({ios_stale})")
if main_stall_count > 0:
    issues.append(f"main stall detected ({main_stall_count})")
if ios_accepted > 0 and ios_accepted <= playback_state_count:
    warnings.append("playbackState count is not lower than accepted currentWord count")
if not latency_known:
    warnings.append("latency unavailable")
if notify_queue_busy > 0:
    warnings.append(f"notify queue busy indicators found ({notify_queue_busy})")
if live_activity_updates > max(20, ios_accepted // 2) and ios_accepted > 10:
    issues.append("LiveActivity update count is unexpectedly high")

if issues:
    result = "FAIL"
elif ios_accepted == 0:
    result = "WARN"
    warnings.append("no accepted currentWord in the test window")
elif ios_accepted < 10:
    result = "PASS_WITH_LOW_ACTIVITY"
    warnings.append("currentWord accepted, but sample count is low")
elif receive_intervals and avg(receive_intervals) > 120:
    result = "PASS_WITH_LOW_ACTIVITY"
    warnings.append("average interval is above 120ms; track may have slow word changes")
else:
    result = "PASS"

if result == "PASS":
    conclusion = "currentWord lightweight push is active and stable in this window."
elif result == "PASS_WITH_LOW_ACTIVITY":
    conclusion = "currentWord lightweight push is active, but this window has too few or slow word changes for high-rate validation."
elif result == "WARN":
    conclusion = "The test completed, but the playback window was not sufficient to prove currentWord behavior."
else:
    conclusion = "currentWord lightweight push did not meet the long-play acceptance criteria."

summary = {
    "result": result,
    "durationSec": duration_sec,
    "track": track,
    "playing": playing,
    "conclusion": conclusion,
    "warnings": warnings,
    "issues": issues,
}
sony = {
    "currentWordPushCount": sony_push_count,
    "currentWordSkipCount": sony_skip_count,
    "currentWordRateLimitedCount": len(sony_rate_limited),
    "currentWordAvgIntervalMs": last_metrics[2],
    "lastPushCostMs": last_metrics[3],
    "playbackStatePushCount": playback_state_push,
    "playbackDiffSkipCount": playback_diff_skip,
    "playbackDiffPushCount": playback_diff_push,
    "trackChangedCount": track_changed,
    "wordChangedCount": word_changed,
    "positionJumpCount": position_jump,
    "notifyQueueBusyCount": notify_queue_busy,
}
ios = {
    "currentWordRawCount": ios_raw,
    "currentWordAcceptedCount": ios_accepted,
    "currentWordStaleDiscardCount": ios_stale,
    "currentWordNormalizedAcceptedCount": normalized_accepted,
    "playbackStateCount": playback_state_count,
    "mainStallCount": main_stall_count,
    "executionGapCount": execution_gap_count,
    "currentWordAvgReceiveIntervalMs": avg(receive_intervals),
    "currentWordP95ReceiveIntervalMs": percentile(receive_intervals, 95),
    "liveActivityUpdateCount": live_activity_updates,
    "trackIdMismatchSamples": stale_samples[:5],
    "currentWordTrackIdSamples": sorted(set(ios_raw_track_ids))[:5],
}
latency = {
    "known": latency_known,
    "reason": "" if latency_known else "no accepted currentWord latencyMs log in window",
    "latencyMinMs": min(latencies) if latencies else 0,
    "latencyAvgMs": avg(latencies),
    "latencyP95Ms": percentile(latencies, 95),
    "latencyMaxMs": max(latencies) if latencies else 0,
}
artifacts = {
    "report_md": str(out_dir / "report.md"),
    "report_json": str(out_dir / "report.json"),
    "ios_ble_log": str(ios_after_path),
    "ios_window_log": str(out_dir / "ios_window.log"),
    "sony_logcat": str(sony_log_path),
    "ios_filtered": str(out_dir / "ios_current_word_filtered.log"),
    "sony_filtered": str(out_dir / "sony_current_word_filtered.log"),
}
payload = {
    "summary": summary,
    "sony": sony,
    "ios": ios,
    "latency": latency,
    "devices": {
        "ios": ios_device,
        "android": android_device,
    },
    "artifacts": artifacts,
}

report_lines = [
    "# CurrentWord Long Play Test",
    "",
    f"Time: {datetime.fromtimestamp(start_epoch_ms / 1000).strftime('%Y-%m-%d %H:%M:%S')} - "
    f"{datetime.fromtimestamp(end_epoch_ms / 1000).strftime('%Y-%m-%d %H:%M:%S')}",
    f"Duration: {duration_sec}s",
    f"Track: {track or '-'}",
    f"Playing: {str(playing).lower()}",
    "",
    "## Summary",
    "",
    f"Result: {result}",
    "",
    "| Metric | Value |",
    "|---|---:|",
    f"| Sony currentWord push | {sony_push_count} |",
    f"| Sony currentWord skip | {sony_skip_count} |",
    f"| Sony rate limited | {len(sony_rate_limited)} |",
    f"| Sony playbackState push | {playback_state_push} |",
    f"| Sony PlaybackDiff skip | {playback_diff_skip} |",
    f"| iOS currentWord raw | {ios_raw} |",
    f"| iOS currentWord accepted | {ios_accepted} |",
    f"| iOS playbackState | {playback_state_count} |",
    f"| stale discard | {ios_stale} |",
    f"| main stall | {main_stall_count} |",
    f"| execution gap | {execution_gap_count} |",
    f"| avg interval | {ios['currentWordAvgReceiveIntervalMs']} ms |",
    f"| p95 interval | {ios['currentWordP95ReceiveIntervalMs']} ms |",
    f"| latency avg | {latency['latencyAvgMs'] if latency_known else 'unknown'} |",
    f"| latency p95 | {latency['latencyP95Ms'] if latency_known else 'unknown'} |",
    "",
    "## Conclusion",
    "",
    conclusion,
    "",
]
if warnings:
    report_lines.extend(["## Warnings", ""])
    report_lines.extend(f"- {item}" for item in warnings)
    report_lines.append("")
if issues:
    report_lines.extend(["## Issues", ""])
    report_lines.extend(f"- {item}" for item in issues)
    report_lines.append("")
report_lines.extend([
    "## Artifacts",
    "",
    f"- ios_ble.log: `{ios_after_path}`",
    f"- sony_logcat.log: `{sony_log_path}`",
    f"- ios_current_word_filtered.log: `{out_dir / 'ios_current_word_filtered.log'}`",
    f"- sony_current_word_filtered.log: `{out_dir / 'sony_current_word_filtered.log'}`",
    "",
])

(out_dir / "report.json").write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
(out_dir / "report.md").write_text("\n".join(report_lines), encoding="utf-8")
PY

REPORT_JSON="$OUT_DIR/report.json"
REPORT_MD="$OUT_DIR/report.md"

if [[ "$JSON_OUTPUT" == true ]]; then
  cat "$OUT_DIR/report.json" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(json.dumps({"report_json": d["artifacts"]["report_json"], "report_md": d["artifacts"]["report_md"], "summary": d["summary"]}, ensure_ascii=False))'
else
  cat "$REPORT_MD"
fi

result="$(python3 - "$REPORT_JSON" <<'PY'
import json
import sys
from pathlib import Path
print(json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))["summary"]["result"])
PY
)"
if [[ "$result" == "FAIL" ]]; then
  exit 1
fi
exit 0
