package pzbutter;

import me.zed_0xff.zombie_buddy.Patch;
import pzbutter.luabudget.Budget;

/**
 * Lua-side hooks, one holder per game method; each advice is one call to a public static method here
 * (advice is inlined into the game class, so it may only reach public members).
 */
public final class LuaPatches {
    private LuaPatches() {}

    /** Event.trigger entry / exit (main thread): which event the handlers below belong to. */
    public static void onTriggerEnter(Object event) { Budget.eventEnter(event); }

    public static void onTriggerExit() { Budget.eventExit(); }

    /** LuaCaller.protectedCallVoid(thread, fn, args) entry: true skips the call. */
    public static boolean onCall(Object fn, Object[] args) { return Budget.shouldSkip(fn, args); }

    @Patch(className = "zombie.Lua.Event", methodName = "trigger")
    public static class Trigger {
        @Patch.OnEnter
        public static void enter(@Patch.This Object event) { onTriggerEnter(event); }

        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit() { onTriggerExit(); }
    }

    // only the (KahluaThread, Object, Object[]) overload, the one Event.trigger calls
    @Patch(className = "se.krka.kahlua.integration.LuaCaller", methodName = "protectedCallVoid")
    public static class Call {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(Object thread, Object fn, Object[] args) { return onCall(fn, args); }
    }
}
