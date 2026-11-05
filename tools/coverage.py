#!/usr/bin/env python3
"""Coverage: does each recording in a run dir cover the marks window (scene.start..scene.end)?

Rows: (recording, status). Status is "ok", "missing", "starts late by N ms",
"ends early by N ms", or "starts late by N ms; ends early by M ms".
A recording is covered when it starts at or before the window's first mark and
ends at or after the window's last mark (earlier starts are fine).

CLI: python3 tools/coverage.py RUN_DIR  (prints rows, exit 1 if any row is not ok)
"""
import datetime
import glob
import os
import re
import shutil
import subprocess
import sys


def _ms(s):
    return int(round(s * 1000))


def window(run_dir):
    """The scene window in epoch ms, or (None, None) when the marks are unusable."""
    start = end = None
    try:
        lines = open(os.path.join(run_dir, "marks.out")).read().splitlines()
    except OSError:
        return (None, None)
    for line in lines:
        m = re.match(r"mark scene\.(start|end) (\d+)", line)
        if not m:
            continue
        if m[1] == "start":
            start = int(m[2])
        else:
            end = int(m[2])
    if start is None or end is None:
        return (None, None)
    return (start, end)


def _epoch_ms_of(iso):
    return _ms(datetime.datetime.fromisoformat(iso).timestamp())


def _parse_utc(s):
    """A jfr_summary UTC stamp: '2026-11-03 12:12:43' or '12:12:43.052'."""
    for fmt in ("%Y-%m-%d %H:%M:%S.%f", "%Y-%m-%d %H:%M:%S"):
        try:
            return _ms(datetime.datetime.strptime(s, fmt).replace(tzinfo=datetime.timezone.utc).timestamp())
        except ValueError:
            continue
    raise ValueError(f"unparsable jfr Start: {s}")


def gc_stamps(run_dir):
    """gc.log: JVM start (run.opts start=) plus the first/last uptime stamps."""
    start = None
    with open(os.path.join(run_dir, "run.opts")) as f:
        for line in f:
            if line.startswith("start="):
                start = _epoch_ms_of(line[6:].strip())
                break
    if start is None:
        raise ValueError("run.opts has no start=")
    first = last = None
    with open(os.path.join(run_dir, "gc.log")) as f:
        for line in f:
            m = re.match(r"\[([0-9.]+)s\]", line)
            if m:
                last = float(m[1])
                if first is None:
                    first = float(m[1])
    return (start + _ms(first), start + _ms(last))


def jfr_stamps(run_dir, cmd="jfr"):
    """game.jfr: the recording's Start and Duration from `jfr summary`."""
    out = subprocess.run([cmd, "summary", os.path.join(run_dir, "game.jfr")],
                         capture_output=True, text=True, check=True).stdout
    start = dur_ms = None
    for line in out.splitlines():
        m = re.match(r"\s*Start: (.+) \(UTC\)", line)
        if m:
            tz = datetime.timezone.utc
            start = _parse_utc(m[1])
        m = re.match(r"\s*Duration: ([0-9.]+) s", line)
        if m:
            dur_ms = _ms(float(m[1]))
    if start is None or dur_ms is None:
        raise ValueError("jfr summary: no Start/Duration")
    return (start, start + dur_ms)


def perf_stamps(run_dir):
    """perf.data: first/last wall-clock stamp from `perf script -F tod`, in epoch ms.
    tod needs the clock data `perf record -k CLOCK_MONOTONIC` stores; the kernel
    refuses CLOCK_REALTIME for hardware events, and older recordings have no clock data."""
    r = subprocess.run(["perf", "script", "-F", "tod", "-i", os.path.join(run_dir, "perf.data")],
                       capture_output=True, text=True)
    if "clock data" in r.stderr:
        raise ValueError("unknown clock")
    first = last = None
    for line in r.stdout.splitlines():
        m = re.match(r"\s*(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d+)", line)
        if m:
            t = _ms(datetime.datetime.strptime(m[1], "%Y-%m-%d %H:%M:%S.%f").timestamp())
            if first is None:
                first = t
            last = t
    if first is None or last is None:
        raise ValueError("perf script: no timestamps")
    return (first, last)


def mangohud_stamps(run_dir):
    """MangoHud CSV: any csv in the run dir other than sysmon.csv (none = no checks)."""
    csvs = sorted(f for f in glob.glob(os.path.join(run_dir, "*.csv"))
                  if os.path.basename(f) != "sysmon.csv")
    if not csvs:
        return None  # absent is not a failure
    with open(csvs[0]) as f:
        rows = f.read().splitlines()
    stamps = []
    for r in rows:
        for fmt in ("%Y-%m-%d %H:%M:%S.%f", "%Y-%m-%d %H:%M:%S"):
            try:
                stamps.append(_ms(datetime.datetime.strptime(r.split(",")[0].strip(), fmt).timestamp()))
            except ValueError:
                continue
    if not stamps:
        raise ValueError("mangohud csv: no timestamp column")
    return (min(stamps), max(stamps))


def _first_last_epoch_ms(path):
    first = last = None
    with open(path) as f:
        for line in f:
            col0 = line.split(",")[0].split()[0]
            if not col0.isdigit():
                continue
            v = int(col0)
            if first is None:
                first = v
            last = v
    return (first, last)


def _opts(run_dir):
    out = {}
    try:
        with open(os.path.join(run_dir, "run.opts")) as f:
            for line in f:
                k, sep, v = line.rstrip("\n").partition("=")
                if sep:
                    out[k] = v
    except OSError:
        pass
    return out


def renderer_row(run_dir):
    """A Zink run is valid only when the console names a zink GL renderer."""
    renderer = _opts(run_dir).get("renderer", "")
    if renderer != "zink":
        return None  # nvidia/auto/older runs are unaffected
    try:
        text = open(os.path.join(run_dir, "console.txt"), errors="replace").read()
    except OSError:
        return ("renderer", "missing console.txt")
    for line in text.splitlines():
        low = line.lower()
        if "graphicscard:" in low and "zink" in low:
            return ("renderer", "ok")
    return ("renderer", "zink renderer not seen")


def _status(rec_start, rec_end, win_start, win_end):
    parts = []
    if rec_start is not None and rec_start > win_start:
        parts.append(f"starts late by {rec_start - win_start} ms")
    if rec_end is not None and rec_end < win_end:
        parts.append(f"ends early by {win_end - rec_end} ms")
    return "; ".join(parts) if parts else "ok"


def coverage(run_dir):
    ws, we = window(run_dir)
    if ws is None or we is None:
        return [("scene window", "no window")]
    rows = []
    for name in ("game.jfr", "gc.log", "frames.out", "sysmon.csv"):
        p = os.path.join(run_dir, name)
        if not os.path.exists(p):
            rows.append((name, "missing"))
            continue
        try:
            if name == "game.jfr":
                cmd = shutil.which("jfr")
                if cmd is None:
                    raise ValueError("jfr not on PATH")
                s, e = jfr_stamps(run_dir, cmd)
            elif name == "gc.log":
                s, e = gc_stamps(run_dir)
            else:
                s, e = _first_last_epoch_ms(p)
        except (ValueError, OSError, subprocess.SubprocessError) as exc:
            rows.append((name, f"unreadable ({exc})"))
            continue
        rows.append((name, _status(s, e, ws, we)))
    if os.path.exists(os.path.join(run_dir, "perf.data")):
        try:
            s, e = perf_stamps(run_dir)
            rows.append(("perf.data", _status(s, e, ws, we)))
        except (ValueError, OSError, subprocess.SubprocessError) as exc:
            rows.append(("perf.data", f"unreadable ({exc})"))
    try:
        mh = mangohud_stamps(run_dir)
        if mh is None:
            pass  # absent is not a failure: no row at all
        else:
            rows.append(("mangohud", _status(mh[0], mh[1], ws, we)))
    except ValueError as exc:
        rows.append(("mangohud", f"unreadable ({exc})"))
    r = renderer_row(run_dir)
    if r is not None:
        rows.append(r)
    return rows


def main(argv):
    run_dir = argv[1]
    rows = coverage(run_dir)
    for name, status in rows:
        print(f"{name:<12} {status}")
    return 1 if any(s != "ok" for _, s in rows) else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
