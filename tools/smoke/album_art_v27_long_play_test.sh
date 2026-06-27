#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
DURATION=120
OUTPUT_DIR=""
JSON_MODE=0
IOS_DEVICE=""
ANDROID_DEVICE=""
EXTRA_ARGS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --duration)
      DURATION="${2:-120}"
      shift 2
      ;;
    --output)
      OUTPUT_DIR="${2:-}"
      shift 2
      ;;
    --ios-device)
      IOS_DEVICE="${2:-}"
      shift 2
      ;;
    --android-device)
      ANDROID_DEVICE="${2:-}"
      shift 2
      ;;
    --json)
      JSON_MODE=1
      EXTRA_ARGS+=("--json")
      shift
      ;;
    *)
      EXTRA_ARGS+=("$1")
      shift
      ;;
  esac
done

if [[ -z "$OUTPUT_DIR" ]]; then
  OUTPUT_DIR="/tmp/album_art_v27/$(date +%Y%m%d_%H%M%S)"
fi
mkdir -p "$OUTPUT_DIR"

BASE_DIR="$OUTPUT_DIR/base"
BASE_ARGS=("--duration" "$DURATION" "--output" "$BASE_DIR")
if [[ -n "$IOS_DEVICE" ]]; then
  BASE_ARGS+=("--ios-device" "$IOS_DEVICE")
fi
if [[ -n "$ANDROID_DEVICE" ]]; then
  BASE_ARGS+=("--android-device" "$ANDROID_DEVICE")
fi
BASE_ARGS+=("${EXTRA_ARGS[@]}")

"$ROOT_DIR/tools/smoke/current_word_long_play_test.sh" "${BASE_ARGS[@]}" || true

python3 - "$OUTPUT_DIR" "$BASE_DIR" "$DURATION" <<'PY'
import json
import re
import sys
from pathlib import Path

out_dir = Path(sys.argv[1])
base_dir = Path(sys.argv[2])
duration = int(sys.argv[3])

base_report_path = base_dir / "report.json"
base_report = json.loads(base_report_path.read_text()) if base_report_path.exists() else {}
sony_log = base_dir / "sony_logcat.log"
ios_log = base_dir / "ios_ble.log"
sony_text = sony_log.read_text(errors="ignore") if sony_log.exists() else ""
ios_text = ios_log.read_text(errors="ignore") if ios_log.exists() else ""

def count(pattern, text):
    return len(re.findall(pattern, text))

def values(pattern, text):
    items = []
    for match in re.finditer(pattern, text):
        try:
            items.append(int(match.group(1)))
        except Exception:
            pass
    return items

def avg(items):
    return round(sum(items) / len(items), 2) if items else 0

def p95(items):
    if not items:
        return 0
    ordered = sorted(items)
    return ordered[int((len(ordered) - 1) * 0.95)]

base_ios = base_report.get("ios", {})
base_sony = base_report.get("sony", {})
base_summary = base_report.get("summary", {})
precheck = base_report.get("precheck", {})

preview_ready_costs = values(r"\[AlbumArtFastPath\] preview ready costMs=(\d+)", sony_text)
hq_ready_costs = values(r"\[AlbumArtFastPath\] hq ready costMs=(\d+)", sony_text)

track_changed = count(r"\[BLE-A\]\[AutoPush\] song changed", sony_text)
fast_start = count(r"\[AlbumArtFastPath\] track_changed start", sony_text)
cache_hit = count(r"\[AlbumArtCache\] hit", sony_text)
cache_miss = count(r"\[AlbumArtCache\] miss", sony_text)
load_start = count(r"\[AlbumArtFastPath\] load start", sony_text)
preview_ready = count(r"\[AlbumArtFastPath\] preview ready", sony_text)
hq_ready = count(r"\[AlbumArtFastPath\] hq ready", sony_text)
failed = count(r"\[AlbumArtFastPath\] failed", sony_text)
pending_request = count(r"\[AlbumArtSend\] pending request", sony_text)
pending_fulfilled = count(r"\[AlbumArtSend\] pending request fulfilled", sony_text)
immediate_cache = count(r"\[AlbumArtSend\] immediate from cache", sony_text)
task_cancel = count(r"\[AlbumArtTask\] cancel", sony_text)
cache_put = count(r"\[AlbumArtCache\] put", sony_text)
cache_evict = count(r"\[AlbumArtCache\] evict", sony_text)

ios_offer = count(r"albumArtOffer|\[AlbumArt\].*offer", ios_text)
ios_preview = count(r"quality=preview|quality\":\"preview|displayQuality=preview|upgrade .*preview", ios_text)
ios_hq = count(r"quality=hq|quality\":\"hq|displayQuality=hq|upgrade .*hq|displayQuality=enhanced", ios_text)

playback_push = count(r"\[PlaybackDiff\] push reason=", sony_text)
playback_skip = count(r"\[PlaybackDiff\] skip reason=", sony_text)
payload_too_large = (
    count(r"status notify skipped: payload=\d+ max=\d+ type=playbackState", sony_text) +
    count(r"status notify skipped: payload=\d+ max=\d+ type=currentWord", sony_text) +
    count(r"Payload maximum size exceeded", sony_text + "\n" + ios_text)
)
main_stall = count(r"main stall detected", ios_text)
stale = int(base_ios.get("currentWordStaleDiscardCount", 0) or 0)
current_word_raw = int(base_ios.get("currentWordRawCount", 0) or 0)
current_word_accepted = int(base_ios.get("currentWordAcceptedCount", 0) or 0)

report = {
    "summary": {
        "result": "PASS",
        "durationSec": duration,
        "track": base_summary.get("track", ""),
        "playing": bool(base_summary.get("playing", False)),
        "warnings": [],
        "issues": [],
        "iosAppLaunched": precheck.get("iosAppLaunched", False),
        "iosBleConnected": precheck.get("iosBleConnected", False),
        "notifySubscribed": precheck.get("notifySubscribed", False),
        "firstPlaybackStateReceived": precheck.get("firstPlaybackStateReceived", False),
        "firstPlaybackStateLatencyMs": precheck.get("firstPlaybackStateLatencyMs", 0),
        "precheckResult": precheck.get("precheckResult", "UNKNOWN"),
        "precheckFailReason": precheck.get("precheckFailReason", ""),
    },
    "albumArtFastPath": {
        "trackChangedCount": track_changed,
        "fastPathStartCount": fast_start,
        "cacheHitCount": cache_hit,
        "cacheMissCount": cache_miss,
        "cachePutCount": cache_put,
        "cacheEvictCount": cache_evict,
        "loadStartCount": load_start,
        "previewReadyCount": preview_ready,
        "hqReadyCount": hq_ready,
        "failedCount": failed,
        "pendingRequestCount": pending_request,
        "pendingFulfilledCount": pending_fulfilled,
        "immediateFromCacheCount": immediate_cache,
        "taskCancelCount": task_cancel,
        "previewReadyAvgMs": avg(preview_ready_costs),
        "previewReadyP95Ms": p95(preview_ready_costs),
        "hqReadyAvgMs": avg(hq_ready_costs),
        "hqReadyP95Ms": p95(hq_ready_costs),
        "trackChangedToPreviewReadyLatencyMs": avg(preview_ready_costs),
        "trackChangedToHqReadyLatencyMs": avg(hq_ready_costs),
    },
    "iosAlbumArt": {
        "offerReceivedCount": ios_offer,
        "previewReceivedCount": ios_preview,
        "hqReceivedCount": ios_hq,
    },
    "regression": {
        "playbackStatePushCount": playback_push,
        "playbackStateSkipCount": playback_skip,
        "currentWordRawCount": current_word_raw,
        "currentWordAcceptedCount": current_word_accepted,
        "sonyCurrentWordPushCount": base_sony.get("currentWordPushCount", 0),
        "staleDiscardCount": stale,
        "payloadTooLarge": payload_too_large,
        "mainStallCount": main_stall,
    },
    "baseCurrentWord": base_report,
    "precheck": precheck,
    "artifacts": {
        "report_json": str(out_dir / "report.json"),
        "report_md": str(out_dir / "report.md"),
        "base_report_json": str(base_report_path),
        "sony_logcat": str(sony_log),
        "ios_ble_log": str(ios_log),
    },
}

issues = report["summary"]["issues"]
warnings = report["summary"]["warnings"]

if stale:
    issues.append(f"currentWord stale discard observed ({stale})")
if payload_too_large:
    issues.append(f"payload too large observed ({payload_too_large})")
if main_stall:
    issues.append(f"main stall observed ({main_stall})")
if current_word_raw > 0 and current_word_accepted == 0:
    issues.append("currentWord raw received but none accepted")

if not base_summary.get("playing", False):
    warnings.append("playing=true not detected")
if fast_start == 0:
    warnings.append("no AlbumArtFastPath track_changed start observed")
if cache_hit and preview_ready_costs and min(preview_ready_costs) >= 100:
    warnings.append("cache hit did not reach <100ms preview-ready target")
if track_changed and preview_ready == 0 and failed == 0:
    warnings.append("track changed but no preview ready or failed result observed")
if pending_request and pending_fulfilled == 0:
    warnings.append("pending album art request was not fulfilled during window")
for item in base_summary.get("warnings", []) or []:
    warnings.append(f"base currentWord warning: {item}")
for item in base_summary.get("issues", []) or []:
    issues.append(f"base currentWord issue: {item}")

if issues:
    report["summary"]["result"] = "FAIL"
elif warnings:
    report["summary"]["result"] = "WARN"

out_dir.mkdir(parents=True, exist_ok=True)
(out_dir / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2))

md = ["# AlbumArt Fast Path V2.7 Long Play Report", ""]
md += [
    f"- Result: {report['summary']['result']}",
    f"- Duration: {duration}s",
    f"- Track: {report['summary']['track']}",
    f"- Playing: {report['summary']['playing']}",
    "",
    "## AlbumArt Fast Path",
]
for key, value in report["albumArtFastPath"].items():
    md.append(f"- {key}: {value}")
md.append("")
md.append("## iOS AlbumArt")
for key, value in report["iosAlbumArt"].items():
    md.append(f"- {key}: {value}")
md.append("")
md.append("## Regression")
for key, value in report["regression"].items():
    md.append(f"- {key}: {value}")
if warnings:
    md += ["", "## Warnings"]
    md += [f"- {item}" for item in warnings]
if issues:
    md += ["", "## Issues"]
    md += [f"- {item}" for item in issues]
(out_dir / "report.md").write_text("\n".join(md))

print(json.dumps({
    "report_json": str(out_dir / "report.json"),
    "report_md": str(out_dir / "report.md"),
    "summary": report["summary"],
}, ensure_ascii=False))
sys.exit(1 if issues else 0)
PY
