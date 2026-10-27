"""frames: profile frame intervals within a marked window of the frame log.

Stdlib only. Usage:
    python3 frames.py FRAMES_OUT [--marks MARKS_OUT] [--window NAME] [--cap-fps N]

The frame log (written by FrameClock/LogSink) is ``epoch_ms frame interval_ms
step_ms cpu_ms``. marks file lines are ``mark <name> <epoch_ms> [note...]``.
"""

import argparse
import os
import sys


class _Marks(list):
    """List of (name, epoch_ms, note) tuples that also supports name lookup."""

    def _find(self, name):
        for item in self:
            if item[0] == name:
                return item
        return None

    def get(self, name, default=None):
        it = self._find(name)
        return it if it is not None else default

    def epoch(self, name):
        it = self._find(name)
        if it is None:
            return None
        return it[1]

    def __getitem__(self, key):
        if isinstance(key, str):
            it = self._find(key)
            if it is None:
                raise KeyError(key)
            return it[1]
        return super().__getitem__(key)


def read_marks(lines):
    out = _Marks()
    if isinstance(lines, str):
        lines = lines.splitlines()
    for line in lines:
        parts = line.split()
        if len(parts) < 3 or parts[0] != "mark":
            continue
        try:
            name = parts[1]
            epoch = int(parts[2])
        except (ValueError, IndexError):
            continue
        note = " ".join(parts[3:]) if len(parts) > 3 else None
        out.append((name, epoch, note))
    return out


def window_bounds(marks, name):
    if name == "scene":
        base = "scene"
    else:
        base = name
    start_key = base + ".start"
    end_key = base + ".end"
    start = None
    end = None
    for m in marks:
        if m[0] == start_key:
            start = m[1]
        elif m[0] == end_key:
            end = m[1]
    if start is None:
        raise SystemExit(2)
    return (start, end)


def _nearest_rank(sorted_vals, p):
    n = len(sorted_vals)
    if n == 0:
        return 0.0
    import math
    rank = math.ceil(p / 100.0 * n)
    if rank < 1:
        rank = 1
    if rank > n:
        rank = n
    return float(sorted_vals[rank - 1])


def profile(intervals_ms, cap_fps=None):
    vals = [float(x) for x in intervals_ms if x is not None]
    # exclude zero intervals
    vals = [v for v in vals if v != 0]
    n = len(vals)
    srt = sorted(vals)
    # slowest 1%, at least one
    if n == 0:
        slow_mean = 0.0
        low_fps = 0.0
    else:
        import math
        k = max(1, int(math.ceil(n * 0.01)))
        slow = srt[-k:]
        slow_mean = sum(slow) / len(slow)
        low_fps = (1000.0 / slow_mean) if slow_mean > 0 else 0.0
    over = 0
    if cap_fps:
        threshold = 2.0 * 1000.0 / float(cap_fps)
        over = sum(1 for v in vals if v > threshold)
    result = {
        "count": n,
        "n": n,
        "frames": n,
        "mean": (sum(vals) / n) if n else 0.0,
        "mean_ms": (sum(vals) / n) if n else 0.0,
        "mean_interval_ms": (sum(vals) / n) if n else 0.0,
        "p50": _nearest_rank(srt, 50),
        "p50_ms": _nearest_rank(srt, 50),
        "p99": _nearest_rank(srt, 99),
        "p99_ms": _nearest_rank(srt, 99),
        "p99.9": _nearest_rank(srt, 99.9),
        "p99_9": _nearest_rank(srt, 99.9),
        "p99.9_ms": _nearest_rank(srt, 99.9),
        "over_2x_cap": over,
        "over2x": over,
        "frames_over_2x_cap": over,
        "over_2x": over,
        "one_pct_low_fps": low_fps,
        "one_percent_low_fps": low_fps,
        "1%_low_fps": low_fps,
        "low_1_pct_fps": low_fps,
    }
    return result


def _read_frames(paths):
    intervals = []  # list of (epoch_ms, interval_ms)
    for path in paths:
        if not os.path.exists(path):
            continue
        with open(path) as f:
            for line in f:
                parts = line.split()
                if len(parts) < 3:
                    continue
                try:
                    epoch = int(parts[0])
                    iv = float(parts[2])
                except ValueError:
                    continue
                intervals.append((epoch, iv))
    return intervals


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("frames_out", nargs="+")
    ap.add_argument("--marks", default=None)
    ap.add_argument("--window", default=None)
    ap.add_argument("--cap-fps", type=float, default=None)
    args = ap.parse_args(argv)

    intervals = _read_frames(args.frames_out)

    marks = []
    if args.marks and os.path.exists(args.marks):
        with open(args.marks) as f:
            marks = read_marks(f.read().splitlines())
    elif args.marks is None:
        # default marks next to? try common
        pass

    selected_intervals = [iv for _, iv in intervals]
    incomplete = False
    scene_failed_note = None

    if args.window is not None:
        try:
            start, end = window_bounds(marks, args.window)
        except SystemExit:
            print("missing start mark for window %s" % args.window, file=sys.stderr)
            raise SystemExit(2)
        if end is None:
            incomplete = True
            end = intervals[-1][0] if intervals else start
        selected_intervals = [iv for (ep, iv) in intervals if start <= ep <= end]
        base = "scene" if args.window == "scene" else args.window
        end_key = base + ".end"
        for m in marks:
            if m[0] == end_key and m[2] and str(m[2]).startswith("failed:"):
                scene_failed_note = m[2]

    p = profile(selected_intervals, args.cap_fps)

    if incomplete:
        print("window incomplete")
    if scene_failed_note is not None:
        print("scene ended early: %s" % scene_failed_note)
    print("frames: %d" % p["count"])
    print("mean ms: %.3f" % p["mean"])
    print("p50 ms: %.3f" % p["p50"])
    print("p99 ms: %.3f" % p["p99"])
    print("p99.9 ms: %.3f" % p["p99.9"])
    if args.cap_fps is not None:
        print("frames over 2x cap: %d" % p["over_2x_cap"])
    print("1%% low fps: %.3f" % p["one_pct_low_fps"])


if __name__ == "__main__":
    try:
        main()
    except SystemExit as e:
        raise
