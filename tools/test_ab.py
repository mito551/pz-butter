import os
import stat
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(__file__))

import ab
import frames


class AbTest(unittest.TestCase):
    def test_abOrderABBAAB(self):
        self.assertEqual(ab.order_for(3), ["A", "B", "B", "A", "A", "B"])

    def test_abResumesWithoutRepeating(self):
        plan = ab.order_for(3)
        self.assertEqual(ab.remaining(plan, 2), ["B", "A", "A", "B"])
        self.assertEqual(ab.remaining(plan, len(plan)), [])

    def test_armRunArgsReachRunSh(self):
        d = tempfile.mkdtemp()
        out = os.path.join(d, "runs")
        log = os.path.join(d, "argv.log")
        run_sh = os.path.join(d, "fake-run.sh")
        with open(run_sh, "w") as f:
            f.write("""#!/bin/sh
echo "$@" >> '%s'
out=""
while [ $# -gt 0 ]; do
  [ "$1" = "--out" ] && { shift; out="$1"; }
  shift
done
mkdir -p "$out/run$(wc -l < '%s')"
""" % (log, log))
        os.chmod(run_sh, os.stat(run_sh).st_mode | stat.S_IXUSR)
        ab.main(["--scene", "S3", "--list", "bare", "--save", "M/S",
                 "--arm", "A=x=1", "--arm", "B=x=2",
                 "--arm-run-args", "A=--renderer zink", "--runs", "1",
                 "--order", "AB", "--out", out, "--json", os.path.join(d, "ab.json"),
                 "--run-sh", run_sh])
        with open(log) as f:
            lines = f.read().splitlines()
        self.assertEqual(len(lines), 2)
        self.assertIn("--renderer zink", lines[0])
        self.assertNotIn("--renderer", lines[1])

    def test_orderLettersPickArmsByPosition(self):
        # the order holds letters (A = first --arm, B = second); arm names are free text like off/on
        arms = [{"name": "off", "props": ""}, {"name": "on", "props": ""}]
        self.assertEqual([ab.arm_for(arms, x)["name"] for x in ab.order_for(2)], ["off", "on", "on", "off"])

    def test_baseKeepsScenePreset(self):
        # run.sh writes --props after the scene preset and the last key wins: a base devScene or
        # devSceneSec would turn S4/S5/S8 into a stand scene of 60 s.
        self.assertNotIn("devScene=", ab.BASE_PROPS)
        self.assertNotIn("devSceneSec=", ab.BASE_PROPS)

    def test_abShortArmMarked(self):
        record = {
            "arms": [{"name": "off", "props": ""}, {"name": "on", "props": ""}],
            "runs_per_arm": 3,
            "order": ab.order_for(3),
            "done": [
                {"arm": "off", "boot_s": 40.0},
                {"arm": "on", "boot_s": 30.0},
                {"arm": "on", "boot_s": 32.0},
            ],
        }
        text = ab.summary_text(record)
        self.assertIn("arm off SHORT (1/3)", text)
        self.assertIn("arm on SHORT (2/3)", text)
        self.assertIn("median 31.0", text)

    def test_bootSwapCounted(self):
        d = tempfile.mkdtemp()
        with open(os.path.join(d, "sysmon.csv"), "w") as f:
            f.write("epoch_ms,mem_avail_mb,swap_used_mb,pswpin\n"
                    "500,9000,7000.5,100\n1000,9000,7100.0,150\n20000,3000,8000,6150\n39000,2000,8500,6200\n50000,2000,8500,9000\n")
        marks = frames.read_marks(["mark jvm.start 1000", "mark menu.ready 39000"])
        self.assertEqual(ab.boot_swap(d, marks), (6050, 7100.0))
        self.assertEqual(ab.boot_swap(tempfile.mkdtemp(), marks), (None, None))  # no sysmon.csv

    def test_noisyMarkedInSummary(self):
        record = {
            "arms": [{"name": "off", "props": ""}, {"name": "on", "props": ""}],
            "runs_per_arm": 1,
            "order": ab.order_for(1),
            "done": [
                {"arm": "off", "dir": "r1", "boot_s": 150.0, "boot_swapin": 42000},
                {"arm": "on", "dir": "r2", "boot_s": 90.0, "boot_swapin": 200},
            ],
        }
        text = ab.summary_text(record)
        self.assertIn("arm off NOISY (1/1)", text)
        self.assertNotIn("arm on NOISY", text)
        self.assertIn("noisy boots (over 5000 swap-in pages): r1 (42000)", text)

    def test_bootFromMarks(self):
        marks = frames.read_marks(["mark jvm.start 1000", "mark menu.ready 39000"])
        self.assertEqual(ab.boot_seconds(marks), 38.0)

    def test_loadFromMarks(self):
        marks = frames.read_marks(["mark menu.ready 39000", "mark world.ready 60000"])
        self.assertEqual(ab.load_seconds(marks), 21.0)
        early = frames.read_marks(["mark world.ready 10000", "mark menu.ready 39000",
                                   "mark world.ready 60000", "mark world.ready 60001"])
        self.assertEqual(ab.load_seconds(early), 21.0)
        self.assertIsNone(ab.boot_seconds(marks))

    def test_gameLoadingFromConsole(self):
        self.assertEqual(ab.game_loading_seconds("INFO: game loading took 12.5 seconds\n"), 12.5)
        self.assertIsNone(ab.game_loading_seconds("nothing"))

    def test_overlap(self):
        record = {
            "arms": [{"name": "off", "props": ""}, {"name": "on", "props": ""}],
            "runs_per_arm": 2,
            "order": ab.order_for(2),
            "done": [
                {"arm": "off", "boot_s": 52.0},
                {"arm": "on", "boot_s": 36.0},
                {"arm": "off", "boot_s": 54.0},
                {"arm": "on", "boot_s": 38.0},
            ],
        }
        text = ab.summary_text(record)
        self.assertIn("boot ranges do not overlap", text)
        self.assertNotIn("SHORT", text)


if __name__ == "__main__":
    unittest.main()
