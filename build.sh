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
mkdir -p "$B/classes/pzbutter" "$B/test-classes/pzbutter"
cp "$HERE"/src/pzbutter/workers-*.txt "$B/classes/pzbutter/"
cp "$HERE"/src/pzbutter/workers-*.txt "$B/test-classes/pzbutter/"
cp "$HERE"/src/pzbutter/census-*.txt "$B/classes/pzbutter/"
cp "$HERE"/src/pzbutter/census-*.txt "$B/test-classes/pzbutter/"
# tests get their own home: SceneDriver/DevMarks tests wrote 21 world.ready marks into a live run's ~/Zomboid/pzbutter/marks.out
TEST_HOME="$(mktemp -d -t pzbutter-home.XXXXXX)"
"$JDK/bin/java" -ea -Duser.home="$TEST_HOME" -cp "$B/test-classes:$CP" pzbutter.SelfTest
"$HERE"/tools/test_lua.sh
python3 "$HERE/tools/check_readme.py"
# fix mods (Plan 17): every mod/PZButter_<Target>, shipped or not, must pass the package rules
mapfile -t FIXMODS < <(find "$HERE/mod" -mindepth 1 -maxdepth 1 -type d -name 'PZButter_*' | sort)
INSTALL_ARGS=()
WORKSHOP="${PZ_GAME%/common/*}/workshop/content/108600"   # full-file overrides are checked against the installed targets (Plan 18 B-D8-9)
[[ -d "$WORKSHOP" ]] && INSTALL_ARGS=(--install "$WORKSHOP")
(( ${#FIXMODS[@]} == 0 )) || python3 "$HERE/tools/fixmods.py" "${INSTALL_ARGS[@]}" "${FIXMODS[@]}"
"$HERE"/tools/test_workshop.sh

printf 'ZB-Preload: true\n' > "$B/MANIFEST.MF"
"$JDK/bin/jar" --create --file "$B/PZButter.jar" --manifest "$B/MANIFEST.MF" -C "$B/classes" .
cp -r "$HERE/mod" "$B/mod"
mkdir -p "$B/mod/PZButter/42/media/java/common"
cp "$B/PZButter.jar" "$B/mod/PZButter/42/media/java/common/"
# ZombieBuddy signature (.zbs beside every jar in the build, unshipped fix mods included): ZB preloads only signed jars of trusted authors. Key and SteamID stay outside the repo.
ZBS_KEY="${PZB_ZBS_KEY:-$HOME/.config/pz-butter/zbs-ed25519.key}"
ZBS_ID="${PZB_ZBS_STEAMID:-$(cat "$HOME/.config/pz-butter/zbs-steamid" 2>/dev/null || true)}"   # set -e: a missing file must not end the build (CI has no key)
if [[ -f "$ZBS_KEY" && -n "$ZBS_ID" ]]; then
    while IFS= read -r jar; do
        "$JDK/bin/java" -cp "$PZ_GAME/ZombieBuddy.jar" "$HERE/tools/ZbsSign.java" sign "$ZBS_KEY" "$ZBS_ID" "$jar"
    done < <(find "$B/mod" -name '*.jar' | sort)
else
    echo "unsigned (no $ZBS_KEY or SteamID): ZombieBuddy will not preload any JAR in this build"
fi
echo "built $B/PZButter.jar"

[[ -n "${PZ_JRE:-}" ]] && "$HERE/harness/preload-check.sh"

if [[ "${1:-}" == "--install" ]]; then
    DEST="$HOME/Zomboid/mods/PZButter"
    if [[ -e "$DEST" && ! -e "$DEST/.pzbutter-build" ]]; then
        echo "$DEST exists and was not installed by build.sh; not touching it" >&2; exit 1
    fi
    rm -rf "$DEST"
    cp -r "$B/mod/PZButter" "$DEST"
    touch "$DEST/.pzbutter-build"
    echo "installed $DEST"
    # fix mods go beside it; enabling one in the game is its switch (mod/UNSHIPPED ones stay out)
    while IFS= read -r fm; do
        FDEST="$HOME/Zomboid/mods/$(basename "$fm")"
        if [[ -e "$FDEST" && ! -e "$FDEST/.pzbutter-build" ]]; then
            echo "$FDEST exists and was not installed by build.sh; not touching it" >&2; exit 1
        fi
        rm -rf "$FDEST"
        cp -r "$B/mod/$(basename "$fm")" "$FDEST"
        rm -rf "$FDEST/tools" "$FDEST/upstream"
        touch "$FDEST/.pzbutter-build"
        echo "installed $FDEST"
    done < <(python3 "$HERE/tools/fixmods.py" --shipped "$HERE/mod")
fi
