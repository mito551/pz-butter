package pzbutter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Plan 3c checks (uiCensus first). Run from SelfTest.main; failures are AssertionErrors. */
final class UiRetainTest {
    private UiRetainTest() {}

    static void run() {
        censusKinds();
        censusMixEqualValues();
        censusStableFraction();
        censusMouseAnswerNeedsRect();
        censusVolatileCallNotStable();
        censusChildFoldsIntoWindow();
        censusStableSameElement();
        censusReportLines();
        censusNeedsUiTiming();
        censusChildStableInsideVolatileWindow();
        censusReusableNotDoubleCounted();
        censusChildVolatileNotReusable();
        censusExitMismatchUnwinds();
        censusReusableSumsStableChildren();
        censusReusableThreeLevels();
        censusReusableMixedThreeLevels();
        censusChildReportLine();
    }

    static void censusKinds() {
        assert UiCensus.kind("getTimestampMs") == UiCensus.TIME && UiCensus.kind("getMillisSinceLastRender") == UiCensus.TIME;
        assert UiCensus.kind("getMouseX") == UiCensus.MOUSE && UiCensus.kind("ZombRand") == UiCensus.RANDOM;
        assert UiCensus.kind("getWidth") == UiCensus.OTHER;
    }

    static void censusMixEqualValues() {
        long a = UiCensus.mix(UiCensus.mix(1, "x", Double.valueOf(3.0)), "s", new String("hp"));
        long b = UiCensus.mix(UiCensus.mix(1, "x", Double.valueOf(3.0)), "s", new String("hp"));
        assert a == b;
        assert a != UiCensus.mix(UiCensus.mix(1, "x", Double.valueOf(4.0)), "s", "hp");
        Object o1 = new Object(), o2 = new Object();
        assert UiCensus.mix(1, "o", o1) != UiCensus.mix(1, "o", o2);   // identity for objects
    }

    static void censusStableFraction() {
        UiCensus.resetForTest();
        UiCensus.renderDone("W (w.lua)", 42, false, 10, 2);
        UiCensus.renderDone("W (w.lua)", 42, false, 10, 2);   // stable
        UiCensus.renderDone("W (w.lua)", 43, false, 10, 2);
        UiCensus.renderDone("W (w.lua)", 43, true, 10, 2);    // volatile: not stable
        assert UiCensus.statForTest("W (w.lua)").stable == 1 && UiCensus.statForTest("W (w.lua)").renders == 4;
    }

    // a mouse answer is hashed only inside the element's rectangle; an Object has none, so it never counts
    static void censusMouseAnswerNeedsRect() {
        UiCensus.resetForTest();
        Object el = new Object();
        UiCensus.elementEnterNamed(el, "W");
        UiCensus.noteCall("getMouseX");
        UiCensus.noteRead("getMouseX", 5.0);
        UiCensus.callExit();
        UiCensus.noteCall("getWidth");
        UiCensus.noteRead("getWidth", 3.0);
        UiCensus.callExit();
        UiCensus.elementExit(el);
        UiCensus.Stat s = UiCensus.statForTest("W");
        assert s.reads == 1 && s.byKind[UiCensus.MOUSE] == 1 && s.byKind[UiCensus.OTHER] == 1;
    }

    // a clock read makes every render volatile, so even identical renders are never stable
    static void censusVolatileCallNotStable() {
        UiCensus.resetForTest();
        Object el = new Object();
        for (int i = 0; i < 2; i++) {
            UiCensus.elementEnterNamed(el, "W");
            UiCensus.noteCall("getTimestampMs");
            UiCensus.callExit();
            UiCensus.elementExit(el);
        }
        UiCensus.Stat s = UiCensus.statForTest("W");
        assert s.renders == 2 && s.stable == 0 && s.volatileRenders == 2;
    }

    // a child's reads and volatility belong to the window's render; the child's entry counts as an entry of the window
    static void censusChildFoldsIntoWindow() {
        UiCensus.resetForTest();
        Object win = new Object(), child = new Object();
        UiCensus.elementEnterNamed(win, "W");
        UiCensus.noteRead("k", 1.0);
        UiCensus.elementEnterNamed(child, null);
        UiCensus.noteVolatile();
        UiCensus.elementExit(child);
        UiCensus.elementExit(win);
        UiCensus.Stat s = UiCensus.statForTest("W");
        assert s.renders == 1 && s.entries == 2 && s.volatileRenders == 1 && s.reads == 1;
    }

    // the same element, the same reads: the second render is stable, a different read is not
    static void censusStableSameElement() {
        UiCensus.resetForTest();
        Object el = new Object();
        for (int i = 0; i < 3; i++) {
            UiCensus.elementEnterNamed(el, "W");
            UiCensus.noteRead("k", 1.0);
            UiCensus.elementExit(el);
        }
        assert UiCensus.statForTest("W").renders == 3 && UiCensus.statForTest("W").stable == 2;
    }

    static void censusReportLines() {
        UiCensus.Stat s = new UiCensus.Stat("W");
        s.renders = 2;
        s.stable = 1;
        s.reads = 6;
        UiTiming.Stat t = new UiTiming.Stat("W");
        t.inclNs = 10_000_000L;
        Map<String, UiTiming.Stat> timing = new HashMap<>();
        timing.put("W", t);
        Map<String, UiCensus.Stat> census = new HashMap<>();
        census.put("W", s);
        List<String> l = UiCensus.reportLines(census, new HashMap<>(), timing, 1_000_000_000L);
        assert l.size() == 1;
        assert l.get(0).contains("10.000 ms/s") && l.get(0).contains("stable  50.0%") && l.get(0).contains("5.000 ms/s");
    }

    // uiCensus needs uiTiming: without it the census stays off (one log line)
    static void censusNeedsUiTiming() {
        Settings.load(SelfTest.write("enabled=true\nuiCensus=true\n"));
        UiTiming.guard.on = false;
        UiCensus.init();
        assert !UiCensus.guard.on;
        Settings.load(SelfTest.write("enabled=true\nuiTiming=true\nuiCensus=true\n"));
        UiTiming.init();
        UiCensus.init();
        assert UiCensus.guard.on;
        UiCensus.guard.on = false;
        UiTiming.guard.on = false;
    }

    // one render of window W with child C inside it: W runs winNs, C runs childNs (both on the driven clock)
    private static void renderWithChild(long[] t, Object win, Object child, boolean winVolatile, boolean childVolatile,
                                        long winNs, long childNs) {
        long start = t[0];
        UiCensus.elementEnterNamed(win, "W");
        UiCensus.noteRead("k", 1.0);
        if (winVolatile) UiCensus.noteVolatile();
        t[0] += 1_000_000L;
        UiCensus.elementEnterNamed(child, "C");
        UiCensus.noteRead("k", 2.0);
        if (childVolatile) UiCensus.noteVolatile();
        t[0] += childNs;
        UiCensus.elementExit(child);
        t[0] = start + winNs;
        UiCensus.elementExit(win);
    }

    // W reads the clock every render, so W is never stable; its child C is, from the second render on: 5 ms reusable
    static void censusChildStableInsideVolatileWindow() {
        UiCensus.resetForTest();
        long[] t = {0};
        UiTiming.clock = () -> t[0];
        Object win = new Object(), child = new Object();
        renderWithChild(t, win, child, true, false, 7_000_000L, 5_000_000L);
        UiCensus.Stat w = UiCensus.statForTest("W");
        assert w.reusableNs == 0 && w.stable == 0;   // first render: nothing kept yet
        renderWithChild(t, win, child, true, false, 7_000_000L, 5_000_000L);
        assert w.reusableNs == 5_000_000L && w.stable == 0;
        UiCensus.ChildStat c = UiCensus.childStatForTest("W > C");
        assert c.renders == 2 && c.stable == 1 && c.inclNs == 10_000_000L;
        UiTiming.clock = System::nanoTime;
    }

    // W and C both stable: W's reusable time is W's own inclusive time, C's is not added on top
    static void censusReusableNotDoubleCounted() {
        UiCensus.resetForTest();
        long[] t = {0};
        UiTiming.clock = () -> t[0];
        Object win = new Object(), child = new Object();
        renderWithChild(t, win, child, false, false, 10_000_000L, 5_000_000L);
        renderWithChild(t, win, child, false, false, 10_000_000L, 5_000_000L);
        UiCensus.Stat w = UiCensus.statForTest("W");
        assert w.stable == 1 && w.reusableNs == 10_000_000L;
        assert UiCensus.childStatForTest("W > C").stable == 1;
        UiTiming.clock = System::nanoTime;
    }

    // C sees a volatile read: C is not reusable, and W (which contains it) gives nothing either
    static void censusChildVolatileNotReusable() {
        UiCensus.resetForTest();
        long[] t = {0};
        UiTiming.clock = () -> t[0];
        Object win = new Object(), child = new Object();
        renderWithChild(t, win, child, false, true, 10_000_000L, 5_000_000L);
        renderWithChild(t, win, child, false, true, 10_000_000L, 5_000_000L);
        assert UiCensus.statForTest("W").reusableNs == 0;
        assert UiCensus.childStatForTest("W > C").stable == 0;
        UiTiming.clock = System::nanoTime;
    }

    // an element that reads one value, is volatile when asked, runs inside() (children advance the clock), then takes ns
    private static void element(long[] t, Object el, String name, boolean volatileRead, long ns, Runnable inside) {
        long start = t[0];
        UiCensus.elementEnterNamed(el, name);
        UiCensus.noteRead("k", 1.0);
        if (volatileRead) UiCensus.noteVolatile();
        inside.run();
        t[0] = start + ns;
        UiCensus.elementExit(el);
    }

    // the window exits without its child's exit: the child is dropped, the window's own render is the last one
    static void censusExitMismatchUnwinds() {
        UiCensus.resetForTest();
        long[] t = {0};
        UiTiming.clock = () -> t[0];
        Object win = new Object(), child = new Object();
        UiCensus.elementExit(new Object());   // a stray exit with nothing open: nothing happens
        assert UiCensus.depthForTest() == 0;
        UiCensus.elementEnterNamed(win, "W");
        UiCensus.noteRead("k", 1.0);
        t[0] += 1_000_000L;
        UiCensus.elementEnterNamed(child, "C");
        UiCensus.noteRead("k", 2.0);
        t[0] += 5_000_000L;
        UiCensus.elementExit(win);   // the child's exit never came
        assert UiCensus.depthForTest() == 0;
        renderWithChild(t, win, child, false, false, 10_000_000L, 5_000_000L);
        renderWithChild(t, win, child, false, false, 10_000_000L, 5_000_000L);
        UiCensus.Stat w = UiCensus.statForTest("W");
        assert UiCensus.depthForTest() == 0;
        assert w.renders == 3 && w.reusableNs == 10_000_000L && w.reusableNs <= 2 * 10_000_000L;
        UiTiming.clock = System::nanoTime;
    }

    // unstable window, two stable panels of 3 and 4 ms: the window can reuse 7 ms
    static void censusReusableSumsStableChildren() {
        UiCensus.resetForTest();
        long[] t = {0};
        UiTiming.clock = () -> t[0];
        Object win = new Object(), c1 = new Object(), c2 = new Object();
        for (int i = 0; i < 2; i++) {
            element(t, win, "W", true, 10_000_000L, () -> {
                element(t, c1, "C1", false, 3_000_000L, () -> { });
                element(t, c2, "C2", false, 4_000_000L, () -> { });
            });
        }
        assert UiCensus.statForTest("W").reusableNs == 7_000_000L;
        UiTiming.clock = System::nanoTime;
    }

    // unstable W > stable P (6 ms) > stable G (2 ms): P is reusable whole, G is not added on top
    static void censusReusableThreeLevels() {
        UiCensus.resetForTest();
        long[] t = {0};
        UiTiming.clock = () -> t[0];
        Object win = new Object(), p = new Object(), g = new Object();
        for (int i = 0; i < 2; i++) {
            element(t, win, "W", true, 10_000_000L, () ->
                element(t, p, "P", false, 6_000_000L, () ->
                    element(t, g, "G", false, 2_000_000L, () -> { })));
        }
        assert UiCensus.statForTest("W").reusableNs == 6_000_000L;
        UiTiming.clock = System::nanoTime;
    }

    // unstable W > unstable P (volatile read in P) > stable G (2 ms): only G is reusable
    static void censusReusableMixedThreeLevels() {
        UiCensus.resetForTest();
        long[] t = {0};
        UiTiming.clock = () -> t[0];
        Object win = new Object(), p = new Object(), g = new Object();
        for (int i = 0; i < 2; i++) {
            element(t, win, "W", true, 10_000_000L, () ->
                element(t, p, "P", true, 6_000_000L, () ->
                    element(t, g, "G", false, 2_000_000L, () -> { })));
        }
        assert UiCensus.statForTest("W").reusableNs == 2_000_000L;
        UiTiming.clock = System::nanoTime;
    }

    // the report: one window line with a reusable column, one census child line in the brief's format
    static void censusChildReportLine() {
        UiCensus.resetForTest();
        long[] t = {0};
        UiTiming.clock = () -> t[0];
        Object win = new Object(), child = new Object();
        renderWithChild(t, win, child, true, false, 10_000_000L, 5_000_000L);
        renderWithChild(t, win, child, true, false, 10_000_000L, 5_000_000L);
        Map<String, UiTiming.Stat> timing = new HashMap<>();
        UiTiming.Stat ts = new UiTiming.Stat("W");
        ts.inclNs = 20_000_000L;
        timing.put("W", ts);
        List<String> lines = UiCensus.report(timing, 1_000_000_000L);
        List<String> children = new java.util.ArrayList<>();
        String window = null;
        for (String l : lines) {
            if (l.startsWith("[PZButter] census child:")) children.add(l);
            else if (l.startsWith("[PZButter] census:")) window = l;
        }
        assert window != null && window.contains("reusable");
        assert children.size() == 1;
        assert children.get(0).contains("ms/s  stable  50.0%  W > C");
        UiTiming.clock = System::nanoTime;
    }
}
