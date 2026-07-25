#!/usr/bin/env bash
set -euo pipefail

OUT_DIR="${OUT_DIR:-/tmp/music_ble_ios_smoke/manual}"
IOS_DEVICE_ID="${IOS_DEVICE_ID:?IOS_DEVICE_ID is required}"
BUNDLE_ID="${BUNDLE_ID:-com.sqz.IOSBleFeasibility}"
WAIT_SECONDS="${WAIT_SECONDS:-5}"

mkdir -p "$OUT_DIR"

smoke_args=()
for launch_arg in "$@"; do
  if [[ "$launch_arg" == --smoke-* ]]; then
    smoke_args+=("$launch_arg")
  fi
done

# CoreBluetooth restoration may relaunch the app before devicectl can deliver
# process arguments. Stage the same one-shot marker consumed by the DEBUG app so
# every smoke entry point remains deterministic on a restored connection.
if [[ "${#smoke_args[@]}" -gt 0 ]]; then
  launch_marker="$OUT_DIR/SmokeLaunchArguments.txt"
  : > "$launch_marker"
  for launch_arg in "${smoke_args[@]}"; do
    printf '%s\n' "$launch_arg" >> "$launch_marker"
  done
  if ! xcrun devicectl device copy to \
    --device "$IOS_DEVICE_ID" \
    --domain-type appDataContainer \
    --domain-identifier "$BUNDLE_ID" \
    --source "$launch_marker" \
    --destination "Documents/SmokeLaunchArguments.txt" \
    >>"$OUT_DIR/ios_launch.log" 2>&1; then
    echo "[iOSLaunch] WARN unable to stage restoration-safe smoke marker" >&2
  fi
fi

xcrun devicectl device process launch \
  --device "$IOS_DEVICE_ID" \
  --terminate-existing \
  "$BUNDLE_ID" \
  "$@" | tee -a "$OUT_DIR/ios_launch.log"

sleep "$WAIT_SECONDS"
