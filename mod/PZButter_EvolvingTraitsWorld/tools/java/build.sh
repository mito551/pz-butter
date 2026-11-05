#!/bin/sh
# Builds 42/media/java/ETWPerf.jar from tools/java/*.java.
set -e
cd "$(dirname "$0")"
PZ=${PZ:-/home/mitori/Sonic/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid}
rm -rf out && mkdir out
javac --release 25 -proc:none -cp "$PZ/projectzomboid.jar:$PZ/ZombieBuddy.jar" -d out *.java
jar --create --file ../../42/media/java/ETWPerf.jar -C out etw
