package pzbutter;

import me.zed_0xff.zombie_buddy.Patch;

import zombie.core.skinnedmodel.animation.AnimationMultiTrack;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.core.skinnedmodel.animation.AnimationTrack;

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

    /** Called by the updateAnimationPlayer holder to track the exact part player being processed. */
    public static void onAnimPartEnter(Object player, Object part) {
        if (part != null) {
            VehicleFixes.currentPartPlayer = player;
            VehicleFixes.currentPartThread = Thread.currentThread();
        } else {
            VehicleFixes.currentPartPlayer = null;
            VehicleFixes.currentPartThread = null;
        }
    }

    /** Called by the updateAnimationPlayer holder to clear the part tracking. */
    public static void onAnimPartExit() {
        VehicleFixes.currentPartPlayer = null;
        VehicleFixes.currentPartThread = null;
    }

    /** AnimationPlayer.Update entry for parked parts: true skips the whole update. */
    public static boolean onAnimUpdate(Object player) {
        if (!VehicleFixes.animGuard.on) return false;
        if (VehicleFixes.currentPartThread != Thread.currentThread()) return false;
        if (player != VehicleFixes.currentPartPlayer) return false;
        try {
            VehicleFixes.animChecked++;
            AnimationPlayer p = (AnimationPlayer) player;
            AnimationMultiTrack mt = p.getMultiTrack();
            if (mt == null || mt.getTrackCount() != 1) return false;
            AnimationTrack t = mt.getTracks().get(0);
            if (t == null || !t.isPlaying) {
                VehicleFixes.removeState(player);
                return false;
            }
            boolean skip = VehicleFixes.decide(player, t, p.getSkinningData(),
                    t.getCurrentTimeValue(), t.getBlendWeight(), t.getSpeedDelta(),
                    FrameClock.frameNo);
            if (skip) VehicleFixes.animSkipped++;
            return skip;
        } catch (Throwable t) {
            VehicleFixes.animGuard.fail(t);
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

    @Patch(className = "zombie.vehicles.BaseVehicle", methodName = "updateAnimationPlayer")
    public static class UpdateAnimationPlayer {
        @Patch.OnEnter
        public static void enter(Object player, Object part) {
            VehiclePatches.onAnimPartEnter(player, part);
        }

        @Patch.OnExit(onThrowable = Throwable.class)
        public static void exit() {
            VehiclePatches.onAnimPartExit();
        }
    }

    @Patch(className = "zombie.core.skinnedmodel.animation.AnimationPlayer", methodName = "Update")
    public static class AnimationUpdate {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(float dt, @Patch.This Object player) {
            return VehiclePatches.onAnimUpdate(player);
        }
    }
}
