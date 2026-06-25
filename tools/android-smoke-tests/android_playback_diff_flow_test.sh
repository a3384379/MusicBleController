#!/usr/bin/env bash
set -euo pipefail

LOG_PATH="${1:-}"
if [[ -z "$LOG_PATH" || ! -f "$LOG_PATH" ]]; then
  printf 'optional\tPlaybackDiff Flow\tWARN\t0\tlogcat missing; cannot evaluate playback diff flow\n'
  exit 0
fi

OUT_DIR="${OUT_DIR:-$(dirname "$LOG_PATH")}"
RESULT_JSON="$OUT_DIR/playback_diff_flow.json"

python3 - "$LOG_PATH" "$RESULT_JSON" <<'PY'
import json
import re
import sys
from pathlib import Path

log_path = Path(sys.argv[1])
json_path = Path(sys.argv[2])
text = log_path.read_text(encoding="utf-8", errors="replace")
lines = text.splitlines()

subscriber_patterns = [
    re.compile(r"status notify subscribed", re.I),
    re.compile(r"subscribedDevices=\[[^\]]+\]", re.I),
    re.compile(r"CLIENT_CAPABILITIES", re.I),
]
empty_subscriber_patterns = [
    re.compile(r"subscribedDevices=\[\]", re.I),
]

has_subscriber = any(pattern.search(text) for pattern in subscriber_patterns)
if not has_subscriber and any(pattern.search(text) for pattern in empty_subscriber_patterns):
    has_subscriber = False

metrics_re = re.compile(
    r"\[PlaybackDiff\]\s+metrics\s+"
    r"snapshots=(?P<snapshots>\d+)\s+"
    r"diffs=(?P<diffs>\d+)\s+"
    r"push=(?P<push>\d+)\s+"
    r"skip=(?P<skip>\d+)\s+"
    r"trackChanged=(?P<track>\d+)\s+"
    r"wordChanged=(?P<word>\d+)\s+"
    r"positionJump=(?P<jump>\d+)",
    re.I,
)
push_re = re.compile(r"\[PlaybackDiff\]\s+push playback type=([A-Za-z]+)", re.I)
skip_re = re.compile(r"\[PlaybackDiff\]\s+skip identical", re.I)

last_metrics = None
push_events = 0
skip_events = 0
push_types: dict[str, int] = {}
for line in lines:
    metrics_match = metrics_re.search(line)
    if metrics_match:
        last_metrics = {
            "snapshotBuildCount": int(metrics_match.group("snapshots")),
            "diffCount": int(metrics_match.group("diffs")),
            "pushCount": int(metrics_match.group("push")),
            "skipCount": int(metrics_match.group("skip")),
            "trackChangedCount": int(metrics_match.group("track")),
            "wordChangedCount": int(metrics_match.group("word")),
            "positionJumpCount": int(metrics_match.group("jump")),
        }
    push_match = push_re.search(line)
    if push_match:
        push_events += 1
        kind = push_match.group(1)
        push_types[kind] = push_types.get(kind, 0) + 1
    if skip_re.search(line):
        skip_events += 1

metrics = last_metrics or {
    "snapshotBuildCount": push_events + skip_events,
    "diffCount": push_events + skip_events,
    "pushCount": push_events,
    "skipCount": skip_events,
    "trackChangedCount": push_types.get("TrackChanged", 0),
    "wordChangedCount": push_types.get("CurrentWordChanged", 0),
    "positionJumpCount": push_types.get("PositionJump", 0),
}

push_count = metrics["pushCount"]
skip_count = metrics["skipCount"]
total_decisions = push_count + skip_count
skip_ratio = (skip_count / total_decisions) if total_decisions else 0.0

if not has_subscriber:
    result = "SKIPPED"
    reason = "no iPhone subscriber; PlaybackDiff Flow not exercised"
elif total_decisions == 0:
    result = "WARN"
    reason = "subscriber detected but no PlaybackDiff decisions found"
elif skip_count > push_count:
    result = "PASS"
    reason = "skipCount greater than pushCount"
else:
    result = "WARN"
    reason = "PlaybackDiff active but skipCount is not greater than pushCount"

payload = {
    "result": result,
    "reason": reason,
    "hasSubscriber": has_subscriber,
    "snapshotBuildCount": metrics["snapshotBuildCount"],
    "diffCount": metrics["diffCount"],
    "pushCount": push_count,
    "skipCount": skip_count,
    "skipRatio": round(skip_ratio, 4),
    "trackChangedCount": metrics["trackChangedCount"],
    "wordChangedCount": metrics["wordChangedCount"],
    "positionJumpCount": metrics["positionJumpCount"],
    "pushEventsInLog": push_events,
    "skipEventsInLog": skip_events,
    "pushTypes": push_types,
    "metricsLineFound": last_metrics is not None,
}
json_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")

detail = (
    f"snapshots={payload['snapshotBuildCount']} diffs={payload['diffCount']} "
    f"push={payload['pushCount']} skip={payload['skipCount']} "
    f"skipRatio={payload['skipRatio']:.2f} "
    f"trackChanged={payload['trackChangedCount']} "
    f"wordChanged={payload['wordChangedCount']} "
    f"positionJump={payload['positionJumpCount']} "
    f"reason={reason}"
)
print(f"optional\tPlaybackDiff Flow\t{result}\t0\t{detail}")
PY
