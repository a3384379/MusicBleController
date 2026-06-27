#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="${ROOT_DIR:-$(cd "$SCRIPT_DIR/../.." && pwd)}"
PROJECT_PATH="${PROJECT_PATH:-$ROOT_DIR/IOSBleFeasibility/IOSBleFeasibility.xcodeproj}"
SCHEME="${SCHEME:-sonyMusic}"
CONFIGURATION="${CONFIGURATION:-Debug}"
BUNDLE_ID="${BUNDLE_ID:-com.sqz.IOSBleFeasibility}"
STATE_FILE="${STATE_FILE:-$SCRIPT_DIR/last_deploy.json}"
OUT_ROOT="${OUT_ROOT:-/tmp/music_ble_deploy}"
IOS_DEVICE_ID="${IOS_DEVICE_ID:-}"
THRESHOLD_HOURS=24
FORCE=false
FORCE_REINSTALL=false

usage() {
  cat <<'EOF'
Usage: ios_reinstall_if_needed.sh [options]

Checks the iOS Debug app provisioning profile and redeploys when needed.

Options:
  --force                 Build/install/launch/smoke regardless of expiry.
  --threshold-hours <n>   Redeploy when remaining profile life is below n hours. Default: 24.
  --device <IOS_DEVICE_ID>
                          Use a specific iPhone device id.
  --force-reinstall       Pass through to ios_deploy.sh; uninstall only after install fails.
  -h, --help              Show this help.

Environment overrides:
  ROOT_DIR PROJECT_PATH SCHEME CONFIGURATION BUNDLE_ID STATE_FILE OUT_ROOT IOS_DEVICE_ID
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --force)
      FORCE=true
      shift
      ;;
    --threshold-hours)
      THRESHOLD_HOURS="${2:?--threshold-hours requires a number}"
      shift 2
      ;;
    --device)
      IOS_DEVICE_ID="${2:?--device requires an id}"
      shift 2
      ;;
    --force-reinstall)
      FORCE_REINSTALL=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 64
      ;;
  esac
done

python3 - "$THRESHOLD_HOURS" <<'PY'
import sys
try:
    value = float(sys.argv[1])
except ValueError:
    print("--threshold-hours must be numeric", file=sys.stderr)
    sys.exit(1)
if value < 0:
    print("--threshold-hours must be >= 0", file=sys.stderr)
    sys.exit(1)
PY

RUN_DIR="$OUT_ROOT/$(date +%Y%m%d_%H%M%S)"
mkdir -p "$RUN_DIR" "$(dirname "$STATE_FILE")"

LAST_RUN_TIME="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
PROFILE_EXPIRE_TIME=""
DAYS_REMAINING=""
DEPLOY_EXECUTED=false
RESULT="UNKNOWN"
REASON=""

write_state() {
  local result="$1"
  local reason="$2"
  DEPLOY_EXECUTED="${3:-$DEPLOY_EXECUTED}"
  python3 - "$STATE_FILE" "$LAST_RUN_TIME" "$PROFILE_EXPIRE_TIME" "$DAYS_REMAINING" "$DEPLOY_EXECUTED" "$result" "$reason" "$RUN_DIR" "$IOS_DEVICE_ID" <<'PY'
import json
import sys
from pathlib import Path

state_file, last_run, expire_time, days_remaining, deploy_executed, result, reason, run_dir, device_id = sys.argv[1:]

def nullable(value):
    return None if value == "" else value

def nullable_float(value):
    if value == "":
        return None
    return round(float(value), 4)

data = {
    "lastRunTime": last_run,
    "profileExpireTime": nullable(expire_time),
    "daysRemaining": nullable_float(days_remaining),
    "deployExecuted": deploy_executed == "true",
    "result": result,
    "reason": reason,
    "runDir": run_dir,
    "deviceId": nullable(device_id),
}
Path(state_file).write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
print(json.dumps(data, indent=2, ensure_ascii=False))
PY
}

find_device() {
  if [[ -n "$IOS_DEVICE_ID" ]]; then
    local details_json="$RUN_DIR/device_details.json"
    if xcrun devicectl --timeout 20 device info details \
      --device "$IOS_DEVICE_ID" \
      --json-output "$details_json" >"$RUN_DIR/device_details.log" 2>"$RUN_DIR/device_details.err"; then
      echo "$IOS_DEVICE_ID"
      return 0
    fi
    echo "Requested iPhone is not available: $IOS_DEVICE_ID" >&2
    return 2
  fi

  local list_file="$RUN_DIR/devices.txt"
  if ! xcrun devicectl --timeout 20 list devices >"$list_file" 2>"$RUN_DIR/devices.err"; then
    echo "Unable to list iOS devices." >&2
    return 2
  fi

  python3 - "$list_file" <<'PY'
import re
import sys
from pathlib import Path

rows = []
uuid_re = re.compile(r"[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}")
for raw in Path(sys.argv[1]).read_text(encoding="utf-8", errors="replace").splitlines():
    match = uuid_re.search(raw)
    if not match or "iPhone" not in raw:
        continue
    right = raw[match.end():].strip()
    state = right.split("  ")[0].strip() if right else ""
    if "available" not in state:
        continue
    name = raw[:match.start()].strip().split("  ")[0].strip() or "iPhone"
    rows.append((name, match.group(0), state))

if len(rows) == 1:
    print(rows[0][1])
    sys.exit(0)
if len(rows) == 0:
    print("No connected/available iPhone found.", file=sys.stderr)
    sys.exit(2)

print("Multiple connected/available iPhones found. Pass --device <id>.", file=sys.stderr)
for name, identifier, state in rows:
    print(f"- name={name} id={identifier} state={state}", file=sys.stderr)
sys.exit(3)
PY
}

resolve_app_path() {
  local build_settings="$RUN_DIR/xcode_build_settings.txt"
  if ! xcodebuild \
    -project "$PROJECT_PATH" \
    -scheme "$SCHEME" \
    -configuration "$CONFIGURATION" \
    -destination 'generic/platform=iOS' \
    -showBuildSettings >"$build_settings" 2>"$RUN_DIR/xcode_build_settings.err"; then
    return 1
  fi

  local target_build_dir
  local wrapper_name
  target_build_dir="$(awk -F'= ' '/TARGET_BUILD_DIR =/ {print $2; exit}' "$build_settings")"
  wrapper_name="$(awk -F'= ' '/WRAPPER_NAME =/ {print $2; exit}' "$build_settings")"
  if [[ -z "$target_build_dir" || -z "$wrapper_name" ]]; then
    return 1
  fi
  printf '%s/%s\n' "$target_build_dir" "$wrapper_name"
}

read_profile_expiry() {
  local profile="$1"
  local decoded="$RUN_DIR/embedded.mobileprovision.plist"
  if [[ ! -r "$profile" ]]; then
    return 1
  fi
  if ! security cms -D -i "$profile" >"$decoded" 2>"$RUN_DIR/security_cms.err"; then
    return 1
  fi
  python3 - "$decoded" <<'PY'
import datetime as dt
import plistlib
import sys
from pathlib import Path

with Path(sys.argv[1]).open("rb") as f:
    data = plistlib.load(f)

expire = data.get("ExpirationDate")
if not isinstance(expire, dt.datetime):
    sys.exit(1)
if expire.tzinfo is None:
    expire = expire.replace(tzinfo=dt.timezone.utc)
else:
    expire = expire.astimezone(dt.timezone.utc)
now = dt.datetime.now(dt.timezone.utc)
days = (expire - now).total_seconds() / 86400
print(expire.isoformat().replace("+00:00", "Z"))
print(f"{days:.6f}")
PY
}

is_app_installed() {
  local apps_json="$RUN_DIR/apps.json"
  local apps_log="$RUN_DIR/apps.log"
  if ! xcrun devicectl --timeout 30 device info apps \
    --device "$IOS_DEVICE_ID" \
    --bundle-id "$BUNDLE_ID" \
    --json-output "$apps_json" >"$apps_log" 2>"$RUN_DIR/apps.err"; then
    return 2
  fi

  python3 - "$apps_json" "$apps_log" "$BUNDLE_ID" <<'PY'
import json
import sys
from pathlib import Path

json_path, log_path, bundle_id = sys.argv[1:]

def walk(value):
    if isinstance(value, dict):
        for key, item in value.items():
            normalized_key = key.replace("_", "").replace("-", "").lower()
            bundle_keys = {
                "bundleid",
                "bundleidentifier",
                "cfbundleidentifier",
                "applicationbundleid",
                "applicationbundleidentifier",
            }
            if isinstance(item, str) and item == bundle_id and normalized_key in bundle_keys:
                return True
            if walk(item):
                return True
    elif isinstance(value, list):
        return any(walk(item) for item in value)
    return False

try:
    data = json.loads(Path(json_path).read_text(encoding="utf-8"))
    if walk(data):
        sys.exit(0)
except Exception:
    pass

if bundle_id in Path(log_path).read_text(encoding="utf-8", errors="replace"):
    sys.exit(0)

sys.exit(1)
PY
}

APP_PATH=""
PROFILE_PATH=""
if APP_PATH="$(resolve_app_path)"; then
  PROFILE_PATH="$APP_PATH/embedded.mobileprovision"
  if profile_output="$(read_profile_expiry "$PROFILE_PATH")"; then
    PROFILE_EXPIRE_TIME="$(sed -n '1p' <<<"$profile_output")"
    DAYS_REMAINING="$(sed -n '2p' <<<"$profile_output")"
  fi
fi

if ! SELECTED_DEVICE="$(find_device)"; then
  RESULT="SKIPPED"
  REASON="iPhone is not connected or not available"
  write_state "$RESULT" "$REASON" false
  exit 0
fi
IOS_DEVICE_ID="$SELECTED_DEVICE"
export IOS_DEVICE_ID

APP_INSTALLED=false
if is_app_installed; then
  APP_INSTALLED=true
else
  app_rc="$?"
  if [[ "$app_rc" -eq 2 ]]; then
    RESULT="SKIPPED"
    REASON="Unable to query installed apps; iPhone may be disconnected or unavailable"
    write_state "$RESULT" "$REASON" false
    exit 0
  fi
fi

SHOULD_DEPLOY=false
if [[ "$FORCE" == true ]]; then
  SHOULD_DEPLOY=true
  REASON="forced"
elif [[ "$APP_INSTALLED" != true ]]; then
  SHOULD_DEPLOY=true
  REASON="app is not installed"
elif [[ -z "$PROFILE_EXPIRE_TIME" || -z "$DAYS_REMAINING" ]]; then
  SHOULD_DEPLOY=true
  REASON="embedded.mobileprovision is missing or unreadable"
else
  if python3 - "$DAYS_REMAINING" "$THRESHOLD_HOURS" <<'PY'
import sys
days = float(sys.argv[1])
threshold_hours = float(sys.argv[2])
sys.exit(0 if days * 24 < threshold_hours else 1)
PY
  then
    SHOULD_DEPLOY=true
    REASON="profile expires within ${THRESHOLD_HOURS} hours"
  else
    REASON="profile is still valid"
  fi
fi

if [[ "$SHOULD_DEPLOY" != true ]]; then
  RESULT="SKIPPED"
  write_state "$RESULT" "$REASON" false
  exit 0
fi

DEPLOY_EXECUTED=true
deploy_args=(--device "$IOS_DEVICE_ID" --output "$RUN_DIR/deploy")
if [[ "$FORCE_REINSTALL" == true ]]; then
  deploy_args+=(--force-reinstall)
fi

if "$SCRIPT_DIR/ios_deploy.sh" "${deploy_args[@]}"; then
  if [[ -n "$APP_PATH" && -r "$PROFILE_PATH" ]]; then
    if profile_output="$(read_profile_expiry "$PROFILE_PATH")"; then
      PROFILE_EXPIRE_TIME="$(sed -n '1p' <<<"$profile_output")"
      DAYS_REMAINING="$(sed -n '2p' <<<"$profile_output")"
    fi
  fi
  RESULT="PASS"
  write_state "$RESULT" "$REASON" true
  exit 0
fi

RESULT="FAIL"
write_state "$RESULT" "$REASON" true
exit 1
