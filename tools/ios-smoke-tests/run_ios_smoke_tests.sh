#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
BUNDLE_ID="${BUNDLE_ID:-com.sqz.IOSBleFeasibility}"
SKIP_BUILD=false
SKIP_INSTALL=false
BLE_OPTIONAL=true
JSON_OUTPUT=false
OUTPUT_DIR_ARG=""

usage() {
  cat <<'EOF'
Usage: run_ios_smoke_tests.sh [options]

Options:
  --quick              Skip build/install and run fast installed-app checks.
  --skip-build         Skip xcodebuild.
  --skip-install       Skip devicectl install.
  --no-ble-optional    Skip optional BLE log checks.
  --device <id>        Use a specific iPhone device id.
  --output <dir>       Write artifacts to a specific output directory.
  --json               Keep stdout machine-readable; write report.json path and summary.
  -h, --help           Show this help.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --quick)
      SKIP_BUILD=true
      SKIP_INSTALL=true
      echo "[Smoke] quick mode enabled" >&2
      echo "[Smoke] skip build/install" >&2
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
    --no-ble-optional)
      BLE_OPTIONAL=false
      shift
      ;;
    --device)
      IOS_DEVICE_ID="${2:?--device requires an id}"
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
  OUT_DIR="${OUT_DIR:-/tmp/music_ble_ios_smoke/$timestamp}"
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

log() {
  echo "[Smoke] $*" >&2
}

read_cost() {
  local key="$1"
  local file="$OUT_DIR/costs.env"
  if [[ -f "$file" ]]; then
    awk -F= -v key="$key" '$1 == key {print $2; found=1} END {if (!found) print "0"}' "$file"
  else
    echo "0"
  fi
}

find_device() {
  if [[ -n "${IOS_DEVICE_ID:-}" ]]; then
    printf '%s\t%s\t%s\t%s\n' "manual" "$IOS_DEVICE_ID" "manual" "manual" > "$OUT_DIR/device_info.tsv"
    echo "$IOS_DEVICE_ID"
    return
  fi

  local list_file="$OUT_DIR/devices.txt"
  local attempt
  local rc
  for attempt in 1 2 3; do
    xcrun devicectl list devices > "$list_file"
    set +e
    python3 - "$list_file" "$OUT_DIR/device_info.tsv" <<'PY'
import re
import sys
from pathlib import Path

list_path = Path(sys.argv[1])
info_path = Path(sys.argv[2])
uuid_re = re.compile(r"[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}")
rows = []
for raw in list_path.read_text(encoding="utf-8", errors="replace").splitlines():
    match = uuid_re.search(raw)
    if not match:
        continue
    identifier = match.group(0)
    left = raw[:match.start()].strip()
    right = raw[match.end():].strip()
    state = right.split("  ")[0].strip() if right else ""
    if "iPhone" not in raw or "available" not in state:
        continue
    name = left.split("  ")[0].strip() or "iPhone"
    model = right.split("   ")[-1].strip() if right else ""
    rows.append((name, identifier, state, model))

if len(rows) == 0:
    print("No connected/available iPhone found. Pass --device <id> or set IOS_DEVICE_ID.", file=sys.stderr)
    sys.exit(2)
if len(rows) > 1:
    print("Multiple connected/available iPhones found. Pass --device <id>.", file=sys.stderr)
    for row in rows:
        print(f"- name={row[0]} id={row[1]} state={row[2]} model={row[3]}", file=sys.stderr)
    sys.exit(3)

name, identifier, state, model = rows[0]
info_path.write_text(f"{name}\t{identifier}\t{state}\t{model}\n", encoding="utf-8")
print(identifier)
PY
    rc="$?"
    set -e
    if [[ "$rc" -eq 0 ]]; then
      return
    fi
    if [[ "$rc" -ne 2 ]]; then
      exit "$rc"
    fi
    echo "[Smoke] no iPhone found on attempt $attempt, retrying..." >&2
    sleep 2
  done
  echo "No connected/available iPhone found after retries. Pass --device <id> or set IOS_DEVICE_ID." >&2
  exit 2
}

if ! find_device > "$OUT_DIR/selected_device_id.txt"; then
  exit 1
fi
IOS_DEVICE_ID="$(tr -d '\n' < "$OUT_DIR/selected_device_id.txt")"
if [[ -z "$IOS_DEVICE_ID" ]]; then
  echo "No iPhone device id selected." >&2
  exit 1
fi
export ROOT_DIR OUT_DIR IOS_DEVICE_ID BUNDLE_ID

if [[ -f "$OUT_DIR/device_info.tsv" ]]; then
  device_name="$(awk -F'\t' 'NR==1 {print $1}' "$OUT_DIR/device_info.tsv")"
else
  device_name="unknown"
fi

log "output=$OUT_DIR"
log "detected device name=$device_name id=$IOS_DEVICE_ID"

if [[ "$SKIP_BUILD" == false || "$SKIP_INSTALL" == false ]]; then
  if SKIP_BUILD="$SKIP_BUILD" SKIP_INSTALL="$SKIP_INSTALL" "$SCRIPT_DIR/ios_build_install.sh" >"$OUT_DIR/ios_build_install_stdout.log" 2>"$OUT_DIR/ios_build_install_stderr.log"; then
    build_cost="$(read_cost buildCostMs)"
    install_cost="$(read_cost installCostMs)"
    if [[ "$SKIP_BUILD" == true ]]; then
      record_required "iOS Build" "PASS" "$build_cost" "skipped by request"
    else
      record_required "iOS Build" "PASS" "$build_cost" "xcodebuild succeeded"
    fi
    if [[ "$SKIP_INSTALL" == true ]]; then
      record_required "iPhone Install" "PASS" "$install_cost" "skipped by request"
    else
      record_required "iPhone Install" "PASS" "$install_cost" "devicectl install succeeded"
    fi
  else
    build_cost="$(read_cost buildCostMs)"
    install_cost="$(read_cost installCostMs)"
    record_required "iOS Build" "FAIL" "$build_cost" "build/install command failed"
    record_required "iPhone Install" "FAIL" "$install_cost" "build/install command failed"
  fi
else
  record_required "iOS Build" "PASS" "0" "skipped by request"
  record_required "iPhone Install" "PASS" "0" "skipped by request"
fi

start="$(now_ms)"
if "$SCRIPT_DIR/ios_launch.sh" >"$OUT_DIR/ios_launch_stdout.log" 2>"$OUT_DIR/ios_launch_stderr.log"; then
  record_required "App Launch" "PASS" "$(elapsed_ms "$start")" "devicectl launch succeeded"
else
  record_required "App Launch" "FAIL" "$(elapsed_ms "$start")" "devicectl launch failed"
fi

start="$(now_ms)"
if "$SCRIPT_DIR/ios_collect_logs.sh" ios_ble.log >"$OUT_DIR/ios_collect_logs_stdout.log" 2>"$OUT_DIR/ios_collect_logs_stderr.log"; then
  bytes="$(wc -c < "$OUT_DIR/ios_ble.log" | tr -d ' ')"
  record_required "Log File" "PASS" "$(elapsed_ms "$start")" "ios_ble.log ${bytes} bytes"
else
  record_required "Log File" "FAIL" "$(elapsed_ms "$start")" "ios_ble.log missing, empty, or missing expected keywords"
fi

start="$(now_ms)"
if "$SCRIPT_DIR/ios_settings_test.sh" >"$OUT_DIR/ios_settings_stdout.log" 2>"$OUT_DIR/ios_settings_stderr.log"; then
  record_required "Preferences" "PASS" "$(elapsed_ms "$start")" "mode=debug size=200 offset=300 autoReconnect=true"
else
  record_required "Preferences" "FAIL" "$(elapsed_ms "$start")" "smoke-test preference logs missing"
fi

start="$(now_ms)"
if "$SCRIPT_DIR/ios_file_checks.sh" > "$OUT_DIR/file_checks_stdout.txt" 2>"$OUT_DIR/file_checks_stderr.txt"; then
  if grep -q $'Documents/Logs/ios_ble.log\ttrue' "$OUT_DIR/file_checks.tsv"; then
    record_required "File Checks" "PASS" "$(elapsed_ms "$start")" "app container files accessible"
  else
    record_required "File Checks" "FAIL" "$(elapsed_ms "$start")" "ios_ble.log not accessible in app container"
  fi
else
  record_required "File Checks" "FAIL" "$(elapsed_ms "$start")" "file checks command failed"
fi

start="$(now_ms)"
if [[ "$BLE_OPTIONAL" == true ]]; then
  "$SCRIPT_DIR/ios_ble_optional_test.sh" "$OUT_DIR/ios_ble_after_preferences_restart.log" >"$OUT_DIR/ios_ble_optional_stdout.log" 2>"$OUT_DIR/ios_ble_optional_stderr.log" || true
else
  printf 'optional\tOptional BLE\tSKIPPED\t0\tdisabled by --no-ble-optional\n' > "$OPTIONAL_RESULTS"
fi
ble_optional_cost="$(elapsed_ms "$start")"
if [[ -s "$OPTIONAL_RESULTS" ]]; then
  awk -F'\t' -v cost="$ble_optional_cost" 'BEGIN {OFS="\t"} {if (NF == 4) print $1,$2,$3,cost,$4; else print $0}' "$OPTIONAL_RESULTS" > "$OPTIONAL_RESULTS.tmp"
  mv "$OPTIONAL_RESULTS.tmp" "$OPTIONAL_RESULTS"
fi

python3 "$SCRIPT_DIR/generate_ios_report.py" "$OUT_DIR" > "$OUT_DIR/report_path.txt"
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
exit 0
