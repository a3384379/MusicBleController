#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="${ROOT_DIR:-$(cd "$SCRIPT_DIR/../.." && pwd)}"
TEMPLATE="$SCRIPT_DIR/com.musicble.ios-reinstall.plist"
LABEL="com.musicble.ios-reinstall"
DEST_DIR="$HOME/Library/LaunchAgents"
DEST_PLIST="$DEST_DIR/$LABEL.plist"
DEVICE_ARG=""

usage() {
  cat <<'EOF'
Usage: install_launchd_plist.sh [options]

Installs the daily MusicBle iOS reinstall LaunchAgent for the current user.

Options:
  --device <IOS_DEVICE_ID>  Pin the LaunchAgent to a specific iPhone id.
  --uninstall              Unload and remove the LaunchAgent.
  -h, --help               Show this help.
EOF
}

UNINSTALL=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --device)
      DEVICE_ARG="${2:?--device requires an id}"
      shift 2
      ;;
    --uninstall)
      UNINSTALL=true
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

uid="$(id -u)"

if [[ "$UNINSTALL" == true ]]; then
  launchctl bootout "gui/$uid" "$DEST_PLIST" >/dev/null 2>&1 || true
  rm -f "$DEST_PLIST"
  echo "Removed $DEST_PLIST"
  exit 0
fi

mkdir -p "$DEST_DIR" /tmp/music_ble_deploy

python3 - "$TEMPLATE" "$DEST_PLIST" "$ROOT_DIR" "$DEVICE_ARG" <<'PY'
import plistlib
import sys
from pathlib import Path

template_path, dest_path, root_dir, device_arg = sys.argv[1:]
with Path(template_path).open("rb") as f:
    data = plistlib.load(f)

program_args = []
for value in data["ProgramArguments"]:
    if value == "__REPO_ROOT__/tools/deploy/ios_reinstall_if_needed.sh":
        program_args.append(f"{root_dir}/tools/deploy/ios_reinstall_if_needed.sh")
    elif value == "__OPTIONAL_DEVICE_ARGS__":
        if device_arg:
            program_args.extend(["--device", device_arg])
    else:
        program_args.append(value)

data["ProgramArguments"] = program_args
data["WorkingDirectory"] = root_dir

with Path(dest_path).open("wb") as f:
    plistlib.dump(data, f, sort_keys=False)
PY

plutil -lint "$DEST_PLIST"
launchctl bootout "gui/$uid" "$DEST_PLIST" >/dev/null 2>&1 || true
launchctl bootstrap "gui/$uid" "$DEST_PLIST"
launchctl enable "gui/$uid/$LABEL"

echo "Installed $DEST_PLIST"
echo "Logs: /tmp/music_ble_deploy/ios-reinstall.out.log and /tmp/music_ble_deploy/ios-reinstall.err.log"
