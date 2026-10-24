#!/usr/bin/env bash
# Build PZButter.jar and the mod folder into build/. --install copies the mod into ~/Zomboid/mods/PZButter.
set -euo pipefail
JDK="${JDK:-/usr/lib/jvm/java-25-openjdk}"
PZ_GAME="${PZ_GAME:-/home/mitori/Sonic/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid}"
HERE="$(cd "$(dirname "$0")" && pwd)"
B="$HERE/build"
CP="$PZ_GAME/projectzomboid.jar:$PZ_GAME/ZombieBuddy.jar${PZ_EXTRA_CP:+:$PZ_EXTRA_CP}"   # PZ_EXTRA_CP: more game jars (CI)

rm -rf "$B/classes" "$B/test-classes" "$B/mod"
mapfile -t SRC < <(find "$HERE/src" -name '*.java')
"$JDK/bin/javac" --release 25 -cp "$CP" -d "$B/classes" "${SRC[@]}"
"$JDK/bin/javac" --release 25 -cp "$CP" -d "$B/test-classes" "${SRC[@]}" $(find "$HERE/test" -name '*.java')
cp "$HERE"/src/pzbutter/workers-*.txt "$B/classes/pzbutter/"
cp "$HERE"/src/pzbutter/workers-*.txt "$B/test-classes/pzbutter/"
"$JDK/bin/java" -ea -cp "$B/test-classes:$CP" pzbutter.SelfTest

printf 'ZB-Preload: true\n' > "$B/MANIFEST.MF"
"$JDK/bin/jar" --create --file "$B/PZButter.jar" --manifest "$B/MANIFEST.MF" -C "$B/classes" .
cp -r "$HERE/mod" "$B/mod"
mkdir -p "$B/mod/PZButter/42/media/java/client"
cp "$B/PZButter.jar" "$B/mod/PZButter/42/media/java/client/"
echo "built $B/PZButter.jar"

if [[ "${1:-}" == "--install" ]]; then
    DEST="$HOME/Zomboid/mods/PZButter"
    if [[ -e "$DEST" && ! -e "$DEST/.pzbutter-build" ]]; then
        echo "$DEST exists and was not installed by build.sh; not touching it" >&2; exit 1
    fi
    rm -rf "$DEST"
    cp -r "$B/mod/PZButter" "$DEST"
    touch "$DEST/.pzbutter-build"
    echo "installed $DEST"
fi
