#!/usr/bin/env python3
"""Optional host-side Sony CPU/memory evidence; no app timer or protocol changes."""
from __future__ import annotations

import argparse
import hashlib
import json
import signal
import subprocess
import time
from pathlib import Path


def collect(output: Path, adb: str, serial: str, interval: int, duration: int) -> int:
    output.mkdir(parents=True, exist_ok=False)
    stop = False
    def stop_handler(_signal, _frame):
        nonlocal stop
        stop = True
    signal.signal(signal.SIGTERM, stop_handler)
    signal.signal(signal.SIGINT, stop_handler)
    start = time.monotonic()
    deadline = start + duration
    records = []
    while not stop and time.monotonic() < deadline:
        cycle = len(records)
        row = {"index": cycle, "startMonotonic": time.monotonic(), "commands": {}}
        for label, args in (("cpu", ["dumpsys", "cpuinfo"]),
                            ("memory", ["dumpsys", "meminfo", "com.example.playeragent"])):
            command = [adb, "-s", serial, "shell", *args]
            try:
                result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=20)
                stdout, stderr, status = result.stdout, result.stderr, result.returncode
            except (OSError, subprocess.TimeoutExpired) as exc:
                stdout, stderr, status = b"", str(exc).encode(), None
            stdout_path = output / f"{cycle:05d}_{label}.stdout"
            stderr_path = output / f"{cycle:05d}_{label}.stderr"
            stdout_path.write_bytes(stdout)
            stderr_path.write_bytes(stderr)
            row["commands"][label] = {"argv": command, "returncode": status,
                "stdout": stdout_path.name, "stdoutSha256": hashlib.sha256(stdout).hexdigest(),
                "stderr": stderr_path.name, "stderrSha256": hashlib.sha256(stderr).hexdigest()}
        row["endMonotonic"] = time.monotonic()
        records.append(row)
        (output / "samples.json").write_text(json.dumps({"schemaVersion": 1, "device": serial,
            "startedMonotonic": start, "samples": records}, indent=2) + "\n")
        target = min(deadline, row["endMonotonic"] + interval)
        while not stop and time.monotonic() < target:
            time.sleep(max(0.0, min(0.25, target - time.monotonic())))
    manifest = {"schemaVersion": 1, "device": serial, "startedMonotonic": start,
                "endedMonotonic": time.monotonic(), "requestedDurationSeconds": duration,
                "samples": records, "ios": "NOT_CAPTURED", "energy": "NOT_CAPTURED"}
    (output / "samples.json").write_text(json.dumps(manifest, indent=2) + "\n")
    return 1 if not records or any(item["returncode"] != 0 or not (output / item["stdout"]).stat().st_size
                                   for row in records for item in row["commands"].values()) else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--interval-seconds", type=int, default=60)
    parser.add_argument("--duration-seconds", type=int, required=True)
    args = parser.parse_args()
    if args.interval_seconds <= 0 or args.duration_seconds <= 0:
        parser.error("interval and duration must be positive")
    return collect(args.output_dir, args.adb, args.serial, args.interval_seconds, args.duration_seconds)

if __name__ == "__main__":
    raise SystemExit(main())
