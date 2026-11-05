#!/usr/bin/env bash
# PZ Butter dev: make a fresh Sandbox save on the current mod list (~/Zomboid/mods/default.txt), unattended.
# usage: tools/newsave.sh NAME [--at X,Y,Z] [--timeout SEC] [--dry-run]
# One launch with devAutoContinue + devNewSave: the main menu starts a new Sandbox world (default sandbox settings,
# unemployed, no traits, player at X,Y,Z), a 10 s stand scene runs after a 30 s settle, and the game quits, which saves
# it. PZButter.properties and latestSave.ini are restored afterwards; default.txt is never touched.
set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
TOOLS="$SCRIPT_DIR"
# shellcheck source=tools/runlib.sh
source "$TOOLS/runlib.sh"
PZ_DIR="${PZ_DIR:-/home/mitori/Sonic/SteamLibrary/steamapps/common/ProjectZomboid}"
WORKSHOP="${WORKSHOP:-$(dirname "$(dirname "$PZ_DIR")")/workshop/content/108600}"
Z="${Z:-$HOME/Zomboid}"

NAME=""; AT="13652,3136,0"; TIMEOUT=900; DRY=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --at) AT="$2"; shift 2 ;;
        --timeout) TIMEOUT="$2"; shift 2 ;;
        --dry-run) DRY=1; shift ;;
        -*) echo "unknown arg: $1"; exit 2 ;;
        *) NAME="$1"; shift ;;
    esac
done
[[ -n "$NAME" && "$NAME" =~ ^[A-Za-z0-9_.-]+$ ]] || { echo "usage: tools/newsave.sh NAME [--at X,Y,Z] [--timeout SEC] [--dry-run]  (NAME: letters, digits, _ . -)"; exit 2; }
[[ "$AT" =~ ^-?[0-9]+,-?[0-9]+,-?[0-9]+$ ]] || die "--at must be X,Y,Z"
[[ "$TIMEOUT" =~ ^[0-9]+$ ]] || die "--timeout must be an integer"
SAVE_DIR="$Z/Saves/Sandbox/$NAME"
[[ ! -e "$SAVE_DIR" ]] || die "save exists already: $SAVE_DIR"

# the list: PZButter in it, every mod and every requirement installed (a missing one opens a dialog and stalls the run)
python3 - "$Z/mods/default.txt" "$Z/mods" "$WORKSHOP" <<'PY' || die "the mod list is not ready (see above)"
import glob, os, re, sys
listed = [m.strip().lstrip('\\') for m in re.findall(r'^\s*mod\s*=\s*([^,\n]+),', open(sys.argv[1]).read(), re.M)]   # ids may hold spaces
variants = {}   # id -> one requirement set per mod.info (version folders differ; the game loads one of them)
for root in (sys.argv[2], sys.argv[3]):
    for p in glob.glob(os.path.join(root, '**', 'mod.info'), recursive=True):
        try: text = open(p, errors='replace').read()
        except OSError: continue
        m = re.search(r'^id=(.+)$', text, re.M)
        if m:
            req = re.search(r'^require=(.*)$', text, re.M)
            variants.setdefault(m.group(1).strip(), []).append(
                {r.strip().lstrip('\\') for r in (req.group(1).split(',') if req else []) if r.strip()})
info = {i: set.intersection(*v) for i, v in variants.items()}   # required by every version folder
bad = False
if 'PZButter' not in listed:
    print('PZButter is not in default.txt'); bad = True
missing = [i for i in listed if i not in info]
if missing:
    print('not installed:', ' '.join(missing)); bad = True
for i in listed:
    for r in sorted(info.get(i, ())):
        if r not in info:
            print(i, 'requires', r, 'which is not installed'); bad = True
print('list:', len(listed), 'mods, all installed' if not bad else 'NOT ready')
sys.exit(1 if bad else 0)
PY

BUILD_JAR="$REPO_ROOT/build/mod/PZButter/42/media/java/common/PZButter.jar"
INSTALLED_JAR="$Z/mods/PZButter/42/media/java/common/PZButter.jar"
[[ -f "$BUILD_JAR" && -f "$INSTALLED_JAR" ]] || die "build or installed jar missing (./build.sh --install)"
[[ "$(sha256sum < "$BUILD_JAR")" == "$(sha256sum < "$INSTALLED_JAR")" ]] || die "the installed jar is not this build (./build.sh --install)"
pgrep -x ProjectZomboid6 >/dev/null 2>&1 && die "a game process is running"
pgrep -x steam >/dev/null 2>&1 || die "steam is not running"

DIR="$Z/pzbutter-runs/$(run_stamp)-newsave-$NAME"
PROPS="enabled=true
frameLog=false
devAutoContinue=true
devNewSave=$NAME
devNewSaveAt=$AT
devScene=stand
devSceneSettleSec=30
devSceneSec=10
devSceneQuit=true"
if (( DRY )); then
    echo "+ would write PZButter.properties:"; echo "$PROPS" | sed 's/^/    /'
    echo "+ would launch $PZ_DIR/projectzomboid.sh and wait up to $TIMEOUT s; log in $DIR"
    exit 0
fi

mkdir -p "$DIR/.backup" || die "cannot create $DIR"
for f in PZButter.properties latestSave.ini; do backup_file "$Z/$f" "$DIR/.backup" "$f"; done
restore() {
    for f in PZButter.properties latestSave.ini; do
        if [[ -f "$DIR/.backup/$f" ]]; then cp -p "$DIR/.backup/$f" "$Z/$f"; elif [[ -f "$DIR/.backup/.absent.$f" ]]; then rm -f "$Z/$f"; fi
    done
    echo "[newsave] restored PZButter.properties and latestSave.ini"
}
trap restore EXIT
echo "$PROPS" > "$Z/PZButter.properties" || die "properties write failed"

echo "[newsave] launching for Sandbox/$NAME at $AT"
cd "$Z" 2>/dev/null || true
"$PZ_DIR/projectzomboid.sh" -javaagent:ZombieBuddy.jar -- > "$DIR/stdout.txt" 2>&1 &
PID=""
for _ in $(seq 120); do PID="$(pgrep -x ProjectZomboid6 | head -1)"; [[ -n "$PID" ]] && break; sleep 1; done
[[ -n "$PID" ]] || die "no game process started; see $DIR/stdout.txt"
DEADLINE=$(( $(date +%s) + TIMEOUT ))
while kill -0 "$PID" 2>/dev/null; do
    if (( $(date +%s) >= DEADLINE )); then
        echo "[newsave] timeout after $TIMEOUT s; stopping the game"
        kill "$PID" 2>/dev/null
        for _ in $(seq 30); do kill -0 "$PID" 2>/dev/null || break; sleep 1; done
        kill -9 "$PID" 2>/dev/null
        break
    fi
    sleep 2
done
cp -p "$Z/console.txt" "$DIR/console.txt" 2>/dev/null || true

[[ -f "$SAVE_DIR/mods.txt" ]] || die "no save was written at $SAVE_DIR (see $DIR/console.txt: grep devNewSave)"
if [[ "$(mod_ids "$SAVE_DIR/mods.txt")" == "$(mod_ids "$Z/mods/default.txt")" ]]; then
    echo "[newsave] done: Sandbox/$NAME, its mods.txt matches default.txt"
else
    echo "[newsave] Sandbox/$NAME written, but its mods.txt differs from default.txt"; exit 1
fi
