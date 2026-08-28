#!/usr/bin/env python3
"""Build the V4 realtime latency report from privacy-safe trace lines."""

from __future__ import annotations

import argparse
import json
import math
import re
import statistics
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Callable, Iterable, Optional


TRACE_MARKER = "[RealtimeTrace] "
METRIC_NAMES = (
    "commandIntentToWriteStartMs",
    "writeStartToCallbackMs",
    "commandCallbackToSonyReceiveMs",
    "sonyReceiveToDispatchEndMs",
    "dispatchEndToMetadataObservedMs",
    "metadataObservedToTrackAcceptedMs",
    "trackAcceptedToPlaybackReadyMs",
    "playbackReadyToQueueStartMs",
    "playbackReadyToNotifyStartMs",
    "playbackQueueWaitMs",
    "playbackNotifyStartToCallbackMs",
    "notifyCallbackToIosReceiveMs",
    "iosReceiveToDecodeMs",
    "iosDecodeToPublishMs",
    "publishToUiConsumeMs",
    "totalCommandToTrackPublishMs",
    "trackAcceptedToLyricReadyMs",
    "lyricReadyToCurrentLineEnqueueMs",
    "currentLineEnqueueToPublishMs",
    "trackAcceptedToWordEligibleMs",
    "lyricReadyToWordEligibleMs",
    "wordEligibleToSchedulerCreatedMs",
    "schedulerCreatedToFirstEnqueueMs",
    "firstEnqueueToSendMs",
    "sendToReceiveMs",
    "receiveToAcceptMs",
    "acceptToPublishMs",
    "wordEligibleToPublishMs",
    "trackAcceptedToFirstWordMs",
    "commandToSonyReceiveMs",
    "commandToTrackPublishMs",
    "trackToCurrentLyricMs",
    "trackToCurrentWordMs",
    "trackToPreviewArtMs",
    "trackToHqArtMs",
    "lyricReadyToPendingFlushMs",
    "pendingFlushToSendStartMs",
    "fullLyricsSendDurationMs",
    "lyricReadyToFullLyricsPublishMs",
    "previewSendDurationMs",
    "notifyQueueWaitMs",
    "iOSDecodeDurationMs",
    "iOSPublishDurationMs",
)


@dataclass(frozen=True)
class Event:
    side: str
    stage: str
    mono_ms: int
    command_seq: Optional[int] = None
    command_type: Optional[str] = None
    track_id: Optional[str] = None
    generation: Optional[int] = None
    transfer_id: Optional[str] = None
    payload_type: Optional[str] = None
    queue_wait_ms: Optional[int] = None
    processing_ms: Optional[int] = None
    chunk_index: Optional[int] = None
    chunk_count: Optional[int] = None
    result: Optional[str] = None
    reason: Optional[str] = None
    handoff_id: Optional[str] = None
    trigger_type: Optional[str] = None
    position_anchor_ms: Optional[int] = None
    line_index: Optional[int] = None
    word_timing_status: Optional[str] = None
    cache_source: Optional[str] = None
    failure_reason: Optional[str] = None
    source_line: int = 0

    def as_dict(self) -> dict:
        return {
            "side": self.side,
            "stage": self.stage,
            "monoMs": self.mono_ms,
            "commandSeq": self.command_seq,
            "commandType": self.command_type,
            "trackId": self.track_id,
            "generation": self.generation,
            "transferId": self.transfer_id,
            "payloadType": self.payload_type,
            "queueWaitMs": self.queue_wait_ms,
            "processingMs": self.processing_ms,
            "chunkIndex": self.chunk_index,
            "chunkCount": self.chunk_count,
            "result": self.result,
            "reason": self.reason,
            "handoffId": self.handoff_id,
            "triggerType": self.trigger_type,
            "positionAnchorMs": self.position_anchor_ms,
            "lineIndex": self.line_index,
            "wordTimingStatus": self.word_timing_status,
            "cacheSource": self.cache_source,
            "failureReason": self.failure_reason,
            "sourceLine": self.source_line,
        }


def _optional_text(value: Optional[str]) -> Optional[str]:
    return None if value in (None, "", "-") else value


def _optional_int(value: Optional[str]) -> Optional[int]:
    value = _optional_text(value)
    if value is None:
        return None
    try:
        return int(value)
    except ValueError:
        return None


def parse_trace_line(line: str, source_line: int = 0) -> Optional[Event]:
    marker_index = line.find(TRACE_MARKER)
    if marker_index < 0:
        return None
    fields = {}
    for token in line[marker_index + len(TRACE_MARKER):].strip().split():
        if "=" not in token:
            continue
        key, value = token.split("=", 1)
        fields[key] = value
    side = _optional_text(fields.get("side"))
    stage = _optional_text(fields.get("stage"))
    mono_ms = _optional_int(fields.get("monoMs"))
    if side is None or stage is None or mono_ms is None:
        return None
    return Event(
        side=side,
        stage=stage,
        mono_ms=mono_ms,
        command_seq=_optional_int(fields.get("commandSeq")),
        command_type=_optional_text(fields.get("commandType")),
        track_id=_optional_text(fields.get("trackId")),
        generation=_optional_int(fields.get("generation")),
        transfer_id=_optional_text(fields.get("transferId")),
        payload_type=_optional_text(fields.get("payloadType")),
        queue_wait_ms=_optional_int(fields.get("queueWaitMs")),
        processing_ms=_optional_int(fields.get("processingMs")),
        chunk_index=_optional_int(fields.get("chunkIndex")),
        chunk_count=_optional_int(fields.get("chunkCount")),
        result=_optional_text(fields.get("result")),
        reason=_optional_text(fields.get("reason")),
        handoff_id=_optional_text(fields.get("handoffId")),
        trigger_type=_optional_text(fields.get("triggerType")),
        position_anchor_ms=_optional_int(fields.get("positionAnchorMs")),
        line_index=_optional_int(fields.get("lineIndex")),
        word_timing_status=_optional_text(fields.get("wordTimingStatus")),
        cache_source=_optional_text(fields.get("cacheSource")),
        failure_reason=_optional_text(fields.get("failureReason")),
        source_line=source_line,
    )


def load_events(paths: Iterable[Path]) -> tuple[list[Event], int]:
    events = []
    malformed = 0
    source_line = 0
    for path in paths:
        if not path.exists():
            continue
        for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
            source_line += 1
            if TRACE_MARKER not in line:
                continue
            event = parse_trace_line(line, source_line)
            if event is None:
                malformed += 1
            else:
                events.append(event)
    return events, malformed


def discover_clock_sync(paths: Iterable[Path]) -> tuple[bool, Optional[int]]:
    pattern = re.compile(
        r"\[ClockSync\]\s+pong\b.*\boffsetMs=(-?\d+)\b.*\bsamples=(\d+)\b"
        r".*\bconfident=(true|false)\b",
        re.IGNORECASE,
    )
    latest = None
    previous_sample_count = None
    reset_seen = False
    for path in paths:
        if not path.exists():
            continue
        for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
            match = pattern.search(line)
            if match:
                sample_count = int(match.group(2))
                if (previous_sample_count is not None
                        and sample_count < previous_sample_count):
                    reset_seen = True
                previous_sample_count = sample_count
                latest = (match.group(3).lower() == "true", int(match.group(1)))
    # One offset must never be applied across a hard reconnect. Even if the
    # final segment later converges, its offset cannot validate events from the
    # earlier segment; keep cross-device metrics unavailable for that run.
    if reset_seen:
        return False, None
    return latest or (False, None)


def percentile(values: list[int], value: float) -> Optional[float]:
    if not values:
        return None
    ordered = sorted(values)
    rank = min(max(value, 0.0), 100.0) / 100.0 * (len(ordered) - 1)
    lower = math.floor(rank)
    upper = math.ceil(rank)
    if lower == upper:
        return float(ordered[lower])
    fraction = rank - lower
    return ordered[lower] + (ordered[upper] - ordered[lower]) * fraction


def summarize(values: list[int], missing: int) -> dict:
    return {
        "count": len(values),
        "min": min(values) if values else None,
        "avg": statistics.fmean(values) if values else None,
        "p50": percentile(values, 50),
        "p95": percentile(values, 95),
        "p99": percentile(values, 99),
        "max": max(values) if values else None,
        "missing": missing,
        "missingCount": missing,
    }


def _track_key(event: Event) -> Optional[tuple]:
    if not event.track_id:
        return None
    return (event.track_id[:12], event.generation)


def _track_id_key(event: Event) -> Optional[tuple]:
    return (event.track_id[:12],) if event.track_id else None


def _ready_track_key(event: Event) -> Optional[tuple]:
    if event.stage == "lyricReady" and event.result != "ready":
        return None
    return _track_key(event)


def _command_key(event: Event) -> Optional[tuple]:
    if event.command_seq is None:
        return None
    return (event.command_seq, event.command_type)


def _control_command_key(event: Event) -> Optional[tuple]:
    if event.command_type not in {"NEXT", "PREVIOUS"}:
        return None
    return _command_key(event)


def _transfer_key(event: Event) -> Optional[tuple]:
    if event.transfer_id:
        return (event.transfer_id, event.generation)
    return _track_key(event)


def _command_or_handoff_key(event: Event) -> Optional[tuple]:
    if event.handoff_id:
        return ("handoff", event.handoff_id)
    command = _command_key(event)
    return ("command", *command) if command is not None else None


def _control_command_or_handoff_key(event: Event) -> Optional[tuple]:
    is_control = event.command_type in {"NEXT", "PREVIOUS"}
    is_control_handoff = (
        event.trigger_type in {"IOS_NEXT", "IOS_PREVIOUS"}
        or bool(event.handoff_id and event.handoff_id.startswith("command-"))
    )
    if not is_control and not is_control_handoff:
        return None
    return _command_or_handoff_key(event)


def _media_key(event: Event) -> Optional[tuple]:
    track = _track_key(event)
    if track is not None:
        return ("track", *track)
    if event.handoff_id:
        return ("handoff", event.handoff_id)
    return None


def _handoff_or_media_key(event: Event) -> Optional[tuple]:
    if event.handoff_id:
        return ("handoff", event.handoff_id)
    return _media_key(event)


def first_correlated_events(
    events: list[Event],
    side: str,
    stages: set[str],
    canonical_stage: str,
    key: Callable[[Event], Optional[tuple]],
) -> list[Event]:
    first_by_key = {}
    for event in sorted(events, key=lambda item: (item.mono_ms, item.source_line)):
        if event.side != side or event.stage not in stages:
            continue
        event_key = key(event)
        if event_key is None or event_key in first_by_key:
            continue
        first_by_key[event_key] = replace(event, stage=canonical_stage)
    return list(first_by_key.values())


def first_events_after(
    events: list[Event],
    starts: list[Event],
    side: str,
    stages: set[str],
    canonical_stage: str,
    key: Callable[[Event], Optional[tuple]],
) -> list[Event]:
    selected = []
    for start in starts:
        start_key = key(start)
        if start_key is None:
            continue
        candidates = [
            event for event in events
            if event.side == side
            and event.stage in stages
            and key(event) == start_key
            and event.mono_ms >= start.mono_ms
        ]
        if candidates:
            selected.append(replace(
                min(candidates, key=lambda item: (item.mono_ms, item.source_line)),
                stage=canonical_stage,
            ))
    return selected


def _same_track(left: tuple, right: tuple) -> bool:
    left_id, left_generation = left
    right_id, right_generation = right
    return left_id == right_id and (
        left_generation is None
        or right_generation is None
        or left_generation == right_generation
    )


def paired_durations(
    events: list[Event],
    start_side: str,
    start_stage: str,
    end_side: str,
    end_stage: str,
    key: Callable[[Event], Optional[tuple]],
    categories: Counter,
    cross_device: bool = False,
    clock_trusted: bool = False,
    sony_to_ios_offset_ms: Optional[int] = None,
    cross_device_tolerance_ms: int = 75,
) -> tuple[list[int], int]:
    starts = [
        event for event in events
        if event.side == start_side
        and event.stage == start_stage
        and key(event) is not None
    ]
    ends = [
        event for event in events
        if event.side == end_side
        and event.stage == end_stage
        and key(event) is not None
    ]
    if cross_device and (not clock_trusted or sony_to_ios_offset_ms is None):
        if starts or ends:
            categories["untrusted_clock"] += max(len(starts), len(ends))
        return [], max(len(starts), len(ends))

    ends_by_key = defaultdict(list)
    for event in ends:
        event_key = key(event)
        if event_key is not None:
            ends_by_key[event_key].append(event)
    for grouped in ends_by_key.values():
        grouped.sort(key=lambda event: event.mono_ms)

    values = []
    used_end_lines = set()
    missing = 0
    for start in sorted(starts, key=lambda event: event.mono_ms):
        event_key = key(start)
        start_ms = start.mono_ms
        if cross_device and start.side == "sony":
            start_ms += sony_to_ios_offset_ms or 0
        candidates = ends_by_key.get(event_key, [])
        eligible = []
        for end in candidates:
            end_ms = end.mono_ms
            if cross_device and end.side == "sony":
                end_ms += sony_to_ios_offset_ms or 0
            minimum_end_ms = (
                start_ms - cross_device_tolerance_ms
                if cross_device else start_ms
            )
            if end.source_line not in used_end_lines and end_ms >= minimum_end_ms:
                eligible.append((end_ms, end))
        if not eligible:
            if candidates:
                categories["out_of_order"] += 1
            missing += 1
            continue
        end_ms, end = eligible[0]
        used_end_lines.add(end.source_line)
        duration = end_ms - start_ms
        if duration < 0:
            categories["clock_uncertainty_clamped"] += 1
            duration = 0
        if duration > 120_000:
            categories["extreme"] += 1
        values.append(duration)
    # Additional or standalone end events are legitimate for streaming and
    # cache-hit stages. A closure is incomplete only when a measured start has
    # no matching end.
    return values, missing


def derive_track_t0(
    events: list[Event],
    categories: Counter,
    clock_trusted: bool,
    sony_to_ios_offset_ms: Optional[int],
) -> tuple[list[Event], list[int], int]:
    """Correlate each visible iOS track with its scenario-specific T0.

    Manual NEXT/PREVIOUS uses the iOS command intent. Sony-local/automatic
    changes use the Sony MediaSession identity mapped onto the iOS monotonic
    clock only when clockSyncV1 is trusted.
    """
    identities = sorted(
        (
            event for event in events
            if event.side == "ios"
            and event.stage == "trackIdentityAccepted"
            and event.result == "changed"
        ),
        key=lambda event: event.mono_ms,
    )
    controls = sorted(
        (
            event for event in events
            if event.side == "ios"
            and event.stage == "commandIntent"
            and event.command_type in {"NEXT", "PREVIOUS"}
        ),
        key=lambda event: event.mono_ms,
    )
    t0_events = []
    track_publish_values = []
    missing = 0

    if controls:
        used_identity_lines = set()
        for index, control in enumerate(controls):
            next_control_ms = (
                controls[index + 1].mono_ms
                if index + 1 < len(controls)
                else None
            )
            candidates = [
                identity for identity in identities
                if identity.source_line not in used_identity_lines
                and identity.mono_ms >= control.mono_ms
                and (
                    next_control_ms is None
                    or identity.mono_ms < next_control_ms
                )
            ]
            if not candidates:
                missing += 1
                continue
            identity = candidates[0]
            used_identity_lines.add(identity.source_line)
            duration = identity.mono_ms - control.mono_ms
            if duration > 120_000:
                categories["extreme"] += 1
            track_publish_values.append(duration)
            t0_events.append(
                Event(
                    side="t0",
                    stage="trackT0",
                    mono_ms=control.mono_ms,
                    command_seq=control.command_seq,
                    command_type=control.command_type,
                    track_id=identity.track_id,
                    generation=identity.generation,
                    result="manual",
                    source_line=-(index + 1),
                )
            )
        return t0_events, track_publish_values, missing

    sony_identities = sorted(
        (
            event for event in events
            if event.side == "sony" and event.stage == "trackIdentityAccepted"
        ),
        key=lambda event: event.mono_ms,
    )
    if sony_identities or identities:
        if not clock_trusted or sony_to_ios_offset_ms is None:
            categories["untrusted_clock"] += max(len(sony_identities), len(identities))
            return [], [], max(len(sony_identities), len(identities))

    identities_by_key = defaultdict(list)
    for identity in identities:
        identity_key = _track_key(identity)
        if identity_key is not None:
            identities_by_key[identity_key].append(identity)
    used_identity_lines = set()
    for index, sony_identity in enumerate(sony_identities):
        identity_key = _track_key(sony_identity)
        mapped_t0_ms = sony_identity.mono_ms + sony_to_ios_offset_ms
        candidates = [
            identity for identity in identities_by_key.get(identity_key, [])
            if identity.source_line not in used_identity_lines
            and identity.mono_ms >= mapped_t0_ms
        ]
        if not candidates:
            missing += 1
            continue
        identity = candidates[0]
        used_identity_lines.add(identity.source_line)
        duration = identity.mono_ms - mapped_t0_ms
        if duration > 120_000:
            categories["extreme"] += 1
        track_publish_values.append(duration)
        t0_events.append(
            Event(
                side="t0",
                stage="trackT0",
                mono_ms=mapped_t0_ms,
                track_id=identity.track_id,
                generation=identity.generation,
                result="automatic",
                source_line=-(index + 1),
            )
        )
    if sony_identities:
        first_mapped_t0 = sony_identities[0].mono_ms + sony_to_ios_offset_ms
        missing += sum(
            1 for identity in identities
            if identity.source_line not in used_identity_lines
            and identity.mono_ms >= first_mapped_t0
        )
    return t0_events, track_publish_values, missing


def classify_transition_samples(
    events: list[Event],
    clock_trusted: bool,
    sony_to_ios_offset_ms: Optional[int] = None,
) -> tuple[list[dict], list[dict]]:
    anchor_candidates = [
        event for event in events
        if (
            event.side == "ios"
            and event.stage == "commandIntent"
            and event.command_type in {"NEXT", "PREVIOUS"}
        ) or (
            event.side == "sony"
            and event.stage == "mediaSessionMetadataObserved"
        )
    ]
    # Sony and iOS monotonic clocks are not directly sortable. When a remote
    # command and its Sony MediaSession observation share the same command
    # sequence, keep the iOS intent as the sample anchor; it carries the stable
    # handoff id even when Android's bounded product log truncates a legacy line.
    anchors_by_identity = {}
    for candidate in anchor_candidates:
        identity = candidate.handoff_id or (
            f"command-{candidate.command_seq}"
            if candidate.command_seq is not None
            else f"track-{candidate.track_id}-{candidate.generation}"
        )
        existing = anchors_by_identity.get(identity)
        if existing is None or (
            candidate.side == "ios"
            and candidate.stage == "commandIntent"
            and existing.stage != "commandIntent"
        ):
            anchors_by_identity[identity] = candidate
    anchors = list(anchors_by_identity.values())
    samples = []
    missing_events = []
    seen = set()
    used_ios_identity_lines = set()
    for index, anchor in enumerate(sorted(anchors, key=lambda item: item.mono_ms)):
        anchor_identity = anchor.handoff_id or (
            f"command-{anchor.command_seq}" if anchor.command_seq is not None
            else f"track-{anchor.track_id}-{anchor.generation}"
        )
        if anchor_identity in seen:
            continue
        seen.add(anchor_identity)
        linked_ios_identity = None
        if (
            anchor.side == "sony"
            and anchor.stage == "mediaSessionMetadataObserved"
            and anchor.track_id
            and clock_trusted
            and sony_to_ios_offset_ms is not None
        ):
            mapped_anchor_ms = anchor.mono_ms + sony_to_ios_offset_ms
            ios_identity_candidates = [
                candidate for candidate in events
                if candidate.side == "ios"
                and candidate.stage == "trackIdentityAccepted"
                and candidate.result == "changed"
                and candidate.source_line not in used_ios_identity_lines
                and candidate.track_id
                and candidate.track_id[:12] == anchor.track_id[:12]
                and mapped_anchor_ms - 100 <= candidate.mono_ms <= mapped_anchor_ms + 15_000
            ]
            if ios_identity_candidates:
                linked_ios_identity = min(
                    ios_identity_candidates,
                    key=lambda candidate: (
                        abs(candidate.mono_ms - mapped_anchor_ms),
                        candidate.mono_ms,
                        candidate.source_line,
                    ),
                )
                used_ios_identity_lines.add(linked_ios_identity.source_line)
        upper_bound = anchor.mono_ms + 15_000
        grouped = []
        for candidate in events:
            if candidate.side == anchor.side and (
                candidate.mono_ms < anchor.mono_ms - 100
                or candidate.mono_ms > upper_bound
            ):
                continue
            same_handoff = bool(
                anchor.handoff_id
                and candidate.handoff_id == anchor.handoff_id
            )
            same_linked_ios_handoff = bool(
                linked_ios_identity
                and linked_ios_identity.handoff_id
                and candidate.handoff_id == linked_ios_identity.handoff_id
            )
            same_command = bool(
                anchor.command_seq is not None
                and candidate.command_seq == anchor.command_seq
            )
            same_track = bool(
                anchor.track_id
                and candidate.track_id
                and candidate.track_id[:12] == anchor.track_id[:12]
                and (
                    linked_ios_identity is not None
                    and candidate.side == "ios"
                    and candidate.generation == linked_ios_identity.generation
                    or linked_ios_identity is None
                    and (
                        anchor.generation is None
                        or candidate.generation is None
                        or anchor.generation == candidate.generation
                    )
                )
                and (
                    linked_ios_identity is None
                    or candidate.side != "ios"
                    or abs(candidate.mono_ms - linked_ios_identity.mono_ms) <= 15_000
                )
            )
            if (
                same_handoff
                or same_linked_ios_handoff
                or same_command
                or same_track
            ):
                grouped.append(candidate)
        stages = {event.stage for event in grouped}
        is_command = anchor.stage == "commandIntent"
        required = {
            "commandIntent", "commandEnqueued", "commandWriteStart", "commandWriteCallback",
            "commandReceived", "commandValidated", "mediaControlDispatchStart",
            "mediaControlDispatchEnd", "mediaSessionMetadataObserved",
            "notificationMetadataObserved", "trackIdentityCandidate",
            "trackIdentityAccepted", "mediaGenerationCreated",
            "playbackReadStart", "playbackReady", "playbackEnqueued",
            "playbackDequeued", "playbackNotifyStart", "playbackNotifyCallback",
            "playbackNotifyReceived", "playbackDecodeEnd", "playbackStatePublished",
            "nowPlayingStateConsumed",
        }
        if not is_command:
            required -= {
                "commandIntent", "commandEnqueued", "commandWriteStart",
                "commandWriteCallback", "commandReceived", "commandValidated",
                "mediaControlDispatchStart", "mediaControlDispatchEnd",
            }
        absent = sorted(required - stages)
        classifications = []
        ios_track_changed = any(
            event.side == "ios"
            and event.stage == "trackIdentityAccepted"
            and event.result == "changed"
            for event in grouped
        )
        if is_command and not ios_track_changed:
            classifications.append("NO_TRACK_CHANGE")
            if not any(event.side == "sony" for event in grouped):
                classifications.append("COMMAND_ONLY")
        lyric_ready = [event for event in grouped if event.stage == "lyricReady"]
        if lyric_ready and not any(event.result == "ready" for event in lyric_ready):
            classifications.append("NO_LYRICS")
        if any(event.word_timing_status == "LINE_ONLY" for event in grouped):
            classifications.append("LINE_ONLY_LYRICS")
        if any(event.word_timing_status == "AVAILABLE" for event in grouped):
            classifications.append("WORD_TIMING_AVAILABLE")
        if any(
            event.stage == "currentWordNotEligible" and event.reason == "INTRO_WAIT"
            for event in grouped
        ):
            classifications.append("INTRO_WAIT")
        if any(event.stage in {"lyricRuntimeCacheHit", "lyricParsedCacheHit"} for event in grouped):
            classifications.append("LYRIC_CACHE_HIT")
        if any(event.stage == "lyricParsedCacheMiss" for event in grouped):
            classifications.append("LYRIC_CACHE_MISS")
        if any(event.stage in {"albumArtCacheHit", "prefetchLocalCacheHit"} for event in grouped):
            classifications.append("PREVIEW_CACHE_HIT")
        if any(event.stage in {"previewSendStart", "previewPublished"} for event in grouped):
            classifications.append("PREVIEW_TRANSFER")
        if not clock_trusted:
            classifications.append("CLOCK_SYNC_UNTRUSTED")
        if any(
            event.stage in {"trackIdentityRejected", "currentWordRejected"}
            and (event.reason or "").upper() in {
                "STALE_PACKET", "GENERATION_MISMATCH", "SEQUENCE_OLD",
                "POSITION_STALE", "TRACK_MISMATCH",
            }
            for event in grouped
        ):
            classifications.append("STALE_REJECTED")
        if any(event.result in {"failure", "failed"} for event in grouped):
            classifications.append("FAILED")
        if absent:
            classifications.append("TRACE_INCOMPLETE")
        elif ios_track_changed or not is_command:
            classifications.append("COMPLETE")
        sample_id = anchor.handoff_id or f"sample-{index + 1}"
        sample = {
            "sampleId": sample_id,
            "handoffId": anchor.handoff_id,
            "triggerType": anchor.trigger_type or (
                "IOS_NEXT" if anchor.command_type == "NEXT"
                else "IOS_PREVIOUS" if anchor.command_type == "PREVIOUS"
                else "UNKNOWN"
            ),
            "trackIdSummary": anchor.track_id[:12] if anchor.track_id else None,
            "generation": anchor.generation,
            "eventCount": len(grouped),
            "classifications": sorted(set(classifications)),
            "missingEvents": absent,
        }
        samples.append(sample)
        if absent:
            missing_events.append({
                "sampleId": sample_id,
                "missingEvents": absent,
            })
    return samples, missing_events


def analyze(
    events: list[Event],
    malformed: int = 0,
    clock_trusted: bool = False,
    sony_to_ios_offset_ms: Optional[int] = None,
) -> dict:
    categories = Counter()
    categories["malformed"] = malformed
    if not events:
        categories["empty"] += 1

    identity_counts = Counter(
        (
            event.side,
            event.stage,
            event.mono_ms,
            event.command_seq,
            event.track_id,
            event.generation,
            event.transfer_id,
        )
        for event in events
    )
    categories["duplicate"] = sum(count - 1 for count in identity_counts.values() if count > 1)

    accepted_generation_stages = {
        "trackIdentityAccepted",
        "playbackStatePublished",
        "currentLyricPublished",
        "currentWordAccepted",
        "currentWordPublished",
        "lyricWindowPublished",
        "fullLyricsPublished",
        "previewPublished",
        "hqPublished",
    }
    latest_generation = {}
    for event in sorted(events, key=lambda item: (item.side, item.mono_ms, item.source_line)):
        if (
            event.stage not in accepted_generation_stages
            or event.generation is None
            or event.generation <= 0
        ):
            continue
        previous = latest_generation.get(event.side)
        if previous is not None and event.generation < previous:
            categories["stale_generation"] += 1
        latest_generation[event.side] = max(previous or event.generation, event.generation)

    current_ios_track: Optional[tuple] = None
    for event in sorted(
        (event for event in events if event.side == "ios"),
        key=lambda item: (item.mono_ms, item.source_line),
    ):
        if event.stage == "trackIdentityAccepted":
            current_ios_track = _track_key(event)
            continue
        event_track = _track_key(event)
        if current_ios_track is None or event_track is None:
            continue
        if event.stage in {"currentWordAccepted", "currentWordPublished"}:
            if not _same_track(event_track, current_ios_track):
                categories["wrong_current_word"] += 1
        elif event.stage in {"previewPublished", "hqPublished"}:
            if not _same_track(event_track, current_ios_track):
                categories["wrong_artwork"] += 1
        elif event.stage in {"lyricWindowPublished", "fullLyricsPublished"}:
            if not _same_track(event_track, current_ios_track):
                categories["wrong_lyrics"] += 1

    dispatch_counts = Counter(
        (event.command_seq, event.command_type)
        for event in events
        if event.side == "sony"
        and event.stage == "mediaControlDispatchStart"
        and _control_command_key(event) is not None
    )
    receive_counts = Counter(
        (event.command_seq, event.command_type)
        for event in events
        if event.side == "sony"
        and event.stage == "commandReceived"
        and _control_command_key(event) is not None
    )
    categories["duplicate_control"] = sum(
        count - 1 for count in dispatch_counts.values() if count > 1
    )
    categories["control_reconnect_resend"] = sum(
        count - 1 for count in receive_counts.values() if count > 1
    )

    metrics = {}
    metric_samples = {}

    phase4_events = list(events)
    phase4_events.extend(first_correlated_events(
        events,
        "sony",
        {"mediaSessionMetadataObserved", "notificationMetadataObserved"},
        "metadataObserved",
        _command_or_handoff_key,
    ))
    first_stage_specs = (
        ("sony", {"trackIdentityAccepted"}, "firstSonyTrackAccepted", _media_key),
        ("sony", {"playbackReady", "playbackStateReady"}, "firstPlaybackReady", _media_key),
        ("sony", {"playbackEnqueued"}, "firstPlaybackEnqueued", _media_key),
        ("sony", {"playbackNotifyStart"}, "firstPlaybackNotifyStart", _media_key),
        ("sony", {"playbackNotifyCallback"}, "firstPlaybackNotifyCallback", _media_key),
        ("ios", {"nowPlayingStateConsumed"}, "firstUiConsumed", _media_key),
        ("sony", {"lyricReady"}, "firstLyricReady", _media_key),
        ("sony", {"currentWordEligible"}, "firstWordEligible", _media_key),
        ("ios", {"trackIdentityAccepted"}, "firstIosTrackAccepted", _media_key),
    )
    for side, stages, canonical, key_function in first_stage_specs:
        source_events = events
        if canonical == "firstIosTrackAccepted":
            # A reconnect baseline can refresh the already-visible identity
            # before the requested measurement starts. It is not a handoff and
            # must not make the old generation eligible for first-word SLOs.
            source_events = [
                event for event in events
                if event.stage != "trackIdentityAccepted"
                or event.side != "ios"
                or event.result == "changed"
            ]
        phase4_events.extend(first_correlated_events(
            source_events, side, stages, canonical, key_function,
        ))
    # Keep every non-empty line publication available for pairing. The first
    # publication for a generation can legitimately predate lyricsReady (for
    # example, a cached/stale UI slice). Cross-device pairing below selects the
    # first exact-identity publication after the Sony enqueue using clockSync.
    phase4_events.extend(
        replace(event, stage="firstLinePublished")
        for event in events
        if event.side == "ios"
        and event.stage == "lyricCurrentLinePublished"
        and event.result != "empty"
        and _media_key(event) is not None
    )
    lyric_ready_events = [
        event for event in phase4_events if event.stage == "firstLyricReady"
    ]
    phase4_events.extend(first_events_after(
        events,
        lyric_ready_events,
        "sony",
        {"lyricCurrentLineEnqueued"},
        "firstLineEnqueued",
        _media_key,
    ))
    identified_playback_receives = [
        event for event in events
        if event.stage != "playbackNotifyReceived" or bool(event.track_id)
    ]
    phase4_events.extend(first_correlated_events(
        identified_playback_receives,
        "ios",
        {"playbackNotifyReceived"},
        "firstPlaybackNotifyReceived",
        _media_key,
    ))
    # playbackState JSON does not carry track identity. The receive trace is
    # recorded immediately before decode and already owns the accepted handoff
    # identity, so inherit it onto the adjacent decode event instead of pairing
    # unrelated payloads that merely share a handoff window.
    playback_receives = [
        event for event in phase4_events
        if event.side == "ios" and event.stage == "firstPlaybackNotifyReceived"
    ]
    playback_decodes = []
    for receive in playback_receives:
        candidates = [
            event for event in events
            if event.side == "ios"
            and event.stage == "playbackDecodeEnd"
            and event.payload_type == "playbackState"
            and event.mono_ms >= receive.mono_ms
            and event.mono_ms - receive.mono_ms <= 100
            and (
                not receive.handoff_id
                or event.handoff_id == receive.handoff_id
            )
        ]
        if candidates:
            decoded = min(candidates, key=lambda item: (item.mono_ms, item.source_line))
            canonical_decode = replace(
                decoded,
                stage="firstPlaybackDecodeEnd",
                track_id=receive.track_id,
                generation=receive.generation,
                handoff_id=receive.handoff_id or decoded.handoff_id,
                trigger_type=receive.trigger_type or decoded.trigger_type,
            )
            playback_decodes.append(canonical_decode)
            phase4_events.append(canonical_decode)
    playback_publishes = first_events_after(
        events,
        playback_decodes,
        "ios",
        {"playbackStatePublished"},
        "firstPlaybackPublished",
        _media_key,
    )
    phase4_events.extend(playback_publishes)
    accepted_media_keys = {
        _media_key(event)
        for event in phase4_events
        if event.stage == "firstIosTrackAccepted"
        and _media_key(event) is not None
    }
    phase4_events = [
        event for event in phase4_events
        if event.stage != "firstWordEligible"
        or _media_key(event) in accepted_media_keys
    ]
    eligible_events = [
        event for event in phase4_events if event.stage == "firstWordEligible"
    ]
    first_eligible_ms_by_key = {
        _media_key(event): event.mono_ms
        for event in eligible_events
        if _media_key(event) is not None
    }
    intro_wait_before_first_word_keys = {
        _media_key(event)
        for event in events
        if event.side == "sony"
        and event.stage in {"currentWordNotEligible", "currentWordEligibilityEvaluated"}
        and event.reason == "INTRO_WAIT"
        and _media_key(event) in first_eligible_ms_by_key
        and event.mono_ms <= first_eligible_ms_by_key[_media_key(event)]
    }
    categories["intro_wait_before_first_word"] += len(
        intro_wait_before_first_word_keys
    )
    phase4_events.extend(
        replace(event, stage="firstIosTrackAcceptedImmediateWord")
        for event in phase4_events
        if event.stage == "firstIosTrackAccepted"
        and _media_key(event) not in intro_wait_before_first_word_keys
    )
    scheduler_events = first_events_after(
        events,
        eligible_events,
        "sony",
        {"currentWordSchedulerCreated"},
        "wordSchedulerCreated",
        _media_key,
    )
    enqueue_events = first_events_after(
        events,
        scheduler_events,
        "sony",
        {"currentWordImmediateSnapshotEnqueued", "currentWordBoundaryEnqueued"},
        "wordEnqueued",
        _media_key,
    )
    send_events = first_events_after(
        events,
        enqueue_events,
        "sony",
        {"currentWordSendStart"},
        "wordSendStart",
        _media_key,
    )
    phase4_events.extend(scheduler_events)
    phase4_events.extend(enqueue_events)
    phase4_events.extend(send_events)
    for side, stages, canonical in (
        ("ios", {"currentWordReceived"}, "wordReceived"),
        ("ios", {"currentWordAccepted"}, "wordAccepted"),
        ("ios", {"currentWordPublished"}, "wordPublished"),
    ):
        phase4_events.extend(first_correlated_events(
            events, side, stages, canonical, _media_key,
        ))

    def add_phase4_pair(
        name: str,
        start_side: str,
        start_stage: str,
        end_side: str,
        end_stage: str,
        key: Callable[[Event], Optional[tuple]],
        cross_device: bool = False,
        cross_device_tolerance_ms: int = 75,
    ) -> None:
        values, missing = paired_durations(
            phase4_events,
            start_side,
            start_stage,
            end_side,
            end_stage,
            key,
            categories,
            cross_device=cross_device,
            clock_trusted=clock_trusted,
            sony_to_ios_offset_ms=sony_to_ios_offset_ms,
            cross_device_tolerance_ms=cross_device_tolerance_ms,
        )
        metric_samples[name] = values
        metrics[name] = summarize(values, missing)

    phase4_pair_specs = (
        ("commandIntentToWriteStartMs", "ios", "commandIntent", "ios", "commandWriteStart", _control_command_or_handoff_key, False),
        ("writeStartToCallbackMs", "ios", "commandWriteStart", "ios", "commandWriteCallback", _control_command_or_handoff_key, False),
        ("commandCallbackToSonyReceiveMs", "ios", "commandWriteCallback", "sony", "commandReceived", _control_command_or_handoff_key, True),
        ("sonyReceiveToDispatchEndMs", "sony", "commandReceived", "sony", "mediaControlDispatchEnd", _control_command_or_handoff_key, False),
        ("dispatchEndToMetadataObservedMs", "sony", "mediaControlDispatchEnd", "sony", "metadataObserved", _control_command_or_handoff_key, False),
        ("metadataObservedToTrackAcceptedMs", "sony", "mediaSessionMetadataObserved", "sony", "firstSonyTrackAccepted", _handoff_or_media_key, False),
        ("trackAcceptedToPlaybackReadyMs", "sony", "firstSonyTrackAccepted", "sony", "firstPlaybackReady", _media_key, False),
        ("playbackReadyToQueueStartMs", "sony", "firstPlaybackReady", "sony", "firstPlaybackEnqueued", _media_key, False),
        ("playbackReadyToNotifyStartMs", "sony", "firstPlaybackReady", "sony", "firstPlaybackNotifyStart", _media_key, False),
        ("playbackNotifyStartToCallbackMs", "sony", "firstPlaybackNotifyStart", "sony", "firstPlaybackNotifyCallback", _media_key, False),
        ("notifyCallbackToIosReceiveMs", "sony", "firstPlaybackNotifyCallback", "ios", "firstPlaybackNotifyReceived", _media_key, True),
        ("iosReceiveToDecodeMs", "ios", "firstPlaybackNotifyReceived", "ios", "firstPlaybackDecodeEnd", _media_key, False),
        ("iosDecodeToPublishMs", "ios", "firstPlaybackDecodeEnd", "ios", "firstPlaybackPublished", _media_key, False),
        ("publishToUiConsumeMs", "ios", "firstIosTrackAccepted", "ios", "firstUiConsumed", _media_key, False),
        ("totalCommandToTrackPublishMs", "ios", "commandIntent", "ios", "firstIosTrackAccepted", _control_command_or_handoff_key, False),
        ("trackAcceptedToLyricReadyMs", "sony", "firstSonyTrackAccepted", "sony", "firstLyricReady", _media_key, False),
        ("lyricReadyToCurrentLineEnqueueMs", "sony", "firstLyricReady", "sony", "firstLineEnqueued", _media_key, False),
        ("currentLineEnqueueToPublishMs", "sony", "firstLineEnqueued", "ios", "firstLinePublished", _media_key, True, 0),
        ("trackAcceptedToWordEligibleMs", "sony", "firstSonyTrackAccepted", "sony", "firstWordEligible", _media_key, False),
        ("lyricReadyToWordEligibleMs", "sony", "firstLyricReady", "sony", "firstWordEligible", _media_key, False),
        ("wordEligibleToSchedulerCreatedMs", "sony", "firstWordEligible", "sony", "wordSchedulerCreated", _media_key, False),
        ("schedulerCreatedToFirstEnqueueMs", "sony", "wordSchedulerCreated", "sony", "wordEnqueued", _media_key, False),
        ("firstEnqueueToSendMs", "sony", "wordEnqueued", "sony", "wordSendStart", _media_key, False),
        ("sendToReceiveMs", "sony", "wordSendStart", "ios", "wordReceived", _media_key, True),
        ("receiveToAcceptMs", "ios", "wordReceived", "ios", "wordAccepted", _media_key, False),
        ("acceptToPublishMs", "ios", "wordAccepted", "ios", "wordPublished", _media_key, False),
        ("wordEligibleToPublishMs", "sony", "firstWordEligible", "ios", "wordPublished", _media_key, True),
        ("trackAcceptedToFirstWordMs", "ios", "firstIosTrackAcceptedImmediateWord", "ios", "wordPublished", _media_key, False),
    )
    for pair_spec in phase4_pair_specs:
        add_phase4_pair(*pair_spec)

    playback_dequeues = [
        event for event in events
        if event.side == "sony" and event.stage == "playbackDequeued"
    ]
    playback_queue_values = [
        event.queue_wait_ms for event in playback_dequeues
        if event.queue_wait_ms is not None
    ]
    metric_samples["playbackQueueWaitMs"] = playback_queue_values
    metrics["playbackQueueWaitMs"] = summarize(
        playback_queue_values,
        len(playback_dequeues) - len(playback_queue_values),
    )

    track_t0_events, track_publish_values, track_publish_missing = derive_track_t0(
        events,
        categories,
        clock_trusted,
        sony_to_ios_offset_ms,
    )
    metric_samples["commandToTrackPublishMs"] = track_publish_values
    metrics["commandToTrackPublishMs"] = summarize(
        track_publish_values,
        track_publish_missing,
    )

    usable_artwork_events = [
        replace(event, stage="usableArtworkPublished")
        for event in events
        if event.side == "ios" and event.stage in {"previewPublished", "hqPublished"}
    ]
    track_identity_t0_events = [
        replace(event, side="t0", stage="trackT0")
        for event in events
        if event.side == "ios"
        and event.stage == "trackIdentityAccepted"
        and event.result == "changed"
    ]
    track_pair_specs = (
        ("trackToCurrentLyricMs", "currentLyricPublished", _track_key),
        ("trackToCurrentWordMs", "currentWordPublished", _track_key),
        ("trackToPreviewArtMs", "usableArtworkPublished", _track_id_key),
        ("trackToHqArtMs", "hqPublished", _track_id_key),
    )
    track_analysis_events = [*events, *usable_artwork_events, *track_identity_t0_events]
    for name, end_stage, key in track_pair_specs:
        values, missing = paired_durations(
            track_analysis_events,
            "t0",
            "trackT0",
            "ios",
            end_stage,
            key,
            categories,
        )
        metric_samples[name] = values
        metrics[name] = summarize(values, missing)

    pair_specs = (
        ("commandToSonyReceiveMs", "ios", "commandIntent", "sony", "commandReceived", _control_command_key, True),
        ("lyricReadyToPendingFlushMs", "sony", "lyricReady", "sony", "pendingFlush", _ready_track_key, False),
        ("pendingFlushToSendStartMs", "sony", "pendingFlush", "sony", "fullLyricsSendStart", _track_key, False),
        ("fullLyricsSendDurationMs", "sony", "fullLyricsSendStart", "sony", "fullLyricsSendEnd", _transfer_key, False),
        ("lyricReadyToFullLyricsPublishMs", "sony", "lyricReady", "ios", "fullLyricsPublished", _ready_track_key, True),
        ("previewSendDurationMs", "sony", "previewSendStart", "sony", "previewSendEnd", _track_key, False),
        (
            "iOSPublishDurationMs",
            "ios",
            "playbackDecodeEnd",
            "ios",
            "playbackStatePublished",
            lambda event: ("playbackState",)
            if event.payload_type == "playbackState"
            else None,
            False,
        ),
    )
    pending_track_keys = {
        _track_key(event)
        for event in events
        if event.side == "sony" and event.stage == "pendingQueued"
    }
    for name, start_side, start_stage, end_side, end_stage, key, cross in pair_specs:
        analysis_events = events
        if name == "lyricReadyToPendingFlushMs":
            analysis_events = [
                event for event in events
                if event.stage != "lyricReady" or _track_key(event) in pending_track_keys
            ]
        values, missing = paired_durations(
            analysis_events,
            start_side,
            start_stage,
            end_side,
            end_stage,
            key,
            categories,
            cross_device=cross,
            clock_trusted=clock_trusted,
            sony_to_ios_offset_ms=sony_to_ios_offset_ms,
        )
        metric_samples[name] = values
        metrics[name] = summarize(values, missing)

    direct_specs = {
        "notifyQueueWaitMs": ("sony", {"notifyDequeued"}, "queue_wait_ms"),
        "iOSDecodeDurationMs": ("ios", {"playbackDecodeEnd"}, "processing_ms"),
    }
    for name, (side, stages, attribute) in direct_specs.items():
        candidates = [e for e in events if e.side == side and e.stage in stages]
        values = [getattr(e, attribute) for e in candidates if getattr(e, attribute) is not None]
        metric_samples[name] = values
        metrics[name] = summarize(values, len(candidates) - len(values))

    for name in METRIC_NAMES:
        metrics.setdefault(name, summarize([], 0))

    delay_thresholds = {
        "IOS_WRITE_DELAY": ("writeStartToCallbackMs", 120),
        "SONY_COMMAND_DELAY": ("sonyReceiveToDispatchEndMs", 120),
        "MEDIASESSION_SWITCH_DELAY": ("dispatchEndToMetadataObservedMs", 300),
        "METADATA_OBSERVATION_DELAY": ("metadataObservedToTrackAcceptedMs", 100),
        "TRACK_ACCEPTANCE_DELAY": ("trackAcceptedToPlaybackReadyMs", 100),
        "PLAYBACK_READ_DELAY": ("trackAcceptedToPlaybackReadyMs", 150),
        "NOTIFY_QUEUE_DELAY": ("playbackQueueWaitMs", 100),
        "BLE_NOTIFY_DELAY": ("playbackNotifyStartToCallbackMs", 100),
        "IOS_PUBLISH_DELAY": ("iosDecodeToPublishMs", 100),
        "COMMAND_DELAY": ("commandToSonyReceiveMs", 200),
        "TRACK_IDENTITY_DELAY": ("commandToTrackPublishMs", 300),
        "LYRIC_READY_DELAY": ("trackToCurrentLyricMs", 500),
        "PENDING_FLUSH_DELAY": ("lyricReadyToPendingFlushMs", 100),
        "SEND_QUEUE_DELAY": ("notifyQueueWaitMs", 200),
        "CURRENT_WORD_DELAY": ("trackToCurrentWordMs", 500),
        "ARTWORK_DELAY": ("trackToPreviewArtMs", 800),
        "IOS_DECODE_DELAY": ("iOSDecodeDurationMs", 50),
    }
    classifications = {
        category: sum(1 for value in metric_samples.get(metric, []) if value > threshold)
        for category, (metric, threshold) in delay_thresholds.items()
    }
    classifications["CLOCK_SYNC_UNTRUSTED"] = 1 if categories["untrusted_clock"] else 0
    classifications["TRACE_INCOMPLETE"] = (
        malformed
        + categories["empty"]
        + categories["out_of_order"]
        + sum(metric["missing"] for metric in metrics.values())
    )
    classifications["STALE_CONTENT"] = (
        categories["stale_generation"]
        + categories["wrong_current_word"]
        + categories["wrong_artwork"]
        + categories["wrong_lyrics"]
    )
    slow_samples = sorted(
        (
            {
                "traceId": f"{name}-{index + 1}",
                "trackIdSummary": None,
                "slowestStage": name,
                "durationMs": value,
                "cacheHit": None,
                "longTaskCompetition": None,
                "currentWordPreempted": None,
                "retry": None,
            }
            for name, samples in metric_samples.items()
            for index, value in enumerate(samples)
        ),
        key=lambda sample: sample["durationMs"],
        reverse=True,
    )[:10]

    samples, missing_events = classify_transition_samples(
        events,
        clock_trusted and sony_to_ios_offset_ms is not None,
        sony_to_ios_offset_ms,
    )

    return {
        "schemaVersion": 2,
        "clock": {
            "crossDeviceTrusted": clock_trusted and sony_to_ios_offset_ms is not None,
            "sonyToIosOffsetMs": sony_to_ios_offset_ms,
        },
        "eventCount": len(events),
        "sideCounts": dict(Counter(event.side for event in events)),
        "stageCounts": dict(Counter(event.stage for event in events)),
        "metrics": metrics,
        "categories": classifications,
        "diagnostics": dict(sorted(categories.items())),
        "slowSamples": slow_samples,
        "samples": samples,
        "missingEvents": missing_events,
    }


def render_summary(report: dict) -> str:
    lines = [
        "# MusicBleController V4 Real-time SLO Summary",
        "",
        f"Events: {report['eventCount']}",
        f"Cross-device clock trusted: {report['clock']['crossDeviceTrusted']}",
        "",
        "| Metric | Count | P50 | P95 | P99 | Max | Missing |",
        "|---|---:|---:|---:|---:|---:|---:|",
    ]
    for name in METRIC_NAMES:
        metric = report["metrics"][name]
        value = lambda key: "-" if metric[key] is None else f"{metric[key]:.1f}"
        lines.append(
            f"| {name} | {metric['count']} | {value('p50')} | {value('p95')} | "
            f"{value('p99')} | {value('max')} | {metric['missing']} |"
        )
    lines.extend(("", "## Diagnostic categories", ""))
    if report["categories"]:
        lines.extend(f"- {key}: {value}" for key, value in report["categories"].items())
    else:
        lines.append("- none")
    return "\n".join(lines) + "\n"


def write_combined_trace(paths: list[Path], destination: Path) -> None:
    """Preserve the report bundle contract even when a trace source is empty."""
    contents = [
        path.read_text(encoding="utf-8", errors="replace")
        for path in paths
        if path.exists()
    ]
    with destination.open("w", encoding="utf-8") as output:
        for content in contents:
            output.write(content)
            if content and not content.endswith("\n"):
                output.write("\n")


def main(argv: Optional[list[str]] = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sony-trace", type=Path, action="append", default=[])
    parser.add_argument("--ios-trace", type=Path, action="append", default=[])
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--clock-trusted", action="store_true")
    parser.add_argument("--sony-to-ios-offset-ms", type=int)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)

    trace_paths = [*args.sony_trace, *args.ios_trace]
    events, malformed = load_events(trace_paths)
    discovered_trusted, discovered_offset = discover_clock_sync(args.ios_trace)
    clock_trusted = args.clock_trusted or discovered_trusted
    sony_to_ios_offset_ms = (
        args.sony_to_ios_offset_ms
        if args.sony_to_ios_offset_ms is not None
        else discovered_offset
    )
    report = analyze(
        events,
        malformed=malformed,
        clock_trusted=clock_trusted,
        sony_to_ios_offset_ms=sony_to_ios_offset_ms,
    )
    args.output_dir.mkdir(parents=True, exist_ok=True)
    write_combined_trace(args.sony_trace, args.output_dir / "sony_trace.log")
    write_combined_trace(args.ios_trace, args.output_dir / "ios_trace.log")
    (args.output_dir / "report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    (args.output_dir / "summary.md").write_text(render_summary(report), encoding="utf-8")
    (args.output_dir / "sample_classification.json").write_text(
        json.dumps(report.get("samples", []), ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    (args.output_dir / "missing_events.json").write_text(
        json.dumps(report.get("missingEvents", []), ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    with (args.output_dir / "raw_events.jsonl").open("w", encoding="utf-8") as handle:
        for event in events:
            handle.write(json.dumps(event.as_dict(), ensure_ascii=False) + "\n")
    if args.json:
        json.dump(report, sys.stdout, ensure_ascii=False, indent=2)
        sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
