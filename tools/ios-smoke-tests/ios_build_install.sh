#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="${ROOT_DIR:-$(cd "$(dirname "$0")/../.." && pwd)}"
OUT_DIR="${OUT_DIR:-/tmp/music_ble_ios_smoke/manual}"
IOS_DEVICE_ID="${IOS_DEVICE_ID:?IOS_DEVICE_ID is required}"
PROJECT_PATH="$ROOT_DIR/IOSBleFeasibility/IOSBleFeasibility.xcodeproj"
SCHEME="sonyMusic"
CONFIGURATION="Debug"
SKIP_BUILD="${SKIP_BUILD:-false}"
SKIP_INSTALL="${SKIP_INSTALL:-false}"

mkdir -p "$OUT_DIR"
COST_FILE="$OUT_DIR/costs.env"
: > "$COST_FILE"

now_ms() {
  python3 - <<'PY'
import time
print(int(time.time() * 1000))
PY
}

if [[ "$SKIP_BUILD" != true ]]; then
  build_start="$(now_ms)"
  xcodebuild \
    -project "$PROJECT_PATH" \
    -scheme "$SCHEME" \
    -configuration "$CONFIGURATION" \
    -destination 'generic/platform=iOS' \
    build | tee "$OUT_DIR/ios_build.log"
  echo "buildCostMs=$(( $(now_ms) - build_start ))" >> "$COST_FILE"
else
  echo "Build skipped" | tee "$OUT_DIR/ios_build.log"
  echo "buildCostMs=0" >> "$COST_FILE"
fi

BUILD_SETTINGS="$OUT_DIR/xcode_build_settings.txt"
xcodebuild \
  -project "$PROJECT_PATH" \
  -scheme "$SCHEME" \
  -configuration "$CONFIGURATION" \
  -destination 'generic/platform=iOS' \
  -showBuildSettings > "$BUILD_SETTINGS"

TARGET_BUILD_DIR="$(awk -F'= ' '/TARGET_BUILD_DIR =/ {print $2; exit}' "$BUILD_SETTINGS")"
WRAPPER_NAME="$(awk -F'= ' '/WRAPPER_NAME =/ {print $2; exit}' "$BUILD_SETTINGS")"
APP_PATH="$TARGET_BUILD_DIR/$WRAPPER_NAME"

if [[ ! -d "$APP_PATH" ]]; then
  echo "Built app not found: $APP_PATH" >&2
  exit 1
fi

echo "$APP_PATH" > "$OUT_DIR/app_path.txt"

if [[ "$SKIP_INSTALL" != true ]]; then
  install_start="$(now_ms)"
  xcrun devicectl device install app \
    --device "$IOS_DEVICE_ID" \
    "$APP_PATH" | tee "$OUT_DIR/ios_install.log"
  echo "installCostMs=$(( $(now_ms) - install_start ))" >> "$COST_FILE"
else
  echo "Install skipped" | tee "$OUT_DIR/ios_install.log"
  echo "installCostMs=0" >> "$COST_FILE"
fi
