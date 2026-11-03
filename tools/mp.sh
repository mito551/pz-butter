#!/usr/bin/env bash
# PZ Butter: one local dedicated-server + client measurement run (Plan 14, D9).
#
# Launches a private, local-only server from a copy of a Multiplayer save, joins it
# with the client, and records both sides in one run folder. It never touches the
# source save, never uses pkill, and only stops PIDs it started itself.
set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TOOLS="$SCRIPT_DIR"
# shellcheck source=tools/runlib.sh
source "$TOOLS/runlib.sh"

PZ_DIR="${PZ_DIR:-/home/mitori/Sonic/SteamLibrary/steamapps/common/ProjectZomboid}"
GAME_DIR="$PZ_DIR"
if [[ ! -f "$GAME_DIR/start-server.sh" && -f "$PZ_DIR/projectzomboid/start-server.sh" ]]; then
    GAME_DIR="$PZ_DIR/projectzomboid"
fi
Z="${Z:-$HOME/Zomboid}"

SCENE="M1"
LIST=""
SAVE=""
PROPS=""
DRY=0
TIMEOUT=600
PORT="${MP_PORT:-16261}"
OUT="${HOME}/Zomboid/pzbutter-runs"
CLIENT_MOD_LIST=""
ADMIN_PASS="${MP_PASSWORD:-pzbutter}"
MP_SKIP_PRECHECK="${MP_SKIP_PRECHECK:-0}"

usage() {
    cat >&2 <<'US'
usage: tools/mp.sh --scene M1|M2 --list bare|full --save Multiplayer/NAME
                   [--client-save-list PATH] [--props "k=v,..."]
                   [--port N] [--timeout SEC] [--out DIR] [--dry-run]
US
    exit 2
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --scene) SCENE="$2"; shift 2 ;;
        --list) LIST="$2"; shift 2 ;;
        --save) SAVE="$2"; shift 2 ;;
        --client-save-list) CLIENT_MOD_LIST="$2"; shift 2 ;;
        --props) PROPS="$2"; shift 2 ;;
        --port) PORT="$2"; shift 2 ;;
        --timeout) TIMEOUT="$2"; shift 2 ;;
        --out) OUT="$2"; shift 2 ;;
        --dry-run) DRY=1; shift ;;
        -h|--help) usage ;;
        *) echo "unknown arg: $1" >&2; usage ;;
    esac
done

[[ "$SCENE" == "M1" || "$SCENE" == "M2" ]] || { echo "scene must be M1 or M2" >&2; exit 2; }
[[ "$LIST" == "bare" || "$LIST" == "full" ]] || { echo "list must be bare or full" >&2; exit 2; }
[[ -n "$SAVE" ]] || usage
[[ "$SAVE" == Multiplayer/* && "${SAVE#Multiplayer/}" != */* && -n "${SAVE#Multiplayer/}" ]] \
    || { echo "save must be Multiplayer/NAME" >&2; exit 2; }
[[ "$PORT" =~ ^[0-9]+$ ]] || { echo "port must be a number" >&2; exit 2; }
[[ "$TIMEOUT" =~ ^[0-9]+$ ]] || { echo "timeout must be seconds" >&2; exit 2; }

SAVE_NAME="${SAVE#Multiplayer/}"
SRC_SAVE="$Z/Saves/$SAVE"
[[ -d "$SRC_SAVE" ]] || die "no save at $SRC_SAVE (--save Multiplayer/NAME)"
CLIENT_MOD_LIST="${CLIENT_MOD_LIST:-$Z/mods/default.txt}"

# --- refusals: before anything is written -----------------------------------

IN_USE="$(port_pids "$PORT")"
if [[ -n "$IN_USE" ]]; then
    echo "port $PORT is in use by pid ${IN_USE//$'\n'/, }" >&2
    echo "refusing to stop it; pick another --port" >&2
    exit 1
fi

if [[ "$MP_SKIP_PRECHECK" != "1" ]]; then
    if pgrep -x ProjectZomboid6 >/dev/null 2>&1 || pgrep -f 'zombie/network/GameServer' >/dev/null 2>&1; then
        die "a game or server process is already running"
    fi
    pgrep -x steam >/dev/null 2>&1 || die "Steam is not running"
    [[ -f "$GAME_DIR/ZombieBuddy.jar" ]] || die "no ZombieBuddy.jar in $GAME_DIR"
    [[ -f "$Z/mods/PZButter/42/media/java/common/PZButter.jar" ]] \
        || die "PZ Butter is not installed in $Z/mods/PZButter (run ./build.sh --install)"
    [[ "$SCENE" == "M2" ]] || [[ -f "$GAME_DIR/projectzomboid.jar" ]] || die "no projectzomboid.jar in $GAME_DIR"
fi

if [[ "$SCENE" == "M2" ]]; then
    echo "[mp] M2 is the in-game Host path; mp.sh launches only the client. Click Host in the menu." >&2
fi

# --- run folder and backups --------------------------------------------------

STAMP="$(run_stamp)"
RUN="$OUT/${STAMP}-${SCENE}-${LIST}-mp"
BAK="$RUN/.backup"
mkdir -p "$RUN/client" "$RUN/server" "$BAK" || die "cannot create $RUN"

SERVER_NAME="${SAVE_NAME}-pzbutter-bench-${STAMP}"
BENCH_SAVE="$Z/Saves/Multiplayer/$SERVER_NAME"
BENCH_INI="$Z/Server/$SERVER_NAME.ini"
FIFO="$RUN/server/in"
SERVER_PID=""
CLIENT_PID=""
SERVER_READY=0
BENCH_CREATED=0
INI_CREATED=0

snmp_sample() {
    local out="$1"
    {
        echo "epoch $(date +%s)"
        grep '^Udp:' /proc/net/snmp 2>/dev/null || true
    } > "$out"
}

stop_server() {
    [[ -n "$SERVER_PID" ]] || return 0
    if (( SERVER_READY )); then
        printf 'save\n' >&3 2>/dev/null || true
        sleep 2
        printf 'quit\n' >&3 2>/dev/null || true
    fi
    local i
    if (( SERVER_READY )); then
        for i in $(seq 1 50); do
            kill -0 "$SERVER_PID" 2>/dev/null || break
            sleep 0.2
        done
    fi
    if kill -0 "$SERVER_PID" 2>/dev/null; then
        kill -TERM "$SERVER_PID" 2>/dev/null || true
        for i in $(seq 1 15); do
            kill -0 "$SERVER_PID" 2>/dev/null || break
            sleep 0.2
        done
    fi
    kill -0 "$SERVER_PID" 2>/dev/null && kill -KILL "$SERVER_PID" 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
    exec 3>&- 2>/dev/null || true
    SERVER_PID=""
}

cleanup() {
    local rc=$?
    stop_server
    [[ -n "$FIFO" ]] && rm -f "$FIFO"
    [[ -n "$BAK" ]] && restore_file "$CLIENT_MOD_LIST" "$BAK" default.txt
    [[ -n "$BAK" ]] && restore_file "$Z/PZButter.properties" "$BAK" PZButter.properties
    (( BENCH_CREATED )) && rm -rf "$BENCH_SAVE"
    (( INI_CREATED )) && rm -f "$BENCH_INI"
    exit "$rc"
}
trap cleanup EXIT INT TERM

backup_file "$CLIENT_MOD_LIST" "$BAK" default.txt
backup_file "$Z/PZButter.properties" "$BAK" PZButter.properties

# --- mod lists ---------------------------------------------------------------

CHOSEN_LIST="$RUN/client-default.txt"
if [[ "$LIST" == "bare" ]]; then
    printf 'mod = ZombieBuddy,\nmod = PZButter,\n' > "$CHOSEN_LIST" || die "cannot write $CHOSEN_LIST"
else
    [[ -f "$CLIENT_MOD_LIST" ]] || die "no client mod list at $CLIENT_MOD_LIST"
    cp "$CLIENT_MOD_LIST" "$CHOSEN_LIST" || die "cannot copy client mod list"
fi
CHOSEN_IDS="$(mod_ids "$CHOSEN_LIST")"
[[ -n "$CHOSEN_IDS" ]] || die "mod list $CHOSEN_LIST has no mods"

norm_ids() { tr ',;' '\n' | sed '/^[[:space:]]*$/d' | sort -u | paste -sd, -; }
CHOSEN_N="$(printf '%s\n' "$CHOSEN_IDS" | norm_ids)"
SERVER_N="$(printf '%s\n' "${MP_SERVER_MODS_OVERRIDE:-$CHOSEN_IDS}" | norm_ids)"
if [[ "$CHOSEN_N" != "$SERVER_N" ]]; then
    echo "chosen: $CHOSEN_N" >&2
    echo "server: $SERVER_N" >&2
    die "mod lists differ; server and client must load the same mods"
fi
SERVER_MODS="$(printf '%s' "$CHOSEN_N" | tr ',' ';')"

# --- bench server ini --------------------------------------------------------

if (( ! DRY )); then
    [[ ! -e "$BENCH_INI" ]] || die "server ini already exists: $BENCH_INI"
fi
INI="$RUN/server.ini"
(( DRY )) || INI="$BENCH_INI"
SRC_INI="$Z/Server/$SAVE_NAME.ini"
WORKSHOP=""
if [[ -f "$SRC_INI" ]]; then
    WORKSHOP="$(sed -n 's/^WorkshopItems=//p' "$SRC_INI" | head -1)"
    cp "$SRC_INI" "$INI" || die "cannot copy $SRC_INI"
    sed -i -E \
        -e "s|^Public=.*|Public=false|" \
        -e "s|^Port=.*|Port=$PORT|" \
        -e "s|^Mods=.*|Mods=$SERVER_MODS|" \
        -e "s|^WorkshopItems=.*|WorkshopItems=$WORKSHOP|" \
        "$INI" || die "cannot edit $INI"
else
    printf 'Public=false\nPort=%s\nMods=%s\nWorkshopItems=%s\n' \
        "$PORT" "$SERVER_MODS" "$WORKSHOP" > "$INI" || die "cannot write $INI"
fi
(( DRY )) || INI_CREATED=1

# --- properties --------------------------------------------------------------

SCENE_PROPS="devScene=walk
devSceneSec=90"
[[ "$SCENE" == "M2" ]] && SCENE_PROPS="devScene=walk
devSceneSec=90"
{
    echo "# generated by tools/mp.sh"
    echo "enabled=true"
    echo "frameLog=true"
    echo "streamTiming=true"
    echo "driveTrace=true"
    echo "devAutoContinue=true"
    echo "devSceneQuit=true"
    echo "devMark=true"
    echo "serverTickLog=true"
    echo "netStats=true"
    printf '%s\n' "$SCENE_PROPS"
    if [[ -n "$PROPS" ]]; then
        printf '%s\n' "$PROPS" | tr ',' '\n'
    fi
} > "$Z/PZButter.properties" || die "cannot write $Z/PZButter.properties"
cp "$Z/PZButter.properties" "$RUN/client/PZButter.properties" 2>/dev/null || true

# --- launch command lines ----------------------------------------------------

SERVER_CMD=("$GAME_DIR/jre64/bin/java")
SERVER_JVM=(
    -cp "./:./projectzomboid.jar"
    -Djava.awt.headless=true
    --enable-native-access=ALL-UNNAMED
    --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED
    -Xms2048m -Xmx2048m -XX:+UseZGC
    -Dzomboid.steam=0 -Dzomboid.znetlog=1
    -Djava.library.path=./:./natives/
    -XX:-OmitStackTraceInFastThrow
    -Djava.security.egd=file:/dev/urandom
    -javaagent:./ZombieBuddy.jar
    "-Xlog:gc*:file=$RUN/server/gc.log:time,uptime,level,tags"
)
SERVER_APP=(
    zombie/network/GameServer
    -coop
    -servername "$SERVER_NAME"
    -adminusername admin
    -adminpassword "$ADMIN_PASS"
    -cachedir "$Z"
    -port "$PORT"
)
PROFILE_SRC=""
for p in "$GAME_DIR/profile.jfc" "$GAME_DIR/jre64/lib/jfr/profile.jfc"; do
    [[ -f "$p" ]] && { PROFILE_SRC="$p"; break; }
done
SERVER_JFR=()
if [[ -n "$PROFILE_SRC" ]]; then
    python3 - "$PROFILE_SRC" "$RUN/server/profile.jfc" <<'PY' || die "cannot prepare JFR profile"
import sys
src, out = sys.argv[1], sys.argv[2]
s = open(src).read()
s = s.replace('<setting name="period" control="java.util.concurrent">20 ms</setting>',
              '<setting name="period" control="java.util.concurrent">10 ms</setting>')
open(out, 'w').write(s)
PY
    SERVER_JFR+=("-XX:StartFlightRecording=filename=$RUN/server/server.jfr,settings=$RUN/server/profile.jfc,dumponexit=true")
fi
SERVER_ARGS=("${SERVER_JVM[@]}" "${SERVER_JFR[@]}" "${SERVER_APP[@]}")

CLIENT_CMD=("$PZ_DIR/projectzomboid.sh" -javaagent:ZombieBuddy.jar -Dzomboid.steam=1)
CLIENT_ARGS=()
if [[ -n "${MP_CLIENT_JOIN_ARGS:-}" ]]; then
    read -r -a CLIENT_ARGS <<< "$MP_CLIENT_JOIN_ARGS"
elif [[ "$SCENE" == "M1" ]]; then
    CLIENT_ARGS=(-connect 127.0.0.1 -port "$PORT" -password "$ADMIN_PASS")
fi
CLIENT_ARGS+=(--)

if [[ -n "${MP_SERVER_CMD:-}" ]]; then
    SERVER_CMD=("$MP_SERVER_CMD")
    SERVER_ARGS=()
fi
if [[ -n "${MP_CLIENT_CMD:-}" ]]; then
    CLIENT_CMD=("$MP_CLIENT_CMD")
    CLIENT_ARGS=()
fi

SERVER_LINE="(cd $GAME_DIR && ${SERVER_CMD[*]} ${SERVER_ARGS[*]})"
CLIENT_LINE="${CLIENT_CMD[*]} ${CLIENT_ARGS[*]}"

{
    echo "scene=$SCENE"
    echo "list=$LIST"
    echo "save=$SAVE"
    echo "server_name=$SERVER_NAME"
    echo "port=$PORT"
    echo "mods=$CHOSEN_N"
    echo "props=$PROPS"
    echo "server=$SERVER_LINE"
    echo "client=$CLIENT_LINE"
    echo "start=$(date +%s)"
} > "$RUN/run.opts"

if (( DRY )); then
    echo "[dry-run] run folder: $RUN"
    echo "[dry-run] server: $SERVER_LINE"
    echo "[dry-run] client: $CLIENT_LINE"
    echo "[dry-run] no process started"
    exit 0
fi

# --- server ------------------------------------------------------------------

if [[ "$SCENE" == "M1" ]]; then
    manifest_write "$SRC_SAVE" "$RUN/source.manifest.json" || die "manifest write failed"
    cp -a "$SRC_SAVE" "$BENCH_SAVE" || die "cannot copy $SRC_SAVE to $BENCH_SAVE"
    BENCH_CREATED=1

    mkfifo "$FIFO" || die "cannot create $FIFO"
    echo "[mp] server: $SERVER_LINE"
    (
        cd "$GAME_DIR" || exit 1
        export LD_LIBRARY_PATH="$GAME_DIR:$GAME_DIR/natives${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
        exec "${SERVER_CMD[@]}" "${SERVER_ARGS[@]}"
    ) < "$FIFO" > "$RUN/server/console.txt" 2>&1 &
    SERVER_PID=$!
    exec 3>"$FIFO"

    echo "[mp] waiting for server ready"
    deadline=$((SECONDS + TIMEOUT))
    while (( SECONDS < deadline )); do
        if grep -q '\*\*\* SERVER STARTED \*\*\*\*' "$RUN/server/console.txt" 2>/dev/null; then
            SERVER_READY=1
            break
        fi
        if ! kill -0 "$SERVER_PID" 2>/dev/null; then
            echo "--- server console ---" >&2
            tail -n 20 "$RUN/server/console.txt" >&2 || true
            die "server exited before ready"
        fi
        sleep 0.2
    done
    (( SERVER_READY )) || die "waiting for server ready (timeout ${TIMEOUT}s); see $RUN/server/console.txt"
    echo "[mp] server ready"
fi

# --- client ------------------------------------------------------------------

if [[ "$SCENE" == "M2" ]]; then
    echo "[mp] click Host in the game menu; the client records the window"
fi
echo "[mp] client: $CLIENT_LINE"
(
    cd "$GAME_DIR" || exit 1
    exec timeout --foreground "$TIMEOUT" "${CLIENT_CMD[@]}" "${CLIENT_ARGS[@]}"
) > "$RUN/client/console.txt" 2>&1 &
CLIENT_PID=$!
snmp_sample "$RUN/snmp.start"
wait "$CLIENT_PID"
CLIENT_RC=$?
CLIENT_PID=""
if (( CLIENT_RC == 124 )); then
    rc=1
elif (( CLIENT_RC != 0 )); then
    echo "client exited with status $CLIENT_RC" >&2
    rc=$CLIENT_RC
else
    rc=0
fi
(( rc == 0 )) || echo "see $RUN/client/console.txt" >&2
snmp_sample "$RUN/snmp.end"

# --- stop server, restore, verify -------------------------------------------

stop_server
rm -f "$FIFO"

# recordings: the client's frame log and marks, the server's tick log
for f in frames.out marks.out; do
    [[ -f "$Z/pzbutter/$f" ]] && cp "$Z/pzbutter/$f" "$RUN/client/$f" 2>/dev/null || true
done
for f in ticks.out hitches.out; do
    [[ -f "$Z/pzbutter/$f" ]] && cp "$Z/pzbutter/$f" "$RUN/server/$f" 2>/dev/null || true
done
[[ -f "$Z/pzbutter/net.out" ]] && cp "$Z/pzbutter/net.out" "$RUN/client/net.out" 2>/dev/null || true
[[ -f "$Z/pzbutter/net-server.out" ]] && cp "$Z/pzbutter/net-server.out" "$RUN/server/net.out" 2>/dev/null || true

restore_file "$CLIENT_MOD_LIST" "$BAK" default.txt
restore_file "$Z/PZButter.properties" "$BAK" PZButter.properties
(( BENCH_CREATED )) && rm -rf "$BENCH_SAVE"
(( INI_CREATED )) && rm -f "$BENCH_INI"
BENCH_CREATED=0
INI_CREATED=0

if [[ "$SCENE" == "M1" ]]; then
    manifest_check "$SRC_SAVE" "$RUN/source.manifest.json"
    MRC=$?
    (( MRC == 0 )) || die "source save changed during the run (manifest check failed)"
fi

echo "end=$(date +%s)" >> "$RUN/run.opts"
echo "[mp] done: $RUN"
exit "$rc"
