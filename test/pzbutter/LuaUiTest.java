package pzbutter;

import se.krka.kahlua.vm.Prototype;

/** Plan 3a (Lua and light UI) checks. Run from SelfTest.main; failures are AssertionErrors. */
final class LuaUiTest {
    private LuaUiTest() {}

    static void run() {
        emptyClosureOnlyBareReturn();
        timingTopTen();
        rawgetFastSame();
        internWalksTree();
        staggerEveryWindowWithin100ms();
        staggerForgetsClosedWindows();
        textWidthCacheHit();
        textWidthCacheBounded();
        textWidthCacheClearedOnFontReload();
        streetMemoClearedOnViewChange();
        streetIndexMatchesIndexOf();
        streetCacheReusedWhileViewSame();
        visitedRowsSameBytes();
    }

    // a street's length is kept for the projection's linear part; another zoom or rotation is a miss
    static void streetMemoClearedOnViewChange() {
        MapFast.clear();
        Object street = new Object();
        double[] view = {2, 0, 0, 2};
        assert MapFast.length(street, view) == null;
        MapFast.putLength(street, view, 123.5f);
        assert MapFast.length(street, view) == 123.5f;
        assert MapFast.length(street, new double[] {3, 0, 0, 3}) == null;
        assert MapFast.length(street, new double[] {2, 1, 0, 2}) == null;   // rotated / isometric
    }

    // the index map answers what ArrayList.indexOf answers, and is rebuilt for a changed list
    static void streetIndexMatchesIndexOf() {
        MapFast.clear();
        java.util.ArrayList<Object> list = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) list.add(new Object());
        for (Object o : list) assert MapFast.indexOf(list, o) == list.indexOf(o);
        assert MapFast.indexOf(list, new Object()) == -1;
        Object added = new Object();
        list.add(0, added);
        MapFast.newSort();
        assert MapFast.indexOf(list, added) == 0 && MapFast.indexOf(list, list.get(10)) == 10;
    }

    // a label layout is rebuilt only when its view key changes or the street data is dirty
    static void streetCacheReusedWhileViewSame() {
        MapFast.clear();
        Object data = new Object();
        MapFast.ViewKey k = new MapFast.ViewKey(new double[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10}, new Object[] {"en", null});
        assert MapFast.shouldInit(data, k, false);
        assert !MapFast.shouldInit(data, new MapFast.ViewKey(new double[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10}, new Object[] {"en", null}), false);
        assert MapFast.shouldInit(data, k, true);   // dirty data always rebuilds
        assert MapFast.shouldInit(data, new MapFast.ViewKey(new double[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 11}, new Object[] {"en", null}), false);
        assert MapFast.shouldInit(new Object(), k, false);   // another render-data object has its own layout
    }

    // explored-area texture: the row-at-a-time fill writes exactly the bytes the per-byte fill writes
    static void visitedRowsSameBytes() {
        int w = 40, h = 30;
        java.util.function.IntBinaryOperator flags = (x, y) -> (x * 7 + y * 3) % 4;
        java.nio.ByteBuffer a = java.nio.ByteBuffer.allocate(w * h * 4), b = java.nio.ByteBuffer.allocate(w * h * 4);
        int x1 = 3, x2 = 20, y1 = 2, y2 = 17;
        for (int y = y1; y <= y2; y++) {   // reference: per byte, as described in the plan's hook table
            a.position((1 + x1) * 4 + (1 + y) * w * 4);
            for (int x = x1; x <= x2; x++) {
                int f = flags.applyAsInt(x, y);
                a.put((byte) ((f & 2) != 0 ? 0 : -1)); a.put((byte) ((f & 1) != 0 ? 0 : -1)); a.put((byte) -1); a.put((byte) -1);
            }
        }
        a.position(0);
        MapFast.fillRows(b, w, x1, x2, y1, y2, flags);
        assert b.position() == 0 && java.util.Arrays.equals(a.array(), b.array());
    }

    // a width stored for (font object, text) comes back; another font object or text is a miss
    static void textWidthCacheHit() {
        TextWidths.clear();
        Object font = new Object(), other = new Object();
        assert TextWidths.get(font, "Loot All") == null;
        TextWidths.put(font, "Loot All", 57);
        assert TextWidths.get(font, "Loot All") == 57;
        assert TextWidths.get(other, "Loot All") == null && TextWidths.get(font, "Loot all") == null;
    }

    // never more than MAX entries: when full it starts over
    static void textWidthCacheBounded() {
        TextWidths.clear();
        Object font = new Object();
        for (int i = 0; i <= TextWidths.MAX; i++) TextWidths.put(font, "label " + i, i);
        assert TextWidths.size() <= TextWidths.MAX : TextWidths.size();
    }

    // fonts reloaded (TextManager.Init): everything is dropped
    static void textWidthCacheClearedOnFontReload() {
        Object font = new Object();
        TextWidths.put(font, "x", 5);
        TextWidths.clear();
        assert TextWidths.size() == 0 && TextWidths.get(font, "x") == null;
    }

    // six windows over one second at 120 fps: each ticks ~10 times, never twice within 90 ms, and the six don't
    // all tick in the same frame (stock: all six in one frame every 100 ms)
    static void staggerEveryWindowWithin100ms() {
        UiTick.reset();
        Object[] w = new Object[6];
        for (int i = 0; i < w.length; i++) w[i] = new Object();
        int[] ticks = new int[w.length];
        long[] last = new long[w.length];
        java.util.Arrays.fill(last, -1_000_000);
        java.util.Set<Integer> framesWithTicks = new java.util.HashSet<>();
        for (int f = 0; f < 120; f++) {
            long now = 10_000 + f * 1000L / 120;
            for (int i = 0; i < w.length; i++) {
                if (!UiTick.decide(w[i], now)) continue;
                assert now - last[i] >= 90 || ticks[i] == 0 : "window " + i + " ticked twice within 90 ms";
                last[i] = now;
                ticks[i]++;
                framesWithTicks.add(f);
            }
        }
        for (int t : ticks) assert t >= 9 && t <= 11 : t;
        assert framesWithTicks.size() >= 50 : framesWithTicks.size();   // spread over frames, not bunched
    }

    // closed windows (unreachable) leave the stagger state
    static void staggerForgetsClosedWindows() {
        UiTick.reset();
        for (int i = 0; i < 50; i++) UiTick.decide(new Object(), 1000);
        for (int i = 0; i < 20 && UiTick.size() > 0; i++) { System.gc(); SelfTest.sleep(10); }
        assert UiTick.size() == 0 : UiTick.size();
    }

    // one get(): a stored value is returned; a missing key says MISS, so the caller asks the metatable as stock
    // does (stock: !containsKey && metatable != null -> metatable.rawget(key)). rawset(k, null) removes the key,
    // so a null value means missing.
    static void rawgetFastSame() {
        java.util.HashMap<Object, Object> m = new java.util.HashMap<>();
        m.put("a", 1.0);
        assert LuaFast.rawgetFast(m, "a").equals(1.0);
        assert LuaFast.rawgetFast(m, "b") == LuaFast.MISS && LuaFast.rawgetFast(m, 2.0) == LuaFast.MISS;
        assert LuaFast.fastAllowed(false, "k", false);
        assert !LuaFast.fastAllowed(true, "k", false) && !LuaFast.fastAllowed(false, null, false) && !LuaFast.fastAllowed(false, "k", true);
        assert !LuaFast.fastAllowed(false, 3, false);   // rawget(int) arrives boxed: leave it to stock (it converts to Double)
    }

    // every String constant in a compiled chunk's prototype tree becomes the interned instance
    static void internWalksTree() {
        Prototype leaf = proto(0x0080001e), mid = proto(0x0080001e), root = proto(0x0080001e);
        leaf.constants = new Object[] {new String("update"), 1.0};
        mid.constants = new Object[] {new String("update"), new String("render")};
        root.constants = new Object[] {new String("render")};
        mid.prototypes = new Prototype[] {leaf};
        root.prototypes = new Prototype[] {mid};
        LuaFast.internConstants(root);
        assert leaf.constants[0] == "update" && mid.constants[0] == "update" && mid.constants[1] == "render" && root.constants[0] == "render";
        assert leaf.constants[1].equals(1.0);
        LuaFast.internConstants(null);   // no throw
    }

    static Prototype proto(int... code) {
        Prototype p = new Prototype();
        p.code = code;
        return p;
    }

    // compiled by the game's Kahlua: function() end = { 0x0080001e } (RETURN A=0 B=1), whatever the parameters
    static void emptyClosureOnlyBareReturn() {
        assert LuaFast.isEmpty(proto(0x0080001e));
        assert !LuaFast.isEmpty(proto(0x00000001, 0x0080001e));                // local y = 1
        assert !LuaFast.isEmpty(proto(0x0100001e));                            // return with a value (B=2)
        assert !LuaFast.isEmpty(proto(0x00000001, 0x0100001e, 0x0080001e));    // return 1
        assert !LuaFast.isEmpty(new Prototype()) && !LuaFast.isEmpty(null);
    }

    // twelve handlers: the report keeps the ten slowest (ms per second), slowest first
    static void timingTopTen() {
        LuaTiming.Stat[] s = new LuaTiming.Stat[12];
        for (int i = 0; i < s.length; i++) {
            s[i] = new LuaTiming.Stat("media/lua/client/H" + i + ".lua", 10 + i, "OnTick");
            s[i].ns = (i + 1) * 1_000_000L;
            s[i].calls = 60;
        }
        java.util.List<String> lines = LuaTiming.reportLines(java.util.Arrays.asList(s), 60_000_000_000L);
        assert lines.size() == 10 : lines.size();
        assert lines.get(0).equals("[PZButter] lua:   0.200 ms/s  OnTick               60 calls  media/lua/client/H11.lua:21") : lines.get(0);
        assert lines.get(9).contains("H2.lua:12");
    }
}
