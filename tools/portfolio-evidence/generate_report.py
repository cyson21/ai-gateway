#!/usr/bin/env python3
"""Create a deterministic JSON summary from Maven Surefire XML reports."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import tempfile
import xml.etree.ElementTree as ET


def _non_negative_int(value: str | None, field: str, source: Path) -> int:
    try:
        parsed = int(value or "0")
    except ValueError as exc:
        raise ValueError(f"{source.name}: invalid {field}") from exc
    if parsed < 0:
        raise ValueError(f"{source.name}: negative {field}")
    return parsed


def parse_suite(source: Path) -> dict[str, object]:
    root = ET.parse(source).getroot()
    if root.tag != "testsuite":
        raise ValueError(f"{source.name}: expected testsuite root")
    tests = _non_negative_int(root.get("tests"), "tests", source)
    failures = _non_negative_int(root.get("failures"), "failures", source)
    errors = _non_negative_int(root.get("errors"), "errors", source)
    skipped = _non_negative_int(root.get("skipped"), "skipped", source)
    if failures + errors + skipped > tests:
        raise ValueError(f"{source.name}: result counts exceed tests")
    return {
        "name": root.get("name") or source.stem.removeprefix("TEST-"),
        "source": source.name,
        "tests": tests,
        "passed": tests - failures - errors - skipped,
        "failures": failures,
        "errors": errors,
        "skipped": skipped,
        "status": "passed" if failures + errors == 0 else "failed",
    }


def build_report(reports: Path, commit: str, source_date_epoch: int) -> dict[str, object]:
    if source_date_epoch < 0:
        raise ValueError("SOURCE_DATE_EPOCH must be non-negative")
    sources = sorted(reports.glob("TEST-*.xml"), key=lambda path: path.name)
    if not sources:
        raise ValueError(f"no Surefire reports found in {reports}")
    suites = sorted((parse_suite(source) for source in sources), key=lambda suite: (suite["name"], suite["source"]))
    totals = {
        field: sum(int(suite[field]) for suite in suites)
        for field in ("tests", "passed", "failures", "errors", "skipped")
    }
    return {
        "schema_version": 1,
        "project": "ai-gateway",
        "commit": commit,
        "generated_at": datetime.fromtimestamp(source_date_epoch, timezone.utc)
        .isoformat()
        .replace("+00:00", "Z"),
        "status": "passed" if totals["failures"] + totals["errors"] == 0 else "failed",
        "totals": totals,
        "suites": suites,
    }


def write_report(report: dict[str, object], output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    payload = json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=output.parent, delete=False) as handle:
        temporary = Path(handle.name)
        handle.write(payload)
    temporary.replace(output)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reports", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--commit", default=os.environ.get("EVIDENCE_COMMIT"))
    parser.add_argument("--source-date-epoch", type=int, default=None)
    args = parser.parse_args()

    commit = (args.commit or "").strip()
    if not commit:
        parser.error("--commit or EVIDENCE_COMMIT is required")
    epoch = args.source_date_epoch
    if epoch is None:
        raw_epoch = os.environ.get("SOURCE_DATE_EPOCH")
        if raw_epoch is None:
            parser.error("--source-date-epoch or SOURCE_DATE_EPOCH is required")
        try:
            epoch = int(raw_epoch)
        except ValueError:
            parser.error("SOURCE_DATE_EPOCH must be an integer")

    write_report(build_report(args.reports, commit, epoch), args.output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
