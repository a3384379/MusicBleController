import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "realtime_latency_report.py"
SPEC = importlib.util.spec_from_file_location("realtime_latency_report", MODULE_PATH)
REPORT = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = REPORT
SPEC.loader.exec_module(REPORT)


def event(side, stage, mono, **kwargs):
    if side == "ios" and stage == "trackIdentityAccepted":
        kwargs.setdefault("result", "changed")
    return REPORT.Event(side=side, stage=stage, mono_ms=mono, **kwargs)


class RealtimeLatencyReportTests(unittest.TestCase):
    def phase4_complete_events(self):
        common = {"handoff_id": "command-9", "track_id": "track-a", "generation": 4}
        return [
            event("ios", "commandIntent", 0, command_seq=9, command_type="NEXT", handoff_id="command-9", trigger_type="IOS_NEXT", source_line=1),
            event("ios", "commandEnqueued", 1, command_seq=9, command_type="NEXT", handoff_id="command-9", source_line=2),
            event("ios", "commandWriteStart", 2, command_seq=9, command_type="NEXT", handoff_id="command-9", source_line=3),
            event("ios", "commandWriteCallback", 4, command_seq=9, command_type="NEXT", handoff_id="command-9", source_line=4),
            event("sony", "commandReceived", 5, command_seq=9, command_type="NEXT", handoff_id="command-9", source_line=5),
            event("sony", "commandValidated", 6, command_seq=9, command_type="NEXT", handoff_id="command-9", source_line=6),
            event("sony", "mediaControlDispatchStart", 7, command_seq=9, command_type="NEXT", handoff_id="command-9", source_line=7),
            event("sony", "mediaControlDispatchEnd", 8, command_seq=9, command_type="NEXT", handoff_id="command-9", source_line=8),
            event("sony", "notificationMetadataObserved", 9, handoff_id="command-9", source_line=9),
            event("sony", "playbackReadStart", 9, handoff_id="command-9", source_line=10),
            event("sony", "mediaSessionMetadataObserved", 10, **common, source_line=11),
            event("sony", "trackIdentityCandidate", 10, **common, source_line=12),
            event("sony", "trackIdentityAccepted", 11, **common, source_line=13),
            event("sony", "mediaGenerationCreated", 12, **common, source_line=14),
            event("sony", "playbackReady", 13, **common, source_line=15),
            event("sony", "playbackEnqueued", 14, **common, source_line=16),
            event("sony", "playbackDequeued", 15, queue_wait_ms=1, **common, source_line=17),
            event("sony", "playbackNotifyStart", 16, **common, source_line=18),
            event("sony", "playbackNotifyCallback", 17, **common, source_line=19),
            event("ios", "playbackNotifyReceived", 18, **common, source_line=20),
            event("ios", "playbackDecodeEnd", 19, payload_type="playbackState", **common, source_line=21),
            event("ios", "trackIdentityAccepted", 20, result="changed", **common, source_line=22),
            event("ios", "playbackStatePublished", 21, payload_type="playbackState", **common, source_line=23),
            event("ios", "nowPlayingStateConsumed", 22, **common, source_line=24),
            event("sony", "lyricReady", 23, result="ready", word_timing_status="AVAILABLE", cache_source="PARSED", **common, source_line=25),
            event("sony", "lyricCurrentLineEnqueued", 24, **common, source_line=26),
            event("ios", "lyricCurrentLinePublished", 25, **common, source_line=27),
            event("sony", "currentWordEligible", 26, word_timing_status="AVAILABLE", **common, source_line=28),
            event("sony", "currentWordSchedulerCreated", 27, **common, source_line=29),
            event("sony", "currentWordImmediateSnapshotEnqueued", 28, **common, source_line=30),
            event("sony", "currentWordSendStart", 29, **common, source_line=31),
            event("ios", "currentWordReceived", 30, **common, source_line=32),
            event("ios", "currentWordAccepted", 31, **common, source_line=33),
            event("ios", "currentWordPublished", 32, **common, source_line=34),
        ]

    def test_phase4_complete_handoff_breakdown_and_classification(self):
        report = REPORT.analyze(
            self.phase4_complete_events(),
            clock_trusted=True,
            sony_to_ios_offset_ms=0,
        )
        self.assertEqual(report["metrics"]["commandIntentToWriteStartMs"]["p50"], 2)
        self.assertEqual(report["metrics"]["dispatchEndToMetadataObservedMs"]["p50"], 1)
        self.assertEqual(report["metrics"]["wordEligibleToPublishMs"]["p50"], 6)
        self.assertIn("COMPLETE", report["samples"][0]["classifications"])
        self.assertEqual(report["missingEvents"], [])

    def test_phase4_prefers_ios_command_anchor_across_unrelated_monotonic_clocks(self):
        events = self.phase4_complete_events()
        events.append(event(
            "sony",
            "mediaSessionMetadataObserved",
            1,
            command_seq=9,
            command_type="NEXT",
            track_id="track-a",
            generation=4,
            source_line=90,
        ))
        samples, _ = REPORT.classify_transition_samples(events, clock_trusted=True)
        self.assertEqual(len(samples), 1)
        self.assertEqual(samples[0]["handoffId"], "command-9")
        self.assertEqual(samples[0]["triggerType"], "IOS_NEXT")

    def test_phase4_missing_ios_and_sony_events_are_named(self):
        ios_missing = [
            item for item in self.phase4_complete_events()
            if item.stage != "nowPlayingStateConsumed"
        ]
        report = REPORT.analyze(ios_missing, clock_trusted=True, sony_to_ios_offset_ms=0)
        self.assertIn("nowPlayingStateConsumed", report["missingEvents"][0]["missingEvents"])
        sony_missing = [
            item for item in self.phase4_complete_events()
            if item.stage != "mediaGenerationCreated"
        ]
        report = REPORT.analyze(sony_missing, clock_trusted=True, sony_to_ios_offset_ms=0)
        self.assertIn("mediaGenerationCreated", report["missingEvents"][0]["missingEvents"])

    def test_phase4_command_only_is_not_a_handoff_sample(self):
        report = REPORT.analyze([
            event("ios", "commandIntent", 1, command_seq=3, command_type="NEXT", handoff_id="command-3", source_line=1),
            event("ios", "commandEnqueued", 2, command_seq=3, command_type="NEXT", handoff_id="command-3", source_line=2),
        ])
        labels = report["samples"][0]["classifications"]
        self.assertIn("NO_TRACK_CHANGE", labels)
        self.assertIn("COMMAND_ONLY", labels)
        self.assertNotIn("COMPLETE", labels)

    def test_phase4_control_segments_ignore_protocol_commands(self):
        events = self.phase4_complete_events()
        events.extend([
            event(
                "ios", "commandIntent", 40, command_seq=10,
                command_type="CAPABILITIES", source_line=40,
            ),
            event(
                "ios", "commandWriteStart", 140, command_seq=10,
                command_type="CAPABILITIES", source_line=41,
            ),
            event(
                "ios", "commandWriteCallback", 340, command_seq=10,
                command_type="CAPABILITIES", source_line=42,
            ),
        ])
        report = REPORT.analyze(
            events,
            clock_trusted=True,
            sony_to_ios_offset_ms=0,
        )
        self.assertEqual(report["metrics"]["commandIntentToWriteStartMs"]["count"], 1)
        self.assertEqual(report["metrics"]["commandIntentToWriteStartMs"]["p50"], 2)
        self.assertEqual(report["metrics"]["writeStartToCallbackMs"]["p50"], 2)

    def test_phase4_line_only_intro_and_rejected_word_classification(self):
        events = self.phase4_complete_events()
        events.extend([
            event("sony", "currentWordNotEligible", 24, reason="INTRO_WAIT", word_timing_status="LINE_ONLY", handoff_id="command-9", track_id="track-a", generation=4, source_line=40),
            event("ios", "currentWordRejected", 31, reason="SEQUENCE_OLD", handoff_id="command-9", track_id="track-a", generation=4, source_line=41),
            event("sony", "lyricParsedCacheMiss", 12, handoff_id="command-9", track_id="track-a", generation=4, source_line=42),
        ])
        labels = REPORT.analyze(events)["samples"][0]["classifications"]
        self.assertIn("LINE_ONLY_LYRICS", labels)
        self.assertIn("INTRO_WAIT", labels)
        self.assertIn("STALE_REJECTED", labels)
        self.assertIn("LYRIC_CACHE_MISS", labels)

    def test_phase4_dual_handoffs_remain_isolated(self):
        first = self.phase4_complete_events()
        second = [
            REPORT.replace(
                item,
                handoff_id="command-10",
                command_seq=10 if item.command_seq is not None else None,
                track_id="track-b" if item.track_id is not None else None,
                generation=5 if item.generation is not None else None,
                mono_ms=item.mono_ms + 100,
                source_line=item.source_line + 100,
            )
            for item in first
        ]
        report = REPORT.analyze(first + second, clock_trusted=True, sony_to_ios_offset_ms=0)
        self.assertEqual(len(report["samples"]), 2)
        self.assertEqual({sample["handoffId"] for sample in report["samples"]}, {"command-9", "command-10"})
    def test_normal_trace_and_percentiles(self):
        events = [
            event(
                "ios", "commandIntent", 90, command_seq=1, command_type="NEXT",
                source_line=1,
            ),
            event("ios", "trackIdentityAccepted", 100, track_id="t", generation=1, source_line=2),
            event("ios", "currentWordPublished", 160, track_id="t", generation=1, source_line=3),
            event("ios", "currentWordPublished", 170, track_id="t", generation=1, source_line=4),
            event("sony", "notifyDequeued", 200, queue_wait_ms=12, source_line=5),
            event("ios", "playbackDecodeEnd", 220, processing_ms=4, source_line=6),
            event(
                "sony", "pendingQueued", 290, track_id="t", generation=1,
                source_line=7,
            ),
            event(
                "sony", "lyricReady", 300, track_id="t", generation=1,
                result="ready", source_line=8,
            ),
            event("sony", "pendingFlush", 325, track_id="t", generation=1, source_line=9),
        ]
        report = REPORT.analyze(events)
        self.assertEqual(report["metrics"]["commandToTrackPublishMs"]["p50"], 10)
        self.assertEqual(report["metrics"]["trackToCurrentWordMs"]["p50"], 70)
        self.assertEqual(report["metrics"]["trackToCurrentWordMs"]["count"], 1)
        self.assertEqual(report["metrics"]["notifyQueueWaitMs"]["avg"], 12)
        self.assertEqual(report["metrics"]["iOSDecodeDurationMs"]["max"], 4)
        self.assertEqual(report["metrics"]["lyricReadyToPendingFlushMs"]["p50"], 25)

    def test_missing_and_empty(self):
        report = REPORT.analyze([
            event(
                "ios", "commandIntent", 90, command_seq=1, command_type="NEXT",
                source_line=1,
            ),
            event("ios", "trackIdentityAccepted", 100, track_id="t", generation=1, source_line=2),
        ])
        self.assertEqual(report["metrics"]["trackToCurrentWordMs"]["missing"], 1)
        self.assertEqual(REPORT.analyze([])["diagnostics"]["empty"], 1)

    def test_duplicate_out_of_order_stale_and_extreme(self):
        duplicate = event("ios", "trackIdentityAccepted", 200, track_id="t", generation=2, source_line=1)
        events = [
            event(
                "ios", "commandIntent", 190, command_seq=1, command_type="NEXT",
                source_line=7,
            ),
            duplicate,
            event("ios", "trackIdentityAccepted", 200, track_id="t", generation=2, source_line=2),
            event("ios", "currentWordPublished", 100, track_id="t", generation=2, source_line=3),
            event("ios", "trackIdentityAccepted", 300, track_id="t", generation=1, source_line=4),
            event("sony", "fullLyricsSendStart", 1, track_id="x", generation=1, source_line=5),
            event("sony", "fullLyricsSendEnd", 130_002, track_id="x", generation=1, source_line=6),
        ]
        report = REPORT.analyze(events)
        self.assertGreaterEqual(report["diagnostics"]["duplicate"], 1)
        self.assertGreaterEqual(report["diagnostics"]["out_of_order"], 1)
        self.assertGreaterEqual(report["diagnostics"]["stale_generation"], 1)
        self.assertGreaterEqual(report["diagnostics"]["extreme"], 1)

    def test_unknown_generation_zero_is_not_reported_as_stale(self):
        report = REPORT.analyze([
            event(
                "ios", "trackIdentityAccepted", 100,
                track_id="t", generation=1, source_line=1,
            ),
            event(
                "ios", "playbackStatePublished", 110,
                track_id="t", generation=0, source_line=2,
            ),
        ])
        self.assertEqual(report["diagnostics"].get("stale_generation", 0), 0)

    def test_cross_device_requires_trusted_clock(self):
        events = [
            event("ios", "commandIntent", 100, command_seq=7, command_type="NEXT", source_line=1),
            event("sony", "commandReceived", 30, command_seq=7, command_type="NEXT", source_line=2),
        ]
        blocked = REPORT.analyze(events)
        self.assertEqual(blocked["metrics"]["commandToSonyReceiveMs"]["count"], 0)
        self.assertEqual(blocked["categories"]["CLOCK_SYNC_UNTRUSTED"], 1)
        trusted = REPORT.analyze(events, clock_trusted=True, sony_to_ios_offset_ms=90)
        self.assertEqual(trusted["metrics"]["commandToSonyReceiveMs"]["p50"], 20)
        near_zero = REPORT.analyze(events, clock_trusted=True, sony_to_ios_offset_ms=65)
        self.assertEqual(near_zero["metrics"]["commandToSonyReceiveMs"]["p50"], 0)
        self.assertEqual(near_zero["diagnostics"]["clock_uncertainty_clamped"], 1)

    def test_manual_track_pairing_does_not_shift_after_missed_command(self):
        events = [
            event(
                "ios", "commandIntent", 100, command_seq=1,
                command_type="NEXT", source_line=1,
            ),
            event(
                "ios", "commandIntent", 200, command_seq=2,
                command_type="NEXT", source_line=2,
            ),
            event(
                "ios", "trackIdentityAccepted", 240, track_id="new",
                generation=2, source_line=3,
            ),
        ]
        report = REPORT.analyze(events)
        metric = report["metrics"]["commandToTrackPublishMs"]
        self.assertEqual(metric["count"], 1)
        self.assertEqual(metric["p50"], 40)
        self.assertEqual(metric["missing"], 1)

    def test_automatic_t0_requires_trusted_clock(self):
        events = [
            event(
                "sony", "trackIdentityAccepted", 100, track_id="t", generation=3,
                source_line=1,
            ),
            event(
                "ios", "trackIdentityAccepted", 210, track_id="t", generation=3,
                source_line=2,
            ),
            event(
                "ios", "currentLyricPublished", 260, track_id="t", generation=3,
                source_line=3,
            ),
        ]
        blocked = REPORT.analyze(events)
        self.assertEqual(blocked["metrics"]["commandToTrackPublishMs"]["count"], 0)
        trusted = REPORT.analyze(
            events,
            clock_trusted=True,
            sony_to_ios_offset_ms=80,
        )
        self.assertEqual(trusted["metrics"]["commandToTrackPublishMs"]["p50"], 30)
        self.assertEqual(trusted["metrics"]["trackToCurrentLyricMs"]["p50"], 80)

    def test_correctness_flags_stale_publish_and_duplicate_control(self):
        events = [
            event(
                "ios", "trackIdentityAccepted", 100, track_id="new", generation=2,
                source_line=1,
            ),
            event(
                "ios", "currentWordPublished", 110, track_id="old", generation=1,
                source_line=2,
            ),
            event(
                "ios", "previewPublished", 120, track_id="old", generation=1,
                source_line=3,
            ),
            event(
                "sony", "commandReceived", 10, command_seq=5, command_type="NEXT",
                source_line=4,
            ),
            event(
                "sony", "commandReceived", 11, command_seq=5, command_type="NEXT",
                source_line=5,
            ),
            event(
                "sony", "mediaControlDispatchStart", 12, command_seq=5,
                command_type="NEXT", source_line=6,
            ),
            event(
                "sony", "mediaControlDispatchStart", 13, command_seq=5,
                command_type="NEXT", source_line=7,
            ),
        ]
        report = REPORT.analyze(events)
        self.assertGreaterEqual(report["categories"]["STALE_CONTENT"], 1)
        self.assertEqual(report["diagnostics"]["wrong_current_word"], 1)
        self.assertEqual(report["diagnostics"]["wrong_artwork"], 1)
        self.assertEqual(report["diagnostics"]["duplicate_control"], 1)
        self.assertEqual(report["diagnostics"]["control_reconnect_resend"], 1)

    def test_parser_rejects_malformed_and_ignores_payload_body(self):
        line = (
            "prefix [RealtimeTrace] side=ios stage=playbackDecodeEnd monoMs=10 "
            "commandSeq=- commandType=- trackId=t generation=1 transferId=- "
            "payloadType=playbackState queueWaitMs=- processingMs=3 chunkIndex=- "
            "chunkCount=- result=success reason=-"
        )
        parsed = REPORT.parse_trace_line(line, 8)
        self.assertEqual(parsed.processing_ms, 3)
        self.assertEqual(parsed.source_line, 8)
        self.assertIsNone(REPORT.parse_trace_line("[RealtimeTrace] side=ios stage=x"))

    def test_clock_sync_discovery_requires_confident_sample(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "ios.log"
            path.write_text(
                "[ClockSync] pong seq=1 bestRttMs=20 offsetMs=123 "
                "jitterMs=4 samples=3 confident=true\n"
            )
            self.assertEqual(REPORT.discover_clock_sync([path]), (True, 123))

    def test_report_bundle_contains_required_files_without_trace_input(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "report"
            self.assertEqual(REPORT.main(["--output-dir", str(output)]), 0)
            required = {
                "report.json",
                "summary.md",
                "raw_events.jsonl",
                "sony_trace.log",
                "ios_trace.log",
                "sample_classification.json",
                "missing_events.json",
            }
            self.assertEqual({path.name for path in output.iterdir()}, required)

    def test_report_bundle_preserves_in_place_trace_sources(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            sony = output / "sony_trace.log"
            ios = output / "ios_trace.log"
            sony.write_text("sony-event\n", encoding="utf-8")
            ios.write_text("ios-event\n", encoding="utf-8")
            self.assertEqual(REPORT.main([
                "--sony-trace", str(sony),
                "--ios-trace", str(ios),
                "--output-dir", str(output),
            ]), 0)
            self.assertEqual(sony.read_text(encoding="utf-8"), "sony-event\n")
            self.assertEqual(ios.read_text(encoding="utf-8"), "ios-event\n")


if __name__ == "__main__":
    unittest.main()
