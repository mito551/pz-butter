#!/usr/bin/env bash
# Microbenchmark of the frame clock's per-frame path. Usage: ./bench.sh [repo dir whose src/ is measured]
set -euo pipefail
JDK="${JDK:-/usr/lib/jvm/java-25-openjdk}"
PZ_GAME="${PZ_GAME:-/home/mitori/Sonic/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid}"
HERE="$(cd "$(dirname "$0")" && pwd)"
R="$(cd "${1:-$HERE}" && pwd)"
CP="$PZ_GAME/projectzomboid.jar:$PZ_GAME/ZombieBuddy.jar"
O="$(mktemp -d)"
trap 'rm -rf "$O"' EXIT

mapfile -t SRC < <(find "$R/src" -name '*.java')
"$JDK/bin/javac" --release 25 -cp "$CP" -d "$O" "${SRC[@]}" "$HERE/bench/pzbutter/FrameBench.java"
"$JDK/bin/java" -cp "$O:$CP" pzbutter.FrameBench
