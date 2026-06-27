#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
IOS_DEVICE_ID="${IOS_DEVICE_ID:-}"
ANDROID_DEVICE_ID="${ANDROID_DEVICE_ID:-}"
OUTPUT_DIR_ARG=""
JSON_OUTPUT=false
BUNDLE_ID="${BUNDLE_ID:-com.sqz.IOSBleFeasibility}"
DURATION_SEC=145

usage() {
  cat <<'EOF'
Usage: track_matrix_v31_test.sh [options]

Options:
  --duration <seconds>       Collection window. Default: 145.
  --ios-device <id>          iPhone devicectl identifier.
  --android-device <id>      Sony adb serial.
  --output <dir>             Output directory.
  --json                     Print report JSON.
  -h, --help                 Show help.

The script launches the DEBUG iOS app with --smoke-track-matrix-v31.
The app samples the current track, requests FullLyrics and AlbumArt,
then sends NEXT every ~10 seconds until 10 tracks are sampled.

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
OUT_DIR="${OUTPUT_DIR_ARG:-/tmp/track_matrix_v31/$timestamp}"
mkdir -p "$OUT_DIR"

log() {
  echo "[TrackMatrixV31] $*" >&2
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
  OUT_DIR="$OUT_DIR" IOS_DEVICE_ID="$IOS_DEVICE_ID" BUNDLE_ID="$BUNDLE_ID" \
    "$SCRIPT_DIR/ios_ble_precheck.sh" --timeout 5 \
    --launch-arg "--smoke-track-matrix-v31" --json \
    >"$OUT_DIR/ios_ble_precheck_stdout.json" \
    2>"$OUT_DIR/ios_ble_precheck_stderr.log"
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
summary = {
    "result": "FAIL",
    "precheckResult": precheck.get("precheckResult", "FAIL"),
    "precheckFailReason": precheck.get("precheckFailReason", reason),
    "iosAppLaunched": precheck.get("iosAppLaunched", False),
    "iosBleConnected": precheck.get("iosBleConnected", False),
    "notifySubscribed": precheck.get("notifySubscribed", False),
    "firstPlaybackStateReceived": precheck.get("firstPlaybackStateReceived", False),
    "firstPlaybackStateLatencyMs": precheck.get("firstPlaybackStateLatencyMs", 0),
    "totalTracks": 0,
    "issues": [reason],
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
(out / "report.md").write_text(f"# Track Matrix V3.1\n\nResult: FAIL\n\nreason={reason}\n", encoding="utf-8")
print(json.dumps({"report": str(out / "report.json"), "summary": summary}, ensure_ascii=False))
PY
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

if ! run_ios_ble_precheck; then
  fail_report "ios_ble_not_connected"
  exit 1
fi

sleep "$DURATION_SEC"

"$ADB_BIN" -s "$ANDROID_DEVICE_ID" logcat -d > "$OUT_DIR/sony_logcat.log" || true
OUT_DIR="$OUT_DIR" IOS_DEVICE_ID="$IOS_DEVICE_ID" BUNDLE_ID="$BUNDLE_ID" \
  "$ROOT_DIR/tools/ios-smoke-tests/ios_collect_logs.sh" ios_ble.log \
  >"$OUT_DIR/ios_collect_logs.out" 2>"$OUT_DIR/ios_collect_logs.err" || true

python3 - "$OUT_DIR" "$START_EPOCH_MS" "$DURATION_SEC" <<'PY'
import json
import re
import statistics
import sys
from datetime import datetime
from pathlib import Path

out_dir = Path(sys.argv[1])
start_epoch_ms = int(sys.argv[2])
duration_sec = int(sys.argv[3])
ios_path = out_dir / "ios_ble.log"
sony_path = out_dir / "sony_logcat.log"
ios_text = ios_path.read_text(encoding="utf-8", errors="replace") if ios_path.exists() else ""
sony_text = sony_path.read_text(encoding="utf-8", errors="replace") if sony_path.exists() else ""

def parse_ios_ms(line: str):
    match = re.match(r"(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})\.(\d{3})", line)
    if not match:
        return None
    dt = datetime.strptime(match.group(1), "%Y-%m-%d %H:%M:%S")
    return int(dt.timestamp() * 1000) + int(match.group(2))

def parse_sony_ms(line: str):
    match = re.match(r"(\d{2})-(\d{2}) (\d{2}:\d{2}:\d{2})\.(\d{3})", line)
    if not match:
        return None
    year = datetime.fromtimestamp(start_epoch_ms / 1000).year
    dt = datetime.strptime(f"{year}-{match.group(1)}-{match.group(2)} {match.group(3)}", "%Y-%m-%d %H:%M:%S")
    return int(dt.timestamp() * 1000) + int(match.group(4))

def int_or_zero(value):
    try:
        return int(value)
    except Exception:
        return 0

def percentile(nums, pct):
    nums = sorted([n for n in nums if n and n > 0])
    if not nums:
        return 0
    return nums[min(len(nums) - 1, int(len(nums) * pct))]

def same_track(incoming: str, current: str):
    if not incoming or not current:
        return False
    return incoming == current or incoming.startswith(current) or current.startswith(incoming)

ios_lines = []
for line in ios_text.splitlines():
    ts = parse_ios_ms(line)
    if ts is None or ts >= start_epoch_ms - 1000:
        ios_lines.append((ts or 0, line))

sony_lines = []
for line in sony_text.splitlines():
    ts = parse_sony_ms(line)
    if ts is None or ts >= start_epoch_ms - 1000:
        sony_lines.append((ts or 0, line))

tracks = []
track_by_id = {}
for ts, line in ios_lines:
    if "[TrackMatrixV31] sampleStart" not in line:
        continue
    index = re.search(r"index=(\d+)", line)
    time_ms = re.search(r"timeMs=(\d+)", line)
    track_id = re.search(r"trackId=([^ ]*)", line)
    title = re.search(r"title=(.*?) artist=", line)
    artist = re.search(r"artist=(.*?) position=", line)
    position = re.search(r"position=(\d+)", line)
    duration = re.search(r"duration=(\d+)", line)
    track = {
        "index": int_or_zero(index.group(1) if index else 0),
        "trackId": (track_id.group(1) if track_id else "").strip(),
        "protocolId": "",
        "title": (title.group(1) if title else "").strip(),
        "artist": (artist.group(1) if artist else "").strip(),
        "album": "",
        "durationMs": int_or_zero(duration.group(1) if duration else 0),
        "trackChangedAt": int_or_zero(time_ms.group(1) if time_ms else ts),
        "sampleLogAt": ts,
        "getFullLyricsRequestedAt": 0,
        "albumArtRequestedAt": 0,
        "qrcLookupStartedAt": 0,
        "qrcLookupCostMs": 0,
        "qrcCooldownBlocked": False,
        "qrcCooldownRetryCount": 0,
        "parsedLyricsSuccess": False,
        "parsedLyricsFailedReason": "",
        "lineCount": 0,
        "hasWordTiming": False,
        "lyricsReadyAtSony": 0,
        "fullLyricsSentAtSony": 0,
        "fullLyricsReceivedAtIOS": 0,
        "firstCurrentWordAtIOS": 0,
        "trackChangedToLyricsReadyMs": 0,
        "trackChangedToFullLyricsIOSMs": 0,
        "trackChangedToFirstCurrentWordMs": 0,
        "lyricsWithin2s": False,
        "lyricsStatus": "SOURCE_NOT_PROVIDED",
        "albumArtSource": "",
        "metadataHasBitmap": False,
        "metadataHasAlbumArtUri": False,
        "notificationLargeIconAvailable": False,
        "albumArtCacheHit": False,
        "albumArtOfferSentAtSony": 0,
        "albumArtOfferReceivedAtIOS": 0,
        "albumArtPreviewReceivedAtIOS": 0,
        "albumArtHqReceivedAtIOS": 0,
        "albumArtWidth": 0,
        "albumArtHeight": 0,
        "albumArtByteSize": 0,
        "trackChangedToAlbumArtIOSMs": 0,
        "albumArtWithin2s": False,
        "albumArtStatus": "SOURCE_NOT_PROVIDED",
        "playbackStateReceivedCount": 0,
        "currentWordRaw": 0,
        "currentWordAccepted": 0,
        "staleDiscard": 0,
        "payloadTooLarge": 0,
        "mainStall": 0,
        "trackNotChanged": False,
        "failureReasons": [],
    }
    tracks.append(track)
    if track["trackId"]:
        track_by_id[track["trackId"]] = track

if not tracks:
    tracks = []

tracks.sort(key=lambda item: item["trackChangedAt"])

def track_for_time(ts):
    if not tracks:
        return None
    chosen = None
    for track in tracks:
        if ts >= track["trackChangedAt"] - 500:
            chosen = track
        else:
            break
    return chosen

def track_for_id(track_id, ts=0):
    matches = [track for track in tracks if same_track(track_id, track["trackId"])]
    if not matches:
        return track_for_time(ts)
    if ts:
        before = [track for track in matches if track["trackChangedAt"] <= ts + 500]
        if before:
            return max(before, key=lambda item: item["trackChangedAt"])
    return matches[-1]

for ts, line in ios_lines:
    track = track_for_time(ts)
    if "[TrackMatrixV31] requestFullLyrics" in line:
        tid = re.search(r"trackId=([^ ]*)", line)
        time_ms = re.search(r"timeMs=(\d+)", line)
        target = track_for_id(tid.group(1) if tid else "", ts)
        if target:
            target["getFullLyricsRequestedAt"] = int_or_zero(time_ms.group(1) if time_ms else ts)
    elif "[TrackMatrixV31] requestAlbumArt" in line:
        tid = re.search(r"trackId=([^ ]*)", line)
        time_ms = re.search(r"timeMs=(\d+)", line)
        target = track_for_id(tid.group(1) if tid else "", ts)
        if target:
            target["albumArtRequestedAt"] = int_or_zero(time_ms.group(1) if time_ms else ts)
    elif "[TrackMatrixV31] track_not_changed" in line:
        if track:
            track["trackNotChanged"] = True
            track["failureReasons"].append("track_not_changed")

    if '"type":"playbackState"' in line:
        if track:
            track["playbackStateReceivedCount"] += 1
    if '"type":"currentWord"' in line:
        match = re.search(r'"trackId":"([^"]+)"', line)
        target = track_for_id(match.group(1) if match else "", ts)
        if target:
            target["currentWordRaw"] += 1
    if "[Lyrics-iOS] currentWord line=" in line:
        if track:
            track["currentWordAccepted"] += 1
            if not track["firstCurrentWordAtIOS"]:
                track["firstCurrentWordAtIOS"] = ts
    if "currentWord discarded stale" in line or "[CurrentWordFence] stale discard" in line:
        if track:
            track["staleDiscard"] += 1
    if "main stall detected" in line.lower():
        if track:
            track["mainStall"] += 1
    if "payload too large" in line.lower() or "Payload maximum size exceeded" in line:
        if track:
            track["payloadTooLarge"] += 1

    if "[LyricTrace-iOS]" in line and "stage=fullLyricsFinal" in line:
        match = re.search(r"id=([^ ]+)", line)
        lines = re.search(r"lines=(\d+)", line)
        target = track_for_id(match.group(1) if match else "", ts)
        if target and not target["fullLyricsReceivedAtIOS"]:
            target["fullLyricsReceivedAtIOS"] = ts
            target["lineCount"] = max(target["lineCount"], int_or_zero(lines.group(1) if lines else 0))
    if "[FullLyrics] end count=" in line:
        if track and not track["fullLyricsReceivedAtIOS"]:
            count = re.search(r"count=(\d+)", line)
            track["fullLyricsReceivedAtIOS"] = ts
            track["lineCount"] = max(track["lineCount"], int_or_zero(count.group(1) if count else 0))

    if "[AlbumArt] offer id=" in line or "[AlbumArtOffer] id=" in line:
        match = re.search(r"id=([^ ]+)", line)
        target = track_for_id(match.group(1) if match else "", ts)
        if target and not target["albumArtOfferReceivedAtIOS"]:
            target["albumArtOfferReceivedAtIOS"] = ts
    if "[AlbumArtCache] display quality=" in line:
        quality = re.search(r"quality=([^ ]+)", line)
        match = re.search(r"id=([^, ]+)", line)
        target = track_for_id(match.group(1) if match else "", ts)
        if target:
            q = quality.group(1) if quality else ""
            if q in ("hq", "enhanced", "hqFallback") and not target["albumArtHqReceivedAtIOS"]:
                target["albumArtHqReceivedAtIOS"] = ts
            elif not target["albumArtPreviewReceivedAtIOS"]:
                target["albumArtPreviewReceivedAtIOS"] = ts
    if '"type":"albumArtBinaryEnd"' in line:
        match = re.search(r'"id":"([^"]+)"', line)
        quality = re.search(r'"quality":"([^"]+)"', line)
        target = track_for_id(match.group(1) if match else "", ts)
        if target:
            if (quality.group(1) if quality else "") == "preview":
                target["albumArtPreviewReceivedAtIOS"] = target["albumArtPreviewReceivedAtIOS"] or ts
            else:
                target["albumArtHqReceivedAtIOS"] = target["albumArtHqReceivedAtIOS"] or ts

for ts, line in sony_lines:
    # Prefer explicit TrackCapability and fast-path lines, but keep attribution conservative.
    target = None
    track_match = re.search(r"(?:track|id|protocolId)=([0-9a-f]{8,})", line)
    if track_match:
        target = track_for_id(track_match.group(1), ts)
    if target is None:
        for candidate in tracks:
            if candidate["title"] and candidate["title"] in line:
                target = candidate
                break
    if target is None:
        target = track_for_time(ts)
    if target is None:
        continue

    if "[TrackCapability] media metadata" in line:
        target["metadataHasBitmap"] = "bitmap=true" in line
        target["metadataHasAlbumArtUri"] = "albumArtUri=true" in line
    elif "[TrackCapability] lyric qrc lookup start" in line:
        target["qrcLookupStartedAt"] = target["qrcLookupStartedAt"] or ts
    elif "[TrackCapability] lyric qrc lookup done" in line:
        cost = re.search(r"costMs=(\d+)", line)
        count = re.search(r"lineCount=(\d+)", line)
        reason = re.search(r"reason=([^\n]+)$", line)
        success = "success=true" in line
        target["qrcLookupCostMs"] = int_or_zero(cost.group(1) if cost else 0)
        target["lineCount"] = max(target["lineCount"], int_or_zero(count.group(1) if count else 0))
        if not success:
            reason_text = (reason.group(1).strip() if reason else "qrc_lookup_failed")
            if "cooldown" in reason_text:
                target["qrcCooldownBlocked"] = True
                target["qrcCooldownRetryCount"] += 1
            target["parsedLyricsFailedReason"] = reason_text
    elif "[TrackCapability] lyric parse done" in line:
        success = "success=true" in line
        count = re.search(r"lineCount=(\d+)", line)
        reason = re.search(r"reason=([^\n]+)$", line)
        target["parsedLyricsSuccess"] = success
        target["lineCount"] = max(target["lineCount"], int_or_zero(count.group(1) if count else 0))
        target["hasWordTiming"] = "hasWordTiming=true" in line
        if not success:
            target["parsedLyricsFailedReason"] = reason.group(1).strip() if reason else "parse_failed"
    elif "[TrackCapability] lyric ready latencyMs=" in line:
        latency = re.search(r"latencyMs=(\d+)", line)
        target["lyricsReadyAtSony"] = ts
        target["trackChangedToLyricsReadyMs"] = int_or_zero(latency.group(1) if latency else ts - target["trackChangedAt"])
        target["parsedLyricsSuccess"] = True
    elif "[TrackCapability] fullLyrics sent" in line:
        count = re.search(r"lines=(\d+)", line)
        target["fullLyricsSentAtSony"] = target["fullLyricsSentAtSony"] or ts
        target["lineCount"] = max(target["lineCount"], int_or_zero(count.group(1) if count else 0))
    elif "[TrackCapability] albumArt load done success=true" in line:
        source = re.search(r"source=([^ ]+)", line)
        size = re.search(r"size=(\d+)x(\d+)", line)
        bytes_m = re.search(r"bytes=(\d+)", line)
        target["albumArtSource"] = source.group(1) if source else target["albumArtSource"]
        target["notificationLargeIconAvailable"] = target["albumArtSource"] == "notificationLargeIcon"
        if size:
            target["albumArtWidth"] = int_or_zero(size.group(1))
            target["albumArtHeight"] = int_or_zero(size.group(2))
        target["albumArtByteSize"] = int_or_zero(bytes_m.group(1) if bytes_m else target["albumArtByteSize"])
    elif "[AlbumArtFastPath] cache hit" in line:
        target["albumArtCacheHit"] = True
        source = re.search(r"source=([^ ]+)", line)
        size = re.search(r"size=(\d+)x(\d+)", line)
        if source:
            target["albumArtSource"] = source.group(1)
        if size:
            target["albumArtWidth"] = int_or_zero(size.group(1))
            target["albumArtHeight"] = int_or_zero(size.group(2))
    elif "[ReconnectSync] send albumArtOffer" in line or "albumArtOffer" in line:
        target["albumArtOfferSentAtSony"] = target["albumArtOfferSentAtSony"] or ts
    elif "[TrackCapability] summary" in line:
        lyrics = re.search(r"lyrics=([A-Z_]+)", line)
        art = re.search(r"albumArt=([A-Z_]+)", line)
        lyric_reason = re.search(r"lyricReason=([^=]+?) albumArtReason=", line)
        art_reason = re.search(r"albumArtReason=([^=]+?) lyricLatencyMs=", line)
        if lyrics:
            target["lyricsStatus"] = lyrics.group(1)
        if art:
            target["albumArtStatus"] = art.group(1)
        if lyric_reason:
            target["parsedLyricsFailedReason"] = lyric_reason.group(1).strip()
        if art_reason and not target["albumArtSource"]:
            reason = art_reason.group(1).strip()
            if reason.startswith("source="):
                target["albumArtSource"] = reason.split("=", 1)[1]

for track in tracks:
    if track["firstCurrentWordAtIOS"]:
        track["trackChangedToFirstCurrentWordMs"] = max(0, track["firstCurrentWordAtIOS"] - track["trackChangedAt"])
    if track["fullLyricsReceivedAtIOS"]:
        track["trackChangedToFullLyricsIOSMs"] = max(0, track["fullLyricsReceivedAtIOS"] - track["trackChangedAt"])
    if track["lyricsReadyAtSony"] and not track["trackChangedToLyricsReadyMs"]:
        track["trackChangedToLyricsReadyMs"] = max(0, track["lyricsReadyAtSony"] - track["trackChangedAt"])

    art_ios_times = [value for value in (
        track["albumArtHqReceivedAtIOS"],
        track["albumArtPreviewReceivedAtIOS"],
        track["albumArtOfferReceivedAtIOS"],
    ) if value]
    if art_ios_times:
        track["trackChangedToAlbumArtIOSMs"] = max(0, min(art_ios_times) - track["trackChangedAt"])

    if track["trackChangedToFullLyricsIOSMs"] and track["lineCount"] > 0:
        track["lyricsStatus"] = "READY_FAST" if track["trackChangedToFullLyricsIOSMs"] <= 2000 else "READY_SLOW"
    elif track["qrcCooldownBlocked"]:
        track["lyricsStatus"] = "COOLDOWN_BLOCKED"
    elif track["parsedLyricsFailedReason"]:
        track["lyricsStatus"] = "PARSE_FAILED"
    elif track["lineCount"] > 0:
        track["lyricsStatus"] = "READY_SLOW"
    else:
        track["lyricsStatus"] = track["lyricsStatus"] if track["lyricsStatus"] != "SOURCE_NOT_PROVIDED" else "SOURCE_NOT_PROVIDED"

    if art_ios_times:
        track["albumArtStatus"] = "READY_FAST" if track["trackChangedToAlbumArtIOSMs"] <= 2000 else "READY_SLOW"
    elif track["albumArtSource"]:
        track["albumArtStatus"] = "READY_SLOW"
    elif track["metadataHasBitmap"] or track["metadataHasAlbumArtUri"]:
        track["albumArtStatus"] = "LOAD_FAILED"
    else:
        track["albumArtStatus"] = "SOURCE_NOT_PROVIDED"

    track["lyricsWithin2s"] = track["lyricsStatus"] == "READY_FAST"
    track["albumArtWithin2s"] = track["albumArtStatus"] == "READY_FAST"

    if track["qrcCooldownBlocked"]:
        track["failureReasons"].append("qrc cooldown retry pending")
    if track["parsedLyricsFailedReason"] and track["parsedLyricsFailedReason"] not in track["failureReasons"]:
        track["failureReasons"].append(track["parsedLyricsFailedReason"])
    if track["albumArtStatus"] == "SOURCE_NOT_PROVIDED":
        track["failureReasons"].append("album art metadata missing")

def count(predicate):
    return sum(1 for item in tracks if predicate(item))

lyrics_ready_lat = [t["trackChangedToLyricsReadyMs"] for t in tracks if t["trackChangedToLyricsReadyMs"]]
full_ios_lat = [t["trackChangedToFullLyricsIOSMs"] for t in tracks if t["trackChangedToFullLyricsIOSMs"]]
word_lat = [t["trackChangedToFirstCurrentWordMs"] for t in tracks if t["trackChangedToFirstCurrentWordMs"]]
art_lat = [t["trackChangedToAlbumArtIOSMs"] for t in tracks if t["trackChangedToAlbumArtIOSMs"]]

reason_counts = {}
for track in tracks:
    for reason in track["failureReasons"]:
        reason_counts[reason] = reason_counts.get(reason, 0) + 1

total_stale = sum(t["staleDiscard"] for t in tracks)
total_payload = sum(t["payloadTooLarge"] for t in tracks)
total_stall = sum(t["mainStall"] for t in tracks)

precheck_path = out_dir / "ios_ble_precheck.json"
precheck = json.loads(precheck_path.read_text()) if precheck_path.exists() else {}

summary = {
    "result": "PASS",
    "totalTracks": len(tracks),
    "lyricsWithin2sCount": count(lambda t: t["lyricsWithin2s"]),
    "lyricsSlowCount": count(lambda t: t["lyricsStatus"] == "READY_SLOW"),
    "lyricsFailedCount": count(lambda t: t["lyricsStatus"] in ("PARSE_FAILED", "SOURCE_NOT_PROVIDED", "COOLDOWN_BLOCKED")),
    "albumArtWithin2sCount": count(lambda t: t["albumArtWithin2s"]),
    "albumArtSlowCount": count(lambda t: t["albumArtStatus"] == "READY_SLOW"),
    "albumArtFailedCount": count(lambda t: t["albumArtStatus"] in ("SOURCE_NOT_PROVIDED", "LOAD_FAILED")),
    "trackChangedToLyricsReadyAvgMs": int(statistics.mean(lyrics_ready_lat)) if lyrics_ready_lat else 0,
    "trackChangedToLyricsReadyP95Ms": percentile(lyrics_ready_lat, 0.95),
    "trackChangedToFullLyricsIOSAvgMs": int(statistics.mean(full_ios_lat)) if full_ios_lat else 0,
    "trackChangedToFullLyricsIOSP95Ms": percentile(full_ios_lat, 0.95),
    "trackChangedToFirstCurrentWordAvgMs": int(statistics.mean(word_lat)) if word_lat else 0,
    "trackChangedToFirstCurrentWordP95Ms": percentile(word_lat, 0.95),
    "trackChangedToAlbumArtIOSAvgMs": int(statistics.mean(art_lat)) if art_lat else 0,
    "trackChangedToAlbumArtIOSP95Ms": percentile(art_lat, 0.95),
    "failureReasons": reason_counts,
    "staleDiscard": total_stale,
    "payloadTooLarge": total_payload,
    "mainStall": total_stall,
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
if len(tracks) < 10:
    warnings.append(f"only {len(tracks)} tracks sampled")
if total_stale:
    issues.append(f"stale discard observed: {total_stale}")
if total_payload:
    issues.append(f"payload too large observed: {total_payload}")
if total_stall:
    issues.append(f"main stall observed: {total_stall}")
if any(t["trackNotChanged"] for t in tracks):
    warnings.append("track_not_changed observed")
if issues:
    summary["result"] = "FAIL"
elif warnings:
    summary["result"] = "WARN"

sony_filtered = "\n".join(
    line for _, line in sony_lines
    if re.search(r"TrackCapability|LyricsCooldown|LyricsFastPath|AlbumArtFastPath|AlbumArt|SongChange|CurrentWordFence|payload|ANR|FATAL|Exception", line, re.I)
)
ios_filtered = "\n".join(
    line for _, line in ios_lines
    if re.search(r"TrackMatrixV31|LyricTrace-iOS|FullLyrics|Lyrics-iOS|AlbumArt|albumArt|playbackState|currentWord|payload|main stall", line, re.I)
)
(out_dir / "sony_track_matrix_filtered.log").write_text(sony_filtered, encoding="utf-8")
(out_dir / "ios_track_matrix_filtered.log").write_text(ios_filtered, encoding="utf-8")

report = {
    "summary": summary,
    "precheck": precheck,
    "issues": issues,
    "warnings": warnings,
    "tracks": tracks,
    "artifacts": {
        "report_json": str(out_dir / "report.json"),
        "report_md": str(out_dir / "report.md"),
        "ios_ble_log": str(ios_path),
        "sony_logcat": str(sony_path),
        "ios_filtered": str(out_dir / "ios_track_matrix_filtered.log"),
        "sony_filtered": str(out_dir / "sony_track_matrix_filtered.log"),
    },
}
(out_dir / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

md = [
    "# Track Matrix V3.1 Report",
    "",
    f"- Result: **{summary['result']}**",
    f"- Total tracks: {summary['totalTracks']}",
    f"- Lyrics <=2s / slow / failed: {summary['lyricsWithin2sCount']} / {summary['lyricsSlowCount']} / {summary['lyricsFailedCount']}",
    f"- AlbumArt <=2s / slow / failed: {summary['albumArtWithin2sCount']} / {summary['albumArtSlowCount']} / {summary['albumArtFailedCount']}",
    f"- FullLyrics iOS avg/p95: {summary['trackChangedToFullLyricsIOSAvgMs']} / {summary['trackChangedToFullLyricsIOSP95Ms']} ms",
    f"- AlbumArt iOS avg/p95: {summary['trackChangedToAlbumArtIOSAvgMs']} / {summary['trackChangedToAlbumArtIOSP95Ms']} ms",
    f"- Stale / PayloadTooLarge / MainStall: {summary['staleDiscard']} / {summary['payloadTooLarge']} / {summary['mainStall']}",
    "",
    "## Failure Reasons",
    "",
]
if reason_counts:
    md.extend([f"- {reason}: {count}" for reason, count in sorted(reason_counts.items())])
else:
    md.append("- none")
md += [
    "",
    "## Tracks",
    "",
    "| # | title | artist | lyrics | fullLyrics iOS ms | firstWord ms | albumArt | albumArt iOS ms | reason |",
    "| ---: | --- | --- | --- | ---: | ---: | --- | ---: | --- |",
]
for track in tracks:
    md.append(
        f"| {track['index']} | {track['title']} | {track['artist']} | "
        f"{track['lyricsStatus']} | {track['trackChangedToFullLyricsIOSMs']} | "
        f"{track['trackChangedToFirstCurrentWordMs']} | {track['albumArtStatus']} | "
        f"{track['trackChangedToAlbumArtIOSMs']} | {', '.join(track['failureReasons'])} |"
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
data = json.loads(Path("$OUT_DIR/report.json").read_text())
print(json.dumps(data["summary"], ensure_ascii=False, indent=2))
PY
fi
