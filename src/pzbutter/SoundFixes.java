package pzbutter;

import java.util.Arrays;
import java.util.IdentityHashMap;
import zombie.characters.IsoPlayer;
import zombie.iso.CorpseCount;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoChunkMap;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;

/**
 * Plan 4 small sound fixes: soundZoneCache (ParameterZone), electricityLevelRange (AmbientStreamManager),
 * fliesToggleFix (FliesSound chunk level), fmodNullHandleFix (FMOD event instance handle) and corpseFliesCap.
 * The pure cores are tested in SoundTest; the holder calls them through SoundPatches.
 */
final class SoundFixes {
    private SoundFixes() {}

    // soundZoneCache: one entry per zone parameter instance, keyed by listener, its square and the zone version

    private record ZoneEntry(Object listener, int sqX, int sqY, long version, float value) {}

    private static final IdentityHashMap<Object, ZoneEntry> zoneCache = new IdentityHashMap<>();
    private static volatile long zoneVersion;

    /** A zone was added or removed: every cached value is stale. */
    static void zonesChanged() { zoneVersion++; }

    static long zoneVersion() { return zoneVersion; }

    /** The cached value for this parameter, or null when the key differs. */
    static synchronized Float zoneCached(Object param, Object listener, int sqX, int sqY, long zoneVersion) {
        ZoneEntry e = zoneCache.get(param);
        if (e == null || e.listener != listener || e.sqX != sqX || e.sqY != sqY || e.version != zoneVersion) return null;
        return e.value;
    }

    static synchronized void zoneStore(Object param, Object listener, int sqX, int sqY, long zoneVersion, float value) {
        zoneCache.put(param, new ZoneEntry(listener, sqX, sqY, zoneVersion, value));
    }

    /** The square a float position falls in, as the zone scan floors it. */
    static int square(float v) { return (int) Math.floor(v); }

    // electricityLevelRange: the level range of the loaded chunks

    /** Min and max level over the loaded chunks (inclusive), or null when none is loaded. */
    static int[] levelRange(int[] minLevels, int[] maxLevels) {
        if (minLevels.length == 0) return null;
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int i = 0; i < minLevels.length; i++) {
            lo = Math.min(lo, minLevels[i]);
            hi = Math.max(hi, maxLevels[i]);
        }
        return new int[] {lo, hi};
    }

    /**
     * AmbientStreamManager.checkHaveElectricity, re-implemented: for each player's chunk map, the same tiles and the
     * same per-object call order as stock, with z limited to the level range of the loaded chunks (stock's -32..31
     * clamps the range). getGridSquare is null outside a chunk's levels, so the skipped levels never had an object.
     */
    static void checkHaveElectricity() {
        int players = IsoPlayer.numPlayers;
        IsoCell cell = IsoWorld.instance.currentCell;
        int width = IsoChunkMap.chunkGridWidth;
        for (int p = 0; p < players; p++) {
            IsoChunkMap map = cell.chunkMap[p];
            if (map.ignore) continue;
            int[] mins = new int[width * width];
            int[] maxs = new int[width * width];
            int n = 0;
            for (int y = 0; y < width; y++) {
                for (int x = 0; x < width; x++) {
                    IsoChunk c = map.getChunk(x, y);
                    if (c == null) continue;
                    mins[n] = c.getMinLevel();
                    maxs[n] = c.getMaxLevel();
                    n++;
                }
            }
            int[] range = levelRange(Arrays.copyOf(mins, n), Arrays.copyOf(maxs, n));
            if (range == null) continue;
            int zLo = Math.max(range[0], -32), zHi = Math.min(range[1], 31);
            for (int z = zLo; z <= zHi; z++) {
                for (int y = map.getWorldYMinTiles(); y <= map.getWorldYMaxTiles(); y++) {
                    for (int x = map.getWorldXMinTiles(); x <= map.getWorldXMaxTiles(); x++) {
                        IsoGridSquare sq = cell.getGridSquare(x, y, z);
                        if (sq == null) continue;
                        for (int i = 0; i < sq.getObjects().size(); i++) {
                            IsoObject o = sq.getObjects().get(i);
                            o.checkHaveElectricity();
                        }
                    }
                }
            }
        }
    }

    // fliesToggleFix: during ChunkLevelData.update, a clear of one square is deferred; the same square's set is a no-op

    private static final class FliesState {
        boolean inUpdate;
        Object pending;
    }

    private static final ThreadLocal<FliesState> fliesState = ThreadLocal.withInitial(FliesState::new);

    static void fliesEnter() {
        FliesState s = fliesState.get();
        s.inUpdate = true;
        s.pending = null;
    }

    /** True skips the call (a deferred clear). Outside an update, or when the clear would be a second one, false. */
    static boolean fliesSet(Object square, boolean value) {
        FliesState s = fliesState.get();
        if (!s.inUpdate) return false;
        if (!value) {
            if (s.pending == square) return true;
            if (s.pending != null) return false;   // one deferred clear per update: this one runs as stock
            s.pending = square;
            return true;
        }
        if (s.pending == square) s.pending = null; // set on the square it would have cleared: nothing to do
        return false;
    }

    /** The square whose clear was deferred, to clear for real now; null when none. Ends the update. */
    static Object fliesExit() {
        FliesState s = fliesState.get();
        Object p = s.pending;
        s.inUpdate = false;
        s.pending = null;
        return p;
    }

    /** Runs the deferred clear of the square held by fliesExit, outside the update, so it runs as stock. */
    static void clearPendingFlies() {
        Object sq = fliesExit();
        if (sq != null) ((IsoGridSquare) sq).setHasFlies(false);
    }

    // fmodNullHandleFix: a 0 event handle becomes -1 only while FMODSoundEmitter.addSound runs on this thread

    private static final ThreadLocal<int[]> addSoundLevel = ThreadLocal.withInitial(() -> new int[1]);

    static void addSoundEnter() { addSoundLevel.get()[0]++; }

    static void addSoundExit() {
        int[] d = addSoundLevel.get();
        if (d[0] > 0) d[0]--;
    }

    static int addSoundDepth() { return addSoundLevel.get()[0]; }

    /** A NULL handle from CreateEventInstance becomes -1 (an error value the addSound caller already rejects). */
    static long nullHandle(long handle, int addSoundDepth) {
        return handle == 0 && addSoundDepth > 0 ? -1 : handle;
    }

    /** Test hook: a fresh state. */
    static void resetForTest() {
        synchronized (SoundFixes.class) {
            zoneCache.clear();
        }
        zoneVersion = 0;
        FliesState s = fliesState.get();
        s.inUpdate = false;
        s.pending = null;
        addSoundLevel.get()[0] = 0;
    }

    /** corpseFliesCap: lowers the stock corpse-count limit once, at init. */
    static void applyCorpseCap() {
        CorpseCount.maxCorpseCount = Math.min(CorpseCount.maxCorpseCount, 12);
    }

    static void init() {
        SoundPatches.zoneGuard.on = Settings.flag("soundZoneCache");
        SoundPatches.electricityGuard.on = Settings.flag("electricityLevelRange");
        SoundPatches.fliesGuard.on = Settings.flag("fliesToggleFix");
        SoundPatches.nullGuard.on = Settings.flag("fmodNullHandleFix");
        if (Settings.flag("corpseFliesCap")) {
            try { applyCorpseCap(); }
            catch (Throwable t) { System.out.println("[PZButter] corpseFliesCap not applied: " + t); }
        }
    }
}
