#!/usr/bin/env python3
"""Summarise a QueryFence report.

    python3 tools/queryfence-summary.py
    python3 tools/queryfence-summary.py target/queryfence/report.json --by table
    python3 tools/queryfence-summary.py build/queryfence --triage
    python3 tools/queryfence-summary.py */target/queryfence --triage

Reads the JSON report a test run wrote and prints what is in it, grouped by rule, by table, by
violation code or by the code that produced the SQL. `--triage` prints one line per distinct
origin, which is the list you work through when adopting QueryFence on an existing project.

Each test JVM writes its own `report-<start>-<pid>.json`, and `report.json` next to them is the
merge of every JVM of the last run. A path can be that file or the directory holding it; with no
path, `target/queryfence` (Maven) or `build/queryfence` (Gradle) is used. When a directory has no
`report.json`, its per-JVM reports of the most recent run are merged here instead. Several paths
(one per module) are summarised together.

No dependencies: standard library only, Python 3.9+.
"""

from __future__ import annotations

import argparse
import collections
import json
import pathlib
import sys

MERGED = "report.json"


def read_json(path: pathlib.Path) -> dict:
    try:
        return json.loads(path.read_text())
    except FileNotFoundError:
        sys.exit(f"No report at {path}. Run your tests first; QueryFence writes it at the end.")
    except json.JSONDecodeError as error:
        sys.exit(f"{path} is not valid JSON: {error}")


def merge(reports: list[dict]) -> dict:
    """Merges per-JVM reports the way QueryFence does when it writes report.json."""
    merged: dict = {"disabled": False, "disabledReasons": [], "policies": []}
    groups: dict[tuple, dict] = {}
    for report in reports:
        merged["disabled"] = merged["disabled"] or bool(report.get("disabled"))
        for reason in report.get("disabledReasons", []):
            if reason not in merged["disabledReasons"]:
                merged["disabledReasons"].append(reason)
        for group in report.get("policies", []):
            key = (group.get("policy"), group.get("mode"))
            summary = group.get("summary", {})
            unmatched = group.get("unmatchedSuppressions", [])
            if key not in groups:
                groups[key] = {
                    "policy": group.get("policy"),
                    "mode": group.get("mode"),
                    "onUnparseable": group.get("onUnparseable"),
                    "summary": {"tests": 0, "statements": 0, "findings": 0},
                    "findings": [],
                    "unmatchedSuppressions": list(unmatched),
                }
                merged["policies"].append(groups[key])
            else:
                still = {(s.get("rule"), s.get("origin")) for s in unmatched}
                groups[key]["unmatchedSuppressions"] = [
                    s for s in groups[key]["unmatchedSuppressions"]
                    if (s.get("rule"), s.get("origin")) in still
                ]
            target = groups[key]
            target["summary"]["tests"] += summary.get("tests", 0)
            target["summary"]["statements"] += summary.get("statements", 0)
            target["findings"].extend(group.get("findings", []))
            target["summary"]["findings"] = len(target["findings"])
    return merged


def load_directory(directory: pathlib.Path) -> dict:
    if (directory / MERGED).exists():
        return read_json(directory / MERGED)
    files = sorted(directory.glob("report-*.json"), key=lambda path: path.stat().st_mtime)
    if not files:
        sys.exit(f"No report in {directory}. Run your tests first; QueryFence writes it at the end.")
    reports = [(path.name, read_json(path)) for path in files]
    run = reports[-1][1].get("run")  # the most recent run
    current = [(name, report) for name, report in reports if report.get("run") == run]
    merged = merge([report for _, report in current])
    merged["reports"] = [name for name, _ in current]
    return merged


def load(path: pathlib.Path) -> dict:
    return load_directory(path) if path.is_dir() else read_json(path)


def default_path() -> pathlib.Path:
    for candidate in (pathlib.Path("target/queryfence"), pathlib.Path("build/queryfence")):
        if candidate.exists():
            return candidate
    return pathlib.Path("target/queryfence") / MERGED


def findings(report: dict) -> list[dict]:
    out = []
    for group in report.get("policies", []):
        for finding in group.get("findings", []):
            out.append({**finding, "policy": group.get("policy"), "mode": group.get("mode")})
    return out


def origin_of(finding: dict) -> str:
    origin = finding.get("origin") or {}
    if not origin.get("class"):
        return "unknown origin"
    where = f"{origin['class']}#{origin['method']}"
    if origin.get("file") and origin.get("line", -1) >= 0:
        where += f" ({origin['file']}:{origin['line']})"
    return where


def table(rows: list[tuple[str, int]], header: str) -> None:
    if not rows:
        return
    width = max(len(name) for name, _ in rows)
    print(f"\n{header}")
    print("-" * (width + 8))
    for name, count in rows:
        print(f"{name.ljust(width)}  {count:>5}")


def counted(items: collections.Counter) -> list[tuple[str, int]]:
    return sorted(items.items(), key=lambda pair: (-pair[1], pair[0]))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("reports", type=pathlib.Path, nargs="*",
                        help="report.json files or report directories (default: "
                             "target/queryfence, else build/queryfence)")
    parser.add_argument("--by", choices=["rule", "table", "code", "origin", "all"], default="all")
    parser.add_argument("--triage", action="store_true",
                        help="one line per distinct origin, to work through when adopting")
    args = parser.parse_args()

    paths = args.reports or [default_path()]
    loaded = [(path, load(path)) for path in paths]
    found = [finding for _, report in loaded for finding in findings(report)]

    for path, report in loaded:
        prefix = f"{path}: " if len(loaded) > 1 else ""
        if report.get("disabled"):
            print(f"{prefix}WARNING: QueryFence was disabled during this run")
            for reason in report.get("disabledReasons", []):
                print(f"  - {reason}")
        for unreadable in report.get("unreadableReports", []):
            print(f"{prefix}WARNING: could not read {unreadable}; its findings are missing")
        jvms = len(report.get("reports", []))
        if jvms > 1:
            print(f"{prefix}merged from {jvms} test JVM reports")

        for group in report.get("policies", []):
            summary = group.get("summary", {})
            unparseable = group.get("onUnparseable")
            modes = group.get("mode") if unparseable is None else f"{group.get('mode')}, unparseable {unparseable}"
            print(
                f"{prefix}{group.get('policy')} [{modes}]: "
                f"{summary.get('findings', 0)} findings, "
                f"{summary.get('statements', 0)} statements, "
                f"{summary.get('tests', 0)} tests"
            )
            for stale in group.get("unmatchedSuppressions", []):
                print(f"  suppression matched nothing: {stale.get('rule')} at {stale.get('origin')}")

    if not found:
        print("\nNothing to report: every statement satisfied the policy.")
        return

    if args.by in ("rule", "all"):
        table(counted(collections.Counter(f.get("rule") or "parser" for f in found)),
              "By rule")
    if args.by in ("table", "all"):
        table(counted(collections.Counter(f.get("table") or "(not a table)" for f in found)),
              "By table")
    if args.by in ("code", "all"):
        table(counted(collections.Counter(f["code"] for f in found)), "By code")
    if args.by in ("origin", "all"):
        table(counted(collections.Counter(origin_of(f) for f in found))[:20],
              "By origin (top 20)")

    if args.triage:
        print("\nTriage list: one row per origin. Decide leak / false positive / suppression.")
        print("-" * 100)
        by_origin: dict[str, list[dict]] = collections.defaultdict(list)
        for finding in found:
            by_origin[origin_of(finding)].append(finding)
        for origin, group in sorted(by_origin.items(), key=lambda kv: -len(kv[1])):
            codes = ", ".join(sorted({f["code"] for f in group}))
            tables = ", ".join(sorted({f.get("table") or "-" for f in group}))
            print(f"\n{origin}")
            print(f"    {len(group)} finding(s)  codes: {codes}  tables: {tables}")
            print(f"    e.g. {group[0]['sql'][:160]}")


if __name__ == "__main__":
    main()
