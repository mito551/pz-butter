package pzbutter;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import me.zed_0xff.zombie_buddy.Patch;

/**
 * Task 38 (viewpointPartsSum, Viewpoint third-party): Viewpoint times its main-thread
 * frame in 12 parts (FP.captureWorld) but only ever prints the slow frames' parts over
 * 1 ms (Hitches.parts). Here every frame's parts sum over a minute — average and worst
 * ms per frame per part — and the lights part is split into its seven snapshot calls
 * (lamps, floor plans, torches, headlights, flames, blood, daylight). The hooks only
 * weave when the Viewpoint mod is installed; without it none of the target classes
 * load and nothing runs.
 */
public final class ViewpointPatches {
    /** Slot order = the seven snapshot calls inside FP.captureWorld, same keys as the SPEC. */
    static final String[] LIGHT_NAMES = {
        "lamps", "floor plans", "torches", "headlights", "flames", "blood", "daylight"};
    static final long WINDOW_NANOS = 60_000L * 1_000_000L;

    public static final Guard guard = new Guard("viewpointPartsSum");

    /** Seam for tests: the wall clock. */
    public static LongSupplier CLOCK = System::nanoTime;
    /** Seam for tests: where the summary lines go, one call per line. */
    public static Consumer<String> SINK = System.out::println;

    private static long windowStart;
    private static boolean windowOpen;
    private static long frames;
    private static double[] sum;
    private static double[] worst;
    private static String[] partNames;
    private static long lightStart;
    private static long[] lightSum;
    private static double[] lightWorst;
    private static long[] lightFrames;

    static void init() {
        try {
            guard.on = Settings.flag("viewpointPartsSum");
        } catch (Throwable t) {
            guard.fail(t);
        }
    }

    /** For tests: a fresh window with nothing recorded. */
    public static void resetState() {
        windowStart = 0;
        windowOpen = false;
        frames = 0;
        sum = null;
        worst = null;
        partNames = null;
        int k = LIGHT_NAMES.length;
        lightSum = new long[k];
        lightWorst = new double[k];
        lightFrames = new long[k];
    }

    /** The frames recorded in the running window (test seam). */
    public static long frames() {
        return frames;
    }

    /** A light slot's running sum in ms (test seam). */
    public static double lightSumSlot(int slot) {
        return lightSum == null || slot < 0 || slot >= lightSum.length
            ? 0.0 : lightSum[slot] / 1_000_000.0;
    }

    /**
     * Profile.mainParts(names, parts) fires after the frame's parts all closed, before the
     * stock body stores the arrays for the slow-frame printer; accumulate without skipping.
     */
    public static void partsEnter(Object namesO, Object partsO) {
        if (!guard.on) return;
        try {
            long[] parts = (long[]) partsO;
            if (parts == null) return;
            String[] names = (String[]) namesO;
            int n = Math.min(parts.length, names == null ? parts.length : names.length);
            long now = CLOCK.getAsLong();
            if (!windowOpen) {
                windowStart = now;
                windowOpen = true;
            } else if (now - windowStart >= WINDOW_NANOS) {
                print(now - windowStart);
                resetState();
                windowStart = now;
                windowOpen = true;
            }
            if (sum == null || sum.length < n) {
                sum = new double[n];
                worst = new double[n];
                partNames = new String[n];
            }
            for (int i = 0; i < n; i++) {
                double ms = parts[i] / 1_000_000.0;
                sum[i] += ms;
                if (ms > worst[i]) worst[i] = ms;
                partNames[i] = names[i];
            }
            frames++;
        } catch (Throwable t) {
            guard.fail(t);
        }
    }

    /** The main thread is the only writer; the seven snapshot calls run one after another. */
    public static void lightBegin(int slot) {
        if (!guard.on) return;
        try {
            lightStart = CLOCK.getAsLong();
        } catch (Throwable t) {
            guard.fail(t);
        }
    }

    public static void lightEnd(int slot) {
        if (!guard.on) return;
        try {
            long took = CLOCK.getAsLong() - lightStart;
            if (took < 0) took = 0;
            lightSum[slot] += took;
            double ms = took / 1_000_000.0;
            if (ms > lightWorst[slot]) lightWorst[slot] = ms;
            lightFrames[slot]++;
        } catch (Throwable t) {
            guard.fail(t);
        }
    }

    private static void print(long elapsed) {
        SINK.accept(String.format(Locale.ROOT, "[PZ Butter] viewpoint parts, %.0f s, %d frames:",
            elapsed / 1_000_000_000.0, frames));
        for (int i = 0; i < sum.length; i++) {
            String name = partNames[i] == null ? ("part " + i) : partNames[i];
            SINK.accept(String.format(Locale.ROOT, "%s avg %.2f ms worst %.2f ms",
                name, sum[i] / frames, worst[i]));
        }
        for (int i = 0; i < lightSum.length; i++) {
            if (lightFrames[i] == 0) continue;
            SINK.accept(String.format(Locale.ROOT, "lights %s avg %.2f ms worst %.2f ms",
                LIGHT_NAMES[i], lightSum[i] / 1_000_000.0 / lightFrames[i], lightWorst[i]));
        }
    }

    /** The per-frame 12 parts land here. */
    @Patch(className = "viewpoint.platform.Profile", methodName = "mainParts")
    public static class ProfileParts {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(0) Object names, @Patch.Argument(1) Object parts) {
            if (!guard.on) return;
            try {
                partsEnter(names, parts);
            } catch (Throwable t) {
                guard.fail(t);
            }
        }
    }

    /** lamps */
    @Patch(className = "viewpoint.light.Lights", methodName = "snapshot")
    public static class LightsSnap {
        @Patch.OnEnter
        public static void enter() {
            try {
                lightBegin(0);
            } catch (Throwable t) {
                guard.fail(t);
            }
        }

        @Patch.OnExit
        public static void exit() {
            try {
                lightEnd(0);
            } catch (Throwable t) {
                guard.fail(t);
            }
        }
    }

    /** floor plans */
    @Patch(className = "viewpoint.light.FloorPlans", methodName = "snapshot")
    public static class FloorPlansSnap {
        @Patch.OnEnter
        public static void enter() {
            try {
                lightBegin(1);
            } catch (Throwable t) {
                guard.fail(t);
            };
        }

        @Patch.OnExit
        public static void exit() {
            try {
                lightEnd(1);
            } catch (Throwable t) {
                guard.fail(t);
            };
        }
    }

    /** torches */
    @Patch(className = "viewpoint.light.Torches", methodName = "snapshot")
    public static class TorchesSnap {
        @Patch.OnEnter
        public static void enter() {
            try {
                lightBegin(2);
            } catch (Throwable t) {
                guard.fail(t);
            };
        }

        @Patch.OnExit
        public static void exit() {
            try {
                lightEnd(2);
            } catch (Throwable t) {
                guard.fail(t);
            };
        }
    }

    /** headlights */
    @Patch(className = "viewpoint.light.Headlights", methodName = "snapshot")
    public static class HeadlightsSnap {
        @Patch.OnEnter
        public static void enter() {
            try {
                lightBegin(3);
            } catch (Throwable t) {
                guard.fail(t);
            };
        }

        @Patch.OnExit
        public static void exit() {
            try {
                lightEnd(3);
            } catch (Throwable t) {
                guard.fail(t);
            };
        }
    }

    /** flames */
    @Patch(className = "viewpoint.world.Flames", methodName = "snapshot")
    public static class FlamesSnap {
        @Patch.OnEnter
        public static void enter() {
            try {
                lightBegin(4);
            } catch (Throwable t) {
                guard.fail(t);
            };
        }

        @Patch.OnExit
        public static void exit() {
            try {
                lightEnd(4);
            } catch (Throwable t) {
                guard.fail(t);
            };
        }
    }

    /** blood */
    @Patch(className = "viewpoint.world.Blood", methodName = "snapshot")
    public static class BloodSnap {
        @Patch.OnEnter
        public static void enter() {
            try {
                lightBegin(5);
            } catch (Throwable t) {
                guard.fail(t);
            };
        }

        @Patch.OnExit
        public static void exit() {
            try {
                lightEnd(5);
            } catch (Throwable t) {
                guard.fail(t);
            };
        }
    }

    /** daylight */
    @Patch(className = "viewpoint.light.Daylight", methodName = "snapshot")
    public static class DaylightSnap {
        @Patch.OnEnter
        public static void enter() {
            try {
                lightBegin(6);
            } catch (Throwable t) {
                guard.fail(t);
            };
        }

        @Patch.OnExit
        public static void exit() {
            try {
                lightEnd(6);
            } catch (Throwable t) {
                guard.fail(t);
            };
        }
    }

}
