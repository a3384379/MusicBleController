import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from evaluate import BOOL_CHECKS, evaluate, percentile95


class EvidenceTest(unittest.TestCase):
    def setUp(self):
        self.template = json.loads((Path(__file__).resolve().parents[1] / "evidence.template.json").read_text())

    def complete_fixture(self, directory):
        # Synthetic evaluator fixture, never saved as real-device evidence.
        e = copy.deepcopy(self.template)
        e.update({key: True for key in BOOL_CHECKS})
        e.update(artifact_sha256="a" * 64, operator="unit-test-fixture",
                 network_loss_fallback_observed="source_paused", stop_fallback_observed="source_paused")
        e["iphone"]["ios_version"] = "fixture"
        e["sony"]["android_version"] = "fixture"
        e["source_apps"] = [{"name": "fixture", "av_sync_ms_samples": [100] * 30,
                            "switch_residual_ms_samples": [120] * 10}]
        (directory / "fixture.txt").write_text("Synthetic unit-test input, not a device recording.")
        e["evidence_files"] = ["fixture.txt"]
        return e

    def test_empty_template_never_passes(self):
        with tempfile.TemporaryDirectory() as temp:
            result = evaluate(self.template, Path(temp))
            self.assertEqual("NEEDS_DEVICE_EVIDENCE", result["decision"])
            self.assertFalse(result["main_integration_authorized"])

    def test_time_budget_ends_screening_even_without_measurements(self):
        with tempfile.TemporaryDirectory() as temp:
            self.template["effort_days"] = 2
            self.assertEqual("STOP", evaluate(self.template, Path(temp))["decision"])

    def test_phone_speaker_failure_stops_instead_of_accepting_other_passes(self):
        with tempfile.TemporaryDirectory() as temp:
            e = self.complete_fixture(Path(temp))
            e["normal_phone_speaker_silent"] = False
            self.assertEqual("STOP", evaluate(e, Path(temp))["decision"])

    def test_large_sync_and_old_audio_tail_stop(self):
        with tempfile.TemporaryDirectory() as temp:
            e = self.complete_fixture(Path(temp))
            e["source_apps"][0]["av_sync_ms_samples"] = [2100] * 30
            self.assertEqual("STOP", evaluate(e, Path(temp))["decision"])
            e["source_apps"][0]["av_sync_ms_samples"] = [100] * 30
            e["source_apps"][0]["switch_residual_ms_samples"] = [2100] * 10
            self.assertEqual("STOP", evaluate(e, Path(temp))["decision"])

    def test_too_few_samples_cannot_close_gate(self):
        with tempfile.TemporaryDirectory() as temp:
            e = self.complete_fixture(Path(temp))
            e["source_apps"][0]["av_sync_ms_samples"] = [100]
            self.assertEqual("NEEDS_DEVICE_EVIDENCE", evaluate(e, Path(temp))["decision"])

    def test_complete_observations_still_require_human_review(self):
        with tempfile.TemporaryDirectory() as temp:
            result = evaluate(self.complete_fixture(Path(temp)), Path(temp))
            self.assertEqual("READY_FOR_HUMAN_REVIEW", result["decision"])
            self.assertFalse(result["main_integration_authorized"])

    def test_boolean_strings_are_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            self.template["normal_phone_speaker_silent"] = "true"
            with self.assertRaises(ValueError):
                evaluate(self.template, Path(temp))

    def test_nan_and_boolean_measurements_are_rejected(self):
        for invalid in [float("nan"), float("inf"), True]:
            with tempfile.TemporaryDirectory() as temp:
                e = self.complete_fixture(Path(temp))
                e["source_apps"][0]["av_sync_ms_samples"] = [invalid] * 30
                with self.assertRaises(ValueError):
                    evaluate(e, Path(temp))

    def test_missing_observation_file_cannot_pass(self):
        with tempfile.TemporaryDirectory() as temp:
            e = self.complete_fixture(Path(temp))
            (Path(temp) / "fixture.txt").unlink()
            self.assertEqual("NEEDS_DEVICE_EVIDENCE", evaluate(e, Path(temp))["decision"])

    def test_evidence_path_traversal_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            e = self.complete_fixture(Path(temp))
            e["evidence_files"] = ["../../other-file"]
            with self.assertRaises(ValueError):
                evaluate(e, Path(temp))

    def test_percentile_uses_absolute_measured_offset(self):
        self.assertEqual(300, percentile95([-300] * 30))
        self.assertEqual(28, percentile95(list(range(30))))


if __name__ == "__main__":
    unittest.main()
