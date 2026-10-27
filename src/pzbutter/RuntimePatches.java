package pzbutter;

import me.zed_0xff.zombie_buddy.Patch;

public final class RuntimePatches {
    private RuntimePatches() {}

    public static void init() {
        RuntimeFixes.init();
    }

    /** BaseLib.collectgarbage entry: true skips the body (stock's System.gc() path). */
    public static boolean onCollect(Object frame, int nArguments) {
        if (!RuntimeFixes.gcGuard.on) return false;
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

    /** Display.sync entry: true skips the body (we park ourselves). */
    public static boolean onSync(int fps) {
        if (!RuntimeFixes.lightGuard.on) return false;
        try {
            RuntimeFixes.lightSync(fps);
            return true;
        } catch (Throwable t) {
            RuntimeFixes.lightGuard.fail(t);
            return false;
        }
    }

    @Patch(className = "org.lwjglx.opengl.Display", methodName = "sync")
    public static class LightSync {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) int fps) {
            return RuntimePatches.onSync(fps);
        }
    }
}
