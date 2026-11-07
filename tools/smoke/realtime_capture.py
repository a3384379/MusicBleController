#!/usr/bin/env python3
"""Preserve iOS log snapshots during a run; fail closed on uncertain continuity."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import signal
import subprocess
import time
from pathlib import Path


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def digest_file(path: Path) -> str:
    result = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def write_manifest(output: Path, manifest: dict) -> None:
    target = output / "ios_capture_manifest.json"
    temporary = output / ".ios_capture_manifest.tmp"
    temporary.write_text(json.dumps(manifest, indent=2) + "\n")
    temporary.replace(target)


def copy_system_window(source_path: Path, target_path: Path, observed_start: int,
                       run_end: int, collector_end: int) -> dict:
    """Include complete boundary lines and the collector tail without buffering it."""
    if not 0 <= observed_start <= run_end <= collector_end <= source_path.stat().st_size:
        raise ValueError("invalid system log window")
    with source_path.open("rb") as source, target_path.open("wb") as target:
        source.seek(max(0, observed_start - 1024 * 1024))
        boundary_begin = source.tell()
        prefix = source.read(observed_start - boundary_begin)
        last_newline = prefix.rfind(b"\n")
        start = boundary_begin + last_newline + 1 if last_newline >= 0 else boundary_begin
        start_complete = last_newline >= 0 or boundary_begin == 0
        source.seek(max(0, collector_end - 1))
        ended_on_boundary = collector_end == 0 or source.read(1) == b"\n"
        source.seek(collector_end)
        suffix = b"" if ended_on_boundary else source.readline(1024 * 1024)
        end_complete = ended_on_boundary or suffix.endswith(b"\n")
        end = collector_end + len(suffix)
        source.seek(start)
        remaining = end - start
        while remaining:
            block = source.read(min(1024 * 1024, remaining))
            if not block:
                raise ValueError("system log shorter than recorded window")
            target.write(block)
            remaining -= len(block)
    return {"startOffset": start, "endOffset": end, "observedStartOffset": observed_start,
            "runEndOffset": run_end, "observedEndOffset": collector_end,
            "boundaryComplete": start_complete and end_complete}


def continuity(previous: bytes | None, before: bytes, old: bytes | None, after: bytes) -> tuple[bool, bytes, str]:
    """Prove ordered byte-prefix continuity across current/old/current copies.

    Never deduplicate equal lines: only accept an entire previously copied byte
    prefix at a known file boundary. A missing prefix can mean multiple rolls.
    """
    if not before or not after:
        return False, b"", "empty_current"
    if after.startswith(before):
        combined = after
    elif old is not None and old.startswith(before):
        combined = old + after
    else:
        return False, b"", "rotation_during_copy_or_overwrite"
    if previous is None:
        return True, combined, "baseline"
    if old is not None and old.startswith(previous) and before.startswith(previous):
        return False, b"", "ambiguous_repeated_prefix"
    if before.startswith(previous):
        return True, combined[len(previous):], "current_prefix"
    if old is not None and old.startswith(previous):
        # If two consecutive copies straddle a roll, old is the former current.
        if combined.startswith(old):
            return True, combined[len(previous):], "rolled_prefix"
        return True, old[len(previous):] + combined, "rolled_prefix"
    return False, b"", "missing_previous_prefix"


def capture(output: Path, device: str, bundle: str, interval: float) -> int:
    output.mkdir(parents=True, exist_ok=True)
    raw = output / "ios_snapshots"
    raw.mkdir(exist_ok=True)
    stop = False
    def stop_handler(_signum, _frame):
        nonlocal stop
        stop = True
    signal.signal(signal.SIGTERM, stop_handler)
    signal.signal(signal.SIGINT, stop_handler)
    manifest = {"schemaVersion": 1, "startedMonotonic": time.monotonic(), "snapshots": [], "errors": []}
    previous = None
    active_run_id = None
    count = 0
    stream = output / "ios_captured.log"
    with stream.open("wb") as dst:
        while True:
            final_snapshot = stop
            started = time.monotonic()
            count += 1
            files = {}
            for label, name in (("before", "ios_ble.log"), ("old", "ios_ble.old.log"), ("after", "ios_ble.log")):
                path = raw / f"{count:06d}_{label}.log"
                command = ["xcrun", "devicectl", "--timeout", "20", "device", "copy", "from", "--device", device,
                           "--domain-type", "appDataContainer", "--domain-identifier", bundle,
                           "--source", f"Documents/Logs/{name}", "--destination", str(path)]
                try:
                    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=25)
                    (raw / f"{count:06d}_{label}.stdout").write_bytes(result.stdout)
                    (raw / f"{count:06d}_{label}.stderr").write_bytes(result.stderr)
                    data = path.read_bytes() if result.returncode == 0 and path.is_file() else None
                    files[label] = {"file": str(path.relative_to(output)), "returncode": result.returncode,
                                    "sha256": digest(data) if data is not None else None,
                                    "size": len(data) if data is not None else None}
                except (OSError, subprocess.TimeoutExpired) as exc:
                    files[label] = {"file": str(path.relative_to(output)), "error": str(exc), "sha256": None}
            def data(label):
                item = files[label]
                return (output / item["file"]).read_bytes() if item.get("sha256") else None
            before, old, after = data("before"), data("old"), data("after")
            run_ids = re.findall(rb"\[RealtimeV4\] scheduled runId=(\S+)", after or b"")
            new_run_id = run_ids[-1].decode("ascii", errors="replace") if run_ids else None
            # The debug entry clears the old file before writing its scheduled
            # marker. A new marker visible in a complete current snapshot is a
            # new epoch; retain all preceding snapshots separately.
            new_epoch = bool(new_run_id and previous is not None and new_run_id != active_run_id)
            if new_run_id:
                active_run_id = new_run_id
            if new_epoch and after and before:
                ok, delta, reason = True, after, "run_boundary_reset"
            else:
                ok, delta, reason = continuity(previous, before or b"", old, after or b"")
            written = 0
            if ok:
                dst.write(delta)
                dst.flush()
                written = len(delta)
                previous = after
            else:
                manifest["errors"].append({"snapshot": count, "runId": active_run_id, "reason": reason})
                # Preserve the observable new epoch even though the gap cannot
                # be repaired; never call it a continuous or passing capture.
                if after:
                    dst.write(after)
                    dst.flush()
                    written = len(after)
                    previous = after
            ended = time.monotonic()
            manifest["snapshots"].append({"index": count, "startMonotonic": started, "endMonotonic": ended,
                                          "files": files, "continuity": reason, "ok": ok,
                                          "runId": active_run_id, "final": final_snapshot,
                                          "deltaBytes": written})
            manifest["updatedMonotonic"] = ended
            write_manifest(output, manifest)
            if final_snapshot:
                break
            deadline = ended + interval
            while not stop and time.monotonic() < deadline:
                time.sleep(max(0.0, min(0.25, deadline - time.monotonic())))
    manifest["endedMonotonic"] = time.monotonic()
    manifest["streamSha256"] = digest_file(stream)
    write_manifest(output, manifest)
    return 1 if manifest["errors"] else 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--device", required=True)
    parser.add_argument("--bundle", required=True)
    parser.add_argument("--interval", type=float, default=2.0)
    args = parser.parse_args()
    if args.interval <= 0:
        parser.error("interval must be positive")
    return capture(args.output_dir, args.device, args.bundle, args.interval)

if __name__ == "__main__":
    raise SystemExit(main())
