package cpd.perf;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Notes every door the game toggles. Each method is an advice on a door's own toggle, so the
 * original runs untouched; a failure here is swallowed rather than allowed into the game.
 */
public final class Patches {
    private Patches() {}

    @Patch(className = "zombie.iso.objects.IsoDoor", methodName = "ToggleDoorActual")
    public static class DoorActual {
        @Patch.OnEnter
        public static void enter(@Patch.This Object self) {
            try { Toggles.record(self); } catch (Throwable ignored) { }
        }
    }

    @Patch(className = "zombie.iso.objects.IsoDoor", methodName = "ToggleDoorSilent")
    public static class DoorSilent {
        @Patch.OnEnter
        public static void enter(@Patch.This Object self) {
            try { Toggles.record(self); } catch (Throwable ignored) { }
        }
    }

    @Patch(className = "zombie.iso.objects.IsoThumpable", methodName = "ToggleDoorActual")
    public static class ThumpableActual {
        @Patch.OnEnter
        public static void enter(@Patch.This Object self) {
            try { Toggles.record(self); } catch (Throwable ignored) { }
        }
    }
}
