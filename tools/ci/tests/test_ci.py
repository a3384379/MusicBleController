import importlib.util
import copy
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[3]
ANDROID = ROOT / "tools/ci/android_device.py"
IOS = ROOT / "tools/ci/ios_simulator.py"
spec = importlib.util.spec_from_file_location("android_device", ANDROID)
android = importlib.util.module_from_spec(spec)
spec.loader.exec_module(android)
HANG = "The test runner hung before establishing connection."
FAILURE_NAME = "LyricsReadingUITests (123) encountered an error"
SUMMARY = {"result": "Failed", "passedTests": 0, "skippedTests": 0, "failedTests": 1, "expectedFailures": 0,
           "totalTestCount": 1, "devicesAndConfigurations": [{"passedTests": 0, "skippedTests": 0, "failedTests": 1, "expectedFailures": 0}],
           "testFailures": [{"testName": FAILURE_NAME, "testIdentifierString": FAILURE_NAME, "targetName": "LyricsReadingUITests", "failureText": HANG}]}
TREE = {"testNodes": [{"name": "System Failures", "nodeType": "Test Suite", "children": [
    {"name": FAILURE_NAME, "nodeIdentifier": FAILURE_NAME, "nodeType": "Test Case", "result": "Failed",
     "children": [{"name": HANG, "nodeType": "Failure Message"}]}]}]}


class CoverageTest(unittest.TestCase):
    def test_multiple_or_unauthorized_devices_fail_before_any_install(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for module in ("ControllerApp", "PlayerAgentApp"):
                shutil.copytree(ROOT / module / "src/androidTest", root / module / "src/androidTest")
            adb = root / "fake adb"
            adb.write_text('#!/bin/sh\nif [ "$*" = "devices -l" ]; then printf "List of devices attached\\nSONY device\\nemulator-5580 device\\nemulator-5554 unauthorized\\n"; fi\n')
            adb.chmod(0o755)
            with mock.patch.object(android, "ROOT", root), mock.patch.dict(os.environ, {"ADB": str(adb), "ANDROID_SERIAL": ""}), mock.patch.object(android, "run") as run:
                for extra in ([], ["--serial", "emulator-5554"], ["--serial", "missing"]):
                    with mock.patch.object(sys, "argv", ["android_device.py", "sony-denied", "--artifacts", str(root / "out"), *extra]):
                        with self.assertRaises(ValueError):
                            android.main()
                run.assert_not_called()
                self.assertFalse((root / "out").exists())

    def test_permission_groups_reject_physical_device_before_install_or_permission_change(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for module in ("ControllerApp", "PlayerAgentApp"):
                shutil.copytree(ROOT / module / "src/androidTest", root / module / "src/androidTest")
            adb = root / "fake adb"
            adb.write_text('#!/bin/sh\ncase "$*" in "devices -l") printf "List of devices attached\\nREAL-DEVICE device\\n";; "-s REAL-DEVICE shell getprop ro.kernel.qemu") echo 0;; esac\n')
            adb.chmod(0o755)
            with mock.patch.object(android, "ROOT", root), mock.patch.dict(os.environ, {"ADB": str(adb), "ANDROID_SERIAL": "REAL-DEVICE"}), mock.patch.object(sys, "argv", ["android_device.py", "sony-denied", "--artifacts", str(root / "out")]), mock.patch.object(android, "run") as run:
                with self.assertRaisesRegex(RuntimeError, "isolated Android emulator"):
                    android.main()
                run.assert_not_called()

    def test_complete_disjoint_source_inventory_and_permission_partition(self):
        inventory = android.collect()
        self.assertTrue(all(inventory.values()))
        self.assertEqual(2, len(inventory["sony-denied"]))
        self.assertEqual(1, len(inventory["sony-granted"]))
        saved = android.GROUPS["sony-granted"]
        try:
            android.GROUPS["sony-granted"] = ("PlayerAgentApp", [])
            with self.assertRaises(ValueError):
                android.collect()
        finally:
            android.GROUPS["sony-granted"] = saved

    def test_xml_rejects_missing_duplicate_skipped_and_failure(self):
        expected = {("example.Test", "one")}
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "TEST.xml"
            for body, should_pass in [
                ('<testcase classname="example.Test" name="one"/>', True),
                ('', False),
                ('<testcase classname="example.Test" name="one"/><testcase classname="example.Test" name="one"/>', False),
                ('<testcase classname="example.Test" name="one"><skipped/></testcase>', False),
                ('<testcase classname="example.Test" name="one"><failure/></testcase>', False),
            ]:
                path.write_text('<testsuite>' + body + '</testsuite>')
                if should_pass:
                    android.verify_xml(Path(temp), expected)
                else:
                    with self.assertRaises(ValueError):
                        android.verify_xml(Path(temp), expected)

    def test_android_business_failure_is_not_retried_and_evidence_is_saved(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for module in ("ControllerApp", "PlayerAgentApp"):
                shutil.copytree(ROOT / module / "src/androidTest", root / module / "src/androidTest")
            trace = root / "trace"
            gradle = root / "fake gradle"
            gradle.write_text('''printf 'gradle ANDROID_SERIAL=%s %s\\n' "$ANDROID_SERIAL" "$*" >> "$TRACE"
if [[ "$*" == *connectedDebugAndroidTest* ]]; then
  mkdir -p "$FAKE_ROOT/PlayerAgentApp/build/outputs/androidTest-results/connected"
  echo '<testsuite><testcase classname="failure" name="first"><failure/></testcase></testsuite>' > "$FAKE_ROOT/PlayerAgentApp/build/outputs/androidTest-results/connected/TEST-first.xml"
  echo 'first business failure'
  exit 1
fi
''')
            adb = root / "fake adb"
            adb.write_text('#!/bin/sh\nprintf "%s\\n" "$*" >> "$TRACE"\ncase "$*" in "devices -l") printf "List of devices attached\\nemulator-5554 device\\n";; "-s emulator-5554 shell getprop ro.kernel.qemu") echo 1;; *) echo evidence;; esac\n')
            adb.chmod(0o755)
            out = root / "artifacts"
            env = {"TRACE": str(trace), "FAKE_ROOT": str(root), "GRADLE": str(gradle), "ADB": str(adb), "ANDROID_SERIAL": "emulator-5554"}
            argv = ["android_device.py", "sony-denied", "--artifacts", str(out)]
            with mock.patch.object(android, "ROOT", root), mock.patch.dict(os.environ, env), mock.patch.object(sys, "argv", argv):
                with self.assertRaises(RuntimeError):
                    android.main()
            self.assertEqual(1, sum("connectedDebugAndroidTest" in line for line in trace.read_text().splitlines()))
            evidence = out / "sony-denied"
            self.assertIn("first business failure", (evidence / "commands.log").read_text())
            self.assertTrue((evidence / "xml/TEST-first.xml").exists())
            self.assertTrue((evidence / "logcat.txt").exists())
            self.assertTrue((evidence / "screen.png").exists())

    def test_android_granted_group_sets_only_expected_permissions_and_checks_xml(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for module in ("ControllerApp", "PlayerAgentApp"):
                shutil.copytree(ROOT / module / "src/androidTest", root / module / "src/androidTest")
            trace = root / "trace"
            gradle = root / "fake gradle"
            gradle.write_text('''printf 'gradle ANDROID_SERIAL=%s %s\\n' "$ANDROID_SERIAL" "$*" >> "$TRACE"
if [[ "$*" == *connectedDebugAndroidTest* ]]; then
  mkdir -p "$FAKE_ROOT/PlayerAgentApp/build/outputs/androidTest-results/connected"
  echo '<testsuite><testcase classname="com.example.playeragent.service.BleServicePermissionTest" name="grantedBlePermissionsAllowColdRecoveryWithoutNotificationPermission"/></testsuite>' > "$FAKE_ROOT/PlayerAgentApp/build/outputs/androidTest-results/connected/TEST-granted.xml"
fi
''')
            adb = root / "fake adb"
            adb.write_text('#!/bin/sh\nprintf "%s\\n" "$*" >> "$TRACE"\ncase "$*" in "devices -l") printf "List of devices attached\\nSONY device\\nemulator-5554 device\\nemulator-5580 device\\n";; "-s emulator-5580 shell getprop ro.kernel.qemu") echo 1;; *) echo evidence;; esac\n')
            adb.chmod(0o755)
            env = {"TRACE": str(trace), "FAKE_ROOT": str(root), "GRADLE": str(gradle), "ADB": str(adb), "ANDROID_SERIAL": "SONY"}
            argv = ["android_device.py", "sony-granted", "--serial", "emulator-5580", "--artifacts", str(root / "artifacts")]
            with mock.patch.object(android, "ROOT", root), mock.patch.dict(os.environ, env), mock.patch.object(sys, "argv", argv):
                android.main()
            lines = trace.read_text().splitlines()
            grants = [line for line in lines if line.startswith("-s emulator-5580 shell pm grant")]
            self.assertEqual(3, len(grants))
            self.assertTrue(any("shell pm revoke" in line and "POST_NOTIFICATIONS" in line for line in lines))
            for line in lines:
                if line.startswith("gradle "):
                    self.assertIn("ANDROID_SERIAL=emulator-5580", line)
                elif line != "devices -l":
                    self.assertTrue(line.startswith("-s emulator-5580 "), line)
            self.assertTrue((root / "artifacts/sony-granted/xml/TEST-granted.xml").exists())


class SimulatorProcessTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name)
        self.trace = self.path / "trace"
        xcrun = self.path / "fake xcrun"
        xcrun.write_text('''#!/usr/bin/env python3
import json, os, pathlib, sys
trace=pathlib.Path(os.environ["TRACE"])
with trace.open("a") as f: f.write("xcrun " + " ".join(sys.argv[1:]) + "\\n")
a=sys.argv[1:]
if a == ["simctl", "list", "runtimes", "-j"]:
 print(json.dumps({"runtimes":[{"identifier":"com.apple.CoreSimulator.SimRuntime.iOS-18-0","isAvailable":True}]}))
elif a == ["simctl", "list", "devices", "available", "-j"]:
 print(json.dumps({"devices":{"com.apple.CoreSimulator.SimRuntime.iOS-18-0":[{"name":"iPhone Mock","udid":"MOCK-UDID","isAvailable":True,"state":"Shutdown"}]}}))
elif a[:4] == ["xcresulttool", "get", "test-results", "summary"]:
 print(pathlib.Path(os.environ["SUMMARY"]).read_text())
elif a[:4] == ["xcresulttool", "get", "test-results", "tests"]:
 print(pathlib.Path(os.environ["TREE"]).read_text())
elif a[1] == "bootstatus":
 count=len([x for x in trace.read_text().splitlines() if " bootstatus " in x])
 if count <= int(os.environ.get("BOOT_FAILURES", "0")): sys.exit(4)
elif a[1:3] == ["io", "MOCK-UDID"]:
 pathlib.Path(a[-1]).write_text("screenshot")
''')
        xcrun.chmod(0o755)
        xcode = self.path / "fake xcodebuild"
        xcode.write_text('''#!/usr/bin/env python3
import os, pathlib, sys
with pathlib.Path(os.environ["TRACE"]).open("a") as f: f.write("xcodebuild " + " ".join(sys.argv[1:]) + "\\n")
p=pathlib.Path(sys.argv[sys.argv.index("-resultBundlePath")+1])
if not os.environ.get("NO_RESULT"): p.mkdir(); (p/"evidence").write_text(p.stem)
if os.environ.get("HANG") and p.stem == "first":
 print("Testing failed:\\n\\tLyricsReadingUITests (123) encountered an error (The test runner hung before establishing connection.)\\n\\n** TEST FAILED **")
else: print("first business failure")
sys.exit(int(os.environ.get("TEST_STATUS", "0")) if p.stem == "first" else int(os.environ.get("SECOND_STATUS", "0")))
''')
        xcode.chmod(0o755)
        summary = self.path / "summary.json"
        summary.write_text(json.dumps(SUMMARY))
        tree = self.path / "tree.json"
        tree.write_text(json.dumps(TREE))
        self.env = dict(os.environ, XCRUN=str(xcrun), XCODEBUILD=str(xcode), TRACE=str(self.trace), SUMMARY=str(summary), TREE=str(tree))

    def invoke(self, **env):
        return subprocess.run([sys.executable, str(IOS), "LyricsReadingUITests", "--artifacts", str(self.path / "artifacts")], env=dict(self.env, **env), capture_output=True, text=True)

    def test_business_failure_is_never_retried_and_first_evidence_survives(self):
        result = self.invoke(TEST_STATUS="65")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(1, self.trace.read_text().count("xcodebuild "))
        out = self.path / "artifacts/LyricsReadingUITests"
        self.assertEqual("first", (out / "first.xcresult/evidence").read_text())
        self.assertIn("first business failure", (out / "first-test.log").read_text())
        self.assertTrue((out / "first-screen.png").exists())

    def test_exact_infrastructure_failure_retries_once_with_both_rounds_preserved(self):
        result = self.invoke(HANG="1", TEST_STATUS="65", SECOND_STATUS="65")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(2, self.trace.read_text().count("xcodebuild "))
        out = self.path / "artifacts/LyricsReadingUITests"
        self.assertEqual("first", (out / "first.xcresult/evidence").read_text())
        self.assertEqual("second", (out / "second.xcresult/evidence").read_text())
        self.assertTrue((out / "first-summary.json").exists())
        self.assertTrue((out / "first-tests.json").exists())
        decision = json.loads((out / "infra-retry.json").read_text())
        self.assertTrue(decision["retried"])
        self.assertEqual(65, decision["firstExitStatus"])
        self.assertEqual(65, decision["secondExitStatus"])

    def test_verified_infrastructure_failure_can_pass_on_second_run(self):
        result = self.invoke(HANG="1", TEST_STATUS="65")
        self.assertEqual(0, result.returncode, result.stderr)
        decision = json.loads((self.path / "artifacts/LyricsReadingUITests/infra-retry.json").read_text())
        self.assertEqual((65, 0), (decision["firstExitStatus"], decision["secondExitStatus"]))
        self.assertTrue(decision["retried"])

    def test_hang_with_any_executed_case_or_mixed_failure_never_retries(self):
        for mutation in ("executed", "mixed", "missing", "no_bundle"):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as sub:
                summary, tree = copy.deepcopy(SUMMARY), copy.deepcopy(TREE)
                if mutation == "executed":
                    tree["testNodes"].append({"nodeType": "Test Case", "name": "testBusiness", "result": "Passed"})
                elif mutation == "mixed":
                    summary["testFailures"].append({"failureText": "assertion failed"})
                summary_path, tree_path = Path(sub) / "summary.json", Path(sub) / "tree.json"
                summary_path.write_text(json.dumps(summary) if mutation != "missing" else "invalid json")
                tree_path.write_text(json.dumps(tree))
                trace = Path(sub) / "trace"
                env = dict(self.env, TRACE=str(trace), SUMMARY=str(summary_path), TREE=str(tree_path), HANG="1", TEST_STATUS="65")
                if mutation == "no_bundle":
                    env["NO_RESULT"] = "1"
                result = subprocess.run([sys.executable, str(IOS), "LyricsReadingUITests", "--artifacts", str(Path(sub) / "artifacts")], env=env, capture_output=True, text=True)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual(1, trace.read_text().count("xcodebuild "))

    def test_boot_failure_retried_before_business_tests_and_logs_preserved(self):
        result = self.invoke(BOOT_FAILURES="1")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(2, self.trace.read_text().count(" bootstatus "))
        self.assertEqual(1, self.trace.read_text().count("xcodebuild "))
        out = self.path / "artifacts/LyricsReadingUITests"
        self.assertTrue((out / "boot-1.log").exists())
        self.assertTrue((out / "boot-2.log").exists())

    def test_repeated_boot_failure_never_runs_business_tests(self):
        result = self.invoke(BOOT_FAILURES="2")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(2, self.trace.read_text().count(" bootstatus "))
        self.assertNotIn("xcodebuild ", self.trace.read_text())


if __name__ == "__main__":
    unittest.main()
