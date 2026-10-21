package pzbutter;

import se.krka.kahlua.vm.Prototype;

/** Plan 3a (Lua and light UI) checks. Run from SelfTest.main; failures are AssertionErrors. */
final class LuaUiTest {
    private LuaUiTest() {}

    static void run() {
        emptyClosureOnlyBareReturn();
        timingTopTen();
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
