import os
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, os.path.dirname(__file__))

import coverage

WIN = (1793708068601, 1793708128602)  # 2026-11-03 S3 bare run marks


def make_dir(**with_files):
    d = tempfile.mkdtemp(prefix="ptcov-")
    w = dict(marks=True, frames=True, sysmon=True, gc=True, jfr=True, perf=False)
    w.update(with_files)
    if w["marks"]:
        open(os.path.join(d, "marks.out"), "w").write(
            f"mark scene.start {WIN[0]}\nmark scene.end {WIN[1]}\n")
    if w["frames"]:
        open(os.path.join(d, "frames.out"), "w").write(
            f"{WIN[0] - 1} 1 0 0 0\n{WIN[1] + 1} 2 0 0 0\n")
    if w["sysmon"]:
        open(os.path.join(d, "sysmon.csv"), "w").write(
            f"epoch_ms,cpu0\n{WIN[0] - 1},0.0\n{WIN[1] + 1},0.0\n")
    if w["gc"]:
        open(os.path.join(d, "gc.log"), "w").write(
            "[0.006s][gc,init]Initializing The Z Garbage Collector\n"
            "[176.502s][gc,exit]Metaspace used 102044K\n")
        open(os.path.join(d, "run.opts"), "w").write(
            "start=2026-11-03T13:12:37+01:00\n")
    if w["jfr"]:
        open(os.path.join(d, "game.jfr"), "wb").write(b"fixture, patched away below")
    if w["perf"]:
        open(os.path.join(d, "perf.data"), "wb").write(b"fixture, patched away below")
    return d


JFR_OK = patch("coverage.jfr_stamps", lambda run_dir, cmd="jfr": WIN)


class CoverageTest(unittest.TestCase):
    def test_coversWindow(self):
        d = make_dir()
        with JFR_OK:
            rows = dict(coverage.coverage(d))
        for name in ("game.jfr", "gc.log", "frames.out", "sysmon.csv"):
            self.assertEqual(rows[name], "ok", name)
        self.assertNotIn("mangohud", rows)

    def test_perfStartsLate(self):
        d = make_dir(perf=True)
        with patch("coverage.perf_stamps", lambda run_dir: (WIN[0] + 2000, WIN[1])):
            rows = dict(coverage.coverage(d))
        self.assertEqual(rows["perf.data"], "starts late by 2000 ms")

    def test_mangohudAbsentIsNotAFailure(self):
        d = make_dir()
        with JFR_OK:
            rows = coverage.coverage(d)
            r = coverage.main(["coverage.py", d])
        self.assertEqual(r, 0)
        self.assertFalse(any(name == "mangohud" for name, _ in rows))

    def test_noMarksIsAnError(self):
        d = make_dir(marks=False, frames=False, sysmon=False)
        rows = coverage.coverage(d)
        self.assertEqual(rows, [("scene window", "no window")])
        import subprocess
        out = subprocess.run([sys.executable, os.path.join(os.path.dirname(__file__), "coverage.py"), d],
                             capture_output=True, text=True)
        self.assertEqual(out.returncode, 1)
        self.assertIn("no window", out.stdout)


if __name__ == "__main__":
    unittest.main()
