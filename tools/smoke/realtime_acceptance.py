#!/usr/bin/env python3
"""Strict V4 acceptance, separate from successful generation of a trace report."""
from __future__ import annotations

import argparse
import hashlib
import json
import plistlib
import re
from collections import deque
from pathlib import Path

from realtime_latency_report import analyze, discover_clock_sync, parse_trace_line, percentile

LIMITS = {"t0ToTrackPublishMs": 300, "t0ToCurrentLyricMs": 500,
          "t0ToCurrentWordMs": 500, "t0ToPreviewArtMs": 800, "t0ToHqArtMs": 2500}
CLOCK_MAX_AGE_MS = 150_000  # 120 s refresh plus bounded scheduling margin.
CLOCK_MAX_JUMP_MS = 100


def sha(path: Path) -> str:
    result = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def component(failures, missing, details=None):
    return {"status": "FAIL" if failures else "INCOMPLETE" if missing else "PASS",
            "failures": failures, "missing": missing, "details": details or {}}


def derived_report_matches(root, report, events):
    """Verify the report and JSONL derive from the captured run, not another run."""
    parsed, malformed, source_line = [], 0, 0
    try:
        for side, name in (("sony", "sony_run.log"), ("ios", "ios_run.log")):
            with (root / name).open(encoding="utf-8", errors="replace") as source:
                for line in source:
                    included = "[RealtimeTrace]" in line or (side == "ios" and "[ClockSync]" in line and "pong" in line)
                    if not included:
                        continue
                    source_line += 1
                    if "[RealtimeTrace]" not in line:
                        continue
                    event = parse_trace_line(line, source_line)
                    if event is None:
                        malformed += 1
                    else:
                        parsed.append(event)
        if [event.as_dict() for event in parsed] != events:
            return False
        trusted, offset = discover_clock_sync([root / "ios_run.log"])
        canonical = analyze(parsed, malformed=malformed, clock_trusted=trusted, sony_to_ios_offset_ms=offset)
        return all(report.get(key) == canonical.get(key) for key in
                   ("eventCount", "clock", "metrics", "samples", "t0Samples", "categories", "diagnostics"))
    except (OSError, ValueError, TypeError, KeyError):
        return False


def stream_window_matches(source, target, lo, hi):
    if not 0 <= lo < hi <= source.stat().st_size or target.stat().st_size != hi - lo:
        return False
    with source.open("rb") as left, target.open("rb") as right:
        left.seek(lo)
        remaining = hi - lo
        while remaining:
            block = left.read(min(1024 * 1024, remaining))
            if not block or block != right.read(len(block)):
                return False
            remaining -= len(block)
    return True


def line_boundaries_valid(source, lo, hi):
    with source.open("rb") as stream:
        if lo:
            stream.seek(lo - 1)
            if stream.read(1) != b"\n":
                return False
        stream.seek(hi - 1)
        return stream.read(1) == b"\n"


def validate_run_log_windows(root, host_id, run_id, mode):
    missing, failures = [], []
    marker = "RealtimeV4: host_measurement_start runId=" + host_id
    marker_count, active, sony_ok, pids = 0, False, True, set()
    with (root / "sony_full.log").open(errors="replace") as full, (root / "sony_run.log").open(errors="replace") as run:
        for line in full:
            if marker in line:
                marker_count += 1
                active = True
                continue
            if active:
                if run.readline() != line:
                    sony_ok = False
                match = re.search(r"^\S+\s+\S+\s+(\d+)\s+\d+\s+[VDIWEF]\s+[^\n]*\[RealtimeTrace\].*\bside=sony\b", line)
                if match:
                    pids.add(match.group(1))
        if run.read(1):
            sony_ok = False
    if marker_count != 1 or not sony_ok:
        missing.append("sony_run_marker_unverified")
    if len(pids) != 1:
        missing.append("sony_process_session_unverified")
    active, finished, ios_ok, all_ids, starts, ended, aborted = False, False, True, [], [], False, False
    scheduled_pattern = re.compile(r"\[RealtimeV4\] scheduled runId=(\S+).*mode=(\S+)")
    with (root / "ios_captured.log").open(errors="replace") as full, (root / "ios_run.log").open(errors="replace") as run:
        for line in full:
            scheduled = scheduled_pattern.search(line)
            if scheduled:
                if active:
                    active, finished = False, True
                if not finished and scheduled.group(1) == run_id:
                    active = True
                    if scheduled.group(2) != mode:
                        ios_ok = False
                if active or finished:
                    all_ids.append(scheduled.group(1))
            if active:
                if run.readline() != line:
                    ios_ok = False
                start = re.search(r"\[RealtimeV4\] start runId=(\S+)", line)
                if start:
                    starts.append(start.group(1))
                ended |= bool(re.search(r"\[RealtimeV4\] end runId=" + re.escape(str(run_id)) + r"\b", line))
                aborted |= bool(re.search(r"\[RealtimeV4\] abort runId=" + re.escape(str(run_id)), line))
        if run.read(1):
            ios_ok = False
    if not all_ids or not ios_ok:
        missing.append("ios_run_marker_unverified")
    if len(all_ids) > 1:
        failures.append("ios_run_restarted")
    if starts != [run_id] or aborted:
        missing.append("ios_run_start_or_abort")
    if mode != "natural" and not ended:
        missing.append("ios_run_end_missing")
    return missing, failures, all_ids


def scan_stability(lines):
    patterns = {
        "anr": re.compile(r"\bANR in (?:com\.example\.playeragent|com\.android\.bluetooth)\b", re.I),
        "bluetooth_death": re.compile(r"(?:Process\s+com\.android\.bluetooth\b.*has died|am_proc_died.*com\.android\.bluetooth)", re.I),
        "app_process_death": re.compile(r"(?:Process\s+com\.example\.playeragent\b.*has died|am_proc_died.*com\.example\.playeragent\b)", re.I),
        "hci_timeout": re.compile(r"\bhci_timeout_abort\b|\bHCI\b[^\n]*\btimeout\b", re.I),
        "l2cap_failure": re.compile(r"L2CAP[^\n]*(?:failure|failed|error)", re.I),
        "hard_reconnect": re.compile(r"\[BLE-Reconnect\] hard reconnect reason=.*manual=false"),
        "ios_termination": re.compile(r"(?:sonyMusic|IOSBleFeasibility)[^\n]*(?:EXC_CRASH|Jetsam|crash|terminated[^\n]*(?:crash|fault|jetsam))", re.I),
        "am_crash": re.compile(r"am_crash[^\n]*(?:com\.example\.playeragent|com\.android\.bluetooth)", re.I),
    }
    result, pending = {}, deque()
    def hit(name, evidence):
        item = result.setdefault(name, {"count": 0, "examples": []})
        item["count"] += 1
        if len(item["examples"]) < 20:
            item["examples"].append(evidence[:2048])
    for number, line in enumerate(lines):
        line = line.rstrip("\n")
        while pending and number - pending[0][0] > 8:
            pending.popleft()
        for name, pattern in patterns.items():
            if pattern.search(line):
                hit(name, line)
        if "FATAL EXCEPTION" in line or "Fatal signal" in line:
            pending.append((number, line))
        identity = re.search(
            r"(?:\bProcess:\s*|\bCmdline:\s*|>>>\s*)([A-Za-z0-9_.]+(?::[A-Za-z0-9_.]+)?)",
            line,
        )
        if identity:
            process = identity.group(1).split(":", 1)[0]
            if process in {"com.example.playeragent", "com.android.bluetooth"}:
                while pending:
                    _, beginning = pending.popleft()
                    hit("crash", beginning + "\n" + line)
            else:
                # Interleaved ordinary target logs are not crash identity, and
                # an explicit other process closes this pending fatal context.
                pending.clear()
    return result


def parse_run_operations(path, expected_run):
    """Bind each launch operation to only the intents before the next operation."""
    schedule = re.compile(r"\[RealtimeV4\] scheduled runId=(\S+) runs=(\d+) mode=(\S+)")
    command = re.compile(r"\[RealtimeV4\] command runId=(\S+) index=(\d+) cmd=(NEXT|PREVIOUS)")
    declared, rows, unbound = [], [], []
    with path.open(errors="replace") as source:
        for line in source:
            match = schedule.search(line)
            if match and match.group(1) == expected_run:
                declared.append((int(match.group(2)), match.group(3)))
            match = command.search(line)
            if match:
                rows.append({"runId": match.group(1), "index": int(match.group(2)),
                             "commandType": match.group(3), "intents": []})
            event = parse_trace_line(line)
            if event and event.side == "ios" and event.stage == "commandIntent" and event.command_type in {"NEXT", "PREVIOUS"}:
                intent = {"commandSeq": event.command_seq, "commandType": event.command_type,
                          "handoffId": event.handoff_id}
                (rows[-1]["intents"] if rows else unbound).append(intent)
    return declared, rows, unbound


def validate_builds(manifest, root, devices=None, commit=None):
    missing = []
    source = (manifest or {}).get("source", {})
    try:
        if not commit or source["head"] != commit:
            raise ValueError("source head mismatch")
        for prefix in ("dirtyDiff", "sourceHashes", "buildLog"):
            path = root / source[prefix + "File"]
            if sha(path) != source[prefix + "Sha256"]:
                raise ValueError("source evidence hash")
        hashes = json.loads((root / source["sourceHashesFile"]).read_text())["files"]
        if not hashes:
            raise ValueError("empty source hashes")
        repository = Path(__file__).resolve().parents[2]
        if any(sha(repository / filename) != digest for filename, digest in hashes.items()):
            raise ValueError("source changed after build")
    except (KeyError, OSError, TypeError, ValueError):
        missing.append("build_source_unverified")
    for side in ("ios", "sony"):
        build = (manifest or {}).get(side, {})
        try:
            artifact = root / build["artifact"]
            actual = sha(artifact)
            if actual != build["sha256"]:
                raise ValueError("artifact hash")
            if not devices or build["deviceId"] != devices.get(side):
                raise ValueError("installed device differs from run")
            install = root / build["installReceipt"]
            if sha(install) != build["installReceiptSha256"]:
                raise ValueError("install receipt hash")
            if side == "sony":
                installed_apk = root / build["installedApk"]
                info = root / build["packageInfo"]
                if (sha(installed_apk) != build["installedApkSha256"] or sha(installed_apk) != actual
                        or sha(info) != build["packageInfoSha256"]
                        or "Success" not in install.read_text(errors="replace")
                        or build["packageName"] not in info.read_text(errors="replace")
                        or not re.search(r"\bversion(?:Code|Name)=" + re.escape(str(build["version"])) + r"(?:\s|$)", info.read_text(errors="replace"))
                        or not build["deviceId"]):
                    raise ValueError("android install chain")
            else:
                plist = root / build["infoPlist"]
                codesign = root / build["codesignEvidence"]
                apps = root / build["appsReceipt"]
                if (sha(plist) != build["infoPlistSha256"] or sha(codesign) != build["codesignEvidenceSha256"]
                        or sha(apps) != build["appsReceiptSha256"]):
                    raise ValueError("ios evidence hash")
                properties = plistlib.loads(plist.read_bytes())
                app_path = (root / build["appPath"]).resolve()
                if app_path not in artifact.resolve().parents or app_path not in plist.resolve().parents:
                    raise ValueError("app artifact path")
                if (str(properties.get("CFBundleVersion")) != str(build["version"])
                        or properties.get("CFBundleIdentifier") != build["bundleIdentifier"]
                        or build["cdHash"].lower() not in codesign.read_text(errors="replace").lower()):
                    raise ValueError("ios app identity")
                receipt = json.loads(install.read_text())
                query = json.loads(apps.read_text())
                install_info = receipt["info"]
                query_info, result = query["info"], query["result"]
                args = install_info["arguments"]
                app_args = [Path(arg).resolve() for arg in args if str(arg).endswith(".app")]
                matched = [item for item in result["apps"] if item.get("bundleIdentifier") == build["bundleIdentifier"]
                           and str(item.get("bundleVersion")) == str(build["version"])]
                if (install_info.get("outcome") != "success" or "install" not in install_info.get("commandType", "")
                        or build["deviceId"] not in args or app_path not in app_args
                        or receipt.get("result", {}).get("deviceIdentifier") != build["deviceId"]
                        or query_info.get("outcome") != "success"
                        or query_info.get("commandType") != "devicectl.device.info.apps"
                        or build["deviceId"] not in query_info.get("arguments", [])
                        or build["bundleIdentifier"] not in query_info.get("arguments", [])
                        or result.get("deviceIdentifier") != build["deviceId"]
                        or result.get("matchingBundleIdentifier") != build["bundleIdentifier"] or len(matched) != 1):
                    raise ValueError("ios install chain")
        except (KeyError, OSError, TypeError, ValueError, json.JSONDecodeError, plistlib.InvalidFileException):
            missing.append(f"{side}_installed_build_unverified")
    return missing


def clock_windows(log_text):
    """Build bounded, locally timed calibration windows; never backfill."""
    points = []
    for line in log_text.splitlines() if isinstance(log_text, str) else log_text:
        if "[ClockSync] reset " in line:
            stamp = re.search(r"\bmonoMs=(\d+)\b", line)
            if stamp is None:
                return []
            point = {"start": int(stamp.group(1)), "offset": points[-1]["offset"] if points else 0,
                     "samples": 0, "valid": False}
            if points and point["start"] <= points[-1]["start"]:
                return []
            points.append(point)
            continue
        if "[ClockSync] pong " not in line:
            continue
        fields = dict(re.findall(r"\b(monoMs|offsetMs|samples|confident)=(-?\w+)", line))
        try:
            if set(fields) != {"monoMs", "offsetMs", "samples", "confident"}:
                return []  # An unlocated calibration transition is unsafe.
            point = {"start": int(fields["monoMs"]), "offset": int(fields["offsetMs"]),
                     "samples": int(fields["samples"]), "valid": fields["confident"].lower() == "true" and int(fields["samples"]) >= 3}
            if points and point["start"] <= points[-1]["start"]:
                return []
            points.append(point)
        except ValueError:
            return []
    for index, point in enumerate(points):
        point["end"] = min(point["start"] + CLOCK_MAX_AGE_MS,
                           points[index + 1]["start"] if index + 1 < len(points) else point["start"] + CLOCK_MAX_AGE_MS)
    for previous, current in zip(points, points[1:]):
        if (abs(current["offset"] - previous["offset"]) > CLOCK_MAX_JUMP_MS
                or current["samples"] < previous["samples"]):
            previous["valid"] = False
            current["valid"] = False
    return points


def clock_for_pair(ios_ms, sony_ms, windows):
    for window in windows:
        if not window["valid"] or not window["start"] <= ios_ms < window["end"]:
            continue
        mapped = sony_ms + window["offset"]
        if window["start"] <= mapped < window["end"]:
            return mapped
    return None


def natural_t0_samples(valid, events, windows):
    rows = []
    stages = {"t0ToTrackPublishMs": {"trackIdentityAccepted"},
              "t0ToCurrentLyricMs": {"lyricCurrentLinePublished"},
              "t0ToCurrentWordMs": {"currentWordPublished"},
              "t0ToPreviewArtMs": {"previewPublished", "hqPublished"},
              "t0ToHqArtMs": {"hqPublished"}}
    for sample in valid:
        anchor = [event for event in events if event.get("side") == "sony"
                  and event.get("stage") == "trackIdentityAccepted"
                  and event.get("handoffId") == sample["handoffId"]
                  and event.get("trackId") and isinstance(event.get("generation"), int)
                  and event["generation"] > 0]
        if len(anchor) != 1:
            continue
        sony = anchor[0]
        identities = [event for event in events if event.get("side") == "ios"
                      and event.get("stage") == "trackIdentityAccepted" and event.get("result") == "changed"
                      and str(event.get("trackId") or "")[:12] == sony["trackId"][:12]
                      and event.get("generation") == sony["generation"]]
        if len(identities) != 1:
            continue
        identity = identities[0]
        t0 = clock_for_pair(identity["monoMs"], sony["monoMs"], windows)
        if t0 is None or not 0 <= identity["monoMs"] - t0 <= 15_000:
            continue
        next_identity = min((event["monoMs"] for event in events if event.get("side") == "ios"
                             and event.get("stage") == "trackIdentityAccepted" and event.get("result") == "changed"
                             and event["monoMs"] > identity["monoMs"]), default=None)
        row = {"handoffId": sample["handoffId"], "trigger": "automatic", "t0MonoMs": t0}
        for name, acceptable in stages.items():
            matches = [event["monoMs"] - t0 for event in events if event.get("side") == "ios"
                       and event.get("stage") in acceptable and event["monoMs"] >= identity["monoMs"]
                       and (next_identity is None or event["monoMs"] < next_identity)
                       and str(event.get("trackId") or "")[:12] == sony["trackId"][:12]
                       and event.get("generation") == sony["generation"]
                       and (event["stage"] != "lyricCurrentLinePublished" or event.get("result") == "published")
                       and (event["stage"] != "trackIdentityAccepted" or event.get("result") == "changed")]
            row[name] = min(matches, default=None)
        rows.append(row)
    return rows


def qualified_cross_device_samples(valid, events, windows):
    """Measure each handoff directly; no cross-device wall-clock arithmetic."""
    specs = {
        "commandEnqueueToSonyReceiveMs": ("ios", "commandEnqueued", "sony", "commandReceived", 200),
        "wordEligibleToPublishMs": ("sony", "currentWordEligible", "ios", "currentWordPublished", 250),
        "lyricReadyToFullLyricsPublishMs": ("sony", "lyricReady", "ios", "fullLyricsPublished", 2000),
    }
    owners = {}
    for sample in valid:
        for event in events:
            if event.get("handoffId") == sample.get("handoffId") and event.get("trackId") and isinstance(event.get("generation"), int) and event["generation"] > 0:
                identity = (event["trackId"][:12], event["generation"])
                owners.setdefault(identity, set()).add(sample["handoffId"])
    result = {}
    for name, (start_side, start_stage, end_side, end_stage, limit) in specs.items():
        rows = []
        for sample in valid:
            handoff = sample.get("handoffId")
            linked = [event for event in events if event.get("handoffId") == handoff]
            seq_match = re.fullmatch(r"command-(\d+)", handoff or "")
            seq = int(seq_match.group(1)) if seq_match else None
            commands = {event.get("commandType") for event in linked if event.get("commandType") in {"NEXT", "PREVIOUS"}}
            identities = {(event.get("trackId", "")[:12], event.get("generation"))
                          for event in linked if event.get("trackId") and isinstance(event.get("generation"), int)
                          and event["generation"] > 0}
            def matching(event):
                identity = (str(event.get("trackId") or "")[:12], event.get("generation"))
                if event.get("handoffId") == handoff:
                    return not event.get("trackId") or event.get("generation") is None or identity in identities
                if event.get("handoffId"):
                    return False
                if name == "commandEnqueueToSonyReceiveMs" and seq is not None:
                    return event.get("commandSeq") == seq and len(commands) == 1 and event.get("commandType") in commands
                return len(identities) == 1 and identity in identities and len(owners.get(identity, ())) == 1
            starts = [event for event in events if event.get("side") == start_side
                      and event.get("stage") == start_stage and matching(event)
                      and (start_stage != "lyricReady" or event.get("result") == "ready")]
            ends = [event for event in events if event.get("side") == end_side
                    and event.get("stage") == end_stage and matching(event)]
            value = None
            if starts and ends:
                start = min(event["monoMs"] for event in starts)
                end = min(event["monoMs"] for event in ends)
                if start_side == "sony":
                    start = clock_for_pair(end, start, windows)
                if end_side == "sony":
                    end = clock_for_pair(start, end, windows)
                if start is not None and end is not None and end >= start:
                    value = end - start
            labels = set(sample.get("classifications", []))
            ineligible = not starts and ((name == "wordEligibleToPublishMs" and "LINE_ONLY_LYRICS" in labels)
                                         or (name == "lyricReadyToFullLyricsPublishMs" and "NO_LYRICS" in labels))
            rows.append({"handoffId": handoff, "durationMs": value, "ineligible": ineligible,
                         "startCount": len(starts), "endCount": len(ends)})
        values = [row["durationMs"] for row in rows if row["durationMs"] is not None]
        result[name] = {"count": len(values), "missing": sum(row["durationMs"] is None and not row["ineligible"] for row in rows),
                        "ineligible": sum(row["ineligible"] for row in rows),
                        "p95": percentile(values, 95), "limitMs": limit, "samples": rows}
    return result


def assess(report, capture, mode, installed=None, root=Path("."), system_logs=None, events=None, operations=None):
    failures, missing = [], []
    events = events or []
    if not capture:
        missing.append("capture_manifest")
    else:
        snapshots = capture.get("snapshots", [])
        if not snapshots or not capture.get("endedMonotonic"):
            missing.append("collector_not_completed")
        run_ids = capture.get("iosRunIds", [])
        run_id = run_ids[0] if run_ids else None
        if len(run_ids) > 1:
            failures.append("ios_run_restarted")
        try:
            context_path = root / "launch_context.json"
            context = json.loads(context_path.read_text())
            precheck = root / "ios_ble_precheck_ios.log"
            ids = re.findall(r"\[RealtimeV4\] scheduled runId=(\S+)", precheck.read_text(errors="replace"))
            new_ids = [value for value in ids if value != context.get("baselineRunId")]
            baseline = next(snap for snap in snapshots if snap["index"] == context["baselineIndex"])
            if (sha(context_path) != capture["launchContextSha256"] or sha(precheck) != context["precheckSha256"]
                    or not new_ids or context.get("expectedRunId") != new_ids[0] or run_id != new_ids[0]
                    or context.get("precheckRunIds") != new_ids or len(new_ids) != 1
                    or baseline.get("runId") != context.get("baselineRunId")
                    or baseline["endMonotonic"] > capture["runStartedMonotonic"]):
                missing.append("ios_launch_run_binding_unverified")
            baseline_index = context["baselineIndex"]
        except (OSError, KeyError, TypeError, ValueError, StopIteration):
            missing.append("ios_launch_run_binding_unverified")
            baseline_index = 0
        first_run = next((i for i, snap in enumerate(snapshots) if snap.get("runId") == run_id and snap.get("index", 0) > baseline_index), None) if run_id else None
        later = next((i for i in range(first_run + 1, len(snapshots)) if snapshots[i].get("runId") != run_id), len(snapshots)) if first_run is not None else 0
        run_snapshots = snapshots[first_run:later] if first_run is not None else []
        if first_run is not None and later < len(snapshots):
            failures.append("ios_process_session_changed")
        if first_run is None:
            missing.append("ios_run_snapshot_missing")
        if any(error.get("snapshot", 0) >= (run_snapshots[0]["index"] if run_snapshots else 0)
               for error in capture.get("errors", [])):
            missing.append("ios_capture_discontinuity")
        for snapshot in run_snapshots:
            if snapshot.get("runId") != run_id:
                missing.append("ios_process_session_changed")
            if not snapshot.get("ok"):
                missing.append("snapshot_failed")
            if snapshot.get("endMonotonic", 0) - snapshot.get("startMonotonic", 0) > 60:
                missing.append("snapshot_long_gap")
            for name in ("before", "old", "after"):
                item = snapshot.get("files", {}).get(name, {})
                if name == "old" and item.get("sha256") is None:
                    continue
                try:
                    if sha(root / item["file"]) != item["sha256"]:
                        missing.append("snapshot_hash_mismatch")
                except (KeyError, OSError, TypeError):
                    missing.append("snapshot_missing")
        for prior, current in zip(run_snapshots, run_snapshots[1:]):
            if current.get("startMonotonic", 0) - prior.get("endMonotonic", 0) > 60:
                missing.append("snapshot_long_gap")
        last_growth = None
        for snapshot in run_snapshots:
            if snapshot.get("deltaBytes", 0) > 0:
                last_growth = snapshot.get("endMonotonic")
            elif last_growth is None or snapshot.get("endMonotonic", 0) - last_growth > 60:
                missing.append("ios_log_long_empty_window")
        if mode == "natural":
            observed = capture.get("observationStartedMonotonic")
            if not isinstance(observed, (int, float)) or observed < capture.get("runStartedMonotonic", 0) or capture.get("runEndedMonotonic", 0) - observed < 7200:
                missing.append("natural_window_under_7200_seconds")
        start, end = capture.get("runStartedMonotonic"), capture.get("runEndedMonotonic")
        if not (isinstance(start, (int, float)) and isinstance(end, (int, float)) and
                capture.get("startedMonotonic", start + 1) <= start <= end <= capture.get("endedMonotonic", end - 1)):
            missing.append("collector_does_not_cover_run_window")
        if not run_snapshots or not run_snapshots[-1].get("final") or run_snapshots[-1].get("startMonotonic", 0) < (end or 0):
            missing.append("final_snapshot_after_run_missing")
        for side, filename, runfile in (("sony", "sony_full.log", "sony_run.log"),
                                        ("ios", "ios_captured.log", "ios_run.log")):
            collector = capture.get(side + "Collector", {})
            try:
                if (not collector.get("aliveAtStop") or sha(root / filename) != collector["sha256"]
                        or sha(root / runfile) != collector["runSha256"]
                        or collector.get("exitCode") not in ((0, 1) if side == "ios" else (0, 130, 143, -15))):
                    missing.append(side + "_collector_unverified")
                if side == "ios" and collector.get("exitCode") != 0:
                    missing.append("ios_collector_failed")
            except (OSError, KeyError, TypeError):
                missing.append(side + "_collector_unverified")
        try:
            monitor = root / "collector_monitor.log"
            if sha(monitor) != capture["sonyCollector"]["monitorSha256"]:
                missing.append("sony_monitor_hash_mismatch")
            points = [tuple(map(int, line.split())) for line in monitor.read_text().splitlines()]
            if not points or any(len(point) != 2 for point in points):
                raise ValueError("invalid monitor")
            if points[0][0] > 10 or points[-1][1] > (root / "sony_full.log").stat().st_size:
                missing.append("sony_log_monitor_gap")
            if mode == "natural" and points[-1][0] < 7190:
                missing.append("sony_log_monitor_gap")
            last_time, last_size = points[0]
            last_growth = last_time
            for observed, size in points[1:]:
                if observed <= last_time or observed - last_time > 60 or size < last_size:
                    missing.append("sony_log_monitor_gap")
                if size > last_size:
                    last_growth = observed
                elif observed - last_growth > 60:
                    missing.append("sony_log_long_empty_window")
                last_time, last_size = observed, size
        except (OSError, KeyError, TypeError, ValueError):
            missing.append("sony_log_monitor_missing")
        try:
            log_missing, log_failures, actual_run_ids = validate_run_log_windows(root, capture["hostRunId"], run_id, mode)
            missing.extend(log_missing)
            failures.extend(log_failures)
            if actual_run_ids != run_ids:
                missing.append("ios_run_identity_manifest_mismatch")
            for side in ("ios", "sony"):
                clocks = [event.get("monoMs") for event in events if event.get("side") == side and isinstance(event.get("monoMs"), int)]
                if not clocks or any(b + 1000 < a for a, b in zip(clocks, clocks[1:])):
                    missing.append(side + "_monotonic_session_unverified")
            if not derived_report_matches(root, report, events):
                missing.append("report_trace_source_mismatch")
        except (OSError, KeyError, IndexError, TypeError):
            missing.append("run_and_session_boundaries_unverified")
    missing.extend(validate_builds(installed, root, report.get("devices"), report.get("basicInfo", {}).get("commit")))
    capture_result = component(failures, sorted(set(missing)))

    samples = report.get("samples", [])
    expected = {"next": "IOS_NEXT", "previous": "IOS_PREVIOUS",
                "auto": "SONY_MEDIA_SESSION_NEXT", "natural": "NATURAL_AUTOPLAY"}.get(mode)
    selected = [s for s in samples if not expected or s.get("triggerType") == expected]
    valid = [s for s in selected if s.get("handoffId") and "COMPLETE" in s.get("classifications", [])
             and "FAILED" not in s.get("classifications", [])
             and "TRACE_INCOMPLETE" not in s.get("classifications", [])]
    trace_missing = []
    if len({s["handoffId"] for s in valid}) != len(valid):
        trace_missing.append("duplicate_sample_identity")
        valid = list({s["handoffId"]: s for s in valid}.values())
    if mode in {"next", "previous", "auto"} and len(valid) < 30:
        trace_missing.append("fewer_than_30_valid_samples")
    if not samples:
        trace_missing.append("no_samples")
    if report.get("diagnostics", {}).get("malformed", 0):
        trace_missing.append("malformed_trace_events")
    if mode in {"next", "previous", "auto", "natural"} and any(
            len({"LYRIC_CACHE_HIT", "LYRIC_CACHE_MISS"} & set(s.get("classifications", []))) != 1 for s in valid):
        trace_missing.append("cache_state_unclassified")
    if mode in {"natural", "auto"} and any(s.get("triggerType") in (None, "UNKNOWN") for s in samples):
        trace_missing.append("trigger_identity_unclassified")
    trace_failures = []
    if any("FAILED" in sample.get("classifications", []) for sample in selected):
        trace_failures.append("failed_attempt")
    if mode != "fast" and len(valid) != len(selected):
        trace_missing.append("attempts_incomplete")
    trace_details = {"attempts": len(selected), "valid": len(valid), "invalid": len(selected) - len(valid)}
    if mode in {"next", "previous", "fast"}:
        try:
            run_id = (capture or {}).get("iosRunIds", [None])[0]
            declared, rows, unbound = parse_run_operations(root / "ios_run.log", run_id)
            scenario = report.get("scenario", {})
            requested = scenario.get("runs")
            if not isinstance(requested, int) or requested < 1 or declared != [(requested, mode)] or scenario.get("mode") != mode:
                trace_missing.append("scheduled_operation_count_unverified")
                requested = requested if isinstance(requested, int) and requested > 0 else 0
            expected_commands = ["NEXT" if mode == "next" or (mode == "fast" and index % 2) else "PREVIOUS"
                                 for index in range(1, requested + 1)]
            if (len(rows) != requested or [row["index"] for row in rows] != list(range(1, requested + 1))
                    or [row["commandType"] for row in rows] != expected_commands
                    or any(row["runId"] != run_id for row in rows)):
                trace_missing.append("scheduled_operations_missing_or_mismatched")
            if unbound or any(len(row["intents"]) != 1 or row["intents"][0]["commandType"] != row["commandType"]
                              or row["intents"][0]["commandSeq"] is None or not row["intents"][0]["handoffId"] for row in rows):
                trace_missing.append("operation_intents_missing_or_ambiguous")
            intents = [row["intents"][0] for row in rows if len(row["intents"]) == 1]
            if len({(intent["commandSeq"], intent["commandType"]) for intent in intents}) != len(intents):
                trace_failures.append("duplicate_operation_intent_identity")
            if mode != "fast" and ({intent["handoffId"] for intent in intents} !=
                                   {sample.get("handoffId") for sample in selected} or len(selected) != requested):
                trace_missing.append("operation_samples_missing_or_ambiguous")
            trace_details["requestedOperations"] = requested
            trace_details["operationRows"] = rows
            trace_details["unboundIntents"] = unbound
        except (OSError, IndexError, TypeError):
            trace_missing.append("scheduled_operation_count_unverified")
    if mode == "natural" and any(e.get("stage") in {"commandIntent", "commandReceived"}
                                 and e.get("commandType") in {"NEXT", "PREVIOUS"} for e in events):
        trace_missing.append("natural_window_contains_external_control")
    if mode == "fast":
        operations = operations or []
        try:
            pattern = re.compile(r"\[RealtimeV4\] command runId=(\S+) index=(\d+) cmd=(NEXT|PREVIOUS)")
            with (root / "ios_run.log").open(errors="replace") as source:
                derived_operations = [(run, int(index), command) for line in source
                                      for run, index, command in pattern.findall(line)]
            if operations != derived_operations:
                trace_missing.append("operation_source_mismatch")
            operations = derived_operations
        except OSError:
            pass
        intents = [e for e in events if e.get("side") == "ios" and e.get("stage") == "commandIntent"
                   and e.get("commandType") in {"NEXT", "PREVIOUS"}]
        trace_details["operations"] = len(operations)
        trace_details["intents"] = len(intents)
        indexes = [item[1] for item in operations]
        trace_details["missingOperationIndexes"] = [index for index in range(1, 101) if index not in indexes]
        trace_details["duplicateOperationIndexes"] = sorted({index for index in indexes if indexes.count(index) > 1})
        if len(operations) != 100 or [x[1] for x in operations] != list(range(1, 101)) or any(
                cmd != ("NEXT" if index % 2 else "PREVIOUS") for _, index, cmd in operations):
            trace_missing.append("100_alternating_operations_not_proven")
        if len({x[0] for x in operations}) != 1:
            trace_missing.append("operation_run_id_ambiguous")
        if len(intents) != 100:
            trace_missing.append("100_command_intents_not_proven")
        if len(intents) != len(operations) or any(
                event.get("commandType") != operation[2] for event, operation in zip(intents, operations)):
            trace_missing.append("operation_intent_correlation_unproven")
        rows = []
        for intent in intents:
            seq, command = intent.get("commandSeq"), intent.get("commandType")
            row = {"commandSeq": seq, "commandType": command, "missing": []}
            if seq is None:
                row["missing"].append("command_identity")
            for side, stage, label in (("ios", "commandWriteStart", "send"),
                                       ("sony", "commandReceived", "receive"),
                                       ("sony", "mediaControlDispatchStart", "execute")):
                count = sum(1 for e in events if seq is not None and e.get("side") == side
                            and e.get("stage") == stage and e.get("commandSeq") == seq
                            and e.get("commandType") == command)
                row[label] = count
                if count == 0:
                    row["missing"].append(label)
                if count > 1:
                    trace_failures.append(f"duplicate_{label}_{seq}")
            if row["missing"]:
                trace_missing.extend(f"command_{seq}_{label}_missing" for label in row["missing"])
            callbacks = [e for e in events if e.get("side") == "ios" and e.get("commandSeq") == seq
                         and e.get("commandType") == command and e.get("stage") == "commandWriteCallback"]
            timeouts = [e for e in events if e.get("side") == "ios" and e.get("commandSeq") == seq
                        and e.get("commandType") == command and e.get("stage") == "commandWriteTimeout"]
            row["callback"] = len(callbacks)
            row["writeTimeout"] = len(timeouts)
            if not callbacks:
                row["missing"].append("write_callback")
                trace_missing.append(f"command_{seq}_write_callback_missing")
            if timeouts or any(e.get("result") in {"failure", "failed", "timeout"} for e in callbacks):
                trace_failures.append(f"command_{seq}_write_anomaly")
            rows.append(row)
        if len({(e.get("commandSeq"), e.get("commandType")) for e in intents}) != len(intents):
            trace_failures.append("duplicate_command_identity")
        trace_details["commands"] = rows
    trace_result = component(trace_failures, trace_missing, trace_details)

    diag, cats = report.get("diagnostics", {}), report.get("categories", {})
    correctness_names = ("duplicate_control", "control_reconnect_resend", "wrong_current_word", "wrong_artwork", "wrong_lyrics", "stale_generation")
    bad = {name: diag.get(name, 0) for name in correctness_names if diag.get(name, 0)}
    if cats.get("STALE_CONTENT", 0):
        bad["STALE_CONTENT"] = cats["STALE_CONTENT"]
    correctness_result = component([f"{key}={value}" for key, value in bad.items()],
                                   [] if report.get("eventCount", 0) and capture_result["status"] == "PASS" else ["complete_run_trace_required"], bad)

    slo_fail, slo_missing, slo_details = [], [], {}
    # Only T0 samples explicitly tied to a valid manual command count.
    valid_ids = {s.get("handoffId") for s in valid if s.get("handoffId")}
    def sample_id(sample):
        return sample.get("handoffId") or (f"command-{sample.get('commandSeq')}" if sample.get("commandSeq") is not None else None)
    try:
        with (root / "ios_run.log").open(errors="replace") as source:
            windows = clock_windows(source)
    except OSError:
        windows = []
    t0 = [s for s in report.get("t0Samples", []) if sample_id(s) in valid_ids
          and (mode not in {"next", "previous"} or s.get("commandType") == expected.removeprefix("IOS_"))
          and (mode not in {"auto", "natural"} or s.get("trigger") == "automatic")]
    if mode in {"auto", "natural"}:
        t0 = natural_t0_samples(valid, events, windows)
    if len({sample_id(s) for s in t0}) != len(t0):
        slo_missing.append("duplicate_t0_sample_identity")
        t0 = list({sample_id(s): s for s in t0}.values())
    if mode in {"next", "previous", "auto", "natural"}:
        classifications = {s["handoffId"]: set(s.get("classifications", [])) for s in valid}
        cross = qualified_cross_device_samples(valid, events, windows)
        word_starts = {row["handoffId"]: row["startCount"] for row in cross["wordEligibleToPublishMs"]["samples"]}
        word_classes = {}
        for sample in valid:
            labels = classifications[sample["handoffId"]]
            word_events = [event for event in events if event.get("side") == "sony"
                           and event.get("handoffId") == sample["handoffId"]
                           and event.get("stage") in {"currentWordNotEligible", "currentWordEligibilityEvaluated", "currentWordEligible"}
                           and isinstance(event.get("monoMs"), int)]
            eligible = min((event["monoMs"] for event in word_events if event["stage"] == "currentWordEligible"), default=None)
            intro_before = any(event.get("reason") == "INTRO_WAIT" and
                               (eligible is None or event["monoMs"] <= eligible) for event in word_events)
            category = ("intro_wait" if intro_before else
                        "line_only" if "LINE_ONLY_LYRICS" in labels else
                        "no_lyrics" if "NO_LYRICS" in labels else
                        "cache_wait" if "WAITING_QQMUSIC_CACHE" in labels else
                        "immediately_eligible" if "WORD_TIMING_AVAILABLE" in labels and eligible is not None and word_starts.get(sample["handoffId"]) else
                        "unclassified")
            word_classes[sample["handoffId"]] = category
        slo_details["wordEligibility"] = {category: sum(value == category for value in word_classes.values())
                                           for category in ("immediately_eligible", "intro_wait", "line_only", "no_lyrics", "cache_wait", "unclassified")}
        if any(value == "unclassified" for value in word_classes.values()):
            slo_missing.append("first_word_eligibility_unclassified")
        for name, limit in LIMITS.items():
            candidates = [s for s in t0 if name != "t0ToCurrentWordMs" or word_classes.get(sample_id(s)) == "immediately_eligible"]
            values = [s[name] for s in candidates if isinstance(s.get(name), (float, int)) and not isinstance(s[name], bool)]
            p95 = percentile(values, 95)
            groups = {}
            resource = ("RUNTIME_CACHE" if name == "t0ToTrackPublishMs" else
                        "LYRIC_CACHE" if name in {"t0ToCurrentLyricMs", "t0ToCurrentWordMs"} else "PREVIEW_CACHE")
            unclassified = [s for s in candidates if len({resource + "_HIT", resource + "_MISS"} &
                            classifications.get(sample_id(s), set())) != 1]
            if unclassified:
                slo_missing.append(f"{name}_cache_state_unclassified")
            for cache_state, suffix in (("warm", "HIT"), ("cold", "MISS")):
                group = [s for s in candidates if s not in unclassified and
                         resource + "_" + suffix in classifications.get(sample_id(s), set())]
                group_values = [s[name] for s in group if isinstance(s.get(name), (float, int))
                                and not isinstance(s[name], bool)]
                group_p95 = percentile(group_values, 95)
                groups[cache_state] = {"count": len(group_values), "p95": group_p95}
                if group_p95 is not None and group_p95 > limit:
                    slo_fail.append(f"{name}_{cache_state}_exceeds_{limit}ms")
                if len(group_values) < 30:
                    slo_missing.append(f"{name}_{cache_state}_insufficient_samples")
            slo_details[name] = {"count": len(values), "p95": p95, "limitMs": limit,
                                 "missing": len(candidates) - len(values), "cacheResource": resource,
                                 "cacheUnclassified": len(unclassified), "cacheGroups": groups}
            if p95 is not None and p95 > limit:
                slo_fail.append(f"{name}_exceeds_{limit}ms")
            if len(values) < 30 or len(values) != len(candidates) or (name != "t0ToCurrentWordMs" and len(candidates) != len(valid)):
                slo_missing.append(f"{name}_insufficient_samples")
        for name, detail in cross.items():
            if name == "commandEnqueueToSonyReceiveMs" and mode in {"auto", "natural"}:
                continue
            slo_details[name] = detail
            if detail["p95"] is not None and detail["p95"] > detail["limitMs"]:
                slo_fail.append(f"{name}_exceeds_{detail['limitMs']}ms")
            if detail["count"] < 30 or detail["missing"]:
                slo_missing.append(f"{name}_insufficient_samples")
    else:
        slo_missing.append("scenario_slo_not_qualified")
    slo_result = component(slo_fail, slo_missing, slo_details)

    stability_fail, stability_missing = [], []
    def source_lines():
        available = False
        for filename in ("sony_run.log", "ios_run.log", "ios_system_run.log"):
            path = root / filename
            if path.exists():
                available = True
                with path.open(errors="replace") as source:
                    yield from source
        if not available and isinstance(system_logs, str):
            yield from system_logs.splitlines()
    stability_evidence = scan_stability(source_lines())
    stability_fail.extend(f"{name}={item['count']}" for name, item in stability_evidence.items())
    ios_system = (capture or {}).get("iosSystemCollector", {})
    try:
        system_file = root / ios_system["file"]
        run_file = root / ios_system["runFile"]
        details_file = root / ios_system["deviceDetailsFile"]
        details = json.loads(details_file.read_text())
        lo, hi = ios_system["startOffset"], ios_system["endOffset"]
        argv = ios_system["argv"]
        ios_system_valid = (ios_system.get("aliveAtStop") is True
                            and ios_system.get("exitCode") in (0, 130, 143, -15)
                            and sha(system_file) == ios_system["sha256"]
                            and sha(run_file) == ios_system["runSha256"]
                            and stream_window_matches(system_file, run_file, lo, hi)
                            and ios_system.get("boundaryComplete") is True
                            and lo <= ios_system["observedStartOffset"] <= ios_system["runEndOffset"] <= ios_system["observedEndOffset"] <= hi
                            and line_boundaries_valid(system_file, lo, hi)
                            and ios_system["runFile"] == "ios_system_run.log"
                            and sha(details_file) == ios_system["deviceDetailsSha256"]
                            and details["info"]["outcome"] == "success"
                            and details["info"]["commandType"] == "devicectl.device.info.details"
                            and details["result"]["identifier"] == report.get("devices", {}).get("ios")
                            and details["result"]["hardwareProperties"]["udid"] == ios_system["udid"]
                            and len(argv) == 3 and argv[1:] == ["-u", ios_system["udid"]]
                            and sha(Path(argv[0])) == ios_system["executableSha256"]
                            and ios_system["startedMonotonic"] <= capture["runStartedMonotonic"]
                            and ios_system["endedMonotonic"] >= capture["runEndedMonotonic"])
    except (KeyError, OSError, TypeError, ValueError):
        ios_system_valid = False
    if not system_logs or capture_result["status"] != "PASS" or not ios_system_valid:
        stability_missing.append("complete_both_device_system_logs_required")
    stability_result = component(stability_fail, stability_missing, stability_evidence)
    sections = {"capture": capture_result, "trace": trace_result, "correctness": correctness_result,
                "slo": slo_result, "stability": stability_result}
    status = "FAIL" if any(x["status"] == "FAIL" for x in sections.values()) else (
        "INCOMPLETE" if any(x["status"] == "INCOMPLETE" for x in sections.values()) else "PASS")
    return {"schemaVersion": 1, "mode": mode, **sections, "overall": status,
            "notAssessed": ["dual_controller", "energy_ab", "cpu_memory", "first_word_vs_steady_state"]}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--capture", type=Path)
    parser.add_argument("--installed-builds", type=Path)
    parser.add_argument("--system-log", type=Path, action="append", default=[])
    parser.add_argument("--raw-events", type=Path)
    parser.add_argument("--ios-log", type=Path)
    parser.add_argument("--mode", choices=["next", "previous", "fast", "auto", "natural"], required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    def read(path):
        return json.loads(path.read_text()) if path and path.exists() else None
    logs = bool(args.system_log)
    events = []
    if args.raw_events and args.raw_events.exists():
        with args.raw_events.open() as source:
            events = [json.loads(line) for line in source if line.strip()]
    operation_pattern = re.compile(r"\[RealtimeV4\] command runId=(\S+) index=(\d+) cmd=(NEXT|PREVIOUS)")
    operations = []
    if args.ios_log and args.ios_log.exists():
        with args.ios_log.open(errors="replace") as source:
            for line in source:
                operations.extend((run, int(index), command) for run, index, command in operation_pattern.findall(line))
    result = assess(read(args.report) or {}, read(args.capture), args.mode,
                    read(args.installed_builds), args.report.parent, logs, events, operations)
    args.output.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n")
    return {"PASS": 0, "FAIL": 1, "INCOMPLETE": 2}[result["overall"]]

if __name__ == "__main__":
    raise SystemExit(main())
