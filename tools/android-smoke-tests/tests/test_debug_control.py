#!/usr/bin/env python3
"""Device-free behavior tests for Android smoke debug control."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]
HEALTHY = "[BleHealth] state=HEALTHY\n[BleGattServer] started\nadvertising started\n"
RECOVERED = HEALTHY + "[BleHealth] recover start\n[BleHealth] recover done\n"


class DebugControlTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.commands = self.root / "commands"
        self.logcat = self.root / "logcat"
        self.logcat.write_text(RECOVERED)
        self.adb = self.root / "adb"
        self.adb.write_text('''#!/usr/bin/env python3
import os, sys
from pathlib import Path
args = sys.argv[1:]
with open(os.environ["FAKE_COMMANDS"], "a") as f:
    f.write(" ".join(args) + "\\n")
if args[:1] == ["devices"]:
    print("List of devices attached")
    if os.environ.get("FAKE_NO_DEVICE") != "1": print("fake-device device")
elif "broadcast" in args:
    sys.exit(int(os.environ.get("FAKE_BROADCAST_RC", "0")))
elif "logcat" in args and "-d" in args:
    if os.environ.get("FAKE_LOGCAT_FAIL") == "1": sys.exit(1)
    print(Path(os.environ["FAKE_LOGCAT"]).read_text(), end="")
elif "pidof" in args:
    print("123")
elif "getprop" in args:
    print("fake")
elif "shell" in args and any(x.startswith("p=") for x in args):
    print("true:1:1:OK")
''')
        self.adb.chmod(0o755)
        sleep = self.root / "sleep"
        sleep.write_text("#!/bin/sh\nexit 0\n")
        sleep.chmod(0o755)
        self.env = os.environ.copy()
        for key in ("DEBUG_CONTROL_ENABLED", "DEVICE_ID", "ANDROID_DEVICE_ID", "OUT_DIR"):
            self.env.pop(key, None)
        self.env.update(ADB_BIN=str(self.adb), FAKE_COMMANDS=str(self.commands),
                        FAKE_LOGCAT=str(self.logcat), PATH=f"{self.root}:{os.environ['PATH']}")

    def invoke(self, script, args=(), **env):
        return subprocess.run([str(SCRIPTS / script), *map(str, args)],
                              env={**self.env, **env}, text=True, capture_output=True)

    def actions(self):
        return self.commands.read_text() if self.commands.exists() else ""

    def watchdog(self, log_text=HEALTHY, **env):
        out = self.root / "watchdog"
        out.mkdir()
        log = out / "sony_logcat.log"
        log.write_text(log_text)
        proc = self.invoke("ble_health_watchdog_test.sh", [log], OUT_DIR=str(out), **env)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        return json.loads((out / "ble_health_watchdog.json").read_text())

    def test_suite_passes_disabled_flag_to_both_analyzers(self):
        out = self.root / "suite"
        proc = self.invoke("run_android_smoke_tests.sh", ["--quick", "--json", "--no-debug-control", "--output", out])
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertNotIn("broadcast", self.actions())
        start = json.loads((out / "debug_control.json").read_text())
        health = json.loads((out / "ble_health_watchdog.json").read_text())
        self.assertFalse(start["debugControlStartAttempted"])
        self.assertEqual(start["debugControlStartResult"], "skipped")
        self.assertFalse(health["debugControlEnabled"])
        self.assertFalse(health["recoverCommandAttempted"])
        self.assertIsNone(health["recoverCommandRc"])
        self.assertEqual(health["recoverCommandSkipReason"], "debug_control_disabled")
        self.assertIn("disabled", health["reason"])

    def test_suite_default_sends_both_controls(self):
        out = self.root / "suite"
        proc = self.invoke("run_android_smoke_tests.sh", ["--quick", "--json", "--output", out])
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertIn("debug.START_BLE_SERVICE", self.actions())
        self.assertIn("debug.RECOVER_BLE_STACK", self.actions())
        health = json.loads((out / "ble_health_watchdog.json").read_text())
        self.assertTrue(health["recoverCommandAttempted"])
        self.assertEqual(health["recoverCommandRc"], 0)

    def test_watchdog_default_broadcast_failure_is_attempted(self):
        health = self.watchdog(DEVICE_ID="fake-device", FAKE_BROADCAST_RC="13")
        self.assertIn("debug.RECOVER_BLE_STACK", self.actions())
        self.assertTrue(health["recoverCommandAttempted"])
        self.assertEqual(health["recoverCommandRc"], 13)
        self.assertIn("failed rc=13", health["reason"])

    def test_watchdog_missing_device_is_not_attempted(self):
        health = self.watchdog()
        self.assertNotIn("broadcast", self.actions())
        self.assertFalse(health["recoverCommandAttempted"])
        self.assertIsNone(health["recoverCommandRc"])
        self.assertEqual(health["recoverCommandSkipReason"], "adb_device_missing")
        self.assertIn("adb/device env missing", health["reason"])

    def test_watchdog_missing_adb_is_not_attempted(self):
        health = self.watchdog(ADB_BIN="", DEVICE_ID="fake-device")
        self.assertFalse(health["recoverCommandAttempted"])
        self.assertIsNone(health["recoverCommandRc"])
        self.assertEqual(health["recoverCommandSkipReason"], "adb_device_missing")
        self.assertEqual(self.actions(), "")

    def test_watchdog_permission_denial_is_not_recovery_success(self):
        self.logcat.write_text(RECOVERED + "[DebugControl] skipped action=RECOVER_BLE_STACK reason=permission_missing\n")
        health = self.watchdog(DEVICE_ID="fake-device")
        self.assertEqual(health["result"], "WARN")
        self.assertIn("permission_missing", health["reason"])

    def test_watchdog_reports_permission_denial_without_health_logs(self):
        for outcome, expected in (("skipped action=RECOVER_BLE_STACK reason=permission_missing", "permission_missing"),
                                  ("failed action=RECOVER_BLE_STACK reason=permission_denied", "permission_denied")):
            with self.subTest(expected=expected):
                out = self.root / expected
                out.mkdir()
                log = out / "sony_logcat.log"
                log.write_text("unrelated log\n")
                self.logcat.write_text(f"[DebugControl] {outcome}\n")
                proc = self.invoke("ble_health_watchdog_test.sh", [log], OUT_DIR=str(out), DEVICE_ID="fake-device")
                self.assertEqual(proc.returncode, 0, proc.stderr)
                health = json.loads((out / "ble_health_watchdog.json").read_text())
                self.assertEqual(health["result"], "WARN")
                self.assertTrue(health["recoverCommandAttempted"])
                self.assertEqual(health["recoverCommandRc"], 0)
                self.assertIn(expected, health["reason"])

    def test_disabled_watchdog_still_fails_on_fatal(self):
        health = self.watchdog(HEALTHY + "FATAL EXCEPTION: main\n", DEVICE_ID="fake-device", DEBUG_CONTROL_ENABLED="false")
        self.assertEqual(health["result"], "FAIL")
        self.assertIn("FATAL", health["reason"])
        self.assertEqual(self.actions(), "")

    def test_watchdog_missing_log_reports_no_attempt(self):
        out = self.root / "missing-log"
        out.mkdir()
        proc = self.invoke("ble_health_watchdog_test.sh", [out / "absent"], OUT_DIR=str(out),
                           DEVICE_ID="fake-device", DEBUG_CONTROL_ENABLED="false")
        self.assertEqual(proc.returncode, 0, proc.stderr)
        health = json.loads((out / "ble_health_watchdog.json").read_text())
        self.assertEqual(health["result"], "WARN")
        self.assertFalse(health["recoverCommandAttempted"])
        self.assertIsNone(health["recoverCommandRc"])
        self.assertEqual(health["recoverCommandSkipReason"], "debug_control_disabled")
        self.assertEqual(self.actions(), "")

    def test_refresh_preserves_fatal_and_failed_refresh_preserves_snapshot(self):
        for fail in ("0", "1"):
            with self.subTest(logcat_failure=fail):
                out = self.root / f"refresh-{fail}"
                out.mkdir()
                log = out / "sony_logcat.log"
                log.write_text(HEALTHY + "FATAL EXCEPTION: old\n")
                proc = self.invoke("android_ble_optional_test.sh", [log], OUT_DIR=str(out),
                                   DEVICE_ID="fake-device", FAKE_LOGCAT_FAIL=fail)
                self.assertEqual(proc.returncode, 0, proc.stderr)
                self.assertIn("\tFAIL\t", proc.stdout)
                self.assertIn("FATAL EXCEPTION: old", log.read_text())

    def test_autostart_poll_preserves_fatal_without_duplicate_counts(self):
        out = self.root / "auto"
        out.mkdir()
        log = out / "sony_logcat.log"
        log.write_text("[ControlServiceAutoStart] start requested\nFATAL EXCEPTION: old\n")
        proc = self.invoke("android_control_service_autostart_test.sh", [log], OUT_DIR=str(out), DEVICE_ID="fake-device")
        self.assertEqual(proc.returncode, 0, proc.stderr)
        data = json.loads((out / "control_service_autostart.json").read_text())
        self.assertEqual(data["result"], "FAIL")
        self.assertIn("FATAL", data["reason"])
        self.assertEqual(data["gattStartedCount"], 1)

    def test_optional_missing_device_reports_no_attempt(self):
        log = self.root / "log"
        log.write_text(HEALTHY)
        proc = self.invoke("android_ble_optional_test.sh", [log], OUT_DIR=str(self.root))
        self.assertEqual(proc.returncode, 0, proc.stderr)
        data = json.loads((self.root / "debug_control.json").read_text())
        self.assertFalse(data["debugControlStartAttempted"])
        self.assertEqual(data["debugControlStartResult"], "missing adb/device env")

    def test_receiver_receipt_and_permission_denial_do_not_claim_start(self):
        for suffix, expected in (("", "received; forwarding not confirmed"),
                                 ("[DebugControl] skipped action=START_BLE_SERVICE reason=permission_missing\n", "skipped: permission_missing"),
                                 ("[DebugControl] failed action=START_BLE_SERVICE reason=permission_denied\n", "failed: permission_denied"),
                                 ("[DebugControl] completed action=START_BLE_SERVICE\n", "forwarded to service")):
            with self.subTest(expected=expected):
                out = self.root / str(len(list(self.root.iterdir())))
                out.mkdir()
                log = out / "sony_logcat.log"
                log.write_text(HEALTHY)
                self.logcat.write_text(HEALTHY + "[DebugControl] received action=com.example.playeragent.debug.START_BLE_SERVICE\n" + suffix)
                proc = self.invoke("android_ble_optional_test.sh", [log], OUT_DIR=str(out), DEVICE_ID="fake-device")
                self.assertEqual(proc.returncode, 0, proc.stderr)
                data = json.loads((out / "debug_control.json").read_text())
                self.assertTrue(data["debugControlAvailable"])
                self.assertEqual(data["debugControlStartResult"], expected)

    def test_suite_missing_device_exits_before_control(self):
        out = self.root / "suite"
        proc = self.invoke("run_android_smoke_tests.sh", ["--quick", "--json", "--no-debug-control", "--output", out], FAKE_NO_DEVICE="1")
        self.assertNotEqual(proc.returncode, 0)
        self.assertNotIn("broadcast", self.actions())


if __name__ == "__main__":
    unittest.main()
