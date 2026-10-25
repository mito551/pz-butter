package pzbutter;

import me.zed_0xff.zombie_buddy.Patch;

public final class CensusPatches {
    private CensusPatches() {}

    @Patch(className = "zombie.iso.IsoMovingObject", methodName = "preupdate")
    public static class PreUpdate {
        @Patch.OnEnter
        public static void enter(@Patch.This Object self) {
            if (Census.guard.on) {
                try { Census.enter(self); }
                catch (Throwable t) { Census.guard.fail(t); }
            }
        }

        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Patch.This Object self) {
            if (Census.guard.on) {
                try { Census.exit(self); }
                catch (Throwable t) { Census.guard.fail(t); }
            }
        }
    }

    @Patch(className = "zombie.iso.IsoMovingObject", methodName = "frameStep")
    public static class FrameStep {
        @Patch.OnEnter
        public static void enter(@Patch.This Object self) {
            if (Census.guard.on) {
                try { Census.enter(self); }
                catch (Throwable t) { Census.guard.fail(t); }
            }
        }

        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Patch.This Object self) {
            if (Census.guard.on) {
                try { Census.exit(self); }
                catch (Throwable t) { Census.guard.fail(t); }
            }
        }
    }

    @Patch(className = "zombie.iso.IsoMovingObject", methodName = "update")
    public static class Update {
        @Patch.OnEnter
        public static void enter(@Patch.This Object self) {
            if (Census.guard.on) {
                try { Census.enter(self); }
                catch (Throwable t) { Census.guard.fail(t); }
            }
        }

        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Patch.This Object self) {
            if (Census.guard.on) {
                try { Census.exit(self); }
                catch (Throwable t) { Census.guard.fail(t); }
            }
        }
    }
}