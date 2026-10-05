import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


DEPLOY_DIR = Path(__file__).resolve().parents[1]
REQUESTED = "11111111-1111-1111-1111-111111111111"
CONNECTED = "22222222-2222-2222-2222-222222222222"
SECOND = "33333333-3333-3333-3333-333333333333"

# Exercise the real CLI entrypoints. External commands are replaced only in the
# child process, and profile directories are never created, copied or removed.
STUB = r'''#!/usr/bin/env python3
import json, os, pathlib, sys, time
name = pathlib.Path(sys.argv[0]).name
args = sys.argv[1:]
root = pathlib.Path(os.environ["DEPLOY_TEST_ROOT"])
config = json.loads((root / "config.json").read_text())
with (root / "calls.jsonl").open("a") as stream:
    stream.write(json.dumps([name, args]) + "\n")
if name == "xcrun":
    if "details" in args:
        sys.exit(0 if config.get("details_ok") else 1)
    if "list" in args and "devices" in args:
        counter = root / "list_count"
        count = int(counter.read_text()) + 1 if counter.exists() else 1
        counter.write_text(str(count))
        print(config.get("rows", ""))
        if config.get("list_fail") or count <= config.get("fail_first", 0):
            time.sleep(1.05)
            sys.exit(1)
        sys.exit(0)
    # Reinstall tests stop after selection, before querying an actual device.
    sys.exit(1)
if name == "xcodebuild":
    print("fixture: reached build, stopping before install")
    sys.exit(17)
if name == "mkdir":
    for arg in args:
        if arg.startswith("-"):
            continue
        path = pathlib.Path(arg)
        if path.is_relative_to(root):
            path.mkdir(parents=True, exist_ok=True)
    sys.exit(0)
if name == "find":
    # The deployment scripts scan profile directories using find. Return no
    # profiles so --renew-profiles cannot remove any real signing data.
    sys.exit(0)
if name in {"defaults", "sleep", "open"}:
    sys.exit(0)
# Neither security nor cp is allowed to inspect or modify real profiles.
sys.exit(99)
'''


class IOSDeployTests(unittest.TestCase):
    def run_script(self, script, config, *arguments, wait=0):
        temporary = tempfile.TemporaryDirectory(prefix="musicble-deploy-tests-")
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        (root / "config.json").write_text(json.dumps(config))
        binaries = root / "bin"
        binaries.mkdir()
        stub = binaries / "stub.py"
        stub.write_text(STUB)
        stub.chmod(0o755)
        for name in ["xcrun", "xcodebuild", "mkdir", "find", "defaults", "sleep", "open", "security", "cp"]:
            (binaries / name).symlink_to(stub)
        environment = os.environ.copy()
        environment.update({
            "PATH": f"{binaries}:{os.environ['PATH']}",
            "DEPLOY_TEST_ROOT": str(root), "ROOT_DIR": str(DEPLOY_DIR.parents[1]),
            "DERIVED_DATA_PATH": str(root / "derived"), "OUT_DIR": str(root / "output"),
            "OUT_ROOT": str(root / "runs"), "STATE_FILE": str(root / "state.json"),
            "LAST_SUCCESS_FILE": str(root / "success.json"), "IOS_DEVICE_ID": "",
            "XCODE_DESTINATION": "generic/platform=iOS", "DEVICETL_WAIT_SECONDS": str(wait),
            "RENEW_PROFILE_WAIT_SECONDS": "0", "XCODE_WARM_AFTER_PROFILE_REMOVAL": "false",
            "DESKTOP_DIR": str(root / "desktop"), "COMMAND_NAME": "refresh.command",
        })
        result = subprocess.run(["bash", str(DEPLOY_DIR / script), *arguments], env=environment,
                                capture_output=True, text=True, timeout=20)
        calls_path = root / "calls.jsonl"
        calls = [json.loads(line) for line in calls_path.read_text().splitlines()] if calls_path.exists() else []
        return result, calls, root

    @staticmethod
    def row(identifier, state):
        return f"Fixture   fixture.local   {identifier}   {state}   iPhone 16 Pro\n"

    def test_unavailable_and_disconnected_devices_never_reach_build_or_apps(self):
        for state in ["unavailable", "disconnected"]:
            for script in ["ios_deploy.sh", "ios_reinstall_if_needed.sh"]:
                with self.subTest(state=state, script=script):
                    result, calls, _ = self.run_script(script, {"rows": self.row(CONNECTED, state)})
                    self.assertFalse(any(name == "xcodebuild" or "apps" in args for name, args in calls), result.stdout)
                    self.assertEqual(result.returncode, 2 if script == "ios_deploy.sh" else 0)

    def test_stale_pinned_id_falls_back_to_the_unique_connected_phone(self):
        for script in ["ios_deploy.sh", "ios_reinstall_if_needed.sh"]:
            with self.subTest(script=script):
                result, calls, root = self.run_script(script, {"rows": self.row(CONNECTED, "connected")},
                                                       "--device", REQUESTED)
                if script == "ios_deploy.sh":
                    self.assertEqual(result.returncode, 17, result.stderr)
                    self.assertIn(f"device={CONNECTED}", result.stdout)
                else:
                    state = json.loads((root / "state.json").read_text())
                    self.assertEqual(state["deviceId"], CONNECTED)
                    self.assertFalse(state["deployExecuted"])
                self.assertTrue(any("list" in args for name, args in calls if name == "xcrun"))

    def test_multiple_available_phones_do_not_choose_an_arbitrary_target(self):
        rows = self.row(CONNECTED, "connected") + self.row(SECOND, "available")
        for script in ["ios_deploy.sh", "ios_reinstall_if_needed.sh"]:
            with self.subTest(script=script):
                result, calls, _ = self.run_script(script, {"rows": rows})
                self.assertIn("Multiple connected/available iPhones", result.stderr)
                self.assertFalse(any(name == "xcodebuild" or "apps" in args for name, args in calls))

    def test_failed_device_listing_cannot_select_a_partial_output(self):
        for script in ["ios_deploy.sh", "ios_reinstall_if_needed.sh"]:
            with self.subTest(script=script):
                result, calls, _ = self.run_script(script, {"rows": self.row(CONNECTED, "connected"), "list_fail": True})
                self.assertIn("Unable to list iOS devices", result.stderr)
                self.assertFalse(any(name == "xcodebuild" or "apps" in args for name, args in calls))

    def test_transient_device_listing_failure_can_recover(self):
        for script in ["ios_deploy.sh", "ios_reinstall_if_needed.sh"]:
            with self.subTest(script=script):
                result, calls, root = self.run_script(script, {"rows": self.row(CONNECTED, "connected"), "fail_first": 1}, wait=3)
                self.assertEqual(int((root / "list_count").read_text()), 2)
                self.assertTrue(any(name == "xcodebuild" or "apps" in args for name, args in calls), result.stderr)

    def test_valid_pinned_id_does_not_fall_back_to_other_phones(self):
        for script in ["ios_deploy.sh", "ios_reinstall_if_needed.sh"]:
            with self.subTest(script=script):
                result, calls, root = self.run_script(script, {"rows": self.row(CONNECTED, "connected"), "details_ok": True},
                                                       "--device", REQUESTED)
                self.assertFalse(any("list" in args for name, args in calls if name == "xcrun"))
                if script == "ios_deploy.sh":
                    self.assertIn(f"device={REQUESTED}", result.stdout)
                else:
                    self.assertEqual(json.loads((root / "state.json").read_text())["deviceId"], REQUESTED)

    def test_failed_required_profile_build_never_attempts_install(self):
        result, calls, root = self.run_script("ios_deploy.sh", {"rows": self.row(CONNECTED, "connected")},
                                             "--renew-profiles", "--require-renewed-profile")
        self.assertEqual(result.returncode, 17, result.stderr)
        self.assertIn("not installing after failed build", result.stdout)
        self.assertFalse(any("install" in args for name, args in calls if name == "xcrun"))
        self.assertEqual((root / "output/profile_backup_manifest.tsv").read_text(), "")

    def test_desktop_command_propagates_wait_limit_and_refresh_flags(self):
        result, _, root = self.run_script("install_desktop_refresh_command.sh", {}, "--device", REQUESTED)
        self.assertEqual(result.returncode, 0, result.stderr)
        command = root / "desktop/refresh.command"
        self.assertTrue(os.access(command, os.X_OK))
        text = command.read_text()
        self.assertIn('export DEVICETL_WAIT_SECONDS="${DEVICETL_WAIT_SECONDS:-120}"', text)
        self.assertIn("--refresh-only --renew-profiles --require-renewed-profile", text)
        self.assertIn(f"--device {REQUESTED}", text)
        subprocess.run(["bash", "-n", str(command)], check=True)


if __name__ == "__main__":
    unittest.main()
