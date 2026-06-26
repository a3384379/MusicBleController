#!/usr/bin/env bash
set -euo pipefail

OUT_DIR="${OUT_DIR:-/tmp/music_ble_ios_smoke/manual}"
LOG_FILE=""
ALBUM_ART_ID=""
WINDOW_LINES="${ALBUM_ART_WINDOW_LINES:-8000}"
COST_MS="${ALBUM_ART_FLOW_COST_MS:-0}"

usage() {
  cat <<'EOF'
Usage: ios_album_art_flow_test.sh <ios_ble.log> [options]

Options:
  --album-art-id <id>   Analyze a specific albumArtId instead of the latest one.
  --window-lines <n>    Analyze only the last n log lines. Default: 8000.
  --cost-ms <n>         Cost value to include in the optional TSV row.
  -h, --help            Show this help.

Outputs one optional TSV row and writes album_art_flow.json into OUT_DIR.
EOF
}

if [[ $# -gt 0 && "$1" != --* ]]; then
  LOG_FILE="$1"
  shift
fi

while [[ $# -gt 0 ]]; do
  case "$1" in
    --album-art-id)
      ALBUM_ART_ID="${2:?--album-art-id requires an id}"
      shift 2
      ;;
    --window-lines)
      WINDOW_LINES="${2:?--window-lines requires a number}"
      shift 2
      ;;
    --cost-ms)
      COST_MS="${2:?--cost-ms requires a number}"
      shift 2
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

if [[ -z "$LOG_FILE" ]]; then
  LOG_FILE="${OUT_DIR}/ios_ble.log"
fi

mkdir -p "$OUT_DIR"

python3 - "$LOG_FILE" "$OUT_DIR" "$ALBUM_ART_ID" "$WINDOW_LINES" "$COST_MS" <<'PY'
import json
import re
import sys
from pathlib import Path

log_path = Path(sys.argv[1])
out_dir = Path(sys.argv[2])
requested_id = sys.argv[3].strip()
try:
    window_lines = int(sys.argv[4])
except ValueError:
    window_lines = 8000
cost_ms = sys.argv[5].strip() or "0"

result_path = out_dir / "album_art_flow.json"


def write_result(payload):
    result_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    detail = payload["detail"].replace("\t", " ").replace("\n", " ")
    fields = [
        "optional",
        "AlbumArt Flow",
        payload["result"],
        cost_ms,
        detail,
        f"albumArtId={payload.get('albumArtId') or '-'}",
        f"finalQuality={payload.get('finalQuality') or '-'}",
        f"preview={str(payload.get('preview', False)).lower()}",
        f"hq={str(payload.get('hq', False)).lower()}",
        f"enhanced={str(payload.get('enhanced', False)).lower()}",
        f"timeout={str(payload.get('timeout', False)).lower()}",
        f"reason={payload.get('reason') or '-'}",
        f"hqPrefetchScheduled={payload.get('hqPrefetchScheduled', 0)}",
        f"hqPrefetchSent={payload.get('hqPrefetchSent', 0)}",
        f"hqPrefetchSkippedCacheHit={payload.get('hqPrefetchSkippedCacheHit', 0)}",
        f"hqPrefetchSkippedInFlight={payload.get('hqPrefetchSkippedInFlight', 0)}",
        f"hqPrefetchSkippedNotConnected={payload.get('hqPrefetchSkippedNotConnected', 0)}",
        f"hqPrefetchCancelledTrackChanged={payload.get('hqPrefetchCancelledTrackChanged', 0)}",
        f"offerToHqRequestMs={payload.get('offerToHqRequestMs', 0)}",
        f"offerToHqReadyMs={payload.get('offerToHqReadyMs', 0)}",
    ]
    print("\t".join(fields))


if not log_path.exists() or log_path.stat().st_size == 0:
    write_result({
        "result": "SKIPPED",
        "albumArtId": "",
        "finalQuality": "unknown",
        "preview": False,
        "hq": False,
        "enhanced": False,
        "timeout": False,
        "reason": "log missing",
        "detail": "ios_ble.log missing or empty",
    })
    sys.exit(0)

all_lines = log_path.read_text(encoding="utf-8", errors="replace").splitlines()
lines = all_lines[-window_lines:] if window_lines > 0 else all_lines

id_patterns = [
    re.compile(r"\[AlbumArt\] offer id=([^\s,]+)"),
    re.compile(r"\[AlbumArtOffer\] id=([^\s,]+)"),
    re.compile(r"albumArtOffer.*(?:id|albumArtId)[=:]\"?([^\"}\s,]+)"),
    re.compile(r"\[AlbumArt(?:Binary|-iOS|Cache|HQ)?\].* id=([^\s,]+)"),
    re.compile(r"\[ArtworkDisplay\] id=([^\s,]+)"),
]


def extract_id(line):
    for pattern in id_patterns:
        match = pattern.search(line)
        if match:
            value = match.group(1).strip().strip(",")
            if value and value != "-":
                return value
    return ""


ids = []
for line in lines:
    album_id = extract_id(line)
    if album_id:
        ids.append(album_id)

target_id = requested_id or (ids[-1] if ids else "")

sony_seen = any(
    token in line
    for line in lines
    for token in ("didConnect", "notify subscribed", "playbackState", "albumArtOffer", "[AlbumArt")
)

if not target_id:
    result = "WARN" if sony_seen else "SKIPPED"
    write_result({
        "result": result,
        "albumArtId": "",
        "finalQuality": "placeholder",
        "preview": False,
        "hq": False,
        "enhanced": False,
        "timeout": False,
        "reason": "no albumArtId",
        "detail": "no albumArtId or albumArtOffer found in iOS log",
        "events": [],
    })
    sys.exit(0)


def related(line):
    return target_id in line or ("[AlbumArt" in line or "[ArtworkDisplay" in line or "[ArtworkEnhance" in line)


events = []
state = {
    "offer": False,
    "previewRequest": False,
    "previewReceived": False,
    "hqRequest": False,
    "hqReceived": False,
    "fallbackHq": False,
    "enhancedStarted": False,
    "enhancedCompleted": False,
    "enhancedSkipped": False,
    "timeout": False,
    "timeoutRecovered": False,
    "transferStart": False,
    "transferEnd": False,
    "transferComplete": False,
    "transferCancelled": False,
    "inProgressBlocked": False,
    "cacheSaveFailed": False,
    "hqUnavailable": False,
    "predictiveOffer": False,
    "hqPrefetchScheduled": 0,
    "hqPrefetchSent": 0,
    "hqPrefetchSkippedCacheHit": 0,
    "hqPrefetchSkippedInFlight": 0,
    "hqPrefetchSkippedNotConnected": 0,
    "hqPrefetchCancelledTrackChanged": 0,
}
final_quality = "placeholder"
last_start_index = None
last_cleanup_index = None
offer_to_hq_request_ms = 0
offer_to_hq_ready_ms = 0

quality_rank = {
    "placeholder": 0,
    "preview": 1,
    "hqFallback": 2,
    "hq": 3,
    "enhanced": 4,
}


def set_quality(value):
    global final_quality
    if quality_rank.get(value, 0) >= quality_rank.get(final_quality, 0):
        final_quality = value


for idx, line in enumerate(lines):
    if target_id not in line and not any(marker in line for marker in ("ALBUM_ART_REQUEST", "[AlbumArt", "[ArtworkDisplay", "[ArtworkEnhance")):
        continue

    lower = line.lower()
    event = None

    if "albumartoffer" in lower or "[albumart] offer" in lower:
        state["offer"] = True
        event = "offer"
    if "[predictivealbumart]" in lower:
        if " offer " in lower or lower.endswith(" offer"):
            state["predictiveOffer"] = True
            event = "predictive offer"
        if "schedule hq" in lower:
            state["hqPrefetchScheduled"] += 1
            event = "predictive schedule hq"
        if "request hq" in lower:
            state["hqPrefetchSent"] += 1
            event = "predictive request hq"
        if "skip hq" in lower:
            event = "predictive skip hq"
            if "cache hit" in lower:
                state["hqPrefetchSkippedCacheHit"] += 1
            elif "in flight" in lower:
                state["hqPrefetchSkippedInFlight"] += 1
            elif "not connected" in lower:
                state["hqPrefetchSkippedNotConnected"] += 1
        if "cancel pending" in lower and "track changed" in lower:
            state["hqPrefetchCancelledTrackChanged"] += 1
            event = "predictive cancel"
        request_match = re.search(r"offerToHqRequestMs=(\d+)", line)
        if request_match:
            offer_to_hq_request_ms = int(request_match.group(1))
            event = event or "predictive request latency"
        ready_match = re.search(r"offerToHqReadyMs=(\d+)", line)
        if ready_match:
            offer_to_hq_ready_ms = int(ready_match.group(1))
            event = event or "predictive ready latency"
    if "album_art_request" in lower or "[albumart] request " in lower:
        if "quality=preview" in lower or "request preview" in lower or '"quality":"preview"' in lower:
            state["previewRequest"] = True
            event = "preview request"
        if "quality=hq" in lower or "request hq" in lower or '"quality":"hq"' in lower:
            state["hqRequest"] = True
            event = "hq request"
    if "[albumartbinary] start" in lower or "[albumart-ios] transfer start" in lower or "[albumart] start" in lower:
        state["transferStart"] = True
        last_start_index = idx
        event = "transfer start"
    if "[albumartbinary] end" in lower or "[albumart] end" in lower:
        state["transferEnd"] = True
        last_cleanup_index = idx
        event = "transfer end"
    if "transfer complete" in lower or "decode success" in lower:
        state["transferComplete"] = True
        last_cleanup_index = idx
        event = "transfer complete"
        if "quality=preview" in lower or "preview decode success" in lower or "preview" in lower:
            state["previewReceived"] = True
            set_quality("preview")
        if "quality=hq" in lower or "quality=full" in lower or "hq decode success" in lower or " full " in lower:
            state["hqReceived"] = True
            set_quality("hq")
    if "[albumartcache] saved" in lower:
        event = "cache saved"
        if "quality=preview" in lower:
            state["previewReceived"] = True
            set_quality("preview")
        if "quality=hq" in lower or "quality=full" in lower:
            state["hqReceived"] = True
            set_quality("hq")
    if "[albumartcache] display quality=" in lower or "[albumartcache] hit" in lower:
        event = "cache display"
        if "quality=enhanced" in lower:
            state["enhancedCompleted"] = True
            set_quality("enhanced")
        elif "quality=hq" in lower:
            state["hqReceived"] = True
            set_quality("hq")
        elif "quality=preview" in lower:
            state["previewReceived"] = True
            set_quality("preview")
    if "[artworkdisplay]" in lower:
        event = "display quality"
        if "-> enhanced" in lower or "incoming=enhanced" in lower:
            state["enhancedCompleted"] = True
            set_quality("enhanced")
        elif "-> hqfallback" in lower or "incoming=hqfallback" in lower:
            state["fallbackHq"] = True
            set_quality("hqFallback")
        elif "-> hq" in lower or "incoming=hq" in lower:
            state["hqReceived"] = True
            set_quality("hq")
        elif "-> preview" in lower or "incoming=preview" in lower:
            state["previewReceived"] = True
            set_quality("preview")
    if "fallback" in lower:
        if "hq" in lower:
            state["fallbackHq"] = True
            set_quality("hqFallback")
        event = event or "fallback"
    if "unavailable" in lower and "quality=hq" in lower:
        state["hqUnavailable"] = True
        event = event or "hq unavailable"
    if "timeout" in lower:
        state["timeout"] = True
        event = event or "timeout"
    if "transfer cancelled" in lower or "cancel in-flight" in lower:
        state["transferCancelled"] = True
        last_cleanup_index = idx
        if "timeout" in lower:
            state["timeoutRecovered"] = True
        event = event or "transfer cancelled"
    if "transfer in progress" in lower:
        state["inProgressBlocked"] = True
        event = event or "in-progress blocked"
    if "save failed" in lower:
        state["cacheSaveFailed"] = True
        event = event or "cache save failed"
    if "[artworkenhance]" in lower:
        if "start" in lower or "processing" in lower or "targetpixelsize" in lower:
            state["enhancedStarted"] = True
        if "enhancement complete" in lower or "a/b enhanced" in lower:
            state["enhancedCompleted"] = True
            set_quality("enhanced")
        if "skipped" in lower or "disabled" in lower or "cache ignored" in lower or "held" in lower:
            state["enhancedSkipped"] = True
        event = event or "enhanced"

    if event:
        events.append({"line": idx, "event": event, "text": line[-260:]})

incomplete_transfer = (
    state["transferStart"]
    and last_start_index is not None
    and (last_cleanup_index is None or last_cleanup_index < last_start_index)
)

if state["cacheSaveFailed"]:
    result = "FAIL"
    reason = "cache save failed"
elif incomplete_transfer:
    result = "FAIL"
    reason = "transfer in progress stuck"
elif state["hqUnavailable"] and state["inProgressBlocked"]:
    result = "FAIL"
    reason = "hq unavailable left request blocked"
elif final_quality in {"preview", "hq", "enhanced", "hqFallback"}:
    result = "PASS"
    reason = "non-placeholder artwork displayed"
elif state["offer"] and not state["hqRequest"] and state["hqPrefetchScheduled"] == 0 and state["hqPrefetchSkippedCacheHit"] == 0:
    result = "WARN"
    reason = "albumArt offer without hq request or predictive reason"
elif state["timeout"] and state["transferCancelled"]:
    result = "WARN"
    reason = "timeout recovered without final artwork"
elif state["offer"] or state["previewRequest"] or state["hqRequest"] or state["transferStart"]:
    result = "WARN"
    reason = "artwork flow observed but no final display quality"
else:
    result = "WARN" if sony_seen else "SKIPPED"
    reason = "no albumArt flow observed"

detail = (
    f"albumArtId={target_id} finalQuality={final_quality} "
    f"offer={state['offer']} preview={state['previewReceived']} "
    f"hq={state['hqReceived']} enhanced={state['enhancedCompleted']} "
    f"timeout={state['timeout']} "
    f"hqPrefetchScheduled={state['hqPrefetchScheduled']} "
    f"hqPrefetchSent={state['hqPrefetchSent']} "
    f"offerToHqRequestMs={offer_to_hq_request_ms} "
    f"offerToHqReadyMs={offer_to_hq_ready_ms} reason={reason}"
)

write_result({
    "result": result,
    "albumArtId": target_id,
    "finalQuality": final_quality,
    "preview": state["previewReceived"],
    "previewRequest": state["previewRequest"],
    "hq": state["hqReceived"],
    "hqRequest": state["hqRequest"],
    "fallbackHq": state["fallbackHq"],
    "enhanced": state["enhancedCompleted"],
    "enhancedStarted": state["enhancedStarted"],
    "enhancedSkipped": state["enhancedSkipped"],
    "timeout": state["timeout"],
    "timeoutRecovered": state["timeoutRecovered"],
    "transferStart": state["transferStart"],
    "transferEnd": state["transferEnd"],
    "transferComplete": state["transferComplete"],
    "transferCancelled": state["transferCancelled"],
    "inProgressBlocked": state["inProgressBlocked"],
    "hqUnavailable": state["hqUnavailable"],
    "predictiveOffer": state["predictiveOffer"],
    "hqPrefetchScheduled": state["hqPrefetchScheduled"],
    "hqPrefetchSent": state["hqPrefetchSent"],
    "hqPrefetchSkippedCacheHit": state["hqPrefetchSkippedCacheHit"],
    "hqPrefetchSkippedInFlight": state["hqPrefetchSkippedInFlight"],
    "hqPrefetchSkippedNotConnected": state["hqPrefetchSkippedNotConnected"],
    "hqPrefetchCancelledTrackChanged": state["hqPrefetchCancelledTrackChanged"],
    "offerToHqRequestMs": offer_to_hq_request_ms,
    "offerToHqReadyMs": offer_to_hq_ready_ms,
    "reason": reason,
    "detail": detail,
    "events": events[-80:],
    "log": str(log_path),
    "windowLines": window_lines,
})
PY
