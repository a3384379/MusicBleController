#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
DURATION_SEC=30
IOS_DEVICE_ID="${IOS_DEVICE_ID:-}"
ANDROID_DEVICE_ID="${ANDROID_DEVICE_ID:-}"
OUTPUT_DIR_ARG=""
JSON_OUTPUT=false
BUNDLE_ID="${BUNDLE_ID:-com.sqz.IOSBleFeasibility}"

usage() {
  cat <<'EOF'
Usage: reconnect_sync_v28_test.sh [options]

Options:
  --duration <seconds>       Post-reconnect collection window. Default: 30.
  --ios-device <id>          iPhone devicectl identifier.
  --android-device <id>      Sony adb serial.
  --output <dir>             Output directory.
  --json                     Print machine-readable summary only.
  -h, --help                 Show help.

Manual prerequisites:
  - iPhone is connected, unlocked, and trusted.
  - Sony is connected by USB and PlayerAgent control service is running.
  - QQMusic is playing if currentWord recovery should be validated.

The script triggers a reconnect by relaunching the iOS app with devicectl.
It does not use adb to control Sony or change BLE protocol state.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --duration)
      DURATION_SEC="${2:?--duration requires seconds}"
      shift 2
      ;;
    --ios-device)
      IOS_DEVICE_ID="${2:?--ios-device requires an id}"
      shift 2
      ;;
    --android-device)
      ANDROID_DEVICE_ID="${2:?--android-device requires an id}"
      shift 2
      ;;
    --output)
      OUTPUT_DIR_ARG="${2:?--output requires a directory}"
      shift 2
      ;;
    --json)
      JSON_OUTPUT=true
      shift
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

timestamp="$(date +%Y%m%d_%H%M%S)"
OUT_DIR="${OUTPUT_DIR_ARG:-/tmp/reconnect_sync_v28/$timestamp}"
mkdir -p "$OUT_DIR"

log() {
  echo "[ReconnectSyncV28] $*" >&2
}

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

detect_ios() {
  if [[ -n "$IOS_DEVICE_ID" ]]; then
    return 0
  fi
  if ! command -v xcrun >/dev/null 2>&1; then
    echo "xcrun not found" > "$OUT_DIR/ios_detect_reason.txt"
    return 1
  fi
  local list_file="$OUT_DIR/devicectl_devices.txt"
  if ! xcrun devicectl list devices > "$list_file" 2>"$OUT_DIR/devicectl_list_stderr.log"; then
    echo "devicectl list failed" > "$OUT_DIR/ios_detect_reason.txt"
    return 1
  fi
  IOS_DEVICE_ID="$(
    python3 - "$list_file" "$OUT_DIR/ios_detect_reason.txt" <<'PY'
import re
import sys
from pathlib import Path

list_path = Path(sys.argv[1])
reason_path = Path(sys.argv[2])
uuid_re = re.compile(r"[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}")
rows = []
for raw in list_path.read_text(encoding="utf-8", errors="replace").splitlines():
    match = uuid_re.search(raw)
    if not match or "iPhone" not in raw:
        continue
    right = raw[match.end():].strip().lower()
    if right.startswith("connected") or right.startswith("available"):
        rows.append(match.group(0))
if len(rows) == 1:
    print(rows[0])
    sys.exit(0)
if not rows:
    reason_path.write_text("no connected iPhone found\n", encoding="utf-8")
else:
    reason_path.write_text("multiple connected iPhones found; pass --ios-device\n", encoding="utf-8")
sys.exit(1)
PY
  )" || return 1
}

detect_android() {
  if ! ADB_BIN="$(find_adb)"; then
    echo "adb not found" > "$OUT_DIR/android_detect_reason.txt"
    return 1
  fi
  export ADB_BIN
  if [[ -n "$ANDROID_DEVICE_ID" ]]; then
    return 0
  fi
  "$ADB_BIN" devices -l > "$OUT_DIR/adb_devices.txt"
  ANDROID_DEVICE_ID="$(
    python3 - "$OUT_DIR/adb_devices.txt" "$OUT_DIR/android_detect_reason.txt" <<'PY'
import sys
from pathlib import Path

devices_path = Path(sys.argv[1])
reason_path = Path(sys.argv[2])
rows = []
unauthorized = []
for raw in devices_path.read_text(encoding="utf-8", errors="replace").splitlines():
    line = raw.strip()
    if not line or line.startswith("List of devices"):
        continue
    parts = line.split()
    if len(parts) < 2:
        continue
    if parts[1] == "device":
        rows.append(parts[0])
    elif parts[1] == "unauthorized":
        unauthorized.append(parts[0])
if len(rows) == 1:
    print(rows[0])
    sys.exit(0)
if unauthorized and not rows:
    reason_path.write_text("adb device unauthorized\n", encoding="utf-8")
elif not rows:
    reason_path.write_text("no online adb device found\n", encoding="utf-8")
else:
    reason_path.write_text("multiple adb devices found; pass --android-device\n", encoding="utf-8")
sys.exit(1)
PY
  )" || return 1
}

copy_ios_log() {
  local dest="$1"
  xcrun devicectl device copy from \
    --device "$IOS_DEVICE_ID" \
    --domain-type appDataContainer \
    --domain-identifier "$BUNDLE_ID" \
    --source Documents/Logs/ios_ble.log \
    --destination "$dest" \
    >"$OUT_DIR/devicectl_copy_$(basename "$dest").out" \
    2>"$OUT_DIR/devicectl_copy_$(basename "$dest").err"
}

fail_with_report() {
  local reason="$1"
  python3 - "$OUT_DIR" "$reason" <<'PY'
import json
import sys
from pathlib import Path

out_dir = Path(sys.argv[1])
reason = sys.argv[2]
payload = {
    "summary": {
        "result": "FAIL",
        "durationSec": 0,
        "reason": reason,
        "warnings": [],
        "issues": [reason],
    },
    "sony": {},
    "ios": {},
    "latency": {},
    "artifacts": {
        "report_md": str(out_dir / "report.md"),
        "report_json": str(out_dir / "report.json"),
    },
}
(out_dir / "report.json").write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
(out_dir / "report.md").write_text(f"# Reconnect Sync V2.8 Test\n\nResult: FAIL\n\n{reason}\n", encoding="utf-8")
print(json.dumps({
    "report_json": str(out_dir / "report.json"),
    "report_md": str(out_dir / "report.md"),
    "summary": payload["summary"],
}, ensure_ascii=False))
PY
  exit 1
}

if ! detect_ios; then
  fail_with_report "$(cat "$OUT_DIR/ios_detect_reason.txt" 2>/dev/null || echo "iPhone unavailable")"
fi
if ! detect_android; then
  fail_with_report "$(cat "$OUT_DIR/android_detect_reason.txt" 2>/dev/null || echo "Android device unavailable")"
fi

IOS_BEFORE="$OUT_DIR/ios_before.log"
IOS_AFTER="$OUT_DIR/ios_ble.log"
SONY_LOG="$OUT_DIR/sony_logcat.log"

log "output=$OUT_DIR"
log "ios=$IOS_DEVICE_ID android=$ANDROID_DEVICE_ID duration=${DURATION_SEC}s"

copy_ios_log "$IOS_BEFORE" || true
"$ADB_BIN" -s "$ANDROID_DEVICE_ID" logcat -c || true

START_EPOCH_MS="$(python3 - <<'PY'
import time
print(int(time.time() * 1000))
PY
)"
log "trigger reconnect by relaunching iOS app"
xcrun devicectl device process launch \
  --device "$IOS_DEVICE_ID" \
  --terminate-existing \
  "$BUNDLE_ID" \
  >"$OUT_DIR/devicectl_relaunch.out" \
  2>"$OUT_DIR/devicectl_relaunch.err" || true

sleep "$DURATION_SEC"

END_EPOCH_MS="$(python3 - <<'PY'
import time
print(int(time.time() * 1000))
PY
)"

copy_ios_log "$IOS_AFTER" || true
"$ADB_BIN" -s "$ANDROID_DEVICE_ID" logcat -v threadtime -d > "$SONY_LOG"

python3 - "$OUT_DIR" "$DURATION_SEC" "$START_EPOCH_MS" "$END_EPOCH_MS" "$IOS_BEFORE" "$IOS_AFTER" "$SONY_LOG" "$IOS_DEVICE_ID" "$ANDROID_DEVICE_ID" <<'PY'
import json
import math
import re
import sys
from datetime import datetime
from pathlib import Path

out_dir = Path(sys.argv[1])
duration_sec = int(float(sys.argv[2]))
start_epoch_ms = int(sys.argv[3])
end_epoch_ms = int(sys.argv[4])
ios_before_path = Path(sys.argv[5])
ios_after_path = Path(sys.argv[6])
sony_log_path = Path(sys.argv[7])
ios_device = sys.argv[8]
android_device = sys.argv[9]

def read(path: Path) -> str:
    if not path.exists():
        return ""
    return path.read_text(encoding="utf-8", errors="replace")

ios_before = read(ios_before_path)
ios_after = read(ios_after_path)
sony_text = read(sony_log_path)
ios_window = ios_after[len(ios_before):] if ios_before and ios_after.startswith(ios_before) else ios_after

ios_ts_re = re.compile(r"^(?P<ts>\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})")
android_ts_re = re.compile(r"^(?P<ts>\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})")

def parse_ios_ms(line: str):
    match = ios_ts_re.search(line)
    if not match:
        return None
    try:
        return int(datetime.strptime(match.group("ts"), "%Y-%m-%d %H:%M:%S.%f").timestamp() * 1000)
    except ValueError:
        return None

def parse_android_ms(line: str):
    match = android_ts_re.search(line)
    if not match:
        return None
    year = datetime.fromtimestamp(start_epoch_ms / 1000).year
    try:
        return int(datetime.strptime(f"{year}-{match.group('ts')}", "%Y-%m-%d %H:%M:%S.%f").timestamp() * 1000)
    except ValueError:
        return None

def first_time(pattern: str, text: str, parser):
    regex = re.compile(pattern, re.I)
    for line in text.splitlines():
        if regex.search(line):
            ts = parser(line)
            if ts is not None:
                return ts
    return 0

def count(pattern: str, text: str):
    return len(re.findall(pattern, text, re.I))

sony_filtered = "\n".join(
    line for line in sony_text.splitlines()
    if re.search(r"ReconnectSync|CurrentWordPush|PlaybackDiff|PlaybackState|AlbumArt|payload|main stall", line, re.I)
)
ios_filtered = "\n".join(
    line for line in ios_window.splitlines()
    if re.search(r"Reconnect|currentWord|playbackState|albumArtOffer|main stall|payload|Lyrics-iOS", line, re.I)
)
(out_dir / "sony_reconnect_filtered.log").write_text(sony_filtered, encoding="utf-8")
(out_dir / "ios_reconnect_filtered.log").write_text(ios_filtered, encoding="utf-8")
(out_dir / "ios_window.log").write_text(ios_window, encoding="utf-8")

reconnect_count = count(r"\[Reconnect\] connected|\[BLE-Reconnect\] success", ios_window)
notify_subscribed = count(r"\[Reconnect\] subscribed|status notify subscribed", ios_window)
sync_start = count(r"\[ReconnectSync\] start", sony_text)
sync_playback_sent = count(r"\[ReconnectSync\] send playbackState", sony_text)
sync_current_word_sent = count(r"\[ReconnectSync\] send currentWord", sony_text)
sync_current_word_skip = count(r"\[ReconnectSync\] skip currentWord", sony_text)
sync_album_offer_sent = count(r"\[ReconnectSync\] send albumArtOffer", sony_text)
cooldown_skip = count(r"\[ReconnectSync\] skip reason=cooldown", sony_text)
ios_state_sync = count(r"\[Reconnect\] state sync received", ios_window)
ios_reconnect_playback_accepted = count(r"\[Reconnect\] playbackState accepted", ios_window)
ios_raw_playback_state = count(r'\{"type":"playbackState"', ios_window)
ios_playback_accepted = max(ios_reconnect_playback_accepted, ios_raw_playback_state)
ios_current_word_after = count(r"\[Reconnect\] currentWord accepted after reconnect", ios_window)
ios_album_offer = count(r"albumArtOffer", ios_window)
stale_after = count(r"\[Reconnect\] stale discard after reconnect", ios_window)
main_stall = count(r"main stall detected", ios_window)
payload_too_large = count(r"payload=\d+ max=\d+|Payload maximum size exceeded", sony_text + "\n" + ios_window)
disconnect_reason = ""
disconnect_match = re.search(r"\[BLE-iOS\] didDisconnect error=([^\n]+)", ios_window)
if disconnect_match:
    disconnect_reason = disconnect_match.group(1).strip()

first_subscribe_ms = first_time(r"\[Reconnect\] subscribed|status notify subscribed", ios_window, parse_ios_ms)
first_playback_ms = first_time(r"\[Reconnect\] playbackState accepted|\[iOS\]\[Status\] playbackState", ios_window, parse_ios_ms)
first_current_word_ms = first_time(r"\[Reconnect\] currentWord accepted after reconnect|\[Lyrics-iOS\]\s+currentWord", ios_window, parse_ios_ms)
first_album_offer_ms = first_time(r"albumArtOffer", ios_window, parse_ios_ms)

def delta_after(start, end):
    return end - start if start and end and end >= start else 0

playback_latency = delta_after(first_subscribe_ms, first_playback_ms)
current_word_latency = delta_after(first_subscribe_ms, first_current_word_ms)
album_offer_latency = delta_after(first_subscribe_ms, first_album_offer_ms)

issues = []
warnings = []
if notify_subscribed == 0:
    issues.append("iOS notify subscription was not observed")
if ios_playback_accepted == 0:
    issues.append("iOS did not receive reconnect playbackState")
elif playback_latency and playback_latency > 5_000:
    issues.append(f"reconnect playbackState latency exceeded 5s ({playback_latency}ms)")
if sync_start == 0:
    warnings.append("Sony reconnect sync start was not observed")
if sync_playback_sent == 0:
    warnings.append("Sony reconnect playbackState send log was not observed")
if sync_current_word_sent == 0:
    warnings.append("currentWord was not sent after reconnect or was unavailable")
if sync_album_offer_sent == 0:
    warnings.append("albumArtOffer was not resent after reconnect")
if stale_after > 0:
    issues.append(f"stale discard after reconnect observed ({stale_after})")
if main_stall > 0:
    issues.append(f"main stall observed ({main_stall})")
if payload_too_large > 0:
    issues.append(f"payload too large observed ({payload_too_large})")

result = "FAIL" if issues else ("WARN" if warnings else "PASS")
payload = {
    "summary": {
        "result": result,
        "durationSec": duration_sec,
        "warnings": warnings,
        "issues": issues,
    },
    "sony": {
        "reconnectSyncStartCount": sync_start,
        "reconnectSyncPlaybackStateSent": sync_playback_sent,
        "reconnectSyncCurrentWordSent": sync_current_word_sent,
        "reconnectSyncCurrentWordSkipped": sync_current_word_skip,
        "reconnectSyncAlbumArtOfferSent": sync_album_offer_sent,
        "reconnectSyncCooldownSkip": cooldown_skip,
    },
    "ios": {
        "reconnectCount": reconnect_count,
        "notifySubscribedCount": notify_subscribed,
        "reconnectPlaybackStateReceived": ios_playback_accepted,
        "reconnectPlaybackStateAcceptedLogCount": ios_reconnect_playback_accepted,
        "rawPlaybackStateReceived": ios_raw_playback_state,
        "reconnectStateSyncReceived": ios_state_sync,
        "reconnectCurrentWordReceived": ios_current_word_after,
        "albumArtOfferReceived": ios_album_offer,
        "staleDiscardAfterReconnect": stale_after,
        "mainStallCount": main_stall,
        "payloadTooLargeCount": payload_too_large,
        "disconnectReason": disconnect_reason,
    },
    "latency": {
        "reconnectToFirstPlaybackStateMs": playback_latency,
        "reconnectToFirstCurrentWordMs": current_word_latency,
        "reconnectToAlbumArtOfferMs": album_offer_latency,
    },
    "devices": {
        "ios": ios_device,
        "android": android_device,
    },
    "artifacts": {
        "report_md": str(out_dir / "report.md"),
        "report_json": str(out_dir / "report.json"),
        "ios_ble_log": str(ios_after_path),
        "sony_logcat": str(sony_log_path),
        "ios_filtered": str(out_dir / "ios_reconnect_filtered.log"),
        "sony_filtered": str(out_dir / "sony_reconnect_filtered.log"),
    },
}

md = [
    "# Reconnect Sync V2.8 Test",
    "",
    f"Result: {result}",
    f"Duration: {duration_sec}s",
    "",
    "## Metrics",
    "",
    f"- reconnect count: {reconnect_count}",
    f"- notify subscribed: {notify_subscribed}",
    f"- Sony sync start: {sync_start}",
    f"- Sony playbackState sent: {sync_playback_sent}",
    f"- Sony currentWord sent: {sync_current_word_sent}",
    f"- Sony albumArtOffer sent: {sync_album_offer_sent}",
    f"- cooldown skip: {cooldown_skip}",
    f"- iOS playbackState received: {ios_playback_accepted}",
    f"- iOS currentWord received: {ios_current_word_after}",
    f"- iOS albumArtOffer received: {ios_album_offer}",
    f"- playback latency: {playback_latency}ms",
    f"- currentWord latency: {current_word_latency}ms",
    f"- albumArtOffer latency: {album_offer_latency}ms",
    f"- stale discard after reconnect: {stale_after}",
    f"- main stall: {main_stall}",
    f"- payload too large: {payload_too_large}",
]
if warnings:
    md += ["", "## Warnings"] + [f"- {item}" for item in warnings]
if issues:
    md += ["", "## Issues"] + [f"- {item}" for item in issues]
md += [
    "",
    "## Artifacts",
    "",
    f"- ios_ble.log: `{ios_after_path}`",
    f"- sony_logcat.log: `{sony_log_path}`",
    f"- ios filtered: `{out_dir / 'ios_reconnect_filtered.log'}`",
    f"- sony filtered: `{out_dir / 'sony_reconnect_filtered.log'}`",
]

(out_dir / "report.json").write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
(out_dir / "report.md").write_text("\n".join(md), encoding="utf-8")
PY

REPORT_JSON="$OUT_DIR/report.json"
if [[ "$JSON_OUTPUT" == true ]]; then
  python3 - "$REPORT_JSON" <<'PY'
import json
import sys
from pathlib import Path
payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
print(json.dumps({
    "report_json": payload["artifacts"]["report_json"],
    "report_md": payload["artifacts"]["report_md"],
    "summary": payload["summary"],
}, ensure_ascii=False))
PY
else
  cat "$OUT_DIR/report.md"
fi

result="$(python3 - "$REPORT_JSON" <<'PY'
import json
import sys
from pathlib import Path
print(json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))["summary"]["result"])
PY
)"
if [[ "$result" == "FAIL" ]]; then
  exit 1
fi
exit 0
