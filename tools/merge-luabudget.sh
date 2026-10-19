#!/usr/bin/env bash
# Move LuaBudget's sources into pz-butter as package pzbutter.luabudget.
#   --check   do it in a temporary copy of this tree, build there, delete the copy; the real tree is untouched
# LuaBudget keeps its own Zomboid/LuaBudget.properties and its own frameStep patch; pzbutter.Main calls Budget.init().
set -euo pipefail
LUABUDGET="${LUABUDGET:-/home/mitori/Zomboid/mods/LuaBudget}"
HERE="$(cd "$(dirname "$0")/.." && pwd)"

merge() {  # $1 = tree root
    local dest="$1/src/pzbutter/luabudget"
    for f in Budget Patches; do
        [[ -f "$LUABUDGET/src/luabudget/$f.java" ]] || { echo "missing $LUABUDGET/src/luabudget/$f.java" >&2; exit 1; }
    done
    mkdir -p "$dest"
    for f in Budget Patches; do
        sed -e 's/^package luabudget;/package pzbutter.luabudget;/' \
            -e 's/^    static void init() {/    public static void init() {/' \
            "$LUABUDGET/src/luabudget/$f.java" > "$dest/$f.java"
    done
    grep -q '^    public static void init() {' "$dest/Budget.java" || { echo "Budget.init() not found; LuaBudget changed?" >&2; exit 1; }
}

if [[ "${1:-}" == "--check" ]]; then
    T="$(mktemp -d)"
    trap 'rm -rf "$T"' EXIT
    cp -r "$HERE/build.sh" "$HERE/src" "$HERE/test" "$HERE/mod" "$T/"
    merge "$T"
    "$T/build.sh"
    unzip -Z1 "$T/build/PZButter.jar" | grep -q '^pzbutter/luabudget/Budget.class$' || { echo "Budget.class not in the jar" >&2; exit 1; }
    echo "merge check ok"
else
    merge "$HERE"
    echo "merged into $HERE/src/pzbutter/luabudget; remove the LuaBudget mod from the mod list"
fi
