"""Native profile (D6): perf report dso/symbol shares with JNI callers.

Reads one run folder made by tools/run.sh:
  perf-<pid>.map  JIT symbol map copied at exit (Optional)
  perf.data       perf record --call-graph dwarf capture (needs --perf)
  game.jfr        NativeMethodSample stacks name the Java callers
  marks.out       the scene window, via frames.py

Refuses (exit 1, reason on stderr) without a window, without JFR coverage
of the window, or without perf.data -- never profiles the whole run.
Writes natives.json next to the inputs so report.py reproduces from the
folder alone. No timestamps in the JSON.
"""
import argparse
import contextlib
import glob
import json
import os
import re
import shutil
import subprocess
import sys

import coverage
import frames
import owners

PERF = shutil.which("perf") or "perf"

_SPLIT = re.compile(r"\s{2,}")
_SAMPLE_TYPE = re.compile(r"^\[.\]\s")


def parse_report(text):
    """Rows (comm, dso, symbol, percent) from perf report --stdio.

    Assumes --sort comm,dso,sym and --no-children; the symbol keeps its
    single spaces, parens and commas (the [.] sample-type prefix is cut).
    """
    rows = []
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = _SPLIT.split(line)
        if len(parts) < 4 or not parts[0].endswith("%"):
            continue
        try:
            pct = float(parts[0].rstrip("%"))
        except ValueError:
            continue
        symbol = _SAMPLE_TYPE.sub("", "  ".join(parts[3:]))
        rows.append((parts[1], parts[2], symbol, pct))
    return rows


def _norm_lib(name):
    n = name.lower()
    if n.startswith("lib"):
        n = n[3:]
    n = re.sub(r"\.so(\.\d+)*$", "", n)
    if n.endswith("64"):
        n = n[:-2]
    return n


def _lib_for_dso(dso, libs):
    # ponytail: measured dso names are matched by normalized name
    # (libLighting64.so <-> libLighting64, libfmodstudio.so <-> libfmod).
    # Ceiling: a renamed library in one place starts a separate row; the
    # upgrade path is reading the ELF soname from the game's natives/ dir.
    nd = _norm_lib(dso)
    for lib in libs:
        nl = _norm_lib(lib)
        if nd == nl or nd.startswith(nl) or nl.startswith(nd):
            return lib
    return None


def shares(rows, min_pct=5.0, top=10):
    """Per-thread dso shares and, above min_pct of all samples, top symbols."""
    grand = sum(p for *_, p in rows)
    per_thread = {}
    threads = {}
    totals = {}
    symbols = {}
    for comm, dso, sym, pct in rows:
        per_thread[comm] = per_thread.get(comm, 0.0) + pct
        threads.setdefault(comm, {}).setdefault(dso, 0.0)
        threads[comm][dso] += pct
        totals[dso] = totals.get(dso, 0.0) + pct
        symbols.setdefault(dso, {}).setdefault(sym, 0.0)
        symbols[dso][sym] += pct
    if grand > 0:
        for comm, dsos in threads.items():
            total = per_thread[comm]
            if total <= 0:
                continue
            for dso in dsos:
                dsos[dso] = dsos[dso] * 100.0 / total
    libraries = {}
    for dso, total in totals.items():
        pct_all = total * 100.0 / grand if grand else 0.0
        if pct_all <= min_pct:
            continue
        ranked = sorted(symbols[dso].items(), key=lambda kv: (-kv[1], kv[0]))[:top]
        libraries[dso] = {"pct": pct_all, "symbols": ranked}
    return {"threads": threads, "libraries": libraries}


def jni_callers(stacks):
    """lib -> [(Java caller, samples)], from JFR NativeMethodSample stacks.

    A stack is class.method frames, leaf first. The first frame whose class
    is a JNI stub (owners._jni_lib) names the library; the frame just below
    it is the Java caller. Stacks without a stub are ignored.
    """
    counts = {}
    for stack in stacks:
        for i, frame in enumerate(stack):
            if "." not in frame:
                continue
            lib = owners._jni_lib(frame.rsplit(".", 1)[0])
            if lib is None:
                continue
            caller = stack[i + 1] if i + 1 < len(stack) else "[no caller]"
            entry = counts.setdefault(lib, {})
            entry[caller] = entry.get(caller, 0) + 1
            break
    return {lib: sorted(c.items(), key=lambda kv: (-kv[1], kv[0])) for lib, c in counts.items()}


@contextlib.contextmanager
def restore_map(run_dir):
    """Put the run's perf-<pid>.map back at /tmp/perf-<pid>.map for perf report.

    Missing map: proceed (JIT frames stay unresolved). The map is removed
    afterwards, as run.sh copied it into the run folder precisely for this.
    """
    maps = sorted(glob.glob(os.path.join(run_dir, "perf-*.map")))
    placed = None
    if maps:
        # ponytail: one map per run (the launch pid).
        placed = os.path.join("/tmp", os.path.basename(maps[0]))
        shutil.copy2(maps[0], placed)
    try:
        yield
    finally:
        if placed and os.path.exists(placed):
            os.remove(placed)


def run_report(perf_data):
    cmd = [PERF, "report", "--stdio", "--no-children", "--sort", "comm,dso,sym", "-i", perf_data]
    return subprocess.run(cmd, check=True, capture_output=True, text=True).stdout


def main(argv=None):
    ap = argparse.ArgumentParser(prog="natives")
    ap.add_argument("run_dir")
    ap.add_argument("--window", default="scene")
    args = ap.parse_args(argv)

    run_dir = args.run_dir
    marks_path = os.path.join(run_dir, "marks.out")
    try:
        with open(marks_path, encoding="utf-8", errors="replace") as f:
            marks = frames.read_marks(f.read().splitlines())
    except OSError:
        marks = []
    try:
        if args.window == "manual":
            ws, we, _ = frames.manual_window(marks)
        else:
            ws, we = frames.window_bounds(marks, args.window)
    except SystemExit:
        ws = we = None
    if ws is None or we is None:
        print("no window", file=sys.stderr)
        raise SystemExit(1)

    try:
        status = coverage._status(*owners._jfr_stamps(run_dir), ws, we)
    except (OSError, ValueError, subprocess.SubprocessError) as exc:
        status = f"unreadable ({exc})"
    if status != "ok":
        print("game.jfr " + status, file=sys.stderr)
        raise SystemExit(1)

    perf_data = os.path.join(run_dir, "perf.data")
    if not os.path.isfile(perf_data):
        print("no perf.data", file=sys.stderr)
        raise SystemExit(1)

    stacks = []
    for _type, values in owners._parse_events(run_dir, ("jdk.NativeMethodSample",)):
        sample = owners._sample_of(values)
        if sample is None:
            continue
        when, _thread, stack = sample
        if ws <= when < we:
            stacks.append(stack)
    callers = jni_callers(stacks)

    with restore_map(run_dir):
        rows = parse_report(run_report(perf_data))
    result = shares(rows)

    for comm in sorted(result["threads"]):
        print(comm)
        for dso, pct in sorted(result["threads"][comm].items(), key=lambda kv: -kv[1]):
            print("  %5.1f%%  %s" % (pct, dso))
    print("")
    print("libraries above %.0f%%:" % 5)
    libraries = {}
    for dso, info in sorted(result["libraries"].items(), key=lambda kv: -kv[1]["pct"]):
        lib = _lib_for_dso(dso, callers)
        jni = callers.get(lib, []) if lib else []
        print("  %5.2f%%  %s" % (info["pct"], dso))
        for sym, pct in info["symbols"]:
            print("    %5.2f%%  %s" % (pct, sym))
        for caller, n in jni:
            print("    jni %s  %d" % (caller, n))
        libraries[dso] = {
            "pct": info["pct"],
            "symbols": [[s, p] for s, p in info["symbols"]],
            "jni_callers": [[c, n] for c, n in jni],
        }

    out = {"window": [ws, we], "threads": result["threads"], "libraries": libraries}
    with open(os.path.join(run_dir, "natives.json"), "w", encoding="utf-8") as f:
        json.dump(out, f, indent=2, sort_keys=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
