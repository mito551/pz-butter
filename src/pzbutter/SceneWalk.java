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
    private int fails;

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
                if (r == Result.FAILED) {
                    fails++;
                    System.out.printf(java.util.Locale.ROOT, "[PZButter] devScene walk: path failed at %.1f,%.1f, leg %d/%d to %d,%d (%d in a row)%n",
                        walker.x(), walker.y(), index + 1, route.length, originX + route[index][0], originY + route[index][1], fails);
                    if (fails >= MAX_FAILS) { SceneDriver.end("path failed"); return; }
                } else fails = 0;
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
        int[] h = Double.isNaN(x) ? new int[] {tx, ty} : hop((int) Math.floor(x), (int) Math.floor(y), tx, ty, HOP_TILES);
        finalHop = h[0] == tx && h[1] == ty;
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
            System.out.printf(java.util.Locale.ROOT, "[PZButter] devScene walk: stuck at %.1f,%.1f, leg %d/%d to %d,%d, moved %.2f tiles in %d ms%n",
                x, y, index + 1, route.length, originX + route[index][0], originY + route[index][1], moved, gap);
            SceneDriver.end("stuck");
            return;
        }
        stuckX = x; stuckY = y; stuckMs = nowMs;
    }

    @Override
    public void stop() {
        try { walker.cancel(); } catch (Throwable t) { SceneDriver.guard.fail(t); }
    }

    private static final class GameWalker implements Walker {
        public void pathTo(int x, int y, int z) {
            try {
                zombie.characters.IsoPlayer p = zombie.characters.IsoPlayer.getInstance();
                if (p == null) return;
                zombie.pathfind.PathFindBehavior2 b = p.getPathFindBehavior2();
                if (b == null) return;
                b.pathToLocation(x, y, z);
            } catch (Throwable t) { SceneDriver.guard.fail(t); }
        }
        public Result update() {
            try {
                zombie.characters.IsoPlayer p = zombie.characters.IsoPlayer.getInstance();
                if (p == null) return Result.FAILED;
                zombie.pathfind.PathFindBehavior2 b = p.getPathFindBehavior2();
                if (b == null) return Result.FAILED;
                zombie.pathfind.PathFindBehavior2.BehaviorResult r = b.update();
                if (r == zombie.pathfind.PathFindBehavior2.BehaviorResult.Succeeded) return Result.SUCCEEDED;
                if (r == zombie.pathfind.PathFindBehavior2.BehaviorResult.Failed) return Result.FAILED;
                return Result.RUNNING;
            } catch (Throwable t) { SceneDriver.guard.fail(t); return Result.FAILED; }
        }
        public void cancel() {
            try {
                zombie.characters.IsoPlayer p = zombie.characters.IsoPlayer.getInstance();
                if (p == null) return;
                zombie.pathfind.PathFindBehavior2 b = p.getPathFindBehavior2();
                if (b == null) return;
                b.cancel();
            } catch (Throwable t) { SceneDriver.guard.fail(t); }
        }
        public boolean dead() {
            try {
                zombie.characters.IsoPlayer p = zombie.characters.IsoPlayer.getInstance();
                if (p == null) return true;
                return p.isDead();
            } catch (Throwable t) { SceneDriver.guard.fail(t); return false; }
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
