import importlib.util
import copy
import json
import plistlib
import signal
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import realtime_acceptance as acceptance
import realtime_capture as capture
import realtime_latency_report as report
import realtime_resource_capture as resources


class AcceptanceTests(unittest.TestCase):
    def sample_report(self, count=30, duration=100):
        return {"eventCount": 100, "samples": [
            {"handoffId": f"command-{i}", "triggerType": "IOS_NEXT", "classifications": ["COMPLETE", "LYRIC_CACHE_HIT"]}
            for i in range(count)], "t0Samples": [
            {"commandSeq": i, "commandType": "NEXT", "trigger": "manual", **{key: duration for key in acceptance.LIMITS}}
            for i in range(count)], "diagnostics": {}, "categories": {}}

    def test_over_limit_fails_and_report_generation_is_not_acceptance(self):
        result = acceptance.assess(self.sample_report(duration=301), None, "next")
        self.assertEqual(result["slo"]["status"], "FAIL")
        self.assertEqual(result["overall"], "FAIL")
        with tempfile.TemporaryDirectory() as directory:
            self.assertEqual(report.main(["--output-dir", directory]), 0)
            self.assertNotEqual(acceptance.assess({}, None, "next")["overall"], "PASS")

    def test_p95_is_not_first_percentile(self):
        data = self.sample_report()
        for index, sample in enumerate(data["t0Samples"]):
            sample["t0ToTrackPublishMs"] = 50 if index < 25 else 500
        self.assertEqual(acceptance.assess(data, None, "next")["slo"]["status"], "FAIL")

    def test_fast_missing_send_receive_and_execution_are_distinct(self):
        ops = [("r", i, "NEXT" if i % 2 else "PREVIOUS") for i in range(1, 101)]
        events = []
        for i in range(1, 101):
            cmd = "NEXT" if i % 2 else "PREVIOUS"
            events.append({"side": "ios", "stage": "commandIntent", "commandSeq": i, "commandType": cmd})
            for side, stage, omitted in (("ios", "commandWriteStart", 1),
                                         ("sony", "commandReceived", 2),
                                         ("sony", "mediaControlDispatchStart", 3)):
                if i != omitted:
                    events.append({"side": side, "stage": stage, "commandSeq": i, "commandType": cmd})
        result = acceptance.assess(self.sample_report(), None, "fast", events=events, operations=ops)
        missing = result["trace"]["missing"]
        self.assertIn("command_1_send_missing", missing)
        self.assertIn("command_2_receive_missing", missing)
        self.assertIn("command_3_execute_missing", missing)

    def test_clock_calibration_is_timed_never_retroactive_and_fails_closed(self):
        def pong(mono, offset, confident, samples=3):
            return f"[ClockSync] pong seq=1 offsetMs={offset} samples={samples} confident={confident} monoMs={mono}\n"
        windows = acceptance.clock_windows(pong(100, 10, "false", 1) + pong(200, 10, "true") +
                                           pong(400, 20, "false") + pong(500, 20, "true"))
        self.assertIsNone(acceptance.clock_for_pair(150, 140, windows))
        self.assertEqual(acceptance.clock_for_pair(250, 230, windows), 240)
        self.assertIsNone(acceptance.clock_for_pair(450, 430, windows))
        self.assertEqual(acceptance.clock_for_pair(550, 510, windows), 530)
        self.assertIsNone(acceptance.clock_for_pair(150_500, 150_450, windows))
        self.assertEqual(acceptance.clock_windows("[ClockSync] pong offsetMs=10 samples=3 confident=true\n"), [])
        self.assertEqual(acceptance.clock_windows(pong(100, 10, "true") + "[ClockSync] reset reason=reconnect\n"), [])
        reset = acceptance.clock_windows(pong(100, 10, "true") + "[ClockSync] reset reason=reconnect monoMs=200\n" + pong(300, 10, "true"))
        self.assertIsNone(acceptance.clock_for_pair(250, 230, reset))
        self.assertEqual(acceptance.clock_for_pair(350, 330, reset), 340)
        jump = acceptance.clock_windows(pong(100, 10, "true") + pong(200, 500, "true") + pong(300, 501, "true"))
        self.assertIsNone(acceptance.clock_for_pair(150, 140, jump))
        self.assertIsNone(acceptance.clock_for_pair(250, -260, jump))
        self.assertEqual(acceptance.clock_for_pair(350, -170, jump), 331)

    def test_fast_write_timeout_is_failure_even_with_late_success_callback(self):
        ops = [("r", 1, "NEXT")]
        common = {"side": "ios", "commandSeq": 1, "commandType": "NEXT"}
        events = [{**common, "stage": "commandIntent"}, {**common, "stage": "commandWriteStart"},
                  {**common, "stage": "commandWriteTimeout", "result": "timeout"},
                  {**common, "stage": "commandWriteCallback", "result": "success"},
                  {**common, "side": "sony", "stage": "commandReceived"},
                  {**common, "side": "sony", "stage": "mediaControlDispatchStart"}]
        result = acceptance.assess(self.sample_report(1), None, "fast", events=events, operations=ops)
        self.assertIn("command_1_write_anomaly", result["trace"]["failures"])

    def test_missing_and_failed_samples_are_not_complete(self):
        data = self.sample_report(30)
        data["samples"][0]["classifications"].append("FAILED")
        result = acceptance.assess(data, None, "next")
        self.assertEqual(result["trace"]["details"]["valid"], 29)
        self.assertEqual(result["trace"]["status"], "FAIL")

    def test_failed_attempt_after_thirty_successes_still_fails(self):
        data = self.sample_report(31)
        data["samples"][-1]["classifications"].append("FAILED")
        result = acceptance.assess(data, None, "next")
        self.assertEqual(result["trace"]["details"]["valid"], 30)
        self.assertEqual(result["overall"], "FAIL")

    def test_cold_group_over_limit_is_not_hidden_by_warm_group(self):
        data = self.sample_report(100)
        for i, sample in enumerate(data["samples"]):
            sample["classifications"].extend(["RUNTIME_CACHE_MISS" if i < 4 else "RUNTIME_CACHE_HIT"])
            if i < 4:
                data["t0Samples"][i]["t0ToTrackPublishMs"] = 500
        result = acceptance.assess(data, None, "next")
        self.assertEqual(result["slo"]["details"]["t0ToTrackPublishMs"]["p95"], 100)
        self.assertIn("t0ToTrackPublishMs_cold_exceeds_300ms", result["slo"]["failures"])

    def test_intro_wait_does_not_fail_first_word_slo(self):
        data = self.sample_report(30)
        events = []
        for sample, t0 in zip(data["samples"], data["t0Samples"]):
            sample["classifications"].append("INTRO_WAIT")
            t0["t0ToCurrentWordMs"] = 10000
            events.append({"side": "sony", "handoffId": sample["handoffId"], "stage": "currentWordNotEligible", "monoMs": 1, "reason": "INTRO_WAIT"})
        result = acceptance.assess(data, None, "next", events=events)
        self.assertEqual(result["slo"]["details"]["wordEligibility"]["intro_wait"], 30)
        self.assertNotIn("t0ToCurrentWordMs_exceeds_500ms", result["slo"]["failures"])

    def test_late_intro_wait_does_not_exclude_first_eligible_word(self):
        data = self.sample_report(30)
        events = []
        for sample, t0 in zip(data["samples"], data["t0Samples"]):
            sample["classifications"].extend(["INTRO_WAIT", "WORD_TIMING_AVAILABLE"])
            t0["t0ToCurrentWordMs"] = 900
            events.extend([{"side": "sony", "handoffId": sample["handoffId"], "stage": "currentWordEligible", "monoMs": 1},
                           {"side": "sony", "handoffId": sample["handoffId"], "stage": "currentWordNotEligible", "monoMs": 2, "reason": "INTRO_WAIT"}])
        result = acceptance.assess(data, None, "next", events=events)
        self.assertEqual(result["slo"]["details"]["wordEligibility"]["immediately_eligible"], 30)
        self.assertIn("t0ToCurrentWordMs_exceeds_500ms", result["slo"]["failures"])

    def test_duplicate_sample_identity_cannot_reach_thirty(self):
        data = self.sample_report(30)
        data["samples"][0]["handoffId"] = data["samples"][1]["handoffId"]
        result = acceptance.assess(data, None, "next")
        self.assertEqual(result["trace"]["details"]["valid"], 29)
        self.assertIn("duplicate_sample_identity", result["trace"]["missing"])

    def test_natural_external_control_is_not_relabelled(self):
        data = self.sample_report(1)
        events = [{"side": "ios", "stage": "commandIntent", "commandType": "NEXT"}]
        result = acceptance.assess(data, None, "natural", events=events)
        self.assertIn("natural_window_contains_external_control", result["trace"]["missing"])
        self.assertEqual(data["samples"][0]["triggerType"], "IOS_NEXT")

    def test_correctness_and_stability_are_failures(self):
        data = self.sample_report()
        data["diagnostics"]["duplicate_control"] = 1
        result = acceptance.assess(data, None, "next", system_logs="ANR in com.example\nL2CAP write failed")
        self.assertEqual(result["correctness"]["status"], "FAIL")
        self.assertEqual(result["stability"]["status"], "FAIL")

    def test_stability_detects_target_native_death_without_unrelated_process_noise(self):
        unrelated = acceptance.scan_stability(["Process com.unrelated.app has died", "ANR in com.other.app"])
        self.assertEqual(unrelated, {})
        evidence = acceptance.scan_stability(["Fatal signal 6 (SIGABRT)",
            "pid: 10, tid: 11, name: main >>> com.example.playeragent <<<",
            "Process com.android.bluetooth (pid 9) has died", "hci_timeout_abort",
            "Process com.example.playeragent (pid 10) has died"])
        self.assertEqual(evidence["crash"]["count"], 1)
        self.assertEqual(evidence["bluetooth_death"]["count"], 1)
        self.assertEqual(evidence["hci_timeout"]["count"], 1)
        self.assertEqual(evidence["app_process_death"]["count"], 1)
        repeated = acceptance.scan_stability(iter(["hci_timeout_abort\n"] * 1000))
        self.assertEqual(repeated["hci_timeout"]["count"], 1000)
        self.assertEqual(len(repeated["hci_timeout"]["examples"]), 20)

    def test_unrelated_fatal_is_not_attributed_to_interleaved_target_log(self):
        evidence = acceptance.scan_stability([
            "FATAL EXCEPTION: main",
            "I PlayerAgent: com.example.playeragent normal playback update",
            "Process: com.other.app, PID: 42",
            "I PlayerAgent: com.example.playeragent normal reconnect update",
        ])
        self.assertNotIn("crash", evidence)

    def test_large_file_hash_and_window_comparison_stream_in_chunks(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "large"
            target = Path(directory) / "window"
            with source.open("wb") as stream:
                stream.write(b"x" * (3 * 1024 * 1024) + b"\n")
            with target.open("wb") as stream:
                stream.write(b"x" * (1024 * 1024))
            with mock.patch.object(Path, "read_bytes", side_effect=AssertionError("unbounded read")):
                self.assertEqual(len(acceptance.sha(source)), 64)
                self.assertTrue(acceptance.stream_window_matches(source, target, 1024 * 1024, 2 * 1024 * 1024))

    def test_system_log_tail_after_run_end_is_scanned_without_old_faults(self):
        with tempfile.TemporaryDirectory() as directory:
            source, window = Path(directory) / "full", Path(directory) / "run"
            old = "sonyMusic EXC_CRASH from previous run\n"
            active = "healthy during run\n"
            late = "sonyMusic EXC_CRASH during collector tail\n"
            source.write_text(old + active + late)
            observed_start, run_end = len(old.encode()), len((old + active).encode())
            metadata = capture.copy_system_window(source, window, observed_start, run_end, source.stat().st_size)
            self.assertEqual(metadata["runEndOffset"], run_end)
            self.assertEqual(metadata["observedEndOffset"], source.stat().st_size)
            self.assertEqual(window.read_text(), active + late)
            with window.open() as stream:
                findings = acceptance.scan_stability(stream)
            self.assertEqual(findings["ios_termination"]["count"], 1)
            self.assertIn("collector tail", findings["ios_termination"]["examples"][0])

    def test_duration_and_installation_evidence_required(self):
        cap = {"startedMonotonic": 0, "endedMonotonic": 7300, "runStartedMonotonic": 10,
               "observationStartedMonotonic": 10, "runEndedMonotonic": 7209, "snapshots": []}
        result = acceptance.assess({}, cap, "natural")
        self.assertIn("natural_window_under_7200_seconds", result["capture"]["missing"])
        self.assertIn("ios_installed_build_unverified", result["capture"]["missing"])
        cap["runEndedMonotonic"] = 7210
        self.assertNotIn("natural_window_under_7200_seconds", acceptance.assess({}, cap, "natural")["capture"]["missing"])

    def test_complete_evidence_bundle_passes_and_missing_final_snapshot_does_not(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def write(name, data):
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(data if isinstance(data, bytes) else data.encode())
                return str(path)
            def h(path):
                return acceptance.sha(Path(path))
            binary = write("Example.app/app", "signed binary")
            plist = write("Example.app/Info.plist", plistlib.dumps({"CFBundleVersion": "1", "CFBundleIdentifier": "test.app"}))
            codesign = write("codesign.txt", "CDHash=abc123")
            receipt = write("install.json", json.dumps({"info": {"commandType": "devicectl.device.install.app", "outcome": "success",
                "arguments": ["devicectl", "device", "install", "app", "--device", "iphone", str(root / "Example.app")]}, "result": {"deviceIdentifier": "iphone"}}))
            apps = write("apps.json", json.dumps({"info": {"commandType": "devicectl.device.info.apps", "outcome": "success", "arguments": ["--device", "iphone", "--bundle-id", "test.app"]},
                "result": {"deviceIdentifier": "iphone", "matchingBundleIdentifier": "test.app", "apps": [{"bundleIdentifier": "test.app", "bundleVersion": "1"}]}}))
            apk = write("source.apk", "apk bytes")
            pulled = write("installed.apk", "apk bytes")
            android_install = write("adb-install.txt", "Success\n")
            package = write("package.txt", "Package [test.sony]\n versionCode=1 minSdk=24\n")
            diff = write("source.diff", "diff")
            hashes = write("hashes.json", json.dumps({"files": {str(Path(__file__).resolve()): h(__file__)}}))
            buildlog = write("build.log", "build completed")
            installed = {"source": {"head": "head", "dirtyDiffFile": diff, "dirtyDiffSha256": h(diff),
                "sourceHashesFile": hashes, "sourceHashesSha256": h(hashes), "buildLogFile": buildlog, "buildLogSha256": h(buildlog)},
                "ios": {"artifact": binary, "sha256": h(binary), "appPath": str(root / "Example.app"),
                    "infoPlist": plist, "infoPlistSha256": h(plist), "version": "1", "bundleIdentifier": "test.app",
                    "deviceId": "iphone", "installReceipt": receipt, "installReceiptSha256": h(receipt),
                    "appsReceipt": apps, "appsReceiptSha256": h(apps), "codesignEvidence": codesign,
                    "codesignEvidenceSha256": h(codesign), "cdHash": "abc123"},
                "sony": {"artifact": apk, "sha256": h(apk), "installedApk": pulled, "installedApkSha256": h(pulled),
                    "version": "1", "deviceId": "sony", "packageName": "test.sony", "installReceipt": android_install,
                    "installReceiptSha256": h(android_install), "packageInfo": package, "packageInfoSha256": h(package)}}
            # Build a genuine, parseable trace; the gate independently
            # re-derives every report field from these exact run logs.
            sony_lines, ios_lines = [], []
            sony_stages = ["commandReceived", "commandValidated", "mediaControlDispatchStart", "mediaControlDispatchEnd",
                "notificationMetadataObserved", "playbackReadStart", "mediaSessionMetadataObserved", "trackIdentityCandidate",
                "trackIdentityAccepted", "mediaGenerationCreated", "playbackReady", "playbackEnqueued", "playbackDequeued",
                "playbackNotifyStart", "playbackNotifyCallback", "lyricReady", "currentWordEligible"]
            ios_stages = ["commandIntent", "commandEnqueued", "commandWriteStart", "commandWriteCallback", "playbackNotifyReceived",
                "playbackDecodeEnd", "trackIdentityAccepted", "playbackStatePublished", "nowPlayingStateConsumed",
                "lyricCurrentLinePublished", "currentWordPublished", "previewPublished", "hqPublished", "fullLyricsPublished"]
            for index in range(60):
                base, track, handoff = 1000 + index * 1000, f"{index:012x}", f"command-{index}"
                state = "Hit" if index < 30 else "Miss"
                ios_lines.append((base - 1, f"2026-11-07 [RealtimeV4] command runId=run index={index + 1} cmd=NEXT trackId={track}\n"))
                def line(side, stage, mono):
                    result = "changed" if side == "ios" and stage == "trackIdentityAccepted" else (
                        "ready" if stage == "lyricReady" else "published" if stage in {"lyricCurrentLinePublished", "currentWordPublished", "previewPublished", "hqPublished", "fullLyricsPublished"} else "success")
                    return (f"[RealtimeTrace] side={side} stage={stage} monoMs={mono} commandSeq={index} commandType=NEXT "
                            f"trackId={track} generation={index + 1} handoffId={handoff} triggerType=IOS_NEXT "
                            f"wordTimingStatus=AVAILABLE result={result}\n")
                for position, stage in enumerate(sony_stages):
                    sony_lines.append((base + 10 + position, "11-07 12:00:00.000  123  124 I Player: " + line("sony", stage, base + 10 + position)))
                for position, stage in enumerate(("runtimeCache" + state, "lyricParsedCache" + state, "albumArtCache" + state)):
                    sony_lines.append((base + 27 + position, "11-07 12:00:00.000  123  124 I Player: " + line("sony", stage, base + 27 + position)))
                for position, stage in enumerate(ios_stages):
                    # Command starts before Sony, publication after Sony eligibility.
                    delta = position if position < 4 else 30 + position
                    ios_lines.append((base + delta, "2026-11-07 " + line("ios", stage, base + delta)))
            sony_text = "".join(item[1] for item in sorted(sony_lines))
            ios_all_lines = "".join(item[1] for item in sorted(ios_lines))
            ios_traces = "".join(item[1] for item in sorted(ios_lines) if "[RealtimeTrace]" in item[1])
            clock_line = "[ClockSync] pong seq=1 bestRttMs=1 offsetMs=0 jitterMs=1 samples=3 confident=true monoMs=100\n"
            schedule = "2026-11-07 [RealtimeV4] scheduled runId=run runs=60 mode=next fastSwitch=false\n"
            ios_text = schedule + "2026-11-07 [RealtimeV4] start runId=run\n" + clock_line + ios_all_lines + "2026-11-07 [RealtimeV4] end runId=run\n"
            sony = write("sony_full.log", "11-07 12:00:00.000  1  2 I RealtimeV4: host_measurement_start runId=host\n" + sony_text)
            sony_run = write("sony_run.log", sony_text)
            sony_trace = write("sony_trace_input.log", sony_text)
            ios_trace = write("ios_trace_input.log", clock_line + ios_traces)
            ios = write("ios_captured.log", ios_text)
            ios_run = write("ios_run.log", ios_text)
            parsed, malformed = report.load_events([Path(sony_trace), Path(ios_trace)])
            data = report.analyze(parsed, malformed=malformed, clock_trusted=True, sony_to_ios_offset_ms=0)
            data.update(devices={"ios": "iphone", "sony": "sony"}, basicInfo={"commit": "head"},
                        scenario={"mode": "next", "runs": 60})
            events = [event.as_dict() for event in parsed]
            monitor = write("collector_monitor.log", "10 100\n20 120\n")
            before = write("before.log", "same")
            after = write("after.log", "same")
            precheck = write("ios_ble_precheck_ios.log", schedule)
            context = write("launch_context.json", json.dumps({"baselineIndex": 1, "baselineRunId": "old",
                "precheckRunIds": ["run"], "expectedRunId": "run", "precheckSha256": h(precheck)}))
            system = write("system.log", "healthy\n")
            system_run = write("ios_system_run.log", "healthy\n")
            system_tool = write("system-tool", "collector")
            device_details = write("device-details.json", json.dumps({"info": {"outcome": "success", "commandType": "devicectl.device.info.details"},
                "result": {"identifier": "iphone", "hardwareProperties": {"udid": "actual-udid"}}}))
            snapshot = {"index": 2, "startMonotonic": 21, "endMonotonic": 21.1, "runId": "run", "final": True, "ok": True, "deltaBytes": 4,
                        "files": {"before": {"file": before, "sha256": h(before)}, "after": {"file": after, "sha256": h(after)}}}
            baseline = {"index": 1, "startMonotonic": 1, "endMonotonic": 2, "runId": "old", "ok": True}
            cap = {"startedMonotonic": 1, "endedMonotonic": 22, "runStartedMonotonic": 10, "runEndedMonotonic": 20,
                   "hostRunId": "host", "iosRunIds": ["run"], "snapshots": [baseline, snapshot], "errors": [], "launchContextSha256": h(context),
                   "sonyCollector": {"aliveAtStop": True, "exitCode": 143, "sha256": h(sony), "runSha256": h(sony_run), "monitorSha256": h(monitor)},
                   "iosCollector": {"aliveAtStop": True, "exitCode": 0, "sha256": h(ios), "runSha256": h(ios_run)},
                   "iosSystemCollector": {"aliveAtStop": True, "exitCode": 143, "file": system, "sha256": h(system),
                                          "runFile": "ios_system_run.log", "runSha256": h(system_run), "startOffset": 0, "endOffset": 8,
                                          "observedStartOffset": 0, "runEndOffset": 8, "observedEndOffset": 8, "boundaryComplete": True,
                                          "udid": "actual-udid", "deviceDetailsFile": device_details, "deviceDetailsSha256": h(device_details),
                                          "argv": [system_tool, "-u", "actual-udid"], "executableSha256": h(system_tool),
                                          "startedMonotonic": 1, "endedMonotonic": 22}}
            result = acceptance.assess(data, cap, "next", installed, root, "healthy", events)
            self.assertEqual(result["overall"], "PASS", result)
            missing_trace = ios_text.replace("runs=60", "runs=61").replace(
                "2026-11-07 [RealtimeV4] end runId=run\n",
                "2026-11-07 [RealtimeV4] command runId=run index=61 cmd=NEXT trackId=missing\n"
                "2026-11-07 [RealtimeV4] end runId=run\n")
            Path(ios).write_text(missing_trace)
            Path(ios_run).write_text(missing_trace)
            Path(precheck).write_text(schedule.replace("runs=60", "runs=61"))
            context_data = json.loads(Path(context).read_text())
            context_data["precheckSha256"] = h(precheck)
            Path(context).write_text(json.dumps(context_data))
            cap["launchContextSha256"] = h(context)
            cap["iosCollector"]["sha256"], cap["iosCollector"]["runSha256"] = h(ios), h(ios_run)
            data["scenario"]["runs"] = 61
            result = acceptance.assess(data, cap, "next", installed, root, "healthy", events)
            self.assertIn("operation_intents_missing_or_ambiguous", result["trace"]["missing"])
            self.assertIn("operation_samples_missing_or_ambiguous", result["trace"]["missing"])
            self.assertEqual(result["overall"], "INCOMPLETE")
            Path(ios).write_text(ios_text)
            Path(ios_run).write_text(ios_text)
            Path(precheck).write_text(schedule)
            context_data["precheckSha256"] = h(precheck)
            Path(context).write_text(json.dumps(context_data))
            cap["launchContextSha256"] = h(context)
            cap["iosCollector"]["sha256"], cap["iosCollector"]["runSha256"] = h(ios), h(ios_run)
            data["scenario"]["runs"] = 60
            altered = copy.deepcopy(data)
            altered["samples"] = altered["samples"][:-1]
            result = acceptance.assess(altered, cap, "next", installed, root, "healthy", events)
            self.assertIn("report_trace_source_mismatch", result["capture"]["missing"])
            altered_events = copy.deepcopy(events)
            altered_events[0]["monoMs"] += 1
            result = acceptance.assess(data, cap, "next", installed, root, "healthy", altered_events)
            self.assertIn("report_trace_source_mismatch", result["capture"]["missing"])
            first_failed = ios_text.replace("[RealtimeV4] end runId=run", "[RealtimeV4] abort runId=run reason=left_foreground")
            Path(ios).write_text(first_failed)
            Path(ios_run).write_text(first_failed)
            cap["iosCollector"]["runSha256"] = h(ios_run)
            with Path(ios).open("a") as stream:
                stream.write("2026-11-07 [RealtimeV4] scheduled runId=second runs=30 mode=next fastSwitch=false\n")
                stream.write("2026-11-07 [RealtimeV4] start runId=second\n")
                stream.write("2026-11-07 [RealtimeV4] end runId=second completed=30\n")
            cap["iosCollector"]["sha256"] = h(ios)
            cap["iosRunIds"] = ["run", "second"]
            result = acceptance.assess(data, cap, "next", installed, root, "healthy", events)
            self.assertIn("ios_run_restarted", result["capture"]["failures"])
            self.assertIn("ios_run_start_or_abort", result["capture"]["missing"])
            self.assertEqual(result["overall"], "FAIL")
            Path(ios).write_text(ios_text)
            Path(ios_run).write_text(ios_text)
            cap["iosCollector"]["sha256"] = h(ios)
            cap["iosCollector"]["runSha256"] = h(ios_run)
            cap["iosRunIds"] = ["run"]
            snapshot["startMonotonic"] = 19
            result = acceptance.assess(data, cap, "next", installed, root, "healthy", events)
            self.assertIn("final_snapshot_after_run_missing", result["capture"]["missing"])
            self.assertEqual(result["overall"], "INCOMPLETE")
            snapshot["startMonotonic"] = 21
            installed["sony"]["deviceId"] = "other-sony"
            result = acceptance.assess(data, cap, "next", installed, root, "healthy", events)
            self.assertIn("sony_installed_build_unverified", result["capture"]["missing"])
            installed["sony"]["deviceId"] = "sony"
            cap["iosSystemCollector"]["udid"] = "other-iphone"
            result = acceptance.assess(data, cap, "next", installed, root, "healthy", events)
            self.assertEqual(result["stability"]["status"], "INCOMPLETE")
            cap["iosSystemCollector"]["udid"] = "actual-udid"
            Path(after).write_text("tampered")
            result = acceptance.assess(data, cap, "next", installed, root, "healthy", events)
            self.assertIn("snapshot_hash_mismatch", result["capture"]["missing"])

    def test_conflicting_handoff_cannot_fill_media_stage(self):
        sample = {"handoffId": "command-1", "classifications": []}
        events = [{"handoffId": "command-1", "side": "sony", "stage": "currentWordEligible",
                   "trackId": "same", "generation": 2, "monoMs": 100},
                  {"handoffId": "command-2", "side": "ios", "stage": "currentWordPublished",
                   "trackId": "same", "generation": 2, "monoMs": 110}]
        result = acceptance.qualified_cross_device_samples([sample], events, [{"start": 0, "end": 1000, "offset": 0, "valid": True}])
        self.assertEqual(result["wordEligibleToPublishMs"]["count"], 0)

    def test_snapshot_byte_continuity_and_roll(self):
        self.assertEqual(capture.continuity(b"abc", b"abcdef", None, b"abcdefg"),
                         (True, b"defg", "current_prefix"))
        self.assertEqual(capture.continuity(b"abc", b"new", b"abcdef", b"new+"),
                         (True, b"defnew+", "rolled_prefix"))
        self.assertFalse(capture.continuity(b"abc", b"new", b"lost", b"new+")[0])
        self.assertFalse(capture.continuity(b"abc", b"abcnew", b"abcold", b"abcnew+")[0])
        self.assertFalse(capture.continuity(b"abc", b"", None, b"abc")[0])

    def test_capture_resets_expected_run_and_takes_final_snapshot_after_stop(self):
        with tempfile.TemporaryDirectory() as directory:
            handlers, clock, epoch, sleeps = {}, [0.0], ["old"], [0]
            def register(number, handler):
                handlers[number] = handler
            def monotonic():
                clock[0] += 0.1
                return clock[0]
            def sleep(_duration):
                sleeps[0] += 1
                clock[0] += 5
                if sleeps[0] == 1:
                    epoch[0] = "new"
                else:
                    handlers[signal.SIGTERM](signal.SIGTERM, None)
            def copy(command, **_kwargs):
                path = Path(command[command.index("--destination") + 1])
                source = command[command.index("--source") + 1]
                if source.endswith("ios_ble.old.log"):
                    return SimpleNamespace(returncode=1, stdout=b"", stderr=b"not present")
                path.write_text(f"[RealtimeV4] scheduled runId={epoch[0]} runs=30 mode=next\n")
                return SimpleNamespace(returncode=0, stdout=b"ok", stderr=b"")
            with mock.patch.object(capture.signal, "signal", side_effect=register), \
                 mock.patch.object(capture.time, "monotonic", side_effect=monotonic), \
                 mock.patch.object(capture.time, "sleep", side_effect=sleep), \
                 mock.patch.object(capture.subprocess, "run", side_effect=copy):
                self.assertEqual(capture.capture(Path(directory), "iphone", "bundle", 2), 0)
            manifest = json.loads((Path(directory) / "ios_capture_manifest.json").read_text())
            self.assertEqual([s["runId"] for s in manifest["snapshots"]], ["old", "new", "new"])
            self.assertEqual(manifest["snapshots"][1]["continuity"], "run_boundary_reset")
            self.assertTrue(manifest["snapshots"][-1]["final"])

    def test_resource_sampler_preserves_raw_outputs_and_failures(self):
        with tempfile.TemporaryDirectory() as directory:
            clock = [0.0]
            def monotonic():
                clock[0] += 0.1
                return clock[0]
            def sleep(_duration):
                clock[0] += 100
            def execute(command, **_kwargs):
                return SimpleNamespace(returncode=1 if "meminfo" in command else 0,
                                       stdout=b"raw output", stderr=b"device error")
            out = Path(directory) / "resource"
            with mock.patch.object(resources.signal, "signal"), \
                 mock.patch.object(resources.time, "monotonic", side_effect=monotonic), \
                 mock.patch.object(resources.time, "sleep", side_effect=sleep), \
                 mock.patch.object(resources.subprocess, "run", side_effect=execute):
                self.assertEqual(resources.collect(out, "adb", "device", 60, 30), 1)
            manifest = json.loads((out / "samples.json").read_text())
            memory = manifest["samples"][0]["commands"]["memory"]
            self.assertEqual(memory["returncode"], 1)
            self.assertEqual(acceptance.sha(out / memory["stderr"]), memory["stderrSha256"])
            self.assertEqual(manifest["ios"], "NOT_CAPTURED")

    def test_t0_media_exact_generation_and_untrusted_natural(self):
        def e(side, stage, ms, **kw):
            return report.Event(side, stage, ms, **kw)
        events = [e("ios", "commandIntent", 100, command_seq=1, command_type="NEXT", source_line=1),
                  e("ios", "trackIdentityAccepted", 200, track_id="song", generation=2, result="changed", source_line=2),
                  e("ios", "currentLyricPublished", 260, track_id="song", generation=2, source_line=3),
                  e("ios", "lyricCurrentLinePublished", 270, track_id="song", generation=2, result="empty", source_line=4),
                  e("ios", "lyricCurrentLinePublished", 400, track_id="song", generation=2, result="published", source_line=5),
                  e("ios", "previewPublished", 280, track_id="song", generation=1, source_line=6)]
        measured = report.analyze(events)
        self.assertEqual(measured["metrics"]["trackToCurrentLyricMs"]["p50"], 60)
        self.assertEqual(measured["metrics"]["t0ToCurrentLyricMs"]["p50"], 300)
        self.assertIsNone(measured["t0Samples"][0]["t0ToPreviewArtMs"])
        self.assertEqual(measured["metrics"]["trackToPreviewArtMs"]["count"], 0)
        self.assertEqual(measured["metrics"]["trackToPreviewArtMs"]["missing"], 1)
        natural = report.analyze([e("sony", "trackIdentityAccepted", 50, track_id="s", generation=1, source_line=5),
                                  e("ios", "trackIdentityAccepted", 80, track_id="s", generation=1, result="changed", source_line=6)])
        self.assertEqual(natural["metrics"]["t0ToTrackPublishMs"]["count"], 0)

    def test_first_command_missing_identity_does_not_shift(self):
        events = [report.Event("ios", "commandIntent", 10, command_seq=1, command_type="NEXT", source_line=1),
                  report.Event("ios", "commandIntent", 20, command_seq=2, command_type="NEXT", source_line=2),
                  report.Event("ios", "trackIdentityAccepted", 30, track_id="s", generation=1, result="changed", source_line=3)]
        samples = report.analyze(events)["t0Samples"]
        self.assertEqual([s["commandSeq"] for s in samples], [2])

if __name__ == "__main__":
    unittest.main()
