package pzbutter;

import me.zed_0xff.zombie_buddy.Patch;
import pzbutter.luabudget.Budget;
import se.krka.kahlua.vm.LuaClosure;

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
