#!/usr/bin/env python3
"""Count GL calls per pass from an apitrace dump.

One dump line is one call, e.g. ``1234 glDrawArrays(GL_TRIANGLES, 0, 3)``.
A pass starts at every ``glBindFramebuffer``; calls before the first bind
belong to pass 0.  The split is deliberately simple: it is a measurement
aid, not a GL state tracker.
"""
import argparse
import fnmatch
import re
import shutil
import subprocess
import sys

CATEGORIES = (
    ("draw", ("glDraw*", "glMultiDraw*")),
    ("bind", ("glBind*", "glUseProgram", "glActiveTexture")),
    ("uniform", ("glUniform*", "glProgramUniform*")),
    ("upload", (
        "glBufferData", "glBufferSubData", "glTexImage*", "glTexSubImage*",
        "glCompressedTex*", "glMap*",
    )),
    ("get", ("glGet*", "glIs*", "glCheckFramebufferStatus")),
    ("sync", ("glFenceSync", "glClientWaitSync", "glWaitSync", "glFinish", "glFlush")),
)
SWAPS = {"glXSwapBuffers", "eglSwapBuffers", "wglSwapBuffers", "glSwapBuffers"}
_CALL = re.compile(r"^\s*\d+\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(")


def _name(line):
    s = line.strip()
    if not s or s.startswith("#"):
        return None
    m = _CALL.match(s)
    if m:
        return m.group(1)
    # apitrace sometimes prefixes a marker, e.g. "frame 3: glDrawArrays(...)"
    m = re.search(r"\b(gl[A-Za-z0-9_]+)\s*\(", s)
    return m.group(1) if m else None


def category(name):
    for cat, patterns in CATEGORIES:
        if any(fnmatch.fnmatchcase(name, p) for p in patterns):
            return cat
    return "other"


def count(dump_lines):
    """Return {"passN": {category: count}} with one pass per framebuffer bind."""
    passes = {}
    current = "pass0"
    passes[current] = {}
    binds = 0
    for line in dump_lines:
        name = _name(line)
        if name is None:
            continue
        if name == "glBindFramebuffer":
            # The first bind belongs to pass0; each later bind opens a new pass.
            if binds:
                current = "pass%d" % binds
                passes[current] = {}
            binds += 1
        cat = category(name)
        passes[current][cat] = passes[current].get(cat, 0) + 1
    if len(passes) == 1 and not passes["pass0"]:
        return {}
    return passes


def frames(dump_lines):
    """Split dump lines into frames, each ending at a buffer swap."""
    out = []
    cur = []
    for line in dump_lines:
        cur.append(line)
        if _name(line) in SWAPS:
            out.append(cur)
            cur = []
    return out


def dump_trace(trace):
    if shutil.which("apitrace") is None:
        raise RuntimeError("apitrace is not on PATH")
    r = subprocess.run(
        ["apitrace", "dump", trace], check=False, text=True,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    )
    if r.returncode != 0:
        raise RuntimeError("apitrace dump failed: %s" % r.stderr.strip())
    return r.stdout.splitlines()


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("trace")
    ap.add_argument("--frame", type=int, default=1, help="1-based frame (ends at a swap)")
    ap.add_argument("--dump", help="read dump lines from this file instead of apitrace")
    ns = ap.parse_args(argv)
    try:
        if ns.dump:
            with open(ns.dump, encoding="utf-8", errors="replace") as f:
                lines = f.read().splitlines()
        else:
            lines = dump_trace(ns.trace)
    except (OSError, RuntimeError) as e:
        print("glcalls: %s" % e, file=sys.stderr)
        return 1
    fs = frames(lines)
    if not fs:
        print("glcalls: no frames (no buffer swap found)", file=sys.stderr)
        return 1
    if ns.frame < 1 or ns.frame > len(fs):
        print("glcalls: frame %d out of range 1..%d" % (ns.frame, len(fs)), file=sys.stderr)
        return 1
    table = count(fs[ns.frame - 1])
    print("frame %d: %d pass(es)" % (ns.frame, len(table)))
    for p, cats in table.items():
        print("%s  %s" % (p, " ".join("%s=%d" % (c, cats[c]) for c in sorted(cats))))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
