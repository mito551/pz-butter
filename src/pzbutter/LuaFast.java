package pzbutter;

import se.krka.kahlua.vm.Prototype;

/**
 * Lua speed-ups that change nothing a script can observe.
 * luaSkipEmpty (SPEC 3.9): a Lua function whose whole code is one bare RETURN (function() end, whatever its
 * parameters) is not called. The game's Kahlua compiles it to the single instruction 0x0080001e (RETURN A=0 B=1).
 */
final class LuaFast {
    static final int EMPTY_RETURN = 0x0080001e;
    static volatile boolean skipEmpty;

    private LuaFast() {}

    static void init() { skipEmpty = Settings.flag("luaSkipEmpty"); }

    static boolean isEmpty(Prototype p) { return p != null && p.code != null && p.code.length == 1 && p.code[0] == EMPTY_RETURN; }
}
