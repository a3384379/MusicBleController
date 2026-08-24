#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RUNS=30
FAST_SWITCH=false
DURATION_MINUTES=0
OUTPUT_DIR=""
IOS_DEVICE=""
ANDROID_DEVICE=""
JSON_MODE=false

usage() {
  cat <<'EOF'
Usage: predictive_media_v4_test.sh [options]

Options:
  --runs <count>               Requested transition samples. Default: 30.
  --fast-switch                Alternate NEXT/PREVIOUS at 650 ms intervals.
  --duration-minutes <count>   Repeat bounded 30-sample rounds for a soak test.
  --ios-device <id>            iPhone devicectl identifier.
  --android-device <id>        Sony adb serial.
  --output <dir>               Output directory.
  --json                       Print report.json.
  -h, --help                   Show this help.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --runs) RUNS="${2:?--runs requires a count}"; shift 2 ;;
    --fast-switch) FAST_SWITCH=true; shift ;;
    --duration-minutes) DURATION_MINUTES="${2:?--duration-minutes requires a count}"; shift 2 ;;
    --ios-device) IOS_DEVICE="${2:?--ios-device requires an id}"; shift 2 ;;
    --android-device) ANDROID_DEVICE="${2:?--android-device requires an id}"; shift 2 ;;
    --output) OUTPUT_DIR="${2:?--output requires a directory}"; shift 2 ;;
    --json) JSON_MODE=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

if ! [[ "$RUNS" =~ ^[1-9][0-9]*$ ]] || (( RUNS > 500 )); then
  echo "--runs must be between 1 and 500" >&2
  exit 2
fi
if ! [[ "$DURATION_MINUTES" =~ ^[0-9]+$ ]] || (( DURATION_MINUTES > 720 )); then
  echo "--duration-minutes must be between 0 and 720" >&2
  exit 2
fi

timestamp="$(date +%Y%m%d_%H%M%S)"
OUTPUT_DIR="${OUTPUT_DIR:-/tmp/musicble_predictive_v4/$timestamp}"
mkdir -p "$OUTPUT_DIR"
: >"$OUTPUT_DIR/sony_trace.log"
: >"$OUTPUT_DIR/ios_trace.log"
: >"$OUTPUT_DIR/sony_full.log"
: >"$OUTPUT_DIR/ios_full.log"

run_round() {
  local round_dir="$1"
  local round_runs="$2"
  local args=(--runs "$round_runs" --output "$round_dir")
  if [[ "$FAST_SWITCH" == true || "$DURATION_MINUTES" -gt 0 ]]; then
    args+=(--fast-switch)
  fi
  if [[ -n "$IOS_DEVICE" ]]; then args+=(--ios-device "$IOS_DEVICE"); fi
  if [[ -n "$ANDROID_DEVICE" ]]; then args+=(--android-device "$ANDROID_DEVICE"); fi
  if ! "$SCRIPT_DIR/realtime_latency_v4_test.sh" "${args[@]}" >/dev/null; then
    echo "[PredictiveMediaV4] realtime round incomplete: $round_dir" >&2
  fi
  for name in sony_trace.log ios_trace.log sony_full.log ios_full.log; do
    if [[ -s "$round_dir/$name" ]]; then
      cat "$round_dir/$name" >>"$OUTPUT_DIR/$name"
    fi
  done
}

if (( DURATION_MINUTES > 0 )); then
  deadline=$(( $(date +%s) + DURATION_MINUTES * 60 ))
  round=0
  while (( $(date +%s) < deadline )); do
    round=$((round + 1))
    run_round "$OUTPUT_DIR/round_$round" 30
  done
  REALTIME_REPORT=""
  EXPECTED_TRANSITIONS=1
else
  run_round "$OUTPUT_DIR/realtime" "$RUNS"
  REALTIME_REPORT="$OUTPUT_DIR/realtime/report.json"
  # Fast-switch is a control-pressure test: Android must receive every command,
  # while the player is allowed to coalesce rapid commands into fewer track changes.
  # The separate source audit remains responsible for collecting 100 real transitions.
  if [[ "$FAST_SWITCH" == true ]]; then
    EXPECTED_TRANSITIONS=1
  else
    EXPECTED_TRANSITIONS="$RUNS"
  fi
fi

SOURCE_AUDIT_DIR="$OUTPUT_DIR/source_audit"
mkdir -p "$SOURCE_AUDIT_DIR"
if ! python3 "$SCRIPT_DIR/prediction_source_audit_report.py" \
  --sony-log "$OUTPUT_DIR/sony_full.log" \
  --output-dir "$SOURCE_AUDIT_DIR" \
  --expected-transitions "$EXPECTED_TRANSITIONS"; then
  echo "[PredictiveMediaV4] prediction source audit incomplete" >&2
fi

report_args=(
  --sony-trace "$OUTPUT_DIR/sony_full.log"
  --ios-trace "$OUTPUT_DIR/ios_full.log"
  --source-audit "$SOURCE_AUDIT_DIR/report.json"
  --output-dir "$OUTPUT_DIR"
)
if [[ -n "$REALTIME_REPORT" && -s "$REALTIME_REPORT" ]]; then
  report_args+=(--realtime-report "$REALTIME_REPORT")
fi
if [[ "$JSON_MODE" == true ]]; then report_args+=(--json); fi

python3 - "$OUTPUT_DIR/compatibility_matrix.json" <<'PY'
import json
import sys
from pathlib import Path
matrix = {
    "newSony_newIOS": "OBSERVED_IN_THIS_RUN",
    "newSony_oldIOS": "NOT_RUN",
    "oldSony_newIOS": "NOT_RUN",
    "newSony_newIOS_oldController": "NOT_RUN",
    "newControllerWithoutCapability": "NOT_RUN",
    "mixedCapabilityDualController": "NOT_RUN",
}
Path(sys.argv[1]).write_text(json.dumps(matrix, indent=2) + "\n", encoding="utf-8")
PY

python3 "$SCRIPT_DIR/predictive_media_report.py" "${report_args[@]}"
echo "[PredictiveMediaV4] report=$OUTPUT_DIR/report.json" >&2
