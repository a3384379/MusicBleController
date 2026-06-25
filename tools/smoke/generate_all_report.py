#!/usr/bin/env python3
import datetime as _dt
import json
import os
import subprocess
import sys
from pathlib import Path
from typing import Optional


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


def read_json(path: Path) -> Optional[dict]:
    if not path.exists():
        return None
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return None


def read_status(out_dir: Path, suite: str) -> dict:
    status = read_json(out_dir / f"{suite}_suite_status.json") or {}
    result = status.get("result", "SKIPPED")
    report_json = status.get("report_json", "")
    report_md = status.get("report_md", "")
    reason = status.get("reason", "")
    report = read_json(Path(report_json)) if report_json else None
    if report:
        result = report.get("summary", {}).get("overall_result", result)
        report_md = report.get("artifacts", {}).get("report_md", report_md)
    return {
        "result": result,
        "reason": reason,
        "report_json": report_json,
        "report_md": report_md,
        "report": report,
    }


def suite_counts(suite: dict) -> dict:
    report = suite.get("report") or {}
    summary = report.get("summary") or {}
    return {
        "required_pass": int(summary.get("required_pass", 0) or 0),
        "required_total": int(summary.get("required_total", 0) or 0),
        "optional_pass": int(summary.get("optional_pass", 0) or 0),
        "optional_warn": int(summary.get("optional_warn", 0) or 0),
        "optional_fail": int(summary.get("optional_fail", 0) or 0),
        "optional_skipped": int(summary.get("optional_skipped", 0) or 0),
    }


def optional_text(counts: dict) -> str:
    return f"pass={counts['optional_pass']} warn={counts['optional_warn']} fail={counts['optional_fail']} skipped={counts['optional_skipped']}"


def suite_table_row(name: str, suite: dict) -> str:
    counts = suite_counts(suite)
    required = "-"
    if counts["required_total"]:
        required = f"{counts['required_pass']}/{counts['required_total']}"
    report = suite.get("report_md") or "-"
    reason = suite.get("reason") or "-"
    return f"| {name} | {suite['result']} | {required} | {optional_text(counts)} | `{report}` | {reason} |"


def overall_result(ios: dict, android: dict) -> str:
    suites = [ios, android]
    if all(s["result"] == "SKIPPED" for s in suites):
        return "FAIL"
    if any(s["result"] == "FAIL" for s in suites):
        return "FAIL"
    any_warn = False
    for suite in suites:
        if suite["result"] == "SKIPPED":
            any_warn = True
        counts = suite_counts(suite)
        if counts["optional_warn"] > 0:
            any_warn = True
    return "WARN" if any_warn else "PASS"


def main():
    out_dir = Path(os.environ.get("OUT_DIR", sys.argv[1] if len(sys.argv) > 1 else ".")).resolve()
    root = Path(os.environ.get("ROOT_DIR", out_dir.parents[1] if len(out_dir.parents) > 1 else out_dir)).resolve()
    ios = read_status(out_dir, "ios")
    android = read_status(out_dir, "android")
    overall = overall_result(ios, android)
    ios_counts = suite_counts(ios)
    android_counts = suite_counts(android)
    summary = {
        "overall_result": overall,
        "ios_result": ios["result"],
        "android_result": android["result"],
        "required_pass": ios_counts["required_pass"] + android_counts["required_pass"],
        "required_total": ios_counts["required_total"] + android_counts["required_total"],
        "optional_pass": ios_counts["optional_pass"] + android_counts["optional_pass"],
        "optional_warn": ios_counts["optional_warn"] + android_counts["optional_warn"],
        "optional_fail": ios_counts["optional_fail"] + android_counts["optional_fail"],
        "optional_skipped": ios_counts["optional_skipped"] + android_counts["optional_skipped"],
    }
    git = git_info(root)
    report_path = out_dir / "report.md"
    report_json_path = out_dir / "report.json"

    report = [
        "# MusicBleController Smoke Test Report",
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
        "## Devices",
        "",
        f"- iOS: `{ios.get('reason') if ios['result'] == 'SKIPPED' else ios['result']}`",
        f"- Android: `{android.get('reason') if android['result'] == 'SKIPPED' else android['result']}`",
        f"- time: `{_dt.datetime.now().strftime('%Y-%m-%d %H:%M:%S')}`",
        "",
        "## Summary",
        "",
        "| Suite | Result | Required | Optional | Report | Notes |",
        "|---|---|---:|---|---|---|",
        suite_table_row("iOS", ios),
        suite_table_row("Android", android),
        "",
        f"Overall: {overall}",
        "",
        "## Notes",
        "",
        "- iOS optional BLE depends on Sony BLE advertising.",
        "- Android smoke does not validate iOS connection.",
        "- Cross-device subjective checks still require manual validation.",
        "",
    ]
    report_path.write_text("\n".join(report), encoding="utf-8")
    payload = {
        "summary": summary,
        "git": git,
        "ios": {
            "result": ios["result"],
            "reason": ios.get("reason", ""),
            "report_json": ios.get("report_json", ""),
            "report_md": ios.get("report_md", ""),
        },
        "android": {
            "result": android["result"],
            "reason": android.get("reason", ""),
            "report_json": android.get("report_json", ""),
            "report_md": android.get("report_md", ""),
        },
        "artifacts": {
            "report_md": str(report_path),
            "report_json": str(report_json_path),
        },
    }
    report_json_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print(report_path)
    print(report_json_path)


if __name__ == "__main__":
    main()
