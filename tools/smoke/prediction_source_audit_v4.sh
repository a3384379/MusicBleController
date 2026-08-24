#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RUNS=100
OUTPUT_DIR=""
IOS_DEVICE=""
ANDROID_DEVICE=""
JSON_MODE=false

usage() {
  cat <<'EOF'
Usage: prediction_source_audit_v4.sh [options]

Options:
  --runs <count>          Actual transitions required. Default: 100.
  --ios-device <id>       iPhone devicectl identifier.
  --android-device <id>   Sony adb serial.
  --output <dir>          Output directory.
  --sony-log <path>       Analyze an existing Sony full log without driving devices.
  --json                  Print report.json.
  -h, --help              Show this help.
EOF
}

SONY_LOG=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --runs) RUNS="${2:?--runs requires a count}"; shift 2 ;;
    --ios-device) IOS_DEVICE="${2:?--ios-device requires an id}"; shift 2 ;;
    --android-device) ANDROID_DEVICE="${2:?--android-device requires an id}"; shift 2 ;;
    --output) OUTPUT_DIR="${2:?--output requires a directory}"; shift 2 ;;
    --sony-log) SONY_LOG="${2:?--sony-log requires a path}"; shift 2 ;;
    --json) JSON_MODE=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

if ! [[ "$RUNS" =~ ^[1-9][0-9]*$ ]] || (( RUNS > 500 )); then
  echo "--runs must be between 1 and 500" >&2
  exit 2
fi

timestamp="$(date +%Y%m%d_%H%M%S)"
OUTPUT_DIR="${OUTPUT_DIR:-/tmp/musicble_prediction_audit_v4/$timestamp}"
mkdir -p "$OUTPUT_DIR"

if [[ -z "$SONY_LOG" ]]; then
  realtime_args=(--runs "$RUNS" --output "$OUTPUT_DIR/realtime")
  if [[ -n "$IOS_DEVICE" ]]; then
    realtime_args+=(--ios-device "$IOS_DEVICE")
  fi
  if [[ -n "$ANDROID_DEVICE" ]]; then
    realtime_args+=(--android-device "$ANDROID_DEVICE")
  fi
  "$SCRIPT_DIR/realtime_latency_v4_test.sh" "${realtime_args[@]}"
  SONY_LOG="$OUTPUT_DIR/realtime/sony_full.log"
fi

if [[ ! -s "$SONY_LOG" ]]; then
  echo "Sony log is missing or empty: $SONY_LOG" >&2
  exit 1
fi

LC_ALL=C grep -E \
  '\[PredictionSource\]|\[PredictiveMedia\]|\[PredictiveLyricsCandidate\]|\[PredictiveLyrics\]|stage=(commandReceived|mediaSessionTrackChanged)|\[RuntimeCache\] track changed' \
  "$SONY_LOG" >"$OUTPUT_DIR/sony_events.log" || true

report_args=(
  --sony-log "$SONY_LOG"
  --output-dir "$OUTPUT_DIR"
  --expected-transitions "$RUNS"
)
if [[ "$JSON_MODE" == true ]]; then
  report_args+=(--json)
fi
python3 "$SCRIPT_DIR/prediction_source_audit_report.py" "${report_args[@]}"

echo "[PredictionSourceAuditV4] report=$OUTPUT_DIR/report.json" >&2
