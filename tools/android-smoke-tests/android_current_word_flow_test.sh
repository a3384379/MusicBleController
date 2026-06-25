#!/usr/bin/env bash
set -euo pipefail

LOG_PATH="${1:-}"
if [[ -z "$LOG_PATH" || ! -f "$LOG_PATH" ]]; then
  printf 'optional\tCurrentWord Flow\tWARN\t0\tlogcat missing; cannot evaluate currentWord flow\n'
  exit 0
fi

OUT_DIR="${OUT_DIR:-$(dirname "$LOG_PATH")}"
RESULT_JSON="$OUT_DIR/current_word_flow.json"

python3 - "$LOG_PATH" "$RESULT_JSON" <<'PY'
import json
import re
import sys
from pathlib import Path

log_path = Path(sys.argv[1])
json_path = Path(sys.argv[2])
text = log_path.read_text(encoding="utf-8", errors="replace")
lines = text.splitlines()

has_subscriber = any(
    re.search(pattern, text, re.I)
    for pattern in (
        r"status notify subscribed",
        r"CLIENT_CAPABILITIES",
        r"subscribedDevices=\[[^\]]+\]",
    )
)

metrics_re = re.compile(
    r"\[CurrentWordPush\]\s+metrics\s+"
    r"push=(?P<push>\d+)\s+"
    r"skip=(?P<skip>\d+)\s+"
    r"avgIntervalMs=(?P<avg>\d+)\s+"
    r"lastPushCostMs=(?P<cost>\d+)",
    re.I,
)
push_re = re.compile(
    r"\[CurrentWordPush\]\s+push .*line=(?P<line>-?\d+)\s+word=(?P<word>-?\d+).*costMs=(?P<cost>\d+)",
    re.I,
)
skip_re = re.compile(r"\[CurrentWordPush\]\s+skip", re.I)
started = "[CurrentWordPush] started" in text

last_metrics = None
push_events = 0
skip_events = 0
last_line = -1
last_word = -1
last_cost = 0
for line in lines:
    metrics = metrics_re.search(line)
    if metrics:
        last_metrics = {
            "pushCount": int(metrics.group("push")),
            "skipCount": int(metrics.group("skip")),
            "averageIntervalMs": int(metrics.group("avg")),
            "lastPushCostMs": int(metrics.group("cost")),
        }
    push = push_re.search(line)
    if push:
        push_events += 1
        last_line = int(push.group("line"))
        last_word = int(push.group("word"))
        last_cost = int(push.group("cost"))
    if skip_re.search(line):
        skip_events += 1

metrics = last_metrics or {
    "pushCount": push_events,
    "skipCount": skip_events,
    "averageIntervalMs": 0,
    "lastPushCostMs": last_cost,
}

if not has_subscriber:
    result = "SKIPPED"
    reason = "no iPhone subscriber; CurrentWord Flow not exercised"
elif not started and metrics["pushCount"] == 0:
    result = "WARN"
    reason = "subscriber detected but currentWord push engine did not start"
elif metrics["pushCount"] > 0:
    result = "PASS"
    reason = "currentWord pushes found"
else:
    result = "WARN"
    reason = "currentWord engine active but no word pushes found"

payload = {
    "result": result,
    "reason": reason,
    "hasSubscriber": has_subscriber,
    "started": started,
    "pushCount": metrics["pushCount"],
    "skipCount": metrics["skipCount"],
    "averageIntervalMs": metrics["averageIntervalMs"],
    "lastPushCostMs": metrics["lastPushCostMs"],
    "lastLine": last_line,
    "lastWord": last_word,
    "pushEventsInLog": push_events,
    "skipEventsInLog": skip_events,
    "metricsLineFound": last_metrics is not None,
}
json_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")

detail = (
    f"push={payload['pushCount']} skip={payload['skipCount']} "
    f"avgIntervalMs={payload['averageIntervalMs']} lastCostMs={payload['lastPushCostMs']} "
    f"lastLine={payload['lastLine']} lastWord={payload['lastWord']} reason={reason}"
)
print(f"optional\tCurrentWord Flow\t{result}\t0\t{detail}")
PY
