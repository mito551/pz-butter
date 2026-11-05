package pzbutter;


/**
 * Dev: the scene action that walks a route with the game's pathfinding.
 * Seamed so the tests (no game world) can feed path results.
 */
public final class SceneWalk implements SceneDriver.Action {
    public SceneWalk() { this.walker = new GameWalker(); }

    public SceneWalk(Walker walker) { this.walker = walker; }

    public enum Result { RUNNING, SUCCEEDED, FAILED }

    public interface Walker {
        void pathTo(int x, int y, int z);
        Result update();
        void cancel();
        boolean dead();
        double x(); // current player x, NaN when unavailable
        double y();
        /** Diagnostics for a failed path: the player's and the target's squares. */
        default String where(int x, int y, int z) { return ""; }
        /** Diagnostics for a stuck walk: what the player and its path are doing. */
        default String state() { return ""; }
    }

    static int next(int index, int size) {
        if (size <= 0) return 0;
        int n = index + 1;
        if (n >= size) n = 0;
        return n;
    }

    static final long STUCK_WINDOW_MS = 3_000;
    /** The pathfinder answers asynchronously; the first seconds of a leg are not movement time. */
    static final long STUCK_GRACE_MS = 10_000;

    /** Stuck: less than one tile moved over a window of STUCK_WINDOW_MS. */
    static boolean stuck(double movedTiles, long windowMs) {
        return windowMs >= STUCK_WINDOW_MS && movedTiles < 1.0;
    }

    public static int[][] parseRoute(String s) { return SceneDriver.parseRoute(s); }

    /** Longest single pathfinder request: far targets lie outside the loaded chunks and fail. */
    static final int HOP_TILES = 30;
    /** Path failures in a row that end the scene (one failure skips to the next waypoint). */
    static final int MAX_FAILS = 3;

    /** An ended walk-to succeeded: at the target, or at least 2 tiles moved from where it began. */
    static boolean arrived(double x, double y, int tx, int ty, double fromX, double fromY) {
        return Math.hypot(x - (tx + 0.5), y - (ty + 0.5)) <= 1.5 || Math.hypot(x - fromX, y - fromY) >= 2.0;
    }

    /** The next request toward (tx,ty) from (px,py): the target itself, or a point maxTiles along the line. */
    static int[] hop(int px, int py, int tx, int ty, int maxTiles) {
        double d = Math.hypot(tx - px, ty - py);
        if (d <= maxTiles) return new int[] {tx, ty};
        double f = maxTiles / d;
        return new int[] {px + (int) Math.round((tx - px) * f), py + (int) Math.round((ty - py) * f)};
    }

    public static class Fake implements Walker {
        private final Result first;
        public Fake() { this.first = Result.FAILED; }
        public Fake(Result first) { this.first = first; }
        private boolean once = false;
        public double x, y;
        public int lastX, lastY;
        public Result lastUpdate() { return first; }
        public void pathTo(int x, int y, int z) { lastX = x; lastY = y; once = false; }
        public Result update() {
            if (!once) { once = true; return first; }
            return Result.RUNNING;
        }
        public void cancel() { }
        public boolean dead() { return false; }
        public double x() { return x; }
        public double y() { return y; }
    }

    private final Walker walker;
    private int[][] route;
    private int originX, originY, originZ;
    private int index;
    private boolean hasRoute;
    private double stuckX = Double.NaN, stuckY;
    private long stuckMs = -1;
    private long legMs = -1; // when the current leg's pathTo was issued (-1: at the next frame)
    private boolean finalHop; // the request in flight ends at the waypoint itself
    private int lastX, lastY; // the request in flight
    private int fails;
    private int hopLen = HOP_TILES; // halves after a failed far request

    @Override
    public void start() {
        try {
            route = SceneDriver.parseRoute(Settings.text("devRoute", ""));
            if (route == null || route.length == 0) {
                System.out.println("[PZButter] devScene walk: no devRoute, standing");
                hasRoute = false;
                return;
            }
            hasRoute = true;
            originX = originY = originZ = 0;
            zombie.characters.IsoPlayer p = zombie.characters.IsoPlayer.getInstance();
            if (p != null) {
                originX = (int) Math.floor(p.getX());
                originY = (int) Math.floor(p.getY());
                originZ = (int) Math.floor(p.getZ());
            }
            index = 0;
            stuckX = Double.NaN;
            stuckMs = -1;
            legMs = -1;
            fails = 0;
            hopLen = HOP_TILES;
            request();
        } catch (Throwable t) { SceneDriver.guard.fail(t); }
    }

    @Override
    public void frame(long nowMs) {
        if (!hasRoute) return;
        try {
            if (walker.dead()) { SceneDriver.end("player dead"); return; }
            Result r = walker.update();
            if (r == Result.SUCCEEDED || r == Result.FAILED) {
                if (r == Result.FAILED && !finalHop && hopLen > 4) {
                    hopLen /= 2;   // an obstacle or an unreachable square on the line: try closer first
                    legMs = nowMs;
                    stuckX = Double.NaN;
                    request();
                    return;
                }
                if (r == Result.FAILED) {
                    fails++;
                    System.out.println(String.format(java.util.Locale.ROOT, "[PZButter] devScene walk: path failed at %.1f,%.1f to %d,%d, leg %d/%d to %d,%d (%d in a row)%s",
                        walker.x(), walker.y(), lastX, lastY, index + 1, route.length, originX + route[index][0], originY + route[index][1], fails,
                        walker.where(lastX, lastY, originZ)));
                    if (fails >= MAX_FAILS) { SceneDriver.end("path failed"); return; }
                } else fails = 0;
                hopLen = HOP_TILES;
                if (finalHop || r == Result.FAILED) index = next(index, route.length);
                legMs = nowMs;
                stuckX = Double.NaN;
                request();
            }
            checkStuck(nowMs);
        } catch (Throwable t) { SceneDriver.guard.fail(t); }
    }

    private void request() {
        int tx = originX + route[index][0], ty = originY + route[index][1];
        double x = walker.x(), y = walker.y();
        int[] h = Double.isNaN(x) ? new int[] {tx, ty} : hop((int) Math.floor(x), (int) Math.floor(y), tx, ty, hopLen);
        finalHop = h[0] == tx && h[1] == ty;
        lastX = h[0]; lastY = h[1];
        walker.pathTo(h[0], h[1], originZ);
    }

    private void checkStuck(long nowMs) {
        double x = walker.x(), y = walker.y();
        if (legMs < 0) legMs = nowMs;
        if (nowMs - legMs < STUCK_GRACE_MS) return;
        if (Double.isNaN(stuckX)) { stuckX = x; stuckY = y; stuckMs = nowMs; return; }
        long gap = nowMs - stuckMs;
        if (gap < STUCK_WINDOW_MS) return;
        double moved = Math.hypot(x - stuckX, y - stuckY);
        if (stuck(moved, gap)) {
            System.out.println(String.format(java.util.Locale.ROOT, "[PZButter] devScene walk: stuck at %.1f,%.1f, leg %d/%d to %d,%d, moved %.2f tiles in %d ms",
                x, y, index + 1, route.length, originX + route[index][0], originY + route[index][1], moved, gap)
                + walker.state() + walker.where(lastX, lastY, originZ));
            SceneDriver.end("stuck");
            return;
        }
        stuckX = x; stuckY = y; stuckMs = nowMs;
    }

    @Override
    public void stop() {
        try { walker.cancel(); } catch (Throwable t) { SceneDriver.guard.fail(t); }
    }

    /**
     * The game's own walk-to: ISPathFindAction (the timed action click-to-walk uses), so the path is followed inside the
     * player's update. Calling PathFindBehavior2.update from a frame hook instead animates the walk without moving the
     * player (it walks on the spot until the pathfinder gives up).
     */
    private static final class GameWalker implements Walker {
        private int tx, ty;
        private double fromX, fromY;
        private long issuedMs;
        private boolean started;

        private static Object lua(String table, String fn, Object... args) {
            Object f = zombie.Lua.LuaManager.env.rawget(table == null ? fn : table);
            if (table != null) f = f instanceof se.krka.kahlua.vm.KahluaTable t ? t.rawget(fn) : null;
            if (f == null) throw new IllegalStateException((table == null ? "" : table + ".") + fn + " not found");
            Object[] r = zombie.Lua.LuaManager.caller.pcall(zombie.Lua.LuaManager.thread, f, args);
            if (r == null || r.length == 0 || !Boolean.TRUE.equals(r[0]))
                throw new IllegalStateException(fn + " failed: " + (r != null && r.length > 1 ? r[1] : "no result"));
            return r.length > 1 ? r[1] : null;
        }

        public void pathTo(int x, int y, int z) {
            try {
                zombie.characters.IsoPlayer p = zombie.characters.IsoPlayer.getInstance();
                if (p == null) return;
                lua("ISTimedActionQueue", "clear", p);
                lua(null, "ISPathFindAction_pathToLocationF", x + 0.5, y + 0.5, (double) z);
                tx = x; ty = y; fromX = p.getX(); fromY = p.getY(); issuedMs = System.currentTimeMillis(); started = false;
            } catch (Throwable t) { SceneDriver.guard.fail(t); }
        }
        public Result update() {
            try {
                zombie.characters.IsoPlayer p = zombie.characters.IsoPlayer.getInstance();
                if (p == null) return Result.FAILED;
                if (!p.getCharacterActions().isEmpty()) { started = true; return Result.RUNNING; }
                if (!started && System.currentTimeMillis() - issuedMs < 2_000) return Result.RUNNING; // queued, not begun yet
                // the action ended. The path stops where the reachable part ends (a fence, a closed building): any real
                // progress counts, and the next hop starts from there
                return arrived(p.getX(), p.getY(), tx, ty, fromX, fromY) ? Result.SUCCEEDED : Result.FAILED;
            } catch (Throwable t) { SceneDriver.guard.fail(t); return Result.FAILED; }
        }
        public void cancel() {
            try {
                zombie.characters.IsoPlayer p = zombie.characters.IsoPlayer.getInstance();
                if (p == null) return;
                lua("ISTimedActionQueue", "clear", p);
            } catch (Throwable t) { SceneDriver.guard.fail(t); }
        }
        public boolean dead() {
            try {
                zombie.characters.IsoPlayer p = zombie.characters.IsoPlayer.getInstance();
                if (p == null) return true;
                return p.isDead();
            } catch (Throwable t) { SceneDriver.guard.fail(t); return false; }
        }
        public String where(int x, int y, int z) {
            try {
                zombie.characters.IsoPlayer p = zombie.characters.IsoPlayer.getInstance();
                if (p == null) return "";
                return "; player " + square(p.getCurrentSquare()) + "; target " + square(p.getCell().getGridSquare(x, y, z));
            } catch (Throwable t) { return "; where: " + t; }
        }
        public String state() {
            try {
                zombie.characters.IsoPlayer p = zombie.characters.IsoPlayer.getInstance();
                if (p == null) return "; no player";
                java.util.List<?> q = p.getCharacterActions();
                zombie.pathfind.PathFindBehavior2 pf = p.getPathFindBehavior2();
                zombie.ui.SpeedControls sc = zombie.ui.UIManager.getSpeedControls();
                return "; actions " + q.size() + (q.isEmpty() ? "" : " (first " + q.get(0).getClass().getSimpleName() + ")")
                    + "; state " + (p.getCurrentState() == null ? "none" : p.getCurrentState().getClass().getSimpleName())
                    + (p.getVehicle() != null ? "; in vehicle" : "") + (p.isAsleep() ? "; asleep" : "")
                    + (p.isSitOnGround() ? "; sitting" : "") + (p.isBlockMovement() ? "; movement blocked" : "")
                    + (p.isGhostMode() ? "; ghost" : "")
                    + "; path " + (pf == null ? "none" : (pf.isGoalLocation() ? "location " + pf.getTargetX() + "," + pf.getTargetY()
                        : pf.isGoalNone() ? "no goal" : "other goal") + (pf.getIsCancelled() ? ", cancelled" : ""))
                    + "; speed " + (sc == null ? "?" : sc.getCurrentGameSpeed()) + " x" + zombie.GameTime.instance.getMultiplier();
            } catch (Throwable t) { return "; state: " + t; }
        }
        private static String square(zombie.iso.IsoGridSquare q) {
            if (q == null) return "not loaded";
            zombie.iso.areas.IsoRoom r = q.getRoom();
            return (r == null ? "outside" : "room " + r.getName()) + (q.isFree(false) ? ", free" : ", not free")
                + (q.isSolid() ? ", solid" : "") + (q.TreatAsSolidFloor() ? "" : ", no floor");
        }
        public double x() {
            zombie.characters.IsoPlayer p = zombie.characters.IsoPlayer.getInstance();
            return p == null ? Double.NaN : p.getX();
        }
        public double y() {
            zombie.characters.IsoPlayer p = zombie.characters.IsoPlayer.getInstance();
            return p == null ? Double.NaN : p.getY();
        }
    }
}
