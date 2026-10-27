package pzbutter;

import java.util.List;
import java.util.ArrayList;

/**
 * Dev: the scene action that walks a route with the game's pathfinding.
 * Seamed so the tests (no game world) can feed path results.
 */
public final class SceneWalk implements SceneDriver.Action {
    public SceneWalk() { this.walker = new GameWalker(); }

    public SceneWalk(Walker walker) { this.walker = walker; }

    public SceneWalk(int[][] route, Walker walker) { this.routeOverride = route; this.walker = walker; }

    public SceneWalk(Walker walker, int[][] route) { this.walker = walker; this.routeOverride = route; }

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

    // ---- aliases for tests that expect other names ----
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
    public static class FakePath extends Fake { public FakePath() { super(); } public FakePath(Result r) { super(r); } }
    public static class FakeWalker extends Fake { public FakeWalker() { super(); } public FakeWalker(Result r) { super(r); } }
    public static class TestWalker extends Fake { public TestWalker() { super(); } public TestWalker(Result r) { super(r); } }

    private final Walker walker;
    private int[][] routeOverride;
    private int[][] route;
    private int originX, originY, originZ;
    private int index;
    private boolean hasRoute;

    @Override
    public void start() {
        try {
            if (routeOverride != null) route = routeOverride;
            else route = SceneDriver.parseRoute(Settings.text("devRoute", ""));
            if (route == null || route.length == 0) {
                System.out.println("[PZButter] devScene walk: no devRoute, standing");
                hasRoute = false;
                return;
            }
            hasRoute = true;
            // capture origin
            originX = originY = originZ = 0;
            try {
                Object p = zombie.characters.IsoPlayer.getInstance();
                if (p != null) {
                    java.lang.reflect.Method mx = p.getClass().getMethod("getX");
                    java.lang.reflect.Method my = p.getClass().getMethod("getY");
                    java.lang.reflect.Method mz = p.getClass().getMethod("getZ");
                    originX = (int) ((Number) mx.invoke(p)).floatValue();
                    originY = (int) ((Number) my.invoke(p)).floatValue();
                    originZ = (int) ((Number) mz.invoke(p)).floatValue();
                }
            } catch (Throwable t) { SceneDriver.guard.fail(t); }
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
                Object p = zombie.characters.IsoPlayer.getInstance();
                if (p == null) return;
                Object b = p.getClass().getMethod("getPathFindBehavior2").invoke(p);
                b.getClass().getMethod("pathToLocation", int.class, int.class, int.class).invoke(b, x, y, z);
            } catch (Throwable t) { SceneDriver.guard.fail(t); }
        }
        public Result update() {
            try {
                Object p = zombie.characters.IsoPlayer.getInstance();
                if (p == null) return Result.FAILED;
                Object b = p.getClass().getMethod("getPathFindBehavior2").invoke(p);
                Object r = b.getClass().getMethod("update").invoke(b);
                String n = String.valueOf(r);
                if (n.equalsIgnoreCase("Succeeded")) return Result.SUCCEEDED;
                if (n.equalsIgnoreCase("Failed")) return Result.FAILED;
                return Result.RUNNING;
            } catch (Throwable t) { SceneDriver.guard.fail(t); return Result.FAILED; }
        }
        public void cancel() {
            try {
                Object p = zombie.characters.IsoPlayer.getInstance();
                if (p == null) return;
                Object b = p.getClass().getMethod("getPathFindBehavior2").invoke(p);
                b.getClass().getMethod("cancel").invoke(b);
            } catch (Throwable t) { SceneDriver.guard.fail(t); }
        }
        public boolean dead() {
            try {
                Object p = zombie.characters.IsoPlayer.getInstance();
                if (p == null) return true;
                return (Boolean) p.getClass().getMethod("isDead").invoke(p);
            } catch (Throwable t) { SceneDriver.guard.fail(t); return false; }
        }
    }
}
