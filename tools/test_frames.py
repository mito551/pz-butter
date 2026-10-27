import unittest

import frames

MARKS = """mark scene.start 1000
mark scene.end 2000
mark w1.start 10
mark w1.end 20
mark w2.start 3000
mark w2.end 4000
"""

INTERVALS = [16.0]*99 + [100.0]


class TestFrames(unittest.TestCase):
    def test_marks_window(self):
        marks = frames.read_marks(MARKS.splitlines())
        self.assertEqual(frames.window_bounds(marks, "scene"), (1000, 2000))
        self.assertEqual(frames.window_bounds(marks, "w2"), (3000, 4000))

    def test_profile_percentiles(self):
        p = frames.profile(INTERVALS, 60)
        self.assertAlmostEqual(p["p50"], 16.0, places=3)
        self.assertAlmostEqual(p["p99"], 16.0, places=3)  # nearest rank
        self.assertEqual(p["over_2x_cap"], 1)
        self.assertAlmostEqual(p["one_pct_low_fps"], 10.0, places=3)

    def test_window_incomplete(self):
        marks = frames.read_marks(["mark scene.start 1000"])
        start, end = frames.window_bounds(marks, "scene")
        self.assertEqual(start, 1000)
        self.assertIsNone(end)

    def test_stale_marks_window_incomplete(self):
        text = "mark scene.start 1000\nmark scene.end 2000\nmark scene.start 5000\n"
        marks = frames.read_marks(text.splitlines())
        start, end = frames.window_bounds(marks, "scene")
        self.assertEqual(start, 5000)
        self.assertIsNone(end)

    def test_missing_start_exits_2(self):
        with self.assertRaises(SystemExit) as cm:
            frames.main(["/tmp/no-such-frames.out", "--window", "scene", "--marks",
                         "/tmp/no-such-marks.out"])
        self.assertEqual(cm.exception.code, 2)


if __name__ == "__main__":
    unittest.main()
