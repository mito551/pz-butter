#!/usr/bin/env bash
# Headless ZombieBuddy preload spike: S2 (transformer sees IsoZombie at load, retransform keeps the marker) and S3 (which
# game classes are loaded before a preload premain). Never starts the game; never touches ~/.zombie_buddy or ~/Zomboid.
set -euo pipefail
JDK="${JDK:-/usr/lib/jvm/java-25-openjdk}"
PZ_GAME="${PZ_GAME:-/home/mitori/Sonic/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid}"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
mkdir -p "$T/zb" "$T/home" "$T/mod" "$T/cls" "$T/src/pzprobe"

"$JDK/bin/javac" --release 25 -d "$T/cls" "$HERE/test/pzbutter/PreloadProbe.java"
cat > "$T/src/pzprobe/PreMain.java" <<'J'
package pzprobe;
public final class PreMain { public static void premain(String[] a) { pzbutter.PreloadProbe.premain(); } }
J
"$JDK/bin/javac" --release 25 -cp "$T/cls" -d "$T/cls" "$T/src/pzprobe/PreMain.java"
printf 'ZB-Preload: true\n' > "$T/manifest.txt"
"$JDK/bin/jar" --create --file "$T/pzprobe.jar" --manifest "$T/manifest.txt" -C "$T/cls" pzprobe
printf 'name=pzprobe\njavaPkgName=pzprobe\njavaPreload=true\n' > "$T/mod/mod.info"
cat > "$T/zb/config.json" <<J
{"preload_mods":{"pzprobe":{"infPath":"$T/mod/mod.info","jarPath":"$T/pzprobe.jar"}}}
J

"$PZ_GAME/jre64/bin/java" -Duser.home="$T/home" \
    -javaagent:"$PZ_GAME/ZombieBuddy.jar=policy=allow-all,allow_unsigned_mods=true,config_dir=$T/zb" \
    -cp "$T/cls:$PZ_GAME/projectzomboid.jar" pzbutter.PreloadProbe
