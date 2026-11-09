#!/bin/sh
set -e
cd "$(dirname "$0")/.."
PZ=${PZ:-/home/mitori/Sonic/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid}
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
mkdir -p 42/media/java/client
MAIN=$(find tools/java -name '*.java' -print)
javac --release 25 -proc:none -cp "$PZ/projectzomboid.jar:$PZ/ZombieBuddy.jar" -d "$OUT" $MAIN
jar --create --file 42/media/java/client/DSPerf.jar -C "$OUT" ds
echo "built 42/media/java/client/DSPerf.jar"