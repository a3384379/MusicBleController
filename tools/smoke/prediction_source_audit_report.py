#!/usr/bin/env python3
"""Build a privacy-safe V4 prediction-source audit from Sony device logs."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import statistics
from pathlib import Path
from typing import Any


TRACE_PREFIX = "[RealtimeTrace] "
RUNTIME_TRACK_RE = re.compile(
    r"\[RuntimeCache\] track changed trackId=(\S+) songKey=(.*?) title=.*? generation=(\d+)"
)
QUEUE_DIAGNOSTIC_RE = re.compile(
    r"queue diagnostic source=(\S+).*?hasQueue=(true|false).*?queueSize=(-?\d+)"
    r".*?activeQueueId=(-?\d+)"
)
SELECTED_RE = re.compile(
    r"\[PredictiveLyricsCandidate\] selected source=(\S+) confidence=([0-9.]+) "
    r"title=(.*?) artist=(.*?) queueId=(-?\d+) mediaId=(.*?) reason="
)
V4_SELECTED_RE = re.compile(
    r"\[PredictionSource\] selected candidateId=(\S+) identityDigest=(\S+) "
    r"source=(\S+) confidence=(CONFIRMED|STRONG|WEAK|NONE)"
)
PREDICTIVE_MEDIA_RE = re.compile(
    r"\[PredictiveMedia\] stage=(\S+) candidateId=(\S+) identityDigest=(\S+) "
    r"source=(\S+) confidence=(\S+) result=(\S+) reason=(.*)"
)
HISTORY_RE = re.compile(
    r"\[PredictionSource\] history candidateId=(\S+) identityDigest=(\S+) "
    r"confidence=WEAK count=(\d+)"
)
UNAVAILABLE_RE = re.compile(
    r"\[(?:PredictiveLyricsCandidate|PredictionSource)\] "
    r"source=(\S+) unavailable reason=([^ ]+)"
)
REJECTED_RE = re.compile(
    r"\[(?:PredictiveLyricsCandidate|PredictionSource)\] "
    r"rejected source=(\S+) reason=([^ ]+)"
)


def safe_digest(*values: str) -> str | None:
    normalized = "|".join(" ".join(value.strip().lower().split()) for value in values)
    if not normalized.strip("|"):
        return None
    return hashlib.sha256(normalized.encode("utf-8")).hexdigest()[:24]


def parse_trace(line: str) -> dict[str, str] | None:
    marker = line.find(TRACE_PREFIX)
    if marker < 0:
        return None
    fields: dict[str, str] = {}
    for token in line[marker + len(TRACE_PREFIX):].strip().split():
        if "=" not in token:
            continue
        key, value = token.split("=", 1)
        fields[key] = value
    return fields


def _identity_by_track_id(lines: list[str]) -> dict[str, str]:
    identities: dict[str, str] = {}
    for line in lines:
        match = RUNTIME_TRACK_RE.search(line)
        if not match:
            continue
        song_parts = match.group(2).split("|", 2)
        if len(song_parts) < 2:
            continue
        digest = safe_digest(song_parts[0], song_parts[1])
        if digest:
            identities[match.group(1)] = digest
    return identities


def _new_pending() -> dict[str, Any]:
    return {
        "commandMonoMs": None,
        "commandType": None,
        "source": "none",
        "numericConfidence": None,
        "candidateIdentityDigest": None,
        "candidateId": None,
        "declaredConfidence": None,
        "candidateQueueId": -1,
        "candidateMediaIdAvailable": False,
        "candidateTitleAvailable": False,
        "candidateArtistAvailable": False,
        "queueAvailable": False,
        "queueSize": -1,
        "activeQueueItemIdAvailable": False,
        "rejectedReason": "no_candidate",
    }


def classify_confidence(pending: dict[str, Any]) -> str:
    if pending["declaredConfidence"] in {"CONFIRMED", "STRONG", "WEAK", "NONE"}:
        return pending["declaredConfidence"]
    if pending["source"] == "none":
        return "NONE"
    if pending["source"] == "history_transition":
        return "WEAK"
    complete_identity = (
        pending["candidateIdentityDigest"] is not None
        and pending["candidateTitleAvailable"]
        and pending["candidateArtistAvailable"]
    )
    if (
        pending["queueAvailable"]
        and pending["activeQueueItemIdAvailable"]
        and pending["candidateQueueId"] >= 0
        and pending["candidateMediaIdAvailable"]
        and complete_identity
    ):
        return "CONFIRMED"
    if pending["queueAvailable"] and pending["candidateQueueId"] >= 0 and complete_identity:
        return "STRONG"
    return "WEAK"


def analyze(sony_text: str, expected_transitions: int = 100) -> dict[str, Any]:
    lines = sony_text.splitlines()
    identity_by_track = _identity_by_track_id(lines)
    pending = _new_pending()
    transitions: list[dict[str, Any]] = []
    queue_observations = 0
    queue_available_observations = 0
    active_queue_id_observations = 0
    source_counts: dict[str, int] = {}
    rejected_reason_counts: dict[str, int] = {}
    seen_trace_events: set[tuple[str, str, str, str]] = set()
    pending_queue_observed = False
    pending_rejections: set[tuple[str, str]] = set()
    exact_identity_candidate_ids: set[str] = set()
    mismatched_candidate_ids: set[str] = set()

    for line in lines:
        trace = parse_trace(line)
        if trace:
            stage = trace.get("stage")
            trace_key = (
                stage or "",
                trace.get("monoMs", ""),
                trace.get("commandSeq", ""),
                trace.get("trackId", ""),
            )
            if trace_key in seen_trace_events:
                continue
            seen_trace_events.add(trace_key)
            command_type = trace.get("commandType")
            if stage == "commandReceived" and command_type in {"NEXT", "PREVIOUS"}:
                pending = _new_pending()
                pending["commandMonoMs"] = int(trace.get("monoMs", "0") or 0)
                pending["commandType"] = command_type
                pending_queue_observed = False
                pending_rejections.clear()
                continue
            if stage == "mediaSessionTrackChanged":
                actual_mono = int(trace.get("monoMs", "0") or 0)
                actual_track_id = trace.get("trackId", "")
                confidence = classify_confidence(pending)
                candidate_digest = pending["candidateIdentityDigest"]
                actual_digest = identity_by_track.get(actual_track_id)
                matched = None
                if candidate_digest is not None and actual_digest is not None:
                    matched = candidate_digest == actual_digest
                lead_time = None
                command_mono = pending["commandMonoMs"]
                if candidate_digest is not None and command_mono is not None:
                    lead_time = max(0, actual_mono - command_mono)
                transitions.append({
                    "index": len(transitions) + 1,
                    "source": pending["source"],
                    "confidence": confidence,
                    "candidateIdentityAvailable": candidate_digest is not None,
                    "candidateIdentityDigest": candidate_digest,
                    "candidateId": pending["candidateId"],
                    "actualIdentityDigest": actual_digest or safe_digest(actual_track_id),
                    "matched": matched,
                    "rejectedReason": None if candidate_digest else pending["rejectedReason"],
                    "queueAvailable": pending["queueAvailable"],
                    "activeQueueItemIdAvailable": pending["activeQueueItemIdAvailable"],
                    "predictionLeadTimeMs": lead_time,
                    "commandType": pending["commandType"] or "AUTOMATIC",
                })
                pending = _new_pending()
                pending_queue_observed = False
                pending_rejections.clear()
                continue

        queue_match = QUEUE_DIAGNOSTIC_RE.search(line)
        if queue_match:
            is_new_queue_observation = not pending_queue_observed
            if is_new_queue_observation:
                queue_observations += 1
                pending_queue_observed = True
            pending["source"] = queue_match.group(1)
            pending["queueAvailable"] = queue_match.group(2) == "true"
            pending["queueSize"] = int(queue_match.group(3))
            pending["activeQueueItemIdAvailable"] = int(queue_match.group(4)) >= 0
            if pending["queueAvailable"] and is_new_queue_observation:
                queue_available_observations += 1
            if pending["activeQueueItemIdAvailable"] and is_new_queue_observation:
                active_queue_id_observations += 1
            continue

        selected_match = SELECTED_RE.search(line)
        if selected_match:
            title = selected_match.group(3).strip()
            artist = selected_match.group(4).strip()
            media_id = selected_match.group(6).strip()
            pending["source"] = selected_match.group(1)
            pending["numericConfidence"] = float(selected_match.group(2))
            pending["candidateIdentityDigest"] = safe_digest(title, artist)
            pending["candidateQueueId"] = int(selected_match.group(5))
            pending["candidateMediaIdAvailable"] = media_id not in {"", "none", "-"}
            pending["candidateTitleAvailable"] = bool(title)
            pending["candidateArtistAvailable"] = bool(artist)
            pending["rejectedReason"] = ""
            continue

        v4_selected_match = V4_SELECTED_RE.search(line)
        if v4_selected_match:
            confidence = v4_selected_match.group(4)
            pending["candidateId"] = v4_selected_match.group(1)
            pending["candidateIdentityDigest"] = v4_selected_match.group(2)
            pending["source"] = v4_selected_match.group(3)
            pending["declaredConfidence"] = confidence
            pending["candidateTitleAvailable"] = confidence != "NONE"
            pending["candidateArtistAvailable"] = confidence != "NONE"
            pending["candidateMediaIdAvailable"] = confidence == "CONFIRMED"
            pending["candidateQueueId"] = 0 if confidence in {"CONFIRMED", "STRONG"} else -1
            pending["rejectedReason"] = ""
            continue

        predictive_match = PREDICTIVE_MEDIA_RE.search(line)
        if predictive_match:
            stage = predictive_match.group(1)
            candidate_id = predictive_match.group(2)
            reason = predictive_match.group(7).strip()
            if stage in {
                "predictionPromotionAttempt",
                "predictionPromoted",
            } or (stage == "predictionRejected" and reason in {
                "prewarm_not_ready",
                "cache_promotion_miss",
            }):
                exact_identity_candidate_ids.add(candidate_id)
            elif stage == "predictionRejected" and reason == "identity_mismatch":
                mismatched_candidate_ids.add(candidate_id)
            continue

        history_match = HISTORY_RE.search(line)
        if history_match:
            pending["candidateId"] = history_match.group(1)
            pending["candidateIdentityDigest"] = history_match.group(2)
            pending["source"] = "history_transition"
            pending["declaredConfidence"] = "WEAK"
            pending["candidateTitleAvailable"] = True
            pending["candidateArtistAvailable"] = True
            pending["rejectedReason"] = ""
            continue

        unavailable_match = UNAVAILABLE_RE.search(line)
        if unavailable_match:
            pending["source"] = unavailable_match.group(1)
            pending["rejectedReason"] = unavailable_match.group(2)
            rejection_key = (pending["source"], pending["rejectedReason"])
            if rejection_key not in pending_rejections:
                pending_rejections.add(rejection_key)
                rejected_reason_counts[pending["rejectedReason"]] = (
                    rejected_reason_counts.get(pending["rejectedReason"], 0) + 1
                )
            continue

        rejected_match = REJECTED_RE.search(line)
        if rejected_match:
            pending["source"] = rejected_match.group(1)
            pending["rejectedReason"] = rejected_match.group(2)
            rejection_key = (pending["source"], pending["rejectedReason"])
            if rejection_key not in pending_rejections:
                pending_rejections.add(rejection_key)
                rejected_reason_counts[pending["rejectedReason"]] = (
                    rejected_reason_counts.get(pending["rejectedReason"], 0) + 1
                )

    for transition in transitions:
        candidate_id = transition.get("candidateId")
        if candidate_id in exact_identity_candidate_ids:
            transition["matched"] = True
        elif candidate_id in mismatched_candidate_ids:
            transition["matched"] = False
        source_counts[transition["source"]] = source_counts.get(transition["source"], 0) + 1

    eligible = sum(1 for item in transitions if item["commandType"] in {"NEXT", "PREVIOUS"})
    candidates = [item for item in transitions if item["candidateIdentityAvailable"]]
    high_confidence = [
        item for item in candidates if item["confidence"] in {"CONFIRMED", "STRONG"}
    ]
    resolved = [item for item in high_confidence if item["matched"] is not None]
    hits = sum(1 for item in resolved if item["matched"] is True)
    misses = sum(1 for item in resolved if item["matched"] is False)
    lead_times = [
        item["predictionLeadTimeMs"]
        for item in high_confidence
        if item["predictionLeadTimeMs"] is not None
    ]
    coverage = len(high_confidence) / eligible if eligible else 0.0
    accuracy = hits / len(resolved) if resolved else None
    sufficient = len(transitions) >= expected_transitions
    cross_device_usable = bool(
        sufficient
        and high_confidence
        and accuracy is not None
        and accuracy >= 0.99
    )

    return {
        "schemaVersion": 1,
        "result": "PASS" if sufficient else "FAIL",
        "failureReason": None if sufficient else "transition_sample_incomplete",
        "expectedTransitions": expected_transitions,
        "sourceAudit": {
            "transitions": len(transitions),
            "predictionEligibleCount": eligible,
            "predictionCandidateCount": len(candidates),
            "highConfidenceCandidateCount": len(high_confidence),
            "predictionHitCount": hits,
            "predictionMissCount": misses,
            "predictionCoverage": round(coverage, 6),
            "highConfidenceAccuracy": round(accuracy, 6) if accuracy is not None else None,
            "averageLeadTimeMs": round(statistics.mean(lead_times), 2) if lead_times else None,
            "queueObservationCount": queue_observations,
            "queueAvailableObservationCount": queue_available_observations,
            "activeQueueItemIdAvailableCount": active_queue_id_observations,
            "sourceCounts": source_counts,
            "rejectedReasonCounts": rejected_reason_counts,
            "crossDevicePrefetchWorthwhile": cross_device_usable,
            "localEventPrewarmWorthwhile": True,
            "conclusion": (
                "high_confidence_candidate_available"
                if high_confidence
                else "no_high_confidence_candidate_exposed"
            ),
        },
        "transitions": transitions,
    }


def write_summary(report: dict[str, Any], output_path: Path) -> None:
    audit = report["sourceAudit"]
    accuracy = audit["highConfidenceAccuracy"]
    lead = audit["averageLeadTimeMs"]
    lines = [
        "# Prediction Source Audit V4",
        "",
        f"- Result: {report['result']}",
        f"- Transitions: {audit['transitions']}/{report['expectedTransitions']}",
        f"- Eligible: {audit['predictionEligibleCount']}",
        f"- Candidates: {audit['predictionCandidateCount']}",
        f"- High-confidence candidates: {audit['highConfidenceCandidateCount']}",
        f"- Coverage: {audit['predictionCoverage']:.2%}",
        f"- Accuracy: {accuracy if accuracy is not None else 'NOT APPLICABLE'}",
        f"- Average lead time: {str(lead) + ' ms' if lead is not None else 'NOT APPLICABLE'}",
        f"- Cross-device prefetch worthwhile: {audit['crossDevicePrefetchWorthwhile']}",
        "",
        "| source | confidence | coverage | accuracy | average lead time | local prewarm | cross-device prefetch |",
        "|---|---|---:|---:|---:|---|---|",
        (
            f"| MediaSession queue | CONFIRMED/STRONG | {audit['predictionCoverage']:.2%} | "
            f"{accuracy if accuracy is not None else 'N/A'} | "
            f"{str(lead) + ' ms' if lead is not None else 'N/A'} | yes | "
            f"{'yes' if audit['crossDevicePrefetchWorthwhile'] else 'no'} |"
        ),
        "| Playback history adjacency | WEAK | N/A | N/A | N/A | index-only | no |",
        "| Notification next metadata | NONE | 0% | N/A | N/A | no | no |",
        "",
        "## Conclusion",
        "",
        (
            "A real high-confidence candidate source is available. Continue with bounded local "
            "prewarm; cross-device prefetch still requires a measured BLE-value gate."
            if audit["highConfidenceCandidateCount"]
            else "This device/application version did not expose a high-confidence next-track "
            "candidate. Keep the normal cold path, implement only event-driven local cache "
            "prewarm/validation, and do not add cross-device prefetch."
        ),
        "",
    ]
    output_path.write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sony-log", required=True)
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--expected-transitions", type=int, default=100)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    sony_text = Path(args.sony_log).read_text(encoding="utf-8", errors="replace")
    report = analyze(sony_text, args.expected_transitions)
    report_path = output_dir / "report.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    write_summary(report, output_dir / "summary.md")
    if args.json:
        print(report_path.read_text(encoding="utf-8"), end="")
    return 0 if report["result"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
