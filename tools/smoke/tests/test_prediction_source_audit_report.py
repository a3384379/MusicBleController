import importlib.util
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "prediction_source_audit_report.py"
SPEC = importlib.util.spec_from_file_location("prediction_source_audit_report", MODULE_PATH)
REPORT = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(REPORT)


def trace(stage, mono, track="-", generation=1, command="-"):
    return (
        "[RealtimeTrace] side=sony "
        f"stage={stage} monoMs={mono} commandSeq=1 commandType={command} "
        f"trackId={track} generation={generation} transferId=- payloadType=- "
        "queueWaitMs=- processingMs=- chunkIndex=- chunkCount=- result=ok reason=-"
    )


class PredictionSourceAuditReportTests(unittest.TestCase):
    def test_queue_null_is_none_and_privacy_safe(self):
        text = "\n".join([
            trace("commandReceived", 100, command="NEXT"),
            "[PredictiveLyricsCandidate] queue diagnostic source=manual_next_with_queue "
            "hasQueue=false queueSize=-1 activeQueueId=-1 currentQueueId=-1",
            "[PredictiveLyricsCandidate] source=manual_next_with_queue unavailable reason=queue_null",
            trace("mediaSessionTrackChanged", 200, track="actual", generation=2),
            "[RuntimeCache] track changed trackId=actual songKey=Secret|Artist|Album "
            "title=Secret generation=2",
        ])
        report = REPORT.analyze(text, expected_transitions=1)
        self.assertEqual(report["result"], "PASS")
        self.assertEqual(report["sourceAudit"]["highConfidenceCandidateCount"], 0)
        self.assertEqual(report["sourceAudit"]["queueAvailableObservationCount"], 0)
        self.assertNotIn("Secret", str(report))

    def test_confirmed_queue_candidate_matches_actual_identity(self):
        text = "\n".join([
            trace("commandReceived", 100, command="NEXT"),
            "[PredictiveLyricsCandidate] queue diagnostic source=manual_next_with_queue "
            "hasQueue=true queueSize=3 activeQueueId=10 currentQueueId=10",
            "[PredictiveLyricsCandidate] selected source=manual_next_with_queue "
            "confidence=1.0 title=Next Song artist=Artist queueId=11 mediaId=stable-11 "
            "reason=manual next with visible queue",
            trace("mediaSessionTrackChanged", 260, track="actual", generation=2),
            "[RuntimeCache] track changed trackId=actual songKey=Next Song|Artist|Album "
            "title=Next Song generation=2",
        ])
        report = REPORT.analyze(text, expected_transitions=1)
        audit = report["sourceAudit"]
        self.assertEqual(audit["highConfidenceCandidateCount"], 1)
        self.assertEqual(audit["predictionHitCount"], 1)
        self.assertEqual(audit["highConfidenceAccuracy"], 1.0)
        self.assertEqual(audit["averageLeadTimeMs"], 160)

    def test_incomplete_transition_sample_fails(self):
        report = REPORT.analyze("", expected_transitions=1)
        self.assertEqual(report["result"], "FAIL")
        self.assertEqual(report["failureReason"], "transition_sample_incomplete")

    def test_log_buffer_duplicate_is_counted_once(self):
        command = trace("commandReceived", 100, command="NEXT")
        queue = (
            "[PredictiveLyricsCandidate] queue diagnostic source=manual_next_with_queue "
            "hasQueue=false queueSize=-1 activeQueueId=-1 currentQueueId=-1"
        )
        changed = trace("mediaSessionTrackChanged", 200, track="actual", generation=2)
        text = "\n".join([command, command, queue, queue, changed, changed])

        report = REPORT.analyze(text, expected_transitions=1)

        self.assertEqual(report["sourceAudit"]["transitions"], 1)
        self.assertEqual(report["sourceAudit"]["queueObservationCount"], 1)

    def test_v4_privacy_safe_candidate_uses_exact_promotion_result(self):
        text = "\n".join([
            trace("commandReceived", 100, command="NEXT"),
            "[PredictionSource] queue diagnostic source=manual_next_with_queue "
            "hasQueue=true queueSize=3 activeQueueId=10 currentQueueId=10 "
            "currentIdentityDigest=abc metadataTitlePresent=true "
            "metadataArtistPresent=true metadataAlbumPresent=true "
            "metadataDurationMs=180000 metadataMediaIdPresent=true "
            "completeQueueIdentityCount=3",
            "[PredictionSource] selected candidateId=candidate-1 "
            "identityDigest=0123456789abcdef01234567 "
            "source=manual_next_with_queue confidence=CONFIRMED",
            trace("mediaSessionTrackChanged", 240, track="actual", generation=2),
            "[PredictiveMedia] stage=predictionPromotionAttempt candidateId=candidate-1 "
            "identityDigest=0123456789abcdef01234567 "
            "source=manual_next_with_queue confidence=CONFIRMED result=started reason=",
            "[PredictiveMedia] stage=predictionPromoted candidateId=candidate-1 "
            "identityDigest=0123456789abcdef01234567 "
            "source=manual_next_with_queue confidence=CONFIRMED result=success reason=",
        ])

        report = REPORT.analyze(text, expected_transitions=1)
        audit = report["sourceAudit"]
        self.assertEqual(audit["highConfidenceCandidateCount"], 1)
        self.assertEqual(audit["predictionHitCount"], 1)
        self.assertEqual(audit["highConfidenceAccuracy"], 1.0)
        self.assertNotIn("Secret Song", str(report))

    def test_v4_identity_mismatch_is_a_prediction_miss(self):
        text = "\n".join([
            trace("commandReceived", 100, command="PREVIOUS"),
            "[PredictionSource] selected candidateId=candidate-2 "
            "identityDigest=abcdef0123456789abcdef01 "
            "source=manual_previous_with_queue confidence=STRONG",
            trace("mediaSessionTrackChanged", 260, track="actual", generation=2),
            "[PredictiveMedia] stage=predictionRejected candidateId=candidate-2 "
            "identityDigest=abcdef0123456789abcdef01 "
            "source=manual_previous_with_queue confidence=STRONG "
            "result=rejected reason=identity_mismatch",
        ])

        report = REPORT.analyze(text, expected_transitions=1)
        audit = report["sourceAudit"]
        self.assertEqual(audit["predictionMissCount"], 1)
        self.assertEqual(audit["highConfidenceAccuracy"], 0.0)

    def test_history_candidate_is_counted_as_weak_only(self):
        text = "\n".join([
            trace("commandReceived", 100, command="NEXT"),
            "[PredictionSource] history candidateId=history-1 "
            "identityDigest=abcdef0123456789abcdef01 confidence=WEAK count=2",
            trace("mediaSessionTrackChanged", 200, track="actual", generation=2),
        ])
        report = REPORT.analyze(text, expected_transitions=1)
        audit = report["sourceAudit"]
        self.assertEqual(audit["predictionCandidateCount"], 1)
        self.assertEqual(audit["highConfidenceCandidateCount"], 0)
        self.assertEqual(report["transitions"][0]["confidence"], "WEAK")

    def test_v4_unavailable_reason_is_counted(self):
        text = "\n".join([
            trace("commandReceived", 100, command="NEXT"),
            "[PredictionSource] queue diagnostic source=manual_next_with_queue "
            "hasQueue=false queueSize=-1 activeQueueId=-1 currentQueueId=-1",
            "[PredictionSource] source=manual_next_with_queue unavailable reason=queue_null",
            trace("mediaSessionTrackChanged", 200, track="actual", generation=2),
        ])
        report = REPORT.analyze(text, expected_transitions=1)
        self.assertEqual(report["sourceAudit"]["rejectedReasonCounts"]["queue_null"], 1)


if __name__ == "__main__":
    unittest.main()
