package pzbutter;

import java.lang.invoke.VarHandle;

import me.zed_0xff.zombie_buddy.Patch;
import pzbutter.luabudget.Budget;
import se.krka.kahlua.j2se.KahluaTableImpl;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.LuaClosure;
import zombie.core.Core;

/**
 * Lua-side hooks, one holder per game method; each advice is one call to a public static method here
 * (advice is inlined into the game class, so it may only reach public members).
 */
public final class LuaPatches {
    private LuaPatches() {}

    /** Event.trigger entry / exit (main thread): which event the handlers below belong to. */
    public static void onTriggerEnter(Object event) {
        Budget.eventEnter(event);
        if (LuaTiming.guard.on) LuaTiming.eventEnter(event);
    }

    public static void onTriggerExit() {
        Budget.eventExit();
        if (LuaTiming.guard.on) LuaTiming.eventExit();
    }

    /** LuaCaller.protectedCallVoid entry (all overloads): true skips the call. a2 is the third argument. */
    public static boolean onCall(Object fn, Object a2) {
        boolean skip = false;
        try {
            if (LuaFast.skipEmpty && fn instanceof LuaClosure c && LuaFast.isEmpty(c.prototype)) skip = true;
            else if (a2 instanceof Object[] args) skip = Budget.shouldSkip(fn, args);
            if (LuaTiming.guard.on) LuaTiming.callEnter(fn, skip);
        } catch (Throwable t) { LuaTiming.guard.fail(t); }
        return skip;
    }

    /** LuaCaller.protectedCallVoid exit (always runs, also after a skip). */
    public static void onCallExit() {
        if (!LuaTiming.guard.on) return;
        try { LuaTiming.callExit(); } catch (Throwable t) { LuaTiming.guard.fail(t); }
    }

    /** LuaCaller.pcallvoid entry (all overloads): true skips an empty function. */
    public static boolean onPcall(Object fn) {
        try { return LuaFast.skipEmpty && fn instanceof LuaClosure c && LuaFast.isEmpty(c.prototype); }
        catch (Throwable t) { return false; }
    }

    // ---- luaRawgetFast, luaInternConstants ----

    /** rawget's answer is stock's: run the original. */
    public static final Object NOT_FAST = new Object();
    static final Guard rawgetGuard = new Guard("luaRawgetFast");
    /** Bound once by class initialisation (thread-safe; rawget runs on more than the main thread), final so the JIT folds them. */
    private static final class Table {
        static final VarHandle RELOAD_REPLACE = Game.field(KahluaTableImpl.class, "reloadReplace", KahluaTable.class);
        static final VarHandle METATABLE = Game.field(KahluaTableImpl.class, "metatable", KahluaTable.class);
    }

    /** KahluaTableImpl.rawget entry: the value (possibly null), or NOT_FAST to run stock. */
    public static Object rawgetFast(Object self, Object key) {
        if (!LuaFast.rawgetFast || !rawgetGuard.on) return NOT_FAST;
        try {
            if (Table.RELOAD_REPLACE == null || Table.METATABLE == null) { rawgetGuard.on = false; return NOT_FAST; }
            KahluaTableImpl t = (KahluaTableImpl) self;
            if (!LuaFast.fastAllowed((KahluaTable) Table.RELOAD_REPLACE.get(t) != null, key, Core.debug)) return NOT_FAST;
            Object v = LuaFast.rawgetFast(t.delegate, key);
            if (v != LuaFast.MISS) return v;
            KahluaTable meta = (KahluaTable) Table.METATABLE.get(t);
            return meta == null ? null : meta.rawget(key);   // stock: a missing key asks the metatable
        } catch (Throwable x) { rawgetGuard.fail(x); return NOT_FAST; }
    }

    /** The Kahlua compiler's load methods exit: intern the new chunk's string constants. */
    public static void onCompiled(Object closure) {
        if (!LuaFast.internConstants) return;
        try { if (closure instanceof LuaClosure c) LuaFast.internConstants(c.prototype); }
        catch (Throwable t) { /* interning is an optimisation only */ }
    }

    /** rawget's answer, after the fast path: a table read counts for the open UI element (uiCensus, uiRetained). */
    public static void onRawgetAnswer(Object table, Object key, Object value) {
        if (UiRetain.noting) { try { UiRetain.noteRead(table, key, value); } catch (Throwable t) { UiRetain.fail(t); } }
        if (!UiCensus.active()) return;
        try { UiCensus.noteRead(key, value); } catch (Throwable t) { UiCensus.guard.fail(t); }
    }

    /** KahluaTableImpl.iterator entry: iterating a table makes the open UI element live (uiCensus). */
    public static void onIterate() {
        if (UiRetain.noting) { try { UiRetain.noteVolatile(); } catch (Throwable t) { UiRetain.fail(t); } }
        if (!UiCensus.active()) return;
        try { UiCensus.noteIterate(); } catch (Throwable t) { UiCensus.guard.fail(t); }
    }

    @Patch(className = "se.krka.kahlua.j2se.KahluaTableImpl", methodName = "rawget")
    public static class Rawget {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object self, @Patch.Argument(0) Object key, @Patch.Local("v") Object v) {
            v = rawgetFast(self, key);
            return v != NOT_FAST;
        }

        @Patch.OnExit
        public static void exit(@Patch.This Object self, @Patch.Argument(0) Object key, @Patch.Return(readOnly = false) Object ret,
                                @Patch.Local("v") Object v) {
            if (v != NOT_FAST) ret = v;
            onRawgetAnswer(self, key, ret);
        }
    }

    @Patch(className = "se.krka.kahlua.j2se.KahluaTableImpl", methodName = "iterator")
    public static class Iterate {
        @Patch.OnEnter
        public static void enter() { onIterate(); }
    }

    @Patch(className = "se.krka.kahlua.luaj.compiler.LuaCompiler", methodName = "loadis")
    public static class LoadStream {
        @Patch.OnExit
        public static void exit(@Patch.Return Object closure) { onCompiled(closure); }
    }

    @Patch(className = "se.krka.kahlua.luaj.compiler.LuaCompiler", methodName = "loadstring")
    public static class LoadString {
        @Patch.OnExit
        public static void exit(@Patch.Return Object closure) { onCompiled(closure); }
    }

    // ---- the PZButter global for pz-butter's own Lua ----

    static volatile boolean uiLuaFast;

    /** LuaManager.LoadDirBase entry (the Lua environment exists, the Lua files are about to load). */
    public static void onLuaLoad() {
        try {
            KahluaTableImpl t = new KahluaTableImpl(new java.util.HashMap<>());
            t.rawset("uiLuaFast", uiLuaFast);
            zombie.Lua.LuaManager.env.rawset("PZButter", t);
        } catch (Throwable x) { System.out.println("[PZButter] Lua settings table not set: " + x); }
    }

    @Patch(className = "zombie.Lua.LuaManager", methodName = "LoadDirBase")
    public static class LuaLoad {
        @Patch.OnEnter
        public static void enter() { onLuaLoad(); }
    }

    @Patch(className = "zombie.Lua.Event", methodName = "trigger")
    public static class Trigger {
        @Patch.OnEnter
        public static void enter(@Patch.This Object event) { onTriggerEnter(event); }

        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit() { onTriggerExit(); }
    }

    // every overload: (thread, fn, arg) ... (thread, fn, Object[]); the third argument is typed Object for all
    @Patch(className = "se.krka.kahlua.integration.LuaCaller", methodName = "protectedCallVoid")
    public static class Call {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(1) Object fn, @Patch.Argument(2) Object a2) { return onCall(fn, a2); }

        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit() { onCallExit(); }
    }

    @Patch(className = "se.krka.kahlua.integration.LuaCaller", methodName = "pcallvoid")
    public static class Pcall {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(1) Object fn) { return onPcall(fn); }
    }
}
