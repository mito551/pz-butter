#!/usr/bin/env bash
# Shared shell helpers for tools/run.sh and tools/mp.sh. Sourced, not run.
# shellcheck shell=bash

die() { echo "${0##*/}: aborting: $*" >&2; exit 1; }

run_stamp() { date +%Y-%m-%d_%H%M%S; }

# Mod ids from a default.txt-style file, sorted and deduplicated.
mod_ids() {
    python3 - "$1" <<'PY'
import re, sys
try:
    s = open(sys.argv[1]).read()
except FileNotFoundError:
    print("")
    sys.exit(0)
ids = re.findall(r'mod\s*=\s*([^,\s}]+)', s)
print(",".join(sorted(set(ids))))
PY
}

# Add "mod = ID," to a default.txt / mods.txt list (inside its mods { } block when there is one). No-op if listed.
add_mod() {
    python3 - "$1" "$2" <<'PY'
import re, sys
path, mid = sys.argv[1], sys.argv[2]
s = open(path).read()
if re.search(r'mod\s*=\s*' + re.escape(mid) + r'\s*,', s):
    sys.exit(0)
m = re.search(r'(^mods\s*\{.*?)(^\})', s, flags=re.S | re.M)
line = "    mod = %s,\n" % mid
s = s[:m.start(2)] + line + s[m.start(2):] if m else s + ("" if s.endswith("\n") or not s else "\n") + line
open(path, "w").write(s)
PY
}

# Remove "mod = ID," from a default.txt / mods.txt list. No-op if not listed.
remove_mod() {
    python3 - "$1" "$2" <<'PY'
import re, sys
path, mid = sys.argv[1], sys.argv[2]
s = open(path).read()
open(path, "w").write(re.sub(r'^[ \t]*mod\s*=\s*' + re.escape(mid) + r'\s*,[ \t]*\r?\n', '', s, flags=re.M))
PY
}

manifest_write() {
    local dir="$1" out="$2"
    python3 "$TOOLS/manifest.py" write "$dir" "$out"
}

manifest_check() {
    local dir="$1" manifest="$2"
    python3 "$TOOLS/manifest.py" check "$dir" "$manifest"
}

# Back one file up, or record that it was absent. NAME is the name under BAK.
backup_file() {
    local src="$1" bak="$2" name="$3"
    if [[ -f "$src" ]]; then
        cp -p "$src" "$bak/$name" || die "backup $src failed"
    else
        touch "$bak/.absent.$name" || die "backup marker for $name failed"
    fi
}

restore_file() {
    local src="$1" bak="$2" name="$3"
    if [[ -f "$bak/$name" ]]; then
        mkdir -p "$(dirname "$src")"
        cp -p "$bak/$name" "$src" 2>/dev/null || true
    elif [[ -f "$bak/.absent.$name" ]]; then
        rm -f "$src"
    fi
}

# PIDs listening on a UDP port, one per line. Empty when free or unknown.
port_pids() {
    local port="$1"
    command -v ss >/dev/null 2>&1 || return 0
    ss -H -lunp "sport = :$port" 2>/dev/null \
        | grep -o 'pid=[0-9]*' \
        | cut -d= -f2 \
        | sort -u
}
