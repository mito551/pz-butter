package pzbutter;

import zombie.iso.LosUtil.TestResults;

/** lightVisionOnce (SPEC 1118): the memo keyed by square identity + the full
 * (dx, dy, dz, edge, diag) argument tuple, alive only inside one updateChunk
 * window. Core-only tests (stock testVisionAdjacent needs a live IsoCell). */
final class LightTest {

    static void run() throws Exception {
        keyPacking();
        windowGating();
        guardBypass();
        settingsTemplate();
    }

    private static void ok(String what, boolean cond) {
        if (!cond) {
            throw new AssertionError(what);
        }
    }

    // ---- key(): every distinct argument combo maps to a distinct key; out-of-range args map to 0 ----
    private static void keyPacking() {
        java.util.HashSet<Long> seen = new java.util.HashSet<Long>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int e = 0; e <= 1; e++) {
                        for (int d = 0; d <= 1; d++) {
                            long k = LightingPatches.key(dx, dy, dz, e == 1, d == 1);
                            ok("key in range nonzero dx=" + dx + " dy=" + dy + " dz=" + dz, k != 0L);
                            ok("distinct key dx=" + dx + " dy=" + dy + " dz=" + dz + " e=" + e + " d=" + d,
                                    seen.add(k));
                        }
                    }
                }
            }
        }
        ok("range violation dx rejected", LightingPatches.key(10001, 0, 0, true, false) == 0L);
        ok("range violation dy rejected", LightingPatches.key(0, -10001, 0, true, false) == 0L);
        ok("range violation dz rejected", LightingPatches.key(0, 0, 128, true, false) == 0L);
        ok("large but legal dx kept", LightingPatches.key(10000, 0, 0, false, true) != 0L);
        ok("large but legal dy kept", LightingPatches.key(0, 10000, 0, true, true) != 0L);
    }

    // ---- the memo only answers inside a window; the window wipes on begin and end ----
    private static void windowGating() {
        boolean wasOn = LightingPatches.guard.on;
        LightingPatches.guard.on = true;
        try {
            windowGatingInner();
        } finally {
            LightingPatches.guard.on = wasOn;
        }
    }

    private static void windowGatingInner() {
        Object sqA = new Object();
        Object sqB = new Object();
        LightingPatches.windowEnd();

        ok("inactive get declines", LightingPatches.memoGet(sqA, 1, 0, 0, true, false) == null);
        LightingPatches.memoStore(sqA, 1, 0, 0, true, false, TestResults.Blocked);
        ok("inactive store declined", LightingPatches.memoGet(sqA, 1, 0, 0, true, false) == null);

        LightingPatches.windowBegin();
        ok("active miss", LightingPatches.memoGet(sqA, 1, 0, 0, true, false) == null);
        LightingPatches.memoStore(sqA, 1, 0, 0, true, false, TestResults.Blocked);
        ok("active hit same answer",
                LightingPatches.memoGet(sqA, 1, 0, 0, true, false) == TestResults.Blocked);

        // a kept answer is valid only for the square object it was asked of
        LightingPatches.memoStore(sqB, 1, 0, 0, true, false, TestResults.Clear);
        ok("other square miss", LightingPatches.memoGet(sqA, 1, 0, 0, true, false) == TestResults.Blocked);
        ok("other square hit", LightingPatches.memoGet(sqB, 1, 0, 0, true, false) == TestResults.Clear);

        // different argument combo on the same square is its own slot
        LightingPatches.memoStore(sqB, 0, 0, 1, true, false, TestResults.ClearThroughWindow);
        ok("other combo miss", LightingPatches.memoGet(sqB, 1, 0, 0, true, false) == TestResults.Clear);
        ok("other combo hit",
                LightingPatches.memoGet(sqB, 0, 0, 1, true, false) == TestResults.ClearThroughWindow);

        LightingPatches.memoStore(sqB, 0, 0, 1, true, false, null);
        ok("null store leaves the slot",
                LightingPatches.memoGet(sqB, 0, 0, 1, true, false) == TestResults.ClearThroughWindow);

        // window end wipes; window begin starts clean
        LightingPatches.windowEnd();
        ok("end wipes", LightingPatches.memoGet(sqB, 0, 0, 1, true, false) == null);

        LightingPatches.memoStore(sqA, 2, 0, 0, true, false, TestResults.Clear);
        LightingPatches.windowBegin();
        ok("begin wipes stale", LightingPatches.memoGet(sqA, 2, 0, 0, true, false) == null);

        // key 0 never memoizes even inside a window
        LightingPatches.memoStore(sqA, 10001, 0, 0, true, false, TestResults.Blocked);
        ok("key0 store declined", LightingPatches.memoGet(sqA, 10001, 0, 0, true, false) == null);

        LightingPatches.windowEnd();
        LightingPatches.windowEnd();
        ok("double windowEnd stays clean", LightingPatches.memoGet(sqA, 1, 0, 0, true, false) == null);
    }

    // ---- guard off (template default): the windows do not open ----
    private static void guardBypass() throws Exception {
        Settings.load(SelfTest.write("enabled=true\n"));
        LightingPatches.init();
        ok("template default off", !LightingPatches.guard.on);

        LightingPatches.windowBegin();
        LightingPatches.memoStore(LightingPatches.class, 0, 0, 0, true, true, TestResults.Clear);
        LightingPatches.windowEnd();
        ok("guard off windowBegin fell back to active path", true);
    }

    private static void settingsTemplate() throws Exception {
        Settings.load(SelfTest.write("enabled=true\nlightVisionOnce=true\n"));
        LightingPatches.init();
        ok("template on", LightingPatches.guard.on);
        Settings.load(SelfTest.write("enabled=true\n"));
        LightingPatches.init();
        ok("reload back to off", !LightingPatches.guard.on);
    }

    private LightTest() {
    }
}
