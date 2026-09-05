#!/usr/bin/env python3
"""Generate a privacy-safe Phase 3 predictive handoff report from V4 traces."""

from __future__ import annotations

import argparse
import json
import math
import re
import statistics
from collections import Counter
from pathlib import Path
from typing import Any


TRACE_PREFIX = "[RealtimeTrace] "
PREDICTIVE_RE = re.compile(
    r"\[PredictiveMedia\] stage=(\S+) candidateId=(\S+) identityDigest=(\S+) "
    r"source=(\S+) confidence=(\S+) result=(\S+) reason=(.*)"
)


def parse_trace_lines(text: str, default_side: str) -> list[dict[str, Any]]:
    events: list[dict[str, Any]] = []
    seen: set[tuple[Any, ...]] = set()
    for source_line, line in enumerate(text.splitlines(), 1):
        marker = line.find(TRACE_PREFIX)
        if marker < 0:
            continue
        fields: dict[str, str] = {}
        for token in line[marker + len(TRACE_PREFIX):].strip().split():
            if "=" in token:
                key, value = token.split("=", 1)
                fields[key] = value
        try:
            mono_ms = int(fields.get("monoMs", ""))
        except ValueError:
            continue
        event = {
            "side": fields.get("side", default_side),
            "stage": fields.get("stage", ""),
            "monoMs": mono_ms,
            "trackId": none_if_dash(fields.get("trackId")),
            "generation": int_or_none(fields.get("generation")),
            "transferId": none_if_dash(fields.get("transferId")),
            "payloadType": none_if_dash(fields.get("payloadType")),
            "result": none_if_dash(fields.get("result")),
            "reason": none_if_dash(fields.get("reason")),
            "sourceLine": source_line,
        }
        identity = (
            event["side"],
            event["stage"],
            event["monoMs"],
            event["trackId"],
            event["generation"],
            event["transferId"],
        )
        if identity in seen:
            continue
        seen.add(identity)
        events.append(event)
    return events


def parse_predictive_lines(text: str) -> list[dict[str, str]]:
    events = []
    seen = set()
    for line in text.splitlines():
        match = PREDICTIVE_RE.search(line)
        if not match:
            continue
        item = {
            "stage": match.group(1),
            "candidateId": match.group(2),
            "identityDigest": match.group(3),
            "source": match.group(4),
            "confidence": match.group(5),
            "result": match.group(6),
            "reason": match.group(7).strip(),
        }
        identity = tuple(item.values())
        if identity not in seen:
            seen.add(identity)
            events.append(item)
    return events


def int_or_none(value: str | None) -> int | None:
    if value in {None, "", "-"}:
        return None
    try:
        return int(value)
    except ValueError:
        return None


def none_if_dash(value: str | None) -> str | None:
    return None if value in {None, "", "-"} else value


def percentile(values: list[int], fraction: float) -> float | int | None:
    if not values:
        return None
    ordered = sorted(values)
    rank = (len(ordered) - 1) * fraction
    low = math.floor(rank)
    high = math.ceil(rank)
    if low == high:
        return ordered[low]
    result = ordered[low] + (ordered[high] - ordered[low]) * (rank - low)
    return round(result, 2)


def metric(values: list[int], missing: int = 0) -> dict[str, Any]:
    valid = [value for value in values if value >= 0]
    return {
        "count": len(valid),
        "min": min(valid) if valid else None,
        "avg": round(statistics.mean(valid), 2) if valid else None,
        "p50": percentile(valid, 0.50),
        "p95": percentile(valid, 0.95),
        "p99": percentile(valid, 0.99),
        "max": max(valid) if valid else None,
        "missing": missing,
    }


def load_json(path: str | None) -> dict[str, Any]:
    if not path:
        return {}
    return json.loads(Path(path).read_text(encoding="utf-8"))


def first_after(
    events: list[dict[str, Any]],
    start: dict[str, Any],
    stages: set[str],
) -> dict[str, Any] | None:
    candidates = [
        event for event in events
        if event["side"] == "ios"
        and event["stage"] in stages
        and event["monoMs"] >= start["monoMs"]
        and (not start["trackId"] or event["trackId"] == start["trackId"])
        and (
            start["generation"] is None
            or event["generation"] is None
            or event["generation"] == start["generation"]
        )
    ]
    return min(candidates, key=lambda event: event["monoMs"], default=None)


def ios_handoff_metrics(events: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    identities = sorted(
        (
            event for event in events
            if event["side"] == "ios" and event["stage"] == "trackIdentityAccepted"
        ),
        key=lambda event: event["monoMs"],
    )
    targets = {
        "trackToPlaybackPublish": {"playbackStatePublished", "trackIdentityAccepted"},
        "trackToCurrentLyric": {"currentLyricPublished", "lyricWindowPublished"},
        "trackToCurrentWord": {"currentWordPublished", "currentWordAccepted"},
        "trackToPreviewArt": {"previewPublished", "cachedArtworkPublished"},
        "trackToFullLyricsAvailable": {"fullLyricsPublished", "cachedLyricsPublished"},
    }
    values: dict[str, list[int]] = {name: [] for name in targets}
    missing: Counter[str] = Counter()
    for identity in identities:
        for name, stages in targets.items():
            if name == "trackToPlaybackPublish":
                # iOS Track Identity is the local publication boundary. A Sony-to-iOS
                # confirmation duration requires trusted cross-device clock mapping.
                missing[name] += 1
                continue
            target = first_after(events, identity, stages)
            if target is None:
                missing[name] += 1
            else:
                values[name].append(target["monoMs"] - identity["monoMs"])
    return {name: metric(values[name], missing[name]) for name in targets}


def correctness(events: list[dict[str, Any]]) -> dict[str, int]:
    def count_reason(*needles: str) -> int:
        return sum(
            1 for event in events
            if any(needle in (event.get("reason") or "") for needle in needles)
            and event.get("result") in {"accepted", "published", "success"}
        )

    duplicate_controls = sum(
        count - 1 for count in Counter(
            (event["side"], event["stage"], event.get("transferId"), event["monoMs"])
            for event in events if event["stage"] == "mediaControlDispatchStart"
        ).values() if count > 1
    )
    return {
        "staleAccepted": count_reason("stale", "generation_mismatch"),
        "wrongCurrentWord": count_reason("wrong_current_word"),
        "wrongArtwork": count_reason("wrong_artwork"),
        "visibleFalsePositive": count_reason("visible_false_positive"),
        "duplicateControl": duplicate_controls,
        "coldFallbackFailures": sum(
            1 for event in events
            if event["stage"] == "predictionRejected"
            and event.get("result") == "cold_fallback_failed"
        ),
    }


def analyze(
    sony_text: str,
    ios_text: str,
    source_audit: dict[str, Any] | None = None,
    realtime_report: dict[str, Any] | None = None,
) -> dict[str, Any]:
    source_audit = source_audit or {}
    realtime_report = realtime_report or {}
    events = parse_trace_lines(sony_text, "sony") + parse_trace_lines(ios_text, "ios")
    events.sort(key=lambda event: (event["side"], event["monoMs"], event["sourceLine"]))
    predictive = parse_predictive_lines(sony_text)
    stage_counts = Counter(event["stage"] for event in events)
    sony_stage_counts = Counter(
        event["stage"] for event in events if event["side"] == "sony"
    )
    prediction_stage_counts = Counter(event["stage"] for event in predictive)
    audit = source_audit.get("sourceAudit", {})
    high_confidence = int(audit.get("highConfidenceCandidateCount", 0) or 0)
    warm_applicable = high_confidence > 0
    handoff = ios_handoff_metrics(events)
    validation_hit_logs = re.findall(
        r"\[FullLyricsCacheValidation\] hit\b[^\n]*bytesSaved=(\d+)",
        sony_text,
    )
    validation_miss_logs = re.findall(
        r"\[FullLyricsCacheValidation\] miss\b",
        sony_text,
    )
    trace_saved_bytes = re.findall(
        r"\[RealtimeTrace\][^\n]*\bstage=fullLyricsTransferSkipped\b[^\n]*\bbytesSaved=(\d+)",
        sony_text,
    )
    validation_hits = (
        len(validation_hit_logs)
        if validation_hit_logs
        else sony_stage_counts["cacheValidationHit"]
    )
    validation_misses = (
        len(validation_miss_logs)
        if validation_miss_logs
        else sony_stage_counts["cacheValidationMiss"]
    )
    transfer_skips = (
        len(validation_hit_logs)
        if validation_hit_logs
        else sony_stage_counts["fullLyricsTransferSkipped"]
    )
    bytes_saved = (
        sum(int(value) for value in validation_hit_logs)
        if validation_hit_logs
        else sum(int(value) for value in trace_saved_bytes)
    )
    cache = {
        "fullLyricsCacheValidationHit": validation_hits,
        "fullLyricsCacheValidationMiss": validation_misses,
        "fullLyricsTransferSkipped": transfer_skips,
        "fullLyricsBytesSaved": bytes_saved,
        "previewCacheHit": sony_stage_counts["albumArtCacheHit"],
        "previewPrefetchHit": sony_stage_counts["prefetchPromoted"],
        "hqCacheHit": sony_stage_counts["hqCacheHit"],
    }
    accuracy = audit.get("highConfidenceAccuracy")
    prediction = {
        "eligibleTransitions": int(audit.get("predictionEligibleCount", 0) or 0),
        "candidateCount": int(audit.get("predictionCandidateCount", 0) or 0),
        "highConfidenceCount": high_confidence,
        "hitCount": int(audit.get("predictionHitCount", 0) or 0),
        "missCount": int(audit.get("predictionMissCount", 0) or 0),
        "accuracy": accuracy,
        "visibleFalsePositive": correctness(events)["visibleFalsePositive"],
        "expiredCount": prediction_stage_counts["predictionExpired"],
        "rejectedCount": prediction_stage_counts["predictionRejected"],
        "prewarmQueued": prediction_stage_counts["predictionPrewarmQueued"],
        "prewarmReady": prediction_stage_counts["predictionReady"],
        "prewarmPromoted": prediction_stage_counts["predictionPromoted"],
    }
    result = "PASS"
    failure_reason = None
    if source_audit and source_audit.get("result") != "PASS":
        result = "FAIL"
        failure_reason = "prediction_source_audit_failed"
    if realtime_report and realtime_report.get("result") != "PASS":
        result = "FAIL"
        failure_reason = failure_reason or "realtime_trace_failed"
    correctness_metrics = correctness(events)
    if any(correctness_metrics.values()):
        result = "FAIL"
        failure_reason = failure_reason or "correctness_regression"

    return {
        "schemaVersion": 1,
        "result": result,
        "failureReason": failure_reason,
        "prediction": prediction,
        "prewarm": {
            "queued": prediction["prewarmQueued"],
            "ready": prediction["prewarmReady"],
            "promoted": prediction["prewarmPromoted"],
            "expired": prediction["expiredCount"],
            "cancelled": prediction_stage_counts["predictionInvalidated"],
        },
        "cache": cache,
        "latency": {
            "warmApplicable": warm_applicable,
            "warmResult": "TRACE_CORRELATION_UNAVAILABLE" if warm_applicable else "NOT_APPLICABLE",
            "warm": {
                name: metric([], high_confidence if warm_applicable else 0)
                for name in handoff
            },
            "cold": handoff,
            "phase2RealtimeMetrics": realtime_report.get("metrics", {}),
        },
        "correctness": correctness_metrics,
        "resources": {
            "additionalMemoryBytes": None,
            "prefetchBytesSent": 0,
            "prefetchPacketsSent": 0,
            "prefetchCancelledPackets": 0,
            "timerCountChange": 0,
            "mainThreadIOChange": 0,
        },
        "trace": {
            "eventCount": len(events),
            "stageCounts": dict(sorted(stage_counts.items())),
            "predictionStageCounts": dict(sorted(prediction_stage_counts.items())),
        },
    }


def write_summary(report: dict[str, Any], path: Path) -> None:
    prediction = report["prediction"]
    cache = report["cache"]
    lines = [
        "# Predictive Media V4 Report",
        "",
        f"- Result: {report['result']}",
        f"- Warm path: {report['latency']['warmResult']}",
        f"- Eligible transitions: {prediction['eligibleTransitions']}",
        f"- High-confidence candidates: {prediction['highConfidenceCount']}",
        f"- Accuracy: {prediction['accuracy'] if prediction['accuracy'] is not None else 'NOT APPLICABLE'}",
        f"- Visible false positives: {prediction['visibleFalsePositive']}",
        f"- FullLyrics validation hits: {cache['fullLyricsCacheValidationHit']}",
        f"- FullLyrics transfers skipped: {cache['fullLyricsTransferSkipped']}",
        f"- FullLyrics bytes saved: {cache['fullLyricsBytesSaved']}",
        "",
        "Warm metrics remain NOT APPLICABLE when the player exposes no high-confidence queue candidate.",
        "All unavailable measurements are null/missing rather than fabricated as zero latency.",
        "",
    ]
    path.write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sony-trace", required=True)
    parser.add_argument("--ios-trace", required=True)
    parser.add_argument("--source-audit")
    parser.add_argument("--realtime-report")
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    sony_text = Path(args.sony_trace).read_text(encoding="utf-8", errors="replace")
    ios_text = Path(args.ios_trace).read_text(encoding="utf-8", errors="replace")
    report = analyze(
        sony_text,
        ios_text,
        source_audit=load_json(args.source_audit),
        realtime_report=load_json(args.realtime_report),
    )
    (output_dir / "report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    write_summary(report, output_dir / "summary.md")
    raw_events = parse_trace_lines(sony_text, "sony") + parse_trace_lines(ios_text, "ios")
    (output_dir / "raw_events.jsonl").write_text(
        "".join(json.dumps(event, ensure_ascii=False) + "\n" for event in raw_events),
        encoding="utf-8",
    )
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["result"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
