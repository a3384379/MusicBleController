#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
DURATION_SEC=75
IOS_DEVICE_ID="${IOS_DEVICE_ID:-}"
ANDROID_DEVICE_ID="${ANDROID_DEVICE_ID:-}"
OUTPUT_DIR_ARG=""
JSON_OUTPUT=false
BUNDLE_ID="${BUNDLE_ID:-com.sqz.IOSBleFeasibility}"

usage() {
  cat <<'EOF'
Usage: control_e2e_v29_test.sh [options]

Options:
  --duration <seconds>       Test collection window. Default: 75.
  --ios-device <id>          iPhone devicectl identifier.
  --android-device <id>      Sony adb serial.
  --output <dir>             Output directory.
  --json                     Print machine-readable summary only.
  -h, --help                 Show help.

Manual prerequisites:
  - iPhone is connected, unlocked, trusted, and can launch the Debug app.
  - Sony is connected by USB and PlayerAgent control service is running.
  - QQMusic is playing and exposes a MediaSession.

The script launches the iOS app with a DEBUG-only --smoke-control-e2e
argument, then verifies the real iOS -> Sony -> iOS interaction from logs.
It does not modify BLE UUIDs or payload protocols.
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
OUT_DIR="${OUTPUT_DIR_ARG:-/tmp/control_e2e_smoke/$timestamp}"
mkdir -p "$OUT_DIR"

log() {
  echo "[ControlE2E] $*" >&2
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

run_ios_ble_precheck() {
  OUT_DIR="$OUT_DIR" IOS_DEVICE_ID="$IOS_DEVICE_ID" BUNDLE_ID="$BUNDLE_ID" \
    "$SCRIPT_DIR/ios_ble_precheck.sh" --timeout 5 \
    --launch-arg "--smoke-control-e2e" --json \
    >"$OUT_DIR/ios_ble_precheck_stdout.json" \
    2>"$OUT_DIR/ios_ble_precheck_stderr.log"
}

precheck_json_object() {
  if [[ -s "$OUT_DIR/ios_ble_precheck.json" ]]; then
    cat "$OUT_DIR/ios_ble_precheck.json"
  else
    printf '{}'
  fi
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
        "reason": reason,
        "warnings": [],
        "issues": [reason],
    },
    "operations": {},
    "metrics": {},
    "artifacts": {
        "report_md": str(out_dir / "report.md"),
        "report_json": str(out_dir / "report.json"),
    },
}
precheck_path = out_dir / "ios_ble_precheck.json"
precheck = json.loads(precheck_path.read_text()) if precheck_path.exists() else {}
payload["precheck"] = precheck
payload["summary"].update({
    "iosAppLaunched": precheck.get("iosAppLaunched", False),
    "iosBleConnected": precheck.get("iosBleConnected", False),
    "notifySubscribed": precheck.get("notifySubscribed", False),
    "firstPlaybackStateReceived": precheck.get("firstPlaybackStateReceived", False),
    "firstPlaybackStateLatencyMs": precheck.get("firstPlaybackStateLatencyMs", 0),
    "precheckResult": precheck.get("precheckResult", "FAIL" if reason == "ios_ble_not_connected" else "UNKNOWN"),
    "precheckFailReason": precheck.get("precheckFailReason", reason if reason == "ios_ble_not_connected" else ""),
})
(out_dir / "report.json").write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
(out_dir / "report.md").write_text(f"# Control E2E V2.9 Smoke\n\nResult: FAIL\n\n{reason}\n", encoding="utf-8")
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
if ! run_ios_ble_precheck; then
  fail_with_report "ios_ble_not_connected"
fi

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
import re
import statistics
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

ios_after = read(ios_after_path)
sony_text = read(sony_log_path)
ios_before = read(ios_before_path)

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

def ios_lines_in_window(text: str):
    lines = []
    floor = start_epoch_ms - 1_000
    for line in text.splitlines():
        ts = parse_ios_ms(line)
        if ts is None or ts >= floor:
            lines.append(line)
    return "\n".join(lines)

if ios_before and ios_after.startswith(ios_before):
    ios_window = ios_after[len(ios_before):]
else:
    ios_window = ios_lines_in_window(ios_after)
(out_dir / "ios_window.log").write_text(ios_window, encoding="utf-8")

def count(pattern: str, text: str) -> int:
    return len(re.findall(pattern, text, re.I))

def quantile_p95(values):
    if not values:
        return 0
    ordered = sorted(values)
    index = min(len(ordered) - 1, int(round((len(ordered) - 1) * 0.95)))
    return ordered[index]

def first_line_time(pattern: str, text: str, parser, after_ms=0):
    regex = re.compile(pattern, re.I)
    for line in text.splitlines():
        if not regex.search(line):
            continue
        ts = parser(line)
        if ts is None:
            continue
        if after_ms and ts < after_ms:
            continue
        return ts
    return 0

def extract_ios_sends():
    sends = []
    send_re = re.compile(r"\[CTRL-iOS\] send start seq=(\d+) cmd=([A-Z_]+) timeMs=(\d+)")
    for line in ios_window.splitlines():
        match = send_re.search(line)
        if match:
            sends.append({
                "seq": match.group(1),
                "cmd": match.group(2),
                "timeMs": int(match.group(3)),
                "line": line,
            })
    return sends

ios_sends = extract_ios_sends()
send_by_cmd = {}
for item in ios_sends:
    send_by_cmd.setdefault(item["cmd"], []).append(item)

sony_received = {}
for match in re.finditer(r"\[CTRL-Sony\] command parsed seq=([^\s]+) cmd=([A-Z_]+)", sony_text):
    seq, cmd = match.group(1), match.group(2)
    sony_received.setdefault(cmd, set()).add(seq)

ios_did_write = {}
did_write_costs = []
for match in re.finditer(r"\[CTRL-iOS\] didWrite seq=(\d+) cmd=([A-Z_]+).*?costMs=(\d+).*?error=([^\n]+)", ios_window):
    seq, cmd, cost, error = match.group(1), match.group(2), int(match.group(3)), match.group(4)
    ios_did_write.setdefault(cmd, set()).add(seq)
    did_write_costs.append(cost)

playback_events = []
for line in ios_window.splitlines():
    if "[iOS][Status] playbackState" in line:
        ts = parse_ios_ms(line) or 0
        pos_match = re.search(r"position=(\d+)", line)
        playback_events.append({
            "timeMs": ts,
            "position": int(pos_match.group(1)) if pos_match else 0,
            "line": line,
        })

track_events = []
for line in ios_window.splitlines():
    if "[TrackInfo] updated title=" in line:
        track_events.append({
            "timeMs": parse_ios_ms(line) or 0,
            "line": line,
        })

volume_events = []
for line in ios_window.splitlines():
    if "[VOL-iOS] remote volume received value=" in line or "[Status] volumeState" in line:
        value_match = re.search(r"value=(\d+)|current=(\d+)", line)
        value = 0
        if value_match:
            value = int(next(group for group in value_match.groups() if group is not None))
        volume_events.append({
            "timeMs": parse_ios_ms(line) or 0,
            "value": value,
            "line": line,
        })

control_steps = {}
for line in ios_window.splitlines():
    match = re.search(r"\[ControlE2E\] step=([A-Z_]+) action=start", line)
    if match:
        control_steps[match.group(1)] = parse_ios_ms(line) or 0

seek_targets = []
for line in ios_window.splitlines():
    match = re.search(r"\[ControlE2E\] seek target=(\d+) from=(\d+) duration=(\d+)", line)
    if match:
        seek_targets.append({
            "target": int(match.group(1)),
            "from": int(match.group(2)),
            "duration": int(match.group(3)),
            "timeMs": parse_ios_ms(line) or 0,
        })

def sent(cmd):
    return len(send_by_cmd.get(cmd, [])) > 0

def received(cmd):
    sent_seqs = {item["seq"] for item in send_by_cmd.get(cmd, [])}
    return bool(sent_seqs & sony_received.get(cmd, set()))

def first_send_time(cmd):
    items = send_by_cmd.get(cmd, [])
    return items[0]["timeMs"] if items else 0

def first_playback_after(ms):
    for event in playback_events:
        if event["timeMs"] >= ms:
            return event
    return None

def first_track_after(ms):
    for event in track_events:
        if event["timeMs"] >= ms:
            return event
    return None

def first_volume_after(ms):
    for event in volume_events:
        if event["timeMs"] >= ms:
            return event
    return None

sony_key_evidence = {
    "PLAY_PAUSE": count(r"KEYCODE_MEDIA_PLAY_PAUSE", sony_text) > 0,
    "NEXT": count(r"KEYCODE_MEDIA_NEXT", sony_text) > 0,
    "PREVIOUS": count(r"KEYCODE_MEDIA_PREVIOUS", sony_text) > 0,
}

def operation(cmd, required=True, extra_ok=True):
    cmd_sent = sent(cmd)
    cmd_received = received(cmd)
    write_ok = bool({item["seq"] for item in send_by_cmd.get(cmd, [])} & ios_did_write.get(cmd, set()))
    issues = []
    warnings = []
    if not cmd_sent:
        issues.append("iOS did not send command")
    if cmd_sent and not write_ok:
        issues.append("iOS did not receive didWrite for command")
    if issues:
        result = "FAIL" if required else "WARN"
    else:
        result = "PASS"
    return {
        "result": result,
        "sent": cmd_sent,
        "sonyReceived": cmd_received,
        "sonyParsed": cmd_received,
        "didWrite": write_ok,
        "warnings": warnings,
        "issues": issues,
        "reason": "; ".join(issues or warnings) or "ok",
    }

def mark_effective(name, evidence, evidence_reason, required=True):
    data = ops[name]
    if data["result"] == "FAIL" and any(issue != "Sony did not parse command" for issue in data["issues"]):
        return
    if data.get("sonyParsed"):
        data["sonyReceived"] = True
        data["verifiedBy"] = "sony_command_log"
        data["reason"] = "; ".join(data.get("issues") or data.get("warnings") or []) or "ok"
        return
    if evidence:
        data["sonyReceived"] = True
        data["verifiedBy"] = evidence_reason
        data["warnings"].append(f"Sony parsed log missing; verified by {evidence_reason}")
        if data["result"] != "WARN":
            data["result"] = "PASS"
        data["reason"] = "; ".join(data.get("issues") or data.get("warnings") or []) or "ok"
        return
    data["sonyReceived"] = False
    data["verifiedBy"] = "missing"
    data["issues"].append("no Sony command parse or effect evidence")
    data["result"] = "FAIL" if required else "WARN"
    data["reason"] = "; ".join(data["issues"])

ops = {}
ops["PLAY_PAUSE"] = operation("PLAY_PAUSE", required=True)
play_send = first_send_time("PLAY_PAUSE")
if ops["PLAY_PAUSE"]["result"] == "PASS" and first_playback_after(play_send):
    ops["PLAY_PAUSE"]["playbackStateAfterCommand"] = True
mark_effective("PLAY_PAUSE", sony_key_evidence["PLAY_PAUSE"], "MediaSession KEYCODE_MEDIA_PLAY_PAUSE")
if ops["PLAY_PAUSE"]["result"] == "PASS" and not ops["PLAY_PAUSE"].get("playbackStateAfterCommand"):
    ops["PLAY_PAUSE"]["result"] = "FAIL"
    ops["PLAY_PAUSE"]["issues"].append("no playbackState after PLAY_PAUSE")
    ops["PLAY_PAUSE"]["reason"] = "; ".join(ops["PLAY_PAUSE"]["issues"])

ops["NEXT"] = operation("NEXT", required=True)
next_send = first_send_time("NEXT")
next_track = first_track_after(next_send)
if ops["NEXT"]["result"] == "PASS" and next_track:
    ops["NEXT"]["trackChanged"] = True
    ops["NEXT"]["trackChangedLatencyMs"] = max(next_track["timeMs"] - next_send, 0) if next_send else 0
mark_effective("NEXT", sony_key_evidence["NEXT"], "MediaSession KEYCODE_MEDIA_NEXT")
if ops["NEXT"]["result"] == "PASS" and not ops["NEXT"].get("trackChanged"):
    ops["NEXT"]["result"] = "FAIL"
    ops["NEXT"]["issues"].append("no trackInfo update after NEXT")
    ops["NEXT"]["reason"] = "; ".join(ops["NEXT"]["issues"])

ops["PREVIOUS"] = operation("PREVIOUS", required=False)
prev_send = first_send_time("PREVIOUS")
prev_track = first_track_after(prev_send)
if prev_track:
    ops["PREVIOUS"]["trackChanged"] = True
mark_effective("PREVIOUS", sony_key_evidence["PREVIOUS"], "MediaSession KEYCODE_MEDIA_PREVIOUS", required=False)
if ops["PREVIOUS"]["result"] == "PASS" and not prev_track:
    ops["PREVIOUS"]["result"] = "WARN"
    ops["PREVIOUS"]["warnings"].append("PREVIOUS parsed but no trackInfo update; player history may not allow previous")
    ops["PREVIOUS"]["reason"] = "; ".join(ops["PREVIOUS"]["warnings"])

for volume_cmd in ("VOLUME_UP", "VOLUME_DOWN"):
    ops[volume_cmd] = operation(volume_cmd, required=True)
    volume_send = first_send_time(volume_cmd)
    volume_after = first_volume_after(volume_send)
    if ops[volume_cmd]["result"] == "PASS" and volume_after:
        ops[volume_cmd]["volumeStateAfterCommand"] = True
        ops[volume_cmd]["volumeValue"] = volume_after["value"]
    mark_effective(volume_cmd, volume_after is not None, "volumeState after command")
    if ops[volume_cmd]["result"] == "PASS" and not volume_after:
        ops[volume_cmd]["result"] = "FAIL"
        ops[volume_cmd]["issues"].append(f"no volumeState after {volume_cmd}")
        ops[volume_cmd]["reason"] = "; ".join(ops[volume_cmd]["issues"])

ops["SEEK_TO"] = operation("SEEK_TO", required=True)
seek_send = first_send_time("SEEK_TO")
if ops["SEEK_TO"]["result"] == "PASS":
    seek_event = first_playback_after(seek_send)
    target = seek_targets[-1]["target"] if seek_targets else 0
    if seek_event and target > 0:
        delta = abs(seek_event["position"] - target)
        ops["SEEK_TO"]["targetPositionMs"] = target
        ops["SEEK_TO"]["observedPositionMs"] = seek_event["position"]
        ops["SEEK_TO"]["positionDeltaMs"] = delta
        if delta > 8_000:
            ops["SEEK_TO"]["result"] = "WARN"
            ops["SEEK_TO"]["warnings"].append(f"seek position delta is high ({delta}ms)")
            ops["SEEK_TO"]["reason"] = "; ".join(ops["SEEK_TO"]["warnings"])
        mark_effective("SEEK_TO", True, "playbackState position jump after seek")
    else:
        mark_effective("SEEK_TO", False, "missing playbackState position after seek")
        ops["SEEK_TO"]["result"] = "FAIL"
        ops["SEEK_TO"]["issues"].append("no playbackState position after SEEK_TO")
        ops["SEEK_TO"]["reason"] = "; ".join(ops["SEEK_TO"]["issues"])

ops["GET_FULL_LYRICS"] = operation("GET_FULL_LYRICS", required=True)
full_lyrics_received = count(r"fullLyricsStart|fullLyricsEnd|\[LyricsPerf\] full publish lines=", ios_window)
full_lyrics_unavailable = count(r"fullLyricsUnavailable", ios_window + "\n" + sony_text)
if ops["GET_FULL_LYRICS"]["result"] == "PASS":
    if full_lyrics_received > 0:
        ops["GET_FULL_LYRICS"]["received"] = True
        line_counts = [int(m.group(1)) for m in re.finditer(r"full publish lines=(\d+)", ios_window)]
        ops["GET_FULL_LYRICS"]["lineCount"] = max(line_counts) if line_counts else 0
        mark_effective("GET_FULL_LYRICS", True, "fullLyrics response")
    elif full_lyrics_unavailable > 0:
        mark_effective("GET_FULL_LYRICS", True, "fullLyricsUnavailable response")
        ops["GET_FULL_LYRICS"]["result"] = "WARN"
        ops["GET_FULL_LYRICS"]["warnings"].append("fullLyrics unavailable for current track")
        ops["GET_FULL_LYRICS"]["reason"] = "; ".join(ops["GET_FULL_LYRICS"]["warnings"])
    else:
        mark_effective("GET_FULL_LYRICS", False, "missing fullLyrics response")
        ops["GET_FULL_LYRICS"]["result"] = "FAIL"
        ops["GET_FULL_LYRICS"]["issues"].append("no fullLyrics response observed")
        ops["GET_FULL_LYRICS"]["reason"] = "; ".join(ops["GET_FULL_LYRICS"]["issues"])

ops["ALBUM_ART"] = {
    "result": "WARN",
    "offer": count(r"albumArtOffer|\[AlbumArtOffer\]", ios_window + "\n" + sony_text) > 0,
    "request": count(r"ALBUM_ART_REQUEST|\[AlbumArt\] request|\[AlbumArtHQ\] request|\[NowDiag\] request HQ artwork", ios_window + "\n" + sony_text) > 0,
    "binaryStart": count(r"albumArtBinaryStart|\[AlbumArtBinary\] start", ios_window + "\n" + sony_text),
    "binaryChunk": count(r"albumArtBinaryChunk|\[AlbumArtBinary\] chunk", ios_window + "\n" + sony_text),
    "binaryEnd": count(r"albumArtBinaryEnd|\[AlbumArtBinary\] end", ios_window + "\n" + sony_text),
    "unavailable": count(r"albumArtUnavailable|hq unavailable|request HQ skipped", ios_window + "\n" + sony_text),
    "reason": "album art is optional; waiting for evidence",
}
if ops["ALBUM_ART"]["binaryEnd"] > 0 or count(r"\[AlbumArt\].*decode success|displayQuality=(hq|preview|enhanced)", ios_window) > 0:
    ops["ALBUM_ART"]["result"] = "PASS"
    ops["ALBUM_ART"]["reason"] = "album art transfer or cache display observed"
elif ops["ALBUM_ART"]["unavailable"] > 0:
    ops["ALBUM_ART"]["reason"] = "album art unavailable or request skipped with explicit reason"
elif ops["ALBUM_ART"]["request"] and not ops["ALBUM_ART"]["binaryEnd"]:
    ops["ALBUM_ART"]["reason"] = "album art requested but no binary end observed"
else:
    ops["ALBUM_ART"]["reason"] = "no album art offer/request observed"

command_sent_count = len(ios_sends)
command_received_count = sum(1 for op in ops.values() if op.get("sonyReceived"))
command_success_count = sum(1 for op in ops.values() if op.get("result") == "PASS")
playback_count = len(playback_events)
current_word_raw = count(r'\{"type":"currentWord"', ios_window)
current_word_accepted = count(r"\[Lyrics-iOS\] currentWord line=", ios_window)
stale_discard = count(r"discarded stale|stale discard", ios_window)
payload_too_large = count(r"payload=\d+ max=\d+|Payload maximum size exceeded|payload too large", sony_text + "\n" + ios_window)
main_stall = count(r"main stall detected", ios_window)
track_changed_count = len(track_events)
album_offer_count = count(r"albumArtOffer|\[AlbumArtOffer\]", ios_window)
album_chunk_count = count(r"albumArtBinaryChunk|\[AlbumArtBinary\] chunk", ios_window)
album_end_count = count(r"albumArtBinaryEnd|\[AlbumArtBinary\] end", ios_window)

playback_after_latencies = []
for item in ios_sends:
    event = first_playback_after(item["timeMs"])
    if event:
        playback_after_latencies.append(max(event["timeMs"] - item["timeMs"], 0))

core = ["PLAY_PAUSE", "NEXT", "VOLUME_UP", "VOLUME_DOWN", "SEEK_TO", "GET_FULL_LYRICS"]
issues = []
warnings = []
for name in core:
    result = ops[name]["result"]
    if result == "FAIL":
        issues.append(f"{name}: {ops[name].get('reason', 'failed')}")
    elif result == "WARN":
        warnings.append(f"{name}: {ops[name].get('reason', 'warn')}")
for name in ("PREVIOUS", "ALBUM_ART"):
    if ops[name]["result"] == "WARN":
        warnings.append(f"{name}: {ops[name].get('reason', 'warn')}")
    elif ops[name]["result"] == "FAIL":
        warnings.append(f"{name}: {ops[name].get('reason', 'failed')}")
if stale_discard:
    issues.append(f"stale discard observed ({stale_discard})")
if payload_too_large:
    issues.append(f"payload too large observed ({payload_too_large})")
if main_stall:
    issues.append(f"main stall observed ({main_stall})")

result = "FAIL" if issues else ("WARN" if warnings else "PASS")

sony_filtered = "\n".join(
    line for line in sony_text.splitlines()
    if re.search(r"CTRL-Sony|Media key|VolumeControl|seek|SongChange|PlaybackDiff|PlaybackState|CurrentWordPush|currentWord|FullLyrics|LyricTrace|AlbumArt|payload|ANR|FATAL|Exception", line, re.I)
)
ios_filtered = "\n".join(
    line for line in ios_window.splitlines()
    if re.search(r"ControlE2E|CTRL-iOS|didWrite|playbackState|TrackInfo|VOL-iOS|volumeState|currentWord|fullLyrics|LyricsPerf|AlbumArt|albumArt|payload|main stall|Reconnect", line, re.I)
)
(out_dir / "sony_control_e2e_filtered.log").write_text(sony_filtered, encoding="utf-8")
(out_dir / "ios_control_e2e_filtered.log").write_text(ios_filtered, encoding="utf-8")

metrics = {
    "commandSentCount": command_sent_count,
    "commandReceivedCount": command_received_count,
    "commandSuccessCount": command_success_count,
    "trackChangedCount": track_changed_count,
    "playbackStateReceivedCount": playback_count,
    "currentWordRawCount": current_word_raw,
    "currentWordAcceptedCount": current_word_accepted,
    "fullLyricsReceivedCount": full_lyrics_received,
    "albumArtOfferCount": album_offer_count,
    "albumArtBinaryChunkCount": album_chunk_count,
    "albumArtBinaryEndCount": album_end_count,
    "staleDiscardCount": stale_discard,
    "payloadTooLargeCount": payload_too_large,
    "mainStallCount": main_stall,
    "commandLatencyAvgMs": int(statistics.mean(did_write_costs)) if did_write_costs else 0,
    "commandLatencyP95Ms": quantile_p95(did_write_costs),
    "playbackStateAfterCommandLatencyAvgMs": int(statistics.mean(playback_after_latencies)) if playback_after_latencies else 0,
    "playbackStateAfterCommandLatencyP95Ms": quantile_p95(playback_after_latencies),
    "trackChangedAfterNextLatencyMs": ops["NEXT"].get("trackChangedLatencyMs", 0),
    "seekPositionDeltaMs": ops["SEEK_TO"].get("positionDeltaMs", 0),
}

precheck_path = out_dir / "ios_ble_precheck.json"
precheck = json.loads(precheck_path.read_text()) if precheck_path.exists() else {}
summary = {
    "result": result,
    "durationSec": duration_sec,
    "warnings": warnings,
    "issues": issues,
    "iosAppLaunched": precheck.get("iosAppLaunched", False),
    "iosBleConnected": precheck.get("iosBleConnected", False),
    "notifySubscribed": precheck.get("notifySubscribed", False),
    "firstPlaybackStateReceived": precheck.get("firstPlaybackStateReceived", False),
    "firstPlaybackStateLatencyMs": precheck.get("firstPlaybackStateLatencyMs", 0),
    "precheckResult": precheck.get("precheckResult", "UNKNOWN"),
    "precheckFailReason": precheck.get("precheckFailReason", ""),
}
payload = {
    "summary": summary,
    "precheck": precheck,
    "devices": {
        "ios": ios_device,
        "android": android_device,
    },
    "operations": ops,
    "metrics": metrics,
    "artifacts": {
        "report_md": str(out_dir / "report.md"),
        "report_json": str(out_dir / "report.json"),
        "ios_ble_log": str(ios_after_path),
        "sony_logcat": str(sony_log_path),
        "ios_filtered": str(out_dir / "ios_control_e2e_filtered.log"),
        "sony_filtered": str(out_dir / "sony_control_e2e_filtered.log"),
    },
}

md = [
    "# Control E2E V2.9 Smoke",
    "",
    f"Result: {result}",
    f"Duration: {duration_sec}s",
    "",
    "## Operations",
    "",
    "| Operation | Result | Reason |",
    "| --- | --- | --- |",
]
for name, data in ops.items():
    md.append(f"| {name} | {data.get('result')} | {data.get('reason', '-')} |")
md += [
    "",
    "## Metrics",
    "",
]
for key, value in metrics.items():
    md.append(f"- {key}: `{value}`")
if warnings:
    md += ["", "## Warnings"] + [f"- {item}" for item in warnings]
if issues:
    md += ["", "## Issues"] + [f"- {item}" for item in issues]
md += [
    "",
    "## Artifacts",
    "",
    f"- report.json: `{out_dir / 'report.json'}`",
    f"- ios_ble.log: `{ios_after_path}`",
    f"- sony_logcat.log: `{sony_log_path}`",
    f"- ios filtered: `{out_dir / 'ios_control_e2e_filtered.log'}`",
    f"- sony filtered: `{out_dir / 'sony_control_e2e_filtered.log'}`",
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
