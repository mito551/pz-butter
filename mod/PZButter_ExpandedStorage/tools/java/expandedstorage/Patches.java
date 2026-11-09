package expandedstorage;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.iso.IsoObject;

/**
 * Both hooks bind the object itself (@Patch.This). The first method argument is NOT the object:
 * for load() it is the ByteBuffer, and createContainersFromSpriteProperties() has no arguments.
 *
 * createContainersFromSpriteProperties() runs from addToWorld() and CellLoader, so it covers
 * placed, generated and loaded objects; the load() hook catches saved containers as they are read.
 */
public final class Patches {
    private Patches() {}

    @Patch(className = "zombie.iso.IsoObject", methodName = "createContainersFromSpriteProperties")
    public static class CreateContainers {
        @Patch.OnExit
        public static void exit(@Patch.This Object self) {
            try {
                if (self instanceof IsoObject object) CapacityRegistry.apply(object);
            } catch (Throwable ignored) {
            }
        }
    }

    @Patch(className = "zombie.iso.IsoObject", methodName = "load")
    public static class Load {
        @Patch.OnExit
        public static void exit(@Patch.This Object self) {
            try {
                if (self instanceof IsoObject object) CapacityRegistry.apply(object);
            } catch (Throwable ignored) {
            }
        }
    }
}
