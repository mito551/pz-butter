#!/bin/sh
set -e
cd "$(dirname "$0")/.."
PZ=${PZ:-/home/mitori/Sonic/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid}
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
javac --release 25 -proc:none -cp "$PZ/projectzomboid.jar:$PZ/ZombieBuddy.jar" -d "$OUT" $(find tools/java -name '*.java')
for v in 42 42.20; do
    mkdir -p "$v/media/java/client"
    jar --create --file "$v/media/java/client/BGI_Perf.jar" -C "$OUT" bgi
done
echo "built BGI_Perf.jar for 42 and 42.20"
