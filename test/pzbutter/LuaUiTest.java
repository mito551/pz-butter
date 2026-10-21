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
