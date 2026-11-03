"""ab: run one scene arm by arm in ABBA order and compare (D8).

Usage:
    python3 tools/ab.py --scene S3 --list full --save M/S \
        --arm off="modWatchAsync=false" --arm on="modWatchAsync=true" \
        [--runs 3] [--order ABBA] [--json ab.json]

Calls the run script once per run in the planned order, writes ab.json after
every run, and resumes a rerun from that plan without repeating a finished run.
Boot is menu.ready - jvm.start, load is world.ready - menu.ready (the console's
"game loading took" is recorded next to it), and the scene window's p50/p99/1%
low come from frames.py.
"""

import argparse
import json
import os
import re
import shlex
import statistics
import subprocess
import sys

import frames

BASE_PROPS = "devAutoContinue=true devScene=stand devSceneSettleSec=20 devSceneSec=60"


def order_for(runs_per_arm, order="ABBA"):
    """3 runs per arm in ABBA order: ABBAAB; 2: ABBA."""
    cycle = list(order)
    seq = []
    while len(seq) < runs_per_arm * 2:
        seq.extend(cycle)
    return seq[: runs_per_arm * 2]


def remaining(plan, done):
    """Runs still to do; done is the number of finished runs, so a rerun never repeats one."""
    return plan[done:]


def _marks(run_dir):
    try:
        with open(os.path.join(run_dir, "marks.out")) as f:
            return frames.read_marks(f.read().splitlines())
    except OSError:
        return frames.read_marks([])


def boot_seconds(marks):
    start, ready = marks.get("jvm.start"), marks.get("menu.ready")
    if start is None or ready is None:
        return None
    return (ready[1] - start[1]) / 1000.0


def load_seconds(marks):
    ready, world = marks.get("menu.ready"), marks.get("world.ready")
    if ready is None or world is None:
        return None
    return (world[1] - ready[1]) / 1000.0


def game_loading_seconds(console_text):
    m = re.search(r"game loading took\s+([\d.]+)", console_text)
    return float(m.group(1)) if m else None


def window_profile(run_dir):
    marks = _marks(run_dir)
    try:
        ws, we = frames.window_bounds(marks, "scene")
    except SystemExit:
        return None
    if we is None:
        return None
    intervals = [iv for (ep, iv) in frames._read_frames([os.path.join(run_dir, "frames.out")]) if ws <= ep <= we]
    if not intervals:
        return None
    return frames.profile(intervals, 60.0)


def _stat(runs, key):
    vals = [r[key] for r in runs if r.get(key) is not None]
    if not vals:
        return None
    return {"n": len(vals), "median": statistics.median(vals), "min": min(vals), "max": max(vals)}


def _overlap(a_runs, b_runs, key):
    a = [r[key] for r in a_runs if r.get(key) is not None]
    b = [r[key] for r in b_runs if r.get(key) is not None]
    if not a or not b:
        return None
    return not (max(a) < min(b) or max(b) < min(a))


def summary(record):
    names = [a["name"] for a in record["arms"]]
    per = {a: [r for r in record["done"] if r["arm"] == a] for a in names}
    out = {"arms": {}}
    for a in names:
        runs = per[a]
        out["arms"][a] = {
            "runs": len(runs),
            "short": len(runs) < record["runs_per_arm"],
            "boot": _stat(runs, "boot_s"),
            "load": _stat(runs, "load_s"),
            "p50": _stat(runs, "p50_ms"),
            "p99": _stat(runs, "p99_ms"),
            "low1": _stat(runs, "low1_fps"),
        }
    out["overlap"] = _overlap(per[names[0]], per[names[1]], "boot_s") if len(names) > 1 else None
    return out


def _fmt(stat):
    if stat is None:
        return "n/a"
    return "median %s, min %s, max %s (n %d)" % (
        "%.1f" % stat["median"], "%.1f" % stat["min"], "%.1f" % stat["max"], stat["n"])


def summary_text(record):
    s = summary(record)
    lines = []
    for name, arm in s["arms"].items():
        short = " SHORT (%d/%d)" % (arm["runs"], record["runs_per_arm"]) if arm["short"] else ""
        lines.append("arm %s%s: boot %s s; load %s s; p50 %s ms; p99 %s ms; 1%% low %s fps" % (
            name, short, _fmt(arm["boot"]), _fmt(arm["load"]), _fmt(arm["p50"]), _fmt(arm["p99"]), _fmt(arm["low1"])))
    if s["overlap"] is None:
        lines.append("boot ranges: not comparable")
    elif s["overlap"]:
        lines.append("boot ranges overlap")
    else:
        lines.append("boot ranges do not overlap")
    return "\n".join(lines)


def _write_json(path, record):
    tmp = path + ".tmp"
    with open(tmp, "w") as f:
        json.dump(record, f, indent=2, sort_keys=True)
        f.write("\n")
    os.replace(tmp, path)


def _same_plan(old, new):
    keys = ("scene", "list", "save", "arms", "runs_per_arm", "order")
    return all(old.get(k) == new.get(k) for k in keys)


def run_once(run_sh, scene, list_name, save, props, out_dir, run_args=""):
    before = set(os.listdir(out_dir)) if os.path.isdir(out_dir) else set()
    argv = [run_sh, "--scene", scene, "--list", list_name, "--save", save,
            "--props", props, "--out", out_dir] + shlex.split(run_args)
    proc = subprocess.run(argv)
    if proc.returncode != 0:
        raise SystemExit("run.sh failed with %d" % proc.returncode)
    os.makedirs(out_dir, exist_ok=True)
    new = sorted(d for d in os.listdir(out_dir) if d not in before)
    if len(new) != 1:
        raise SystemExit("expected one new run folder under %s, found %s" % (out_dir, new))
    return os.path.join(out_dir, new[0])


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--scene", required=True)
    ap.add_argument("--list", required=True)
    ap.add_argument("--save", required=True)
    ap.add_argument("--arm", action="append", required=True, help="NAME=PROPS, two arms")
    ap.add_argument("--arm-run-args", action="append", default=[], metavar="NAME=ARGS",
                    help="extra run.sh arguments for one arm, e.g. A='--renderer nvidia'")
    ap.add_argument("--runs", type=int, default=3)
    ap.add_argument("--order", default="ABBA")
    ap.add_argument("--out", default=os.path.join(os.path.expanduser("~"), "Zomboid/pzbutter-runs"))
    ap.add_argument("--json", default="ab.json")
    ap.add_argument("--run-sh", default=os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "tools", "run.sh"))
    args = ap.parse_args(argv)

    arms = []
    for spec in args.arm:
        name, _, props = spec.partition("=")
        arms.append({"name": name.strip(), "props": props.strip(), "run_args": ""})
    by_name = {a["name"]: a for a in arms}
    for spec in args.arm_run_args:
        name, _, extra = spec.partition("=")
        arm = by_name.get(name.strip())
        if arm is None:
            raise SystemExit("--arm-run-args names unknown arm: %s" % name.strip())
        arm["run_args"] = extra.strip()

    record = {"scene": args.scene, "list": args.list, "save": args.save, "arms": arms,
              "runs_per_arm": args.runs, "order": order_for(args.runs, args.order), "done": []}
    if os.path.exists(args.json):
        try:
            with open(args.json) as f:
                old = json.load(f)
        except ValueError:
            old = None
        if old and _same_plan(old, record):
            record = old
            print("resuming %s: %d run(s) done" % (args.json, len(record["done"])))
        else:
            print("ab.json does not match this plan; starting a new one")

    todo = remaining(record["order"], len(record["done"]))
    print("plan %s, %d done, %d to run" % ("".join(record["order"]), len(record["done"]), len(todo)))
    for i, arm_name in enumerate(todo, start=len(record["done"])):
        arm = next(a for a in arms if a["name"] == arm_name)
        props = BASE_PROPS + ((" " + arm["props"]) if arm["props"] else "")
        run_dir = run_once(args.run_sh, args.scene, args.list, args.save, props, args.out,
                           arm.get("run_args", ""))
        marks = _marks(run_dir)
        prof = window_profile(run_dir)
        try:
            with open(os.path.join(run_dir, "console.txt")) as f:
                console = f.read()
        except OSError:
            console = ""
        record["done"].append({
            "arm": arm_name,
            "run_args": arm.get("run_args", ""),
            "dir": os.path.basename(run_dir),
            "boot_s": boot_seconds(marks),
            "load_s": load_seconds(marks),
            "game_loading_s": game_loading_seconds(console),
            "p50_ms": prof["p50"] if prof else None,
            "p99_ms": prof["p99"] if prof else None,
            "low1_fps": prof["one_pct_low_fps"] if prof else None,
        })
        _write_json(args.json, record)
        print("run %d/%d %s: %s" % (i + 1, len(record["order"]), arm_name, run_dir))

    print(summary_text(record))
    return record


if __name__ == "__main__":
    main()
