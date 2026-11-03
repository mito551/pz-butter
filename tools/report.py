"""report: the findings report from run folders and notes alone (D7).

Usage:
    python3 tools/report.py RUN_DIR... --notes DIR --out FILE

Every number comes from the run folders (frames.py, owners.json, natives.json and
the logs); the notes supply the findings. Nothing else is read, so two runs over
the same inputs write identical bytes.
"""

import argparse
import glob
import json
import os
import re
import sys

import frames

CAP_FPS = 60.0
RISK = {"low": 1, "medium": 2, "high": 4}


def _meta(run_dir):
    meta = {}
    try:
        with open(os.path.join(run_dir, "run.opts")) as f:
            for line in f:
                line = line.rstrip("\n")
                if "=" in line and not line.startswith((" ", "#")):
                    k, v = line.split("=", 1)
                    meta[k] = v
    except OSError:
        pass
    return meta


def _marks(run_dir):
    try:
        with open(os.path.join(run_dir, "marks.out")) as f:
            return frames.read_marks(f.read().splitlines())
    except OSError:
        return frames.read_marks([])


def _window(run_dir):
    try:
        return frames.window_bounds(_marks(run_dir), "scene")
    except SystemExit:
        return None


def _intervals(run_dir):
    return frames._read_frames([os.path.join(run_dir, "frames.out")])


def _profile(run_dir, ws, we):
    return frames.profile([iv for (ep, iv) in _intervals(run_dir) if ws <= ep <= we], CAP_FPS)


def _present(run_dir, ws, we):
    rows = []
    try:
        with open(os.path.join(run_dir, "present.out")) as f:
            for line in f:
                p = line.split()
                if len(p) < 6:
                    continue
                try:
                    rows.append((int(p[0]), float(p[2]), float(p[3])))
                except ValueError:
                    continue
    except OSError:
        return None
    rows = [r for r in rows if ws <= r[0] <= we]
    if not rows:
        return None
    return {
        "interval": frames.profile([r[1] for r in rows]),
        "render": frames.profile([r[2] for r in rows]),
    }


def _gpu_busy(run_dir, ws, we):
    try:
        with open(os.path.join(run_dir, "sysmon.csv")) as f:
            lines = [l.strip() for l in f if l.strip()]
    except OSError:
        return None
    if not lines:
        return None
    head = lines[0].split(",")
    if "gpu_util" not in head:
        return None
    col = head.index("gpu_util")
    vals = []
    for line in lines[1:]:
        p = line.split(",")
        try:
            ep, v = int(p[0]), float(p[col])
        except (ValueError, IndexError):
            continue
        if ws <= ep <= we and v >= 0:
            vals.append(v)
    return (sum(vals) / len(vals)) if vals else None


GC_LINE = re.compile(r"^\[([\d.]+)s\].*?Pause.*?([\d.]+)ms\s*$")


def _gc(run_dir, ws, we, slow_spans):
    """Pause count and total in the window; the ones overlapping a slow frame, when jvm.start maps uptime to epoch."""
    jvm_start = None
    marks = _marks(run_dir)
    start = marks.epoch("jvm.start")
    if start is not None:
        jvm_start = start
    count = 0
    total_ms = 0.0
    slow_count = 0
    slow_ms = 0.0
    try:
        with open(os.path.join(run_dir, "gc.log")) as f:
            for line in f:
                m = GC_LINE.match(line.rstrip("\n"))
                if not m:
                    continue
                uptime_s, dur_ms = float(m.group(1)), float(m.group(2))
                if jvm_start is not None:
                    epoch = jvm_start + int(uptime_s * 1000)
                    if epoch < ws or epoch > we:
                        continue
                count += 1
                total_ms += dur_ms
                if jvm_start is not None:
                    a, b = jvm_start + int(uptime_s * 1000), jvm_start + int((uptime_s + dur_ms / 1000.0) * 1000)
                    if any(a <= sp_end and b >= sp_start for (sp_start, sp_end) in slow_spans):
                        slow_count += 1
                        slow_ms += dur_ms
    except OSError:
        return None
    return {"count": count, "total_ms": total_ms, "slow_count": slow_count, "slow_ms": slow_ms, "mapped": jvm_start is not None}


def _slow_spans(run_dir):
    spans = []
    for (ep, iv) in _intervals(run_dir):
        if iv > 2.0 * 1000.0 / CAP_FPS:
            spans.append((ep - int(iv), ep))
    return spans


def _json(run_dir, name):
    try:
        with open(os.path.join(run_dir, name)) as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


def _dev_lines(run_dir, ws, we):
    """spikes / hitches / stalls summaries when the files are in the run folder."""
    out = {}
    try:
        with open(os.path.join(run_dir, "spikes.out")) as f:
            rows = [l.split() for l in f if l.strip()]
        rows = [r for r in rows if len(r) >= 4 and ws <= int(r[0]) <= we]
        if rows:
            ctx = {}
            for r in rows:
                ctx[r[3]] = ctx.get(r[3], 0) + 1
            out["spikes"] = {"count": len(rows), "contexts": dict(sorted(ctx.items()))}
    except (OSError, ValueError):
        pass
    for name, key in (("hitches.out", "hitches"), ("stalls.out", "stalls")):
        try:
            with open(os.path.join(run_dir, name)) as f:
                rows = [l for l in f if l.strip()]
        except OSError:
            continue
        if rows:
            out[key] = len(rows)
    return out


def parse_note(path):
    head = {}
    body = []
    started = False
    with open(path) as f:
        for line in f:
            line = line.rstrip("\n")
            m = re.match(r"^([A-Za-z_0-9]+):\s*(.*)$", line)
            if not started and m:
                head[m.group(1)] = m.group(2).strip()
            elif not started and not line.strip():
                started = True
            else:
                started = True
                body.append(line)
    return {"file": os.path.basename(path), "keys": head, "body": "\n".join(body).strip()}


def _gain(keys):
    m = re.search(r"[-+]?\d+(?:\.\d+)?", keys.get("gain", ""))
    return float(m.group(0)) if m else 0.0


def _risk_weight(keys):
    return RISK.get(keys.get("risk", "").strip().lower(), 4)


def rank_notes(notes):
    """Best gain per risk first; report-only notes last, ties by file name."""
    def score(n):
        gain, risk = _gain(n["keys"]), _risk_weight(n["keys"])
        return (gain / risk if gain > 0 else -1.0, n["file"])
    return sorted(notes, key=lambda n: (-score(n)[0], score(n)[1]))


def _fmt(v, width=1):
    return ("%." + str(width) + "f") % v


def _section_run(run_dir):
    meta = _meta(run_dir)
    window = _window(run_dir)
    lines = ["## " + os.path.basename(os.path.normpath(run_dir)), ""]
    tags = []
    if meta.get("scene"):
        tags.append("scene " + meta["scene"])
    if meta.get("list"):
        tags.append("list " + meta["list"])
    if tags:
        lines.append("- " + ", ".join(tags))
    if window is None:
        lines += ["", "Not analysed: no scene.start mark (or the JFR does not cover it).", ""]
        return "\n".join(lines)
    ws, we = window
    lines.append("- window " + _fmt((we - ws) / 1000.0) + " s")
    lines.append("")

    p = _profile(run_dir, ws, we)
    if p["count"] == 0:
        lines += ["Not analysed: no frame rows inside the window.", ""]
        return "\n".join(lines)
    lines += [
        "### Frame profile",
        "frames %d, mean %s ms, p50 %s, p99 %s, p99.9 %s, 1%% low %s fps, over 2x cap %d"
        % (p["count"], _fmt(p["mean"]), _fmt(p["p50"]), _fmt(p["p99"]), _fmt(p["p99.9"]),
           _fmt(p["one_pct_low_fps"]), p["over_2x_cap"]),
        "",
    ]

    wall = ["### The wall"]
    wall.append("- game step p50 %s ms, p99 %s ms" % (_fmt(p["p50"], 3), _fmt(p["p99"], 3)))
    pr = _present(run_dir, ws, we)
    if pr:
        wall.append("- presented interval p50 %s ms, p99 %s ms; render p50 %s ms, p99 %s ms"
                    % (_fmt(pr["interval"]["p50"]), _fmt(pr["interval"]["p99"]),
                       _fmt(pr["render"]["p50"]), _fmt(pr["render"]["p99"])))
    gpu = _gpu_busy(run_dir, ws, we)
    if gpu is not None:
        wall.append("- GPU busy %s %%" % _fmt(gpu))
    gc = _gc(run_dir, ws, we, _slow_spans(run_dir))
    if gc:
        mapped = "" if gc["mapped"] else " (no jvm.start mark: whole run)"
        wall.append("- GC pauses: %d, %s ms total%s; in slow frames: %d, %s ms"
                    % (gc["count"], _fmt(gc["total_ms"]), mapped, gc["slow_count"], _fmt(gc["slow_ms"])))
    lines += wall + [""]

    owners = _json(run_dir, "owners.json")
    if owners and owners.get("owners"):
        totals = owners.get("totals") or {}

        def pct(count, total):
            return (100.0 * float(count) / float(total)) if total else 0.0

        lines += ["### Owners", "| owner | thread % | slow % | alloc % |", "|---|---|---|---|"]
        all_owners = dict(owners.get("owners") or {})
        slow = owners.get("slow_owners") or {}
        alloc = owners.get("alloc_owners") or {}
        for name in sorted(all_owners, key=lambda k: (-float(all_owners[k]), k)):
            lines.append("| %s | %s | %s | %s |" % (
                name, _fmt(pct(all_owners[name], totals.get("samples")), 2),
                _fmt(pct(slow.get(name, 0.0), totals.get("slow_samples")), 2),
                _fmt(pct(alloc.get(name, 0.0), totals.get("alloc_samples")), 2)))
        lines.append("")

    natives = _json(run_dir, "natives.json")
    libs = (natives or {}).get("libraries") or {}
    if libs:
        lines += ["### Natives", "| library | % | top symbols |", "|---|---|---|"]
        for dso in sorted(libs, key=lambda k: (-float(libs[k].get("pct", 0.0)), k)):
            syms = ", ".join("%s (%s%%)" % (s, _fmt(pct, 2)) for s, pct in (libs[dso].get("symbols") or [])[:3])
            lines.append("| %s | %s | %s |" % (dso, _fmt(float(libs[dso].get("pct", 0.0)), 2), syms))
        lines.append("")

    dev = _dev_lines(run_dir, ws, we)
    if "spikes" in dev:
        s = dev["spikes"]
        lines += ["### Spikes", "%d in the window (%s)" % (s["count"], ", ".join("%s %d" % kv for kv in s["contexts"].items())), ""]
    if "hitches" in dev:
        lines += ["### Hitches", "%d lines" % dev["hitches"], ""]
    if "stalls" in dev:
        lines += ["### Stalls", "%d lines" % dev["stalls"], ""]
    return "\n".join(lines)


def _findings_table(notes):
    lines = ["| scene | window | cost | frequency | owner | cause | vehicle | parity | gain | risk |", "|---|---|---|---|---|---|---|---|---|---|"]
    for n in notes:
        k = n["keys"]
        lines.append("| %s | %s | %s | %s | %s | %s | %s | %s | %s | %s |" % (
            k.get("scene", ""), k.get("window", ""), k.get("cost_p50_ms", "") + ("/" + k.get("cost_p99_ms", "") if k.get("cost_p99_ms") else ""),
            k.get("frequency", ""), k.get("owner", ""), k.get("cause", ""), k.get("vehicle", ""),
            k.get("parity", ""), k.get("gain", ""), k.get("risk", "")))
    return lines


def report_text(run_dirs, notes_dir):
    notes = []
    for path in sorted(glob.glob(os.path.join(notes_dir, "*"))):
        if os.path.isfile(path) and os.path.splitext(path)[1].lower() in (".md", ".txt"):
            notes.append(parse_note(path))
    ranked = rank_notes(notes)
    findings = [n for n in ranked if (n["keys"].get("vehicle") or "").strip().lower() != "none"]
    measured = [n for n in ranked if (n["keys"].get("vehicle") or "").strip().lower() == "none"]

    lines = ["# PZ Butter findings", "",
             "Runs: %d. Notes: %d. Built from the run folders and the notes alone." % (len(run_dirs), len(notes)), ""]
    for run_dir in sorted(os.path.normpath(d) for d in run_dirs):
        lines += [_section_run(run_dir), ""]

    lines += ["## Findings", ""]
    if findings:
        lines += _findings_table(findings)
        lines.append("")
        for n in findings:
            title = n["keys"].get("cause") or n["keys"].get("owner") or n["file"]
            lines += ["### " + title, ""]
            if n["keys"]:
                lines += ["- %s: %s" % (k, v) for k, v in n["keys"].items()]
                lines.append("")
            if n["body"]:
                lines += [n["body"], ""]
    else:
        lines += ["No findings notes.", ""]

    lines += ["## Measured, does not matter", ""]
    if measured:
        for n in measured:
            lines.append("- %s, %s (%s ms): %s" % (
                n["keys"].get("scene", n["file"]), n["keys"].get("cause", n["file"]),
                n["keys"].get("cost_p50_ms", "?"), n["body"]))
    else:
        lines.append("None recorded.")
    lines.append("")
    return "\n".join(lines)


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("run_dirs", nargs="+")
    ap.add_argument("--notes", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args(argv)
    text = report_text(args.run_dirs, args.notes)
    out_dir = os.path.dirname(os.path.abspath(args.out))
    os.makedirs(out_dir, exist_ok=True)
    with open(args.out, "w") as f:
        f.write(text)
    print("wrote %s" % args.out)


if __name__ == "__main__":
    main()
