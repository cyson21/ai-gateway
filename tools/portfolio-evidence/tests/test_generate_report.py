from __future__ import annotations

import json
from pathlib import Path
import tempfile
import unittest

import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from generate_report import build_report, parse_suite, write_report


class GenerateReportTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def suite(self, filename: str, name: str, tests: int, failures: int = 0,
              errors: int = 0, skipped: int = 0) -> Path:
        source = self.root / filename
        source.write_text(
            f'<testsuite name="{name}" tests="{tests}" failures="{failures}" '
            f'errors="{errors}" skipped="{skipped}"/>',
            encoding="utf-8",
        )
        return source

    def test_aggregates_and_sorts_suites_without_timing_noise(self) -> None:
        self.suite("TEST-z.xml", "z.Suite", 3, skipped=1)
        self.suite("TEST-a.xml", "a.Suite", 2)

        report = build_report(self.root, "abc123", 0)

        self.assertEqual([suite["name"] for suite in report["suites"]], ["a.Suite", "z.Suite"])
        self.assertEqual(report["generated_at"], "1970-01-01T00:00:00Z")
        self.assertEqual(report["totals"], {
            "tests": 5, "passed": 4, "failures": 0, "errors": 0, "skipped": 1,
        })

    def test_failed_suite_sets_report_status(self) -> None:
        self.suite("TEST-failed.xml", "failed.Suite", 2, failures=1)

        report = build_report(self.root, "abc123", 1)

        self.assertEqual(report["status"], "failed")
        self.assertEqual(report["suites"][0]["passed"], 1)

    def test_rejects_empty_report_directory(self) -> None:
        with self.assertRaisesRegex(ValueError, "no Surefire reports"):
            build_report(self.root, "abc123", 1)

    def test_rejects_negative_epoch(self) -> None:
        self.suite("TEST-ok.xml", "ok.Suite", 1)
        with self.assertRaisesRegex(ValueError, "non-negative"):
            build_report(self.root, "abc123", -1)

    def test_rejects_inconsistent_counts(self) -> None:
        source = self.suite("TEST-bad.xml", "bad.Suite", 1, failures=1, skipped=1)
        with self.assertRaisesRegex(ValueError, "exceed tests"):
            parse_suite(source)

    def test_atomic_writer_leaves_only_final_json(self) -> None:
        self.suite("TEST-ok.xml", "ok.Suite", 1)
        output = self.root / "nested" / "report.json"

        write_report(build_report(self.root, "abc123", 1), output)

        self.assertEqual(json.loads(output.read_text(encoding="utf-8"))["commit"], "abc123")
        self.assertEqual(list(output.parent.iterdir()), [output])


if __name__ == "__main__":
    unittest.main()
