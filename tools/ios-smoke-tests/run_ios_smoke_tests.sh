#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
BUNDLE_ID="${BUNDLE_ID:-com.sqz.IOSBleFeasibility}"
SKIP_BUILD=false
SKIP_INSTALL=false
BLE_OPTIONAL=true

while [[ $# -gt 0 ]]; do
  case "$1" in
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
    *)
      echo "Unknown argument: $1" >&2
      exit 1
      ;;
  esac
done

timestamp="$(date +%Y%m%d_%H%M%S)"
OUT_DIR="${OUT_DIR:-/tmp/music_ble_ios_smoke/$timestamp}"
mkdir -p "$OUT_DIR"
REQUIRED_RESULTS="$OUT_DIR/required_results.tsv"
: > "$REQUIRED_RESULTS"

record_required() {
  local test="$1"
  local result="$2"
  local detail="$3"
  printf 'required\t%s\t%s\t%s\n' "$test" "$result" "$detail" >> "$REQUIRED_RESULTS"
}

find_device() {
  if [[ -n "${IOS_DEVICE_ID:-}" ]]; then
    echo "$IOS_DEVICE_ID"
    return
  fi
  local list_file="$OUT_DIR/devices.txt"
  xcrun devicectl list devices > "$list_file"
  local devices_file="$OUT_DIR/ios_devices_ids.txt"
  grep -i 'iPhone' "$list_file" |
    grep 'available' |
    grep -Eo '[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}' > "$devices_file" || true
  local device_count
  device_count="$(wc -l < "$devices_file" | tr -d ' ')"
  if [[ "$device_count" -eq 0 ]]; then
    echo "No available iPhone found. Set IOS_DEVICE_ID=..." >&2
    exit 1
  fi
  if [[ "$device_count" -gt 1 ]]; then
    echo "Multiple iPhones found. Set IOS_DEVICE_ID=..." >&2
    cat "$list_file" >&2
    exit 1
  fi
  head -n 1 "$devices_file"
}

IOS_DEVICE_ID="$(find_device)"
export ROOT_DIR OUT_DIR IOS_DEVICE_ID BUNDLE_ID

echo "iOS smoke output: $OUT_DIR"
echo "iPhone device: $IOS_DEVICE_ID"

if [[ "$SKIP_BUILD" == false || "$SKIP_INSTALL" == false ]]; then
  if SKIP_BUILD="$SKIP_BUILD" SKIP_INSTALL="$SKIP_INSTALL" "$SCRIPT_DIR/ios_build_install.sh"; then
    if [[ "$SKIP_BUILD" == true ]]; then
      record_required "iOS Build" "PASS" "skipped by request"
    else
      record_required "iOS Build" "PASS" "xcodebuild succeeded"
    fi
    if [[ "$SKIP_INSTALL" == true ]]; then
      record_required "iPhone Install" "PASS" "skipped by request"
    else
      record_required "iPhone Install" "PASS" "devicectl install succeeded"
    fi
  else
    record_required "iOS Build" "FAIL" "build/install command failed"
    record_required "iPhone Install" "FAIL" "build/install command failed"
  fi
else
  record_required "iOS Build" "PASS" "skipped by request"
  record_required "iPhone Install" "PASS" "skipped by request"
fi

if "$SCRIPT_DIR/ios_launch.sh"; then
  record_required "App Launch" "PASS" "devicectl launch succeeded"
else
  record_required "App Launch" "FAIL" "devicectl launch failed"
fi

if "$SCRIPT_DIR/ios_collect_logs.sh" ios_ble.log >/dev/null; then
  bytes="$(wc -c < "$OUT_DIR/ios_ble.log" | tr -d ' ')"
  record_required "Log File" "PASS" "ios_ble.log ${bytes} bytes"
else
  record_required "Log File" "FAIL" "ios_ble.log missing, empty, or missing expected keywords"
fi

if "$SCRIPT_DIR/ios_settings_test.sh"; then
  record_required "Preferences" "PASS" "mode=debug size=200 offset=300 autoReconnect=true"
else
  record_required "Preferences" "FAIL" "smoke-test preference logs missing"
fi

if "$SCRIPT_DIR/ios_file_checks.sh" > "$OUT_DIR/file_checks_stdout.txt"; then
  if grep -q $'Documents/Logs/ios_ble.log\ttrue' "$OUT_DIR/file_checks.tsv"; then
    record_required "File Checks" "PASS" "app container files accessible"
  else
    record_required "File Checks" "FAIL" "ios_ble.log not accessible in app container"
  fi
else
  record_required "File Checks" "FAIL" "file checks command failed"
fi

if [[ "$BLE_OPTIONAL" == true ]]; then
  "$SCRIPT_DIR/ios_ble_optional_test.sh" "$OUT_DIR/ios_ble_after_preferences_restart.log" >/dev/null || true
else
  : > "$OUT_DIR/optional_results.tsv"
fi

python3 "$SCRIPT_DIR/generate_ios_report.py" "$OUT_DIR" > "$OUT_DIR/report_path.txt"
REPORT_PATH="$(cat "$OUT_DIR/report_path.txt")"
cat "$REPORT_PATH"

if grep -q $'\trequired\t.*\tFAIL\t' "$REQUIRED_RESULTS"; then
  exit 1
fi
if grep -q $'\tFAIL\t' "$REQUIRED_RESULTS"; then
  exit 1
fi
exit 0
