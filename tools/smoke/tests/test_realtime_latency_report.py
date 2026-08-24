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


if __name__ == "__main__":
    unittest.main()
