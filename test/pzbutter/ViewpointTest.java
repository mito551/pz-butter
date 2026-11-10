package pzbutter;

import java.util.ArrayList;
import java.util.List;

/**
 * Task 38 (viewpointPartsSum, Viewpoint third-party): the per-frame 12 parts of
 * Profile.mainParts accumulate over a minute (average and worst ms per frame),
 * and the lights part is split into its seven snapshot calls; the summary then
 * resets. Nothing accumulates with the key off.
 */
public final class ViewpointTest {
    private ViewpointTest() {}

    private static final String[] NAMES = {"relief", "chunk cache", "loot menu", "far world",
        "far light", "characters", "vehicles", "world models", "corpses", "lights",
        "world text", "caches"};

    public static void run() throws Exception {
        offAccumulatesNothing();
        accumulateAndPrint();
        backToStock();
    }

    private static void check(String what, boolean ok) {
        if (!ok) throw new AssertionError(what);
    }

    /** Key off (template default): hooks fire but nothing records. */
    private static void offAccumulatesNothing() throws Exception {
        Settings.load(SelfTest.write("enabled=true\n"));
        ViewpointPatches.init();
        check("viewpointPartsSum defaults off", !ViewpointPatches.guard.on);
        ViewpointPatches.partsEnter(NAMES, new long[] {1_000_000L, 2_000_000L});
        ViewpointPatches.lightBegin(0);
        ViewpointPatches.lightEnd(0);
        check("off: frames stay 0", ViewpointPatches.frames() == 0);
    }

    /** Two frames accumulate, the 60 s window prints and resets, lights split carries slots. */
    private static void accumulateAndPrint() throws Exception {
        Settings.load(SelfTest.write("enabled=true\nviewpointPartsSum=true\n"));
        ViewpointPatches.init();
        check("template turns the guard on", ViewpointPatches.guard.on);

        long[] now = {0};
        List<String> lines = new ArrayList<>();
        java.util.function.LongSupplier realClock = ViewpointPatches.CLOCK;
        java.util.function.Consumer<String> realSink = ViewpointPatches.SINK;
        ViewpointPatches.CLOCK = () -> now[0];
        ViewpointPatches.SINK = lines::add;
        ViewpointPatches.resetState();
        try {
            // frame 1: relief 1 ms, lights 3 ms, caches 0.5 ms (rest 0)
            ViewpointPatches.partsEnter(NAMES, new long[] {
                1_000_000L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 3_000_000L, 0L, 500_000L});
            // frame 2: relief 3 ms (the worst), lights 1 ms
            ViewpointPatches.partsEnter(NAMES, new long[] {
                3_000_000L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 1_000_000L, 0L, 0L});
            check("two frames recorded", ViewpointPatches.frames() == 2);
            check("no print before the window ends", lines.isEmpty());

            // lights slots: lamps 2 ms + 1 ms = 3 ms, daylight 5 ms
            now[0] = 10_000_000L;
            ViewpointPatches.lightBegin(0); now[0] = 12_000_000L; ViewpointPatches.lightEnd(0);
            now[0] = 13_000_000L;
            ViewpointPatches.lightBegin(0); now[0] = 14_000_000L; ViewpointPatches.lightEnd(0);
            now[0] = 20_000_000L;
            ViewpointPatches.lightBegin(6); now[0] = 25_000_000L; ViewpointPatches.lightEnd(6);
            check("lamps running sum 3 ms", ViewpointPatches.lightSumSlot(0) >= 2.9);

            // sixty one seconds... just past the minute: the next frame prints and resets.
            // 1 header + 12 parts + the two light slots with samples = 15 lines.
            now[0] = 60_100_000_000L;
            ViewpointPatches.partsEnter(NAMES, new long[] {
                9_000_000L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L});
            check("summary printed after the window", lines.size() == 15);
            check("header line: " + lines.get(0), lines.get(0).contains("viewpoint")
                && lines.get(0).contains("60 s") && lines.get(0).contains("2 frames"));
            check("relief line: avg 2, worst 3", lines.get(1).contains("relief")
                && lines.get(1).contains("avg 2.00") && lines.get(1).contains("worst 3.00"));
            check("lights part line", lines.get(10).contains("lights")
                && lines.get(10).contains("avg 2.00") && lines.get(10).contains("worst 3.00"));
            check("caches line: avg 0.25", lines.get(12).contains("caches")
                && lines.get(12).contains("avg 0.25"));
            check("lamps split line: avg 1.5 worst 2", lines.get(13).contains("lamps")
                && lines.get(13).contains("avg 1.50") && lines.get(13).contains("worst 2.00"));
            check("daylight split line", lines.get(14).contains("daylight")
                && lines.get(14).contains("avg 5.00"));
            boolean floorPrinted = false;
            for (String l : lines) {
                if (l.contains("floor plans")) floorPrinted = true;
            }
            check("unused light slot not printed", !floorPrinted);

            // reset: the expiring frame itself opened the next window (its parts are in it)
            check("expiring frame starts the next window", ViewpointPatches.frames() == 1);
            now[0] = 60_100_000_001L;
            ViewpointPatches.partsEnter(NAMES, new long[] {
                7_000_000L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L});
            check("window restarted, no second print", lines.size() == 15);

            // a backward clock never makes a negative slot
            now[0] = 30_000_000L;
            ViewpointPatches.lightBegin(2); now[0] = 29_000_000L; ViewpointPatches.lightEnd(2);
            check("negative elapsed clamps to zero", ViewpointPatches.lightSumSlot(2) == 0.0);

            // a fresh window: only relief moves (6 then 4 ms), a minute passes, print
            ViewpointPatches.resetState();
            now[0] = 0L;
            ViewpointPatches.partsEnter(NAMES, new long[] {
                6_000_000L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L});
            now[0] = 60_100_000_000L;
            ViewpointPatches.partsEnter(NAMES, new long[] {
                4_000_000L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L});
            // no light samples in this window: only the header + the 12 parts print
            check("second summary printed: " + lines.size(), lines.size() == 28);
            check("second relief line: avg 6, worst 6", lines.get(16).contains("relief")
                && lines.get(16).contains("avg 6.00") && lines.get(16).contains("worst 6.00"));
        } finally {
            ViewpointPatches.CLOCK = realClock;
            ViewpointPatches.SINK = realSink;
            ViewpointPatches.resetState();
        }
    }

    /** Reload the stock template so the guard is off for whatever runs next. */
    private static void backToStock() throws Exception {
        Settings.load(SelfTest.write("enabled=true\n"));
        ViewpointPatches.init();
        check("stock template turns the guard off", !ViewpointPatches.guard.on);
    }
}
