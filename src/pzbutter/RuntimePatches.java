package pzbutter;

import me.zed_0xff.zombie_buddy.Patch;

public final class RuntimePatches {
    private RuntimePatches() {}

    public static void init() {
        RuntimeFixes.init();
    }

    /** BaseLib.collectgarbage entry: true skips the body (stock's System.gc() path). */
    public static boolean onCollect(Object frame, int nArguments) {
        try {
            Object option = nArguments > 0 ? ((se.krka.kahlua.vm.LuaCallFrame) frame).get(0) : null;
            return RuntimeFixes.skipCollect(option);
        } catch (Throwable t) {
            RuntimeFixes.gcGuard.fail(t);
            return false;
        }
    }

    @Patch(className = "se.krka.kahlua.stdlib.BaseLib", methodName = "collectgarbage")
    public static class CollectGarbage {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) Object frame, @Patch.Argument(1) int nArguments) {
            return RuntimePatches.onCollect(frame, nArguments);
        }
    }
}
