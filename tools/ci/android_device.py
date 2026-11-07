#!/usr/bin/env python3
"""Run one explicitly enumerated Android instrumentation group and retain evidence."""
import argparse
import collections
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
PERMISSION = "com.example.playeragent.service.BleServicePermissionTest"
GROUPS = {
    "controller-history": ("ControllerApp", ["com.example.controllerapp.ControllerHistoryRegressionTest", "com.example.controllerapp.ControllerRepositoryProtocolTest", "com.example.controllerapp.ControllerLogStoreDeviceTest"]),
    "controller-ui": ("ControllerApp", ["com.example.controllerapp.ui.ControllerUiSmokeTest", "com.example.controllerapp.ui.FullLyricsInteractionTest", "com.example.controllerapp.ui.HistoryActivityRecreationTest"]),
    "sony-room": ("PlayerAgentApp", ["com.example.playeragent.history.PlaybackStatsRoomTest"]),
    "sony-denied": ("PlayerAgentApp", [PERMISSION + "#deniedDebugActionsDoNotColdStartAServiceAndStopRemainsAvailable", PERMISSION + "#deniedDirectForegroundStartStopsBeyondTheSystemDeadlineWithoutRestart"]),
    "sony-granted": ("PlayerAgentApp", [PERMISSION + "#grantedBlePermissionsAllowColdRecoveryWithoutNotificationPermission"]),
}


def collect():
    """Compare the complete source test inventory with this suite's partition."""
    found = {}
    for module in ("ControllerApp", "PlayerAgentApp"):
        for source in (ROOT / module / "src/androidTest").rglob("*"):
            if source.suffix not in (".kt", ".java"):
                continue
            text = source.read_text()
            annotations = re.findall(r"@(?:org\.junit\.)?Test\b", text)
            if not annotations:
                continue
            package = re.search(r"(?m)^package\s+([\w.]+)", text)
            classes = re.findall(r"(?m)^class\s+(\w+)", text)
            methods = re.findall(r"@Test\s+(?:@[\w.]+(?:\([^\n]*\))?\s+)*fun\s+(\w+)\s*\(", text)
            if source.suffix != ".kt" or not package or len(classes) != 1 or len(methods) != len(annotations) or len(set(methods)) != len(methods):
                raise ValueError(f"cannot enumerate tests in {source}")
            name = package.group(1) + "." + classes[0]
            if (module, name) in found:
                raise ValueError(f"duplicate test class: {name}")
            found[(module, name)] = set(methods)
    assigned = collections.Counter()
    by_group = {}
    for group, (module, selectors) in GROUPS.items():
        expected = set()
        for selector in selectors:
            name, _, method = selector.partition("#")
            methods = found.get((module, name), set())
            selected = {method} if method else methods
            if not selected or not selected <= methods:
                raise ValueError(f"unknown or empty selector: {selector}")
            expected.update((name, item) for item in selected)
            assigned.update((module, name, item) for item in selected)
        by_group[group] = expected
    inventory = {(module, name, item) for (module, name), methods in found.items() for item in methods}
    if set(assigned) != inventory or any(count != 1 for count in assigned.values()):
        raise ValueError(f"unassigned={inventory - set(assigned)}, duplicated={[x for x, n in assigned.items() if n != 1]}, unknown={set(assigned) - inventory}")
    return by_group


def run(args, log, check=True, env=None):
    print("+", " ".join(map(str, args)), flush=True)
    with log.open("ab") as output:
        output.write(("\n+ " + " ".join(map(str, args)) + "\n").encode())
        output.flush()
        result = subprocess.run(args, cwd=ROOT, stdout=output, stderr=subprocess.STDOUT, env=env)
    if check and result.returncode:
        raise RuntimeError(f"command failed ({result.returncode}); see {log}")
    return result.returncode


def select_device(adb, requested):
    listing = subprocess.check_output([adb, "devices", "-l"], text=True)
    devices = {}
    for line in listing.splitlines():
        fields = line.split()
        if len(fields) >= 2 and fields[0] != "List" and not line.startswith("*"):
            devices[fields[0]] = fields[1]
    if requested:
        if not re.fullmatch(r"[A-Za-z0-9_.:-]+", requested):
            raise ValueError("invalid device serial")
        if devices.get(requested) != "device":
            raise ValueError(f"selected device {requested!r} is missing or not authorized/online")
        return requested
    if len(devices) != 1 or next(iter(devices.values()), None) != "device":
        raise ValueError("select one online device explicitly using --serial or ANDROID_SERIAL")
    return next(iter(devices))


def verify_xml(directory, expected):
    actual = collections.Counter()
    files = list(directory.rglob("*.xml"))
    if not files:
        raise ValueError("no instrumentation XML reports")
    for path in files:
        tree = ET.parse(path)
        for case in tree.iter("testcase"):
            identity = (case.get("classname"), case.get("name"))
            actual[identity] += 1
            if case.find("skipped") is not None or case.find("failure") is not None or case.find("error") is not None:
                raise ValueError(f"test skipped or failed: {identity}")
    if set(actual) != expected or any(count != 1 for count in actual.values()):
        raise ValueError(f"missing={expected - set(actual)}, unexpected={set(actual) - expected}, duplicates={[x for x, n in actual.items() if n != 1]}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("group", choices=[*GROUPS, "collect"])
    parser.add_argument("--artifacts", type=Path, default=Path("ci-artifacts/android"))
    parser.add_argument("--serial", help="explicit adb serial; overrides ANDROID_SERIAL")
    args = parser.parse_args()
    inventory = collect()
    for name, tests in inventory.items():
        print(f"{name}: {len(tests)} tests")
    if args.group == "collect":
        return
    module, selectors = GROUPS[args.group]
    adb = os.environ.get("ADB", "adb")
    serial = select_device(adb, args.serial or os.environ.get("ANDROID_SERIAL"))
    targeted_adb = [adb, "-s", serial]
    gradle_env = dict(os.environ, ANDROID_SERIAL=serial)
    print(f"Using Android device: {serial}", flush=True)
    if args.group in ("sony-denied", "sony-granted"):
        if not serial.startswith("emulator-"):
            raise RuntimeError("permission groups require an isolated Android emulator")
        emulator = subprocess.check_output([*targeted_adb, "shell", "getprop", "ro.kernel.qemu"], text=True).strip()
        if emulator != "1":
            raise RuntimeError("permission groups require an isolated Android emulator")
    out = args.artifacts.resolve() / args.group
    if out.exists() and any(out.iterdir()):
        raise ValueError(f"artifact directory is not empty: {out}; choose a fresh directory")
    out.mkdir(parents=True, exist_ok=True)
    (out / "device.txt").write_text(serial + "\n")
    log = out / "commands.log"
    package = "com.example.playeragent" if module == "PlayerAgentApp" else "com.example.controllerapp"
    gradle = os.environ.get("GRADLE", str(ROOT / "gradlew"))
    result_dir = ROOT / module / "build/outputs/androidTest-results/connected"
    report_dir = ROOT / module / "build/reports/androidTests/connected"
    try:
        for stale in (result_dir, report_dir):
            if stale.exists():
                shutil.rmtree(stale)
        run(["bash", gradle, "--no-daemon", f":{module}:installDebug", f":{module}:installDebugAndroidTest"], log, env=gradle_env)
        if args.group.startswith("sony-") and args.group != "sony-room":
            for permission in ("BLUETOOTH_CONNECT", "BLUETOOTH_ADVERTISE", "BLUETOOTH_SCAN", "POST_NOTIFICATIONS"):
                action = "grant" if args.group == "sony-granted" and permission != "POST_NOTIFICATIONS" else "revoke"
                run([*targeted_adb, "shell", "pm", action, package, "android.permission." + permission], log)
        run(["bash", gradle, "--no-daemon", f":{module}:connectedDebugAndroidTest", "-Pandroid.testInstrumentationRunnerArguments.class=" + ",".join(selectors)], log, env=gradle_env)
        verify_xml(result_dir, inventory[args.group])
    finally:
        for source, name in ((result_dir, "xml"), (report_dir, "html")):
            if source.exists():
                shutil.copytree(source, out / name, dirs_exist_ok=True)
        with (out / "logcat.txt").open("wb") as file:
            subprocess.run([*targeted_adb, "logcat", "-d", "-v", "threadtime"], stdout=file, stderr=subprocess.STDOUT)
        with (out / "screen.png").open("wb") as file:
            subprocess.run([*targeted_adb, "exec-out", "screencap", "-p"], stdout=file, stderr=subprocess.DEVNULL)
        with (out / "app-cache.tar").open("wb") as file:
            subprocess.run([*targeted_adb, "exec-out", "run-as", package, "tar", "cf", "-", "cache"], stdout=file, stderr=subprocess.DEVNULL)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, OSError, subprocess.CalledProcessError, ET.ParseError) as error:
        print(f"CI instrumentation failed: {error}", file=sys.stderr)
        sys.exit(1)
