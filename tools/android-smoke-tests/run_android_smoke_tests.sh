#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
PACKAGE_NAME="${PACKAGE_NAME:-com.example.playeragent}"
SKIP_BUILD=false
SKIP_INSTALL=false
JSON_OUTPUT=false
DEBUG_CONTROL=true
OUTPUT_DIR_ARG=""

usage() {
  cat <<'EOF'
Usage: run_android_smoke_tests.sh [options]

Options:
  --quick          Skip build/install and run fast installed-app checks.
  --skip-build     Skip Gradle build.
  --skip-install   Skip APK install.
  --no-debug-control
                   Do not send debug-only service control broadcasts.
  --device <id>    Use a specific adb device id.
  --output <dir>   Write artifacts to a specific output directory.
  --json           Keep stdout machine-readable; write report.json path and summary.
  -h, --help       Show this help.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --quick)
      SKIP_BUILD=true
      SKIP_INSTALL=true
      echo "[AndroidSmoke] quick mode enabled" >&2
      echo "[AndroidSmoke] skip build/install" >&2
      shift
      ;;
    --skip-build)
      SKIP_BUILD=true
      shift
      ;;
    --skip-install)
      SKIP_INSTALL=true
      shift
      ;;
    --no-debug-control)
      DEBUG_CONTROL=false
      shift
      ;;
    --device)
      ANDROID_DEVICE_ID="${2:?--device requires an id}"
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
if [[ -n "$OUTPUT_DIR_ARG" ]]; then
  OUT_DIR="$OUTPUT_DIR_ARG"
else
  OUT_DIR="${OUT_DIR:-/tmp/music_ble_android_smoke/$timestamp}"
fi
mkdir -p "$OUT_DIR"

if [[ "$JSON_OUTPUT" == true ]]; then
  exec 3>&1
  exec >"$OUT_DIR/smoke_stdout.log"
else
  exec 3>&1
fi

REQUIRED_RESULTS="$OUT_DIR/required_results.tsv"
OPTIONAL_RESULTS="$OUT_DIR/optional_results.tsv"
: > "$REQUIRED_RESULTS"
: > "$OPTIONAL_RESULTS"

now_ms() {
  python3 - <<'PY'
import time
print(int(time.time() * 1000))
PY
}

elapsed_ms() {
  local start="$1"
  local end
  end="$(now_ms)"
  echo $((end - start))
}

record_result() {
  local category="$1"
  local test="$2"
  local result="$3"
  local cost_ms="$4"
  local detail="$5"
  printf '%s\t%s\t%s\t%s\t%s\n' "$category" "$test" "$result" "$cost_ms" "$detail" >> "$OUT_DIR/${category}_results.tsv"
}

record_required() {
  record_result "required" "$1" "$2" "$3" "$4"
}

record_optional() {
  record_result "optional" "$1" "$2" "$3" "$4"
}

log() {
  echo "[AndroidSmoke] $*" >&2
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

if ! ADB_BIN="$(find_adb)"; then
  log "adb not found. Install Android platform-tools or set ADB_BIN."
  record_required "Device Check" "FAIL" "0" "adb not found"
  python3 "$SCRIPT_DIR/generate_android_report.py" "$OUT_DIR" >/dev/null
  exit 1
fi

export ROOT_DIR OUT_DIR PACKAGE_NAME ADB_BIN
log "adb=$ADB_BIN"
log "output=$OUT_DIR"

start="$(now_ms)"
if "$SCRIPT_DIR/android_device_check.sh" >"$OUT_DIR/android_device_check_stdout.log" 2>"$OUT_DIR/android_device_check_stderr.log"; then
  DEVICE_ID="$(awk -F'\t' 'NR==1 {print $2}' "$OUT_DIR/device_info.tsv")"
  export DEVICE_ID
  record_required "Device Check" "PASS" "$(elapsed_ms "$start")" "device=$DEVICE_ID"
else
  detail="$(tail -20 "$OUT_DIR/android_device_check_stderr.log" | tr '\n' ' ' | sed 's/[[:space:]]\\+/ /g')"
  record_required "Device Check" "FAIL" "$(elapsed_ms "$start")" "${detail:-adb device check failed}"
  python3 "$SCRIPT_DIR/generate_android_report.py" "$OUT_DIR" > "$OUT_DIR/report_path.txt"
  REPORT_JSON="$(sed -n '2p' "$OUT_DIR/report_path.txt")"
  if [[ "$JSON_OUTPUT" == true ]]; then
    python3 - "$REPORT_JSON" >&3 <<'PY'
import json, sys
from pathlib import Path
path = Path(sys.argv[1])
data = json.loads(path.read_text(encoding="utf-8"))
print(json.dumps({"report_json": str(path), "report_md": data["artifacts"].get("report_md", ""), "summary": data["summary"]}, ensure_ascii=False))
PY
  else
    cat "$(sed -n '1p' "$OUT_DIR/report_path.txt")"
  fi
  exit 1
fi

start="$(now_ms)"
if [[ "$SKIP_BUILD" == true ]]; then
  record_required "Android Build" "PASS" "0" "skipped by request"
else
  if SKIP_BUILD=false SKIP_INSTALL=true "$SCRIPT_DIR/android_build_install.sh" >"$OUT_DIR/android_build_stdout.log" 2>"$OUT_DIR/android_build_stderr.log"; then
    record_required "Android Build" "PASS" "$(elapsed_ms "$start")" "Gradle assembleDebug succeeded"
  else
    record_required "Android Build" "FAIL" "$(elapsed_ms "$start")" "Gradle assembleDebug failed"
  fi
fi

start="$(now_ms)"
if [[ "$SKIP_INSTALL" == true ]]; then
  record_required "APK Install" "PASS" "0" "skipped by request"
else
  if SKIP_BUILD=true SKIP_INSTALL=false "$SCRIPT_DIR/android_build_install.sh" >"$OUT_DIR/android_install_stdout.log" 2>"$OUT_DIR/android_install_stderr.log"; then
    record_required "APK Install" "PASS" "$(elapsed_ms "$start")" "adb install succeeded"
  else
    record_required "APK Install" "FAIL" "$(elapsed_ms "$start")" "adb install failed"
  fi
fi

start="$(now_ms)"
if "$SCRIPT_DIR/android_launch.sh" >"$OUT_DIR/android_launch_stdout.log" 2>"$OUT_DIR/android_launch_stderr.log"; then
  pid="$(tr -d '\n' < "$OUT_DIR/app_pid.txt" 2>/dev/null || true)"
  record_required "App Launch" "PASS" "$(elapsed_ms "$start")" "pid=${pid:-unknown}"
else
  record_required "App Launch" "FAIL" "$(elapsed_ms "$start")" "launch or process check failed"
fi

if grep -Eiq "FATAL EXCEPTION|\\bANR\\b" "$OUT_DIR/launch_logcat.log" 2>/dev/null; then
  record_required "Crash Check" "FAIL" "0" "FATAL/ANR found after launch"
else
  record_required "Crash Check" "PASS" "0" "no immediate FATAL/ANR after launch"
fi

start="$(now_ms)"
if "$SCRIPT_DIR/android_collect_logs.sh" >"$OUT_DIR/android_collect_logs_stdout.log" 2>"$OUT_DIR/android_collect_logs_stderr.log"; then
  bytes="$(wc -c < "$OUT_DIR/sony_logcat.log" | tr -d ' ')"
  record_required "Logcat" "PASS" "$(elapsed_ms "$start")" "sony_logcat.log ${bytes} bytes"
else
  record_required "Logcat" "FAIL" "$(elapsed_ms "$start")" "logcat collection failed"
fi

start="$(now_ms)"
if "$SCRIPT_DIR/android_file_checks.sh" >"$OUT_DIR/android_file_checks_stdout.log" 2>"$OUT_DIR/android_file_checks_stderr.log"; then
  if awk -F'\t' '$1 == "/sdcard/Android/data/com.example.playeragent/files" && $2 == "true" {found=1} END {exit found ? 0 : 1}' "$OUT_DIR/file_checks.tsv"; then
    record_required "App Data" "PASS" "$(elapsed_ms "$start")" "app external files accessible"
  else
    record_required "App Data" "FAIL" "$(elapsed_ms "$start")" "app external files unavailable"
  fi
  if awk -F'\t' '$1 == "/sdcard/Android/data/com.example.playeragent/files" && $4 != "ERROR" {found=1} END {exit found ? 0 : 1}' "$OUT_DIR/file_checks.tsv"; then
    record_required "Cache Dirs" "PASS" "0" "basic app files directory listable"
  else
    record_required "Cache Dirs" "FAIL" "0" "basic app files directory not listable"
  fi
else
  record_required "App Data" "FAIL" "$(elapsed_ms "$start")" "file checks command failed"
  record_required "Cache Dirs" "FAIL" "0" "file checks command failed"
fi

if [[ -f "$OUT_DIR/file_checks.tsv" ]]; then
  if awk -F'\t' '$1 ~ /QrcLyricCache$/ && $2 == "true" {found=1} END {exit found ? 0 : 1}' "$OUT_DIR/file_checks.tsv"; then
    record_optional "QRC Cache" "PASS" "0" "QrcLyricCache exists"
  else
    record_optional "QRC Cache" "WARN" "0" "QrcLyricCache not found or empty on this device"
  fi
  if awk -F'\t' '$1 == "/sdcard/QQMusic" && $2 == "true" || $1 == "/sdcard/qqmusic" && $2 == "true" {found=1} END {exit found ? 0 : 1}' "$OUT_DIR/file_checks.tsv"; then
    record_optional "QQMusic Dir" "PASS" "0" "QQMusic public directory exists"
  else
    record_optional "QQMusic Dir" "WARN" "0" "QQMusic public directory not found or inaccessible"
  fi
fi

start="$(now_ms)"
DEBUG_CONTROL_ENABLED="$DEBUG_CONTROL" \
  "$SCRIPT_DIR/android_ble_optional_test.sh" "$OUT_DIR/sony_logcat.log" \
  >"$OUT_DIR/android_ble_optional_stdout.log" \
  2>"$OUT_DIR/android_ble_optional_stderr.log" || true
ble_cost="$(elapsed_ms "$start")"
if [[ -s "$OUT_DIR/android_ble_optional_stdout.log" ]]; then
  awk -F'\t' -v cost="$ble_cost" 'BEGIN {OFS="\t"} {$4=cost; print $0}' "$OUT_DIR/android_ble_optional_stdout.log" >> "$OPTIONAL_RESULTS"
else
  record_optional "BLE Service" "WARN" "$ble_cost" "optional analyzer produced no result"
fi

start="$(now_ms)"
"$SCRIPT_DIR/android_playback_diff_flow_test.sh" "$OUT_DIR/sony_logcat.log" \
  >"$OUT_DIR/android_playback_diff_flow_stdout.log" \
  2>"$OUT_DIR/android_playback_diff_flow_stderr.log" || true
playback_diff_cost="$(elapsed_ms "$start")"
if [[ -s "$OUT_DIR/android_playback_diff_flow_stdout.log" ]]; then
  awk -F'\t' -v cost="$playback_diff_cost" 'BEGIN {OFS="\t"} {$4=cost; print $0}' "$OUT_DIR/android_playback_diff_flow_stdout.log" >> "$OPTIONAL_RESULTS"
else
  record_optional "PlaybackDiff Flow" "WARN" "$playback_diff_cost" "optional analyzer produced no result"
fi

python3 "$SCRIPT_DIR/generate_android_report.py" "$OUT_DIR" > "$OUT_DIR/report_path.txt"
REPORT_PATH="$(sed -n '1p' "$OUT_DIR/report_path.txt")"
REPORT_JSON="$(sed -n '2p' "$OUT_DIR/report_path.txt")"

if [[ "$JSON_OUTPUT" == true ]]; then
  python3 - "$REPORT_JSON" >&3 <<'PY'
import json
import sys
from pathlib import Path
path = Path(sys.argv[1])
data = json.loads(path.read_text(encoding="utf-8"))
print(json.dumps({
    "report_json": str(path),
    "report_md": data["artifacts"].get("report_md", ""),
    "summary": data["summary"],
}, ensure_ascii=False))
PY
else
  cat "$REPORT_PATH"
fi

if grep -q $'\tFAIL\t' "$REQUIRED_RESULTS"; then
  exit 1
fi
if grep -q $'\tFAIL\t' "$OPTIONAL_RESULTS"; then
  exit 1
fi
exit 0
