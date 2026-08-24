import importlib.util
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "predictive_media_report.py"
SPEC = importlib.util.spec_from_file_location("predictive_media_report", MODULE_PATH)
REPORT = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(REPORT)


def trace(stage, mono, side="ios", track="track-1", generation=1, result="published", reason="-"):
    return (
        f"[RealtimeTrace] side={side} stage={stage} monoMs={mono} "
        f"commandSeq=- commandType=- trackId={track} generation={generation} "
        f"transferId=- payloadType=fullLyrics queueWaitMs=- processingMs=- "
        f"chunkIndex=- chunkCount=- result={result} reason={reason}"
    )


def audit(high=0, hits=0, misses=0):
    return {
        "result": "PASS",
        "sourceAudit": {
            "predictionEligibleCount": 10,
            "predictionCandidateCount": high,
            "highConfidenceCandidateCount": high,
            "predictionHitCount": hits,
            "predictionMissCount": misses,
            "highConfidenceAccuracy": hits / (hits + misses) if hits + misses else None,
        },
    }


class PredictiveMediaReportTests(unittest.TestCase):
    def test_no_candidate_marks_warm_not_applicable(self):
        report = REPORT.analyze("", "", audit())
        self.assertEqual(report["latency"]["warmResult"], "NOT_APPLICABLE")

    def test_candidate_hit_is_reported_without_fabricated_warm_latency(self):
        report = REPORT.analyze("", "", audit(high=1, hits=1))
        self.assertEqual(report["prediction"]["hitCount"], 1)
        self.assertEqual(report["latency"]["warmResult"], "TRACE_CORRELATION_UNAVAILABLE")
        self.assertEqual(report["latency"]["warm"]["trackToCurrentLyric"]["count"], 0)

    def test_candidate_error_preserves_accuracy(self):
        report = REPORT.analyze("", "", audit(high=1, misses=1))
        self.assertEqual(report["prediction"]["accuracy"], 0.0)

    def test_prewarm_complete_is_counted(self):
        sony = "\n".join([
            "[PredictiveMedia] stage=predictionPrewarmQueued candidateId=c1 "
            "identityDigest=d1 source=queue confidence=CONFIRMED result=queued reason=",
            "[PredictiveMedia] stage=predictionReady candidateId=c1 "
            "identityDigest=d1 source=queue confidence=CONFIRMED result=ready reason=",
        ])
        report = REPORT.analyze(sony, "", audit())
        self.assertEqual(report["prewarm"]["ready"], 1)

    def test_prewarm_incomplete_has_no_ready_count(self):
        sony = (
            "[PredictiveMedia] stage=predictionPrewarmQueued candidateId=c1 "
            "identityDigest=d1 source=queue confidence=CONFIRMED result=queued reason="
        )
        report = REPORT.analyze(sony, "", audit())
        self.assertEqual(report["prewarm"]["ready"], 0)

    def test_cache_hit_is_counted(self):
        report = REPORT.analyze(
            trace("cacheValidationHit", 100, side="sony"),
            trace("cacheValidationHit", 100),
            audit(),
        )
        self.assertEqual(report["cache"]["fullLyricsCacheValidationHit"], 1)

    def test_full_lyrics_skip_and_saved_bytes_are_counted(self):
        sony = trace("fullLyricsTransferSkipped", 100, side="sony") + " bytesSaved=4096"
        report = REPORT.analyze(sony, "", audit())
        self.assertEqual(report["cache"]["fullLyricsTransferSkipped"], 1)
        self.assertEqual(report["cache"]["fullLyricsBytesSaved"], 4096)

    def test_missing_trace_stays_missing(self):
        report = REPORT.analyze("not a trace", "not a trace", audit())
        self.assertEqual(report["trace"]["eventCount"], 0)
        self.assertEqual(report["latency"]["cold"]["trackToCurrentWord"]["count"], 0)

    def test_out_of_order_events_are_sorted_before_latency_calculation(self):
        ios = "\n".join([
            trace("currentLyricPublished", 250),
            trace("trackIdentityAccepted", 100, result="accepted"),
        ])
        report = REPORT.analyze("", ios, audit())
        self.assertEqual(report["latency"]["cold"]["trackToCurrentLyric"]["p95"], 150)

    def test_stale_accepted_event_fails_correctness(self):
        ios = trace("currentWordPublished", 100, result="accepted", reason="stale_generation")
        report = REPORT.analyze("", ios, audit())
        self.assertEqual(report["result"], "FAIL")
        self.assertEqual(report["correctness"]["staleAccepted"], 1)

    def test_dual_transfer_ids_remain_distinct(self):
        sony = "\n".join([
            trace("fullLyricsSendStart", 100, side="sony").replace("transferId=-", "transferId=a"),
            trace("fullLyricsSendStart", 100, side="sony").replace("transferId=-", "transferId=b"),
        ])
        report = REPORT.analyze(sony, "", audit())
        self.assertEqual(report["trace"]["stageCounts"]["fullLyricsSendStart"], 2)

    def test_warm_and_cold_sections_always_exist(self):
        ios = "\n".join([
            trace("trackIdentityAccepted", 100, result="accepted"),
            trace("fullLyricsPublished", 200),
        ])
        report = REPORT.analyze("", ios, audit())
        self.assertIn("warm", report["latency"])
        self.assertEqual(
            report["latency"]["cold"]["trackToFullLyricsAvailable"]["p95"],
            100,
        )


if __name__ == "__main__":
    unittest.main()
