package wkt.perf;

import me.zed_0xff.zombie_buddy.Patch;

import zombie.iso.objects.IsoWorldInventoryObject;
import zombie.inventory.types.Key;

public final class Patches {
    private Patches() {}

    @Patch(className = "zombie.iso.objects.IsoWorldInventoryObject", methodName = "render")
    public static class RenderSeen {
        @Patch.OnEnter
        public static void enter(@Patch.This Object self) {
            try {
                if (self instanceof IsoWorldInventoryObject wo
                        && wo.getItem() instanceof Key) {
                    GroundKeys.seen(wo);
                }
            } catch (Throwable ignored) {
            }
        }
    }
}
