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
  OUTPUT_DIR="/tmp/predictive_lyrics_v25/$(date +%Y%m%d_%H%M%S)"
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

python3 - "$OUTPUT_DIR" "$BASE_DIR" "$DURATION" "$JSON_MODE" <<'PY'
import json
import re
import statistics
import sys
from pathlib import Path

out_dir = Path(sys.argv[1])
base_dir = Path(sys.argv[2])
duration = int(sys.argv[3])
json_mode = sys.argv[4] == "1"

base_report_path = base_dir / "report.json"
base_report = json.loads(base_report_path.read_text()) if base_report_path.exists() else {}
sony_log = base_dir / "sony_logcat.log"
ios_log = base_dir / "ios_ble.log"
sony_text = sony_log.read_text(errors="ignore") if sony_log.exists() else ""
ios_text = ios_log.read_text(errors="ignore") if ios_log.exists() else ""

def count(pattern, text):
    return len(re.findall(pattern, text))

def costs(pattern, text):
    values = []
    for match in re.finditer(pattern, text):
        try:
            values.append(int(match.group(1)))
        except Exception:
            pass
    return values

def avg(values):
    return round(sum(values) / len(values), 2) if values else 0

def p95(values):
    if not values:
        return 0
    ordered = sorted(values)
    index = int((len(ordered) - 1) * 0.95)
    return ordered[index]

track_changed_count = count(r"\[BLE-A\]\[AutoPush\] song changed", sony_text)
candidate_count = count(r"\[PredictiveLyrics\] candidate", sony_text)
candidate_selected_count = count(r"\[PredictiveLyricsCandidate\] selected", sony_text)
candidate_rejected_count = count(r"\[PredictiveLyricsCandidate\] rejected", sony_text)
queue_diagnostic_count = count(r"\[PredictiveLyricsCandidate\] queue diagnostic", sony_text)
queue_available_count = count(r"\[PredictiveLyricsCandidate\] queue diagnostic .*hasQueue=true", sony_text)
queue_null_count = count(r"source=(?:media_session_queue|manual_next_with_queue) unavailable reason=queue_null", sony_text)
queue_empty_count = count(r"source=(?:media_session_queue|manual_next_with_queue) unavailable reason=queue_empty", sony_text)
metadata_missing_count = count(r"source=(?:media_session_queue|manual_next_with_queue) unavailable reason=metadata_missing", sony_text)
active_queue_unknown_count = count(r"source=(?:media_session_queue|manual_next_with_queue) unavailable reason=active_queue_id_unknown", sony_text)
manual_next_hint_count = count(r"\[PredictiveLyricsCandidate\] manual next requested", sony_text)
manual_next_candidate_hint_count = count(r"\[PredictiveLyricsCandidate\] manual next hint", sony_text)
history_learned_count = count(r"\[PredictiveLyricsCandidate\] history transition learned", sony_text)
history_candidate_count = count(r"\[PredictiveLyricsCandidate\] selected source=history_transition", sony_text)
media_queue_candidate_count = count(r"\[PredictiveLyricsCandidate\] selected source=media_session_queue", sony_text)
manual_next_candidate_count = count(r"\[PredictiveLyricsCandidate\] selected source=manual_next_with_queue", sony_text)
preload_start_count = count(r"\[PredictiveLyrics\] preload start", sony_text)
preload_hit_count = count(r"\[PredictiveLyrics\] preload hit", sony_text)
preload_miss_count = count(r"\[PredictiveLyrics\] preload miss", sony_text)
preload_success_count = count(r"\[PredictiveLyrics\] preload hit .*preloadCostMs=", sony_text)
preload_failed_count = count(r"\[PredictiveLyrics\] preload miss .*reason=", sony_text)
cache_put_count = count(r"\[PredictiveLyrics\] cache put", sony_text)
cache_evict_count = count(r"\[PredictiveLyrics\] cache evict", sony_text)
apply_hit_count = count(r"\[PredictiveLyrics\] apply hit", sony_text)
apply_miss_count = count(r"\[PredictiveLyrics\] apply miss", sony_text)
identity_mismatch_count = count(r"\[PredictiveLyrics\] identity mismatch", sony_text)
preload_costs = costs(r"preloadCostMs=(\d+)", sony_text)
apply_costs = costs(r"applyCostMs=(\d+)", sony_text)

playback_state_push = count(r"\[PlaybackDiff\] push reason=", sony_text)
playback_state_skip = count(r"\[PlaybackDiff\] skip reason=", sony_text)
playback_state_payload_too_large = count(
    r"status notify skipped: payload=\d+ max=\d+ type=playbackState", sony_text
)
current_word_payload_too_large = count(
    r"status notify skipped: payload=\d+ max=\d+ type=currentWord", sony_text
)
payload_max_exceeded = count(r"Payload maximum size exceeded", sony_text + "\n" + ios_text)
full_lyrics_words_omitted = count(r"\[FullLyrics\] words omitted .*reason=payload too large", sony_text)
main_stall = count(r"main stall detected", ios_text)

base_ios = base_report.get("ios", {})
base_sony = base_report.get("sony", {})
base_summary = base_report.get("summary", {})
base_latency = base_report.get("latency", {})

current_word_raw = int(base_ios.get("currentWordRawCount", 0) or 0)
current_word_accepted = int(base_ios.get("currentWordAcceptedCount", 0) or 0)
stale_discard = int(base_ios.get("currentWordStaleDiscardCount", 0) or 0)
sony_current_word_push = int(base_sony.get("currentWordPushCount", 0) or 0)

lyrics_ready_latencies = []
first_word_latencies = []
song_change_times = []
for line in sony_text.splitlines():
    if "[BLE-A][AutoPush] song changed" in line:
        m = re.match(r"(\d\d-\d\d \d\d:\d\d:\d\d\.\d+)", line)
        if m:
            song_change_times.append(m.group(1))

# The log timestamps do not include a year and are not monotonic-safe across devices.
# Use explicit pipeline cost logs as the primary latency source for V1.
if apply_costs:
    lyrics_ready_latencies = apply_costs[:]

report = {
    "summary": {
        "result": "PASS",
        "durationSec": duration,
        "track": base_summary.get("track", ""),
        "playing": bool(base_summary.get("playing", False)),
        "warnings": [],
        "issues": []
    },
    "predictiveLyrics": {
        "trackChangedCount": track_changed_count,
        "candidateCount": candidate_count,
        "candidateSelectedCount": candidate_selected_count,
        "candidateRejectedCount": candidate_rejected_count,
        "candidateSourceCount": {
            "mediaSessionQueue": media_queue_candidate_count,
            "manualNextWithQueue": manual_next_candidate_count,
            "historyTransition": history_candidate_count
        },
        "mediaSessionQueueAvailableCount": queue_available_count,
        "queueDiagnosticCount": queue_diagnostic_count,
        "queueNullCount": queue_null_count,
        "queueEmptyCount": queue_empty_count,
        "metadataMissingCount": metadata_missing_count,
        "activeQueueUnknownCount": active_queue_unknown_count,
        "manualNextHintCount": manual_next_hint_count,
        "manualNextCandidateHintCount": manual_next_candidate_hint_count,
        "historyTransitionLearnedCount": history_learned_count,
        "historyTransitionCandidateCount": history_candidate_count,
        "preloadStartCount": preload_start_count,
        "preloadHitCount": preload_hit_count,
        "preloadMissCount": preload_miss_count,
        "preloadSuccessCount": preload_success_count,
        "preloadFailedCount": preload_failed_count,
        "cachePutCount": cache_put_count,
        "cacheEvictCount": cache_evict_count,
        "applyHitCount": apply_hit_count,
        "applyMissCount": apply_miss_count,
        "identityMismatchCount": identity_mismatch_count,
        "preloadCostAvgMs": avg(preload_costs),
        "preloadCostP95Ms": p95(preload_costs),
        "preloadCostMaxMs": max(preload_costs) if preload_costs else 0,
        "applyCostAvgMs": avg(apply_costs),
        "applyCostP95Ms": p95(apply_costs),
        "applyCostMaxMs": max(apply_costs) if apply_costs else 0,
        "trackChangedToLyricsReadyAvgMs": avg(lyrics_ready_latencies),
        "trackChangedToLyricsReadyP95Ms": p95(lyrics_ready_latencies),
        "trackChangedToFirstCurrentWordAvgMs": base_latency.get("latencyAvgMs", 0),
        "trackChangedToFirstCurrentWordP95Ms": base_latency.get("latencyP95Ms", 0)
    },
    "regression": {
        "currentWordRawCount": current_word_raw,
        "currentWordAcceptedCount": current_word_accepted,
        "staleDiscardCount": stale_discard,
        "playbackStatePushCount": playback_state_push,
        "playbackStateSkipCount": playback_state_skip,
        "playbackStatePayloadTooLarge": playback_state_payload_too_large,
        "currentWordPayloadTooLarge": current_word_payload_too_large,
        "payloadMaximumExceeded": payload_max_exceeded,
        "fullLyricsWordsOmitted": full_lyrics_words_omitted,
        "mainStallCount": main_stall,
        "sonyCurrentWordPushCount": sony_current_word_push
    },
    "baseCurrentWord": base_report,
    "artifacts": {
        "report_json": str(out_dir / "report.json"),
        "report_md": str(out_dir / "report.md"),
        "base_report_json": str(base_report_path),
        "sony_logcat": str(sony_log),
        "ios_ble_log": str(ios_log)
    }
}

issues = report["summary"]["issues"]
warnings = report["summary"]["warnings"]

if playback_state_payload_too_large:
    issues.append(f"playbackState payload too large was observed ({playback_state_payload_too_large})")
if current_word_payload_too_large:
    issues.append(f"currentWord payload too large was observed ({current_word_payload_too_large})")
if payload_max_exceeded:
    issues.append(f"payload maximum exceeded was observed ({payload_max_exceeded})")
if full_lyrics_words_omitted:
    warnings.append(f"FullLyrics omitted word timing in {full_lyrics_words_omitted} chunks to fit MTU")
if main_stall:
    issues.append("iOS main stall was observed")
if stale_discard:
    issues.append("currentWord stale discard was observed")
if current_word_raw > 0 and current_word_accepted == 0:
    issues.append("currentWord raw was received but none accepted")
if sony_current_word_push > 0 and current_word_raw == 0:
    issues.append("Sony pushed currentWord but iOS received none")
for item in base_summary.get("issues", []) or []:
    issues.append(f"base currentWord issue: {item}")
for item in base_summary.get("warnings", []) or []:
    warnings.append(f"base currentWord warning: {item}")
if identity_mismatch_count:
    issues.append("predictive lyrics identity mismatch was observed")

if candidate_count == 0:
    warnings.append("no predictive lyrics candidate observed; MediaSession queue may be unavailable")
if candidate_count == 0 and queue_diagnostic_count == 0:
    warnings.append("no predictive queue diagnostic observed")
if preload_start_count == 0:
    warnings.append("no predictive preload started")
if apply_hit_count == 0:
    warnings.append("no predictive apply hit observed in this window")
if apply_costs and max(apply_costs) >= 100:
    warnings.append("predictive apply cost exceeded 100ms")
if track_changed_count == 0:
    warnings.append("no track_changed observed in this window")

if issues:
    report["summary"]["result"] = "FAIL"
elif warnings:
    report["summary"]["result"] = "WARN"

out_dir.mkdir(parents=True, exist_ok=True)
(out_dir / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2))

md = []
md.append("# Predictive Lyrics V2.5 Long Play Report")
md.append("")
md.append(f"- Result: {report['summary']['result']}")
md.append(f"- Duration: {duration}s")
md.append(f"- Track: {report['summary']['track']}")
md.append(f"- Playing: {report['summary']['playing']}")
md.append("")
md.append("## Predictive Lyrics")
for key, value in report["predictiveLyrics"].items():
    md.append(f"- {key}: {value}")
md.append("")
md.append("## Regression")
for key, value in report["regression"].items():
    md.append(f"- {key}: {value}")
if warnings:
    md.append("")
    md.append("## Warnings")
    for item in warnings:
        md.append(f"- {item}")
if issues:
    md.append("")
    md.append("## Issues")
    for item in issues:
        md.append(f"- {item}")
(out_dir / "report.md").write_text("\n".join(md))

summary = {
    "report_json": str(out_dir / "report.json"),
    "report_md": str(out_dir / "report.md"),
    "summary": report["summary"]
}
print(json.dumps(summary, ensure_ascii=False))
sys.exit(1 if issues else 0)
PY
