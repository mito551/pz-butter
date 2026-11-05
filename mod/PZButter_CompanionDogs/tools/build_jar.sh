#!/bin/sh
# Builds 42/media/java/client/CDPerf.jar from tools/java (main sources + optional test).
set -e
cd "$(dirname "$0")/.."
PZ=${PZ:-/home/mitori/Sonic/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid}
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT

mkdir -p 42/media/java/client
MAIN=$(find tools/java -path 'tools/java/test' -prune -o -name '*.java' -print)
javac --release 25 -proc:none -cp "$PZ/projectzomboid.jar:$PZ/ZombieBuddy.jar" -d "$OUT" $MAIN
jar --create --file 42/media/java/client/CDPerf.jar -C "$OUT" cd

if [ "$1" = "test" ]; then
    TESTS=$(find tools/java/test -name '*.java')
    javac --release 25 -cp "$OUT:$PZ/projectzomboid.jar:$PZ/ZombieBuddy.jar" -d "$OUT" $TESTS
    java -ea -cp "$OUT:$PZ/projectzomboid.jar:$PZ/ZombieBuddy.jar" SpotBookTest
fi