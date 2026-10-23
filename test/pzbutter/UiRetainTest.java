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
        List<String> l = UiCensus.reportLines(census, timing, 1_000_000_000L);
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
}
