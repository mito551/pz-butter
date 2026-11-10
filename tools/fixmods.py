#!/usr/bin/env python3
"""Package rules for PZ Butter's fix mods (Plan 17): one PZButter_<Target> mod per target mod.

check(mod_dir, packaged=False) -> list of problems (empty: the mod passes).
  - id starts with PZButter_ and matches the folder name
  - loadModAfter= names the target(s); require= names them too (a fix MUST depend on its target),
    except a multi-target fix, whose targets go in loadModAfter= only
  - javaJarFile= needs require=\\ZombieBuddy
  - require=\\PZButter needs the `if not PZButter ... then return end` guard in every Lua file
  - a packaged copy has no tools/ or upstream/

CLI: fixmods.py [--install ROOT] [--packaged] MOD_DIR...  (prints problems, exit 1 if any)
     fixmods.py --shipped MOD_ROOT        (prints the PZButter_* folders not in MOD_ROOT/UNSHIPPED)
"""
import hashlib
import os
import re
import sys

MULTI_TARGET = {"PZButter_VehicleRadio"}
NOT_TARGETS = {"ZombieBuddy", "PZButter"}
GUARD = re.compile(r"^\s*if\s+not\s+\(?\s*PZButter\b.*\breturn\b", re.M)

# Full-file overrides: fix mod id -> {override path under 42/media/lua/: target mod folder name}.
# A copy drifts when the target mod updates its file (Plan 18 B-D8-9): the copy then silently
# reverts those changes. With --install, check() reports absent lines and compares the installed
# file with upstream/<override>.sha256 so deletions and reordering are visible too. A target that
# is not installed under --install is simply unchecked today.
OVERRIDES = {"PZButter_XensEnhancedGUI": {"client/XEG/XEG_QuickAccess.lua": "XensEnhancedGUI"}}


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


def installed_target_lines(install_root, target, rel):
    """Lines of the installed target's copy of the override file; None when not installed."""
    suffix = os.path.join(target, "42", "media", "lua", *rel.split("/"))
    found = []
    for dirpath, dirs, files in os.walk(install_root):
        if "PZButter" in dirpath or not dirpath.endswith(os.path.dirname(suffix)):
            continue
        if os.path.basename(suffix) in files:
            found.append(os.path.join(dirpath, os.path.basename(suffix)))
    if not found:
        return None
    lines = set()
    for p in found:
        with open(p, encoding="utf-8", errors="replace") as f:
            lines |= {l.strip() for l in f if l.strip()}
    return found, lines


def override_problems(mod_dir, mod_id, install_root):
    if not install_root or mod_id not in OVERRIDES:
        return []
    problems = []
    for rel, target in OVERRIDES[mod_id].items():
        override = os.path.join(mod_dir, "42", "media", "lua", *rel.split("/"))
        if not os.path.isfile(override):
            problems.append("override %s is missing (declared in OVERRIDES)" % rel)
            continue
        got = installed_target_lines(install_root, target, rel)
        if got is None:
            continue
        installed, lines = got
        with open(override, encoding="utf-8", errors="replace") as f:
            ours = {l.strip() for l in f if l.strip()}
        stale = lines - ours
        if stale:
            head = " | ".join(sorted(stale)[:3])
            problems.append("override %s is stale: installed %s has %d line(s) absent from the copy "
                            "(rebase the copy): %s" % (rel, target, len(stale), head))
            continue
        checksum_path = os.path.join(mod_dir, "upstream", *rel.split("/")) + ".sha256"
        if not os.path.isfile(checksum_path):
            problems.append("override %s has no reviewed upstream checksum" % rel)
            continue
        with open(checksum_path, encoding="ascii") as f:
            expected = f.read().split()[0]
        changed = False
        for p in installed:
            with open(p, "rb") as f:
                changed |= hashlib.sha256(f.read()).hexdigest() != expected
        if changed:
            problems.append("override %s is stale: installed %s differs from the reviewed upstream "
                            "(rebase the copy and checksum)" % (rel, target))
    return problems


def check(mod_dir, packaged=False, install_root=None):
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
                if name == "PZBClaim.lua":
                    continue
                p = os.path.join(base, name)
                with open(p, encoding="utf-8", errors="replace") as f:
                    content = f.read()
                    # guard must be the first statement (not buried in a function)
                    first_code = None
                    for line in content.splitlines():
                        s = line.strip()
                        if not s or s.startswith("--"):
                            continue
                        first_code = s
                        break
                    if not (first_code and GUARD.match(first_code)):
                        problems.append("%s: no leading `if not PZButter ... return end` guard"
                                        % os.path.relpath(p, mod_dir))
    if packaged:
        for sub in ("tools", "upstream"):
            if os.path.isdir(os.path.join(mod_dir, sub)) or os.path.isdir(os.path.join(mod_dir, "42", sub)):
                problems.append("packaged copy has %s/" % sub)
    problems += override_problems(mod_dir, os.path.basename(os.path.normpath(mod_dir)), install_root)
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
    install_root = None
    dirs = []
    it = iter(argv)
    for a in it:
        if a == "--install":
            install_root = next(it, None)
        elif a != "--packaged":
            dirs.append(a)
    bad = 0
    for d in dirs:
        for p in check(d, "--packaged" in argv, install_root):
            print("fixmods: %s: %s" % (os.path.basename(os.path.normpath(d)), p))
            bad += 1
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
