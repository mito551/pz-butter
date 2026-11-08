package pzbutter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Server cell spread: the chunk-level part of ServerMap$ServerCell.RecalcAll2 driven from ServerCell.Load2,
 * budgeted per tick. Pure logic lives here; the game seam is Host, implemented by the patch holder.
 * Stock step order (bytecode of zombie.network.ServerMap$ServerCell.RecalcAll2): roomDec(A), min/max scan,
 * ensure-surround windows (D), border recalc loop (E), chunk flags+rooms (F), doLoadGridsquare (G),
 * roomInc(+indoor spawn)(H). isLoaded differs from stock: false between slices, true only at the end.
 */
public final class CellSpread {
    private CellSpread() {}

    static final Guard guard = new Guard("cellSpread");

    static final long SAFETY_NET_BAND = 120;      // ms, near an active player
    static final int REST_CAP_MS = 50;
    static final int PREFETCH_AHEAD_TILES = 192;
    static final int PREFETCH_KEEP_MS = 3000;

    // ---- pure budget ----

    /** Cost of the last chunk unit, exponential mean; the budget floor is at least one chunk. */
    static double chunkCostMs = 10.0;
    public static void noteChunkCostMs(double ms) {
        chunkCostMs = chunkCostMs <= 0 ? ms : chunkCostMs * 0.9 + ms * 0.1;
    }

    public static long budgetMs(long tickTargetMs, long margin, long restOfTickMs, long preupdateUsedMs) {
        long floor = Math.max(1, (long) chunkCostMs);
        long free = tickTargetMs - margin - restOfTickMs - preupdateUsedMs;
        return Math.max(floor, free);
    }

    // rest-of-tick estimate: largest of the last ticks, decaying 10 % a tick, at most REST_CAP_MS
    static long restEstimateMs;
    public static void noteRestTick(long largestLastTickMs) {
        restEstimateMs = Math.min(REST_CAP_MS, Math.max(largestLastTickMs, restEstimateMs * 9 / 10));
    }
    public static long restOfTickMs() { return restEstimateMs; }

    // ---- safety net ----

    public static long safetyNet(long budgetMs, int cellX, int cellY, int playerX, int playerY) {
        int d = Math.max(Math.abs(cellX - playerX), Math.abs(cellY - playerY));
        return safetyNet(budgetMs, d <= 1);
    }

    public static long safetyNet(long budgetMs, boolean near) {
        return near ? Math.max(budgetMs, SAFETY_NET_BAND) : budgetMs;
    }

    // ---- prefetch: cells along the player's heading, kept 3 s after last ahead ----

    private static final Map<Long, Long> aheadCells = new HashMap<>();

    public static void prefetchUpdate(long nowMs, int cellX, int cellY, double dirX, double dirY) {
        double len = Math.hypot(dirX, dirY);
        int r = (PREFETCH_AHEAD_TILES + 63) / 64 + 1;   // window big enough for the ray
        for (int dy = -r; dy <= r; dy++) {
            for (int dxx = -r; dxx <= r; dxx++) {
                if (segmentHitsCell(cellX, cellY, dirX, dirY, len, cellX + dxx, cellY + dy))
                    aheadCells.put(key(cellX + dxx, cellY + dy), nowMs + PREFETCH_KEEP_MS);
            }
        }
    }

    public static boolean prefetchKeeps(int cx, int cy, long nowMs) {
        Long exp = aheadCells.get(key(cx, cy));
        return exp != null && nowMs <= exp;
    }

    private static long key(int cx, int cy) { return ((long) cx << 32) | (cy & 0xffffffffL); }

    /** Does the segment from the player cell center along dir (192 tiles) touch the cell? */
    static boolean segmentHitsCell(int pCX, int pCY, double dx, double dy, double len, int cx, int cy) {
        if (dx == 0 && dy == 0) return cx == pCX && cy == pCY;
        long px = (long) pCX * 64 + 32, py = (long) pCY * 64 + 32;
        long x0 = (long) cx * 64, x1 = x0 + 63, y0 = (long) cy * 64, y1 = y0 + 63;
        double txMin = (x0 - px) / dx, txMax = (x1 - px) / dx;
        if (txMin > txMax) { double t = txMin; txMin = txMax; txMax = t; }
        double tyMin = (y0 - py) / dy, tyMax = (y1 - py) / dy;
        if (tyMin > tyMax) { double t = tyMin; tyMin = tyMax; tyMax = t; }
        double tMin = Math.max(Math.max(txMin, tyMin), 0), tMax = Math.min(Math.min(txMax, tyMax), PREFETCH_AHEAD_TILES);
        return tMax >= tMin;
    }

    // ---- the spread runner ----

    /** Game seam: the real adapter lives in ServerPatches; the test uses a recording fake. */
    interface Host {
        long nanoTime();
        /** Cell-creation snapshot of unexploredRooms, consumed (cleared) for step A. */
        List<Object> currentRooms();
        Object chunk(int cx, int cy);
        int minLevel(Object chunk);
        int maxLevel(Object chunk);
        /** getGridSquare; if it has objects, EnsureSurroundNotNull. */
        void ensureAt(int x, int y, int level);
        /** getGridSquare; if not null, RecalcAllWithNeighbours(true). */
        void borderRecalcAt(int x, int y, int level);
        /** chunk.loaded=true and the per-square room/propertiesDirty pass; collects room defs. */
        void finishChunk(Object chunk, List<Object> roomsOut);
        void doLoadGridsquare(Object chunk);
        void roomDec(Object def);   // def.indoorZombies -= 1
        void roomInc(Object def);   // def.indoorZombies += 1 (+ tryAddIndoorZombies when it becomes 1)
        void setLoaded(boolean b);
        void loadVehicles();
    }

    static final int PH_A_ROOMS = 0, PH_SCAN = 1, PH_ENSURE = 2, PH_BORDER = 3, PH_FLAGS = 4, PH_LOADSQ = 5, PH_INC = 6, PH_TAIL = 7;

    static final class Slice {
        int phase = PH_A_ROOMS;
        boolean phaseStarted;
        int scanIdx;                      // 0..63 within the min/max scan
        int minLevel = Integer.MAX_VALUE, maxLevel = Integer.MIN_VALUE;
        int level, x, part;               // loop cursors
        int chunkIdx;                     // 0..63 for flags/loadsq passes
        final ArrayList<Object> rooms = new ArrayList<>();
        int roomIdx;

        boolean done() { return phase > PH_TAIL; }
    }

    private static final Map<Long, Slice> slices = new HashMap<>();
    private static final Slice ANON = new Slice();   // cell == null (tests)

    public static void spread(Host host, Object cell, int wxTiles, int wyTiles, long budgetMs) {
        try {
            Slice s = sliceState(cell);
            if (s.done()) resetSlice(s);
            long startNs = host.nanoTime();
            long limitNs = budgetMs <= 0 || budgetMs >= Long.MAX_VALUE / 1_000_000 ? Long.MAX_VALUE : startNs + budgetMs * 1_000_000L;
            if (s.done()) { finishSlice(host); return; }
            do {
                step(host, s, wxTiles, wyTiles);
            } while (!s.done() && host.nanoTime() <= limitNs);   // floor: at least one unit per slice
            if (s.done()) finishSlice(host);
            else if (limitNs != Long.MAX_VALUE) host.setLoaded(false);   // nobody sees a half-spread cell
        } catch (Throwable t) {
            guard.fail(t);
            try { host.setLoaded(true); host.loadVehicles(); } catch (Throwable ignored) {}
        }
    }

    private static void finishSlice(Host host) {
        host.setLoaded(true);
        host.loadVehicles();
        // GameHost holds only a VarHandle per cell; drop the entry once its slice
        // has been drained so a re-arrived cell re-creates it instead of keeping
        // (potentially stale) state forever.
        if (host instanceof GameHost) hosts.remove(keyOf(((GameHost) host).cell));
    }

    private static Slice sliceState(Object cell) {
        if (cell == null) return ANON;
        return slices.computeIfAbsent(keyOf(cell), k -> new Slice());
    }

    private static long keyOf(Object o) { return (long) (System.identityHashCode(o) & 0x7fffffff); }

    /** One unit of work, in the stock order. */
    private static void step(Host host, Slice s, int wx, int wy) {
        switch (s.phase) {
            case PH_A_ROOMS: {
                for (Object def : host.currentRooms()) host.roomDec(def);
                nextPhase(s, PH_SCAN);
                return;
            }
            case PH_SCAN: {
                Object c = host.chunk(s.scanIdx & 7, s.scanIdx >> 3);
                if (c != null) {
                    s.minLevel = Math.min(s.minLevel, host.minLevel(c));
                    s.maxLevel = Math.max(s.maxLevel, host.maxLevel(c));
                }
                if (++s.scanIdx >= 64) nextPhase(s, s.maxLevel >= 1 ? PH_ENSURE : PH_BORDER);
                return;
            }
            case PH_ENSURE: {   // levels 1..max: four horizontal windows + four vertical windows per column
                if (!s.phaseStarted) { s.phaseStarted = true; s.level = 1; }
                if (s.level > s.maxLevel) { nextPhase(s, PH_BORDER); return; }
                ensureUnit(host, s, wx, wy);
                return;
            }
            case PH_BORDER: {   // levels min..max: four border squares per column
                if (!s.phaseStarted) { s.phaseStarted = true; s.level = s.minLevel == Integer.MAX_VALUE ? 0 : Math.max(s.minLevel, 0); }
                if (s.level > s.maxLevel) { nextPhase(s, PH_FLAGS); return; }
                borderUnit(host, s, wx, wy);
                return;
            }
            case PH_FLAGS: {
                Object c = host.chunk(s.chunkIdx & 7, s.chunkIdx >> 3);
                if (c != null) {
                    int before = s.rooms.size();
                    host.finishChunk(c, s.rooms);
                    // stock keeps unexploredRooms a set: no duplicate defs within one recalc
                    for (int i = before; i < s.rooms.size(); i++)
                        if (s.rooms.indexOf(s.rooms.get(i)) < i) { s.rooms.remove(i); i--; }
                }
                if (++s.chunkIdx >= 64) nextPhase(s, PH_LOADSQ);
                return;
            }
            case PH_LOADSQ: {
                Object c = host.chunk(s.chunkIdx & 7, s.chunkIdx >> 3);
                if (c != null) host.doLoadGridsquare(c);
                if (++s.chunkIdx >= 64) nextPhase(s, PH_INC);
                return;
            }
            case PH_INC: {
                if (s.roomIdx >= s.rooms.size()) { nextPhase(s, PH_TAIL); return; }
                host.roomInc(s.rooms.get(s.roomIdx++));
                return;
            }
            default:
                nextPhase(s, PH_TAIL + 1);   // PH_TAIL: finished
        }
    }

    private static void nextPhase(Slice s, int phase) {
        s.phase = phase;
        s.phaseStarted = false;
        s.scanIdx = 0; s.level = 0; s.x = 0; s.part = 0; s.chunkIdx = 0; s.roomIdx = 0;
    }

    /** One ensure call plus cursor advance. Horizontal windows x=-1..64 then vertical x=0..63. */
    private static void ensureUnit(Host host, Slice s, int wx, int wy) {
        switch (s.part) {
            case 0: host.ensureAt(wx + s.x, wy - 1, s.level); s.part = 1; break;
            case 1: if (s.x >= 0 && s.x <= 63) host.ensureAt(wx + s.x, wy, s.level); s.part = 2; break;
            case 2: if (s.x >= 0 && s.x <= 63) host.ensureAt(wx + s.x, wy + 64, s.level); s.part = 3; break;
            case 3: if (s.x >= 0 && s.x <= 63) host.ensureAt(wx + s.x, wy + 63, s.level);
                if (s.x < 64) { s.x++; s.part = 0; } else { s.part = 4; s.x = 0; }   // horizontal done -> vertical
                break;
            case 4: host.ensureAt(wx - 1, wy + s.x, s.level); s.part = 5; break;
            case 5: host.ensureAt(wx, wy + s.x, s.level); s.part = 6; break;
            case 6: host.ensureAt(wx + 64, wy + s.x, s.level); s.part = 7; break;
            default:
                host.ensureAt(wx + 63, wy + s.x, s.level);
                if (++s.x > 63) { s.x = -1; s.part = 0; s.level++; }   // vertical done -> next level
                else s.part = 4;
        }
    }

    /** One border-recalc call plus cursor advance, per the stock loop's four bodies. */
    private static void borderUnit(Host host, Slice s, int wx, int wy) {
        switch (s.part) {
            case 0: host.borderRecalcAt(wx + s.x, wy - 1, s.level); s.part = 1; break;
            case 1: host.borderRecalcAt(wx + s.x, wy + 63, s.level); s.part = 2; break;
            case 2: host.borderRecalcAt(wx, wy + s.x, s.level); s.part = 3; break;
            default:
                host.borderRecalcAt(wx + 63, wy + s.x, s.level);
                if (++s.x > 63) { s.x = 0; s.part = 0; s.level++; }
                else s.part = 0;
        }
    }

    static void resetForTest() {
        chunkCostMs = 10.0;
        restEstimateMs = 0;
        aheadCells.clear();
        slices.clear();
        hosts.clear();
        lastCells.clear();
        ANON.phase = PH_A_ROOMS;
        ANON.phaseStarted = false;
        armed = false;
        guard.on = false;
    }

    // ---- runtime adapter: the game seam is zombie.network.ServerMap$ServerCell (server + host) ----

    public static volatile boolean armed;

    static void init() {
        try {
            armed = Settings.enabled && Settings.flag("serverCellSpread");   // Settings filters by side
            guard.on = armed;
        } catch (Throwable t) { guard.fail(t); armed = false; }
    }

    /** Load2 enter (patch holder): when armed the spread executes RecalcAll2's stock body budgeted per slice. */
    public static boolean onLoad2Enter(Object cellObj) {
        if (!armed) return false;
        try {
            if (!(cellObj instanceof zombie.network.ServerMap.ServerCell)) return false;
            zombie.network.ServerMap.ServerCell cell = (zombie.network.ServerMap.ServerCell) cellObj;
            spread(hostFor(cell), cell, cell.getWX() * 64, cell.getWY() * 64, budgetNow(cell));
            return true;
        } catch (Throwable t) { guard.fail(t); return false; }
    }

    /** RecalcAll2 enter (patch holder): while the spread is mid-way the stock body must not double-run. */
    public static boolean onRecalcAll2Enter(Object cellObj) {
        if (!armed) return false;
        try {
            Slice s = slices.get(keyOf(cellObj));
            if (s != null && !s.done()) return true;
        } catch (Throwable t) { guard.fail(t); }
        return false;
    }

    static final int SPREAD_MARGIN_MS = 15;

    static long budgetNow(zombie.network.ServerMap.ServerCell cell) {
        long target = Math.max(ServerTick.lastIntervalMs(), 33);
        long used = Math.max(0, (System.nanoTime() - ServerTick.tickStartNs) / 1_000_000L);
        long budget = budgetMs(target, SPREAD_MARGIN_MS, restOfTickMs(), used);
        float cx = cell.getWX() * 64f + 32f, cy = cell.getWY() * 64f + 32f;
        boolean near = false;
        for (zombie.core.raknet.UdpConnection conn : zombie.network.GameServer.SlotToConnection)
            if (conn != null && conn.isRelevantTo(cx, cy)) { near = true; break; }
        return safetyNet(budget, near);
    }

    private static final Map<Long, GameHost> hosts = new HashMap<>();

    private static GameHost hostFor(zombie.network.ServerMap.ServerCell cell) {
        GameHost h = hosts.get(keyOf(cell));
        if (h == null) { h = new GameHost(cell); hosts.put(keyOf(cell), h); }
        return h;
    }

    private static void resetSlice(Slice s) {
        s.phase = PH_A_ROOMS; s.phaseStarted = false; s.scanIdx = 0;
        s.minLevel = Integer.MAX_VALUE; s.maxLevel = Integer.MIN_VALUE;
        s.level = 0; s.x = 0; s.part = 0; s.chunkIdx = 0; s.roomIdx = 0; s.rooms.clear();
    }
    static final class GameHost implements Host {
        final zombie.network.ServerMap.ServerCell cell;
        final java.lang.invoke.VarHandle unexploredRooms;

        GameHost(zombie.network.ServerMap.ServerCell c) {
            cell = c;
            java.lang.invoke.VarHandle vh = null;
            try { vh = Game.field(c.getClass(), "unexploredRooms", java.util.HashSet.class); } catch (Throwable ignored) {}
            unexploredRooms = vh;
        }

        public long nanoTime() { return System.nanoTime(); }
        public List<Object> currentRooms() {
            try {
                if (unexploredRooms == null) return new ArrayList<>();
                @SuppressWarnings("unchecked")
                java.util.HashSet<Object> set = (java.util.HashSet<Object>) unexploredRooms.get(cell);
                List<Object> out = new ArrayList<>(set);
                set.clear();
                return out;
            } catch (Throwable t) { return new ArrayList<>(); }
        }
        public Object chunk(int cx, int cy) { return cell.getChunk(cx, cy); }
        public int minLevel(Object chunk) { return ((zombie.iso.IsoChunk) chunk).minLevel; }
        public int maxLevel(Object chunk) { return ((zombie.iso.IsoChunk) chunk).maxLevel; }
        public void ensureAt(int x, int y, int level) {
            zombie.iso.IsoGridSquare sq = zombie.network.ServerMap.instance.getGridSquare(x, y, level);
            if (sq != null && !sq.getObjects().isEmpty())
                zombie.iso.IsoWorld.instance.currentCell.EnsureSurroundNotNull(sq.x, sq.y, level);
        }
        public void borderRecalcAt(int x, int y, int level) {
            zombie.iso.IsoGridSquare sq = zombie.network.ServerMap.instance.getGridSquare(x, y, level);
            if (sq != null) sq.RecalcAllWithNeighbours(true);
        }
        public void finishChunk(Object chunkObj, List<Object> roomsOut) {
            zombie.iso.IsoChunk chunk = (zombie.iso.IsoChunk) chunkObj;
            chunk.loaded = true;
            for (int level = chunk.minLevel; level <= chunk.maxLevel; level++) {
                zombie.iso.IsoGridSquare[] squares = chunk.squares[chunk.squaresIndexOfLevel(level)];
                if (squares == null) continue;   // level never populated: fixed 8x8 geometry, 64 squares max
                for (int slot = 0; slot < 64; slot++) {
                    zombie.iso.IsoGridSquare sq = squares[slot];
                    if (sq == null) continue;
                    zombie.iso.areas.IsoRoom room = sq.getRoom();
                    if (room != null && !room.def.explored) roomsOut.add(room.def);
                    sq.propertiesDirty = true;
                }
            }
        }
        public void doLoadGridsquare(Object chunkObj) { ((zombie.iso.IsoChunk) chunkObj).doLoadGridsquare(); }
        public void roomDec(Object def) { ((zombie.iso.RoomDef) def).indoorZombies -= 1; }
        public void roomInc(Object def) {
            zombie.iso.RoomDef d = (zombie.iso.RoomDef) def;
            d.indoorZombies += 1;
            if (d.indoorZombies == 1) {
                try { zombie.VirtualZombieManager.instance.tryAddIndoorZombies(d, false); }
                catch (Exception e) { zombie.debug.DebugType.General.printException(e, zombie.debug.LogSeverity.Error); }
            }
        }
        public void setLoaded(boolean b) { cell.isLoaded = b; }
        public void loadVehicles() {
            for (int cy = 0; cy < 8; cy++)
                for (int cx = 0; cx < 8; cx++) {
                    zombie.iso.IsoChunk c = cell.chunks[cy][cx];
                    if (c != null && !c.isNewChunk()) zombie.vehicles.VehiclesDB2.instance.loadChunkMain(c);
                }
        }
    }

    private static final Map<Integer, double[]> lastCells = new HashMap<>();   // conn slot -> {cellX, cellY, dirX, dirY}

    /** ServerTick.begin: refresh the prefetch cells from every connected player's heading. */
    public static void tickStart() {
        if (!armed) return;
        try {
            long now = System.currentTimeMillis();
            zombie.core.raknet.UdpConnection[] cons = zombie.network.GameServer.SlotToConnection;
            for (int i = 0; i < cons.length; i++) {
                zombie.core.raknet.UdpConnection conn = cons[i];
                zombie.characters.IsoPlayer p = null;
                if (conn != null && conn.players != null)
                    for (zombie.characters.IsoPlayer q : conn.players)
                        if (q != null) { p = q; break; }
                if (p == null) { lastCells.remove(i); continue; }
                int cx = (int) Math.floor(p.getX() / 64.0), cy = (int) Math.floor(p.getY() / 64.0);
                double[] st = lastCells.get(i);
                double dirX = 0, dirY = 0;
                if (st != null) {
                    double dx = cx - st[0], dy = cy - st[1];
                    // set a heading once the player has moved a tile (1/64 of a cell),
                    // rather than a quarter cell, so prefetches start with the first movement
                    if (dx * dx + dy * dy >= 1.0 / 4096.0) { dirX = dx; dirY = dy; }
                    else { dirX = st[2]; dirY = st[3]; }
                }
                prefetchUpdate(now, cx, cy, dirX, dirY);
                lastCells.put(i, new double[] { cx, cy, dirX, dirY });
            }
        } catch (Throwable t) { guard.fail(t); }
    }
}
