#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

OUTPUT="$("$SCRIPT_DIR/run_ios_smoke_tests.sh" --quick --json)"
echo "$OUTPUT"

REPORT_JSON="$(python3 -c 'import json,sys; print(json.loads(sys.stdin.read())["report_json"])' <<<"$OUTPUT")"

SUMMARY="$(python3 - "$REPORT_JSON" <<'PY'
import json
import sys
from pathlib import Path

data = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
summary = data["summary"]
print(
    f"Required {summary['required_pass']}/{summary['required_total']} "
    f"Optional pass={summary['optional_pass']} warn={summary['optional_warn']} "
    f"skipped={summary['optional_skipped']} overall={summary['overall_result']}"
)
PY
)"

echo "$SUMMARY"

OVERALL="$(python3 - "$REPORT_JSON" <<'PY'
import json
import sys
from pathlib import Path

data = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
print(data["summary"]["overall_result"])
PY
)"

if [[ "$OVERALL" == "PASS" ]]; then
  echo "PASS"
  exit 0
fi

echo "FAIL"
FAILURE_EXCERPT="$(python3 - "$REPORT_JSON" <<'PY'
import json
import sys
from pathlib import Path

data = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
print(data.get("artifacts", {}).get("failure_excerpt", ""))
PY
)"
if [[ -n "$FAILURE_EXCERPT" && -f "$FAILURE_EXCERPT" ]]; then
  echo "failure_excerpt=$FAILURE_EXCERPT"
fi
exit 1
