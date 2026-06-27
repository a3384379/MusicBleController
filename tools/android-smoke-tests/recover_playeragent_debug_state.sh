#!/usr/bin/env bash
set -euo pipefail

PACKAGE_NAME="${PACKAGE_NAME:-com.example.playeragent}"
ACTIVITY_NAME="${ACTIVITY_NAME:-com.example.playeragent/.MainActivity}"
DEVICE_ID="${ANDROID_DEVICE_ID:-}"
CLEAR_LOGCAT=false
WAIT_SECONDS=8

usage() {
  cat <<'EOF'
Usage: recover_playeragent_debug_state.sh [options]

Options:
  --device <id>       Use a specific adb device id.
  --clear-logcat      Clear logcat before relaunch.
  --wait <seconds>    Seconds to wait after launch before checks. Default: 8.
  -h, --help          Show this help.

The script clears Android's debug-app state, force-stops PlayerAgent, relaunches
MainActivity, and checks for debugger wait, stuck splash, and stuck foreground
service startup.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --device)
      DEVICE_ID="${2:?--device requires an id}"
      shift 2
      ;;
    --clear-logcat)
      CLEAR_LOGCAT=true
      shift
      ;;
    --wait)
      WAIT_SECONDS="${2:?--wait requires seconds}"
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

log() {
  echo "[RecoverPlayerAgent] $*"
}

fail() {
  echo "[RecoverPlayerAgent] FAIL reason=$*" >&2
  exit 1
}

ADB_BIN="$(find_adb)" || fail "adb_not_found"

if [[ -z "$DEVICE_ID" ]]; then
  mapfile -t devices < <("$ADB_BIN" devices | awk 'NR > 1 && $2 == "device" {print $1}')
  if [[ "${#devices[@]}" -eq 0 ]]; then
    fail "adb_device_missing"
  fi
  if [[ "${#devices[@]}" -gt 1 ]]; then
    printf '%s\n' "${devices[@]}" >&2
    fail "multiple_adb_devices_pass_device"
  fi
  DEVICE_ID="${devices[0]}"
fi

log "device=$DEVICE_ID package=$PACKAGE_NAME"
log "clear debug app"
"$ADB_BIN" -s "$DEVICE_ID" shell am clear-debug-app >/dev/null 2>&1 || true

log "force-stop"
"$ADB_BIN" -s "$DEVICE_ID" shell am force-stop "$PACKAGE_NAME" >/dev/null
sleep 1

if [[ "$CLEAR_LOGCAT" == true ]]; then
  log "clear logcat"
  "$ADB_BIN" -s "$DEVICE_ID" logcat -c || true
fi

log "launch $ACTIVITY_NAME"
"$ADB_BIN" -s "$DEVICE_ID" shell am start -n "$ACTIVITY_NAME" >/dev/null
sleep "$WAIT_SECONDS"

tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/playeragent_recover.XXXXXX")"
trap 'rm -rf "$tmp_dir"' EXIT

"$ADB_BIN" -s "$DEVICE_ID" shell dumpsys activity processes > "$tmp_dir/processes.txt" || true
"$ADB_BIN" -s "$DEVICE_ID" shell dumpsys activity activities > "$tmp_dir/activities.txt" || true
"$ADB_BIN" -s "$DEVICE_ID" shell dumpsys activity services "$PACKAGE_NAME" > "$tmp_dir/services.txt" || true
"$ADB_BIN" -s "$DEVICE_ID" logcat -d -t 1200 > "$tmp_dir/logcat.txt" || true

pid="$("$ADB_BIN" -s "$DEVICE_ID" shell pidof "$PACKAGE_NAME" 2>/dev/null | tr -d '\r' | awk '{print $1}')"
[[ -n "$pid" ]] || fail "process_not_running"

debugging=false
if grep -A35 -F "$pid:$PACKAGE_NAME" "$tmp_dir/processes.txt" | grep -q "mDebugging=true"; then
  debugging=true
fi

waiting_debugger=false
if grep -q "Application $PACKAGE_NAME is waiting for the debugger" "$tmp_dir/logcat.txt"; then
  waiting_debugger=true
fi

splash_stuck=false
if grep -q "Splash Screen $PACKAGE_NAME" "$tmp_dir/activities.txt" &&
   grep -q "reportedDrawn=false" "$tmp_dir/activities.txt"; then
  splash_stuck=true
fi

service_executing=false
if grep -q "Executing Services" "$tmp_dir/processes.txt" ||
   grep -q "executeNesting=.*executingStart=-" "$tmp_dir/services.txt"; then
  service_executing=true
fi

cat <<EOF
[RecoverPlayerAgent] pid=$pid
[RecoverPlayerAgent] mDebugging=$debugging
[RecoverPlayerAgent] waitingForDebuggerLog=$waiting_debugger
[RecoverPlayerAgent] splashReportedDrawnFalse=$splash_stuck
[RecoverPlayerAgent] serviceExecuting=$service_executing
EOF

if [[ "$debugging" == true ]]; then
  fail "debugging_still_true"
fi
if [[ "$waiting_debugger" == true ]]; then
  fail "waiting_for_debugger_log_seen"
fi
if [[ "$splash_stuck" == true ]]; then
  fail "splash_reported_drawn_false"
fi
if [[ "$service_executing" == true ]]; then
  fail "foreground_service_start_still_executing"
fi

log "PASS"
