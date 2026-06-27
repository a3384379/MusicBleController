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
BUNDLE_ID="${BUNDLE_ID:-com.sqz.IOSBleFeasibility}"

usage() {
  cat <<'EOF'
Usage: lyric_latency_trace_test.sh [options]

Options:
  --duration <seconds>       Capture window duration. Default: 90.
  --ios-device <id>          iPhone devicectl identifier.
  --android-device <id>      Sony adb serial.
  --output <dir>             Output directory.
  --json                     Print machine-readable summary only.
  --no-clear-logcat          Do not clear Sony logcat before the window.
  -h, --help                 Show help.

Manual prerequisites:
  - iPhone is connected, unlocked, and the app is connected to Sony.
  - Sony is connected by USB and PlayerAgent BLE service is running.
  - During the capture window, switch about 5 songs and let each play briefly.
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
  OUT_DIR="${OUT_DIR:-/tmp/lyric_latency_trace/$timestamp}"
fi
mkdir -p "$OUT_DIR"

log() {
  echo "[LyricLatencyTrace] $*" >&2
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
online = []
unauthorized = []
for raw in devices_path.read_text(encoding="utf-8", errors="replace").splitlines():
    line = raw.strip()
    if not line or line.startswith("List of devices"):
        continue
    parts = line.split()
    if len(parts) < 2:
        continue
    if parts[1] == "device":
        online.append(parts[0])
    elif parts[1] == "unauthorized":
        unauthorized.append(parts[0])
if len(online) == 1:
    print(online[0])
    sys.exit(0)
if unauthorized and not online:
    reason_path.write_text("adb device unauthorized\n", encoding="utf-8")
elif not online:
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

run_ios_ble_precheck() {
  OUT_DIR="$OUT_DIR" IOS_DEVICE_ID="$IOS_DEVICE_ID" BUNDLE_ID="$BUNDLE_ID" \
    "$SCRIPT_DIR/ios_ble_precheck.sh" --timeout 5 --json \
    >"$OUT_DIR/ios_ble_precheck_stdout.json" \
    2>"$OUT_DIR/ios_ble_precheck_stderr.log"
}

write_unavailable_report() {
  local result="$1"
  local reason="$2"
  python3 - "$OUT_DIR" "$result" "$reason" <<'PY'
import json
import sys
from pathlib import Path

out_dir = Path(sys.argv[1])
result = sys.argv[2]
reason = sys.argv[3]
precheck_path = out_dir / "ios_ble_precheck.json"
precheck = json.loads(precheck_path.read_text()) if precheck_path.exists() else {}
payload = {
    "summary": {
        "result": result,
        "reason": reason,
        "songCount": 0,
        "slowCount": 0,
        "iosAppLaunched": precheck.get("iosAppLaunched", False),
        "iosBleConnected": precheck.get("iosBleConnected", False),
        "notifySubscribed": precheck.get("notifySubscribed", False),
        "firstPlaybackStateReceived": precheck.get("firstPlaybackStateReceived", False),
        "firstPlaybackStateLatencyMs": precheck.get("firstPlaybackStateLatencyMs", 0),
        "precheckResult": precheck.get("precheckResult", "UNKNOWN"),
        "precheckFailReason": precheck.get("precheckFailReason", reason if reason == "ios_ble_not_connected" else ""),
    },
    "precheck": precheck,
    "songs": [],
    "artifacts": {
        "report_md": str(out_dir / "report.md"),
        "report_json": str(out_dir / "report.json"),
    },
}
(out_dir / "report.json").write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
(out_dir / "report.md").write_text(
    f"# Lyric Latency Trace\n\nResult: {result}\n\n{reason}\n",
    encoding="utf-8",
)
print(json.dumps({
    "report_json": str(out_dir / "report.json"),
    "report_md": str(out_dir / "report.md"),
    "summary": payload["summary"],
}, ensure_ascii=False))
PY
}

if ! detect_ios; then
  write_unavailable_report "WARN" "$(cat "$OUT_DIR/ios_detect_reason.txt" 2>/dev/null || echo "iPhone unavailable")"
  exit 0
fi
if ! detect_android; then
  write_unavailable_report "WARN" "$(cat "$OUT_DIR/android_detect_reason.txt" 2>/dev/null || echo "Android device unavailable")"
  exit 0
fi
if ! run_ios_ble_precheck; then
  write_unavailable_report "FAIL" "ios_ble_not_connected"
  exit 1
fi

log "output=$OUT_DIR"
log "ios=$IOS_DEVICE_ID android=$ANDROID_DEVICE_ID duration=${DURATION_SEC}s"
log "please switch about 5 songs during the capture window"

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
import sys
from collections import Counter, defaultdict
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
precheck_path = out_dir / "ios_ble_precheck.json"
precheck = json.loads(precheck_path.read_text()) if precheck_path.exists() else {}

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
lyric_trace_re = re.compile(r"\[LyricTrace\]\s+id=(?P<id>\S+)\s+stage=(?P<stage>\S+)\s*(?P<detail>.*)")
ios_trace_re = re.compile(r"\[LyricTrace-iOS\]\s+id=(?P<id>\S+)\s+stage=(?P<stage>\S+)\s*(?P<detail>.*)")

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

sony_trace_lines = [line for line in sony_text.splitlines() if "[LyricTrace]" in line]
ios_trace_lines = [line for line in ios_window.splitlines() if "[LyricTrace-iOS]" in line]
(out_dir / "lyric_trace_sony.log").write_text("\n".join(sony_trace_lines), encoding="utf-8")
(out_dir / "lyric_trace_ios.log").write_text("\n".join(ios_trace_lines), encoding="utf-8")
(out_dir / "ios_window.log").write_text(ios_window, encoding="utf-8")

kv_re = re.compile(r"([A-Za-z][A-Za-z0-9_]+)=((?:\"[^\"]*\")|(?:\S+))")

def parse_detail(detail: str):
    parsed = {}
    for key, value in kv_re.findall(detail):
        parsed[key] = value.strip('"')
    return parsed

def to_int(value, default=0):
    if value is None:
        return default
    try:
        return int(float(str(value)))
    except (TypeError, ValueError):
        return default

def canonical_id(raw_id: str) -> str:
    if "@" in raw_id:
        return raw_id.split("@", 1)[0]
    return raw_id

songs = {}
alias_to_trace = {}

def ensure(trace_id: str):
    if trace_id not in songs:
        songs[trace_id] = {
            "traceId": trace_id,
            "canonicalId": canonical_id(trace_id),
            "songKey": "",
            "title": "",
            "artist": "",
            "sonyStages": [],
            "iosStages": [],
            "cache": "unknown",
            "queueWaitMs": 0,
            "qrcCostMs": 0,
            "runtimeReadyMs": 0,
            "firstLyricMs": 0,
            "fullLyricsMs": 0,
            "fullLyricsRequestMs": 0,
            "fullLyricsReceiveMs": 0,
            "runtimeLines": 0,
            "selectedLines": 0,
            "reason": "",
            "slowStage": "",
            "result": "UNKNOWN",
        }
    return songs[trace_id]

def bind_alias(trace_id: str, *aliases):
    for alias in aliases:
        if alias:
            alias_to_trace[alias] = trace_id
            alias_to_trace[canonical_id(alias)] = trace_id

def resolve_trace(raw_id: str):
    return alias_to_trace.get(raw_id) or alias_to_trace.get(canonical_id(raw_id)) or raw_id

for line in sony_trace_lines:
    match = lyric_trace_re.search(line)
    if not match:
        continue
    raw_id = match.group("id")
    stage = match.group("stage")
    detail = parse_detail(match.group("detail"))
    trace_id = resolve_trace(raw_id)
    song = ensure(trace_id)
    song["sonyStages"].append({"stage": stage, "detail": detail})
    bind_alias(trace_id, raw_id, detail.get("trackId"), detail.get("songKey"))
    if detail.get("songKey"):
        song["songKey"] = detail.get("songKey", "")
    if detail.get("title"):
        song["title"] = detail.get("title", "")
    if detail.get("artist"):
        song["artist"] = detail.get("artist", "")
    if stage == "trackChanged":
        bind_alias(trace_id, detail.get("trackId"), detail.get("songKey"))
    elif stage == "loadStart":
        song["queueWaitMs"] = max(song["queueWaitMs"], to_int(detail.get("queueWaitMs")))
    elif stage in ("parsedCache", "qrcL1", "qrcL2"):
        if detail.get("result") == "hit":
            if stage == "parsedCache":
                song["cache"] = "parsed"
            elif stage == "qrcL1":
                song["cache"] = "qrcL1"
            elif stage == "qrcL2":
                song["cache"] = "qrcL2"
    elif stage == "qrcLookup":
        if "costMs" in detail:
            song["qrcCostMs"] = max(song["qrcCostMs"], to_int(detail.get("costMs")))
        if detail.get("reason"):
            song["reason"] = detail.get("reason", "")
    elif stage == "qrcIndex":
        song["qrcIndexCostMs"] = max(song.get("qrcIndexCostMs", 0), to_int(detail.get("costMs")))
    elif stage == "decrypt":
        song["decryptCostMs"] = max(song.get("decryptCostMs", 0), to_int(detail.get("costMs")))
        if detail.get("result") == "success" and song["cache"] == "unknown":
            song["cache"] = "qrc_parse"
    elif stage == "runtimeCacheUpdated":
        song["runtimeLines"] = to_int(detail.get("lines"))
        song["runtimeApplyCostMs"] = max(song.get("runtimeApplyCostMs", 0), to_int(detail.get("costMs")))
    elif stage == "ready":
        song["runtimeReadyMs"] = max(song["runtimeReadyMs"], to_int(detail.get("totalCostMs")))
        if detail.get("source") and song["cache"] == "unknown":
            song["cache"] = detail.get("source", "").lower()
    elif stage == "failed":
        song["runtimeReadyMs"] = max(song["runtimeReadyMs"], to_int(detail.get("totalCostMs")))
        song["reason"] = detail.get("reason", song.get("reason", ""))
    elif stage == "fullLyricsRequest":
        bind_alias(trace_id, detail.get("trackId"))
    elif stage in ("fullLyricsFromRuntime", "fullLyricsFromLyricManager"):
        song["fullLyricsSource"] = stage
        song["runtimeLines"] = max(song["runtimeLines"], to_int(detail.get("runtimeLines")))
        song["selectedLines"] = max(song["selectedLines"], to_int(detail.get("selectedLines")))
    elif stage == "fullLyricsUnavailable":
        song["reason"] = detail.get("reason", song.get("reason", ""))
    elif stage == "fullLyricsBuildDone":
        song["fullLyricsBuildCostMs"] = max(song.get("fullLyricsBuildCostMs", 0), to_int(detail.get("costMs")))
    elif stage == "fullLyricsSendEnd":
        song["fullLyricsSendCostMs"] = max(song.get("fullLyricsSendCostMs", 0), to_int(detail.get("costMs")))

for line in ios_trace_lines:
    match = ios_trace_re.search(line)
    if not match:
        continue
    raw_id = match.group("id")
    stage = match.group("stage")
    detail = parse_detail(match.group("detail"))
    trace_id = resolve_trace(raw_id)
    if trace_id == raw_id:
        # Prefer an existing Sony trace whose canonical id matches the iOS short track id.
        for existing in list(songs.keys()):
            if canonical_id(existing).startswith(raw_id) or raw_id.startswith(canonical_id(existing)):
                trace_id = existing
                break
    song = ensure(trace_id)
    bind_alias(trace_id, raw_id)
    song["iosStages"].append({"stage": stage, "detail": detail})
    if stage == "trackInfoReceived":
        if detail.get("title"):
            song["title"] = detail.get("title", "")
        if detail.get("artist"):
            song["artist"] = detail.get("artist", "")
    elif stage == "playbackStateLyric":
        if detail.get("lyricEmpty") == "false" and "sinceTrackInfoMs" in detail:
            current = to_int(detail.get("sinceTrackInfoMs"))
            if song["firstLyricMs"] == 0 or current < song["firstLyricMs"]:
                song["firstLyricMs"] = current
    elif stage == "requestFullLyrics":
        song["fullLyricsRequestMs"] = max(song["fullLyricsRequestMs"], to_int(detail.get("sinceTrackInfoMs")))
    elif stage == "fullLyricsStart":
        song["fullLyricsReceiveMs"] = max(song["fullLyricsReceiveMs"], to_int(detail.get("sinceRequestMs")))
    elif stage == "fullLyricsFinal":
        song["fullLyricsFinalRequestMs"] = max(song.get("fullLyricsFinalRequestMs", 0), to_int(detail.get("sinceRequestMs")))
        song["fullLyricsReceiveCostMs"] = max(song.get("fullLyricsReceiveCostMs", 0), to_int(detail.get("receiveCostMs")))
    elif stage == "uiPublished":
        if "sinceTrackInfoMs" in detail:
            current = to_int(detail.get("sinceTrackInfoMs"))
            if detail.get("final") == "true":
                song["fullLyricsMs"] = current
            elif song["fullLyricsMs"] == 0:
                song["fullLyricsMs"] = current

def choose_slow_stage(song):
    reason = (song.get("reason") or "").lower()
    if "waiting qqmusic" in reason:
        return "qqmusic_lazy_cache"
    checks = [
        ("queueWait", song.get("queueWaitMs", 0)),
        ("qrcLookup", song.get("qrcCostMs", 0)),
        ("qrcIndex", song.get("qrcIndexCostMs", 0)),
        ("decrypt", song.get("decryptCostMs", 0)),
        ("runtimeReady", song.get("runtimeReadyMs", 0)),
        ("firstLyric", song.get("firstLyricMs", 0)),
        ("fullLyrics", song.get("fullLyricsMs", 0)),
    ]
    return max(checks, key=lambda item: item[1])[0]

def classify(song):
    cache = song.get("cache", "unknown")
    runtime = song.get("runtimeReadyMs", 0)
    first = song.get("firstLyricMs", 0)
    full = song.get("fullLyricsMs", 0)
    reason = (song.get("reason") or "").lower()
    best_latency = first or full or runtime
    if "waiting qqmusic" in reason:
        return "WARN"
    if cache in ("parsed", "qrcL1", "qrcL2", "lrc", "memory") and best_latency > 3000:
        return "FAIL"
    if best_latency > 10000:
        return "FAIL"
    if cache in ("parsed", "qrcL1", "qrcL2", "lrc", "memory") and best_latency > 500:
        return "WARN"
    if best_latency > 3000:
        return "WARN"
    if best_latency == 0:
        return "WARN"
    return "PASS"

song_rows = []
for trace_id, song in songs.items():
    if not song["sonyStages"] and not song["iosStages"]:
        continue
    if not any(stage["stage"] in ("trackChanged", "trackInfoReceived", "ready", "failed", "uiPublished") for stage in song["sonyStages"] + song["iosStages"]):
        continue
    if song["cache"] == "unknown":
        if song.get("runtimeLines", 0) > 0:
            song["cache"] = "runtime"
        elif song.get("reason"):
            song["cache"] = "miss"
    song["slowStage"] = choose_slow_stage(song)
    song["result"] = classify(song)
    song_rows.append(song)

order = {"FAIL": 0, "WARN": 1, "PASS": 2, "UNKNOWN": 3}
song_rows.sort(key=lambda item: (order.get(item["result"], 9), -(item.get("firstLyricMs") or item.get("fullLyricsMs") or item.get("runtimeReadyMs") or 0)))

result_counts = Counter(row["result"] for row in song_rows)
if result_counts["FAIL"] > 0:
    overall = "FAIL"
elif result_counts["WARN"] > 0 or len(song_rows) < 2:
    overall = "WARN"
else:
    overall = "PASS"

stage_counts = Counter(row["slowStage"] for row in song_rows)

tsv_lines = [
    "song\tcache\tqueueWaitMs\tqrcCostMs\truntimeReadyMs\tfirstLyricMs\tfullLyricsMs\tslowStage\tresult\treason\ttraceId"
]
for row in song_rows:
    title = row.get("title") or row.get("songKey") or row["canonicalId"]
    artist = row.get("artist") or ""
    song_name = f"{title} / {artist}".strip(" /")
    tsv_lines.append(
        "\t".join([
            song_name,
            row.get("cache", "unknown"),
            str(row.get("queueWaitMs", 0)),
            str(row.get("qrcCostMs", 0)),
            str(row.get("runtimeReadyMs", 0)),
            str(row.get("firstLyricMs", 0)),
            str(row.get("fullLyricsMs", 0)),
            row.get("slowStage", ""),
            row.get("result", ""),
            row.get("reason", ""),
            row.get("traceId", ""),
        ])
    )
(out_dir / "lyric_latency_report.tsv").write_text("\n".join(tsv_lines) + "\n", encoding="utf-8")

payload = {
    "summary": {
        "result": overall,
        "durationSec": duration_sec,
        "songCount": len(song_rows),
        "failCount": result_counts["FAIL"],
        "warnCount": result_counts["WARN"],
        "passCount": result_counts["PASS"],
        "slowStageCounts": dict(stage_counts),
        "iosAppLaunched": precheck.get("iosAppLaunched", False),
        "iosBleConnected": precheck.get("iosBleConnected", False),
        "notifySubscribed": precheck.get("notifySubscribed", False),
        "firstPlaybackStateReceived": precheck.get("firstPlaybackStateReceived", False),
        "firstPlaybackStateLatencyMs": precheck.get("firstPlaybackStateLatencyMs", 0),
        "precheckResult": precheck.get("precheckResult", "UNKNOWN"),
        "precheckFailReason": precheck.get("precheckFailReason", ""),
    },
    "precheck": precheck,
    "songs": [
        {
            key: row.get(key)
            for key in [
                "traceId",
                "canonicalId",
                "songKey",
                "title",
                "artist",
                "cache",
                "queueWaitMs",
                "qrcCostMs",
                "qrcIndexCostMs",
                "decryptCostMs",
                "runtimeReadyMs",
                "firstLyricMs",
                "fullLyricsMs",
                "fullLyricsRequestMs",
                "fullLyricsReceiveMs",
                "runtimeLines",
                "selectedLines",
                "slowStage",
                "result",
                "reason",
            ]
        }
        for row in song_rows
    ],
    "devices": {
        "ios": ios_device,
        "android": android_device,
    },
    "artifacts": {
        "report_md": str(out_dir / "report.md"),
        "report_json": str(out_dir / "report.json"),
        "report_tsv": str(out_dir / "lyric_latency_report.tsv"),
        "ios_ble_log": str(ios_after_path),
        "sony_logcat": str(sony_log_path),
        "sony_trace": str(out_dir / "lyric_trace_sony.log"),
        "ios_trace": str(out_dir / "lyric_trace_ios.log"),
    },
}
(out_dir / "report.json").write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")

report = [
    "# Lyric Latency Trace",
    "",
    f"Window: {datetime.fromtimestamp(start_epoch_ms / 1000).strftime('%Y-%m-%d %H:%M:%S')} - "
    f"{datetime.fromtimestamp(end_epoch_ms / 1000).strftime('%Y-%m-%d %H:%M:%S')}",
    f"Duration: {duration_sec}s",
    f"Result: {overall}",
    "",
    "## Summary",
    "",
    f"- songs: {len(song_rows)}",
    f"- PASS/WARN/FAIL: {result_counts['PASS']}/{result_counts['WARN']}/{result_counts['FAIL']}",
    f"- slow stages: {dict(stage_counts)}",
    "",
    "## Songs",
    "",
    "| song | cache | queueWait | qrcCost | runtimeReady | firstLyric | fullLyrics | slowStage | result | reason |",
    "|---|---|---:|---:|---:|---:|---:|---|---|---|",
]
for row in song_rows:
    title = row.get("title") or row.get("songKey") or row["canonicalId"]
    artist = row.get("artist") or ""
    song_name = f"{title} / {artist}".strip(" /").replace("|", "\\|")
    report.append(
        f"| {song_name} | {row.get('cache', 'unknown')} | {row.get('queueWaitMs', 0)} | "
        f"{row.get('qrcCostMs', 0)} | {row.get('runtimeReadyMs', 0)} | "
        f"{row.get('firstLyricMs', 0)} | {row.get('fullLyricsMs', 0)} | "
        f"{row.get('slowStage', '')} | {row.get('result', '')} | "
        f"{(row.get('reason') or '').replace('|', '/')} |"
    )
if not song_rows:
    report.append("| - | - | 0 | 0 | 0 | 0 | 0 | no_trace | WARN | no LyricTrace rows found |")
report.extend([
    "",
    "## Interpretation",
    "",
    "- cache hit <500ms is PASS.",
    "- cache hit 500-3000ms is WARN.",
    "- cache hit >3000ms is FAIL.",
    "- any >10000ms without `waiting qqmusic lyric cache` is FAIL.",
    "",
    "## Artifacts",
    "",
    f"- report.json: `{out_dir / 'report.json'}`",
    f"- lyric_latency_report.tsv: `{out_dir / 'lyric_latency_report.tsv'}`",
    f"- ios_ble.log: `{ios_after_path}`",
    f"- sony_logcat.log: `{sony_log_path}`",
    f"- lyric_trace_sony.log: `{out_dir / 'lyric_trace_sony.log'}`",
    f"- lyric_trace_ios.log: `{out_dir / 'lyric_trace_ios.log'}`",
    "",
])
(out_dir / "report.md").write_text("\n".join(report), encoding="utf-8")
PY

REPORT_JSON="$OUT_DIR/report.json"
REPORT_MD="$OUT_DIR/report.md"

if [[ "$JSON_OUTPUT" == true ]]; then
  python3 - "$REPORT_JSON" <<'PY'
import json
import sys
from pathlib import Path
data = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
print(json.dumps({
    "report_json": data["artifacts"]["report_json"],
    "report_md": data["artifacts"]["report_md"],
    "report_tsv": data["artifacts"]["report_tsv"],
    "summary": data["summary"],
}, ensure_ascii=False))
PY
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
