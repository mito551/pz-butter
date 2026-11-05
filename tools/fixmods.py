#!/usr/bin/env python3
"""Package rules for PZ Butter's fix mods (Plan 17): one PZButter_<Target> mod per target mod.

check(mod_dir, packaged=False) -> list of problems (empty: the mod passes).
  - id starts with PZButter_ and matches the folder name
  - loadModAfter= names the target(s); require= names them too (a fix MUST depend on its target),
    except a multi-target fix, whose targets go in loadModAfter= only
  - javaJarFile= needs require=\\ZombieBuddy
  - require=\\PZButter needs the `if not PZButter ... then return end` guard in every Lua file
  - a packaged copy has no tools/ or upstream/

CLI: fixmods.py [--packaged] MOD_DIR...  (prints problems, exit 1 if any)
     fixmods.py --shipped MOD_ROOT        (prints the PZButter_* folders not in MOD_ROOT/UNSHIPPED)
"""
import os
import re
import sys

MULTI_TARGET = {"PZButter_VehicleRadio"}
NOT_TARGETS = {"ZombieBuddy", "PZButter"}
GUARD = re.compile(r"^\s*if\s+not\s+\(?\s*PZButter\b.*\breturn\b", re.M)


def _ids(value):
    return [v.strip().lstrip("\\").strip() for v in value.split(",") if v.strip().lstrip("\\").strip()]


def read_info(path):
    info = {}
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            key, sep, value = line.strip().partition("=")
            if sep:
                info[key.strip()] = value.strip()
    return info


def check(mod_dir, packaged=False):
    problems = []
    info_path = os.path.join(mod_dir, "42", "mod.info")
    if not os.path.isfile(info_path):
        return ["no 42/mod.info"]
    info = read_info(info_path)
    mod_id = info.get("id", "")
    if not mod_id.startswith("PZButter_"):
        problems.append("id %s does not start with PZButter_" % mod_id)
    if mod_id != os.path.basename(os.path.normpath(mod_dir)):
        problems.append("id %s is not the folder name" % mod_id)
    require = _ids(info.get("require", ""))
    targets = [t for t in _ids(info.get("loadModAfter", "")) if t not in NOT_TARGETS]
    if not targets:
        problems.append("loadModAfter= names no target")
    if mod_id not in MULTI_TARGET:
        for t in targets:
            if t not in require:
                problems.append("require= lacks target %s" % t)
    if info.get("javaJarFile") and "ZombieBuddy" not in require:
        problems.append("javaJarFile= without require=\\ZombieBuddy")
    if "PZButter" in require:
        for base, _, files in os.walk(mod_dir):
            for name in files:
                if not name.endswith(".lua"):
                    continue
                p = os.path.join(base, name)
                with open(p, encoding="utf-8", errors="replace") as f:
                    if not GUARD.search(f.read()):
                        problems.append("%s: no `if not PZButter ... return end` guard"
                                        % os.path.relpath(p, mod_dir))
    if packaged:
        for sub in ("tools", "upstream"):
            if os.path.isdir(os.path.join(mod_dir, sub)) or os.path.isdir(os.path.join(mod_dir, "42", sub)):
                problems.append("packaged copy has %s/" % sub)
    return problems


def unshipped(path):
    """mod/UNSHIPPED: one mod id per line; # comments and blank lines are ignored."""
    try:
        with open(path, encoding="utf-8") as f:
            return {l.strip() for l in f if l.strip() and not l.strip().startswith("#")}
    except OSError:
        return set()


def fix_mod_dirs(mod_root):
    try:
        names = sorted(os.listdir(mod_root))
    except OSError:
        return []
    return [os.path.join(mod_root, n) for n in names
            if n.startswith("PZButter_") and os.path.isdir(os.path.join(mod_root, n))]


def shipped(mod_root):
    skip = unshipped(os.path.join(mod_root, "UNSHIPPED"))
    return [d for d in fix_mod_dirs(mod_root) if os.path.basename(d) not in skip]


def main(argv):
    if argv[:1] == ["--shipped"]:
        for d in shipped(argv[1]):
            print(d)
        return 0
    packaged = "--packaged" in argv
    dirs = [a for a in argv if a != "--packaged"]
    bad = 0
    for d in dirs:
        for p in check(d, packaged):
            print("fixmods: %s: %s" % (os.path.basename(os.path.normpath(d)), p))
            bad += 1
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
