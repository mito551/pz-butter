#!/usr/bin/env bash
# Run every Lua test with luajit. Called by build.sh.
set -u
HERE="$(cd "$(dirname "$0")/.." && pwd)"
command -v luajit >/dev/null 2>&1 || { echo "test_lua.sh: luajit is not on PATH" >&2; exit 1; }
fail=0
for t in "$HERE"/test/lua/*_test.lua; do
    [[ -e "$t" ]] || continue
    echo "[lua] ${t##*/}"
    if ! luajit "$t"; then fail=1; fi
done
exit "$fail"
