#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="${ROOT_DIR:-$(cd "$SCRIPT_DIR/../.." && pwd)}"
TEMPLATE="$SCRIPT_DIR/com.musicble.ios-reinstall.plist"
LABEL="com.musicble.ios-reinstall"
DEST_DIR="$HOME/Library/LaunchAgents"
DEST_PLIST="$DEST_DIR/$LABEL.plist"
RUNNER_DIR="$HOME/Library/Application Support/MusicBleController/deploy-runner"
RUNNER_SCRIPT="$RUNNER_DIR/ios-reinstall-launchd.sh"
COMMAND_FILE="$RUNNER_DIR/ios-reinstall.command"
CHECKER_SCRIPT="$RUNNER_DIR/ios-reinstall-checker.sh"
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

mkdir -p "$DEST_DIR" "$RUNNER_DIR" /tmp/music_ble_deploy

python3 - "$ROOT_DIR" "$RUNNER_DIR" "$RUNNER_SCRIPT" "$COMMAND_FILE" "$CHECKER_SCRIPT" "$DEVICE_ARG" <<'PY'
import os
import shutil
import stat
import sys
from pathlib import Path

root_dir = Path(sys.argv[1])
runner_dir = Path(sys.argv[2])
runner_script = Path(sys.argv[3])
command_file = Path(sys.argv[4])
checker_script = Path(sys.argv[5])
device_arg = sys.argv[6]
runner_dir.mkdir(parents=True, exist_ok=True)

for name in ("ios_reinstall_if_needed.sh", "ios_deploy.sh"):
    source = root_dir / "tools" / "deploy" / name
    dest = runner_dir / name
    dest.write_bytes(source.read_bytes())
    dest.chmod(dest.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)

smoke_source = root_dir / "tools" / "ios-smoke-tests"
smoke_dest = runner_dir / "ios-smoke-tests"
if smoke_dest.exists():
    shutil.rmtree(smoke_dest)
smoke_dest.mkdir(parents=True)
for source in smoke_source.iterdir():
    if source.is_file():
        dest = smoke_dest / source.name
        dest.write_bytes(source.read_bytes())
        mode = source.stat().st_mode
        if mode & stat.S_IXUSR:
            dest.chmod(dest.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)

runner = Path(runner_script)
runner.write_text(
    "#!/usr/bin/env bash\n"
    "set -euo pipefail\n"
    f"export HOME={str(Path.home())!r}\n"
    f"export USER={os.environ.get('USER', '')!r}\n"
    f"export LOGNAME={os.environ.get('LOGNAME', os.environ.get('USER', ''))!r}\n"
    "export PATH=/usr/bin:/bin:/usr/sbin:/sbin:/Applications/Xcode.app/Contents/Developer/usr/bin\n"
    "export DEVELOPER_DIR=${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}\n"
    f"export ROOT_DIR={str(root_dir)!r}\n"
    "export STATE_FILE=/tmp/music_ble_deploy/last_deploy.json\n"
    "export LAST_SUCCESS_FILE=/tmp/music_ble_deploy/last_successful_deploy.json\n"
    f"export SMOKE_CHECK={str(smoke_dest / 'codex_check.sh')!r}\n"
    "export APP_NAME=sonyMusic.app\n"
    f"export DERIVED_DATA_PATH={str(Path.home() / 'Library' / 'Developer' / 'Xcode' / 'DerivedData' / 'MusicBleControllerAutoDeploy')!r}\n"
    "export OUT_ROOT=/tmp/music_ble_deploy\n"
    "export RENEW_PROFILE_WAIT_SECONDS=${RENEW_PROFILE_WAIT_SECONDS:-90}\n"
    f"exec /bin/bash {str(runner_dir / 'ios_reinstall_if_needed.sh')!r} \"$@\"\n",
    encoding="utf-8",
)
runner.chmod(runner.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)

args = ["--force", "--refresh-only", "--once-per-day", "--renew-profiles", "--require-renewed-profile", "--threshold-hours", "48"]
if device_arg:
    args.extend(["--device", device_arg])
quoted_args = " ".join(repr(arg) for arg in args)
command_file.write_text(
    "#!/usr/bin/env bash\n"
    "set -uo pipefail\n"
    "mkdir -p /tmp/music_ble_deploy\n"
    "{\n"
    "  echo \"[LaunchdTerminal] start $(date)\"\n"
    "  lock_dir=/tmp/music_ble_deploy/ios-reinstall-running.lock\n"
    "  if ! mkdir \"$lock_dir\" 2>/dev/null; then\n"
    "    echo \"[LaunchdTerminal] another refresh is already running\"\n"
    "    exit 0\n"
    "  fi\n"
    "  trap 'rmdir \"$lock_dir\" 2>/dev/null || true' EXIT\n"
    f"  /bin/bash {str(runner)!r} {quoted_args}\n"
    "  rc=$?\n"
    "  echo \"[LaunchdTerminal] end $(date) rc=$rc\"\n"
    "  exit \"$rc\"\n"
    "} >> /tmp/music_ble_deploy/ios-reinstall.out.log 2>> /tmp/music_ble_deploy/ios-reinstall.err.log\n",
    encoding="utf-8",
)
command_file.chmod(command_file.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)

checker_device = repr(device_arg)
checker_script.write_text(
    "#!/usr/bin/env bash\n"
    "set -uo pipefail\n"
    "mkdir -p /tmp/music_ble_deploy\n"
    "STATE=/tmp/music_ble_deploy/last_deploy.json\n"
    "SUCCESS=/tmp/music_ble_deploy/last_successful_deploy.json\n"
    "LOCK=/tmp/music_ble_deploy/ios-reinstall-running.lock\n"
    f"COMMAND={str(command_file)!r}\n"
    f"DEVICE_ARG={checker_device}\n"
    "log() { echo \"[LaunchdChecker] $(date) $*\" >> /tmp/music_ble_deploy/ios-reinstall.out.log; }\n"
    "if [[ -d \"$LOCK\" ]]; then log \"refresh already running\"; exit 0; fi\n"
    "if python3 - \"$SUCCESS\" <<'PY2'\n"
    "import datetime as dt, json, sys\n"
    "from pathlib import Path\n"
    "try:\n"
    "    data = json.loads(Path(sys.argv[1]).read_text(encoding='utf-8'))\n"
    "    last = dt.datetime.fromisoformat(data['lastRunTime'].replace('Z', '+00:00')).astimezone()\n"
    "except Exception:\n"
    "    sys.exit(1)\n"
    "sys.exit(0 if last.date() == dt.datetime.now().astimezone().date() else 1)\n"
    "PY2\n"
    "then\n"
    "  log \"already successfully deployed today\"\n"
    "  exit 0\n"
    "fi\n"
    "if python3 - \"$STATE\" <<'PY2'\n"
    "import datetime as dt, json, sys\n"
    "from pathlib import Path\n"
    "try:\n"
    "    data = json.loads(Path(sys.argv[1]).read_text(encoding='utf-8'))\n"
    "    if data.get('result') != 'FAIL':\n"
    "        sys.exit(1)\n"
    "    last = dt.datetime.fromisoformat(data['lastRunTime'].replace('Z', '+00:00'))\n"
    "except Exception:\n"
    "    sys.exit(1)\n"
    "age = dt.datetime.now(dt.timezone.utc) - last.astimezone(dt.timezone.utc)\n"
    "sys.exit(0 if age.total_seconds() < 3600 else 1)\n"
    "PY2\n"
    "then\n"
    "  log \"last refresh failed less than 1 hour ago; skipped\"\n"
    "  exit 0\n"
    "fi\n"
    "device_found=false\n"
    "if [[ -n \"$DEVICE_ARG\" ]]; then\n"
    "  if xcrun devicectl --timeout 20 device info details --device \"$DEVICE_ARG\" >/tmp/music_ble_deploy/checker-device.log 2>/tmp/music_ble_deploy/checker-device.err; then\n"
    "    device_found=true\n"
    "  fi\n"
    "else\n"
    "  if xcrun devicectl --timeout 20 list devices >/tmp/music_ble_deploy/checker-devices.txt 2>/tmp/music_ble_deploy/checker-devices.err; then\n"
    "    if python3 - /tmp/music_ble_deploy/checker-devices.txt <<'PY2'\n"
    "import re, sys\n"
    "from pathlib import Path\n"
    "uuid_re = re.compile(r'[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}')\n"
    "for raw in Path(sys.argv[1]).read_text(encoding='utf-8', errors='replace').splitlines():\n"
    "    m = uuid_re.search(raw)\n"
    "    if not m or 'iPhone' not in raw:\n"
    "        continue\n"
    "    state = raw[m.end():].strip().split('  ')[0].strip()\n"
    "    if 'available' in state or 'connected' in state:\n"
    "        sys.exit(0)\n"
    "sys.exit(1)\n"
    "PY2\n"
    "    then\n"
    "      device_found=true\n"
    "    fi\n"
    "  fi\n"
    "fi\n"
    "if [[ \"$device_found\" != true ]]; then\n"
    "  log \"iPhone not connected; skipped\"\n"
    "  python3 - \"$STATE\" <<'PY2'\n"
    "import datetime as dt, json, sys\n"
    "from pathlib import Path\n"
    "data = {\n"
    "  'lastRunTime': dt.datetime.now(dt.timezone.utc).isoformat().replace('+00:00', 'Z'),\n"
    "  'profileExpireTime': None,\n"
    "  'daysRemaining': None,\n"
    "  'deployExecuted': False,\n"
    "  'result': 'SKIPPED',\n"
    "  'reason': 'iPhone is not connected or not available',\n"
    "  'runDir': None,\n"
    "  'deviceId': None,\n"
    "}\n"
    "Path(sys.argv[1]).write_text(json.dumps(data, indent=2, ensure_ascii=False) + '\\n', encoding='utf-8')\n"
    "PY2\n"
    "  exit 0\n"
    "fi\n"
    "log \"iPhone connected and no successful deploy today; opening refresh command\"\n"
    "/usr/bin/open \"$COMMAND\"\n",
    encoding="utf-8",
)
checker_script.chmod(checker_script.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
PY

python3 - "$TEMPLATE" "$DEST_PLIST" "$RUNNER_DIR" "$CHECKER_SCRIPT" <<'PY'
import plistlib
import sys
from pathlib import Path

template_path, dest_path, runner_dir, checker_script = sys.argv[1:]
with Path(template_path).open("rb") as f:
    data = plistlib.load(f)

program_args = []
for value in data["ProgramArguments"]:
    if value == "__CHECKER_SCRIPT__":
        program_args.append(checker_script)
    else:
        program_args.append(value)

data["ProgramArguments"] = program_args
data["WorkingDirectory"] = runner_dir

with Path(dest_path).open("wb") as f:
    plistlib.dump(data, f, sort_keys=False)
PY

plutil -lint "$DEST_PLIST"
launchctl bootout "gui/$uid" "$DEST_PLIST" >/dev/null 2>&1 || true
launchctl bootstrap "gui/$uid" "$DEST_PLIST"
launchctl enable "gui/$uid/$LABEL"

echo "Installed $DEST_PLIST"
echo "Runner: $RUNNER_SCRIPT"
echo "Command: $COMMAND_FILE"
echo "Checker: $CHECKER_SCRIPT"
echo "Logs: /tmp/music_ble_deploy/ios-reinstall.out.log and /tmp/music_ble_deploy/ios-reinstall.err.log"
