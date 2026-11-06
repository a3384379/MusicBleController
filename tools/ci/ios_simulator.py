#!/usr/bin/env python3
"""Boot an available iOS simulator, then run exactly one XCTest scheme once."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
RUNNER_HUNG = "The test runner hung before establishing connection."


def command(args, logfile):
    print("+", " ".join(map(str, args)), flush=True)
    with logfile.open("ab") as out:
        out.write(("\n+ " + " ".join(map(str, args)) + "\n").encode())
        out.flush()
        return subprocess.run(args, cwd=ROOT, stdout=out, stderr=subprocess.STDOUT).returncode


def runner_hung_before_tests(summary, tree, log, scheme):
    """Accept only Xcode's synthetic System Failures case for this exact fault."""
    if not isinstance(summary, dict) or not isinstance(tree, dict):
        return False
    if any(type(summary.get(key)) is not type(value) or summary.get(key) != value for key, value in {
        "result": "Failed", "passedTests": 0, "skippedTests": 0,
        "failedTests": 1, "expectedFailures": 0, "totalTestCount": 1,
    }.items()):
        return False
    devices = summary.get("devicesAndConfigurations")
    if not isinstance(devices, list) or len(devices) != 1 or not isinstance(devices[0], dict) or any(type(devices[0].get(k)) is not type(v) or devices[0].get(k) != v for k, v in {
        "passedTests": 0, "skippedTests": 0, "failedTests": 1, "expectedFailures": 0,
    }.items()):
        return False
    failures = summary.get("testFailures")
    target = "IOSBleFeasibilityTests" if scheme == "sonyMusic" else "LyricsReadingUITests"
    name_pattern = re.compile(re.escape(scheme) + r" \(\d+\) encountered an error")
    if not isinstance(failures, list) or len(failures) != 1 or not isinstance(failures[0], dict):
        return False
    failure = failures[0]
    name = failure.get("testName", "")
    if not isinstance(name, str) or not name_pattern.fullmatch(name) or failure.get("testIdentifierString") != name or failure.get("targetName") != target or failure.get("failureText") != RUNNER_HUNG:
        return False
    cases, messages = [], []
    def visit(node, parent):
        if not isinstance(node, dict):
            raise ValueError("invalid test tree")
        if node.get("nodeType") == "Test Case":
            cases.append((node, parent))
        if node.get("nodeType") == "Failure Message":
            messages.append(node)
        children = node.get("children", [])
        if not isinstance(children, list):
            raise ValueError("invalid test tree children")
        for child in children:
            visit(child, node)
    try:
        nodes = tree.get("testNodes")
        if not isinstance(nodes, list) or not nodes:
            return False
        for node in nodes:
            visit(node, {})
    except (AttributeError, ValueError):
        return False
    if len(cases) != 1 or len(messages) != 1:
        return False
    case, parent = cases[0]
    if parent.get("name") != "System Failures" or parent.get("nodeType") != "Test Suite" or case.get("name") != name or case.get("nodeIdentifier") != name or case.get("result") != "Failed" or case.get("children") != messages or messages[0].get("name") != RUNNER_HUNG:
        return False
    if re.search(r"(?m)^\s*Test Case\b", log) or re.search(r"(?i)\bassertion (?:failed|failure)\b|\bXCTAssert\w* failed\b", log):
        return False
    expected_line = re.escape(name) + r" \(" + re.escape(RUNNER_HUNG) + r"\)"
    failure_section = re.search(r"(?ms)^Testing failed:\s*\n(.*?)\n\s*\*\* TEST FAILED \*\*", log)
    return bool(failure_section and re.fullmatch(r"\s*" + expected_line + r"\s*", failure_section.group(1)))


def read_results(xcrun, bundle, out):
    if not bundle.is_dir():
        return None
    values = []
    for kind in ("summary", "tests"):
        result = subprocess.run([xcrun, "xcresulttool", "get", "test-results", kind, "--path", str(bundle)], capture_output=True)
        (out / f"first-{kind}.json").write_bytes(result.stdout)
        (out / f"first-{kind}.stderr.log").write_bytes(result.stderr)
        if result.returncode:
            return None
        try:
            values.append(json.loads(result.stdout))
        except (ValueError, UnicodeDecodeError):
            return None
    return values


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("scheme", choices=("sonyMusic", "LyricsReadingUITests"))
    parser.add_argument("--artifacts", type=Path, default=Path("ci-artifacts/ios"))
    args = parser.parse_args()
    out = args.artifacts.resolve() / args.scheme
    if out.exists() and any(out.iterdir()):
        raise RuntimeError(f"artifact directory is not empty: {out}; choose a fresh directory")
    out.mkdir(parents=True, exist_ok=True)
    xcrun = os.environ.get("XCRUN", "xcrun")
    xcodebuild = os.environ.get("XCODEBUILD", "xcodebuild")
    runtimes = json.loads(subprocess.check_output([xcrun, "simctl", "list", "runtimes", "-j"]))
    available = {r["identifier"] for r in runtimes["runtimes"] if r.get("isAvailable") and r["identifier"].startswith("com.apple.CoreSimulator.SimRuntime.iOS-")}
    devices = json.loads(subprocess.check_output([xcrun, "simctl", "list", "devices", "available", "-j"]))
    candidates = [(runtime, d) for runtime, items in devices["devices"].items() if runtime in available for d in items if d.get("isAvailable") and d.get("name", "").startswith("iPhone")]
    if not candidates:
        raise RuntimeError("no available iPhone simulator with an available iOS runtime")
    runtime, device = sorted(candidates, key=lambda item: (item[0], item[1]["name"]), reverse=True)[0]
    udid = device["udid"]
    print(f"Using {device['name']} ({udid}), runtime {runtime}", flush=True)
    (out / "simulator.txt").write_text(f"{device['name']} {udid} {runtime}\n")
    booted = device.get("state") == "Booted"
    for attempt in (1, 2):
        log = out / f"boot-{attempt}.log"
        started = 0 if booted else command([xcrun, "simctl", "boot", udid], log)
        if started == 0 and command([xcrun, "simctl", "bootstatus", udid, "-b"], log) == 0:
            break
        command([xcrun, "simctl", "shutdown", udid], log)
        booted = False
    else:
        raise RuntimeError("simulator failed to boot after two pre-test attempts")
    def test_round(prefix):
        status = command([xcodebuild, "-project", "IOSBleFeasibility/IOSBleFeasibility.xcodeproj", "-scheme", args.scheme,
                          "-destination", f"platform=iOS Simulator,id={udid}", "-derivedDataPath", str(out / "DerivedData"),
                          "-resultBundlePath", str(out / f"{prefix}.xcresult"), "-parallel-testing-enabled", "NO",
                          "CODE_SIGNING_ALLOWED=NO", "test"], out / f"{prefix}-test.log")
        command([xcrun, "simctl", "io", udid, "screenshot", str(out / f"{prefix}-screen.png")], out / f"{prefix}-screenshot.log")
        return status

    status = test_round("first")
    decision = {"firstExitStatus": status, "retried": False, "reason": "first run passed" if not status else "unrecognized failure"}
    decision_path = out / "infra-retry.json"
    try:
        if status:
            results = read_results(xcrun, out / "first.xcresult", out)
            if results and runner_hung_before_tests(*results, (out / "first-test.log").read_text(errors="replace"), args.scheme):
                decision.update(retryEligible=True, reason="runner hung before establishing connection; no business test case ran")
                decision_path.write_text(json.dumps(decision, indent=2) + "\n")
                print("First run hit a verified pre-test runner failure; preserving evidence and retrying once.", flush=True)
                restart_log = out / "retry-boot.log"
                if command([xcrun, "simctl", "shutdown", udid], restart_log) or command([xcrun, "simctl", "boot", udid], restart_log) or command([xcrun, "simctl", "bootstatus", udid, "-b"], restart_log):
                    decision["retryBootFailed"] = True
                    raise RuntimeError("simulator restart failed before retry")
                decision["retried"] = True
                status = test_round("second")
                decision["secondExitStatus"] = status
        if status:
            raise RuntimeError(f"{args.scheme} test failed ({status}); see preserved result bundle and logs")
    finally:
        decision_path.write_text(json.dumps(decision, indent=2) + "\n")


if __name__ == "__main__":
    try:
        main()
    except (OSError, subprocess.CalledProcessError, KeyError, ValueError, RuntimeError) as error:
        print(f"CI iOS failed: {error}", file=sys.stderr)
        sys.exit(1)
