#!/usr/bin/env bash
# Microbenchmarks. Usage: ./bench.sh [repo dir whose src/ is measured] [parker|stamps|learner|widths|rawget|uislice|readset]
# With no unit: the frame clock's per-frame cost (FrameBench).
# Units: parker | stamps | learner | widths | rawget | soundgate | cutaway | splash | edge | plan | gate | luagate | replay | luafiles | dedupe | scenecull | simlod | spotted | cullsort | statsget | vbosize | vboslots | glrecord | scratch | batch | calm | latch (DeliveryBench).
set -euo pipefail
JDK="${JDK:-/usr/lib/jvm/java-25-openjdk}"
PZ_GAME="${PZ_GAME:-/home/mitori/Sonic/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid}"
HERE="$(cd "$(dirname "$0")" && pwd)"
R="$(cd "${1:-$HERE}" && pwd)"
CP="$PZ_GAME/projectzomboid.jar:$PZ_GAME/ZombieBuddy.jar"
O="$(mktemp -d)"
trap 'rm -rf "$O"' EXIT

mapfile -t SRC < <(find "$R/src" -name '*.java')
"$JDK/bin/javac" --release 25 -cp "$CP" -d "$O" "${SRC[@]}" "$HERE"/bench/pzbutter/*.java
if [[ -n "${2:-}" ]]; then "$JDK/bin/java" -cp "$O:$CP" pzbutter.DeliveryBench "$2"
else "$JDK/bin/java" -cp "$O:$CP" pzbutter.FrameBench; fi
