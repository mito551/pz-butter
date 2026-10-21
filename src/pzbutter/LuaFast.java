package pzbutter;

import se.krka.kahlua.vm.Prototype;

/**
 * Lua speed-ups that change nothing a script can observe.
 * luaSkipEmpty (SPEC 3.9): a Lua function whose whole code is one bare RETURN (function() end, whatever its
 * parameters) is not called. The game's Kahlua compiles it to the single instruction 0x0080001e (RETURN A=0 B=1).
 * luaRawgetFast (SPEC 4.11, Smooth Frames 3.2): KahluaTableImpl.rawget does containsKey then get; one get answers
 * the same, since rawset(k, null) removes the key (no null values). A missing key still goes to the metatable.
 * luaInternConstants (SPEC 3.9): String constants of every compiled chunk are interned, so table lookups with a
 * constant key find an identical key object first.
 */
final class LuaFast {
    static final int EMPTY_RETURN = 0x0080001e;
    static volatile boolean skipEmpty, rawgetFast, internConstants;
    static final Object MISS = new Object();

    private LuaFast() {}

    static void init() {
        skipEmpty = Settings.flag("luaSkipEmpty");
        rawgetFast = Settings.flag("luaRawgetFast");
        LuaPatches.rawgetGuard.on = rawgetFast;
        internConstants = Settings.flag("luaInternConstants");
    }

    static boolean isEmpty(Prototype p) { return p != null && p.code != null && p.code.length == 1 && p.code[0] == EMPTY_RETURN; }

    /** The fast path applies: no reload redirect, a real key (not rawget(int)'s boxed Integer), not debugging. */
    static boolean fastAllowed(boolean hasReloadReplace, Object key, boolean debug) {
        return !hasReloadReplace && key != null && !(key instanceof Integer) && !debug;
    }

    /** One lookup: the value, or MISS when the key is not in the table. */
    static Object rawgetFast(java.util.Map<Object, Object> delegate, Object key) {
        Object v = delegate.get(key);
        return v != null ? v : MISS;
    }

    /** Interns every String constant in p and the prototypes nested in it. */
    static void internConstants(Prototype p) {
        if (p == null) return;
        Object[] c = p.constants;
        if (c != null) for (int i = 0; i < c.length; i++) if (c[i] instanceof String s) c[i] = s.intern();
        if (p.prototypes != null) for (Prototype q : p.prototypes) internConstants(q);
    }
}
