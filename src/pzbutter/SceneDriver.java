package pzbutter;

import java.io.File;
import java.nio.file.Path;

/**
 * Dev: scene clock (stand/walk/drive) and its window mark; plus the auto-continue decision.
 * Off by default; gated on Settings.enabled and devScene != off.
 */
public final class SceneDriver {
    private SceneDriver() {}

    public static final Guard guard = new Guard("devScene");
    public static final Guard autoGuard = new Guard("devAutoContinue");

    public enum Phase { IDLE, SETTLE, WINDOW, DONE }

    /** The action a concrete scene performs; assigned by the scene tasks. */
    public interface Action {
        default void prepare() { }
        /** Each settle frame after prepare(). */
        default void settleFrame(long nowMs) { }
        void start();
        void frame(long nowMs);
        void stop();
    }

    /** Replaceable quit seam (tests override). */
    public static volatile Runnable quit;

    /** Replaceable marks file seam (tests override). */
    public static volatile Path marksFile;

    public static volatile Action action;

    public static volatile boolean devSceneQuit;

    /** Spawn seam for devPopulation (tests override). */
    public interface Spawner {
        boolean spawn(int x, int y, int z, int count);
        /** The ring's centre {x, y, z}, or null with no player. Tests keep the default. */
        default int[] centre() { return new int[] {0, 0, 0}; }
    }

    public static final class GameSpawner implements Spawner {
        public int[] centre() {
            zombie.characters.IsoPlayer p = zombie.characters.IsoPlayer.getInstance();
            if (p == null) return null;
            return new int[] {(int) Math.floor(p.getX()), (int) Math.floor(p.getY()), (int) Math.floor(p.getZ())};
        }
        public boolean spawn(int x, int y, int z, int count) {
            try {
                java.util.ArrayList<?> spawned = zombie.Lua.LuaManager.GlobalObject.addZombiesInOutfit(x, y, z, count, null, 50);
                int n = spawned == null ? count : spawned.size();
                System.out.println("[PZButter] devPopulation: spawned " + n + " at " + x + "," + y + "," + z);
                return true;
            } catch (Throwable t) { guard.fail(t); return false; }
        }
    }

    /** Replaceable spawn seams (tests override any one of them). */
    public static volatile Spawner spawner;
    public static volatile int populationSpawnCount;

    private static volatile Phase phase = Phase.IDLE;
    private static volatile boolean worldReady;
    private static volatile boolean sceneSet;
    private static volatile boolean populationSpawned;
    private static volatile boolean prepared;
    private static volatile long readyMs;
    private static volatile long quitAtMs;
    private static int settleSec = 20;
    private static int sceneSec = 60;
    private static volatile boolean autoContinue;
    private static volatile boolean autoContinued;

    static void init() {
        guard.on = Settings.enabled && !"off".equalsIgnoreCase(Settings.text("devScene", "off"));
        sceneSet = guard.on;
        devSceneQuit = Settings.flag("devSceneQuit");
        settleSec = Settings.num("devSceneSettleSec", 20, 0, 86400);
        sceneSec = Math.max(1, Settings.num("devSceneSec", 60, 0, 86400));
        autoContinue = Settings.flag("devAutoContinue");
        autoGuard.on = autoContinue;
        if (autoContinue) System.out.println("[PZButter] devAutoContinue: presses Continue and Click to Start once");
        if (!guard.on) return;
        System.out.println("[PZButter] devScene: " + Settings.text("devScene", "off") + " settle=" + settleSec + " scene=" + sceneSec);
        String scene = Settings.text("devScene", "off");
        if ("walk".equalsIgnoreCase(scene)) action = new SceneWalk();
        else if ("drive".equalsIgnoreCase(scene)) action = new SceneDrive();
    }

    public static Phase phase() { return phase; }
    public static boolean sceneSet() { return sceneSet; }
    public static boolean autoContinue() { return autoContinue; }

    private static final java.lang.invoke.VarHandle FORCE_DONE_VH =
        Game.field(zombie.gameStates.GameLoadingState.class, "forceDone", boolean.class);

    public static void clickToStart(Object self) {
        if (!autoContinue) return;
        if (FORCE_DONE_VH == null) return;
        try { FORCE_DONE_VH.set(self, true); } catch (Throwable t) { autoGuard.fail(t); }
    }

    public static void resetForTest() {
        guard.on = false;
        autoGuard.on = false;
        phase = Phase.IDLE;
        worldReady = false;
        sceneSet = false;
        populationSpawned = false;
        prepared = false;
        readyMs = 0;
        quitAtMs = 0;
        autoContinued = false;
        action = null;
        marksFile = null;
        spawner = null;
        populationSpawnCount = 0;
        quit = null;
        settleSec = 20;
        sceneSec = 60;
        devSceneQuit = false;
        autoContinue = false;
    }

    public static Phase phaseAt(long sinceReadyMs, int settleSec, int sceneSec) {
        long settleMs = (long) settleSec * 1000L;
        long endMs = settleMs + (long) sceneSec * 1000L;
        if (sinceReadyMs < settleMs) return Phase.SETTLE;
        if (sinceReadyMs < endMs) return Phase.WINDOW;
        return Phase.DONE;
    }

    public static void onWorldReady() {
        DevAudit.report("world");
        try {
            if (action != null && phase != Phase.IDLE && phase != Phase.DONE) {
                try { action.stop(); } catch (Throwable t) { guard.fail(t); }
            }
            try { VirtualKeys.releaseAll(); } catch (Throwable t) { guard.fail(t); }
            phase = Phase.IDLE;
            worldReady = true;
            populationSpawned = false;
            prepared = false;
            readyMs = 0;
            quitAtMs = 0;
        } catch (Throwable t) { guard.fail(t); }
    }

    public static void onWorldExit() {
        try {
            if (sceneSet && worldReady && phase != Phase.IDLE && phase != Phase.DONE) end("left world");
            worldReady = false;
        } catch (Throwable t) { guard.fail(t); }
    }

    private static void prepareAction(long nowMs) {
        prepared = true;
        if (action == null) return;
        try { action.prepare(); } catch (Throwable t) { guard.fail(t); end("prepare failed", nowMs); }
    }

    public static void tick(long nowMs) {
        if (!sceneSet) return;
        if (!worldReady) return;
        if (Thread.currentThread() != FrameClock.mainThread) return;
        if (!guard.on) {
            if (phase == Phase.SETTLE || phase == Phase.WINDOW) end("devScene off after failures", nowMs);
            fireQuitIfNeeded(nowMs);
            return;
        }
        try {
            if (readyMs == 0) readyMs = nowMs;
            long since = nowMs - readyMs;
            if (since < 0) since = 0;
            Phase next = phaseAt(since, settleSec, sceneSec);
            if (next == phase) {
                // the action prepares halfway through the settle: a car spawned on the first in-game frame
                // lands in a chunk the game drops a moment later (BaseVehicle.update: chunk.refs empty)
                if (phase == Phase.SETTLE && !prepared && since >= (long) settleSec * 500L) {
                    prepareAction(nowMs);
                    if (phase == Phase.DONE) { fireQuitIfNeeded(nowMs); return; }
                } else if (phase == Phase.SETTLE && prepared && action != null) {
                    try { action.settleFrame(nowMs); } catch (Throwable t) { guard.fail(t); }
                }
                if (phase == Phase.WINDOW && action != null) {
                    try { action.frame(nowMs); } catch (Throwable t) { guard.fail(t); }
                }
                fireQuitIfNeeded(nowMs);
                return;
            }
            // advancing
            if (phase == Phase.DONE) {
                fireQuitIfNeeded(nowMs);
                return;
            }
            if (phase == Phase.IDLE && next == Phase.SETTLE) {
                phase = Phase.SETTLE;
                try { spawnPopulationIfNeeded(); } catch (Throwable t) { guard.fail(t); }
                fireQuitIfNeeded(nowMs);
                return;
            }
            if (next == Phase.WINDOW) {
                if (phase == Phase.IDLE) {
                    try { spawnPopulationIfNeeded(); } catch (Throwable t) { guard.fail(t); }
                    if (phase == Phase.DONE) { fireQuitIfNeeded(nowMs); return; }
                }
                if (!prepared) {
                    prepareAction(nowMs);
                    if (phase == Phase.DONE) { fireQuitIfNeeded(nowMs); return; }
                }
                phase = Phase.WINDOW;
                Path f = marksFileForWrite();
                DevMarks.write(f, DevMarks.line("scene.start", nowMs, null));
                if (action != null) try { action.start(); } catch (Throwable t) { guard.fail(t); }
                fireQuitIfNeeded(nowMs);
                return;
            }
            if (next == Phase.DONE) {
                phase = Phase.DONE;
                if (action != null) try { action.stop(); } catch (Throwable t) { guard.fail(t); }
                try { VirtualKeys.releaseAll(); } catch (Throwable t) { guard.fail(t); }
                Path f = marksFileForWrite();
                DevMarks.write(f, DevMarks.line("scene.end", nowMs, null));
                scheduleQuit(nowMs);
                fireQuitIfNeeded(nowMs);
                return;
            }
            phase = next;
            fireQuitIfNeeded(nowMs);
        } catch (Throwable t) { guard.fail(t); }
    }

    public static int[][] parseRoute(String s) {
        if (s == null) s = "";
        String t = s.trim();
        if (t.isEmpty()) return new int[0][];
        String[] parts = t.split(";", -1);
        java.util.List<int[]> out = new java.util.ArrayList<>();
        for (String part : parts) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            String[] nums = p.split(",", -1);
            if (nums.length != 2) {
                System.out.println("[PZButter] devScene walk: bad devRoute: " + s);
                return null;
            }
            try {
                int x = Integer.parseInt(nums[0].trim());
                int y = Integer.parseInt(nums[1].trim());
                out.add(new int[] {x, y});
            } catch (NumberFormatException e) {
                System.out.println("[PZButter] devScene walk: bad devRoute: " + s);
                return null;
            }
        }
        if (out.isEmpty()) return new int[0][];
        return out.toArray(new int[0][]);
    }

    private static Spawner resolveSpawner() {
        if (spawner != null) return spawner;
        return new GameSpawner();
    }

    static void spawnPopulationIfNeeded() {
        if (populationSpawned) return;
        populationSpawned = true;
        int count = Settings.num("devPopulation", 0, 0, 1000000);
        if (count <= 0) return;
        Spawner s = resolveSpawner();
        int[] c; // centre = the player's square
        try { c = s.centre(); } catch (Throwable t) { guard.fail(t); return; }
        if (c == null) { System.out.println("[PZButter] devPopulation: no player, nothing spawned"); return; }
        int cz = c[2];
        int[][] pts = ringPoints(count, 10, 16, c[0], c[1]);
        boolean failed = false;
        for (int[] pt : pts) {
            try {
                boolean ok = s.spawn(pt[0], pt[1], cz, pt[2]);
                if (!ok) { guard.fail(new RuntimeException("devPopulation spawn failed")); failed = true; break; }
                populationSpawnCount += pt[2];
            } catch (Throwable t) { guard.fail(t); failed = true; break; }
        }
        if (failed) { SceneDriver.end("population spawn failed"); return; }
        System.out.println("[PZButter] devPopulation: spawned " + populationSpawnCount + " zombies");
    }

    public static int[][] ringPoints(int count, int perPoint, int radius, int cx, int cy) {
        if (count <= 0 || perPoint <= 0) return new int[0][];
        int n = (count + perPoint - 1) / perPoint;
        int[][] out = new int[n][3];
        for (int i = 0; i < n; i++) {
            double angle = (Math.PI * 2.0 * i) / n;
            int x = cx + (int) Math.round((double) radius * Math.cos(angle));
            int y = cy + (int) Math.round((double) radius * Math.sin(angle));
            int batch = (i == n - 1) ? (count - i * perPoint) : perPoint;
            out[i][0] = x;
            out[i][1] = y;
            out[i][2] = batch;
        }
        return out;
    }

    public static void end(String reason) {
        end(reason, System.currentTimeMillis());
    }


    /** Package-private seam: one timestamp drives both the scene.end mark and the quit base. */
    static void end(String reason, long nowMs) {
        if (phase == Phase.DONE) return;
        try {
            if (action != null && phase == Phase.WINDOW) try { action.stop(); } catch (Throwable t) { guard.fail(t); }
            try { VirtualKeys.releaseAll(); } catch (Throwable t) { guard.fail(t); }
            phase = Phase.DONE;
            Path f = marksFileForWrite();
            DevMarks.write(f, DevMarks.line("scene.end", nowMs, "failed: " + reason));
            scheduleQuit(nowMs);
        } catch (Throwable t) { guard.fail(t); }
    }

    public static boolean shouldContinue(boolean menuBuilt, boolean firedBefore) {
        return menuBuilt && !firedBefore;
    }

    /**
     * Called every frame from ZombiePatches.onFrameEnd (GameWindow.frameStep, menu included). A hook on
     * MainScreenState.update never installs: that class is the game's entry point and loads before ZombieBuddy patches.
     */
    public static void autoContinueTick() {
        if (!autoContinue) return;
        if (autoContinued) return;
        if (!autoGuard.on) return;
        if (Thread.currentThread() != FrameClock.mainThread) return;
        try {
            Object ms = zombie.Lua.LuaManager.env.rawget("MainScreen");
            se.krka.kahlua.vm.KahluaTable t = ms instanceof se.krka.kahlua.vm.KahluaTable k ? k : null;
            boolean menuBuilt = t != null && t.rawget("instance") != null;
            if (!shouldContinue(menuBuilt, autoContinued)) return;
            Object inst = menuBuilt ? t.rawget("instance") : null;
            if (inst instanceof se.krka.kahlua.vm.KahluaTable it) {
                Object d = it.rawget("delay");
                if (d instanceof Number n && n.doubleValue() > 0) return;
            }
            Object fn = t.rawget("continueLatestSave");
            Object mode = t.rawget("latestSaveGameMode");
            Object world = t.rawget("latestSaveWorld");
            if (mode == null || world == null) {
                System.out.println("[PZButter] devAutoContinue: no latest save");
                autoContinued = true;
                return;
            }
            if (fn == null) {
                System.out.println("[PZButter] devAutoContinue off: MainScreen.continueLatestSave not found");
                autoContinued = true;
                return;
            }
            Object[] r = zombie.Lua.LuaManager.caller.pcall(zombie.Lua.LuaManager.thread, fn, mode, world);
            if (r == null || r.length == 0 || !Boolean.TRUE.equals(r[0]))
                System.out.println("[PZButter] devAutoContinue: continueLatestSave failed: " + (r != null && r.length > 1 ? r[1] : "no result"));
            autoContinued = true;
        } catch (Throwable t) { autoGuard.fail(t); }
    }

    private static Path marksFileForWrite() {
        Path p = marksFile;
        if (p == null) p = DevMarks.defaultMarksFile();
        return p;
    }

    private static void scheduleQuit(long eventNowMs) {
        if (!devSceneQuit) return;
        quitAtMs = eventNowMs + 3000L;
    }

    private static void fireQuitIfNeeded(long nowMs) {
        if (quitAtMs == 0) return;
        if (nowMs < quitAtMs) return;
        quitAtMs = 0;
        try { VirtualKeys.releaseAll(); } catch (Throwable t) { guard.fail(t); }
        Runnable q = resolveQuit();
        try { q.run(); } catch (Throwable t) { guard.fail(t); }
    }

    private static Runnable resolveQuit() {
        if (quit != null) return quit;
        return () -> zombie.core.Core.getInstance().quitToDesktop();
    }
}
