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

    private static volatile Phase phase = Phase.IDLE;
    private static volatile boolean worldReady;
    private static volatile long readyMs;
    private static volatile long quitAtMs;
    private static int settleSec = 20;
    private static int sceneSec = 60;
    private static volatile boolean autoContinue;
    private static volatile boolean autoContinued;

    static void init() {
        guard.on = Settings.enabled && !"off".equalsIgnoreCase(Settings.text("devScene", "off"));
        devSceneQuit = Settings.flag("devSceneQuit");
        settleSec = Settings.num("devSceneSettleSec", 20, 0, 86400);
        sceneSec = Settings.num("devSceneSec", 60, 0, 86400);
        autoContinue = Settings.flag("devAutoContinue");
        if (!guard.on) return;
        System.out.println("[PZButter] devScene: " + Settings.text("devScene", "off") + " settle=" + settleSec + " scene=" + sceneSec);
        String scene = Settings.text("devScene", "off");
        if ("walk".equalsIgnoreCase(scene)) action = new SceneWalk();

    }

    public static Phase phase() { return phase; }

    public static void resetForTest() {
        guard.on = false;
        autoGuard.on = false;
        phase = Phase.IDLE;
        worldReady = false;
        readyMs = 0;
        quitAtMs = 0;
        autoContinued = false;
        action = null;
        marksFile = null;
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
        try {
            if (action != null && phase != Phase.IDLE && phase != Phase.DONE) {
                try { action.stop(); } catch (Throwable t) { guard.fail(t); }
            }
            phase = Phase.IDLE;
            worldReady = true;
            readyMs = 0;
            quitAtMs = 0;
        } catch (Throwable t) { guard.fail(t); }
    }

    public static void tick(long nowMs) {
        if (!guard.on) return;
        if (!worldReady) return;
        if (Thread.currentThread() != FrameClock.mainThread) return;
        try {
            if (readyMs == 0) readyMs = nowMs;
            long since = nowMs - readyMs;
            if (since < 0) since = 0;
            Phase next = phaseAt(since, settleSec, sceneSec);
            if (next == phase) {
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
                fireQuitIfNeeded(nowMs);
                return;
            }
            if (next == Phase.WINDOW) {
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

    public static void end(String reason) {
        end(reason, System.currentTimeMillis());
    }


    /** Package-private seam: one timestamp drives both the scene.end mark and the quit base. */
    static void end(String reason, long nowMs) {
        if (phase == Phase.DONE) return;
        try {
            if (action != null && phase == Phase.WINDOW) try { action.stop(); } catch (Throwable t) { guard.fail(t); }
            phase = Phase.DONE;
            Path f = marksFileForWrite();
            DevMarks.write(f, DevMarks.line("scene.end", nowMs, "failed: " + reason));
            scheduleQuit(nowMs);
        } catch (Throwable t) { guard.fail(t); }
    }

    public static boolean shouldContinue(boolean menuBuilt, boolean firedBefore) {
        return menuBuilt && !firedBefore;
    }

    /** Called from DevPatches.MainScreenState update hook. */
    public static void autoContinueTick() {
        if (!autoContinue) return;
        if (autoContinued) return;
        try {
            Object ms = zombie.Lua.LuaManager.env.rawget("MainScreen");
            se.krka.kahlua.vm.KahluaTable t = ms instanceof se.krka.kahlua.vm.KahluaTable k ? k : null;
            boolean menuBuilt = t != null && t.rawget("instance") != null;
            if (!shouldContinue(menuBuilt, autoContinued)) return;
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
            zombie.Lua.LuaManager.caller.pcall(zombie.Lua.LuaManager.thread, fn, mode, world);
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
        Runnable q = resolveQuit();
        try { q.run(); } catch (Throwable t) { guard.fail(t); }
    }

    private static Runnable resolveQuit() {
        if (quit != null) return quit;
        return () -> zombie.core.Core.getInstance().quitToDesktop();
    }
}
