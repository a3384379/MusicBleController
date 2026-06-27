#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
DURATION_SEC=150
IOS_DEVICE_ID="${IOS_DEVICE_ID:-}"
ANDROID_DEVICE_ID="${ANDROID_DEVICE_ID:-}"
OUTPUT_DIR_ARG=""
JSON_OUTPUT=false
BUNDLE_ID="${BUNDLE_ID:-com.sqz.IOSBleFeasibility}"

usage() {
  cat <<'EOF'
Usage: source_capability_v30_test.sh [options]

Options:
  --duration <seconds>       Collection window. Default: 150.
  --ios-device <id>          iPhone devicectl identifier.
  --android-device <id>      Sony adb serial.
  --output <dir>             Output directory.
  --json                     Print report summary only.
  -h, --help                 Show help.

Manual prerequisites:
  - iPhone is connected, unlocked, trusted, and can launch the Debug app.
  - Sony is connected by USB and PlayerAgent control service is running.
  - QQMusic is playing. Switch 3-5 tracks during the window for best coverage.

The script launches the iOS app with DEBUG-only --smoke-source-capability,
which periodically requests playbackState, FullLyrics, and HQ AlbumArt.
It does not modify BLE UUIDs or payload protocols.
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
OUT_DIR="${OUTPUT_DIR_ARG:-/tmp/music_ble_capability/$timestamp}"
mkdir -p "$OUT_DIR"

log() {
  echo "[SourceCapability] $*" >&2
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
  local list_file="$OUT_DIR/adb_devices.txt"
  "$ADB_BIN" devices > "$list_file"
  ANDROID_DEVICE_ID="$(
    python3 - "$list_file" "$OUT_DIR/android_detect_reason.txt" <<'PY'
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
    "$SCRIPT_DIR/ios_ble_precheck.sh" --timeout 5 \
    --launch-arg "--smoke-source-capability" --json \
    >"$OUT_DIR/ios_ble_precheck_stdout.json" \
    2>"$OUT_DIR/ios_ble_precheck_stderr.log"
}

precheck_json_object() {
  if [[ -s "$OUT_DIR/ios_ble_precheck.json" ]]; then
    cat "$OUT_DIR/ios_ble_precheck.json"
  else
    printf '{}'
  fi
}


if ! detect_ios; then
  echo "iPhone unavailable: $(cat "$OUT_DIR/ios_detect_reason.txt" 2>/dev/null || true)" >&2
  exit 1
fi
if ! detect_android; then
  echo "Sony adb unavailable: $(cat "$OUT_DIR/android_detect_reason.txt" 2>/dev/null || true)" >&2
  exit 1
fi
log "output=$OUT_DIR"
log "iPhone=$IOS_DEVICE_ID Sony=$ANDROID_DEVICE_ID duration=${DURATION_SEC}s"

START_EPOCH_MS="$(python3 - <<'PY'
import time
print(int(time.time() * 1000))
PY
)"

"$ADB_BIN" -s "$ANDROID_DEVICE_ID" logcat -c || true
copy_ios_log "$OUT_DIR/ios_ble_before.log" || true

if ! run_ios_ble_precheck; then
  python3 - "$OUT_DIR" <<'PYFAIL'
import json, sys
from pathlib import Path
out_dir = Path(sys.argv[1])
precheck_path = out_dir / "ios_ble_precheck.json"
precheck = json.loads(precheck_path.read_text()) if precheck_path.exists() else {"precheckResult":"FAIL","precheckFailReason":"ios_ble_not_connected"}
summary = {
    "result": "FAIL",
    "trackCount": 0,
    "lyricsReadyFast": 0,
    "lyricsReadySlow": 0,
    "lyricsUnavailable": 0,
    "lyricsParseFailed": 0,
    "albumArtReadyFast": 0,
    "albumArtReadySlow": 0,
    "albumArtUnavailable": 0,
    "qrcLookupAvgMs": 0,
    "qrcLookupP95Ms": 0,
    "parseAvgMs": 0,
    "parseP95Ms": 0,
    "albumArtLoadAvgMs": 0,
    "albumArtLoadP95Ms": 0,
    "sourceNotProvidedCount": 0,
    "currentWordStaleBlockedCount": 0,
    "iosStaleDiscardCount": 0,
    "payloadTooLarge": 0,
    "mainStall": 0,
    "durationSec": 0,
    "iosAppLaunched": precheck.get("iosAppLaunched", False),
    "iosBleConnected": precheck.get("iosBleConnected", False),
    "notifySubscribed": precheck.get("notifySubscribed", False),
    "firstPlaybackStateReceived": precheck.get("firstPlaybackStateReceived", False),
    "firstPlaybackStateLatencyMs": precheck.get("firstPlaybackStateLatencyMs", 0),
    "precheckResult": precheck.get("precheckResult", "FAIL"),
    "precheckFailReason": precheck.get("precheckFailReason", "ios_ble_not_connected"),
}
report = {"summary": summary, "precheck": precheck, "issues": ["ios_ble_not_connected"], "warnings": [], "tracks": [], "artifacts": {"report_json": str(out_dir / "report.json"), "report_md": str(out_dir / "report.md")}}
(out_dir / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
(out_dir / "report.md").write_text("# Source Capability V3.0 Report\n\nResult: FAIL\n\nreason=ios_ble_not_connected\n", encoding="utf-8")
print(json.dumps({"report": str(out_dir / "report.json"), "summary": summary}, ensure_ascii=False))
PYFAIL
  exit 1
fi

sleep "$DURATION_SEC"

"$ADB_BIN" -s "$ANDROID_DEVICE_ID" logcat -d > "$OUT_DIR/sony_logcat.log" || true
copy_ios_log "$OUT_DIR/ios_ble.log" || true

python3 - "$OUT_DIR" "$START_EPOCH_MS" "$DURATION_SEC" <<'PY'
import json
import re
import statistics
import sys
from pathlib import Path
from datetime import datetime

out_dir = Path(sys.argv[1])
start_epoch_ms = int(sys.argv[2])
duration_sec = int(sys.argv[3])
sony_path = out_dir / "sony_logcat.log"
ios_path = out_dir / "ios_ble.log"
sony_text = sony_path.read_text(encoding="utf-8", errors="replace") if sony_path.exists() else ""
ios_text = ios_path.read_text(encoding="utf-8", errors="replace") if ios_path.exists() else ""

def parse_ios_ms(line: str):
    m = re.match(r"(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})\.(\d{3})", line)
    if not m:
        return None
    dt = datetime.strptime(m.group(1), "%Y-%m-%d %H:%M:%S")
    return int(dt.timestamp() * 1000) + int(m.group(2))

ios_window_lines = []
for line in ios_text.splitlines():
    ts = parse_ios_ms(line)
    if ts is None or ts >= start_epoch_ms - 1000:
        ios_window_lines.append(line)
ios_window = "\n".join(ios_window_lines)

sony_filtered = "\n".join(
    line for line in sony_text.splitlines()
    if re.search(r"TrackCapability|CurrentWordFence|CurrentWordPush|LyricTrace|LyricsFastPath|FullLyrics|AlbumArt|SongChange|payload|ANR|FATAL|Exception", line, re.I)
)
ios_filtered = "\n".join(
    line for line in ios_window.splitlines()
    if re.search(r"SourceCapabilitySmoke|CurrentWordFence|Lyrics-iOS|LyricsPerf|fullLyrics|AlbumArt|albumArt|playbackState|payload|main stall", line, re.I)
)
(out_dir / "sony_source_capability_filtered.log").write_text(sony_filtered, encoding="utf-8")
(out_dir / "ios_source_capability_filtered.log").write_text(ios_filtered, encoding="utf-8")

tracks = {}
current_key = ""

def track_key(track="", protocol="", title="", artist=""):
    return track or protocol or f"{title}|{artist}"

def ensure(key):
    if key not in tracks:
        tracks[key] = {
            "trackId": key,
            "protocolId": "",
            "title": "",
            "artist": "",
            "lyricsStatus": "SOURCE_NOT_PROVIDED",
            "lyricsReason": "source_app_not_provided",
            "lyricsLatencyMs": 0,
            "albumArtStatus": "SOURCE_NOT_PROVIDED",
            "albumArtReason": "source_app_not_provided",
            "albumArtLatencyMs": 0,
            "qrcLookupCostMs": 0,
            "parseCostMs": 0,
            "lineCount": 0,
            "hasWordTiming": False,
            "albumArtSource": "",
            "albumArtWidth": 0,
            "albumArtHeight": 0,
            "albumArtByteSize": 0,
            "sourceFields": {
                "metadataBitmap": False,
                "metadataIconUri": False,
                "metadataAlbumArtUri": False,
            },
            "missingSource": [],
            "events": [],
        }
    return tracks[key]

def merge_track(old_key, new_key):
    if not old_key or not new_key or old_key == new_key or old_key not in tracks:
        return ensure(new_key or old_key)
    old = tracks.pop(old_key)
    if new_key not in tracks:
        tracks[new_key] = old
        tracks[new_key]["trackId"] = new_key
        return tracks[new_key]
    target = tracks[new_key]
    for field in ("protocolId", "title", "artist", "lyricsReason", "albumArtReason", "albumArtSource"):
        if not target.get(field) and old.get(field):
            target[field] = old[field]
    for field in ("qrcLookupCostMs", "parseCostMs", "lineCount", "albumArtLatencyMs",
                  "lyricsLatencyMs", "albumArtWidth", "albumArtHeight", "albumArtByteSize",
                  "fullLyricsDelayMs", "albumArtDelayMs"):
        target[field] = max(int(target.get(field, 0) or 0), int(old.get(field, 0) or 0))
    for field, default in (("lyricsStatus", "SOURCE_NOT_PROVIDED"), ("albumArtStatus", "SOURCE_NOT_PROVIDED")):
        if target.get(field) == default and old.get(field) != default:
            target[field] = old[field]
    target["hasWordTiming"] = bool(target.get("hasWordTiming")) or bool(old.get("hasWordTiming"))
    for source_key, value in old.get("sourceFields", {}).items():
        target["sourceFields"][source_key] = bool(target["sourceFields"].get(source_key)) or bool(value)
    target["events"].extend(old.get("events", []))
    return target

for line in sony_text.splitlines():
    if "[TrackCapability] start" in line:
        detail = dict(re.findall(r"(\w+)=([^=]+?)(?=\s+\w+=|$)", line))
        key = track_key(
            detail.get("track", "").strip(),
            detail.get("protocolId", "").strip(),
            detail.get("title", "").strip(),
            detail.get("artist", "").strip(),
        )
        if current_key in ("unknown", "summary") and current_key in tracks:
            merge_track(current_key, key)
        current_key = key
        track = ensure(key)
        track["trackId"] = detail.get("track", key).strip()
        track["protocolId"] = detail.get("protocolId", "").strip()
        track["title"] = detail.get("title", "").strip()
        track["artist"] = detail.get("artist", "").strip()
        track["events"].append("start")
    elif "[TrackCapability] media metadata" in line:
        if not current_key:
            continue
        track = ensure(current_key)
        track["sourceFields"]["metadataBitmap"] = "bitmap=true" in line
        track["sourceFields"]["metadataIconUri"] = "iconUri=true" in line
        track["sourceFields"]["metadataAlbumArtUri"] = "albumArtUri=true" in line
        track["events"].append("media_metadata")
    elif "[TrackCapability] lyric qrc lookup done" in line:
        track = ensure(current_key or "unknown")
        success = "success=true" in line
        cost = re.search(r"costMs=(\d+)", line)
        lines = re.search(r"lineCount=(\d+)", line)
        reason = re.search(r"reason=([^\n]+)$", line)
        track["qrcLookupCostMs"] = int(cost.group(1)) if cost else 0
        track["lineCount"] = max(track["lineCount"], int(lines.group(1)) if lines else 0)
        if not success:
            track["lyricsReason"] = reason.group(1).strip() if reason else "qrc_lookup_failed"
        track["events"].append("qrc_lookup_done")
    elif "[TrackCapability] lyric parse done" in line:
        track = ensure(current_key or "unknown")
        success = "success=true" in line
        cost = re.search(r"costMs=(\d+)", line)
        lines = re.search(r"lineCount=(\d+)", line)
        track["parseCostMs"] = int(cost.group(1)) if cost else 0
        track["lineCount"] = max(track["lineCount"], int(lines.group(1)) if lines else 0)
        track["hasWordTiming"] = "hasWordTiming=true" in line
        if not success:
            reason = re.search(r"reason=([^\n]+)$", line)
            track["lyricsStatus"] = "PARSE_FAILED"
            track["lyricsReason"] = reason.group(1).strip() if reason else "parse_failed"
        track["events"].append("lyric_parse_done")
    elif "[TrackCapability] lyric ready latencyMs=" in line:
        track = ensure(current_key or "unknown")
        latency = int(re.search(r"latencyMs=(\d+)", line).group(1))
        track["lyricsLatencyMs"] = latency
        track["lyricsStatus"] = "READY_FAST" if latency <= 1000 else "READY_SLOW"
        track["lyricsReason"] = f"lines={track['lineCount']}"
        track["events"].append("lyric_ready")
    elif "[TrackCapability] fullLyrics sent" in line:
        track = ensure(current_key or "unknown")
        lines = re.search(r"lines=(\d+)", line)
        delay = re.search(r"delayMs=(\d+)", line)
        line_count = int(lines.group(1)) if lines else 0
        track["lineCount"] = max(track["lineCount"], line_count)
        if line_count > 0 and track["lyricsStatus"] == "SOURCE_NOT_PROVIDED":
            track["lyricsStatus"] = "READY_SLOW"
            track["lyricsReason"] = f"fullLyrics lines={line_count}"
        if delay:
            track["fullLyricsDelayMs"] = int(delay.group(1))
        track["events"].append("full_lyrics_sent")
    elif "[TrackCapability] albumArt load done success=true" in line:
        track = ensure(current_key or "unknown")
        source = re.search(r"source=([^ ]+)", line)
        size = re.search(r"size=(\d+)x(\d+)", line)
        bytes_m = re.search(r"bytes=(\d+)", line)
        latency = re.search(r"latencyMs=(\d+)", line)
        latency_ms = int(latency.group(1)) if latency else 0
        track["albumArtStatus"] = "READY_FAST" if latency_ms <= 1000 else "READY_SLOW"
        track["albumArtReason"] = f"source={source.group(1)}" if source else "ready"
        track["albumArtLatencyMs"] = latency_ms
        track["albumArtSource"] = source.group(1) if source else ""
        if size:
            track["albumArtWidth"] = int(size.group(1))
            track["albumArtHeight"] = int(size.group(2))
        track["albumArtByteSize"] = int(bytes_m.group(1)) if bytes_m else 0
        track["events"].append("album_art_ready")
    elif "[TrackCapability] albumArt unavailable" in line:
        track = ensure(current_key or "unknown")
        reason = re.search(r"reason=([^ ]+)", line)
        reason_text = reason.group(1) if reason else "album_art_unavailable"
        track["albumArtStatus"] = "SOURCE_NOT_PROVIDED" if reason_text == "source_app_not_provided" else "LOAD_FAILED"
        track["albumArtReason"] = reason_text
        track["events"].append("album_art_unavailable")
    elif "[TrackCapability] albumArt sent" in line:
        track = ensure(current_key or "unknown")
        delay = re.search(r"delayMs=(\d+)", line)
        if delay:
            track["albumArtDelayMs"] = int(delay.group(1))
        if track["albumArtStatus"] == "SOURCE_NOT_PROVIDED":
            track["albumArtStatus"] = "READY_SLOW"
            track["albumArtReason"] = "albumArt sent"
        track["events"].append("album_art_sent")
    elif "[TrackCapability] summary" in line:
        lyrics = re.search(r"lyrics=([A-Z_]+)", line)
        art = re.search(r"albumArt=([A-Z_]+)", line)
        lyric_lat = re.search(r"lyricLatencyMs=(\d+)", line)
        art_lat = re.search(r"albumArtLatencyMs=(\d+)", line)
        title = re.search(r"title=([^=]+?) artist=", line)
        artist = re.search(r"artist=([^=]+?) lyricReason=", line)
        lyric_reason = re.search(r"lyricReason=([^=]+?) albumArtReason=", line)
        art_reason = re.search(r"albumArtReason=([^=]+?) lyricLatencyMs=", line)
        title_text = title.group(1).strip() if title else ""
        artist_text = artist.group(1).strip() if artist else ""
        summary_key = f"{title_text}|{artist_text}" if title_text or artist_text else "summary"
        key = current_key or summary_key
        if key in ("unknown", "summary") and summary_key not in ("unknown", "summary"):
            track = merge_track(key, summary_key)
            current_key = summary_key
            key = summary_key
        else:
            track = ensure(key)
        if title:
            track["title"] = title_text
        if artist:
            track["artist"] = artist_text
        if lyrics:
            track["lyricsStatus"] = lyrics.group(1)
        if art:
            track["albumArtStatus"] = art.group(1)
        if lyric_reason:
            track["lyricsReason"] = lyric_reason.group(1).strip()
        if art_reason:
            art_reason_text = art_reason.group(1).strip()
            if not (
                art_reason_text == "source_app_not_provided" and
                track.get("albumArtSource")
            ):
                track["albumArtReason"] = art_reason_text
        if lyric_lat:
            track["lyricsLatencyMs"] = int(lyric_lat.group(1))
        if art_lat:
            track["albumArtLatencyMs"] = int(art_lat.group(1))
        track["events"].append("summary")

non_placeholder_keys = [
    key for key in tracks
    if key not in ("unknown", "summary") and not key.startswith("summary")
]
if len(non_placeholder_keys) == 1:
    for placeholder in ("unknown", "summary"):
        if placeholder in tracks:
            merge_track(placeholder, non_placeholder_keys[0])

for track in tracks.values():
    missing = []
    if not any(track["sourceFields"].values()) and track["albumArtStatus"] in ("SOURCE_NOT_PROVIDED", "UNAVAILABLE", "LOAD_FAILED"):
        missing.append("metadata_artwork")
    if track["lyricsStatus"] == "SOURCE_NOT_PROVIDED":
        missing.append("lyrics_source")
    track["missingSource"] = missing

def count_status(kind, status):
    return sum(1 for item in tracks.values() if item.get(kind) == status)

def values(name):
    return [int(item.get(name, 0) or 0) for item in tracks.values() if int(item.get(name, 0) or 0) > 0]

def avg(nums):
    return int(statistics.mean(nums)) if nums else 0

def p95(nums):
    if not nums:
        return 0
    nums = sorted(nums)
    return nums[min(len(nums) - 1, int(len(nums) * 0.95))]

ios_stale = len(re.findall(r"CurrentWordFence\\] stale discard|currentWord discarded stale", ios_window))
sony_stale_blocked = len(re.findall(r"CurrentWordFence\\] skip reason=stale_generation", sony_text))
payload_too_large = len(re.findall(
    r"status notify skipped: payload=\\d+ max=\\d+|Payload maximum size exceeded|\\[BLE\\].*payload too large",
    sony_text + "\\n" + ios_window,
    flags=re.I
))
main_stall = len(re.findall(r"main stall detected", ios_window, flags=re.I))

precheck_path = out_dir / "ios_ble_precheck.json"
precheck = json.loads(precheck_path.read_text()) if precheck_path.exists() else {}
summary = {
    "result": "PASS",
    "trackCount": len(tracks),
    "lyricsReadyFast": count_status("lyricsStatus", "READY_FAST"),
    "lyricsReadySlow": count_status("lyricsStatus", "READY_SLOW"),
    "lyricsUnavailable": count_status("lyricsStatus", "UNAVAILABLE") + count_status("lyricsStatus", "SOURCE_NOT_PROVIDED"),
    "lyricsParseFailed": count_status("lyricsStatus", "PARSE_FAILED"),
    "albumArtReadyFast": count_status("albumArtStatus", "READY_FAST"),
    "albumArtReadySlow": count_status("albumArtStatus", "READY_SLOW"),
    "albumArtUnavailable": count_status("albumArtStatus", "UNAVAILABLE") + count_status("albumArtStatus", "SOURCE_NOT_PROVIDED") + count_status("albumArtStatus", "LOAD_FAILED"),
    "qrcLookupAvgMs": avg(values("qrcLookupCostMs")),
    "qrcLookupP95Ms": p95(values("qrcLookupCostMs")),
    "parseAvgMs": avg(values("parseCostMs")),
    "parseP95Ms": p95(values("parseCostMs")),
    "albumArtLoadAvgMs": avg(values("albumArtLatencyMs")),
    "albumArtLoadP95Ms": p95(values("albumArtLatencyMs")),
    "sourceNotProvidedCount": sum(1 for item in tracks.values() if item["missingSource"]),
    "currentWordStaleBlockedCount": sony_stale_blocked,
    "iosStaleDiscardCount": ios_stale,
    "payloadTooLarge": payload_too_large,
    "mainStall": main_stall,
    "durationSec": duration_sec,
    "iosAppLaunched": precheck.get("iosAppLaunched", False),
    "iosBleConnected": precheck.get("iosBleConnected", False),
    "notifySubscribed": precheck.get("notifySubscribed", False),
    "firstPlaybackStateReceived": precheck.get("firstPlaybackStateReceived", False),
    "firstPlaybackStateLatencyMs": precheck.get("firstPlaybackStateLatencyMs", 0),
    "precheckResult": precheck.get("precheckResult", "UNKNOWN"),
    "precheckFailReason": precheck.get("precheckFailReason", ""),
}
issues = []
warnings = []
if not tracks:
    warnings.append("No TrackCapability logs observed. Ensure Sony build includes V3.0 and QQMusic is playing.")
if payload_too_large:
    issues.append(f"payload too large observed ({payload_too_large})")
if main_stall:
    issues.append(f"main stall observed ({main_stall})")
if ios_stale:
    issues.append(f"iOS currentWord stale discard observed ({ios_stale})")
if issues:
    summary["result"] = "FAIL"
elif warnings:
    summary["result"] = "WARN"

report = {
    "summary": summary,
    "precheck": precheck,
    "issues": issues,
    "warnings": warnings,
    "tracks": list(tracks.values()),
    "artifacts": {
        "report_json": str(out_dir / "report.json"),
        "report_md": str(out_dir / "report.md"),
        "sony_logcat": str(sony_path),
        "ios_ble_log": str(ios_path),
        "sony_filtered": str(out_dir / "sony_source_capability_filtered.log"),
        "ios_filtered": str(out_dir / "ios_source_capability_filtered.log"),
    },
}
(out_dir / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

md = [
    "# Source Capability V3.0 Report",
    "",
    f"- Result: **{summary['result']}**",
    f"- Tracks: {summary['trackCount']}",
    f"- Lyrics READY_FAST / READY_SLOW / UNAVAILABLE / PARSE_FAILED: {summary['lyricsReadyFast']} / {summary['lyricsReadySlow']} / {summary['lyricsUnavailable']} / {summary['lyricsParseFailed']}",
    f"- AlbumArt READY_FAST / READY_SLOW / UNAVAILABLE: {summary['albumArtReadyFast']} / {summary['albumArtReadySlow']} / {summary['albumArtUnavailable']}",
    f"- QRC lookup avg/p95: {summary['qrcLookupAvgMs']} / {summary['qrcLookupP95Ms']} ms",
    f"- Parse avg/p95: {summary['parseAvgMs']} / {summary['parseP95Ms']} ms",
    f"- AlbumArt load avg/p95: {summary['albumArtLoadAvgMs']} / {summary['albumArtLoadP95Ms']} ms",
    f"- Source not provided count: {summary['sourceNotProvidedCount']}",
    f"- Sony stale blocked: {summary['currentWordStaleBlockedCount']}",
    f"- iOS stale discard: {summary['iosStaleDiscardCount']}",
    f"- Payload too large: {summary['payloadTooLarge']}",
    f"- Main stall: {summary['mainStall']}",
    "",
]
if issues:
    md += ["## Issues", ""] + [f"- {item}" for item in issues] + [""]
if warnings:
    md += ["## Warnings", ""] + [f"- {item}" for item in warnings] + [""]
md += [
    "## Tracks",
    "",
    "| title | artist | lyrics | reason | lyric latency | album art | reason | art latency | source missing |",
    "| --- | --- | --- | --- | ---: | --- | --- | ---: | --- |",
]
for item in tracks.values():
    md.append(
        f"| {item['title']} | {item['artist']} | {item['lyricsStatus']} | "
        f"{item['lyricsReason']} | {item['lyricsLatencyMs']} | "
        f"{item['albumArtStatus']} | {item['albumArtReason']} | "
        f"{item['albumArtLatencyMs']} | {', '.join(item['missingSource'])} |"
    )
(out_dir / "report.md").write_text("\n".join(md) + "\n", encoding="utf-8")

print(json.dumps({"report": str(out_dir / "report.json"), "summary": summary}, ensure_ascii=False))
PY

if [[ "$JSON_OUTPUT" == true ]]; then
  cat "$OUT_DIR/report.json"
else
  log "report.md=$OUT_DIR/report.md"
  log "report.json=$OUT_DIR/report.json"
  python3 - <<PY
import json
from pathlib import Path
data=json.loads(Path("$OUT_DIR/report.json").read_text())
print(json.dumps(data["summary"], ensure_ascii=False, indent=2))
PY
fi
