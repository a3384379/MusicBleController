#!/usr/bin/env bash
set -euo pipefail

OUT_DIR="${OUT_DIR:-/tmp/music_ble_ios_smoke/manual}"
LOG_FILE="${1:-$OUT_DIR/ios_ble.log}"
COST_MS="${CURRENT_WORD_FLOW_COST_MS:-0}"

mkdir -p "$OUT_DIR"

python3 - "$LOG_FILE" "$OUT_DIR/current_word_flow.json" "$COST_MS" <<'PY'
import json
import re
import sys
from pathlib import Path

log_path = Path(sys.argv[1])
json_path = Path(sys.argv[2])
cost_ms = sys.argv[3]

def emit(payload):
    json_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    detail = (
        f"raw={payload['receivedRawCount']} accepted={payload['acceptedCount']} "
        f"stale={payload['discardedStaleCount']} normalized={payload['normalizedAcceptedCount']} "
        f"avgIntervalMs={payload['averageIntervalMs']} "
        f"lastLatencyMs={payload['lastLatencyMs']} reason={payload['reason']}"
    )
    print(f"optional\tCurrentWord Flow\t{payload['result']}\t{cost_ms}\t{detail}")

if not log_path.exists() or log_path.stat().st_size == 0:
    emit({
        "result": "SKIPPED",
        "reason": "ios log missing",
        "receivedRawCount": 0,
        "acceptedCount": 0,
        "discardedStaleCount": 0,
        "normalizedAcceptedCount": 0,
        "sonyTrackIdSample": "",
        "iosCurrentTrackIdSample": "",
        "receivedCount": 0,
        "dropCount": 0,
        "averageIntervalMs": 0,
        "lastLatencyMs": 0,
        "lastLine": -1,
        "lastWord": -1,
    })
    sys.exit(0)

text = log_path.read_text(encoding="utf-8", errors="replace")
has_sony = any(token in text for token in ("didConnect", "notify subscribed", "playbackState"))
raw_re = re.compile(r'\{"type":"currentWord","trackId":"(?P<track>[^"]+)"', re.I)
received_re = re.compile(
    r"\[Lyrics-iOS\]\s+currentWord\s+line=(?P<line>-?\d+)\s+word=(?P<word>-?\d+)"
    r".*latencyMs=(?P<latency>\d+)(?:.*avgIntervalMs=(?P<avg>\d+))?",
    re.I,
)
drop_re = re.compile(
    r"\[Lyrics-iOS\]\s+currentWord discarded stale trackId=(?P<incoming>\S+)\s+current=(?P<current>\S+)",
    re.I,
)
normalized_re = re.compile(
    r"\[Lyrics-iOS\]\s+currentWord accepted by normalized trackId\s+incoming=(?P<incoming>\S+)\s+current=(?P<current>\S+)",
    re.I,
)

raw_received = 0
accepted = 0
discarded_stale = 0
normalized_accepted = 0
sony_track_id_sample = ""
ios_current_track_id_sample = ""
last_line = -1
last_word = -1
last_latency = 0
last_average_interval = 0
for line in text.splitlines():
    raw_match = raw_re.search(line)
    if raw_match:
        raw_received += 1
        if not sony_track_id_sample:
            sony_track_id_sample = raw_match.group("track")
    match = received_re.search(line)
    if match:
        accepted += 1
        last_line = int(match.group("line"))
        last_word = int(match.group("word"))
        last_latency = int(match.group("latency"))
        if match.group("avg"):
            last_average_interval = int(match.group("avg"))
    drop_match = drop_re.search(line)
    if drop_match:
        discarded_stale += 1
        if not sony_track_id_sample:
            sony_track_id_sample = drop_match.group("incoming")
        if not ios_current_track_id_sample:
            ios_current_track_id_sample = drop_match.group("current")
    normalized_match = normalized_re.search(line)
    if normalized_match:
        normalized_accepted += 1
        if not sony_track_id_sample:
            sony_track_id_sample = normalized_match.group("incoming")
        if not ios_current_track_id_sample:
            ios_current_track_id_sample = normalized_match.group("current")

metrics_match = re.findall(
    r"currentWordPushCount=(\d+).*?currentWordDropCount=(\d+)",
    text,
    flags=re.I | re.S,
)

if raw_received > 0 and accepted == 0 and discarded_stale > 0:
    result = "FAIL"
    reason = "currentWord received but all discarded as stale"
elif normalized_accepted > 0:
    result = "PASS"
    reason = "currentWord accepted by normalized trackId"
elif accepted > 0 and discarded_stale == 0:
    result = "PASS"
    reason = "currentWord notifications received"
elif has_sony:
    result = "WARN"
    reason = "Sony connected but no currentWord notifications found"
else:
    result = "SKIPPED"
    reason = "Sony not connected; CurrentWord Flow not exercised"

emit({
    "result": result,
    "reason": reason,
    "receivedRawCount": raw_received,
    "acceptedCount": accepted,
    "discardedStaleCount": discarded_stale,
    "normalizedAcceptedCount": normalized_accepted,
    "sonyTrackIdSample": sony_track_id_sample,
    "iosCurrentTrackIdSample": ios_current_track_id_sample,
    "receivedCount": accepted,
    "dropCount": discarded_stale,
    "averageIntervalMs": last_average_interval,
    "lastLatencyMs": last_latency,
    "lastLine": last_line,
    "lastWord": last_word,
    "diagnosticMetricsFound": bool(metrics_match),
})
PY
