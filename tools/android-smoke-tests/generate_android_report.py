#!/usr/bin/env python3
import datetime as _dt
import json
import os
import subprocess
import sys
from pathlib import Path


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
        if len(parts) < 5:
            continue
        category, name, result, cost_ms, detail = parts[:5]
        try:
            cost = int(cost_ms)
        except ValueError:
            cost = 0
        rows.append({
            "category": category,
            "name": name,
            "result": result,
            "detail": detail,
            "costMs": cost,
        })
    return rows


def read_device(out_dir: Path) -> dict:
    info = out_dir / "device_info.tsv"
    if not info.exists():
        return {"id": os.environ.get("ANDROID_DEVICE_ID", "unknown"), "name": "unknown", "state": "unknown", "model": "unknown", "androidVersion": "unknown", "sdk": "unknown"}
    parts = info.read_text(encoding="utf-8", errors="replace").splitlines()[0].split("\t")
    while len(parts) < 6:
        parts.append("unknown")
    return {
        "name": parts[0],
        "id": parts[1],
        "state": parts[2],
        "model": parts[3],
        "androidVersion": parts[4],
        "sdk": parts[5],
    }


def table(rows: list[dict]) -> str:
    lines = ["| Test | Result | Cost | Detail |", "|---|---|---:|---|"]
    for row in rows:
        detail = row["detail"].replace("|", "/")
        lines.append(f"| {row['name']} | {row['result']} | {row['costMs']} ms | {detail} |")
    return "\n".join(lines)


def file_table(path: Path) -> str:
    lines = ["| Path | Exists | File Count | Size Blocks | Result |", "|---|---:|---:|---:|---|"]
    if not path.exists():
        lines.append("| - | false | 0 | 0 | missing file_checks.tsv |")
        return "\n".join(lines)
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        parts = line.split("\t")
        while len(parts) < 5:
            parts.append("")
        lines.append(f"| `{parts[0]}` | {parts[1]} | {parts[2]} | {parts[3]} | {parts[4]} |")
    return "\n".join(lines)


def build_failure_excerpt(out_dir: Path, should_extract: bool) -> str:
    if not should_extract:
        return ""
    candidates = [out_dir / "sony_filtered.log", out_dir / "sony_logcat.log", out_dir / "launch_logcat.log"]
    lines: list[str] = []
    for path in candidates:
        if path.exists():
            lines.extend(path.read_text(encoding="utf-8", errors="replace").splitlines())
    if not lines:
        return ""
    keywords = [
        "PlayerAgent", "BLE-A", "BLE-ADV", "BLE-GATT", "BLE-RECOVERY",
        "Qrc", "Lyric", "AlbumArt", "FullLyrics", "FATAL EXCEPTION",
        "ANR", "AndroidRuntime", "failed", "error",
    ]
    selected = []
    lower_keywords = [keyword.lower() for keyword in keywords]
    for line in lines:
        lower = line.lower()
        if any(keyword in lower for keyword in lower_keywords):
            selected.append(line)
    excerpt = (selected or lines)[-120:]
    path = out_dir / "failure_excerpt.log"
    path.write_text("\n".join(excerpt) + "\n", encoding="utf-8")
    return str(path)


def main():
    out_dir = Path(os.environ.get("OUT_DIR", sys.argv[1] if len(sys.argv) > 1 else ".")).resolve()
    root = Path(os.environ.get("ROOT_DIR", out_dir)).resolve()
    required = read_tsv(out_dir / "required_results.tsv")
    optional = read_tsv(out_dir / "optional_results.tsv")
    tests = required + optional

    required_pass = sum(1 for row in required if row["result"] == "PASS")
    required_total = len(required)
    required_fail = sum(1 for row in required if row["result"] == "FAIL")
    optional_pass = sum(1 for row in optional if row["result"] == "PASS")
    optional_warn = sum(1 for row in optional if row["result"] == "WARN")
    optional_fail = sum(1 for row in optional if row["result"] == "FAIL")
    optional_skipped = sum(1 for row in optional if row["result"] == "SKIPPED")
    overall_result = "PASS" if required_fail == 0 and optional_fail == 0 else "FAIL"
    failure_excerpt = build_failure_excerpt(out_dir, overall_result != "PASS" or optional_warn > 0)
    git = git_info(root)
    device = read_device(out_dir)

    report_path = out_dir / "report.md"
    report_json_path = out_dir / "report.json"
    logcat = out_dir / "sony_logcat.log"
    filtered = out_dir / "sony_filtered.log"

    report = [
        "# Android Smoke Test Report",
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
        f"- id: `{device['id']}`",
        f"- model: `{device['model']}`",
        f"- androidVersion: `{device['androidVersion']}`",
        f"- sdk: `{device['sdk']}`",
        f"- state: `{device['state']}`",
        f"- time: `{_dt.datetime.now().strftime('%Y-%m-%d %H:%M:%S')}`",
        "",
        "## Required Tests",
        "",
        table(required),
        "",
        "## Optional Tests",
        "",
        table(optional) if optional else "| Test | Result | Cost | Detail |\n|---|---|---:|---|\n| Optional | SKIPPED | 0 ms | no optional tests |",
        "",
        "## File Checks",
        "",
        file_table(out_dir / "file_checks.tsv"),
        "",
    ]
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
            "logcat": str(logcat) if logcat.exists() else "",
            "filtered_logcat": str(filtered) if filtered.exists() else "",
            "failure_excerpt": failure_excerpt,
        },
    }
    report_json_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print(report_path)
    print(report_json_path)


if __name__ == "__main__":
    main()
