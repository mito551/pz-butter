package pzbutter;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehiclePart;

/**
 * Plan 19: while one of KI5's per-frame handlers runs, let at most one
 * part-mod-data send per vehicle part through per 100 ms. A suppressed call
 * is remembered and sent once the handler window closes, so the last state
 * is not lost. All other callers go straight to the game.
 */
public final class ThirdPartyPatches {
    private ThirdPartyPatches() {}

    @Patch(className = "zombie.vehicles.BaseVehicle", methodName = "transmitPartModData")
    public static class PartModData {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This BaseVehicle self, @Patch.Argument(0) VehiclePart part) {
            if (self == null || part == null) return false;
            long id = self.getId();
            int index = part.getIndex();
            if (ThirdParty.partModGate(id, index, System.currentTimeMillis())) return false;
            ThirdParty.notePart(id, index, self, part);
            return true;
        }
    }
}
