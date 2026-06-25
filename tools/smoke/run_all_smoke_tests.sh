#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
IOS_SMOKE="$ROOT_DIR/tools/ios-smoke-tests/run_ios_smoke_tests.sh"
ANDROID_SMOKE="$ROOT_DIR/tools/android-smoke-tests/run_android_smoke_tests.sh"
QUICK=false
JSON_OUTPUT=false
SKIP_IOS=false
SKIP_ANDROID=false
IOS_EXPLICIT=false
ANDROID_EXPLICIT=false
OUTPUT_DIR_ARG=""
IOS_DEVICE_ARG=""
ANDROID_DEVICE_ARG=""

usage() {
  cat <<'EOF'
Usage: run_all_smoke_tests.sh [options]

Options:
  --quick                Pass --quick to iOS and Android smoke suites.
  --json                 Print machine-readable summary to stdout.
  --ios-only             Run only iOS smoke; fail if no iPhone is available.
  --android-only         Run only Android/Sony smoke; fail if no adb device is available.
  --skip-ios             Skip iOS smoke.
  --skip-android         Skip Android/Sony smoke.
  --ios-device <id>      Pass a specific iPhone id to iOS smoke.
  --android-device <id>  Pass a specific adb serial to Android smoke.
  --output <dir>         Write artifacts to a specific output directory.
  -h, --help             Show this help.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --quick)
      QUICK=true
      shift
      ;;
    --json)
      JSON_OUTPUT=true
      shift
      ;;
    --ios-only)
      SKIP_ANDROID=true
      IOS_EXPLICIT=true
      shift
      ;;
    --android-only)
      SKIP_IOS=true
      ANDROID_EXPLICIT=true
      shift
      ;;
    --skip-ios)
      SKIP_IOS=true
      shift
      ;;
    --skip-android)
      SKIP_ANDROID=true
      shift
      ;;
    --ios-device)
      IOS_DEVICE_ARG="${2:?--ios-device requires an id}"
      IOS_EXPLICIT=true
      shift 2
      ;;
    --android-device)
      ANDROID_DEVICE_ARG="${2:?--android-device requires an id}"
      ANDROID_EXPLICIT=true
      shift 2
      ;;
    --output)
      OUTPUT_DIR_ARG="${2:?--output requires a directory}"
      shift 2
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

if [[ "$SKIP_IOS" == true && "$SKIP_ANDROID" == true ]]; then
  echo "Both iOS and Android suites are skipped; nothing to run." >&2
  exit 1
fi

timestamp="$(date +%Y%m%d_%H%M%S)"
if [[ -n "$OUTPUT_DIR_ARG" ]]; then
  OUT_DIR="$OUTPUT_DIR_ARG"
else
  OUT_DIR="${OUT_DIR:-/tmp/music_ble_smoke/$timestamp}"
fi
mkdir -p "$OUT_DIR"
export ROOT_DIR OUT_DIR

if [[ "$JSON_OUTPUT" == true ]]; then
  exec 3>&1
  exec >"$OUT_DIR/smoke_stdout.log"
else
  exec 3>&1
fi

log() {
  echo "[SmokeAll] $*" >&2
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
  local out="$OUT_DIR/devicectl_devices.txt"
  if [[ -n "$IOS_DEVICE_ARG" ]]; then
    echo "$IOS_DEVICE_ARG" > "$OUT_DIR/ios_detected_device.txt"
    return 0
  fi
  if ! command -v xcrun >/dev/null 2>&1; then
    echo "xcrun not found" > "$OUT_DIR/ios_detect_reason.txt"
    return 1
  fi
  if ! xcrun devicectl list devices > "$out" 2>"$OUT_DIR/devicectl_list_stderr.log"; then
    echo "devicectl list failed" > "$OUT_DIR/ios_detect_reason.txt"
    return 1
  fi
  python3 - "$out" "$OUT_DIR/ios_detected_device.txt" "$OUT_DIR/ios_detect_reason.txt" <<'PY'
import re
import sys
from pathlib import Path

list_path = Path(sys.argv[1])
device_path = Path(sys.argv[2])
reason_path = Path(sys.argv[3])
uuid_re = re.compile(r"[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}")
rows = []
for raw in list_path.read_text(encoding="utf-8", errors="replace").splitlines():
    match = uuid_re.search(raw)
    if not match:
        continue
    if "iPhone" not in raw:
        continue
    right = raw[match.end():].strip()
    state = right.split("  ")[0].strip() if right else ""
    if "available" not in state:
        continue
    rows.append(match.group(0))
if len(rows) == 1:
    device_path.write_text(rows[0] + "\n", encoding="utf-8")
    sys.exit(0)
if not rows:
    reason_path.write_text("no available iPhone found\n", encoding="utf-8")
else:
    reason_path.write_text("multiple available iPhones found; pass --ios-device\n", encoding="utf-8")
sys.exit(1)
PY
}

detect_android() {
  if [[ -n "$ANDROID_DEVICE_ARG" ]]; then
    echo "$ANDROID_DEVICE_ARG" > "$OUT_DIR/android_detected_device.txt"
    return 0
  fi
  local adb_bin
  if ! adb_bin="$(find_adb)"; then
    echo "adb not found" > "$OUT_DIR/android_detect_reason.txt"
    return 1
  fi
  ADB_BIN="$adb_bin" "$adb_bin" devices -l > "$OUT_DIR/adb_devices.txt"
  python3 - "$OUT_DIR/adb_devices.txt" "$OUT_DIR/android_detected_device.txt" "$OUT_DIR/android_detect_reason.txt" <<'PY'
import sys
from pathlib import Path

devices_path = Path(sys.argv[1])
device_path = Path(sys.argv[2])
reason_path = Path(sys.argv[3])
rows = []
unauthorized = []
for raw in devices_path.read_text(encoding="utf-8", errors="replace").splitlines():
    line = raw.strip()
    if not line or line.startswith("List of devices"):
        continue
    parts = line.split()
    if len(parts) < 2:
        continue
    if parts[1] == "device":
        rows.append(parts[0])
    elif parts[1] == "unauthorized":
        unauthorized.append(parts[0])
if len(rows) == 1:
    device_path.write_text(rows[0] + "\n", encoding="utf-8")
    sys.exit(0)
if unauthorized and not rows:
    reason_path.write_text("adb device unauthorized\n", encoding="utf-8")
elif not rows:
    reason_path.write_text("no online adb device found\n", encoding="utf-8")
else:
    reason_path.write_text("multiple adb devices found; pass --android-device\n", encoding="utf-8")
sys.exit(1)
PY
}

write_suite_status() {
  local suite="$1"
  local result="$2"
  local reason="$3"
  local report_json="$4"
  local report_md="$5"
  python3 - "$OUT_DIR/${suite}_suite_status.json" "$suite" "$result" "$reason" "$report_json" "$report_md" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
payload = {
    "suite": sys.argv[2],
    "result": sys.argv[3],
    "reason": sys.argv[4],
    "report_json": sys.argv[5],
    "report_md": sys.argv[6],
}
path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
PY
}

IOS_AVAILABLE=false
ANDROID_AVAILABLE=false
if [[ "$SKIP_IOS" != true ]] && detect_ios; then
  IOS_AVAILABLE=true
fi
if [[ "$SKIP_ANDROID" != true ]] && detect_android; then
  ANDROID_AVAILABLE=true
fi

if [[ "$SKIP_IOS" != true && "$IOS_AVAILABLE" != true && "$IOS_EXPLICIT" == true ]]; then
  reason="$(cat "$OUT_DIR/ios_detect_reason.txt" 2>/dev/null || echo "iPhone unavailable")"
  write_suite_status "ios" "FAIL" "$reason" "" ""
elif [[ "$SKIP_IOS" == true ]]; then
  write_suite_status "ios" "SKIPPED" "skipped by request" "" ""
elif [[ "$IOS_AVAILABLE" != true ]]; then
  reason="$(cat "$OUT_DIR/ios_detect_reason.txt" 2>/dev/null || echo "iPhone unavailable")"
  write_suite_status "ios" "SKIPPED" "$reason" "" ""
fi

if [[ "$SKIP_ANDROID" != true && "$ANDROID_AVAILABLE" != true && "$ANDROID_EXPLICIT" == true ]]; then
  reason="$(cat "$OUT_DIR/android_detect_reason.txt" 2>/dev/null || echo "Android device unavailable")"
  write_suite_status "android" "FAIL" "$reason" "" ""
elif [[ "$SKIP_ANDROID" == true ]]; then
  write_suite_status "android" "SKIPPED" "skipped by request" "" ""
elif [[ "$ANDROID_AVAILABLE" != true ]]; then
  reason="$(cat "$OUT_DIR/android_detect_reason.txt" 2>/dev/null || echo "Android device unavailable")"
  write_suite_status "android" "SKIPPED" "$reason" "" ""
fi

if [[ "$IOS_AVAILABLE" != true && "$ANDROID_AVAILABLE" != true ]]; then
  if [[ "$IOS_EXPLICIT" != true && "$ANDROID_EXPLICIT" != true ]]; then
    write_suite_status "ios" "SKIPPED" "$(cat "$OUT_DIR/ios_detect_reason.txt" 2>/dev/null || echo "iPhone unavailable")" "" ""
    write_suite_status "android" "SKIPPED" "$(cat "$OUT_DIR/android_detect_reason.txt" 2>/dev/null || echo "Android device unavailable")" "" ""
  fi
fi

run_child() {
  local suite="$1"
  shift
  local child_dir="$OUT_DIR/$suite"
  mkdir -p "$child_dir"
  set +e
  "$@" >"$OUT_DIR/${suite}_stdout.json" 2>"$OUT_DIR/${suite}_stderr.log"
  local rc="$?"
  set -e
  local report_json="$child_dir/report.json"
  local report_md="$child_dir/report.md"
  if [[ -f "$report_json" ]]; then
    local result
    result="$(python3 - "$report_json" <<'PY'
import json, sys
from pathlib import Path
data = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
print(data.get("summary", {}).get("overall_result", "FAIL"))
PY
)"
    if [[ "$rc" -ne 0 && "$result" == "PASS" ]]; then
      result="FAIL"
    fi
    write_suite_status "$suite" "$result" "suite exited rc=$rc" "$report_json" "$report_md"
  else
    write_suite_status "$suite" "FAIL" "suite exited rc=$rc without report.json" "" ""
  fi
}

if [[ "$IOS_AVAILABLE" == true ]]; then
  ios_args=("$IOS_SMOKE" "--json" "--output" "$OUT_DIR/ios")
  if [[ "$QUICK" == true ]]; then
    ios_args+=("--quick")
  fi
  if [[ -n "$IOS_DEVICE_ARG" ]]; then
    ios_args+=("--device" "$IOS_DEVICE_ARG")
  fi
  log "running iOS smoke"
  run_child "ios" "${ios_args[@]}"
fi

if [[ "$ANDROID_AVAILABLE" == true ]]; then
  android_args=("$ANDROID_SMOKE" "--json" "--output" "$OUT_DIR/android")
  if [[ "$QUICK" == true ]]; then
    android_args+=("--quick")
  fi
  if [[ -n "$ANDROID_DEVICE_ARG" ]]; then
    android_args+=("--device" "$ANDROID_DEVICE_ARG")
  fi
  log "running Android smoke"
  run_child "android" "${android_args[@]}"
fi

python3 "$SCRIPT_DIR/generate_all_report.py" "$OUT_DIR" > "$OUT_DIR/report_path.txt"
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

overall="$(python3 - "$REPORT_JSON" <<'PY'
import json, sys
from pathlib import Path
print(json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))["summary"]["overall_result"])
PY
)"
if [[ "$overall" == "FAIL" ]]; then
  exit 1
fi
exit 0
