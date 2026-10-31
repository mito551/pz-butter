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
TIMEOUT=""
TIMEOUT_SET=0
JVM_EXTRA=""

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
        --timeout) TIMEOUT="$2"; TIMEOUT_SET=1; shift 2 ;;
        --jvm) JVM_EXTRA="$2"; shift 2 ;;
        *) echo "unknown arg: $1"; exit 2 ;;
    esac
done

if [[ -z "$SCENE" || -z "$LIST" || -z "$SAVE" ]]; then
    echo "usage: run.sh --scene NAME --list bare|full --save MODE/SAVE [--props \"k=v ...\"] [--perf] [--mangohud] [--jfr-period MS] [--out DIR] [--dry-run] [--timeout SEC] [--jvm \"...\"]"; exit 2
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

if (( PERF == 1 )) && [[ $SCENE == S7 ]]; then echo "--perf is incompatible with S7"; exit 2; fi
if (( MANGO == 1 )); then
    command -v mangohud >/dev/null 2>&1 || die "mangohud is not on PATH"
fi

MODE="${SAVE%%/*}"
SAVE_NAME="${SAVE#*/}"
if [[ -z "$MODE" || -z "$SAVE_NAME" || "$MODE" == "$SAVE" ]]; then echo "save must be MODE/SAVE"; exit 2; fi
SRC_SAVE="$Z/Saves/$MODE/$SAVE_NAME"
[[ -n $MODE && -n $SAVE_NAME && -d $SRC_SAVE ]] || die "no save at $SRC_SAVE (--save MODE/SAVE)"

mod_ids() {
    python3 - "$1" <<'PY'
import re, sys
try:
    s = open(sys.argv[1]).read()
except FileNotFoundError:
    print("")
    sys.exit(0)
ids = re.findall(r'mod\s*=\s*([^,\s}]+)', s)
print(",".join(sorted(set(ids))))
PY
}

# build checks (before step 3)
BUILD_MOD="$REPO_ROOT/build/mod/PZButter"
BUILD_JAR="$BUILD_MOD/42/media/java/client/PZButter.jar"
INSTALLED_JAR="$Z/mods/PZButter/42/media/java/client/PZButter.jar"
echo "[build] signed-build check"
if ! find "$BUILD_MOD" -name '*.zbs' -print -quit 2>/dev/null | grep -q .; then
    if (( DRY )); then echo "[build] signed-build check: FAIL"; fi
    die "build is not signed or missing"
fi
if (( DRY )); then echo "[build] signed-build check: OK"; fi
echo "[build] jar-equality check"
BUILD_SHA=""; INSTALLED_SHA=""
[[ -f "$BUILD_JAR" ]] && BUILD_SHA="$(sha256sum "$BUILD_JAR" | awk '{print $1}')"
[[ -f "$INSTALLED_JAR" ]] && INSTALLED_SHA="$(sha256sum "$INSTALLED_JAR" | awk '{print $1}')"
if [[ -z "$BUILD_SHA" || "$BUILD_SHA" != "$INSTALLED_SHA" ]]; then
    if (( DRY )); then echo "[build] jar-equality check: FAIL (build=${BUILD_SHA:-<missing>} installed=${INSTALLED_SHA:-<missing>})"; fi
    die "install this build and start the game once (approve it in ZombieBuddy): cp -a $BUILD_MOD $Z/mods/PZButter"
fi
if (( DRY )); then echo "[build] jar-equality check: OK"; fi

# step 1
echo "[step1] checking game process"
if pgrep -x ProjectZomboid6 >/dev/null 2>&1; then
    if (( DRY )); then echo "[step1] a game process is running (would refuse in real run)"; else echo "refusing: a game process is running"; exit 1; fi
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
BENCH_CREATED=0
GAME_STARTED=0
TIMED_OUT=0
GAME_PID_ACTUAL=""

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
        elif [[ -f "$BAK/.absent.$f" ]]; then
            if [[ "$f" == "default.txt" ]]; then
                rm -f "$Z/mods/default.txt"
            else
                rm -f "$Z/$f"
            fi
        fi
    done
    echo "[trap] restored backed-up files"
}
cleanup() {
    [[ "$_CLEANED" -eq 0 ]] || return 0
    _CLEANED=1
    if [[ -n "$SYSMON_PID" ]] && kill -0 "$SYSMON_PID" 2>/dev/null; then kill "$SYSMON_PID" 2>/dev/null; wait "$SYSMON_PID" 2>/dev/null; fi
    if [[ -n "$PERF_PID" ]] && kill -0 "$PERF_PID" 2>/dev/null; then kill "$PERF_PID" 2>/dev/null; wait "$PERF_PID" 2>/dev/null; fi
    if [[ "$GAME_STARTED" -eq 1 && -n "$GAME_PID_ACTUAL" ]] && kill -0 "$GAME_PID_ACTUAL" 2>/dev/null; then kill "$GAME_PID_ACTUAL" 2>/dev/null; fi
    if [[ "$BENCH_CREATED" -eq 1 && -n "${BENCH_SAVE:-}" ]]; then
        case "$BENCH_SAVE" in
            */pzbutter-bench-*) rm -rf "$BENCH_SAVE"; echo "[trap] removed bench copy" ;;
        esac
    fi
    restore_files
}
trap cleanup EXIT
trap 'cleanup; exit 130' INT TERM

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

# default timeout (unless provided or S7)
if [[ -z "$TIMEOUT" && "$SCENE" != "S7" ]]; then
    settle_val=0; scene_val=0
    for e in "${PROPS_ENTRIES[@]}"; do
        case "$e" in
            devSceneSettleSec=*) settle_val="${e#*=}" ;;
            devSceneSec=*) scene_val="${e#*=}" ;;
        esac
    done
    TIMEOUT=$(( settle_val + scene_val + 300 ))
fi
if [[ -n "$TIMEOUT" ]]; then
    [[ "$TIMEOUT" =~ ^[0-9]+$ ]] || die "timeout must be an integer"
fi

# step 3 backup
echo "[step3] backing up files to $BAK"
for f in default.txt options.ini PZButter.properties latestSave.ini; do
    if [[ -f "$Z/$([[ "$f" == "default.txt" ]] && echo mods/default.txt || echo "$f")" ]]; then
        if [[ "$f" == "default.txt" ]]; then
            cp -p "$Z/mods/default.txt" "$BAK/default.txt" || die "backup default.txt failed"
        else
            cp -p "$Z/$f" "$BAK/$f" || die "backup $f failed"
        fi
    else
        touch "$BAK/.absent.$f" || die "backup marker failed"
    fi
done
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
    BENCH_CREATED=1
    printf '%s\n%s\n' "$BENCH_NAME" "$MODE" > "$Z/latestSave.ini" || die "latestSave update failed"
fi

# step5 mod list
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

# I2 mods.txt compare
if [[ "$LIST" == "bare" ]]; then
    CHOSEN_IDS="PZButter,ZombieBuddy"
else
    if [[ -f "$BAK/default.txt" ]]; then CHOSEN_IDS="$(mod_ids "$BAK/default.txt")"; else CHOSEN_IDS="$(mod_ids "$Z/mods/default.txt")"; fi
fi
if (( DRY )); then
    CMP_MODS_PATH="$SRC_SAVE/mods.txt"
else
    CMP_MODS_PATH="$BENCH_SAVE/mods.txt"
fi
BENCH_IDS="$(mod_ids "$CMP_MODS_PATH")"
echo "[I2] chosen mods: $CHOSEN_IDS"
echo "[I2] bench mods:  $BENCH_IDS"
if [[ "$CHOSEN_IDS" != "$BENCH_IDS" ]]; then
    echo "mod list mismatch:"
    echo "  chosen: $CHOSEN_IDS"
    echo "  bench:  $BENCH_IDS"
    die "save mod list does not match chosen list"
fi

# step6 properties
echo "[step6] write properties"
if (( DRY )); then
    echo "+ would write properties (dry-run: skipped)"
else
    printf '%s\n' "${PROPS_ENTRIES[@]}" > "$Z/PZButter.properties" || die "properties write failed"
fi
if (( ! DRY )); then
    cp "$Z/PZButter.properties" "$RUN_DIR/PZButter.properties" || die "properties copy failed"
fi

# step7 jvm options
JFC="$RUN_DIR/game.jfc"
JDK_JFC="$(find "$PZ_DIR" -name profile.jfc 2>/dev/null | head -1)"
if [[ -z "$JDK_JFC" || ! -f "$JDK_JFC" ]]; then
    die "profile.jfc not found"
fi
if (( ! DRY )); then
    cp "$JDK_JFC" "$JFC" || die "jfc copy failed"
    python3 - "$JFC" "$JFR_PERIOD" <<'PY'
import re, sys
p, ms = sys.argv[1], sys.argv[2]
s = open(p).read()
ev = re.search(r'<event name="jdk\.ExecutionSample">.*?</event>', s, flags=re.S)
st = ev and re.search(r'(<setting name="period"[^>]*>)(.*?)(</setting>)', ev.group(0), flags=re.S)
if not st:
    print("profile.jfc: no jdk.ExecutionSample period setting", file=sys.stderr)
    sys.exit(1)
# a period that already equals the wanted one is fine: write the file either way
block = ev.group(0)[:st.start(2)] + ms + " ms" + ev.group(0)[st.end(2):]
open(p, "w").write(s[:ev.start()] + block + s[ev.end():])
PY
    if [[ $? -ne 0 ]]; then die "jfc period patch failed"; fi
else
    echo "+ would copy $JDK_JFC -> $JFC and set jdk.ExecutionSample period ${JFR_PERIOD}ms"
fi

JVM_STR="${JVM_EXTRA:+$JVM_EXTRA }-XX:StartFlightRecording=settings=$JFC,filename=$RUN_DIR/game.jfr,dumponexit=true -Xlog:gc*,safepoint:file=$RUN_DIR/gc.log:uptime,tags -XX:+UnlockDiagnosticVMOptions -XX:+DumpPerfMapAtExit"
LAUNCH_ENV=""
if (( MANGO )); then
    LAUNCH_ENV="MANGOHUD=1 "
fi
if (( MANGO )); then
    LAUNCH_LINE="mangohud $PZ_DIR/projectzomboid.sh -javaagent:ZombieBuddy.jar $JVM_STR --"
else
    LAUNCH_LINE="$PZ_DIR/projectzomboid.sh -javaagent:ZombieBuddy.jar $JVM_STR --"
fi

echo "[step7] jvm: $JVM_STR"

# ZombieBuddy jar hash
ZB_GAME_JAR="$PZ_DIR/projectzomboid/ZombieBuddy.jar"
if [[ ! -f "$ZB_GAME_JAR" ]]; then
    if (( DRY )); then echo "[step7] warning: ZombieBuddy.jar not found at $ZB_GAME_JAR"; else die "ZombieBuddy.jar not found at $ZB_GAME_JAR"; fi
fi
PZ_REV="$(git -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"
ZB_SHA=""
[[ -f "$ZB_GAME_JAR" ]] && ZB_SHA="$(sha256sum "$ZB_GAME_JAR" | awk '{print $1}')"

if (( DRY )); then
    echo "[step8] (dry-run) launch line:"
    echo "  $LAUNCH_LINE"
    echo "[step9] (dry-run) would wait for game exit and collect files"
    echo "[step10] (dry-run) would run manifest check and delete bench copy"
    TIMEOUT_VALUE="${TIMEOUT:-none}"
    cat > "$RUN_DIR/run.opts" <<EOF
scene=$SCENE
list=$LIST
save=$SAVE
props=$PROPS_LINE
chosen_mods=$CHOSEN_IDS
bench_mods=$BENCH_IDS
jvm=$JVM_STR
jvm_extra=$JVM_EXTRA
timeout=$TIMEOUT_VALUE
launch=$LAUNCH_LINE
pz_rev=$PZ_REV
zombiebuddy_sha256=$ZB_SHA
start=$START_TIME
end=(dry-run)
EOF
    if [[ "$SCENE" == "S7" ]]; then WINDOW_HINT="w1"; else WINDOW_HINT="scene"; fi
    echo "[step12] profiling command:"
    echo "  python3 $TOOLS/frames.py $RUN_DIR/frames.out --marks $RUN_DIR/marks.out --window $WINDOW_HINT"
    END_TIME="$(date -Iseconds)"
    echo "dry run done: $RUN_DIR"
    exit 0
fi

# move marks aside before launch
if [[ -f "$Z/pzbutter/marks.out" ]]; then
    mv "$Z/pzbutter/marks.out" "$BAK/marks.out.prev" || die "marks move failed"
fi

# step 8 launch
# A shell outside the desktop session (ssh, an agent) has no display: use the session's Xwayland :0.
if [[ -z ${DISPLAY:-} ]]; then
    export DISPLAY=:0
    for a in /run/user/$(id -u)/.mutter-Xwaylandauth.*; do [[ -f $a ]] && export XAUTHORITY=$a; done
fi
[[ -z ${WAYLAND_DISPLAY:-} && -S /run/user/$(id -u)/wayland-0 ]] && export WAYLAND_DISPLAY=wayland-0
# The game reads the clipboard at start (and ImGui later); a remote-desktop clipboard owner never answers
# and the game hangs. A local owner answers at once. This empties the clipboard.
command -v wl-copy >/dev/null 2>&1 && printf ' ' | timeout 5 wl-copy 2>/dev/null
echo "[step8] launching: $LAUNCH_LINE"
if (( MANGO )); then
    export MANGOHUD=1
    export MANGOHUD_CONFIG="output_folder=$RUN_DIR,autostart_log=1,log_interval=0"
fi
cd "$Z" 2>/dev/null || true
if (( MANGO )); then
    mangohud "$PZ_DIR/projectzomboid.sh" -javaagent:ZombieBuddy.jar $JVM_STR -- > "$RUN_DIR/stdout.txt" 2>&1 &
else
    "$PZ_DIR/projectzomboid.sh" -javaagent:ZombieBuddy.jar $JVM_STR -- > "$RUN_DIR/stdout.txt" 2>&1 &
fi
GAME_PID=$!
GAME_STARTED=1
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
    (
      while kill -0 "$GAME_PID_ACTUAL" 2>/dev/null; do
        if [[ -f "$Z/pzbutter/marks.out" ]] && grep -q 'scene.start' "$Z/pzbutter/marks.out" 2>/dev/null; then break; fi
        sleep 1
      done
      if kill -0 "$GAME_PID_ACTUAL" 2>/dev/null; then
        perf record -g --call-graph dwarf -p "$GAME_PID_ACTUAL" -o "$RUN_DIR/perf.data" -- sleep 30
      fi
    ) &
    PERF_PID=$!
fi

# step 9 wait
echo "[step9] waiting for game exit"
DEADLINE=""
if [[ -n "$TIMEOUT" ]]; then
    DEADLINE=$(( $(date +%s) + TIMEOUT ))
fi
while kill -0 "$GAME_PID_ACTUAL" 2>/dev/null; do
    if [[ -n "$DEADLINE" && $(date +%s) -ge "$DEADLINE" ]]; then
        echo "[step9] timeout expired; killing game pid $GAME_PID_ACTUAL"
        kill "$GAME_PID_ACTUAL" 2>/dev/null || true
        TIMED_OUT=1
        break
    fi
    sleep 2
done
echo "[step9] game exited"
if [[ -n "$PERF_PID" ]]; then wait "$PERF_PID" 2>/dev/null; fi
if [[ -n "$SYSMON_PID" ]]; then kill "$SYSMON_PID" 2>/dev/null; wait "$SYSMON_PID" 2>/dev/null; SYSMON_PID=""; fi

cp "$Z/console.txt" "$RUN_DIR/" 2>/dev/null || true
# the game's own stdout: every "[ZB] patching" line and Byte Buddy error, including those before console.txt starts
cp "$Z/projectzomboid.sh.log" "$RUN_DIR/game-stdout.log" 2>/dev/null || true
cp "$Z"/pzbutter/*.out* "$RUN_DIR/" 2>/dev/null || true
if [[ -f "/tmp/perf-${GAME_PID_ACTUAL}.map" ]]; then cp "/tmp/perf-${GAME_PID_ACTUAL}.map" "$RUN_DIR/" 2>/dev/null || true; fi
[[ -f "$BENCH_SAVE/mods.txt" ]] && cp "$BENCH_SAVE/mods.txt" "$RUN_DIR/save-mods.txt" 2>/dev/null || true

END_TIME="$(date -Iseconds)"

WORKERS_LINE="$(grep '\[PZButter\] workers:' "$RUN_DIR/console.txt" 2>/dev/null | tr '\n' '; ' || true)"

# step 10 manifest check + delete bench copy
echo "[step10] checking source save against manifest"
python3 "$TOOLS/manifest.py" check "$SRC_SAVE" "$RUN_DIR/source.manifest.json"; rc=$?
if (( rc != 0 )); then echo "source save differs (listed above)"; fi
if [[ "$BENCH_CREATED" -eq 1 ]]; then rm -rf "$BENCH_SAVE"; BENCH_CREATED=0; fi

# step 11 run.opts
ZB_JAR="$ZB_GAME_JAR"
ZB_SHA2="$ZB_SHA"
[[ -f "$ZB_JAR" ]] && ZB_SHA2="$(sha256sum "$ZB_JAR" | awk '{print $1}')"
cat > "$RUN_DIR/run.opts" <<EOF
scene=$SCENE
list=$LIST
save=$SAVE
props=$PROPS_LINE
chosen_mods=$CHOSEN_IDS
bench_mods=$BENCH_IDS
jvm=$JVM_STR
launch=$LAUNCH_LINE
pz_rev=$PZ_REV
zombiebuddy_sha256=$ZB_SHA2
workers=$WORKERS_LINE
timeout=$([[ "$TIMED_OUT" -eq 1 ]] && echo yes || echo no)
start=$START_TIME
end=$END_TIME
EOF

# step 12
if [[ "$SCENE" == "S7" ]]; then WINDOW_HINT="w1"; else WINDOW_HINT="scene"; fi
echo "[step12] profile with:"
echo "  python3 $TOOLS/frames.py $RUN_DIR/frames.out --marks $RUN_DIR/marks.out --window $WINDOW_HINT"
echo "run complete: $RUN_DIR"