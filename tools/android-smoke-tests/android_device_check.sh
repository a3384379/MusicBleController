#!/usr/bin/env bash
set -euo pipefail

OUT_DIR="${OUT_DIR:?OUT_DIR is required}"
ADB_BIN="${ADB_BIN:?ADB_BIN is required}"

"$ADB_BIN" devices -l > "$OUT_DIR/adb_devices.txt"

python3 - "$OUT_DIR/adb_devices.txt" "$OUT_DIR/device_info.tsv" "${ANDROID_DEVICE_ID:-}" "$ADB_BIN" <<'PY'
import subprocess
import sys
from pathlib import Path

devices_path = Path(sys.argv[1])
info_path = Path(sys.argv[2])
requested = sys.argv[3].strip()
adb = sys.argv[4]

rows = []
for raw in devices_path.read_text(encoding="utf-8", errors="replace").splitlines():
    line = raw.strip()
    if not line or line.startswith("List of devices"):
        continue
    parts = line.split()
    if len(parts) < 2:
        continue
    serial, state = parts[0], parts[1]
    rows.append((serial, state, raw))

if requested:
    matches = [row for row in rows if row[0] == requested]
    if not matches:
        print(f"Requested device {requested} not found in adb devices.", file=sys.stderr)
        sys.exit(1)
    selected = matches[0]
else:
    if not rows:
        print("No adb device found. Connect Sony and enable USB debugging.", file=sys.stderr)
        sys.exit(1)
    device_rows = [row for row in rows if row[1] == "device"]
    unauthorized = [row for row in rows if row[1] == "unauthorized"]
    if unauthorized and not device_rows:
        print("adb device unauthorized. Confirm USB debugging authorization on Sony.", file=sys.stderr)
        sys.exit(1)
    if len(device_rows) == 0:
        print("No online adb device found.", file=sys.stderr)
        sys.exit(1)
    if len(device_rows) > 1:
        print("Multiple adb devices found. Set ANDROID_DEVICE_ID or pass --device.", file=sys.stderr)
        for serial, state, raw in device_rows:
            print(f"- {serial} {state} {raw}", file=sys.stderr)
        sys.exit(1)
    selected = device_rows[0]

serial, state, raw = selected
if state == "unauthorized":
    print("adb device unauthorized. Confirm USB debugging authorization on Sony.", file=sys.stderr)
    sys.exit(1)
if state != "device":
    print(f"adb device {serial} is not online: {state}", file=sys.stderr)
    sys.exit(1)

def getprop(prop: str) -> str:
    try:
        return subprocess.check_output([adb, "-s", serial, "shell", "getprop", prop], text=True, stderr=subprocess.DEVNULL).strip()
    except Exception:
        return "unknown"

model = getprop("ro.product.model") or "unknown"
android_version = getprop("ro.build.version.release") or "unknown"
sdk = getprop("ro.build.version.sdk") or "unknown"
info_path.write_text(f"Sony\t{serial}\t{state}\t{model}\t{android_version}\t{sdk}\n", encoding="utf-8")
print(serial)
PY
