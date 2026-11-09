#!/bin/sh
set -e
cd "$(dirname "$0")/.."
PZ=${PZ:-/home/mitori/Sonic/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid}
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
mkdir -p media/java/client
javac --release 25 -proc:none -cp "$PZ/projectzomboid.jar:$PZ/ZombieBuddy.jar" -d "$OUT" $(find java/src -name '*.java')
jar --create --file media/java/client/AZ_Perf.jar -C "$OUT" azperf
echo "built media/java/client/AZ_Perf.jar"
