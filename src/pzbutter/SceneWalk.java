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
    }

    static int next(int index, int size) {
        if (size <= 0) return 0;
        int n = index + 1;
        if (n >= size) n = 0;
        return n;
    }

    public static int[][] parseRoute(String s) { return SceneDriver.parseRoute(s); }

    public static class Fake implements Walker {
        private final Result first;
        public Fake() { this.first = Result.FAILED; }
        public Fake(Result first) { this.first = first; }
        private boolean once = false;
        public Result lastUpdate() { return first; }
        public void pathTo(int x, int y, int z) { }
        public Result update() {
            if (!once) { once = true; return first; }
            return Result.RUNNING;
        }
        public void cancel() { }
        public boolean dead() { return false; }
    }

    private final Walker walker;
    private int[][] route;
    private int originX, originY, originZ;
    private int index;
    private boolean hasRoute;

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
            walker.pathTo(originX + route[0][0], originY + route[0][1], originZ);
        } catch (Throwable t) { SceneDriver.guard.fail(t); }
    }

    @Override
    public void frame(long nowMs) {
        if (!hasRoute) return;
        try {
            if (walker.dead()) { SceneDriver.end("player dead"); return; }
            Result r = walker.update();
            if (r == Result.SUCCEEDED) {
                index = next(index, route.length);
                walker.pathTo(originX + route[index][0], originY + route[index][1], originZ);
            } else if (r == Result.FAILED) {
                SceneDriver.end("path failed");
            }
        } catch (Throwable t) { SceneDriver.guard.fail(t); }
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
    }
}
