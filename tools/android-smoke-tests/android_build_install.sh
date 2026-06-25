#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="${ROOT_DIR:?ROOT_DIR is required}"
OUT_DIR="${OUT_DIR:?OUT_DIR is required}"
ADB_BIN="${ADB_BIN:?ADB_BIN is required}"
DEVICE_ID="${DEVICE_ID:-}"
SKIP_BUILD="${SKIP_BUILD:-false}"
SKIP_INSTALL="${SKIP_INSTALL:-false}"

if [[ -z "${JAVA_HOME:-}" ]]; then
  bundled_jbr="/Volumes/雷电/Android Studio.app/Contents/jbr/Contents/Home"
  if [[ -d "$bundled_jbr" ]]; then
    export JAVA_HOME="$bundled_jbr"
    export PATH="$JAVA_HOME/bin:$PATH"
  fi
fi

echo "JAVA_HOME=${JAVA_HOME:-unset}"

if [[ "$SKIP_BUILD" != true ]]; then
  if [[ -x "$ROOT_DIR/gradlew" ]]; then
    (cd "$ROOT_DIR" && ./gradlew :PlayerAgentApp:assembleDebug)
  else
    (cd "$ROOT_DIR" && bash ./gradlew :PlayerAgentApp:assembleDebug)
  fi
fi

apk="$ROOT_DIR/PlayerAgentApp/build/outputs/apk/debug/PlayerAgentApp-debug.apk"
if [[ ! -f "$apk" ]]; then
  echo "APK not found: $apk" >&2
  exit 1
fi
echo "$apk" > "$OUT_DIR/apk_path.txt"

if [[ "$SKIP_INSTALL" != true ]]; then
  if [[ -z "$DEVICE_ID" ]]; then
    echo "DEVICE_ID is required for install" >&2
    exit 1
  fi
  "$ADB_BIN" -s "$DEVICE_ID" install -r "$apk"
fi
