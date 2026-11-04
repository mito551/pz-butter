#!/usr/bin/env python3
"""owners: attribute a run's JFR samples to their owners (Plan 13 D5).

Owner vocabulary: `pzbutter` for PZ Butter, `game`, a mod id, `lua:<mod id>`,
`native:<lib>`, `jdk`. Walk each sample from the leaf: skip java./jdk./sun.
frames; the first frame whose class prefix is in the mod table (or pzbutter.)
owns it; Kahlua frames (se.krka.kahlua) are `lua`, split by lua.out's per-file
ms; a JNI stub frame is `native:<lib>`; otherwise `game`, labelled
`game (patched by: <mods>)` when a mod patches that method. A run without
marks or a JFR that does not cover the window is refused with the reason.

CLI: python3 tools/owners.py RUN_DIR [--window NAME] [--thread MainThread]
                                [--cap-fps N] [--roots DIR ...]
Prints owner | share of the thread | share of slow-frame samples (>2x the
frame cap, from frames.out) | allocation share, and writes owners.json into
the run folder.
"""
import argparse
import datetime
import json
import os
import re
import shutil
import subprocess
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import coverage
import frames

MODS_LOCAL = os.path.expanduser("~/Zomboid/mods")
MODS_WORKSHOP = "/home/mitori/Sonic/SteamLibrary/steamapps/workshop/content/108600"
JFR = shutil.which("jfr") or "/usr/lib/jvm/java-25-openjdk/bin/jfr"

# JNI stub classes (Task 1 item 6): class prefix -> native library.
JNI_LIBS = [
    ("zombie.iso.LightingJNI", "libLighting64"),
    ("zombie.core.physics.Bullet", "libPZBullet64"),
    ("zombie.popman.ZombiePopulationManager", "libPZPopMan64"),
    ("zombie.pathfind.nativeCode.PathfindNative", "libPZPathFind64"),
    ("zombie.vehicles.Clipper", "libPZClipper64"),
    ("fmod.javafmodJNI", "libfmod"),
    ("fmod.javafmod", "libfmod"),
    ("org.lwjgl.", "liblwjgl"),
    ("zombie.core.znet.SteamUtils", "libZNetJNI64"),
    ("zombie.network.FakeClientManager", "libZNetNoSteam64"),
    ("zombie.core.raknet.RakNetPeerInterface", "libRakNet64"),
    ("zombie.core.textures.VideoTexture", "libbink64"),
    ("zombie.core.skinnedmodel.model.jassimp.JAssImpImporter", "libjassimp64"),
    ("imgui.ImGui", "libimgui-java64"),
    ("zombie.pzexe", "libpzexe_jni64"),
    ("zombie.jsig", "libpzexe_jni64"),
]

_JDK_PREFIXES = ("java.", "javax.", "jdk.", "sun.", "com.sun.")
_KAHLUA = "se.krka.kahlua"


def _mods_in(root):
    """Mod folders under one root: root/<folder> and the workshop shape root/<item>/mods/<folder>."""
    out = []
    try:
        names = sorted(os.listdir(root))
    except OSError:
        return out
    for name in names:
        base = os.path.join(root, name)
        if not os.path.isdir(base):
            continue
        if os.path.isdir(os.path.join(base, "mods")):
            for inner in sorted(os.listdir(os.path.join(base, "mods"))):
                out.append(os.path.join(base, "mods", inner))
        out.append(base)
    return out


def _version_dirs(folder):
    """Version dirs best first: 42, then 42.x desc, then common, then the folder itself."""
    def key(v):
        if v == "42":
            return (2, 99.0)
        if re.fullmatch(r"42\.\d+", v):
            return (2, float(v[3:]))
        if v == "common":
            return (1, 0.0)
        return (0, 0.0)

    vers = []
    try:
        names = os.listdir(folder)
    except OSError:
        return []
    for name in names:
        if os.path.isfile(os.path.join(folder, name, "mod.info")):
            vers.append(name)
    vers.sort(key=key, reverse=True)
    return [os.path.join(folder, v) for v in vers] + ([folder] if os.path.isfile(os.path.join(folder, "mod.info")) else [])


def _mod_info(vdir):
    path = os.path.join(vdir, "mod.info")
    try:
        text = open(path, encoding="utf-8", errors="replace").read()
    except OSError:
        return {}
    out = {}
    for field in ("id", "name", "javaPkgName", "javaJarFile"):
        m = re.search(rf"{field}\s*=\s*([^,\n]+)", text)
        if m:
            out[field] = m.group(1).strip()
    return out


def _jar_packages(jar_path):
    """Every class's package in the jar, minus multi-release shadow copies."""
    out = set()
    try:
        with zipfile.ZipFile(jar_path) as zf:
            for entry in zf.namelist():
                if not entry.endswith(".class") or entry.startswith("META-INF/") or "/" not in entry:
                    continue
                out.add(entry.rsplit("/", 1)[0].replace("/", "."))
    except (OSError, zipfile.BadZipFile):
        pass
    return out


def _folder_info(folder):
    """(mod id, javaPkgName, jar path) from the best version dir that declares Java, else None."""
    for vdir in _version_dirs(folder):
        info = _mod_info(vdir)
        if not info:
            continue
        mid = info.get("id") or os.path.basename(folder)
        pkg, jar = info.get("javaPkgName"), info.get("javaJarFile")
        if not pkg and not jar:
            return mid, None, None
        jar_path = os.path.normpath(os.path.join(vdir, jar)) if jar else None
        return mid, pkg, jar_path
    return os.path.basename(folder), None, None


def mod_table(modlist_path, roots, report=None):
    """Java package prefix -> mod id, from the enabled list's mod.info declarations and jar classes.

    Duplicate mod ids across roots: the earlier root wins completely and the
    duplicate is reported. ponytail: jar packages of same-id folders inside one
    root merge (they are one mod's folders); a mod whose files disagree across
    version dirs takes the best version dir only.
    """
    ids = []
    for line in open(modlist_path, encoding="utf-8", errors="replace"):
        m = re.match(r"\s*mod\s*=\s*([^,]+),", line)
        if m and m.group(1) not in ids:
            ids.append(m.group(1))
    enabled = set(ids)
    table = {}
    jar_mods = {}
    claimed = {}

    def note(msg):
        if report is not None:
            report.append(msg)
        print(msg, file=sys.stderr)

    for rid, root in enumerate(roots):
        for folder in _mods_in(root):
            mid, pkg, jar_path = _folder_info(folder)
            if mid not in enabled:
                continue
            if mid in claimed:
                if claimed[mid] != rid:
                    note(f"duplicate mod id {mid}: {root} also has it, {roots[claimed[mid]]} wins")
                    continue
                # same root: one mod's folders, merge jar packages
            else:
                claimed[mid] = rid
            value = "pzbutter" if pkg == "pzbutter" else mid
            keys = set()
            if pkg:
                keys.add(pkg)
            if jar_path:
                keys |= _jar_packages(jar_path)
                jar_mods.setdefault(os.path.basename(jar_path), value)
            for k in keys:
                table.setdefault(k, value)
    return table


def jar_mod_map(modlist_path, roots):
    """jar file basename -> mod id, for mapping modCompat log lines back to mods."""
    mods = {}
    ids = set()
    for line in open(modlist_path, encoding="utf-8", errors="replace"):
        m = re.match(r"\s*mod\s*=\s*([^,]+),", line)
        if m:
            ids.add(m.group(1))
    claimed = set()
    for root in roots:
        for folder in _mods_in(root):
            mid, pkg, jar_path = _folder_info(folder)
            if mid not in ids or mid in claimed or not jar_path:
                continue
            claimed.add(mid)
            mods[os.path.basename(jar_path)] = "pzbutter" if pkg == "pzbutter" else mid
    return mods


def lua_root(path):
    """The nearest mod.info's id for a Lua source path, else None (game Lua)."""
    d = os.path.dirname(os.path.abspath(path))
    while True:
        info = _mod_info(d)
        if info:
            return info.get("id") or os.path.basename(d)
        parent = os.path.dirname(d)
        if parent == d:
            return None
        d = parent


def _jni_lib(cls):
    for prefix, lib in JNI_LIBS:
        if cls == prefix or cls.startswith(prefix + "$") or (prefix.endswith(".") and cls.startswith(prefix)):
            return lib
    return None


def _owner(frames_list, table, patched):
    for frame in frames_list:
        cls, _, method = frame.rpartition(".")
        if cls.startswith(_JDK_PREFIXES):
            continue
        best = None
        for key in table:
            if (cls == key or cls.startswith(key + ".")) and (best is None or len(key) > len(best)):
                best = key
        if best is not None:
            return table[best]
        if cls == "pzbutter" or cls.startswith("pzbutter."):
            return "pzbutter"
        if cls.startswith(_KAHLUA):
            return "lua"
        lib = _jni_lib(cls)
        if lib:
            return "native:" + lib
        mods = (patched or {}).get(frame)
        return "game (patched by: " + ", ".join(mods) + ")" if mods else "game"
    return "jdk" if frames_list else "unknown"


def fold(samples, table, lua_ms=None, patched=None):
    """owner -> sample count; `lua` samples split by lua_ms (mod id -> ms) proportions."""
    counts = {}
    for sample in samples:
        owner = _owner(sample["frames"], table, patched)
        counts[owner] = counts.get(owner, 0.0) + 1.0
    n = counts.pop("lua", 0.0)
    if n:
        total = sum(v for v in (lua_ms or {}).values() if v > 0)
        if total > 0:
            for mod, ms in lua_ms.items():
                if ms > 0:
                    key = "lua:" + mod
                    counts[key] = counts.get(key, 0.0) + n * ms / total
        else:
            counts["lua (no lua.out)"] = counts.get("lua (no lua.out)", 0.0) + n
    return counts


def _jfr_stamps(run_dir):
    return coverage.jfr_stamps(run_dir, JFR if os.path.exists(JFR) else "jfr")


def _epoch_ms(iso):
    m = re.match(r"(.*T\d{2}:\d{2}:\d{2})\.(\d+)(.*)", iso)
    if m:
        iso = m.group(1) + "." + m.group(2)[:6] + m.group(3)
    return int(datetime.datetime.fromisoformat(iso).timestamp() * 1000)


def _parse_events(run_dir, types):
    """Stream `jfr print --json` events of the given types; yields (type, values) dicts."""
    cmd = [JFR, "print", "--json", "--events", ",".join(types), os.path.join(run_dir, "game.jfr")]
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, text=True, errors="replace")
    buf = []
    in_events = False
    try:
        for line in proc.stdout:
            stripped = line.rstrip()
            if not in_events:
                if re.match(r'^ {4}"events": \[\{', stripped):
                    in_events = True
                    buf = []
                continue
            if re.match(r"^ {4}\}, \{", stripped) or re.match(r"^ {4}\}\]", stripped):
                if buf:
                    try:
                        ev = json.loads("{\n" + "".join(buf) + "}\n")
                        if ev.get("type") in types:
                            yield ev["type"], ev.get("values") or {}
                    except (ValueError, KeyError):
                        pass
                    buf = []
                if re.match(r"^ {4}\}\]", stripped):
                    break
                continue
            buf.append(line)
    finally:
        proc.stdout.close()
        proc.wait()


def _sample_of(values):
    """(epoch ms, thread, [class.method leaf first]) for one JFR sample event, or None."""
    sat = values.get("sampledThread") or values.get("eventThread") or {}
    thread = sat.get("javaName") or sat.get("osName") or "?"
    stack = values.get("stackTrace") or {}
    out = []
    for f in stack.get("frames") or []:
        m = f.get("method") or {}
        t = m.get("type") or {}
        pkg = ((t.get("package") or {}).get("name") or "").replace("/", ".")
        simple = (t.get("name") or "?").split("/")[-1]
        cls = (pkg + "." + simple).strip(".")
        out.append(cls + "." + (m.get("name") or "?"))
    try:
        when = _epoch_ms(values.get("startTime", ""))
    except ValueError:
        return None
    return when, thread, out


def _lua_ms(run_dir):
    """LuaTiming's lua.out -> mod id -> ms over the recorded windows."""
    path = os.path.join(run_dir, "lua.out")
    if not os.path.isfile(path):
        return None
    headers = []
    lines = []
    for line in open(path, encoding="utf-8", errors="replace"):
        m = re.match(r"# (\d+)", line)
        if m:
            headers.append(int(m.group(1)))
            continue
        m = re.match(r"\[PZButter\] lua:\s+([\d.]+)\s+ms/s\s+(\S+)\s+(\d+)\s+calls\s+(.+):(\d+)\s*$", line)
        if m:
            lines.append((len(headers) - 1, float(m.group(1)), m.group(4)))
    if not lines:
        return None
    totals = {}
    for i, rate, file in lines:
        if 0 <= i < len(headers) - 1:
            window_s = (headers[i + 1] - headers[i]) / 1000.0
        else:
            window_s = (headers[-1] - headers[-2]) / 1000.0 if len(headers) > 1 else 60.0
        mod = lua_root(file) or "game"
        totals[mod] = totals.get(mod, 0.0) + rate * window_s
    return totals or None


def _patched(run_dir, jar_mods):
    """game method 'class.method' -> [mod ids] from modCompat and ZB log lines.

    ponytail: ZB's lines name the method but not the mod, so they are credited
    to ZombieBuddy; a ModCompat class list exported per run would replace this.
    """
    patched = {}
    for name in ("game-stdout.log", "console.txt"):
        path = os.path.join(run_dir, name)
        if not os.path.isfile(path):
            continue
        for line in open(path, encoding="utf-8", errors="replace"):
            m = re.search(r"\[PZButter\] modCompat: (\S+) patches (\S+) \(", line)
            if m:
                where, method = m.group(1), m.group(2)
                mod = "pzbutter" if where == "Outer.Holder" else jar_mods.get(where, where)
                if mod not in patched.setdefault(method, []):
                    patched[method].append(mod)
                continue
            m = re.search(r"\[ZB\] patching (\S+) with \d+ advice", line)
            if m and not patched.get(m.group(1)):
                patched[m.group(1)] = ["ZombieBuddy"]
    return patched


def _slow_spans(run_dir, cap_fps):
    """(start, end) epoch ms spans of frames over 2x the frame cap, from frames.out."""
    spans = []
    threshold = 2.0 * 1000.0 / float(cap_fps)
    path = os.path.join(run_dir, "frames.out")
    if not os.path.isfile(path):
        return spans
    for line in open(path, encoding="utf-8", errors="replace"):
        p = line.split()
        if len(p) < 3:
            continue
        try:
            epoch, interval = int(p[0]), float(p[2])
        except ValueError:
            continue
        if interval > threshold:
            spans.append((epoch - interval, epoch))
    return spans


def main(argv=None):
    ap = argparse.ArgumentParser(prog="owners", add_help=True)
    ap.add_argument("run_dir")
    ap.add_argument("--window", default="scene")
    ap.add_argument("--thread", default="MainThread")
    ap.add_argument("--cap-fps", type=float, default=60.0)
    ap.add_argument("--roots", nargs="*", default=[MODS_LOCAL, MODS_WORKSHOP])
    args = ap.parse_args(argv)

    run_dir = args.run_dir
    marks_path = os.path.join(run_dir, "marks.out")
    try:
        marks = frames.read_marks(open(marks_path, encoding="utf-8", errors="replace").read().splitlines())
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
        status = coverage._status(*_jfr_stamps(run_dir), ws, we)
    except (OSError, ValueError, subprocess.SubprocessError) as exc:
        status = f"unreadable ({exc})"
    if status != "ok":
        print("game.jfr " + status, file=sys.stderr)
        raise SystemExit(1)

    modlist = os.path.join(run_dir, "modlist.used.txt")
    dups = []
    table = mod_table(modlist, args.roots, dups) if os.path.isfile(modlist) else {}
    jar_mods = jar_mod_map(modlist, args.roots) if os.path.isfile(modlist) else {}
    lua_ms = _lua_ms(run_dir)
    patched = _patched(run_dir, jar_mods)

    samples, allocs = [], []
    for etype, values in _parse_events(run_dir, ("jdk.ExecutionSample", "jdk.ObjectAllocationSample")):
        s = _sample_of(values)
        if s is None:
            continue
        when, thread, frames_list = s
        if thread != args.thread or not (ws <= when < we):
            continue
        target = samples if etype == "jdk.ExecutionSample" else allocs
        target.append({"frames": frames_list, "time": when})

    slow = [s for s in samples if any(a <= s["time"] < b for a, b in _slow_spans(run_dir, args.cap_fps))]
    owners = fold(samples, table, lua_ms, patched)
    slow_owners = fold(slow, table, lua_ms, patched)
    alloc_owners = fold(allocs, table, lua_ms, patched)

    def pct(counts, key):
        total = sum(counts.values())
        return 100.0 * counts.get(key, 0.0) / total if total else 0.0

    keys = sorted(set(owners) | set(slow_owners) | set(alloc_owners),
                  key=lambda k: (-owners.get(k, 0.0), k))
    print("%-28s %9s %9s %9s" % ("owner", "thread%", "slow%", "alloc%"))
    for k in keys:
        print("%-28s %9.2f %9.2f %9.2f" % (k, pct(owners, k), pct(slow_owners, k), pct(alloc_owners, k)))

    out = {
        "window": [ws, we],
        "window_name": args.window,
        "thread": args.thread,
        "cap_fps": args.cap_fps,
        "owners": owners,
        "slow_owners": slow_owners,
        "alloc_owners": alloc_owners,
        "totals": {"samples": sum(owners.values()), "slow_samples": sum(slow_owners.values()),
                   "alloc_samples": sum(alloc_owners.values())},
        "duplicates": dups,
        "patched": patched,
    }
    with open(os.path.join(run_dir, "owners.json"), "w") as f:
        json.dump(out, f, indent=2, sort_keys=True, default=str)
        f.write("\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
