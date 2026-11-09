#!/bin/sh
set -e
cd "$(dirname "$0")/.."
PZ=${PZ:-/home/mitori/Sonic/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid}
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
mkdir -p 42/media/java/common
javac --release 25 -proc:none -cp "$PZ/projectzomboid.jar:$PZ/ZombieBuddy.jar" -d "$OUT" $(find tools/java -name '*.java')
cp -r tools/resources/. "$OUT"/
jar --create --file 42/media/java/common/ExpandedStorageZB.jar --manifest tools/MANIFEST.MF -C "$OUT" .
echo "built 42/media/java/common/ExpandedStorageZB.jar"
