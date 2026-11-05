#!/usr/bin/env python3
"""
Release-build step for Companion Dogs: remove the profiler wrappers from call sites.

    CD.pz("srv.fallguard", CD.tickFallGuard)            ->  CD.tickFallGuard()
    CD.pz("srv.teleportbring", eachOnlinePlayer, f)     ->  eachOnlinePlayer(f)
    local sq = CD.pzd("k", CD.fn, a, b)                 ->  local sq = CD.fn(a, b)

The instrumented source stays as it is for development. Run this on a COPY of the
lua tree to produce the files that ship; measurement is then compiled out of the
call paths instead of being skipped by an `if CD._perfLv < 1` on every call.

    strip_perf.py <src_lua_dir> <dst_lua_dir>      copy the tree, rewriting *.lua
    strip_perf.py --selftest

Only CD.pz / CD.pzd call sites are rewritten. They are pure passthroughs, so the
rewrite changes nothing but the number of calls. Results differ in one respect:
pz returns at most two values, a direct call returns them all. A site whose
callee is not a plain dotted name, or whose zone key is not a string literal, is
left alone and listed in the report.
"""
import os
import re
import shutil
import sys

CALLEE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)*$")
TARGET = re.compile(r"(?<![A-Za-z0-9_.:])CD\.(pzd?)\(")


def skip_non_code(s, i):
    """If s[i:] starts a comment or string, return the index after it, else None."""
    if s.startswith("--", i):
        m = re.match(r"--\[(=*)\[", s[i:])
        if m:
            close = "]" + m.group(1) + "]"
            j = s.find(close, i + m.end())
            return len(s) if j < 0 else j + len(close)
        j = s.find("\n", i)
        return len(s) if j < 0 else j
    c = s[i]
    if c in "\"'":
        j = i + 1
        while j < len(s):
            if s[j] == "\\":
                j += 2
                continue
            if s[j] == c or s[j] == "\n":
                return j + 1
            j += 1
        return len(s)
    if c == "[":
        m = re.match(r"\[(=*)\[", s[i:])
        if m:
            close = "]" + m.group(1) + "]"
            j = s.find(close, i + m.end())
            return len(s) if j < 0 else j + len(close)
    return None


def parse_call(s, open_idx):
    """s[open_idx] == '('. Return (close_idx, [top level comma indexes]) or None."""
    depth, commas, i = 0, [], open_idx
    while i < len(s):
        skipped = skip_non_code(s, i)
        if skipped is not None:
            i = skipped
            continue
        c = s[i]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
            if depth == 0:
                return i, commas
        elif c == "," and depth == 1:
            commas.append(i)
        i += 1
    return None


def strip(s, skipped=None):
    """Rewrite every eligible CD.pz/CD.pzd call in s. Returns (new_text, count)."""
    out, pos, count = [], 0, 0
    i = 0
    while i < len(s):
        skipped_to = skip_non_code(s, i)
        if skipped_to is not None:
            i = skipped_to
            continue
        m = TARGET.match(s, i)
        if not m:
            i += 1
            continue
        # the definition: `function CD.pz(key, fn, ...)`
        if re.search(r"function\s+$", s[max(0, i - 12):i]):
            i = m.end()
            continue
        parsed = parse_call(s, m.end() - 1)
        if not parsed:
            i = m.end()
            continue
        close, commas = parsed
        line = s.count("\n", 0, i) + 1
        if not commas:
            if skipped is not None:
                skipped.append((line, "no callee"))
            i = m.end()
            continue
        key = s[m.end():commas[0]].strip()
        end_fn = commas[1] if len(commas) > 1 else close
        callee = s[commas[0] + 1:end_fn].strip()
        if not re.fullmatch(r"\"[^\"\\\n]*\"|'[^'\\\n]*'", key) or not CALLEE.match(callee):
            if skipped is not None:
                skipped.append((line, "key/callee not simple: " + s[i:min(close + 1, i + 60)].replace("\n", " ")))
            i = m.end()
            continue
        rest = s[commas[1] + 1:close].lstrip() if len(commas) > 1 else ""
        rest = rest.rstrip()
        out.append(s[pos:i])
        out.append(callee + "(" + rest + ")")
        pos = close + 1
        count += 1
        i = pos
    out.append(s[pos:])
    return "".join(out), count


def strip_all(s, skipped=None):
    """strip() until nothing is left to rewrite, for calls nested inside other calls."""
    total = 0
    while True:
        s, n = strip(s, None)
        total += n
        if n == 0:
            break
    if skipped is not None:
        strip(s, skipped)
    return s, total


def build(src, dst):
    total, files, left = 0, 0, []
    for root, _dirs, names in os.walk(src):
        for name in names:
            sp = os.path.join(root, name)
            dp = os.path.join(dst, os.path.relpath(sp, src))
            os.makedirs(os.path.dirname(dp), exist_ok=True)
            if not name.endswith(".lua"):
                shutil.copy2(sp, dp)
                continue
            text = open(sp, encoding="utf-8").read()
            skipped = []
            new, n = strip_all(text, skipped)
            if n:
                files += 1
            total += n
            for line, why in skipped:
                left.append(f"{os.path.relpath(sp, src)}:{line}: {why}")
            open(dp, "w", encoding="utf-8", newline="").write(new)
    return total, files, left


def selftest():
    cases = [
        ('CD.pz("srv.a", CD.tickA)', 'CD.tickA()'),
        ('CD.pz("srv.a", eachOnlinePlayer, CD.f)', 'eachOnlinePlayer(CD.f)'),
        ('local x = CD.pzd("k", CD.fn, a, b)', 'local x = CD.fn(a, b)'),
        ('CD.pz("k", CD.fn, f(a, "x,)"), {1, 2})', 'CD.fn(f(a, "x,)"), {1, 2})'),
        ('CD.pz("k", CD.fn,\n    a,\n    b)', 'CD.fn(a,\n    b)'),
        ('-- CD.pz("k", CD.fn)\nlocal s = "CD.pz(\\"k\\", f)"', '-- CD.pz("k", CD.fn)\nlocal s = "CD.pz(\\"k\\", f)"'),
        ('function CD.pz(key, fn, a)\n    return fn(a)\nend', 'function CD.pz(key, fn, a)\n    return fn(a)\nend'),
        ('MyCD.pz("k", f)', 'MyCD.pz("k", f)'),
        ('CD.pz(key, CD.fn)', 'CD.pz(key, CD.fn)'),
        ('CD.pz("k", function() end)', 'CD.pz("k", function() end)'),
        ('if CD.pz("a", CD.f, x) and CD.pz("b", CD.g) then', 'if CD.f(x) and CD.g() then'),
        ('x = CD.pz("a", CD.f, CD.pz("b", CD.g, 1))', 'x = CD.f(CD.g(1))'),
        ('--[[ CD.pz("k", f) ]] CD.pz("k", f)', '--[[ CD.pz("k", f) ]] f()'),
    ]
    bad = 0
    for src, want in cases:
        got, _ = strip_all(src)
        if got != want:
            bad += 1
            print("FAIL", repr(src), "->", repr(got), "wanted", repr(want))
    print("selftest:", "OK" if not bad else f"{bad} failed", f"({len(cases)} cases)")
    return bad


if __name__ == "__main__":
    if sys.argv[1:] == ["--selftest"]:
        sys.exit(1 if selftest() else 0)
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    total, files, left = build(sys.argv[1], sys.argv[2])
    print(f"rewrote {total} call sites in {files} files")
    for item in left:
        print("left alone:", item)
