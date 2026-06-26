#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
DURATION_SEC=90
OUTPUT_DIR_ARG=""
JSON_OUTPUT=false
PASSTHROUGH=()

usage() {
  cat <<'EOF'
Usage: playback_state_v24_long_play_test.sh [options]

Options:
  --duration <seconds>       Test window duration. Default: 90.
  --ios-device <id>          iPhone devicectl identifier.
  --android-device <id>      Sony adb serial.
  --output <dir>             Output directory.
  --json                     Print machine-readable summary only.
  -h, --help                 Show help.

This V2.4 test reuses the CurrentWord long-play capture window and adds
PlaybackState Diff / Buffer analysis from Sony and iOS logs.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --duration)
      DURATION_SEC="${2:?--duration requires seconds}"
      PASSTHROUGH+=("$1" "$2")
      shift 2
      ;;
    --ios-device|--android-device)
      PASSTHROUGH+=("$1" "${2:?$1 requires a value}")
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
      PASSTHROUGH+=("$1")
      shift
      ;;
  esac
done

timestamp="$(date +%Y%m%d_%H%M%S)"
if [[ -n "$OUTPUT_DIR_ARG" ]]; then
  OUT_DIR="$OUTPUT_DIR_ARG"
else
  OUT_DIR="${OUT_DIR:-/tmp/playback_state_v24_long_play/$timestamp}"
fi
mkdir -p "$OUT_DIR"

BASE_DIR="$OUT_DIR/base"
mkdir -p "$BASE_DIR"

"$SCRIPT_DIR/current_word_long_play_test.sh" \
  --duration "$DURATION_SEC" \
  --output "$BASE_DIR" \
  --json \
  "${PASSTHROUGH[@]}" > "$OUT_DIR/base_summary.json"

python3 - "$OUT_DIR" "$BASE_DIR" <<'PY'
import json
import re
import sys
from pathlib import Path

out_dir = Path(sys.argv[1])
base_dir = Path(sys.argv[2])
base = json.loads((base_dir / "report.json").read_text(encoding="utf-8"))
sony_log = Path(base["artifacts"]["sony_logcat"]).read_text(encoding="utf-8", errors="replace")
ios_log = Path(base["artifacts"]["ios_ble_log"]).read_text(encoding="utf-8", errors="replace")

candidate_lines = re.findall(r"\[PlaybackDiff\]\s+candidate\b.*", sony_log)
push_lines = re.findall(r"\[PlaybackDiff\]\s+push reason=([^\s]+).*", sony_log)
skip_lines = re.findall(r"\[PlaybackDiff\]\s+skip reason=([^\s]+).*", sony_log)
buffer_coalesce = re.findall(r"\[PlaybackBuffer\]\s+coalesce count=(\d+).*", sony_log)
buffer_flush = re.findall(r"\[PlaybackBuffer\]\s+flush reason=([^\s]+).*", sony_log)
buffer_sent = re.findall(r"\[PlaybackBuffer\]\s+sent reason=([^\s]+).*payloadSize=(\d+).*", sony_log)

skip_reasons = {}
for reason in skip_lines:
    skip_reasons[reason] = skip_reasons.get(reason, 0) + 1
push_reasons = {}
for reason in push_lines:
    push_reasons[reason] = push_reasons.get(reason, 0) + 1
buffer_flush_reasons = {}
for reason in buffer_flush:
    buffer_flush_reasons[reason] = buffer_flush_reasons.get(reason, 0) + 1

payload_sizes = [int(size) for _, size in buffer_sent]
payload_too_large = len(re.findall(r"status notify skipped: payload=\d+ max=\d+ type=playbackState", sony_log))
current_word_payload_too_large = len(
    re.findall(r"status notify skipped: payload=\d+ max=\d+ type=currentWord", sony_log)
)
ios_playback = len(re.findall(r'\{"type":"playbackState"', ios_log))
ios_position_logs = len(re.findall(r"\[iOS\]\[Status\]\s+playbackState position=", ios_log))
ios_karaoke_tick_logs = len(re.findall(r"\[Lyrics-iOS\]\s+karaoke offsetMs=", ios_log))
main_stall = base["ios"].get("mainStallCount", 0)
stale = base["ios"].get("currentWordStaleDiscardCount", 0)
current_word_raw = base["ios"].get("currentWordRawCount", 0)
current_word_accepted = base["ios"].get("currentWordAcceptedCount", 0)

candidate_count = len(candidate_lines)
push_count = len(buffer_sent) or len(push_lines)
skip_count = len(skip_lines)
push_lower_than_candidate = candidate_count > 0 and push_count < candidate_count
position_small_count = skip_reasons.get("position_delta_small", 0)

issues = []
warnings = []
if ios_playback <= 0:
    issues.append("iOS received no playbackState")
if current_word_raw > 0 and current_word_accepted <= 0:
    issues.append("currentWord raw was present but none accepted")
if stale > 0:
    issues.append(f"currentWord stale discard observed ({stale})")
if main_stall > 0:
    issues.append(f"main stall detected ({main_stall})")
if payload_too_large > 0:
    issues.append(f"playbackState payload too large ({payload_too_large})")
if current_word_payload_too_large > 0:
    issues.append(f"currentWord payload too large ({current_word_payload_too_large})")
if candidate_count <= 0:
    warnings.append("no PlaybackDiff candidate logs found")
if candidate_count > 0 and not push_lower_than_candidate:
    warnings.append("playbackState push count is not lower than candidate count")
if position_small_count <= 0:
    warnings.append("position_delta_small skip was not observed")
if ios_karaoke_tick_logs <= 0 and ios_position_logs <= 0:
    warnings.append("no iOS position progress evidence logs found")

if issues:
    result = "FAIL"
elif warnings:
    result = "WARN"
else:
    result = "PASS"

payload = {
    "summary": {
        "result": result,
        "durationSec": base["summary"].get("durationSec", 0),
        "track": base["summary"].get("track", ""),
        "playing": base["summary"].get("playing", False),
        "warnings": warnings,
        "issues": issues,
    },
    "playbackState": {
        "candidateCount": candidate_count,
        "pushCount": push_count,
        "skipCount": skip_count,
        "skipReasons": skip_reasons,
        "pushReasons": push_reasons,
        "bufferCoalesceCount": len(buffer_coalesce),
        "bufferMaxCoalesce": max([int(v) for v in buffer_coalesce], default=0),
        "bufferFlushCount": len(buffer_flush),
        "bufferFlushReasons": buffer_flush_reasons,
        "payloadTooLarge": payload_too_large,
        "payloadSizeMax": max(payload_sizes, default=0),
        "payloadSizeAvg": round(sum(payload_sizes) / len(payload_sizes), 2) if payload_sizes else 0,
    },
    "ios": {
        "playbackStateReceivedCount": ios_playback,
        "positionStatusLogCount": ios_position_logs,
        "karaokeTickLogCount": ios_karaoke_tick_logs,
        "currentWordRawCount": current_word_raw,
        "currentWordAcceptedCount": current_word_accepted,
        "staleDiscardCount": stale,
        "mainStallCount": main_stall,
    },
    "baseCurrentWord": base,
    "artifacts": {
        "report_json": str(out_dir / "report.json"),
        "report_md": str(out_dir / "report.md"),
        "base_report_json": str(base_dir / "report.json"),
        "base_report_md": str(base_dir / "report.md"),
        "sony_logcat": base["artifacts"]["sony_logcat"],
        "ios_ble_log": base["artifacts"]["ios_ble_log"],
    },
}

lines = [
    "# PlaybackState V2.4 Long Play Test",
    "",
    f"Result: {result}",
    f"Track: {payload['summary']['track'] or '-'}",
    f"Playing: {str(payload['summary']['playing']).lower()}",
    "",
    "| Metric | Value |",
    "|---|---:|",
    f"| PlaybackDiff candidates | {candidate_count} |",
    f"| PlaybackState push | {push_count} |",
    f"| PlaybackState skip | {skip_count} |",
    f"| position_delta_small | {position_small_count} |",
    f"| Buffer coalesce events | {len(buffer_coalesce)} |",
    f"| Buffer max coalesce | {payload['playbackState']['bufferMaxCoalesce']} |",
    f"| iOS playbackState received | {ios_playback} |",
    f"| currentWord raw | {current_word_raw} |",
    f"| currentWord accepted | {current_word_accepted} |",
    f"| stale discard | {stale} |",
    f"| main stall | {main_stall} |",
    f"| playbackState payload too large | {payload_too_large} |",
    f"| currentWord payload too large | {current_word_payload_too_large} |",
    "",
    "## Skip Reasons",
    "",
]
if skip_reasons:
    lines.extend(f"- {key}: {value}" for key, value in sorted(skip_reasons.items()))
else:
    lines.append("- none")
lines.append("")
if warnings:
    lines.extend(["## Warnings", ""])
    lines.extend(f"- {item}" for item in warnings)
    lines.append("")
if issues:
    lines.extend(["## Issues", ""])
    lines.extend(f"- {item}" for item in issues)
    lines.append("")
lines.extend([
    "## Artifacts",
    "",
    f"- base currentWord report: `{base_dir / 'report.json'}`",
    f"- sony_logcat: `{base['artifacts']['sony_logcat']}`",
    f"- ios_ble.log: `{base['artifacts']['ios_ble_log']}`",
    "",
])

(out_dir / "report.json").write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
(out_dir / "report.md").write_text("\n".join(lines), encoding="utf-8")
print(json.dumps({
    "report_json": str(out_dir / "report.json"),
    "report_md": str(out_dir / "report.md"),
    "summary": payload["summary"],
}, ensure_ascii=False))
if result == "FAIL":
    sys.exit(1)
PY

if [[ "$JSON_OUTPUT" == true ]]; then
  cat "$OUT_DIR/report.json" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(json.dumps({"report_json": d["artifacts"]["report_json"], "report_md": d["artifacts"]["report_md"], "summary": d["summary"]}, ensure_ascii=False))'
else
  cat "$OUT_DIR/report.md"
fi
