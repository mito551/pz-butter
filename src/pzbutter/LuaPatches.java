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

    /** KahluaTableImpl.rawset entry (rawset(int, Object) ends here too): a write by the open UI window (uiRetained). */
    public static void onRawset(Object table, Object key, Object value) {
        if (!UiRetain.noting) return;
        try { UiRetain.noteWrite(table, key, value); } catch (Throwable t) { UiRetain.fail(t); }
    }

    @Patch(className = "se.krka.kahlua.j2se.KahluaTableImpl", methodName = "rawset")
    public static class Rawset {
        @Patch.OnEnter
        public static void enter(@Patch.This Object self, @Patch.Argument(0) Object key, @Patch.Argument(1) Object value) {
            onRawset(self, key, value);
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

    // ---- luaWorkerGate: the Lua token (Task 3) ----

    /**
     * Entry of every gated Lua entry point: true when this call took the Lua token (the exit must release it).
     * kt is the call's KahluaThread, or null for the static entries (they use LuaManager.thread).
     */
    public static boolean gateEnter(Object kt) {
        if (!LuaGate.guard.on) return false;
        try { return LuaGate.enter(kt instanceof se.krka.kahlua.vm.KahluaThread k ? k : zombie.Lua.LuaManager.thread); }
        catch (Throwable t) { LuaGate.guard.fail(t); return false; }
    }

    /**
     * Entry of LuaEventManager.triggerEvent: stock queues an event raised off the owner thread (safe), so a non-worker caller is never a
     * stray here and LuaManager is not read for it; only a frame worker takes the token (research Surprise 1).
     */
    public static boolean gateEnterEvent() {
        if (!LuaGate.guard.on) return false;
        try { return Thread.currentThread() instanceof LuaGate.Worker && LuaGate.enter(zombie.Lua.LuaManager.thread); }
        catch (Throwable t) { LuaGate.guard.fail(t); return false; }
    }

    /** Releases one hold taken by gateEnter; never checks the key (a hold taken before a switch-off is still released). */
    public static void gateExit() {
        try { LuaGate.exit(); } catch (Throwable t) { LuaGate.guard.fail(t); }
    }

    /** GameWindow.frameStep entry (game thread): claim the token, hand it to stray waiters. */
    public static void onFrameYield() {
        if (LuaGate.guard.on) try { LuaGate.frameYield(); FrameBatch.frameTick(); } catch (Throwable t) { LuaGate.guard.fail(t); }
    }

    // every overload: (thread, fn, arg) ... (thread, fn, Object[]); the third argument is typed Object for all
    @Patch(className = "se.krka.kahlua.integration.LuaCaller", methodName = "protectedCallVoid")
    public static class Call {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) Object kt, @Patch.Argument(1) Object fn, @Patch.Argument(2) Object a2,
                                    @Patch.Local("g") boolean g) {
            g = gateEnter(kt);
            return onCall(fn, a2);
        }

        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Patch.Local("g") boolean g) {
            onCallExit();
            if (g) gateExit();
        }
    }

    @Patch(className = "se.krka.kahlua.integration.LuaCaller", methodName = "pcallvoid")
    public static class Pcall {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) Object kt, @Patch.Argument(1) Object fn, @Patch.Local("g") boolean g) {
            g = gateEnter(kt);
            return onPcall(fn);
        }

        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Patch.Local("g") boolean g) { if (g) gateExit(); }
    }

    // the other LuaCaller names and the two static entries: gate only, bound to Object so every overload matches
    @Patch(className = "se.krka.kahlua.integration.LuaCaller", methodName = "pcallBoolean")
    public static class PcallBoolean {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(0) Object kt, @Patch.Local("g") boolean g) { g = gateEnter(kt); }

        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Patch.Local("g") boolean g) { if (g) gateExit(); }
    }

    @Patch(className = "se.krka.kahlua.integration.LuaCaller", methodName = "pcall")
    public static class PcallReturn {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(0) Object kt, @Patch.Local("g") boolean g) { g = gateEnter(kt); }

        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Patch.Local("g") boolean g) { if (g) gateExit(); }
    }

    @Patch(className = "se.krka.kahlua.integration.LuaCaller", methodName = "protectedCallBoolean")
    public static class ProtectedBoolean {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(0) Object kt, @Patch.Local("g") boolean g) { g = gateEnter(kt); }

        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Patch.Local("g") boolean g) { if (g) gateExit(); }
    }

    @Patch(className = "se.krka.kahlua.integration.LuaCaller", methodName = "protectedCall")
    public static class ProtectedCall {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(0) Object kt, @Patch.Local("g") boolean g) { g = gateEnter(kt); }

        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Patch.Local("g") boolean g) { if (g) gateExit(); }
    }

    @Patch(className = "zombie.Lua.LuaEventManager", methodName = "triggerEvent")
    public static class TriggerEvent {
        @Patch.OnEnter
        public static void enter(@Patch.Local("g") boolean g) { g = gateEnterEvent(); }

        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Patch.Local("g") boolean g) { if (g) gateExit(); }
    }

    @Patch(className = "zombie.Lua.LuaHookManager", methodName = "TriggerHook")
    public static class TriggerHook {
        @Patch.OnEnter
        public static void enter(@Patch.Local("g") boolean g) { g = gateEnter(null); }

        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Patch.Local("g") boolean g) { if (g) gateExit(); }
    }

    // ---- luaFileWriteThrottle: held getFileWriter writes (Tasks 5-6) ----

    /** getFileWriter entry: a held LuaFileWriter, or null to run stock. */
    public static Object onFileWriter(String name, boolean create, boolean append) {
        if (!LuaFiles.active() || name == null) return null;
        try { return LuaFiles.onOpen(name, create, append, System.nanoTime()); }
        catch (Throwable t) { LuaFiles.guard.fail(t); return null; }
    }

    /** getFileWriter exit after a stock open (ret is the stock writer, null when stock refused). */
    public static void onFileWriterOpened(String name, boolean append, Object ret) {
        if (!LuaFiles.guard.on || ret == null || name == null) return;
        try { LuaFiles.onStockOpened(name, append, ret, System.nanoTime()); }
        catch (Throwable t) { LuaFiles.guard.fail(t); }
    }

    /** getFileReader / getFileInput / getFileOutput entry: held text of that name reaches the disk first. */
    public static void onFileRead(String name) {
        if (!LuaFiles.active() || name == null) return;
        try { LuaFiles.beforeRead(name); } catch (Throwable t) { LuaFiles.guard.fail(t); }
    }

    /** GameWindow.frameStep exit: write the held files whose window ended. */
    public static void onFrameEndFiles() {
        if (!LuaFiles.active()) return;
        try { LuaFiles.tick(System.nanoTime()); } catch (Throwable t) { LuaFiles.guard.fail(t); }
    }

    @Patch(className = "zombie.Lua.LuaManager$GlobalObject", methodName = "getFileWriter")
    public static class FileWriter {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) String name, @Patch.Argument(1) boolean create, @Patch.Argument(2) boolean append,
                                    @Patch.Local("w") Object w) {
            w = onFileWriter(name, create, append);
            return w != null;
        }

        @Patch.OnExit
        public static void exit(@Patch.Argument(0) String name, @Patch.Argument(2) boolean append,
                                @Patch.Return(readOnly = false) zombie.Lua.LuaManager.GlobalObject.LuaFileWriter ret,
                                @Patch.Local("w") Object w) {
            if (w != null) ret = (zombie.Lua.LuaManager.GlobalObject.LuaFileWriter) w;
            else onFileWriterOpened(name, append, ret);
        }
    }

    @Patch(className = "zombie.Lua.LuaManager$GlobalObject", methodName = "getFileReader")
    public static class FileReader {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(0) String name) { onFileRead(name); }
    }

    @Patch(className = "zombie.Lua.LuaManager$GlobalObject", methodName = "getFileInput")
    public static class FileInput {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(0) String name) { onFileRead(name); }
    }

    @Patch(className = "zombie.Lua.LuaManager$GlobalObject", methodName = "getFileOutput")
    public static class FileOutput {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(0) String name) { onFileRead(name); }
    }
}
