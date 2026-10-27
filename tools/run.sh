#!/usr/bin/env bash
# PZ Butter: one measurement run.
set -u

die() { echo "run.sh: aborting: $*" >&2; exit 1; }

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
TOOLS="$SCRIPT_DIR"
PZ_DIR="${PZ_DIR:-/home/mitori/Sonic/SteamLibrary/steamapps/common/ProjectZomboid}"
Z="${Z:-$HOME/Zomboid}"

SCENE=""
LIST=""
SAVE=""
PROPS=""
PERF=0
MANGO=0
JFR_PERIOD=10
OUT="${HOME}/Zomboid/pzbutter-runs"
DRY=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --scene) SCENE="$2"; shift 2 ;;
        --list) LIST="$2"; shift 2 ;;
        --save) SAVE="$2"; shift 2 ;;
        --props) PROPS="$2"; shift 2 ;;
        --perf) PERF=1; shift ;;
        --mangohud) MANGO=1; shift ;;
        --jfr-period) JFR_PERIOD="$2"; shift 2 ;;
        --out) OUT="$2"; shift 2 ;;
        --dry-run) DRY=1; shift ;;
        *) echo "unknown arg: $1"; exit 2 ;;
    esac
done

if [[ -z "$SCENE" || -z "$LIST" || -z "$SAVE" ]]; then
    echo "usage: run.sh --scene NAME --list bare|full --save MODE/SAVE [--props \"k=v ...\"] [--perf] [--mangohud] [--jfr-period MS] [--out DIR] [--dry-run]"; exit 2
fi
if [[ "$LIST" != "bare" && "$LIST" != "full" ]]; then echo "list must be bare or full"; exit 2; fi

# scene presets
case "$SCENE" in
    S2) SCENE_PROPS="devScene=stand devSceneSettleSec=0 devSceneSec=20" ;;
    S3) SCENE_PROPS="devScene=stand devSceneSec=60" ;;
    S4) SCENE_PROPS="devScene=walk devSceneSec=90"
        if [[ "$PROPS" != *devRoute=* ]]; then echo "S4 requires --props devRoute=..."; exit 2; fi ;;
    S5) SCENE_PROPS="devScene=drive devSceneSec=60 devDriveKmh=120" ;;
    S6) SCENE_PROPS="devScene=stand devPopulation=200 devSceneSec=60" ;;
    S7) SCENE_PROPS="devScene=off devMark=true" ;;
    S8) echo 'S8: not built (no weather key); use S4 in a storm save'; exit 2 ;;
    *) echo "unknown scene: $SCENE"; exit 2 ;;
esac

MODE="${SAVE%%/*}"
SAVE_NAME="${SAVE#*/}"
if [[ -z "$MODE" || -z "$SAVE_NAME" || "$MODE" == "$SAVE" ]]; then echo "save must be MODE/SAVE"; exit 2; fi
SRC_SAVE="$Z/Saves/$MODE/$SAVE_NAME"
[[ -n $MODE && -n $SAVE_NAME && -d $SRC_SAVE ]] || die "no save at $SRC_SAVE (--save MODE/SAVE)"

# step 1
echo "[step1] checking game process"
if pgrep -x ProjectZomboid6 >/dev/null 2>&1; then
    echo "refusing: a game process is running"; exit 1
fi
if [[ "$LIST" == "full" ]]; then
    if ! pgrep -x steam >/dev/null 2>&1; then
        if (( DRY )); then echo "[step1] steam not running (would refuse in real run)"; else echo "refusing: steam is not running (--list full)"; exit 1; fi
    fi
fi
echo "[step1] ok"

RUN_STAMP="$(date +%Y-%m-%d_%H%M%S)"
RUN_BASE="${OUT%/}/${RUN_STAMP}-${SCENE}-${LIST}"
RUN_DIR="$RUN_BASE"
_run_n=2
while [[ -e "$RUN_DIR" ]]; do
    RUN_DIR="${RUN_BASE}-${_run_n}"
    _run_n=$((_run_n + 1))
done
BAK="$RUN_DIR/.backup"
mkdir -p "$RUN_DIR" "$BAK" || die "cannot create run dir $RUN_DIR"
SYSMON_PID=""
PERF_PID=""
_RESTORED=0
_CLEANED=0

restore_files() {
    [[ -f "$BAK/done" ]] || return 0
    [[ "$_RESTORED" -eq 0 ]] || return 0
    _RESTORED=1
    for f in default.txt options.ini PZButter.properties latestSave.ini; do
        if [[ -f "$BAK/$f" ]]; then
            if [[ "$f" == "default.txt" ]]; then
                cp -p "$BAK/$f" "$Z/mods/default.txt" 2>/dev/null || true
            else
                cp -p "$BAK/$f" "$Z/$f" 2>/dev/null || true
            fi
        fi
    done
    if [[ -d "$BAK/PZButter" ]]; then
        rm -rf "$Z/mods/PZButter"
        cp -a "$BAK/PZButter" "$Z/mods/PZButter"
    fi
    echo "[trap] restored backed-up files"
}
cleanup() {
    [[ "$_CLEANED" -eq 0 ]] || return 0
    _CLEANED=1
    if [[ -n "$SYSMON_PID" ]] && kill -0 "$SYSMON_PID" 2>/dev/null; then kill "$SYSMON_PID" 2>/dev/null; wait "$SYSMON_PID" 2>/dev/null; fi
    if [[ -n "$PERF_PID" ]] && kill -0 "$PERF_PID" 2>/dev/null; then kill "$PERF_PID" 2>/dev/null; wait "$PERF_PID" 2>/dev/null; fi
    restore_files
}
trap cleanup EXIT INT TERM

# properties array
PROPS_ENTRIES=()
add_prop_entry() {
    if [[ "$1" != *=* ]]; then
        echo "invalid property entry '$1' (expected key=value)" >&2
        exit 1
    fi
    PROPS_ENTRIES+=("$1")
}
parse_props_string() {
    local s="$1" e
    for e in $s; do
        [[ -n "$e" ]] || continue
        add_prop_entry "$e"
    done
}
parse_props_string "enabled=true frameLog=true devAutoContinue=true devSceneQuit=true"
parse_props_string "$SCENE_PROPS"
if [[ -n "$PROPS" ]]; then parse_props_string "$PROPS"; fi
PROPS_LINE="${PROPS_ENTRIES[*]}"

# step 3 backup
echo "[step3] backing up files to $BAK"
if [[ -f "$Z/mods/default.txt" ]]; then cp -p "$Z/mods/default.txt" "$BAK/default.txt" || die "backup default.txt failed"; fi
if [[ -f "$Z/options.ini" ]]; then cp -p "$Z/options.ini" "$BAK/options.ini" || die "backup options.ini failed"; fi
if [[ -f "$Z/PZButter.properties" ]]; then cp -p "$Z/PZButter.properties" "$BAK/PZButter.properties" || die "backup PZButter.properties failed"; fi
if [[ -f "$Z/latestSave.ini" ]]; then cp -p "$Z/latestSave.ini" "$BAK/latestSave.ini" || die "backup latestSave.ini failed"; fi
if [[ -d "$Z/mods/PZButter" ]]; then cp -a "$Z/mods/PZButter" "$BAK/PZButter" || die "backup PZButter dir failed"; fi
touch "$BAK/done" || die "backup marker failed"
echo "[step3] backup done"

START_TIME="$(date -Iseconds)"

# step 4 source save manifest + copy
BENCH_NAME="pzbutter-bench-${RUN_STAMP}"
BENCH_SAVE="$Z/Saves/$MODE/$BENCH_NAME"
echo "[step4] manifest write $SRC_SAVE"
if (( DRY )); then
    echo "+ python3 $TOOLS/manifest.py write $SRC_SAVE $RUN_DIR/source.manifest.json (dry-run: skipped)"
else
    python3 "$TOOLS/manifest.py" write "$SRC_SAVE" "$RUN_DIR/source.manifest.json" || die "manifest write failed"
    cp -a "$SRC_SAVE" "$BENCH_SAVE" || die "save copy failed"
    printf '%s\n%s\n' "$BENCH_NAME" "$MODE" > "$Z/latestSave.ini" || die "latestSave update failed"
fi

# step 5 mod list
echo "[step5] mod list: $LIST"
if [[ "$LIST" == "bare" ]]; then
    if (( DRY )); then
        echo "+ write bare default.txt (dry-run: skipped)"
    else
        printf 'VERSION = 1,\n\nmods\n{\n    mod = ZombieBuddy,\n    mod = PZButter,\n}\n\nmaps\n{\n}\n' > "$Z/mods/default.txt" || die "mod list write failed"
    fi
else
    echo "[step5] leaving default.txt as the user has it"
fi
if (( ! DRY )); then
    cp "$Z/mods/default.txt" "$RUN_DIR/modlist.used.txt" || die "mod list copy failed"
fi

# step 6 install build + properties
BUILD_MOD="$REPO_ROOT/build/mod/PZButter"
echo "[step6] install build from $BUILD_MOD"
if [[ ! -d "$BUILD_MOD" ]] || [[ ! -f "$BUILD_MOD"/*.zbs ]]; then
    if (( DRY )); then
        echo "[step6] build not present (dry-run: skipped)"
    else
        echo "build is not signed or missing; stop"; exit 1
    fi
else
    if (( DRY )); then
        echo "+ would remove+copy build and write properties (dry-run: skipped)"
    else
        rm -rf "$Z/mods/PZButter" || die "remove old PZButter failed"
        cp -a "$BUILD_MOD" "$Z/mods/PZButter" || die "install build failed"
        printf '%s\n' "${PROPS_ENTRIES[@]}" > "$Z/PZButter.properties" || die "properties write failed"
    fi
fi
if (( ! DRY )); then
    cp "$Z/PZButter.properties" "$RUN_DIR/PZButter.properties" || die "properties copy failed"
fi

# step 7 jvm options
JFC="$RUN_DIR/game.jfc"
JDK_JFC="$(find "$PZ_DIR" -name profile.jfc 2>/dev/null | head -1)"
if [[ -n "$JDK_JFC" && -f "$JDK_JFC" ]]; then
    if (( ! DRY )); then
        cp "$JDK_JFC" "$JFC"
        python3 - "$JFC" "$JFR_PERIOD" <<'PY'
import re, sys
p, ms = sys.argv[1], sys.argv[2]
s = open(p).read()
def repl_event(m):
    ev = m.group(1)
    ev = re.sub(r'(<setting name="period">).*?(</setting>)',
                r'\g<1>' + ms + ' ms\g<2>', ev, count=1, flags=re.S)
    return ev
s2 = re.sub(r'(<event name="jdk\.ExecutionSample".*?</event>)', repl_event, s, count=1, flags=re.S)
open(p, "w").write(s2)
PY
    else
        echo "+ would copy $JDK_JFC -> $JFC and set jdk.ExecutionSample period ${JFR_PERIOD}ms"
    fi
else
    echo "[step7] profile.jfc not found; jfr binary at $PZ_DIR/projectzomboid/jre64/bin/jfr (jfr configure fallback documented)"
fi
# We copy profile.jfc and edit the period directly; `jfr configure --help` prints "jfr configure: unknown option --help" (no in-place patch mode).

JVM_STR="-XX:StartFlightRecording=settings=$JFC,filename=$RUN_DIR/game.jfr,dumponexit=true -Xlog:gc*,safepoint:file=$RUN_DIR/gc.log:uptime,tags -XX:+UnlockDiagnosticVMOptions -XX:+DumpPerfMapAtExit"
LAUNCH_ENV=""
if (( MANGO )); then
    LAUNCH_ENV="MANGOHUD=1 MANGOHUD_CONFIG=output_folder=$RUN_DIR,autostart_log=1,log_interval=0 "
fi
LAUNCH_LINE="${LAUNCH_ENV}$PZ_DIR/projectzomboid.sh -javaagent:ZombieBuddy.jar $JVM_STR --"

echo "[step7] jvm: $JVM_STR"

# ZombieBuddy jar (game folder) hash target
ZB_GAME_JAR="$PZ_DIR/projectzomboid/ZombieBuddy.jar"
if [[ ! -f "$ZB_GAME_JAR" ]]; then
    if (( DRY )); then
        echo "[step7] warning: ZombieBuddy.jar not found at $ZB_GAME_JAR"
    else
        die "ZombieBuddy.jar not found at $ZB_GAME_JAR"
    fi
fi
PZ_REV="$(git -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"
ZB_SHA=""
[[ -f "$ZB_GAME_JAR" ]] && ZB_SHA="$(sha256sum "$ZB_GAME_JAR" | awk '{print $1}')"

if (( DRY )); then
    echo "[step8] (dry-run) launch line:"
    echo "  $LAUNCH_LINE"
    echo "[step9] (dry-run) would wait for game exit and collect files"
    echo "[step10] (dry-run) would run manifest check and delete bench copy"
    cat > "$RUN_DIR/run.opts" <<EOF
scene=$SCENE
list=$LIST
save=$SAVE
props=$PROPS_LINE
jvm=$JVM_STR
launch=$LAUNCH_LINE
pz_rev=$PZ_REV
zombiebuddy_sha256=$ZB_SHA
start=$START_TIME
end=(dry-run)
EOF
    echo "[step12] profiling command:"
    echo "  python3 $TOOLS/frames.py $RUN_DIR/frames.out --marks $RUN_DIR/marks.out --window scene"
    END_TIME="$(date -Iseconds)"
    echo "dry run done: $RUN_DIR"
    exit 0
fi

# step 8 launch
echo "[step8] launching: $LAUNCH_LINE"
if (( MANGO )); then
    export MANGOHUD=1
    export MANGOHUD_CONFIG="output_folder=$RUN_DIR,autostart_log=1,log_interval=0"
fi
cd "$Z" 2>/dev/null || true
"$PZ_DIR/projectzomboid.sh" -javaagent:ZombieBuddy.jar $JVM_STR -- > "$RUN_DIR/stdout.txt" 2>&1 &
GAME_PID=$!
GAME_PID_ACTUAL=""
for i in $(seq 1 120); do
    GAME_PID_ACTUAL="$(pgrep -x ProjectZomboid6 | head -1)"
    [[ -n "$GAME_PID_ACTUAL" ]] && break
    sleep 1
done
if [[ -z "$GAME_PID_ACTUAL" ]]; then
    echo "no game process started; see $RUN_DIR/stdout.txt"; exit 1
fi
echo "[step8] game pid=$GAME_PID_ACTUAL"
python3 "$TOOLS/sysmon.py" "$RUN_DIR/sysmon.csv" --pid "$GAME_PID_ACTUAL" &
SYSMON_PID=$!
if (( PERF )); then
    ( while [[ ! -f "$Z/pzbutter/marks.out" ]] || ! grep -q 'scene.start' "$Z/pzbutter/marks.out" 2>/dev/null; do sleep 1; done
      perf record -g --call-graph dwarf -p "$GAME_PID_ACTUAL" -o "$RUN_DIR/perf.data" -- sleep 30 ) &
    PERF_PID=$!
fi

# step 9 wait
echo "[step9] waiting for game exit"
while kill -0 "$GAME_PID_ACTUAL" 2>/dev/null; do sleep 2; done
echo "[step9] game exited"
if [[ -n "$PERF_PID" ]]; then wait "$PERF_PID" 2>/dev/null; fi
if [[ -n "$SYSMON_PID" ]]; then kill "$SYSMON_PID" 2>/dev/null; wait "$SYSMON_PID" 2>/dev/null; SYSMON_PID=""; fi

cp "$Z/console.txt" "$RUN_DIR/" 2>/dev/null || true
cp "$Z"/pzbutter/*.out* "$RUN_DIR/" 2>/dev/null || true
if [[ -f "/tmp/perf-${GAME_PID_ACTUAL}.map" ]]; then cp "/tmp/perf-${GAME_PID_ACTUAL}.map" "$RUN_DIR/" 2>/dev/null || true; fi
[[ -f "$BENCH_SAVE/mods.txt" ]] && cp "$BENCH_SAVE/mods.txt" "$RUN_DIR/save-mods.txt" 2>/dev/null || true

END_TIME="$(date -Iseconds)"

# step 10 manifest check + delete bench copy
echo "[step10] checking source save against manifest"
python3 "$TOOLS/manifest.py" check "$SRC_SAVE" "$RUN_DIR/source.manifest.json"; rc=$?
if (( rc != 0 )); then echo "source save differs (listed above)"; fi
rm -rf "$BENCH_SAVE"

# step 11 run.opts
ZB_JAR="$ZB_GAME_JAR"
ZB_SHA2="$ZB_SHA"
[[ -f "$ZB_JAR" ]] && ZB_SHA2="$(sha256sum "$ZB_JAR" | awk '{print $1}')"
cat > "$RUN_DIR/run.opts" <<EOF
scene=$SCENE
list=$LIST
save=$SAVE
props=$PROPS_LINE
jvm=$JVM_STR
launch=$LAUNCH_LINE
pz_rev=$PZ_REV
zombiebuddy_sha256=$ZB_SHA2
start=$START_TIME
end=$END_TIME
EOF

# step 12
echo "[step12] profile with:"
echo "  python3 $TOOLS/frames.py $RUN_DIR/frames.out --marks $RUN_DIR/marks.out --window scene"
echo "run complete: $RUN_DIR"
