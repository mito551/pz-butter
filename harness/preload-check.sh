#!/usr/bin/env bash
# Headless ZombieBuddy preload check on the real build/mod/PZButter/42 and the real worker list. Three runs:
# frameThreads=4 -> ACTIVE with every marker; frameThreads=0 -> OFF, no marker; -XX:AOTCache -> OFF, no marker.
# Never starts the game; the scratch user.home keeps ~/Zomboid and ~/.zombie_buddy untouched.
set -euo pipefail
JDK="${JDK:-/usr/lib/jvm/java-25-openjdk}"
PZ_GAME="${PZ_GAME:-/home/mitori/Sonic/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid}"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
MOD="$HERE/build/mod/PZButter/42"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
mkdir -p "$T/zb" "$T/home/Zomboid" "$T/cls"

"$JDK/bin/javac" --release 25 -cp "$MOD/media/java/client/PZButter.jar" -d "$T/cls" "$HERE/test/pzbutter/PreloadProbe.java"
cat > "$T/zb/config.json" <<J
{"preload_mods":{"PZButter":{"infPath":"$MOD/mod.info","jarPath":"$MOD/media/java/client/PZButter.jar"}}}
J

fail() { echo "preload check FAILED: $1" >&2; echo "$OUT" >&2; exit 1; }

run() {   # run <frameThreads> <expect state> <expect reason> <expect markers: all|none> [jvm args...]
    local ft="$1" st="$2" why="$3" mk="$4"; shift 4
    printf 'enabled=true\nluaWorkerGate=true\nframeThreads=%s\n' "$ft" > "$T/home/Zomboid/PZButter.properties"
    OUT="$("$PZ_GAME/jre64/bin/java" "$@" -Duser.home="$T/home" \
        -javaagent:"$PZ_GAME/ZombieBuddy.jar=policy=allow-all,allow_unsigned_mods=true,config_dir=$T/zb" \
        -cp "$T/cls:$PZ_GAME/projectzomboid.jar" pzbutter.PreloadProbe 2>&1)" || fail "run frameThreads=$ft: probe exited non-zero"
    grep -qx "state=$st" <<<"$OUT" || fail "run frameThreads=$ft: state is not $st"
    grep -qx "reason=$why" <<<"$OUT" || fail "run frameThreads=$ft: reason is not '$why'"
    local m; m="$(sed -n 's/^markers=//p' <<<"$OUT")"
    if [[ "$mk" == all ]]; then [[ "${m%/*}" == "${m#*/}" && "${m#*/}" -gt 0 ]] || fail "run frameThreads=$ft: markers $m, want all"
    else [[ "${m%/*}" == 0 ]] || fail "run frameThreads=$ft: markers $m, want none"; fi
    echo "run frameThreads=$ft $*: $st ${why:+($why)} markers=$m"
}

run 4 ACTIVE "" all
run 0 OFF "frameThreads=0" none
run 4 OFF "AOT cache -XX:AOTCache=x.aot" none -XX:AOTCache=x.aot
echo "preload ok"
