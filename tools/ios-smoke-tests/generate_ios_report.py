#!/usr/bin/env python3
import datetime as _dt
import os
import subprocess
import sys
from pathlib import Path


def read_tsv(path: Path):
    rows = []
    if not path.exists():
        return rows
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        parts = line.split("\t")
        if len(parts) >= 4:
            rows.append(parts[:4])
    return rows


def git_commit(root: Path) -> str:
    try:
        return subprocess.check_output(
            ["git", "rev-parse", "--short", "HEAD"],
            cwd=root,
            text=True,
        ).strip()
    except Exception:
        return "unknown"


def table(rows):
    lines = ["| Test | Result | Detail |", "|---|---|---|"]
    for _, test, result, detail in rows:
        lines.append(f"| {test} | {result} | {detail.replace('|', '/')} |")
    return "\n".join(lines)


def file_table(path: Path):
    if not path.exists():
        return "| path | exists | bytes |\n|---|---|---|\n| - | false | 0 |"
    lines = ["| path | exists | bytes |", "|---|---:|---:|"]
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        parts = line.split("\t")
        if len(parts) >= 3:
            lines.append(f"| {parts[0]} | {parts[1]} | {parts[2]} |")
    return "\n".join(lines)


def main():
    out_dir = Path(os.environ.get("OUT_DIR", sys.argv[1] if len(sys.argv) > 1 else ".")).resolve()
    root = Path(os.environ.get("ROOT_DIR", out_dir)).resolve()
    device = os.environ.get("IOS_DEVICE_ID", "unknown")
    required = read_tsv(out_dir / "required_results.tsv")
    optional = read_tsv(out_dir / "optional_results.tsv")
    required_pass = sum(1 for row in required if row[2] == "PASS")
    required_total = len(required)
    optional_pass = sum(1 for row in optional if row[2] == "PASS")
    optional_warn = sum(1 for row in optional if row[2] == "WARN")
    optional_skipped = sum(1 for row in optional if row[2] == "SKIPPED")

    report = [
        "# iOS Smoke Test Report",
        "",
        f"Git Commit: {git_commit(root)}",
        f"Device: {device}",
        f"Time: {_dt.datetime.now().strftime('%Y-%m-%d %H:%M:%S')}",
        "",
        "## Required Tests",
        "",
        table(required),
        "",
        "## Optional BLE Tests",
        "",
        table(optional) if optional else "| Test | Result | Detail |\n|---|---|---|\n| Optional BLE | SKIPPED | disabled |",
        "",
        "## File Checks",
        "",
        file_table(out_dir / "file_checks.tsv"),
        "",
        "## Summary",
        "",
        f"Required PASS {required_pass}/{required_total}",
        f"Optional PASS {optional_pass}, WARN {optional_warn}, SKIPPED {optional_skipped}",
        "",
    ]
    report_path = out_dir / "report.md"
    report_path.write_text("\n".join(report), encoding="utf-8")
    print(report_path)


if __name__ == "__main__":
    main()
