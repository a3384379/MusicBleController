#!/usr/bin/env bash
set -euo pipefail

LOG_PATH="${1:-}"
if [[ -z "$LOG_PATH" || ! -f "$LOG_PATH" ]]; then
  printf 'optional\tControl Service Auto-start\tWARN\t0\tlogcat missing; cannot evaluate auto-start\n'
  exit 0
fi

OUT_DIR="${OUT_DIR:-$(dirname "$LOG_PATH")}"
JSON_PATH="$OUT_DIR/control_service_autostart.json"

python3 - "$LOG_PATH" "$JSON_PATH" <<'PY'
import json
import re
import sys
from pathlib import Path

log_path = Path(sys.argv[1])
json_path = Path(sys.argv[2])
text = log_path.read_text(encoding="utf-8", errors="replace")
lower = text.lower()

def count(pattern: str) -> int:
    return len(re.findall(pattern, text))

fatal = bool(re.search(r"FATAL EXCEPTION|\bANR\b", text, re.I))
enabled = "[ControlServiceAutoStart] enabled=true" in text
start_requested = "[ControlServiceAutoStart] start requested" in text
service_started = (
    "[ControlServiceAutoStart] service started" in text
    or "Foreground service started" in text
)
already_started = "[ControlServiceAutoStart] skip reason=already_started" in text
user_stopped = "[ControlServiceAutoStart] skip reason=user_stopped" in text
permission_missing = "[ControlServiceAutoStart] failed reason=permission_missing" in text

gatt_started_count = count(r"\[BleGattServer\] started")
gatt_already_count = count(r"\[BleGattServer\] already started")
gatt_ready = gatt_started_count > 0 or gatt_already_count > 0

media_registered_count = count(r"\[MediaSessionReader\] registered")
media_already_count = count(r"\[MediaSessionReader\] already registered")
media_ready = media_registered_count > 0 or media_already_count > 0

duplicate_start = gatt_started_count > 2 and gatt_already_count == 0

result = "PASS"
reason = "auto-start requested and service chain reached GATT/MediaSession reader"

if fatal:
    result = "FAIL"
    reason = "FATAL/ANR found in logcat"
elif user_stopped and not start_requested:
    result = "WARN"
    reason = "user stopped service in this app run; auto-start correctly skipped"
elif permission_missing:
    result = "WARN"
    reason = "permission_missing; app did not crash"
elif not enabled:
    result = "WARN"
    reason = "autoStartControlService enabled=true log missing"
elif not (start_requested or already_started):
    result = "WARN"
    reason = "no auto-start request or already-started guard observed"
elif not service_started:
    result = "WARN"
    reason = "service start confirmation missing"
elif not gatt_ready:
    result = "WARN"
    reason = "GATT start/already-started log missing"
elif not media_ready:
    result = "WARN"
    reason = "MediaSessionReader registered/already-registered log missing"
elif duplicate_start:
    result = "FAIL"
    reason = f"possible duplicate GATT start count={gatt_started_count}"

payload = {
    "result": result,
    "reason": reason,
    "enabled": enabled,
    "startRequested": start_requested,
    "serviceStarted": service_started,
    "alreadyStarted": already_started,
    "userStopped": user_stopped,
    "permissionMissing": permission_missing,
    "gattStartedCount": gatt_started_count,
    "gattAlreadyStartedCount": gatt_already_count,
    "mediaRegisteredCount": media_registered_count,
    "mediaAlreadyRegisteredCount": media_already_count,
    "duplicateStart": duplicate_start,
}
json_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")

detail = (
    f"{reason}; enabled={enabled} startRequested={start_requested} "
    f"serviceStarted={service_started} gattStarted={gatt_started_count} "
    f"gattAlready={gatt_already_count} mediaRegistered={media_registered_count} "
    f"mediaAlready={media_already_count}"
)
print(f"optional\tControl Service Auto-start\t{result}\t0\t{detail}")
PY
