#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="${ROOT_DIR:-$(cd "$SCRIPT_DIR/../.." && pwd)}"
PROJECT_PATH="${PROJECT_PATH:-$ROOT_DIR/IOSBleFeasibility/IOSBleFeasibility.xcodeproj}"
SCHEME="${SCHEME:-sonyMusic}"
CONFIGURATION="${CONFIGURATION:-Debug}"
BUNDLE_ID="${BUNDLE_ID:-com.sqz.IOSBleFeasibility}"
APP_NAME="${APP_NAME:-sonyMusic.app}"
DERIVED_DATA_PATH="${DERIVED_DATA_PATH:-$HOME/Library/Developer/Xcode/DerivedData/MusicBleControllerAutoDeploy}"
OUT_DIR="${OUT_DIR:-/tmp/music_ble_deploy/$(date +%Y%m%d_%H%M%S)}"
SMOKE_CHECK="${SMOKE_CHECK:-$ROOT_DIR/tools/ios-smoke-tests/codex_check.sh}"
RENEW_PROFILE_WAIT_SECONDS="${RENEW_PROFILE_WAIT_SECONDS:-90}"
IOS_DEVICE_ID="${IOS_DEVICE_ID:-}"
XCODE_DESTINATION="${XCODE_DESTINATION:-}"
XCODE_DESTINATION_ID="${XCODE_DESTINATION_ID:-}"
XCODE_WARM_AFTER_PROFILE_REMOVAL="${XCODE_WARM_AFTER_PROFILE_REMOVAL:-true}"
FORCE_REINSTALL=false
REFRESH_ONLY=false
RENEW_PROFILES=false
REQUIRE_RENEWED_PROFILE=false

usage() {
  cat <<'EOF'
Usage: ios_deploy.sh [options]

Build, install, launch, and smoke-test the iOS Debug app.

Options:
  --device <IOS_DEVICE_ID>  Use a specific iPhone device id.
  --output <dir>            Write logs/artifacts to a specific directory.
  --force-reinstall         If install fails, uninstall once and retry install.
  --refresh-only            Build and install only; skip launch and smoke.
  --renew-profiles          Back up and remove local matching provisioning profiles before build.
  --require-renewed-profile Stop after restoring backups if profile renewal build fails.
  -h, --help                Show this help.

Environment overrides:
  ROOT_DIR PROJECT_PATH SCHEME CONFIGURATION BUNDLE_ID APP_NAME DERIVED_DATA_PATH OUT_DIR SMOKE_CHECK RENEW_PROFILE_WAIT_SECONDS IOS_DEVICE_ID XCODE_DESTINATION XCODE_DESTINATION_ID XCODE_WARM_AFTER_PROFILE_REMOVAL
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
    --refresh-only)
      REFRESH_ONLY=true
      shift
      ;;
    --renew-profiles)
      RENEW_PROFILES=true
      shift
      ;;
    --require-renewed-profile)
      REQUIRE_RENEWED_PROFILE=true
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
    if "available" not in state and "connected" not in state:
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

sync_xcode_profiles() {
  local source_dir="$HOME/Library/Developer/Xcode/UserData/Provisioning Profiles"
  local legacy_dir="$HOME/Library/MobileDevice/Provisioning Profiles"
  if [[ ! -d "$source_dir" ]]; then
    return 0
  fi

  mkdir -p "$legacy_dir"
  find "$source_dir" -maxdepth 1 -name '*.mobileprovision' -print0 |
    while IFS= read -r -d '' profile; do
      cp -p "$profile" "$legacy_dir/$(basename "$profile")"
    done
}

restore_profile_backup() {
  local backup_manifest="$OUT_DIR/profile_backup_manifest.tsv"
  [[ -r "$backup_manifest" ]] || return 0

  while IFS=$'\t' read -r backup original; do
    [[ -r "$backup" && -n "$original" ]] || continue
    mkdir -p "$(dirname "$original")"
    cp -p "$backup" "$original"
    echo "[Deploy] restored provisioning profile: $original"
  done < "$backup_manifest"
}

run_xcodebuild() {
  local args=(
    -allowProvisioningUpdates \
    -allowProvisioningDeviceRegistration \
    -project "$PROJECT_PATH" \
    -scheme "$SCHEME" \
    -configuration "$CONFIGURATION" \
    -derivedDataPath "$DERIVED_DATA_PATH"
  )
  if [[ -n "$XCODE_DESTINATION" ]]; then
    args+=(-destination "$XCODE_DESTINATION")
  else
    args+=(-destination 'generic/platform=iOS')
  fi
  args+=(build)
  xcodebuild "${args[@]}"
}

resolve_xcode_destination() {
  if [[ -n "$XCODE_DESTINATION" ]]; then
    echo "$XCODE_DESTINATION"
    return 0
  fi
  if [[ -n "$XCODE_DESTINATION_ID" ]]; then
    echo "id=$XCODE_DESTINATION_ID"
    return 0
  fi

  local destinations_file="$OUT_DIR/xcodebuild_destinations.txt"
  if ! xcodebuild -project "$PROJECT_PATH" -scheme "$SCHEME" -showdestinations \
    >"$destinations_file" 2>"$OUT_DIR/xcodebuild_destinations.err"; then
    echo 'generic/platform=iOS'
    return 0
  fi

  python3 - "$destinations_file" <<'PY'
import re
import sys
from pathlib import Path

destinations = []
for raw in Path(sys.argv[1]).read_text(encoding="utf-8", errors="replace").splitlines():
    if "platform:iOS" not in raw or "placeholder" in raw or "Simulator" in raw:
        continue
    match = re.search(r"id:([^,} ]+)", raw)
    if match:
        destinations.append(match.group(1))

if len(destinations) == 1:
    print(f"id={destinations[0]}")
else:
    print("generic/platform=iOS")
PY
}

renew_matching_profiles() {
  local profile_dirs=(
    "$HOME/Library/Developer/Xcode/UserData/Provisioning Profiles"
    "$HOME/Library/MobileDevice/Provisioning Profiles"
  )
  local profile_list="$OUT_DIR/profiles_to_remove.txt"
  : > "$profile_list"

  for profile_dir in "${profile_dirs[@]}"; do
    [[ -d "$profile_dir" ]] || continue
    find "$profile_dir" -maxdepth 1 -name '*.mobileprovision' -print >> "$profile_list"
  done

  python3 - "$profile_list" "$BUNDLE_ID" <<'PY'
import plistlib
import subprocess
import sys
from pathlib import Path

profile_list = Path(sys.argv[1])
bundle_id = sys.argv[2]
bundle_ids = {bundle_id, f"{bundle_id}.SonyMusicLiveActivityExtension"}

for raw in profile_list.read_text(encoding="utf-8").splitlines():
    path = Path(raw)
    if not path.exists():
        continue
    try:
        decoded = subprocess.check_output(["security", "cms", "-D", "-i", str(path)], stderr=subprocess.DEVNULL)
        data = plistlib.loads(decoded)
    except Exception:
        continue

    app_identifier = data.get("Entitlements", {}).get("application-identifier", "")
    matched = any(app_identifier.endswith(f".{candidate}") for candidate in bundle_ids)
    if matched:
        print(path)
PY
}

wait_for_matching_profiles() {
  local deadline=$((SECONDS + RENEW_PROFILE_WAIT_SECONDS))
  local profile_dirs=(
    "$HOME/Library/Developer/Xcode/UserData/Provisioning Profiles"
    "$HOME/Library/MobileDevice/Provisioning Profiles"
  )

  while (( SECONDS <= deadline )); do
    if python3 - "$BUNDLE_ID" "${profile_dirs[@]}" <<'PY'
import plistlib
import subprocess
import sys
from pathlib import Path

bundle_id = sys.argv[1]
required = {bundle_id, f"{bundle_id}.SonyMusicLiveActivityExtension"}
found = set()

for raw_dir in sys.argv[2:]:
    profile_dir = Path(raw_dir)
    if not profile_dir.is_dir():
        continue
    for path in profile_dir.glob("*.mobileprovision"):
        try:
            decoded = subprocess.check_output(["security", "cms", "-D", "-i", str(path)], stderr=subprocess.DEVNULL)
            data = plistlib.loads(decoded)
        except Exception:
            continue
        app_identifier = data.get("Entitlements", {}).get("application-identifier", "")
        for candidate in required:
            if app_identifier.endswith(f".{candidate}"):
                found.add(candidate)

missing = sorted(required - found)
if missing:
    print("missing " + ", ".join(missing))
    sys.exit(1)
print("found " + ", ".join(sorted(found)))
PY
    then
      return 0
    fi
    sleep 2
  done

  return 1
}

warm_xcode_after_profile_removal() {
  if [[ "$XCODE_WARM_AFTER_PROFILE_REMOVAL" != true ]]; then
    return 0
  fi

  echo "[Deploy] opening Xcode after profile removal to warm signing account state" | tee -a "$OUT_DIR/xcodebuild.log"
  open -a Xcode "$PROJECT_PATH" >/dev/null 2>&1 || true
  echo "[Deploy] waiting up to ${RENEW_PROFILE_WAIT_SECONDS}s for Xcode to regenerate profiles" | tee -a "$OUT_DIR/xcodebuild.log"
  if wait_for_matching_profiles 2>&1 | tee -a "$OUT_DIR/xcodebuild.log"; then
    echo "[Deploy] regenerated profiles found before xcodebuild" | tee -a "$OUT_DIR/xcodebuild.log"
    sync_xcode_profiles
  else
    echo "[Deploy] Xcode did not regenerate profiles before xcodebuild; continuing" | tee -a "$OUT_DIR/xcodebuild.log"
  fi
}

IOS_DEVICE_ID="$(find_device)"
XCODE_DESTINATION="$(resolve_xcode_destination)"
export ROOT_DIR OUT_DIR IOS_DEVICE_ID BUNDLE_ID XCODE_DESTINATION

echo "[Deploy] output=$OUT_DIR"
echo "[Deploy] device=$IOS_DEVICE_ID"
echo "[Deploy] xcodeDestination=$XCODE_DESTINATION"
echo "[Deploy] project=$PROJECT_PATH scheme=$SCHEME configuration=$CONFIGURATION"
echo "[Deploy] derivedData=$DERIVED_DATA_PATH"
{
  echo "DVTDeveloperAccountManagerAppleIDLists:"
  defaults read com.apple.dt.Xcode DVTDeveloperAccountManagerAppleIDLists 2>&1 || true
  echo "IDEProvisioningTeamManagerLastSelectedTeamID:"
  defaults read com.apple.dt.Xcode IDEProvisioningTeamManagerLastSelectedTeamID 2>&1 || true
} >"$OUT_DIR/xcode_account_diagnostics.log"

sync_xcode_profiles
if [[ "$RENEW_PROFILES" == true ]]; then
  echo "[Deploy] renewProfiles=true"
  backup_dir="$OUT_DIR/profile_backup"
  backup_manifest="$OUT_DIR/profile_backup_manifest.tsv"
  mkdir -p "$backup_dir"
  : > "$backup_manifest"
  while IFS= read -r profile; do
    [[ -n "$profile" ]] || continue
    if [[ ! -r "$profile" ]]; then
      echo "[Deploy] provisioning profile disappeared before backup, skipping: $profile"
      continue
    fi
    backup="$backup_dir/$(printf '%s' "$profile" | shasum -a 256 | awk '{print $1}').mobileprovision"
    cp -p "$profile" "$backup"
    printf '%s\t%s\n' "$backup" "$profile" >> "$backup_manifest"
    echo "[Deploy] backed up provisioning profile: $profile"
    echo "[Deploy] removing provisioning profile: $profile"
    rm -f "$profile"
  done < <(renew_matching_profiles)

  if [[ -s "$backup_manifest" ]]; then
    warm_xcode_after_profile_removal
  fi
fi

set +e
run_xcodebuild | tee "$OUT_DIR/xcodebuild.log"
build_rc="${PIPESTATUS[0]}"
set -e

if [[ "$build_rc" -ne 0 && "$RENEW_PROFILES" == true ]]; then
  echo "[Deploy] xcodebuild failed after profile renewal; waiting up to ${RENEW_PROFILE_WAIT_SECONDS}s for regenerated profiles" | tee -a "$OUT_DIR/xcodebuild.log"
  if wait_for_matching_profiles 2>&1 | tee -a "$OUT_DIR/xcodebuild.log"; then
    echo "[Deploy] regenerated profiles found; retrying build before restoring backups" | tee -a "$OUT_DIR/xcodebuild.log"
    sync_xcode_profiles
    set +e
    run_xcodebuild | tee "$OUT_DIR/xcodebuild_retry_renewed.log"
    build_rc="${PIPESTATUS[0]}"
    set -e
  fi
fi

if [[ "$build_rc" -ne 0 && "$RENEW_PROFILES" == true ]]; then
  echo "[Deploy] xcodebuild still failed after profile renewal; restoring previous profiles" | tee -a "$OUT_DIR/xcodebuild.log"
  restore_profile_backup
  sync_xcode_profiles
  if [[ "$REQUIRE_RENEWED_PROFILE" == true ]]; then
    echo "[Deploy] renewed profile is required; not reinstalling with restored profiles" | tee -a "$OUT_DIR/xcodebuild.log"
    exit "$build_rc"
  fi
  echo "[Deploy] retrying once with restored profiles" | tee -a "$OUT_DIR/xcodebuild.log"
  set +e
  run_xcodebuild | tee "$OUT_DIR/xcodebuild_retry.log"
  build_rc="${PIPESTATUS[0]}"
  set -e
fi

if [[ "$build_rc" -ne 0 ]]; then
  exit "$build_rc"
fi

sync_xcode_profiles

APP_PATH="$DERIVED_DATA_PATH/Build/Products/$CONFIGURATION-iphoneos/$APP_NAME"

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

if [[ "$REFRESH_ONLY" == true ]]; then
  echo "[Deploy] refresh-only enabled; launch and smoke skipped" | tee "$OUT_DIR/launch.log"
  echo "[Deploy] PASS"
  exit 0
fi

xcrun devicectl device process launch \
  --device "$IOS_DEVICE_ID" \
  --terminate-existing \
  "$BUNDLE_ID" | tee "$OUT_DIR/launch.log"

IOS_DEVICE_ID="$IOS_DEVICE_ID" BUNDLE_ID="$BUNDLE_ID" \
  "$SMOKE_CHECK" | tee "$OUT_DIR/smoke.log"

echo "[Deploy] PASS"
