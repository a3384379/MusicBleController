#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="${ROOT_DIR:-$(cd "$SCRIPT_DIR/../.." && pwd)}"
PROJECT_PATH="${PROJECT_PATH:-$ROOT_DIR/IOSBleFeasibility/IOSBleFeasibility.xcodeproj}"
SCHEME="${SCHEME:-sonyMusic}"
CONFIGURATION="${CONFIGURATION:-Debug}"
BUNDLE_ID="${BUNDLE_ID:-com.sqz.IOSBleFeasibility}"
OUT_DIR="${OUT_DIR:-/tmp/music_ble_deploy/$(date +%Y%m%d_%H%M%S)}"
IOS_DEVICE_ID="${IOS_DEVICE_ID:-}"
FORCE_REINSTALL=false

usage() {
  cat <<'EOF'
Usage: ios_deploy.sh [options]

Build, install, launch, and smoke-test the iOS Debug app.

Options:
  --device <IOS_DEVICE_ID>  Use a specific iPhone device id.
  --output <dir>            Write logs/artifacts to a specific directory.
  --force-reinstall         If install fails, uninstall once and retry install.
  -h, --help                Show this help.

Environment overrides:
  ROOT_DIR PROJECT_PATH SCHEME CONFIGURATION BUNDLE_ID OUT_DIR IOS_DEVICE_ID
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --device)
      IOS_DEVICE_ID="${2:?--device requires an id}"
      shift 2
      ;;
    --output)
      OUT_DIR="${2:?--output requires a directory}"
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

mkdir -p "$OUT_DIR"

find_device() {
  if [[ -n "$IOS_DEVICE_ID" ]]; then
    echo "$IOS_DEVICE_ID"
    return 0
  fi

  local list_file="$OUT_DIR/devices.txt"
  if ! xcrun devicectl list devices >"$list_file" 2>"$OUT_DIR/devices.err"; then
    echo "Unable to list iOS devices. See $OUT_DIR/devices.err" >&2
    return 1
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
    print("No connected/available iPhone found. Pass --device <id> or set IOS_DEVICE_ID.", file=sys.stderr)
    sys.exit(2)

print("Multiple connected/available iPhones found. Pass --device <id>.", file=sys.stderr)
for name, identifier, state in rows:
    print(f"- name={name} id={identifier} state={state}", file=sys.stderr)
sys.exit(3)
PY
}

IOS_DEVICE_ID="$(find_device)"
export ROOT_DIR OUT_DIR IOS_DEVICE_ID BUNDLE_ID

echo "[Deploy] output=$OUT_DIR"
echo "[Deploy] device=$IOS_DEVICE_ID"
echo "[Deploy] project=$PROJECT_PATH scheme=$SCHEME configuration=$CONFIGURATION"

xcodebuild \
  -allowProvisioningUpdates \
  -project "$PROJECT_PATH" \
  -scheme "$SCHEME" \
  -configuration "$CONFIGURATION" \
  -destination 'generic/platform=iOS' \
  build | tee "$OUT_DIR/xcodebuild.log"

BUILD_SETTINGS="$OUT_DIR/xcode_build_settings.txt"
xcodebuild \
  -project "$PROJECT_PATH" \
  -scheme "$SCHEME" \
  -configuration "$CONFIGURATION" \
  -destination 'generic/platform=iOS' \
  -showBuildSettings >"$BUILD_SETTINGS"

TARGET_BUILD_DIR="$(awk -F'= ' '/TARGET_BUILD_DIR =/ {print $2; exit}' "$BUILD_SETTINGS")"
WRAPPER_NAME="$(awk -F'= ' '/WRAPPER_NAME =/ {print $2; exit}' "$BUILD_SETTINGS")"
APP_PATH="$TARGET_BUILD_DIR/$WRAPPER_NAME"

if [[ ! -d "$APP_PATH" ]]; then
  echo "Built app not found: $APP_PATH" >&2
  exit 1
fi

echo "$APP_PATH" >"$OUT_DIR/app_path.txt"
echo "[Deploy] app=$APP_PATH"

set +e
xcrun devicectl device install app \
  --device "$IOS_DEVICE_ID" \
  "$APP_PATH" > >(tee "$OUT_DIR/install.log") 2> >(tee "$OUT_DIR/install.err" >&2)
install_rc="$?"
set -e

if [[ "$install_rc" -ne 0 ]]; then
  if [[ "$FORCE_REINSTALL" != true ]]; then
    echo "[Deploy] install failed. Re-run with --force-reinstall to uninstall once and retry." >&2
    exit "$install_rc"
  fi

  echo "[Deploy] install failed; --force-reinstall was provided, uninstalling $BUNDLE_ID once before retry." >&2
  xcrun devicectl device uninstall app \
    --device "$IOS_DEVICE_ID" \
    "$BUNDLE_ID" > >(tee "$OUT_DIR/uninstall.log") 2> >(tee "$OUT_DIR/uninstall.err" >&2)

  xcrun devicectl device install app \
    --device "$IOS_DEVICE_ID" \
    "$APP_PATH" > >(tee "$OUT_DIR/install_retry.log") 2> >(tee "$OUT_DIR/install_retry.err" >&2)
fi

xcrun devicectl device process launch \
  --device "$IOS_DEVICE_ID" \
  --terminate-existing \
  "$BUNDLE_ID" | tee "$OUT_DIR/launch.log"

IOS_DEVICE_ID="$IOS_DEVICE_ID" BUNDLE_ID="$BUNDLE_ID" \
  "$ROOT_DIR/tools/ios-smoke-tests/codex_check.sh" | tee "$OUT_DIR/smoke.log"

echo "[Deploy] PASS"
