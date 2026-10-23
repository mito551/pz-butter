package pzbutter;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.concurrent.CompletableFuture;

import zombie.core.Styles.AlphaOp;
import zombie.core.Styles.GeometryData;
import zombie.core.Styles.Style;
import zombie.core.profiling.PerformanceProfileProbe;
import zombie.core.sprite.GenericSpriteRenderState;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;

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
        sliceCopyIsComplete();
        sliceCopyDoesNotAliasVars();
        sliceReplayableTypes();
        sliceRejectsUnreadyTexture();
        sliceRejectsDrawerFutureProbe();
        sliceRecordReplayRoundTrip();
        sliceRecordReusesEntries();
        readSameSemantics();
        readSetStillSame();
        retainNeedsSameStencil();
        retainRejectsSliceOnError();
        retainVolatileNeverReplays();
        retainNeedsSameEpochFboGeometryReads();
        retainGeometryOfElement();
        try { retainNotesFillOpenRecord(); } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        retainSuspendedNotesNothing();
        try { retainMouseNeedsSamePosition(); retainUnverifiableAnswerIsVolatile(); } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        retainJavaRenderOverrideIsLive();
        retainRenderWriteIsVolatile();
        retainBalanceOnErrorAndThrow();
        retainReplayThrowLeavesNothingOpen();
        retainExcludeAndModsFilter();
        retainSettingsAndInit();
        retainControlsSnapshot();
        segmentsReplayOrder();
        retainOverrideChildDoesNotPoisonParent();
        retainChildrenReplayBalanced();
        retainChildrenUnobservedControlIsLive();
        retainChildrenUnbalancedStencilIsLive();
        retainChildrenSetting();
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

    // a state whose first slots are objects (CheckSpriteSlots only fills the slot at numSprites)
    private static GenericSpriteRenderState state() {
        GenericSpriteRenderState s = new GenericSpriteRenderState(0) { };
        for (int i = 0; i < 8; i++) {
            s.numSprites = i;
            s.CheckSpriteSlots();
        }
        s.numSprites = 0;
        return s;
    }

    private static void put(GenericSpriteRenderState s, int i, TextureDraw.Type t, float x) {
        TextureDraw d = s.sprite[i];
        d.type = t;
        d.x0 = x;
        d.vars = new float[] {x};
        d.tex = null;
    }

    private static Style style(int id) {
        return new Style() {
            public void setupState() { }
            public void resetState() { }
            public int getStyleID() { return id; }
            public AlphaOp getAlphaOp() { return null; }
            public boolean getRenderSprite() { return true; }
            public GeometryData build() { return null; }
            public void render(int a, int b) { }
        };
    }

    // copy carries every instance field: set each to a distinct value by reflection, copy, compare
    static void sliceCopyIsComplete() {
        try { copyCarriesEveryField(); } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static void copyCarriesEveryField() throws ReflectiveOperationException {
        TextureDraw a = new TextureDraw(), b = new TextureDraw();
        b.drawer = new TextureDraw.GenericDrawer() { public void render() { } };
        b.future = CompletableFuture.completedFuture(null);
        b.probe = new PerformanceProfileProbe("copy-test");
        int i = 1;
        for (Field f : TextureDraw.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            String n = f.getName();
            // handles a kept slice never holds: copy leaves them null (ImDrawData needs native imgui, so it is never set)
            if (n.equals("drawer") || n.equals("future") || n.equals("probe") || n.equals("imDrawData")) continue;
            f.setAccessible(true);
            Class<?> t = f.getType();
            if (t == float.class) f.setFloat(a, i++);
            else if (t == int.class) f.setInt(a, i++);
            else if (t == boolean.class) f.setBoolean(a, true);
            else if (t == byte.class) f.setByte(a, (byte) i++);
            else if (t == TextureDraw.Type.class) f.set(a, TextureDraw.Type.glStencilFunc);
            else if (t == float[].class) f.set(a, new float[] {i++});
            else if (t == Texture.class) f.set(a, new Texture());
        }
        UiSlice.copy(a, b);
        assert b.drawer == null && b.future == null && b.probe == null && b.imDrawData == null;
        for (Field f : TextureDraw.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            Object x = f.get(a), y = f.get(b);
            assert java.util.Objects.equals(x, y) || (x instanceof float[] fa && Arrays.equals(fa, (float[]) y)) : f.getName();
        }
    }

    static void sliceCopyDoesNotAliasVars() {
        TextureDraw src = new TextureDraw(), dst = new TextureDraw();
        src.vars = new float[] {1f, 2f};
        UiSlice.copy(src, dst);
        assert dst.vars != src.vars && Arrays.equals(dst.vars, new float[] {1f, 2f});
        src.vars[0] = 9f;
        assert dst.vars[0] == 1f;
        float[] kept = dst.vars;
        UiSlice.copy(src, dst);   // same length: the destination keeps its own array
        assert dst.vars == kept;
        src.vars = new float[3];
        UiSlice.copy(src, dst);   // other length: a fresh array of the source's length
        assert dst.vars.length == 3 && dst.vars != src.vars;
        src.vars = null;
        UiSlice.copy(src, dst);
        assert dst.vars == null;
    }

    static void sliceReplayableTypes() {
        Set<TextureDraw.Type> want = EnumSet.of(TextureDraw.Type.glDraw, TextureDraw.Type.glStencilFunc,
                TextureDraw.Type.glStencilOp, TextureDraw.Type.glStencilMask, TextureDraw.Type.glColorMask,
                TextureDraw.Type.glAlphaFunc, TextureDraw.Type.glBlendFunc, TextureDraw.Type.glBlendFuncSeparate,
                TextureDraw.Type.glEnable, TextureDraw.Type.glDisable);
        for (TextureDraw.Type t : TextureDraw.Type.values()) assert UiSlice.replayable(t) == want.contains(t) : t;
    }

    // a texture still loading (EMPTY) makes the range unkept; no texture is fine. A READY Texture cannot be built
    // headless (its constructors reach GL), so the accept branch for a ready texture is not exercised here.
    static void sliceRejectsUnreadyTexture() {
        GenericSpriteRenderState s = state();
        put(s, 0, TextureDraw.Type.glDraw, 1f);
        s.sprite[0].tex = new Texture();
        assert UiSlice.record(s, 0, 1, null) == null;
        s.sprite[0].tex = null;
        assert UiSlice.record(s, 0, 1, null) != null;
    }

    // record refuses any range holding a non-null drawer, future, probe, or a non-replayable entry
    static void sliceRejectsDrawerFutureProbe() {
        GenericSpriteRenderState s = state();
        put(s, 0, TextureDraw.Type.glDraw, 1f);
        put(s, 1, TextureDraw.Type.glEnable, 2f);
        assert UiSlice.record(s, 0, 2, null) != null;
        s.sprite[0].drawer = new TextureDraw.GenericDrawer() { public void render() { } };
        assert UiSlice.record(s, 0, 2, null) == null;
        s.sprite[0].drawer = null;
        s.sprite[0].future = CompletableFuture.completedFuture(null);
        assert UiSlice.record(s, 0, 2, null) == null;
        s.sprite[0].future = null;
        s.sprite[0].probe = new PerformanceProfileProbe("reject-test");
        assert UiSlice.record(s, 0, 2, null) == null;
        s.sprite[0].probe = null;
        assert UiSlice.record(s, 0, 2, null) != null;
        put(s, 1, TextureDraw.Type.DrawModel, 2f);
        assert UiSlice.record(s, 0, 2, null) == null;
    }

    // record copies a range, replay writes it into another state's own slots, and the kept slice aliases nothing
    static void sliceRecordReplayRoundTrip() {
        GenericSpriteRenderState src = state();
        put(src, 0, TextureDraw.Type.glEnable, 0f);   // outside the recorded range
        put(src, 1, TextureDraw.Type.glDraw, 1f);
        src.sprite[1].flipped = true;
        src.sprite[1].col0 = 7;
        src.style[1] = style(11);
        put(src, 2, TextureDraw.Type.glStencilFunc, 2f);
        put(src, 3, TextureDraw.Type.glDraw, 3f);
        src.style[3] = style(13);
        UiSlice sl = UiSlice.record(src, 1, 4, null);
        assert sl != null && sl.n == 3;
        assert sl.styles[0] == src.style[1] && sl.styles[2] == src.style[3];
        assert sl.entries[0] != src.sprite[1];   // the slice owns its entries

        GenericSpriteRenderState dst = state();
        UiSlice.replay(sl, dst);
        assert dst.numSprites == 3 && UiSlice.sameAs(sl, dst, 0, 3);
        assert UiSlice.sameAs(sl, src, 1, 4);
        for (int i = 0; i < 3; i++) assert dst.sprite[i] != sl.entries[i];   // the state's own slots
        dst.sprite[0].vars[0] = 99f;
        assert sl.entries[0].vars[0] == 1f;
        UiSlice.replay(sl, dst);   // replay appends
        assert dst.numSprites == 6 && UiSlice.sameAs(sl, dst, 3, 6);
    }

    // a second record into the same slice reuses its entries, and sameAs sees a changed field
    static void sliceRecordReusesEntries() {
        GenericSpriteRenderState s = state();
        put(s, 0, TextureDraw.Type.glDraw, 1f);
        put(s, 1, TextureDraw.Type.glDraw, 2f);
        UiSlice sl = UiSlice.record(s, 0, 2, null);
        TextureDraw first = sl.entries[0];
        put(s, 0, TextureDraw.Type.glDraw, 5f);
        assert UiSlice.record(s, 0, 2, sl) == sl;
        assert sl.entries[0] == first && first.x0 == 5f;
        assert UiSlice.sameAs(sl, s, 0, 2);
        assert !UiSlice.sameAs(sl, s, 0, 1);
        s.sprite[1].y2 = 9f;
        assert !UiSlice.sameAs(sl, s, 0, 2);
    }

    // ---- uiRetained: read sets, verify, replay decision ----

    static void readSameSemantics() {
        assert UiRetain.same(Double.valueOf(3.0), Double.valueOf(3.0));
        assert UiRetain.same("a", new String("a"));
        assert !UiRetain.same(new Object(), new Object());
        assert UiRetain.same(null, null);
        assert !UiRetain.same(1.0, 1.0f);
        assert !UiRetain.same(null, "a") && !UiRetain.same("a", null);
        Object o = new Object();
        assert UiRetain.same(o, o);
        assert UiRetain.same(Integer.valueOf(1000), Integer.valueOf(1000)) && UiRetain.same(Boolean.TRUE, Boolean.TRUE);
        assert !UiRetain.same(Double.valueOf(3.0), "3.0");
    }

    private static BiFunction<Object, Object, Object> reader(Map<Object, Map<Object, Object>> m) {
        return (t, k) -> m.get(t).get(k);
    }

    static void readSetStillSame() {
        Object t1 = new Object(), t2 = new Object();
        Map<Object, Map<Object, Object>> m = new HashMap<>();
        m.put(t1, new HashMap<>(Map.of("a", 1.0, "b", "x")));
        m.put(t2, new HashMap<>(Map.of("c", true)));
        UiRetain.ReadSet rs = new UiRetain.ReadSet();
        rs.add(t1, "a", 1.0);
        rs.add(t1, "b", "x");
        rs.add(t2, "c", true);
        assert rs.size() == 3 && rs.stillSame(reader(m));
        m.get(t1).put("a", 2.0);
        assert !rs.stillSame(reader(m));
        m.get(t1).put("a", 1.0);
        assert rs.stillSame(reader(m));
        m.get(t2).remove("c");   // a key removed: the reader answers null
        assert !rs.stillSame(reader(m));
        rs.clear();
        assert rs.size() == 0 && rs.stillSame(reader(m));
        for (int i = 0; i < 1000; i++) rs.add(t1, "a", 1.0);   // grows
        assert rs.size() == 1000;
    }

    private static UiRetain.Record record() {
        GenericSpriteRenderState s = state();
        put(s, 0, TextureDraw.Type.glDraw, 1f);
        UiRetain.Record r = new UiRetain.Record();
        r.slice = UiSlice.record(s, 0, 1, null);
        r.geometry = new UiRetain.Geometry();
        r.stencilIn = 2;
        r.stencilOut = 3;
        r.epoch = 7;
        r.fbo = true;
        return r;
    }

    private static final BiFunction<Object, Object, Object> NO_READS = (t, k) -> null;

    static void retainNeedsSameStencil() {
        UiRetain.Record r = record();
        assert UiRetain.canReplay(r, 2, 7, true, new UiRetain.Geometry(), NO_READS);
        assert !UiRetain.canReplay(r, 1, 7, true, new UiRetain.Geometry(), NO_READS);
        assert !UiRetain.canReplay(r, 3, 7, true, new UiRetain.Geometry(), NO_READS);
    }

    static void retainRejectsSliceOnError() {
        UiRetain.Record r = record();
        r.errorSeen = true;
        assert !UiRetain.canReplay(r, 2, 7, true, new UiRetain.Geometry(), NO_READS);
        r.errorSeen = false;
        r.slice = null;   // the slice was rejected at record time
        assert !UiRetain.canReplay(r, 2, 7, true, new UiRetain.Geometry(), NO_READS);
    }

    static void retainVolatileNeverReplays() {
        UiRetain.Record r = record();
        r.volatileSeen = true;
        assert !UiRetain.canReplay(r, 2, 7, true, new UiRetain.Geometry(), NO_READS);
    }

    static void retainNeedsSameEpochFboGeometryReads() {
        UiRetain.Record r = record();
        UiRetain.Geometry g = new UiRetain.Geometry();
        assert !UiRetain.canReplay(r, 2, 8, true, g, NO_READS);    // epoch bumped
        assert !UiRetain.canReplay(r, 2, 7, false, g, NO_READS);   // FBO flag flipped
        UiRetain.Geometry moved = new UiRetain.Geometry();
        moved.absX = 5;
        assert !UiRetain.canReplay(r, 2, 7, true, moved, NO_READS);
        UiRetain.Geometry nan = new UiRetain.Geometry();
        nan.absY = Double.NaN;
        UiRetain.Geometry nan2 = new UiRetain.Geometry();
        nan2.absY = Double.NaN;
        r.geometry = nan2;
        assert UiRetain.canReplay(r, 2, 7, true, nan, NO_READS);   // NaN equals itself here
        r.geometry = new UiRetain.Geometry();
        Object t = new Object();
        r.reads.add(t, "k", 1.0);
        assert UiRetain.canReplay(r, 2, 7, true, g, (tt, k) -> 1.0);
        assert !UiRetain.canReplay(r, 2, 7, true, g, (tt, k) -> 2.0);
    }

    static void retainGeometryOfElement() {
        zombie.ui.UIElement e = new zombie.ui.UIElement();
        UiRetain.Geometry a = UiRetain.Geometry.of(e);
        assert a.equals(UiRetain.Geometry.of(e));
        e.setX(12.0);
        assert !a.equals(UiRetain.Geometry.of(e));
        UiRetain.Geometry b = UiRetain.Geometry.of(e);
        e.setVisible(!e.isVisible());
        assert !b.equals(UiRetain.Geometry.of(e));
        zombie.ui.UIElement kid = new zombie.ui.UIElement();
        UiRetain.Geometry c = UiRetain.Geometry.of(e);
        e.AddChild(kid);
        assert !c.equals(UiRetain.Geometry.of(e));   // a child added
    }

    static void retainNotesFillOpenRecord() throws ReflectiveOperationException {
        UiRetain.Record r = new UiRetain.Record();
        UiRetain.openForTest(r);
        try {
            Object t = new Object();
            UiRetain.noteRead(t, "k", 1.0);
            assert r.reads.size() == 1;
            java.util.ArrayList<Object> list = new java.util.ArrayList<>(List.of(10, 20));
            se.krka.kahlua.integration.expose.caller.MethodCaller size =
                new se.krka.kahlua.integration.expose.caller.MethodCaller(java.util.ArrayList.class.getMethod("size"), null, true);
            se.krka.kahlua.integration.expose.caller.MethodCaller get =
                new se.krka.kahlua.integration.expose.caller.MethodCaller(java.util.ArrayList.class.getMethod("get", int.class), null, true);
            se.krka.kahlua.integration.expose.caller.MethodCaller add =
                new se.krka.kahlua.integration.expose.caller.MethodCaller(java.util.ArrayList.class.getMethod("add", Object.class), null, true);
            UiRetain.noteCall(size, list, new Object[0]);
            UiRetain.noteAnswer(2);
            UiRetain.callExit();
            Object[] args = {0};
            UiRetain.noteCall(get, list, args);
            args[0] = 1;   // Kahlua reuses its argument arrays
            UiRetain.noteAnswer(10);
            UiRetain.callExit();
            UiRetain.noteCall(add, list, new Object[] {5});   // not a getter: an effect, not recorded
            UiRetain.callExit();
            assert r.nCalls == 2 && ((Object[]) r.calls[1].args)[0].equals(0);
            assert UiRetain.callsSame(r);
            list.set(0, 11);   // get(0) answers differently now
            assert !UiRetain.callsSame(r);
            list.set(0, 10);
            assert UiRetain.callsSame(r);
            list.add(7);       // size differs
            assert !UiRetain.callsSame(r);
            UiRetain.noteVolatile();
            UiRetain.noteError();
            assert r.volatileSeen && r.errorSeen;
            UiRetain.noteItemIcon(null);   // no item: nothing, no throw
        } finally { UiRetain.closeForTest(); }
    }

    static void retainSuspendedNotesNothing() {
        UiRetain.Record r = new UiRetain.Record();
        UiRetain.openForTest(r);
        try {
            UiRetain.suspended = true;
            UiRetain.noteRead(new Object(), "k", 1.0);
            UiRetain.noteVolatile();
            UiRetain.noteError();
            assert r.reads.size() == 0 && !r.volatileSeen && !r.errorSeen;
        } finally { UiRetain.suspended = false; UiRetain.closeForTest(); }
        UiRetain.noteRead(new Object(), "k", 1.0);   // nothing open: nothing
        assert r.reads.size() == 0;
    }

    static void retainMouseNeedsSamePosition() throws ReflectiveOperationException {
        UiRetain.Record r = record();
        assert UiRetain.mouseClear(r, 1, 1);   // no mouse read: any position
        r.mouseSeen = true;
        r.mouseX = 10;
        r.mouseY = 20;
        assert UiRetain.mouseClear(r, 10, 20) && !UiRetain.mouseClear(r, 11, 20) && !UiRetain.mouseClear(r, 10, 19);
        // a mouse-kind call during a record stores the position and its answer is not "unverifiable"
        UiRetain.Record n = new UiRetain.Record();
        UiRetain.openForTest(n);
        try {
            se.krka.kahlua.integration.expose.caller.MethodCaller m = new se.krka.kahlua.integration.expose.caller.MethodCaller(
                Mouseish.class.getMethod("getMouseX"), null, false);
            UiRetain.noteCall(m, null, new Object[0]);
            UiRetain.noteAnswer(5);
            UiRetain.callExit();
            assert n.mouseSeen && !n.volatileSeen && n.nCalls == 0;
        } finally { UiRetain.closeForTest(); }
    }

    public static final class Mouseish { public static int getMouseX() { return 5; } }

    public static class Over extends zombie.ui.UIElement { public void render() { } }
    public static class Sub extends Over { }

    static void retainJavaRenderOverrideIsLive() {
        assert !UiRetain.hasJavaRender(new zombie.ui.UIElement());
        assert UiRetain.hasJavaRender(new Over()) && UiRetain.hasJavaRender(new Sub());
        zombie.ui.UIElement p = new zombie.ui.UIElement();
        zombie.ui.UIElement mid = new zombie.ui.UIElement();
        p.AddChild(mid);
        assert !UiRetain.hasJavaRender(p);
        mid.AddChild(new Over());   // a grandchild; it need not call super.render() to count
        assert UiRetain.hasJavaRender(p);
    }

    static void retainUnverifiableAnswerIsVolatile() throws ReflectiveOperationException {
        java.util.ArrayList<Object> list = new java.util.ArrayList<>(List.of(1));
        se.krka.kahlua.integration.expose.caller.MethodCaller add =
            new se.krka.kahlua.integration.expose.caller.MethodCaller(java.util.ArrayList.class.getMethod("add", Object.class), null, true);
        se.krka.kahlua.integration.expose.caller.MethodCaller size =
            new se.krka.kahlua.integration.expose.caller.MethodCaller(java.util.ArrayList.class.getMethod("size"), null, true);
        UiRetain.Record r = new UiRetain.Record();
        UiRetain.openForTest(r);
        try {
            UiRetain.noteCall(add, list, new Object[] {2});   // a void-like effect that pushes nothing
            UiRetain.callExit();
            assert !r.volatileSeen;
            UiRetain.noteCall(add, list, new Object[] {3});
            UiRetain.noteAnswer(true);                         // an answer nobody can re-read
            UiRetain.callExit();
            assert r.volatileSeen;
            r.volatileSeen = false;
            Object[] arr = {"a", 1.0};
            UiRetain.noteCall(size, list, new Object[0]);
            UiRetain.noteAnswer(arr);
            UiRetain.callExit();
            arr[0] = "changed";
            assert !r.volatileSeen && ((Object[]) r.calls[0].answer)[0].equals("a");   // a copy
        } finally { UiRetain.closeForTest(); }
        assert UiRetain.sameAnswer(new String[] {"a"}, new String[] {new String("a")});
        assert !UiRetain.sameAnswer(new String[] {"a"}, new String[] {"b"}) && !UiRetain.sameAnswer(new String[] {"a"}, new String[0]);
        assert UiRetain.sameAnswer(new Object[] {1.0, new String[] {"x"}}, new Object[] {1.0, new String[] {"x"}});
    }

    static void retainRenderWriteIsVolatile() {
        se.krka.kahlua.j2se.KahluaTableImpl t = new se.krka.kahlua.j2se.KahluaTableImpl(new HashMap<>());
        t.rawset("k", 1.0);
        UiRetain.Record r = new UiRetain.Record();
        UiRetain.openForTest(r);
        try {
            UiRetain.noteWrite(t, "k", 1.0);        // idempotent: the same value again
            assert !r.volatileSeen;
            UiRetain.noteWrite(t, "k", 2.0);
            assert r.volatileSeen;
            r.volatileSeen = false;
            UiRetain.noteWrite(t, "fresh", 1.0);    // a new key
            assert r.volatileSeen && r.reads.size() == 0;   // the check read itself was not noted
        } finally { UiRetain.closeForTest(); }
    }

    // enter/exit stay balanced and nothing is kept when an error was logged or the render threw
    static void retainBalanceOnErrorAndThrow() {
        zombie.ui.UIElement root = new zombie.ui.UIElement(), kid = new zombie.ui.UIElement();
        GenericSpriteRenderState s = state();
        put(s, 0, TextureDraw.Type.glDraw, 1f);
        s.numSprites = 1;
        var saved = UiRetain.populating;
        boolean savedAny = UiRetain.any;
        UiRetain.populating = () -> s;
        UiRetain.any = true;
        try {
            for (int mode = 0; mode < 3; mode++) {   // 0 clean, 1 error noted, 2 threw
                UiRetain.Record r = new UiRetain.Record();
                r.geometry = UiRetain.Geometry.of(root);
                UiRetain.openForTest(r, root, s);
                s.numSprites = 1;
                assert !UiRetain.onRenderEnter(kid) && UiRetain.depthForTest() == 1;
                if (mode == 1) UiRetain.noteError();
                UiRetain.onRenderExit(kid, false);
                assert UiRetain.depthForTest() == 0 && UiRetain.openForTest();
                UiRetain.onRenderExit(root, mode == 2);
                assert !UiRetain.openForTest() && UiRetain.depthForTest() == 0 && !UiRetain.noting;
                assert (r.slice != null) == (mode == 0) : "mode " + mode;
                assert r.nKids == 1;
            }
        } finally { UiRetain.populating = saved; UiRetain.any = savedAny; UiRetain.closeForTest(); }
    }

    static void retainExcludeAndModsFilter() {
        Set<String> ex = UiRetain.parseNames(" ObjectTooltip, ISToolTip ,,ISToolTipInv");
        assert ex.equals(Set.of("ObjectTooltip", "ISToolTip", "ISToolTipInv"));
        assert UiRetain.excluded(ex, "ObjectTooltip", null) && UiRetain.excluded(ex, "UIElement", "ISToolTipInv");
        assert !UiRetain.excluded(ex, "UIElement", "ISPanel") && !UiRetain.excluded(ex, "UIElement", null);
        assert UiRetain.gameFile(null) && UiRetain.gameFile("media/lua/client/ISUI/ISInventoryPane.lua");
        assert UiRetain.gameFile("/home/u/ProjectZomboid/projectzomboid/media/lua/client/ISUI/ISPanel.lua");
        assert !UiRetain.gameFile("mods/x/media/lua/client/MyWin.lua");
        assert !UiRetain.gameFile("/steam/steamapps/workshop/content/108600/1/mods/Y/media/lua/client/W.lua");
        assert !UiRetain.gameFile("C:\\steam\\steamapps\\workshop\\content\\1\\mods\\Y\\media\\lua\\W.lua");
        assert !UiRetain.gameFile("somewhere/else.lua");
    }

    static void retainSettingsAndInit() {
        Settings.load(SelfTest.write("enabled=true\nuiRetainExclude=A,B\n"));
        assert Settings.text("uiRetainExclude", "x").equals("A,B") && Settings.text("nope", "d").equals("d");
        assert Settings.flag("uiVerify") && Settings.flag("uiRetainedMods") && !Settings.flag("uiRetained") && !Settings.flag("uiRetainCheck");
        UiRetain.init();
        assert !UiRetain.guard.on && !UiRetain.check.on;
        Settings.load(SelfTest.write("enabled=true\nuiRetained=true\n"));
        UiRetain.init();
        assert UiRetain.guard.on && UiRetain.excludedNames().equals(Set.of("ObjectTooltip", "ISToolTip", "ISToolTipInv"));
        Settings.load(SelfTest.write("enabled=true\nuiRetained=true\nuiVerify=false\n"));
        UiRetain.init();
        assert !UiRetain.guard.on;   // uiRetained needs uiVerify: one log line, retain stays off
        Settings.load(SelfTest.write("enabled=false\n"));
        UiRetain.init();
        assert !UiRetain.guard.on;
    }

    // a replay that throws, again and again until the guard trips, leaves the queue, the open state and the read set clean
    static void retainReplayThrowLeavesNothingOpen() {
        GenericSpriteRenderState s = state();
        put(s, 0, TextureDraw.Type.glDraw, 1f);
        UiSlice bad = UiSlice.record(s, 0, 1, null);
        bad.entries = null;   // replay throws
        UiRetain.Record r = record();
        boolean g = UiRetain.guard.on, c = UiRetain.check.on, a = UiRetain.any;
        UiRetain.guard.on = true;
        UiRetain.any = true;
        try {
            for (int i = 0; i < 5; i++) {
                s.numSprites = 3;
                UiRetain.openForTest(r, new Object(), s);
                UiRetain.noteRead(new Object(), "k", 1.0);
                assert !UiRetain.tryReplay(r, bad, s);
                assert s.numSprites == 3 && !UiRetain.noting && !UiRetain.openForTest() && UiRetain.depthForTest() == 0;
            }
            assert !UiRetain.guard.on && !UiRetain.any;
            UiRetain.openForTest(r, new Object(), s);
            UiRetain.noteRead(new Object(), "k", 1.0);
            r.reads.clear();
            assert UiRetain.noting;   // sanity: openForTest itself still opens
        } finally { UiRetain.closeForTest(); UiRetain.guard.on = g; UiRetain.check.on = c; UiRetain.any = a; }
        assert !UiRetain.noting;
    }

    // ---- uiRetainedChildren ----

    static void retainControlsSnapshot() {
        zombie.ui.UIElement a = new zombie.ui.UIElement(), b = new zombie.ui.UIElement(), c = new zombie.ui.UIElement();
        Object[] rec = {a, b};
        assert UiRetain.sameControls(rec, new java.util.ArrayList<>(List.of(a, b)));
        assert UiRetain.sameControls(new Object[0], new java.util.ArrayList<>()) && UiRetain.sameControls(new Object[0], null);
        assert !UiRetain.sameControls(rec, new java.util.ArrayList<>(List.of(a)));          // one removed
        assert !UiRetain.sameControls(rec, new java.util.ArrayList<>(List.of(a, b, c)));    // one added
        assert !UiRetain.sameControls(rec, new java.util.ArrayList<>(List.of(b, a)));       // swapped
        assert !UiRetain.sameControls(rec, new java.util.ArrayList<>(List.of(a, c)));       // replaced
    }

    private static UiSlice seg(GenericSpriteRenderState s, float x) {
        s.numSprites = 0;
        put(s, 0, TextureDraw.Type.glDraw, x);
        return UiSlice.record(s, 0, 1, null);
    }

    // a parent with segments [p0][c1][p1][c2][p2] replays p0, calls c1, p1, calls c2, p2, with the recorded stencil at each step
    static void segmentsReplayOrder() {
        GenericSpriteRenderState s = state();
        UiRetain.Record r = new UiRetain.Record();
        zombie.ui.UIElement c1 = new zombie.ui.UIElement(), c2 = new zombie.ui.UIElement();
        UiSlice p0 = seg(s, 10f), p1 = seg(s, 11f), p2 = seg(s, 12f);
        r.addKid(c1);
        r.addKid(c2);
        r.putSeg(0, p0, 1, 2);
        r.putSeg(1, p1, 2, 3);
        r.putSeg(2, p2, 3, 4);
        r.stencilOut = 4;
        r.slice = p0;
        s.numSprites = 0;
        List<String> log = new java.util.ArrayList<>();
        UiRetain.walk(r, s, kid -> {
            log.add(kid == c1 ? "c1@" + UiRetain.stencilForTest() : "c2@" + UiRetain.stencilForTest());
            put(s, s.numSprites, TextureDraw.Type.glDraw, kid == c1 ? 20f : 21f);   // the child draws live
            s.numSprites++;
            UiRetain.setStencilForTest(UiRetain.stencilForTest());   // balanced child
        });
        assert log.equals(List.of("c1@2", "c2@3")) : log;
        assert s.numSprites == 5;
        float[] want = {10f, 20f, 11f, 21f, 12f};
        for (int i = 0; i < 5; i++) assert s.sprite[i].x0 == want[i] : i + " " + s.sprite[i].x0;
        assert UiRetain.stencilForTest() == 4 && r.slice != null;
        // a child that leaves another stencil level than recorded: the parent is drawn again next frame
        s.numSprites = 0;
        UiRetain.walk(r, s, kid -> UiRetain.setStencilForTest(9));
        assert r.slice == null;
        UiRetain.setStencilForTest(0);
    }

    // ---- fake hierarchy: the advice around UIElement.render, with bodies standing in for the Lua prerender/render ----

    private static final class Rig {
        final GenericSpriteRenderState s = state();
        final Map<zombie.ui.UIElement, Runnable> bodies = new HashMap<>();
        final Map<zombie.ui.UIElement, Integer> ran = new HashMap<>();
        final List<zombie.ui.UIElement> order = new java.util.ArrayList<>();

        void draw(float x) { put(s, s.numSprites, TextureDraw.Type.glDraw, x); s.numSprites++; }

        /** Stock render of a parent: own draw, the children loop, own draw. */
        Runnable parent(zombie.ui.UIElement e, float before, float after) {
            return () -> {
                draw(before);
                java.util.ArrayList<zombie.ui.UIElement> cs = e.getControls();
                for (int i = 0; i < cs.size(); i++) render(cs.get(i));
                draw(after);
            };
        }

        Runnable leaf(float x) { return () -> draw(x); }

        void render(zombie.ui.UIElement e) {
            Object r = UiPatches.onElementRenderEnter(e);
            Throwable thrown = null;
            try {
                if (r == null) {
                    ran.merge(e, 1, Integer::sum);
                    order.add(e);
                    if (Boolean.TRUE.equals(e.isVisible())) bodies.get(e).run();
                }
            } finally { UiPatches.onElementRenderExit(e, r, thrown); }
        }

        int count(zombie.ui.UIElement e) { return ran.getOrDefault(e, 0); }

        float[] xs() {
            float[] x = new float[s.numSprites];
            for (int i = 0; i < x.length; i++) x[i] = s.sprite[i].x0;
            return x;
        }

        void frame(zombie.ui.UIElement top) {
            s.numSprites = 0;
            UiTiming.uiEnter();
            render(top);
            UiTiming.uiExit();
        }
    }

    private interface Body { void run(Rig rig) throws Exception; }

    private static void withChildren(Rig rig, Body b) {
        var savedPop = UiRetain.populating;
        var savedPlaying = UiRetain.playing;
        var savedRender = UiRetain.renderChild;
        boolean any = UiRetain.any, ch = UiRetain.children, g = UiRetain.guard.on, c = UiRetain.check.on, t = UiTiming.guard.on;
        Thread mt = FrameClock.mainThread;
        FrameClock.mainThread = Thread.currentThread();
        UiRetain.closeForTest();
        UiRetain.populating = () -> rig.s;
        UiRetain.playing = () -> true;
        UiRetain.renderChild = rig::render;
        UiRetain.guard.on = true;
        UiRetain.check.on = false;
        UiRetain.any = true;
        UiRetain.children = true;
        UiTiming.guard.on = true;
        UiRetain.bumpEpoch();
        try { b.run(rig); }
        catch (Exception e) { throw new AssertionError(e); }
        finally {
            UiRetain.closeForTest();
            UiRetain.populating = savedPop; UiRetain.playing = savedPlaying; UiRetain.renderChild = savedRender;
            UiRetain.any = any; UiRetain.children = ch; UiRetain.guard.on = g; UiRetain.check.on = c; UiTiming.guard.on = t;
            FrameClock.mainThread = mt;
            UiRetain.bumpEpoch();
        }
    }

    private static void balanced() {
        assert UiRetain.stackForTest() == 0 && !UiRetain.noting && !UiRetain.openForTest() && UiRetain.depthForTest() == 0;
        assert UiTiming.depthForTest() == 0;
    }

    // a clean parent replays its own pieces and calls its children in order; the children decide for themselves; everything stays balanced
    static void retainChildrenReplayBalanced() {
        Rig rig = new Rig();
        zombie.ui.UIElement p = new zombie.ui.UIElement(), c1 = new zombie.ui.UIElement(), c2 = new zombie.ui.UIElement();
        p.AddChild(c1);
        p.AddChild(c2);
        rig.bodies.put(p, rig.parent(p, 1f, 5f));
        rig.bodies.put(c1, rig.leaf(2f));
        rig.bodies.put(c2, rig.leaf(3f));
        withChildren(rig, r -> {
            r.frame(p);
            balanced();
            assert Arrays.equals(r.xs(), new float[] {1f, 2f, 3f, 5f}) : Arrays.toString(r.xs());
            assert r.count(p) == 1 && r.count(c1) == 1 && r.count(c2) == 1;
            r.frame(p);   // nothing changed: all three replay (the parent by its pieces, the children by the calls)
            balanced();
            assert Arrays.equals(r.xs(), new float[] {1f, 2f, 3f, 5f}) : Arrays.toString(r.xs());
            assert r.count(p) == 1 && r.count(c1) == 1 && r.count(c2) == 1 : "replayed " + r.count(p) + r.count(c1) + r.count(c2);
            c2.setX(40.0);   // one child moved: the parent runs stock, the other child still replays
            r.frame(p);
            balanced();
            assert r.count(p) == 2 && r.count(c1) == 1 && r.count(c2) == 2;
            assert Arrays.equals(r.xs(), new float[] {1f, 2f, 3f, 5f});
            r.frame(p);   // and settles again
            balanced();
            assert r.count(p) == 2 && r.count(c1) == 1 && r.count(c2) == 2;
            c1.setVisible(false);   // a hidden child: the parent runs stock again
            r.frame(p);
            balanced();
            assert r.count(p) == 3 && Arrays.equals(r.xs(), new float[] {1f, 3f, 5f}) : Arrays.toString(r.xs());
            c1.setVisible(true);
            r.frame(p);
            balanced();
            assert r.count(p) == 4 && Arrays.equals(r.xs(), new float[] {1f, 2f, 3f, 5f});
            // the child is called once per replayed parent and in list order
            r.order.clear();
            r.frame(p);
            assert r.order.isEmpty();
        });
    }

    // a child whose class overrides render() draws itself on every parent replay; the parent stays retainable (not with children off)
    static void retainOverrideChildDoesNotPoisonParent() {
        Rig rig = new Rig();
        zombie.ui.UIElement p = new zombie.ui.UIElement(), o = new Over(), leaf = new zombie.ui.UIElement();
        p.AddChild(leaf);
        p.AddChild(o);
        rig.bodies.put(p, rig.parent(p, 1f, 5f));
        rig.bodies.put(leaf, rig.leaf(2f));
        float[] n = {3f};
        rig.bodies.put(o, () -> { rig.draw(n[0]); n[0] += 1f; });   // an animated Java render
        withChildren(rig, r -> {
            r.frame(p);
            r.frame(p);
            balanced();
            assert r.count(p) == 1 && r.count(leaf) == 1 : "parent replays: " + r.count(p);
            assert r.count(o) == 2;
            assert Arrays.equals(r.xs(), new float[] {1f, 2f, 4f, 5f}) : Arrays.toString(r.xs());
            r.frame(p);
            assert r.count(p) == 1 && r.count(o) == 3 && Arrays.equals(r.xs(), new float[] {1f, 2f, 5f, 5f});
            // with children off, any override in the tree makes the record volatile
            UiRetain.children = false;
            UiRetain.bumpEpoch();
            r.ran.clear();
            r.frame(p);
            r.frame(p);
            balanced();
            assert r.count(p) == 2 : "children off: " + r.count(p);
        });
    }

    // a control the render never entered (an override that does not call super.render) makes the parent run stock
    static void retainChildrenUnobservedControlIsLive() {
        Rig rig = new Rig();
        zombie.ui.UIElement p = new zombie.ui.UIElement(), ghost = new zombie.ui.UIElement();
        p.AddChild(ghost);
        rig.bodies.put(p, () -> { rig.draw(1f); rig.draw(2f); });   // draws its own, never renders the control
        withChildren(rig, r -> {
            r.frame(p);
            r.frame(p);
            balanced();
            assert r.count(p) == 2;
        });
    }

    // a child that changes the stencil level on its way makes the parent run stock
    static void retainChildrenUnbalancedStencilIsLive() {
        Rig rig = new Rig();
        zombie.ui.UIElement p = new zombie.ui.UIElement(), c = new zombie.ui.UIElement();
        p.AddChild(c);
        rig.bodies.put(p, rig.parent(p, 1f, 5f));
        rig.bodies.put(c, () -> { rig.draw(2f); UiRetain.setStencilForTest(UiRetain.stencilForTest() + 1); });
        withChildren(rig, r -> {
            UiRetain.setStencilForTest(0);
            r.frame(p);
            UiRetain.setStencilForTest(0);
            r.frame(p);
            balanced();
            assert r.count(p) == 2 && r.count(c) == 1 && UiRetain.stencilForTest() == 1;   // the child itself replays, with its level
            UiRetain.setStencilForTest(0);
        });
    }

    static void retainChildrenSetting() {
        Settings.load(SelfTest.write("enabled=true\nuiRetained=true\n"));
        assert !Settings.flag("uiRetainedChildren");
        UiRetain.init();
        assert UiRetain.guard.on && !UiRetain.children;
        Settings.load(SelfTest.write("enabled=true\nuiRetained=true\nuiRetainedChildren=true\n"));
        UiRetain.init();
        assert UiRetain.children;
        Settings.load(SelfTest.write("enabled=true\nuiRetainedChildren=true\n"));
        UiRetain.init();
        assert !UiRetain.children;   // nothing to refine without uiRetained
        Settings.load(SelfTest.write("enabled=false\n"));
        UiRetain.init();
        assert !UiRetain.children;
    }
}
