#!/usr/bin/env python3
"""Read-only prototype preflight. Installation and launch need explicit CLI options."""
import argparse
import hashlib
import json
import os
import shutil
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent
PACKAGE = "com.musicblecontroller.phoneaudiolab"
ACTIVITY = PACKAGE + "/.PhoneAudioLabActivity"


def run(command):
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=25)
        return result.returncode, result.stdout.strip(), result.stderr.strip()
    except (OSError, subprocess.TimeoutExpired) as error:
        return 1, "", type(error).__name__


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", type=Path)
    parser.add_argument("--serial")
    parser.add_argument("--install", action="store_true", help="Install only the independent lab APK")
    parser.add_argument("--launch", action="store_true", help="Open the lab; receiving still stays off")
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/device-check.json")
    args = parser.parse_args()
    sdk = Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk"))
    adb = str(args.adb or shutil.which("adb") or sdk / "platform-tools/adb")
    code, stdout, _ = run([adb, "devices", "-l"])
    online = [line.split()[0] for line in stdout.splitlines()[1:]
              if len(line.split()) > 1 and line.split()[1] == "device"]
    target = args.serial if args.serial in online else online[0] if len(online) == 1 and not args.serial else None
    report = {"schema_version": 1, "package": PACKAGE, "receiving_automatically_started": False,
              "checks": [], "physical_acceptance": "NOT_RUN"}
    report["checks"].append({"name": "unique_android_device", "result": "PASS" if code == 0 and target else "FAIL"})
    def command(*values):
        return run([adb, "-s", target, *values])
    if target:
        _, model, _ = command("shell", "getprop", "ro.product.model")
        _, version, _ = command("shell", "getprop", "ro.build.version.release")
        _, abi, _ = command("shell", "getprop", "ro.product.cpu.abi")
        _, api, _ = command("shell", "getprop", "ro.build.version.sdk")
        _, emulator, _ = command("shell", "getprop", "ro.kernel.qemu")
        # A simulator never silently passes the Sony device gate.
        sony = model.upper().replace("_", "-") == "NW-WM1AM2" and emulator != "1"
        report["device"] = {"model": model, "android": version, "abi": abi, "emulator": emulator == "1"}
        report["checks"].append({"name": "reference_sony_device", "result": "PASS" if sony else "FAIL"})
        supported = abi == "arm64-v8a" and api.isdigit() and int(api) >= 30
        report["checks"].append({"name": "runtime_supported", "result": "PASS" if supported else "FAIL"})
        if args.install or args.launch:
            if not sony or not supported:
                report["checks"].append({"name": "explicit_device_actions", "result": "FAIL",
                                         "reason": "wrong_device_no_action_taken"})
            else:
                apk = ROOT / "artifacts/PhoneAudioLab-debug.apk"
                if args.install:
                    if not apk.is_file():
                        result = "FAIL"
                    else:
                        result = "PASS" if command("install", "-r", str(apk))[0] == 0 else "FAIL"
                        report["apk_sha256"] = hashlib.sha256(apk.read_bytes()).hexdigest()
                    report["checks"].append({"name": "install_lab_apk", "result": result})
                install_failed = any(c["result"] == "FAIL" for c in report["checks"])
                if args.launch and not install_failed:
                    code, output, _ = command("shell", "am", "start", "-W", "-n", ACTIVITY)
                    report["checks"].append({"name": "open_lab_default_off",
                                             "result": "PASS" if code == 0 and "Status: ok" in output else "FAIL"})
    report["overall_result"] = "PASS" if all(c["result"] == "PASS" for c in report["checks"]) else "FAIL"
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["overall_result"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
