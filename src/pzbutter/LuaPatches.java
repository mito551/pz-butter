package pzbutter;

import java.lang.invoke.VarHandle;
import java.util.ArrayList;

import me.zed_0xff.zombie_buddy.Patch;
import se.krka.kahlua.integration.expose.MethodArguments;
import se.krka.kahlua.j2se.KahluaTableImpl;
import se.krka.kahlua.vm.JavaFunction;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.LuaCallFrame;
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
        LuaBudget.eventEnter(event);
        if (LuaTiming.guard.on) LuaTiming.eventEnter(event);
    }

    public static void onTriggerExit() {
        LuaBudget.eventExit();
        if (LuaTiming.guard.on) LuaTiming.eventExit();
    }

    /** LuaCaller.protectedCallVoid entry (all overloads): true skips the call. a2 is the third argument. */
    public static boolean onCall(Object fn, Object a2) {
        boolean skip = false;
        try {
            if (LuaFast.skipEmpty && fn instanceof LuaClosure c && LuaFast.isEmpty(c.prototype)) skip = true;
            else if (a2 instanceof Object[] args) skip = LuaBudget.shouldSkip(fn, args);
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

    static volatile boolean uiLuaFast, modOptionsFast;

    /** LuaManager.LoadDirBase entry (the Lua environment exists, the Lua files are about to load). */
    public static void onLuaLoad() {
        ThirdParty.resetActive();   // a Lua load can follow a change of the mod list
        try {
            // the game runs LoadDirBase more than once per session in the same Lua env: keep the table, so what the
            // first load put in it (PZButter.Census, PZButter.Ki5) survives; a new table made Ki5.lua see no Census
            Object old = zombie.Lua.LuaManager.env.rawget("PZButter");
            KahluaTableImpl t = old instanceof KahluaTableImpl k ? k : new KahluaTableImpl(new java.util.HashMap<>());
            t.rawset("uiLuaFast", uiLuaFast);
            t.rawset("modOptionsFast", modOptionsFast);
            t.rawset("containerSendGuard", Settings.flag("containerSendGuard"));
            t.rawset("handlerCensus", Settings.flag("handlerCensus"));
            t.rawset("ki5EveryStart", new JavaFunction() {
                public int call(LuaCallFrame frame, int n) { ThirdParty.beginEvery(); return 0; }
            });
            t.rawset("ki5EveryStop", new JavaFunction() {
                public int call(LuaCallFrame frame, int n) { ThirdParty.endEvery(); return 0; }
            });
            t.rawset("ki5Flush", new JavaFunction() {
                public int call(LuaCallFrame frame, int n) { ThirdParty.flush(); return 0; }
            });
            // the options page's read and write (Plan 19 Task 6); setting(key) -> boolean, setFromUi(key, "true"/"false") -> boolean
            t.rawset("setting", new JavaFunction() {
                public int call(LuaCallFrame frame, int n) {
                    Object key = n > 0 ? frame.get(0) : null;
                    String v = Settings.uiBoolean(key instanceof String s ? s : null);
                    frame.push(Boolean.parseBoolean(v == null ? "false" : v));
                    return 1;
                }
            });
            t.rawset("setFromUi", new JavaFunction() {
                public int call(LuaCallFrame frame, int n) {
                    Object key = n > 0 ? frame.get(0) : null;
                    Object value = n > 1 ? frame.get(1) : null;
                    String r = Settings.setFromUi(key instanceof String s ? s : null,
                        value instanceof String s ? s : String.valueOf(value));
                    frame.push(Boolean.parseBoolean(r));
                    return 1;
                }
            });
            t.rawset("gpu", GpuOverTarget.luaHolder);
            t.rawset("windSwayAutoTreeDetail", Settings.flag("windSwayAutoTreeDetail"));
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
        public static void enter(@Patch.Argument(0) String event, @Patch.Local("g") boolean g) {
            ServerTick.luaEnter();
            SpikeContext.noteEvent(event);
            g = gateEnterEvent();
        }

        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Patch.Local("g") boolean g) { if (g) gateExit(); ServerTick.luaExit(); }
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

    // kahluaPoolDoublePut: a failed overload search returns the last MethodArguments to the pool twice;
    // two nested calls then share one object and the outer call dies in ReturnValues.put. Skip a put of
    // an object the pool already holds (the game's own debug check, minus the throw).

    public static final Guard poolGuard = new Guard("kahluaPoolDoublePut");

    static void initPoolPut() {
        try {
            poolGuard.on = Settings.flag("kahluaPoolDoublePut");
        } catch (Throwable t) {
            poolGuard.fail(t);
        }
    }

    static ArrayList<?>[] pools;

    /** True when m is already sitting in the game's own MethodArguments pool. */
    public static boolean isPooled(MethodArguments m) throws Exception {
        if (m == null) return false;
        if (pools == null) {
            java.lang.reflect.Field f = MethodArguments.class.getDeclaredField("Pools");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            ArrayList<?>[] cast = (ArrayList<?>[]) f.get(null);
            pools = cast;
        }
        Object[] params = m.getParams();
        int idx = params == null ? -1 : params.length;
        if (idx < 0 || idx >= pools.length) return false;
        return pools[idx].contains(m);
    }

    @Patch(className = "se.krka.kahlua.integration.expose.MethodArguments", methodName = "put")
    public static class PoolPut {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) MethodArguments args) {
            if (!poolGuard.on) return false;
            try {
                return isPooled(args);
            } catch (Throwable t) {
                poolGuard.fail(t);
                return false;
            }
        }
    }

    // bodyLocationOnce: getAllItemsForBodyLocation resolved the location string again for every clothing
    // item; resolve it once per call, lazily at the first eligible item.

    public static final Guard bodyLocGuard = new Guard("bodyLocationOnce");

    static void initBodyLoc() {
        try {
            bodyLocGuard.on = Settings.flag("bodyLocationOnce");
        } catch (Throwable t) {
            bodyLocGuard.fail(t);
        }
    }

    /** All item full names equippable by a body location, in the given order; null/blank gives an empty fresh table. */
    public static se.krka.kahlua.vm.KahluaTable getAllItemsForBodyLocationOnce(String location, java.util.List<?> allItems) {
        se.krka.kahlua.vm.KahluaTable result = zombie.Lua.LuaManager.platform.newTable();
        if (zombie.util.StringUtils.isNullOrWhitespace(location)) return result;
        int idx = 1;
        zombie.scripting.objects.ItemBodyLocation resolved = null;
        boolean haveResolved = false;
        for (int i = 0; i < allItems.size(); i++) {
            zombie.scripting.objects.Item item = (zombie.scripting.objects.Item) allItems.get(i);
            if (zombie.util.StringUtils.isNullOrWhitespace(item.getClothingItem())) continue;
            if (!haveResolved) {
                resolved = zombie.scripting.objects.ItemBodyLocation.get(
                    zombie.scripting.objects.ResourceLocation.of(location));
                haveResolved = true;
            }
            if (resolved != item.getBodyLocation() && resolved != item.canBeEquipped) continue;
            result.rawset(idx++, item.getFullName());
        }
        return result;
    }

    @Patch(className = "zombie.Lua.LuaManager$GlobalObject", methodName = "getAllItemsForBodyLocation")
    public static class BodyLocation {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) String location, @Patch.Local("r") se.krka.kahlua.vm.KahluaTable r) {
            if (!bodyLocGuard.on) return false;
            try {
                r = getAllItemsForBodyLocationOnce(location, zombie.scripting.ScriptManager.instance.getAllItems());
                return r != null;
            } catch (Throwable t) {
                bodyLocGuard.fail(t);
                return false;
            }
        }

        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) se.krka.kahlua.vm.KahluaTable ret,
                                @Patch.Local("r") se.krka.kahlua.vm.KahluaTable r) {
            if (r != null) ret = r;
        }
    }

    // luaListWalk: LuaManager.searchFolders asks the OS each entry's canonical path; walk once.

    public static final Guard walkGuard = new Guard("luaListWalk");

    static void initWalk() {
        try {
            walkGuard.on = Settings.flag("luaListWalk");
        } catch (Throwable t) {
            walkGuard.fail(t);
        }
    }

    /** Same list and order as stock searchFolders; symlink/junction entries walk the game's code. */
    public static java.util.List<String> walkFolders(java.net.URI uri, java.io.File dir, java.util.List<String> out) throws Exception {
        if (dir.isDirectory()) {
            String[] names = dir.list();
            if (names == null) return out;
            String base = dir.getAbsolutePath();
            java.nio.file.Path basePath = dir.toPath();
            for (String name : names) {
                java.nio.file.Path childPath = basePath.resolve(name);
                java.io.File child = new java.io.File(base + java.io.File.separator + name);
                if (java.nio.file.Files.isSymbolicLink(childPath)) zombie.Lua.LuaManager.searchFolders(uri, child);
                else walkFolders(uri, child, out);
            }
        } else if (dir.getAbsolutePath().toLowerCase().endsWith(".lua")) {
            String rel = zombie.ZomboidFileSystem.instance.getRelativeFile(uri, dir.getAbsolutePath());
            out.add(rel.toLowerCase(java.util.Locale.ENGLISH));
        }
        return out;
    }

    /** True when the path or an ancestor resolves elsewhere (junction chain keeps the game's code). */
    public static boolean walkTainted(java.io.File f) throws Exception {
        return !f.getCanonicalFile().getAbsolutePath().equals(f.getAbsolutePath());
    }

    @Patch(className = "zombie.Lua.LuaManager", methodName = "searchFolders")
    public static class ListWalk {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) java.net.URI uri, @Patch.Argument(1) java.io.File dir) {
            if (!walkGuard.on) return false;
            try {
                if (uri == null || dir == null || zombie.Lua.LuaManager.loadList == null) return false;
                if (walkTainted(dir)) return false;
                java.util.List<String> out = new ArrayList<>();
                walkFolders(uri, dir, out);
                zombie.Lua.LuaManager.loadList.addAll(out);
                return true;
            } catch (Throwable t) {
                walkGuard.fail(t);
                return false;
            }
        }
    }

    // ---- luaBreakpointSkip: the breakpoint machinery answers at once while its maps are empty ----

    public static final Guard bpGuard = new Guard("luaBreakpointSkip");

    static void initBp() {
        try {
            bpGuard.on = Settings.flag("luaBreakpointSkip");
        } catch (Throwable t) {
            bpGuard.fail(t);
        }
    }

    private static final java.util.Map<String, java.lang.reflect.Field> bpFields = new java.util.HashMap<>();

    private static synchronized java.util.Map<?, ?> bpMap(Object thread, String name) throws Exception {
        java.lang.reflect.Field f = bpFields.get(name);
        if (f == null) {
            f = se.krka.kahlua.vm.KahluaThread.class.getDeclaredField(name);
            f.setAccessible(true);
            bpFields.put(name, f);
        }
        return (java.util.Map<?, ?>) f.get(thread);
    }

    /** True when every named KahluaThread map reads empty (also when the thread reads null: nothing is configured). */
    public static boolean mapsEmpty(Object thread, String[] names) {
        try {
            if (thread == null) return true;
            for (String n : names) {
                java.util.Map<?, ?> m = bpMap(thread, n);
                if (m == null || !m.isEmpty()) return false;
            }
            return true;
        } catch (Throwable t) {
            bpGuard.fail(t);
            return false;
        }
    }

    public static final String[] BP_ALL = {"breakpointMap", "breakpointDataMap", "breakpointReadDataMap"};

    /** The instance methods' shared advice body: skip the stock body while the relevant map is empty. */
    public static Object bpSingle(Object self, String mapName) {
        try {
            if (!bpGuard.on) return null;
            return mapsEmpty(self, new String[] {mapName}) ? Boolean.TRUE : null;
        } catch (Throwable t) {
            bpGuard.fail(t);
            return null;
        }
    }

    /** The write-side methods' shared advice body: skip only while nothing is configured at all. */
    public static Object bpAll(Object self) {
        try {
            if (!bpGuard.on) return null;
            return mapsEmpty(self, BP_ALL) ? Boolean.TRUE : null;
        } catch (Throwable t) {
            bpGuard.fail(t);
            return null;
        }
    }

    @Patch(className = "se.krka.kahlua.vm.KahluaThread", methodName = "hasDataBreakpoint")
    public static class HasData {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object self, @Patch.Local("r") Object r) {
            r = bpSingle(self, "breakpointDataMap");
            return r != null;
        }

        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) boolean ret, @Patch.Local("r") Object r) {
            if (r != null) ret = false;
        }
    }

    @Patch(className = "se.krka.kahlua.vm.KahluaThread", methodName = "hasReadDataBreakpoint")
    public static class HasReadData {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object self, @Patch.Local("r") Object r) {
            r = bpSingle(self, "breakpointReadDataMap");
            return r != null;
        }

        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) boolean ret, @Patch.Local("r") Object r) {
            if (r != null) ret = false;
        }
    }

    @Patch(className = "se.krka.kahlua.vm.KahluaThread", methodName = "toggleBreakOnRead")
    public static class ToggleRead {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object self) {
            return bpAll(self) != null;
        }
    }

    @Patch(className = "se.krka.kahlua.vm.KahluaThread", methodName = "setLocalVarToStack")
    public static class SetLocalVar {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object self) {
            return bpAll(self) != null;
        }
    }

    @Patch(className = "zombie.ui.UIManager", methodName = "debugBreakpoint")
    public static class DebugBp {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter() {
            if (!bpGuard.on) return false;
            try {
                return mapsEmpty(zombie.ui.UIManager.defaultthread, BP_ALL);
            } catch (Throwable t) {
                bpGuard.fail(t);
                return false;
            }
        }
    }

    // ---- kahluaTableAlloc: Platform.newTable answers a table with a pre-sized backing store ----

    public static final Guard taGuard = new Guard("kahluaTableAlloc");

    static void initTa() {
        try {
            taGuard.on = Settings.flag("kahluaTableAlloc");
        } catch (Throwable t) {
            taGuard.fail(t);
        }
    }

    /** The pre-sized answer for the table constructor; iteration order stays the stock LinkedHashMap's. */
    public static se.krka.kahlua.vm.KahluaTable newTablePresized() {
        return new se.krka.kahlua.j2se.KahluaTableImpl(new java.util.LinkedHashMap<>(32));
    }

    @Patch(className = "se.krka.kahlua.j2se.J2SEPlatform", methodName = "newTable")
    public static class TableAlloc {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Local("r") Object r) {
            if (!taGuard.on) return false;
            try {
                r = newTablePresized();
                return true;
            } catch (Throwable t) {
                taGuard.fail(t);
                return false;
            }
        }

        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) se.krka.kahlua.vm.KahluaTable ret, @Patch.Local("r") Object r) {
            if (r != null) ret = (se.krka.kahlua.vm.KahluaTable) r;
        }
    }

    // ---- luaRequireIndex: the require list (LuaManager.loaded) keeps a name hash so contains is O(1);
    // the hash is rebuilt whenever the list's size changed behind it (the only cheap change stamp). ----

    public static final Guard liGuard = new Guard("luaRequireIndex");

    static void initLi() {
        try {
            liGuard.on = Settings.flag("luaRequireIndex");
        } catch (Throwable t) {
            liGuard.fail(t);
        }
    }

    public static final class IndexList<E> extends java.util.ArrayList<E> {
        private java.util.HashSet<E> names;
        private int namesOf = -1; // size the names hash was built from; -1 = stale

        private void hash() {
            if (names != null && namesOf == size()) return;
            names = new java.util.HashSet<>(this);
            namesOf = size();
        }

        @Override public boolean contains(Object o) {
            if (!(o instanceof String s)) return super.contains(o);
            hash();
            return names.contains(s);
        }

        @Override public E set(int i, E e) { namesOf = -1; return super.set(i, e); }
        @Override public void clear() { namesOf = -1; super.clear(); }
    }

    /** The advice face: wrap the live require list (called from a LuaManager.init exit). */
    public static void swapLoadedIndex() {
        if (!liGuard.on) return;
        try {
            if (!(zombie.Lua.LuaManager.loaded instanceof IndexList)) {
                @SuppressWarnings("unchecked")
                IndexList<String> l = new IndexList<>();
                l.addAll((java.util.List<String>) zombie.Lua.LuaManager.loaded);
                zombie.Lua.LuaManager.loaded = l;
            }
        } catch (Throwable t) {
            liGuard.fail(t);
        }
    }

    @Patch(className = "zombie.Lua.LuaManager", methodName = "init")
    public static class LoadedIndex {
        @Patch.OnExit
        public static void exit() {
            swapLoadedIndex();
        }
    }

    // ---- luaCallInvoker: after 64 calls of the same MethodCaller, the Java method is called through a
    // MethodHandle instead of reflection; arguments, return values and the logged-throw behaviour stay stock. ----

    public static final Guard invGuard = new Guard("luaCallInvoker");

    static void initInv() {
        try {
            invGuard.on = Settings.flag("luaCallInvoker");
        } catch (Throwable t) {
            invGuard.fail(t);
        }
    }

    static final java.util.concurrent.ConcurrentHashMap<se.krka.kahlua.integration.expose.caller.MethodCaller, long[]> invCounts =
            new java.util.concurrent.ConcurrentHashMap<>();
    static final java.util.concurrent.ConcurrentHashMap<se.krka.kahlua.integration.expose.caller.MethodCaller, Invoker> invokers =
            new java.util.concurrent.ConcurrentHashMap<>();
    static final java.util.concurrent.ConcurrentHashMap<se.krka.kahlua.integration.expose.caller.MethodCaller, java.lang.reflect.Method> invMethods =
            new java.util.concurrent.ConcurrentHashMap<>();

    static final class Invoker {
        final java.lang.invoke.MethodHandle h;
        final boolean hasRet;
        final boolean hasSelf;
        final Object owner;

        Invoker(java.lang.invoke.MethodHandle h, boolean hasRet, boolean hasSelf, Object owner) {
            this.h = h;
            this.hasRet = hasRet;
            this.hasSelf = hasSelf;
            this.owner = owner;
        }

        Object apply(Object self, Object[] args) throws Throwable {
            return h.invoke(self, args);
        }
    }

    static java.lang.reflect.Method methodOf(se.krka.kahlua.integration.expose.caller.MethodCaller mc) throws Exception {
        java.lang.reflect.Method m = invMethods.get(mc);
        if (m == null) {
            java.lang.reflect.Field f = mc.getClass().getDeclaredField("method");
            f.setAccessible(true);
            m = (java.lang.reflect.Method) f.get(mc);
            invMethods.put(mc, m);
        }
        return m;
    }

    static Invoker invokerOf(se.krka.kahlua.integration.expose.caller.MethodCaller mc) throws Exception {
        java.lang.reflect.Method m = methodOf(mc);
        boolean stat = java.lang.reflect.Modifier.isStatic(m.getModifiers());
        java.lang.invoke.MethodHandle h = java.lang.invoke.MethodHandles.lookup().unreflect(m);
        // a variable-arity method unreflects to a collector handle, which would pack the already-packed Lua arguments a second
        // time (getText("UI_NMM_By", author) got the Object[] {author} as its argument); stock's Method.invoke is fixed arity
        h = h.asFixedArity();
        h = h.asSpreader(Object[].class, m.getParameterTypes().length);
        Object owner = null;
        boolean hasSelf = false;
        try {
            java.lang.reflect.Field sf = mc.getClass().getDeclaredField("hasSelf");
            sf.setAccessible(true);
            hasSelf = (Boolean) sf.get(mc);
            if (!hasSelf) {
                java.lang.reflect.Field of = mc.getClass().getDeclaredField("owner");
                of.setAccessible(true);
                owner = of.get(mc);
            }
        } catch (Throwable t) {
            hasSelf = true; // the binding stayed unreadable: the caller's self is as good as any
        }
        if (!stat) {
            h = h.asType(java.lang.invoke.MethodType.methodType(Object.class, Object.class, Object[].class));
        } else {
            h = java.lang.invoke.MethodHandles.dropArguments(h, 0, Object.class);
            h = h.asType(java.lang.invoke.MethodType.methodType(Object.class, Object.class, Object[].class));
        }
        return new Invoker(h, !m.getReturnType().equals(void.class), hasSelf, owner);
    }

    /** The log the stock MethodCaller.call does for a thrown Java method (swallowed the same way). */
    static void logCallerError(Throwable t) {
        try {
            zombie.ui.UIManager.defaultthread.doStacktraceProper();
            zombie.ui.UIManager.defaultthread.debugException(t);
            zombie.core.logger.ExceptionLogger.logException(t);
        } catch (Throwable t2) {
            try {
                zombie.core.logger.ExceptionLogger.logException(t);
            } catch (Throwable ignored) {
            }
        }
    }

    public static boolean callThrough(se.krka.kahlua.integration.expose.caller.MethodCaller mc, Object self,
                                      se.krka.kahlua.integration.expose.ReturnValues rv, Object[] args) {
        try {
            if (!invGuard.on || args == null) return false;
            Invoker inv = invokers.get(mc);
            if (inv == null) {
                long[] c = invCounts.computeIfAbsent(mc, k -> new long[1]);
                if (++c[0] < 64) return false;
                try {
                    inv = invokerOf(mc);
                    invokers.put(mc, inv);
                } catch (Throwable t) {
                    invGuard.fail(t);
                    c[0] = 0; // the build failed: try again after another 64 calls
                    return false;
                }
            }
            Object recv = inv.hasSelf ? self : inv.owner;
            Object r;
            try {
                r = inv.apply(recv, args);
            } catch (Throwable t) {
                logCallerError(t);
                return true;
            }
            if (inv.hasRet) rv.push(r);
            return true;
        } catch (Throwable t) {
            invGuard.fail(t);
            return false;
        }
    }

    @Patch(className = "se.krka.kahlua.integration.expose.caller.MethodCaller", methodName = "call")
    public static class CallInvoker {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This se.krka.kahlua.integration.expose.caller.MethodCaller mc, @Patch.Argument(0) Object self,
                                    @Patch.Argument(1) se.krka.kahlua.integration.expose.ReturnValues rv, @Patch.Argument(2) Object[] args) {
            if (!invGuard.on) return false;
            try {
                return callThrough(mc, self, rv, args);
            } catch (Throwable t) {
                invGuard.fail(t);
                return false;
            }
        }
    }
}