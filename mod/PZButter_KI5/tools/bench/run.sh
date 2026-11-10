#!/bin/sh
# Median ns per Ki5.dispatch under the game's own Kahlua: on foot, in a vanilla car, in a KI5 car.
# Usage: tools/bench/run.sh [calls per round, default 50000]
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
PZ=${PZ:-/home/mitori/Sonic/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid}
OUT=$(mktemp -d)
javac -proc:none -cp "$PZ/projectzomboid.jar" -d "$OUT" "$HERE/BenchKi5.java"
LUA="$HERE/../../42/media/lua/client/PZButter_KI5"
# Kahlua reads stdlib.lua from the working directory
cd "$PZ" && java -cp "$OUT:$PZ/projectzomboid.jar:$PZ/*" BenchKi5 "$LUA/Ki5.lua" "$LUA/ki5table.lua" "${1:-50000}"
