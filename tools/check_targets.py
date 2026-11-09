#!/usr/bin/env python3
"""Check that every @Patch target still exists in the game: the class, and a method of that name.

  check_targets.py [--game DIR] [--jdk DIR]      (defaults: $PZ_GAME, $JDK)

A game update that renames a class or a method turns the patch into a silent no-op. Prints one line per missing
class or method and exits 1 if there is any; the weekly Workshop run uses it to tell which feature needs a change.
"""
import glob
import os
import re
import subprocess
import sys
import zipfile

PATCH = re.compile(r'@Patch\(([^)]*)\)', re.S)
KV = re.compile(r'(\w+)\s*=\s*"([^"]*)"')


def targets(src):
    out = []
    for f in sorted(glob.glob(os.path.join(src, "*.java"))):
        text = open(f, encoding="utf-8", errors="replace").read()
        for m in PATCH.finditer(text):
            kv = dict(KV.findall(m.group(1)))
            if "className" in kv:
                line = text.count("\n", 0, m.start()) + 1
                out.append((os.path.basename(f), line, kv["className"], kv.get("methodName", "")))
    return out


def main(argv):
    game = os.environ.get("PZ_GAME", "")
    jdk = os.environ.get("JDK", "/usr/lib/jvm/java-25-openjdk")
    args = list(argv)
    while args:
        a = args.pop(0)
        if a == "--game": game = args.pop(0)
        elif a == "--jdk": jdk = args.pop(0)
    if not game:
        sys.exit("check_targets: set PZ_GAME or pass --game")
    here = os.path.dirname(os.path.abspath(__file__))
    jars = sorted(glob.glob(os.path.join(game, "*.jar")))
    names = set()
    for j in jars:
        names.update(n for n in zipfile.ZipFile(j).namelist() if n.endswith(".class"))
    cp = os.pathsep.join(jars)
    cache = {}
    bad = 0
    for f, line, cls, meth in targets(os.path.join(here, "..", "src", "pzbutter")):
        path = cls.replace(".", "/") + ".class"
        if path not in names:
            print("MISSING CLASS  %s  (%s:%d)" % (cls, f, line)); bad += 1; continue
        if not meth:
            continue
        if cls not in cache:
            r = subprocess.run([os.path.join(jdk, "bin", "javap"), "-p", "-cp", cp, cls], capture_output=True, text=True)
            cache[cls] = r.stdout
        listing = cache[cls]
        want = cls.rsplit(".", 1)[-1] if meth == "<init>" else meth
        if not re.search(r'[ .]%s\(' % re.escape(want), listing):
            print("MISSING METHOD %s.%s  (%s:%d)" % (cls, meth, f, line)); bad += 1
    print("%d patch target(s) missing" % bad)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
