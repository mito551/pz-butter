"""netsum: line packet counts up with the frame log and the OS UDP counters.

Stdlib only. Usage:
    python3 netsum.py RUN_DIR [--slow-ms 50]

RUN_DIR is a tools/mp.sh run folder. It reads client/net.out and
server/net.out (written by NetStats), client/frames.out (FrameClock) and the
run's snmp.start/snmp.end samples of /proc/net/snmp.
"""

import argparse
import os
import sys

SLOW_MS = 50.0


def parse_net(lines):
    """NetStats lines -> list of (epoch, side, type, packets_in, packets_out, bytes_in, bytes_out)."""
    out = []
    if isinstance(lines, str):
        lines = lines.splitlines()
    for line in lines:
        p = line.split()
        if len(p) != 7:
            continue
        try:
            out.append((int(p[0]), p[1], p[2], int(p[3]), int(p[4]), int(p[5]), int(p[6])))
        except ValueError:
            continue
    return out


def parse_frames(lines):
    """FrameClock lines -> list of (epoch, step_seconds)."""
    out = []
    if isinstance(lines, str):
        lines = lines.splitlines()
    for line in lines:
        p = line.split()
        if len(p) < 4:
            continue
        try:
            out.append((int(p[0]), float(p[3])))
        except ValueError:
            continue
    return out


def parse_snmp(text):
    """Udp InDatagrams/OutDatagrams from /proc/net/snmp text (or a compact 'epoch in out' line)."""
    if isinstance(text, list):
        text = "\n".join(text)
    if not text:
        return None
    lines = text.splitlines()
    header = None
    fields = None
    for line in lines:
        p = line.split()
        if not p:
            continue
        if p[0] == "Udp:" and "InDatagrams" in p:
            header = p[1:]
            continue
        if p[0] == "Udp:" and header is not None and len(p) > 1:
            try:
                vals = [int(x) for x in p[1:]]
            except ValueError:
                continue
            fields = dict(zip(header, vals))
            break
    if fields is not None:
        return fields.get("InDatagrams"), fields.get("OutDatagrams")
    for line in reversed(lines):
        p = line.split()
        if len(p) == 3:
            try:
                return int(p[1]), int(p[2])
            except ValueError:
                pass
    return None


def summarize(net_lines, frame_lines, snmp_start="", snmp_end="", slow_ms=SLOW_MS):
    recs = parse_net(net_lines)
    totals = {}
    per_second = {}
    for epoch, side, _type, pin, pout, bin_, bout in recs:
        t = totals.setdefault(side, [0, 0, 0, 0])
        t[0] += pin
        t[1] += pout
        t[2] += bin_
        t[3] += bout
        sec = epoch // 1000
        s = per_second.setdefault((sec, side), [0, 0, 0, 0])
        s[0] += pin
        s[1] += pout
        s[2] += bin_
        s[3] += bout

    slow = []
    for epoch, step in parse_frames(frame_lines):
        if step >= slow_ms:
            slow.append((epoch, step))
    slow_by_second = {}
    for epoch, step in slow:
        sec = epoch // 1000
        n, worst = slow_by_second.get(sec, (0, 0.0))
        slow_by_second[sec] = (n + 1, max(worst, step))

    rows = []
    for sec in sorted(slow_by_second):
        n, worst = slow_by_second[sec]
        sides = {side: vals for (s, side), vals in per_second.items() if s == sec}
        rows.append((sec, n, worst, sides))

    snmp = None
    a, b = parse_snmp(snmp_start), parse_snmp(snmp_end)
    if a and b:
        din = b[0] - a[0]
        dout = b[1] - a[1]
        if din < 0:
            din = b[0]
        if dout < 0:
            dout = b[1]
        snmp = (din, dout)

    ratio = None
    if snmp and (snmp[0] or snmp[1]):
        pin = sum(t[0] for t in totals.values())
        pout = sum(t[1] for t in totals.values())
        ratio = (pin / snmp[0] if snmp[0] else None, pout / snmp[1] if snmp[1] else None)

    return {
        "totals": totals,
        "snmp": snmp,
        "ratio": ratio,
        "slow": slow,
        "rows": rows,
    }


def _read(path):
    try:
        with open(path, encoding="ascii", errors="replace") as f:
            return f.read()
    except OSError:
        return ""


def load_run(run_dir):
    nets = {}
    for side in ("client", "server"):
        nets[side] = _read(os.path.join(run_dir, side, "net.out"))
    frames = _read(os.path.join(run_dir, "client", "frames.out"))
    start = _read(os.path.join(run_dir, "snmp.start"))
    end = _read(os.path.join(run_dir, "snmp.end"))
    return nets, frames, start, end


def main(argv):
    ap = argparse.ArgumentParser(description="summarise net.out against frames.out and /proc/net/snmp")
    ap.add_argument("run_dir")
    ap.add_argument("--slow-ms", type=float, default=SLOW_MS)
    args = ap.parse_args(argv)
    nets, frames, start, end = load_run(args.run_dir)
    net_lines = []
    for side in ("client", "server"):
        net_lines.extend(nets[side].splitlines())
    s = summarize(net_lines, frames, start, end, args.slow_ms)

    if not s["totals"]:
        print("no net.out lines in %s" % args.run_dir)
        return 1
    for side in sorted(s["totals"]):
        pin, pout, bin_, bout = s["totals"][side]
        print("%s packets in=%d out=%d bytes in=%d out=%d" % (side, pin, pout, bin_, bout))
    if s["snmp"]:
        print("snmp Udp in=%d out=%d" % s["snmp"])
    else:
        print("snmp Udp: no samples")
    if s["ratio"]:
        rin, rout = s["ratio"]
        print("ratio packets/snmp in=%s out=%s" % (
            "n/a" if rin is None else "%.3f" % rin,
            "n/a" if rout is None else "%.3f" % rout))
    print("slow frames (>=%g ms)=%d" % (args.slow_ms, len(s["slow"])))
    for sec, n, worst, sides in s["rows"]:
        parts = ["%s in=%d out=%d" % (side, v[0], v[1]) for side, v in sorted(sides.items())]
        print("second %d slow=%d worst=%.1fms %s" % (sec, n, worst, " ".join(parts)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
