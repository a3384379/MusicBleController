#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="${ROOT_DIR:-$(cd "$SCRIPT_DIR/../.." && pwd)}"
DESKTOP_DIR="${DESKTOP_DIR:-$HOME/Desktop}"
COMMAND_NAME="${COMMAND_NAME:-刷新 MusicBle iOS 签名.command}"
COMMAND_PATH="$DESKTOP_DIR/$COMMAND_NAME"
DEVICE_ARG=""

usage() {
  cat <<'EOF'
Usage: install_desktop_refresh_command.sh [options]

Creates a double-clickable Desktop command for manually refreshing the iOS Debug signature.

Options:
  --device <IOS_DEVICE_ID>  Pin the command to a specific iPhone id.
  -h, --help               Show this help.

Environment overrides:
  ROOT_DIR DESKTOP_DIR COMMAND_NAME
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --device)
      DEVICE_ARG="${2:?--device requires an id}"
      shift 2
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

mkdir -p "$DESKTOP_DIR" /tmp/music_ble_deploy

python3 - "$ROOT_DIR" "$COMMAND_PATH" "$DEVICE_ARG" <<'PY'
import shlex
import stat
import sys
from pathlib import Path

root_dir = Path(sys.argv[1])
command_path = Path(sys.argv[2])
device_arg = sys.argv[3]

project_path = root_dir / "IOSBleFeasibility" / "IOSBleFeasibility.xcodeproj"
refresh_script = root_dir / "tools" / "deploy" / "ios_reinstall_if_needed.sh"

args = [
    "--force",
    "--refresh-only",
    "--renew-profiles",
    "--require-renewed-profile",
    "--threshold-hours",
    "48",
]
if device_arg:
    args.extend(["--device", device_arg])

script = f"""#!/usr/bin/env bash
set -uo pipefail

export ROOT_DIR={shlex.quote(str(root_dir))}
export STATE_FILE=/tmp/music_ble_deploy/last_deploy.json
export LAST_SUCCESS_FILE=/tmp/music_ble_deploy/last_successful_deploy.json
export OUT_ROOT=/tmp/music_ble_deploy
export APP_NAME=sonyMusic.app
export DERIVED_DATA_PATH="$HOME/Library/Developer/Xcode/DerivedData/MusicBleControllerAutoDeploy"
export RENEW_PROFILE_WAIT_SECONDS="${{RENEW_PROFILE_WAIT_SECONDS:-90}}"

mkdir -p /tmp/music_ble_deploy

echo "[MusicBle] start $(date)"
echo "[MusicBle] repo: $ROOT_DIR"
echo "[MusicBle] opening Xcode project to warm signing account state..."
open -a Xcode {shlex.quote(str(project_path))}
sleep 8

echo "[MusicBle] refreshing provisioning profile, building, and installing..."
/bin/bash {shlex.quote(str(refresh_script))} {" ".join(shlex.quote(arg) for arg in args)}
rc=$?

echo
if [[ "$rc" -eq 0 ]]; then
  echo "[MusicBle] PASS"
else
  echo "[MusicBle] FAIL rc=$rc"
  echo "[MusicBle] check /tmp/music_ble_deploy/last_deploy.json"
fi

if [[ -r /tmp/music_ble_deploy/last_deploy.json ]]; then
  echo
  echo "[MusicBle] last_deploy.json:"
  cat /tmp/music_ble_deploy/last_deploy.json
fi

echo
echo "Press Enter to close this window."
read -r _
exit "$rc"
"""

command_path.write_text(script, encoding="utf-8")
command_path.chmod(command_path.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
print(command_path)
PY

echo "Installed Desktop command: $COMMAND_PATH"
