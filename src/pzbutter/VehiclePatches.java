package pzbutter;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Plan 10 vehicle hooks: one holder per game method. Advice is inlined into the game class, so it reaches only public
 * members: each holder's advice is one call into a public static shim here, which catches its own Throwable into the
 * feature's Guard.
 */
public final class VehiclePatches {
    private VehiclePatches() {}

    public static void init() {
        VehicleFixes.init();
    }

    /** BaseVehicle.addKeyToGloveBox entry: true skips the body (no container to add into). */
    public static boolean onGloveBox(Object vehicle) {
        if (!VehicleFixes.gloveGuard.on) return false;
        try {
            zombie.vehicles.BaseVehicle v = (zombie.vehicles.BaseVehicle) vehicle;
            zombie.vehicles.VehiclePart part = v.getPartById("GloveBox");
            boolean partExists = part != null;
            boolean containerNull = part != null && part.getItemContainer() == null;
            if (VehicleFixes.skipGloveBox(partExists, containerNull)) {
                VehicleFixes.gloveSkipped++;
                return true;
            }
            return false;
        } catch (Throwable t) {
            VehicleFixes.gloveGuard.fail(t);
            return false;
        }
    }

    @Patch(className = "zombie.vehicles.BaseVehicle", methodName = "addKeyToGloveBox")
    public static class GloveBox {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object vehicle) {
            return VehiclePatches.onGloveBox(vehicle);
        }
    }
}
