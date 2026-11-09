package expandedstorage;

import se.krka.kahlua.integration.annotations.LuaMethod;

public final class LuaBridge {
    private LuaBridge() {}

    /** Lua: true once the Java hooks are live, so the Lua per-square scan can stand down. */
    @LuaMethod(name = "esZBActive", global = true)
    public static boolean active() {
        return CapacityRegistry.active();
    }
}
