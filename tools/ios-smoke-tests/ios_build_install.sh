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

if [[ "$SKIP_BUILD" != true ]]; then
  xcodebuild \
    -project "$PROJECT_PATH" \
    -scheme "$SCHEME" \
    -configuration "$CONFIGURATION" \
    -destination 'generic/platform=iOS' \
    build | tee "$OUT_DIR/ios_build.log"
else
  echo "Build skipped" | tee "$OUT_DIR/ios_build.log"
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
  xcrun devicectl device install app \
    --device "$IOS_DEVICE_ID" \
    "$APP_PATH" | tee "$OUT_DIR/ios_install.log"
else
  echo "Install skipped" | tee "$OUT_DIR/ios_install.log"
fi
