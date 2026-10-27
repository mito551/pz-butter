"""sysmon: sample CPU, memory, swap, faults and (optionally) GPU and a process.

Stdlib only. Run ``python3 sysmon.py OUT_CSV [--interval 1.0] [--pid PID]``.
"""

import argparse
import csv
import os
import signal
import subprocess
import sys
import time


def parse_stat(text):
    out = {}
    for line in text.splitlines():
        parts = line.split()
        if len(parts) < 2:
            continue
        key = parts[0]
        if key != "cpu" and not (key.startswith("cpu") and key[3:].isdigit()):
            continue
        try:
            vals = [int(x) for x in parts[1:]]
        except ValueError:
            continue
        out[key] = vals
    return out


def cpu_busy(prev, cur):
    result = {}
    keys = sorted((k for k in cur if k.startswith("cpu") and k[3:].isdigit()),
                  key=lambda k: int(k[3:]))
    for key in keys:
        p = prev.get(key)
        c = cur.get(key)
        if not p or not c or sum(c[:8]) - sum(p[:8]) <= 0:
            result[key] = 0.0
            continue
        idle_p = p[3] + (p[4] if len(p) > 4 else 0)
        idle_c = c[3] + (c[4] if len(c) > 4 else 0)
        # guest/guest_nice (fields 9-10) are already counted in user/nice
        delta_total = sum(c[:8]) - sum(p[:8])
        delta_idle = idle_c - idle_p
        busy = 100.0 * (delta_total - delta_idle) / delta_total
        result[key] = busy
    return result


def parse_meminfo(text):
    out = {}
    for line in text.splitlines():
        if ":" not in line:
            continue
        key, rest = line.split(":", 1)
        parts = rest.strip().split()
        if not parts:
            continue
        try:
            out[key.strip()] = int(parts[0])
        except ValueError:
            continue
    return out


def parse_vmstat(text):
    out = {}
    for line in text.splitlines():
        parts = line.split()
        if len(parts) != 2:
            continue
        try:
            out[parts[0]] = int(parts[1])
        except ValueError:
            continue
    return out


def _read_file(path):
    try:
        with open(path) as f:
            return f.read()
    except OSError:
        return ""


def _gpu_sample():
    try:
        out = subprocess.run(
            ["nvidia-smi", "--query-gpu=utilization.gpu,memory.used,clocks.gr",
             "--format=csv,noheader,nounits"],
            capture_output=True, text=True, timeout=5)
    except (FileNotFoundError, subprocess.TimeoutExpired, OSError):
        return "", "", ""
    if out.returncode != 0:
        return "", "", ""
    line = out.stdout.strip().splitlines()
    if not line:
        return "", "", ""
    parts = [p.strip() for p in line[0].split(",")]
    if len(parts) < 3:
        return "", "", ""
    return parts[0], parts[1], parts[2]


def _proc_sample(pid):
    rss_mb = ""
    threads = ""
    try:
        with open("/proc/%d/statm" % pid) as f:
            fields = f.read().split()
        rss_pages = int(fields[1])
        rss_mb = round(rss_pages * 4096 / (1024 * 1024), 1)
    except (OSError, ValueError, IndexError):
        rss_mb = ""
    try:
        with open("/proc/%d/status" % pid) as f:
            for line in f:
                if line.startswith("Threads:"):
                    threads = line.split()[1]
                    break
    except OSError:
        threads = ""
    return rss_mb, threads


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("out_csv")
    ap.add_argument("--interval", type=float, default=1.0)
    ap.add_argument("--pid", type=int, default=None)
    args = ap.parse_args(argv)

    stop = {"v": False}

    def handler(signum, frame):
        stop["v"] = True

    signal.signal(signal.SIGTERM, handler)
    signal.signal(signal.SIGINT, handler)

    # discover cores from first stat
    first_stat = parse_stat(_read_file("/proc/stat"))
    core_keys = sorted((k for k in first_stat if k.startswith("cpu") and k[3:].isdigit()),
                      key=lambda k: int(k[3:]))
    header = ["epoch_ms"] + core_keys + ["mem_avail_mb", "swap_used_mb",
             "pswpin", "pswpout", "pgmajfault",
             "gpu_util", "gpu_mem_mb", "gpu_clock_mhz"]
    if args.pid is not None:
        header += ["rss_mb", "threads"]

    f = open(args.out_csv, "w", newline="")
    w = csv.writer(f)
    w.writerow(header)
    f.flush()

    prev_stat = None
    prev_vm = None
    cur_stat = first_stat
    cur_vm = parse_vmstat(_read_file("/proc/vmstat"))

    while not stop["v"]:
        now_ms = int(time.time() * 1000)
        if prev_stat is None:
            busy = {k: 0.0 for k in core_keys}
        else:
            busy = cpu_busy(prev_stat, cur_stat)
        mi = parse_meminfo(_read_file("/proc/meminfo"))
        vm = cur_vm
        if prev_vm is None:
            d_pswpin = 0
            d_pswpout = 0
            d_pgmaj = 0
        else:
            d_pswpin = vm.get("pswpin", 0) - prev_vm.get("pswpin", 0)
            d_pswpout = vm.get("pswpout", 0) - prev_vm.get("pswpout", 0)
            d_pgmaj = vm.get("pgmajfault", 0) - prev_vm.get("pgmajfault", 0)
        mem_avail_mb = round(mi.get("MemAvailable", 0) / 1024.0, 1)
        swap_total = mi.get("SwapTotal", 0)
        swap_free = mi.get("SwapFree", 0)
        swap_used_mb = round((swap_total - swap_free) / 1024.0, 1)
        gpu_util, gpu_mem, gpu_clock = _gpu_sample()

        row = [now_ms] + [round(busy.get(k, 0.0), 1) for k in core_keys] + [
            mem_avail_mb, swap_used_mb, d_pswpin, d_pswpout, d_pgmaj,
            gpu_util, gpu_mem, gpu_clock]
        if args.pid is not None:
            # pid liveness check
            if not os.path.exists("/proc/%d" % args.pid):
                break
            rss, thr = _proc_sample(args.pid)
            row += [rss, thr]
        w.writerow(row)
        f.flush()

        prev_stat = cur_stat
        prev_vm = vm
        time.sleep(args.interval)
        if stop["v"]:
            break
        # refresh
        cur_stat = parse_stat(_read_file("/proc/stat"))
        cur_vm = parse_vmstat(_read_file("/proc/vmstat"))
        if args.pid is not None and not os.path.exists("/proc/%d" % args.pid):
            break

    f.close()


if __name__ == "__main__":
    main()
