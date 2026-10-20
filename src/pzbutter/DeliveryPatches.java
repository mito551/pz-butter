package pzbutter;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.core.SpriteRenderer;

/**
 * One patch holder per hooked game method; each calls the frame-delivery features in a fixed order
 * (ZombieBuddy gives no order between separate advices on one method). Every body catches Throwable.
 */
public final class DeliveryPatches {
    private DeliveryPatches() {}

    @Patch(className = "zombie.core.SpriteRenderer", methodName = "pushFrameDown")
    public static class Push {
        @Patch.OnEnter
        public static void enter() {
            if (!FrameStamps.guard.on) return;
            try { FrameStamps.onPush(SpriteRenderer.instance.getPopulatingState()); }
            catch (Throwable t) { FrameStamps.guard.fail(t); }
        }
    }

    @Patch(className = "zombie.core.SpriteRenderer", methodName = "acquireStateForRendering")
    public static class Acquire {
        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Patch.Return Object state) {
            if (!FrameStamps.guard.on || state == null) return;
            try { FrameStamps.onAcquire(state); }
            catch (Throwable t) { FrameStamps.guard.fail(t); }
        }
    }

    @Patch(className = "org.lwjglx.opengl.Display", methodName = "swapBuffers")
    public static class Swap {
        @Patch.OnEnter
        public static void enter() {
            if (!PresentLog.guard.on) return;
            try { PresentLog.swapStart(); } catch (Throwable t) { PresentLog.guard.fail(t); }
        }

        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit() {
            if (!PresentLog.guard.on) return;
            try { PresentLog.swapEnd(); } catch (Throwable t) { PresentLog.guard.fail(t); }
        }
    }
}
