#!/usr/bin/env python3
"""Evaluate observed prototype acceptance; missing device evidence can never become PASS."""
import argparse
import json
import math
from pathlib import Path

BOOL_CHECKS = (
    "normal_phone_speaker_silent",
    "screen_remains_on_iphone",
    "manual_start_and_pairing_acceptable",
    "network_loss_fallback_acceptable",
    "stop_fallback_acceptable",
    "qq_music_manually_paused",
    "sony_music_not_automatically_resumed",
    "sony_screen_off_stable",
    "existing_ble_lyrics_controls_unchanged",
)
SYNC_TARGET_MS = 200
MIN_SYNC_SAMPLES = 30
MIN_SWITCH_SAMPLES = 10


def percentile95(samples):
    if not samples:
        return None
    values = sorted(abs(float(value)) for value in samples)
    return values[math.ceil(0.95 * len(values)) - 1]


def evaluate(evidence, base):
    if evidence.get("schema_version") != 1:
        raise ValueError("Unsupported evidence schema")
    checks = []

    def check(name, result, reason, **data):
        checks.append({"name": name, "result": result, "reason": reason, **data})

    for key in BOOL_CHECKS:
        value = evidence.get(key)
        if value is not None and type(value) is not bool:
            raise ValueError(key + " must be boolean or null")
        check(key, "NOT_RUN" if value is None else "PASS" if value else "FAIL",
              "operator_observation_required" if value is None else "operator_observation")
    for key in ("network_loss_fallback_observed", "stop_fallback_observed"):
        value = evidence.get(key)
        if value not in (None, "silent", "speaker", "source_paused", "other"):
            raise ValueError("Invalid fallback observation")
        check(key, "NOT_RUN" if value is None else "PASS", "observed_output_required")

    device_details = all(evidence.get(device, {}).get(field)
                         for device, field in (("iphone", "ios_version"), ("sony", "android_version")))
    check("device_versions", "PASS" if device_details else "NOT_RUN", "actual_versions_required")
    artifact = evidence.get("artifact_sha256")
    valid_artifact = isinstance(artifact, str) and len(artifact) == 64 and all(
        char in "0123456789abcdef" for char in artifact)
    check("artifact_identity", "PASS" if valid_artifact else "NOT_RUN", "final_apk_sha256_required")
    check("operator", "PASS" if evidence.get("operator") else "NOT_RUN", "human_observation_attribution")
    files = evidence.get("evidence_files", [])
    if not isinstance(files, list) or not all(isinstance(file, str) for file in files):
        raise ValueError("Evidence files must be relative paths")
    for file in files:
        path = (base / file).resolve()
        if not path.is_relative_to(base.resolve()):
            raise ValueError("Evidence file must remain inside the evidence directory")
    proof = bool(files) and all((base / file).is_file() and (base / file).stat().st_size > 0 for file in files)
    check("evidence_files", "PASS" if proof else "NOT_RUN", "observation_files_required")
    apps = evidence.get("source_apps", [])
    if not isinstance(apps, list) or len(apps) > 2:
        raise ValueError("Limit this screening to one or two target apps")
    if not apps:
        check("source_apps", "NOT_RUN", "real_target_app_required")
    for app in apps:
        name = app.get("name")
        if not isinstance(name, str) or not name.strip():
            raise ValueError("Each target app needs a name")
        for key, minimum in (("av_sync_ms_samples", MIN_SYNC_SAMPLES),
                             ("switch_residual_ms_samples", MIN_SWITCH_SAMPLES)):
            samples = app.get(key, [])
            if not isinstance(samples, list) or any(
                    type(v) not in (int, float) or not math.isfinite(v) for v in samples):
                raise ValueError("Samples must be finite measured numbers")
            p95 = percentile95(samples)
            enough = len(samples) >= minimum
            check(name + "/" + key,
                  "NOT_RUN" if not enough else "PASS" if p95 <= SYNC_TARGET_MS else "FAIL",
                  "observed_not_packet_latency", samples=len(samples), p95_ms=p95,
                  target_ms=SYNC_TARGET_MS, minimum_samples=minimum)
    failed = any(c["result"] == "FAIL" for c in checks)
    complete = all(c["result"] == "PASS" for c in checks)
    effort = evidence.get("effort_days", 0)
    if type(effort) not in (int, float) or not math.isfinite(effort) or effort < 0:
        raise ValueError("Invalid effort_days")
    decision = ("STOP" if failed or (not complete and effort >= 2) else
                "READY_FOR_HUMAN_REVIEW" if complete else "NEEDS_DEVICE_EVIDENCE")
    return {
        "schema_version": 1,
        "decision": decision,
        "checks": checks,
        "main_integration_authorized": False,
        "evidence_kind": "operator_observations_and_measurements",
        "note": "APK build, decode stats, and unit tests do not establish real routing or audio/video acceptance.",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("evidence", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        report = evaluate(json.loads(args.evidence.read_text()), args.evidence.parent)
    except (ValueError, OSError, TypeError, AttributeError) as error:
        parser.exit(2, "Invalid evidence: " + str(error) + "\n")
    text = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text)
    print(text, end="")
    return 0 if report["decision"] == "READY_FOR_HUMAN_REVIEW" else 1


if __name__ == "__main__":
    raise SystemExit(main())
