#!/usr/bin/env python3
import datetime as _dt
import json
import os
import subprocess
import sys
from pathlib import Path


KEYWORDS = [
    "SmokeTest",
    "BLE-Reconnect",
    "BLE-Health",
    "didDisconnect",
    "didConnect",
    "notify subscribed",
    "playbackState",
    "AlbumArt",
    "Lyrics",
    "Preferences",
    "fatal",
    "crash",
    "error",
    "failed",
]


def run_git(root: Path, args: list[str]) -> str:
    try:
        return subprocess.check_output(["git", *args], cwd=root, text=True, stderr=subprocess.DEVNULL).strip()
    except Exception:
        return "unknown"


def git_info(root: Path) -> dict:
    status = run_git(root, ["status", "--short"])
    return {
        "branch": run_git(root, ["branch", "--show-current"]),
        "commit": run_git(root, ["rev-parse", "--short", "HEAD"]),
        "dirty": bool(status),
        "status": status,
        "last_commit_message": run_git(root, ["log", "-1", "--pretty=%s"]),
    }


def read_tsv(path: Path) -> list[dict]:
    rows = []
    if not path.exists():
        return rows
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        parts = line.split("\t")
        if len(parts) >= 5:
            category, name, result, cost_ms, detail = parts[:5]
        elif len(parts) >= 4:
            category, name, result, detail = parts[:4]
            cost_ms = "0"
        else:
            continue
        try:
            cost = int(cost_ms)
        except ValueError:
            cost = 0
        row = {
            "category": category,
            "name": name,
            "result": result,
            "detail": detail,
            "costMs": cost,
        }
        for extra in parts[5:]:
            if "=" not in extra:
                continue
            key, value = extra.split("=", 1)
            if not key:
                continue
            if value.lower() == "true":
                row[key] = True
            elif value.lower() == "false":
                row[key] = False
            else:
                row[key] = value
        rows.append(row)
    return rows


def read_device(out_dir: Path, fallback_id: str) -> dict:
    info = out_dir / "device_info.tsv"
    if not info.exists():
        return {"id": fallback_id, "name": "unknown", "state": "unknown", "model": "unknown"}
    parts = info.read_text(encoding="utf-8", errors="replace").splitlines()[0].split("\t")
    while len(parts) < 4:
        parts.append("unknown")
    return {"name": parts[0], "id": parts[1], "state": parts[2], "model": parts[3]}


def table(rows: list[dict]) -> str:
    lines = ["| Test | Result | Cost | Detail |", "|---|---|---:|---|"]
    for row in rows:
        detail = row["detail"].replace("|", "/")
        lines.append(f"| {row['name']} | {row['result']} | {row['costMs']} ms | {detail} |")
    return "\n".join(lines)


def file_table(path: Path) -> str:
    if not path.exists():
        return "| path | exists | bytes |\n|---|---:|---:|\n| - | false | 0 |"
    lines = ["| path | exists | bytes |", "|---|---:|---:|"]
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        parts = line.split("\t")
        if len(parts) >= 3:
            lines.append(f"| {parts[0]} | {parts[1]} | {parts[2]} |")
    return "\n".join(lines)


def build_failure_excerpt(out_dir: Path, should_extract: bool) -> str:
    if not should_extract:
        return ""
    log_path = out_dir / "ios_ble.log"
    if not log_path.exists():
        return ""
    lines = log_path.read_text(encoding="utf-8", errors="replace").splitlines()
    lower_keywords = [keyword.lower() for keyword in KEYWORDS]
    selected_indexes = set()
    for idx, line in enumerate(lines):
        lower = line.lower()
        if any(keyword.lower() in lower for keyword in lower_keywords):
            for nearby in range(max(0, idx - 2), min(len(lines), idx + 3)):
                selected_indexes.add(nearby)
    excerpt_lines = [lines[index] for index in sorted(selected_indexes)][-80:]
    if not excerpt_lines:
        return ""
    excerpt_path = out_dir / "failure_excerpt.log"
    excerpt_path.write_text("\n".join(excerpt_lines) + "\n", encoding="utf-8")
    return str(excerpt_path)


def main():
    out_dir = Path(os.environ.get("OUT_DIR", sys.argv[1] if len(sys.argv) > 1 else ".")).resolve()
    root = Path(os.environ.get("ROOT_DIR", out_dir)).resolve()
    device = read_device(out_dir, os.environ.get("IOS_DEVICE_ID", "unknown"))
    required = read_tsv(out_dir / "required_results.tsv")
    optional = read_tsv(out_dir / "optional_results.tsv")
    tests = required + optional

    required_pass = sum(1 for row in required if row["result"] == "PASS")
    required_total = len(required)
    optional_pass = sum(1 for row in optional if row["result"] == "PASS")
    optional_warn = sum(1 for row in optional if row["result"] == "WARN")
    optional_fail = sum(1 for row in optional if row["result"] == "FAIL")
    optional_skipped = sum(1 for row in optional if row["result"] == "SKIPPED")
    required_fail = sum(1 for row in required if row["result"] == "FAIL")
    overall_result = "PASS" if required_fail == 0 else "FAIL"
    has_warn_or_fail = any(row["result"] in {"WARN", "FAIL"} for row in tests)
    failure_excerpt = build_failure_excerpt(out_dir, has_warn_or_fail)
    git = git_info(root)
    album_art_flow_path = out_dir / "album_art_flow.json"
    album_art_flow = None
    if album_art_flow_path.exists():
        try:
            album_art_flow = json.loads(album_art_flow_path.read_text(encoding="utf-8"))
        except Exception:
            album_art_flow = None

    report_path = out_dir / "report.md"
    report_json_path = out_dir / "report.json"
    ios_log = out_dir / "ios_ble.log"

    report = [
        "# iOS Smoke Test Report",
        "",
        "## Git",
        "",
        f"- branch: `{git['branch']}`",
        f"- commit: `{git['commit']}`",
        f"- dirty: `{str(git['dirty']).lower()}`",
        f"- last commit: {git['last_commit_message']}",
        "",
        "```text",
        git["status"] or "clean",
        "```",
        "",
        "## Device",
        "",
        f"- name: `{device['name']}`",
        f"- id: `{device['id']}`",
        f"- state: `{device['state']}`",
        f"- model: `{device['model']}`",
        f"- time: `{_dt.datetime.now().strftime('%Y-%m-%d %H:%M:%S')}`",
        "",
        "## Required Tests",
        "",
        table(required),
        "",
        "## Optional BLE Tests",
        "",
        table(optional) if optional else "| Test | Result | Cost | Detail |\n|---|---|---:|---|\n| Optional BLE | SKIPPED | 0 ms | disabled |",
        "",
        "## AlbumArt Flow",
        "",
    ]
    if album_art_flow:
        report.extend([
            f"- result: `{album_art_flow.get('result', 'unknown')}`",
            f"- albumArtId: `{album_art_flow.get('albumArtId') or '-'}`",
            f"- finalQuality: `{album_art_flow.get('finalQuality') or '-'}`",
            f"- preview: `{str(album_art_flow.get('preview', False)).lower()}`",
            f"- hq: `{str(album_art_flow.get('hq', False)).lower()}`",
            f"- enhanced: `{str(album_art_flow.get('enhanced', False)).lower()}`",
            f"- timeout: `{str(album_art_flow.get('timeout', False)).lower()}`",
            f"- reason: {album_art_flow.get('reason') or '-'}",
            "",
        ])
    else:
        report.extend(["AlbumArt Flow was not collected.", ""])
    report.extend([
        "## File Checks",
        "",
        file_table(out_dir / "file_checks.tsv"),
        "",
    ])
    if failure_excerpt:
        report.extend([
            "## Failure/WARN Excerpt",
            "",
            f"Key log excerpt: `{failure_excerpt}`",
            "",
        ])
    report.extend([
        "## Summary",
        "",
        f"Overall: {overall_result}",
        f"Required PASS {required_pass}/{required_total}",
        f"Optional PASS {optional_pass}, WARN {optional_warn}, FAIL {optional_fail}, SKIPPED {optional_skipped}",
        "",
    ])
    report_path.write_text("\n".join(report), encoding="utf-8")

    payload = {
        "summary": {
            "required_pass": required_pass,
            "required_total": required_total,
            "optional_pass": optional_pass,
            "optional_warn": optional_warn,
            "optional_fail": optional_fail,
            "optional_skipped": optional_skipped,
            "overall_result": overall_result,
        },
        "git": git,
        "device": device,
        "tests": tests,
        "artifacts": {
            "report_md": str(report_path),
            "report_json": str(report_json_path),
            "ios_log": str(ios_log) if ios_log.exists() else "",
            "failure_excerpt": failure_excerpt,
            "album_art_flow": str(album_art_flow_path) if album_art_flow_path.exists() else "",
        },
    }
    if album_art_flow:
        payload["albumArtFlow"] = album_art_flow
    report_json_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print(report_path)
    print(report_json_path)


if __name__ == "__main__":
    main()
