#!/usr/bin/env bash
set -euo pipefail

MOD_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PZ="/home/mitori/Sonic/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid"
SRC="$MOD_DIR/tools/java"
OUT="$MOD_DIR/tools/.build_classes"
JAR_DIR="$MOD_DIR/42/media/java/client"
JAR="$JAR_DIR/WKTPerf.jar"
CP="$PZ/projectzomboid.jar:$PZ/ZombieBuddy.jar"

rm -rf "$OUT"
mkdir -p "$OUT" "$JAR_DIR"

# Compile library classes.
javac --release 25 -cp "$CP" -d "$OUT" $(find "$SRC/wkt" -name '*.java' | sort)

if [ "${1:-}" = "test" ]; then
    TEST_OUT="$MOD_DIR/tools/.test_classes"
    rm -rf "$TEST_OUT"
    mkdir -p "$TEST_OUT"
    javac --release 25 -cp "$OUT:$CP" -d "$TEST_OUT" $(find "$SRC/test" -name '*.java' | sort)
    java -ea -cp "$TEST_OUT:$OUT:$CP" SeenBufferTest
    rm -rf "$TEST_OUT"
fi

# Build jar from classes.
(cd "$OUT" && jar cf "$JAR" .)

rm -rf "$OUT"
echo "built $JAR"
