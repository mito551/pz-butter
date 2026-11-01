package pzbutter;

import me.zed_0xff.zombie_buddy.Patch;

/** Plan 11 world-load hooks: one holder per game method; each advice calls one public static shim here. */
public final class LoadPatches {
    private LoadPatches() {}

    /** BuildingRoomsEditor.checkBuildingAndRoomIDs entry (both overloads): true skips the walk. */
    public static boolean onIdCheck() {
        if (!LoadFixes.idGuard.on) return false;
        try { return LoadFixes.skipIdCheck(zombie.core.Core.debug); }
        catch (Throwable t) { LoadFixes.idGuard.fail(t); return false; }
    }

    /** ScreenFader.startFadeToBlack entry: true skips the pre-load fade (only inside GameLoadingState.exit). */
    public static boolean onLoadFade() {
        if (!LoadFixes.noLoadFadeGuard.on) return false;
        try { return LoadFixes.skipLoadFade(); }
        catch (Throwable t) { LoadFixes.noLoadFadeGuard.fail(t); return false; }
    }

    @Patch(className = "zombie.buildingRooms.BuildingRoomsEditor", methodName = "checkBuildingAndRoomIDs")
    public static class CheckBuildingAndRoomIDs {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter() { return LoadPatches.onIdCheck(); }
    }

    @Patch(className = "zombie.ui.ScreenFader", methodName = "startFadeToBlack")
    public static class FadeToBlack {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter() { return LoadPatches.onLoadFade(); }
    }

    @Patch(className = "zombie.gameStates.GameLoadingState", methodName = "exit")
    public static class GameLoadingExit {
        @Patch.OnEnter
        public static void enter() { LoadFixes.loadExitEnter(); }

        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit() { LoadFixes.loadExitLeave(); ShaderReuse.printSummaryOnce(); }
    }

    @Patch(className = "zombie.core.skinnedmodel.model.Model", methodName = "CreateShader")
    public static class CreateShader {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object self, @Patch.Argument(0) String name) {
            try { return ShaderReuse.onCreateShader(self, name); }
            catch (Throwable t) { ShaderReuse.guard.fail(t); return false; }
        }
    }

    /** Vehicle zones: true skips stock. */
    public static boolean onVehicleZones(Object self) {
        if (!VehicleZones.guard.on) return false;
        try {
            if (!(self instanceof zombie.iso.IsoMetaGrid grid)) return false;
            java.util.List<zombie.iso.zones.VehicleZone> zones = grid.vehiclesZones;
            java.util.function.BiConsumer<zombie.iso.zones.VehicleZone, zombie.iso.zones.VehicleZone> debug = null;
            if (zombie.debug.DebugType.Vehicle.isEnabled()) {
                debug = (removed, kept) -> zombie.debug.DebugType.Vehicle.debugln(
                    "checkVehiclesZones: ERROR! Zone '" + removed.name + "':'" + removed.type + "' (" + removed.x + ", " + removed.y
                        + ") duplicate with Zone '" + kept.name + "':'" + kept.type + "' (" + kept.x + ", " + kept.y + ")");
            }
            VehicleZones.dedupe(zones,
                z -> (((long) z.x) << 32) | (z.y & 0xffffffffL),
                z -> (((long) z.h) << 32) | (z.w & 0xffffffffL),
                debug);
            return true;
        } catch (Throwable t) { VehicleZones.guard.fail(t); return false; }
    }

    @Patch(className = "zombie.iso.IsoMetaGrid", methodName = "checkVehiclesZones")
    public static class CheckVehiclesZones {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object self) {
            try { return LoadPatches.onVehicleZones(self); }
            catch (Throwable t) { VehicleZones.guard.fail(t); return false; }
        }
    }

    /** Script comments: true skips stock. */
    public static String onScriptComments(String s) {
        if (!ScriptComments.guard.on) return null;
        try { return ScriptComments.strip(s); }
        catch (Throwable t) { ScriptComments.guard.fail(t); return null; }
    }

    @Patch(className = "zombie.scripting.ScriptParser", methodName = "stripComments")
    public static class StripComments {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) String s, @Patch.Local("v") Object v) {
            try {
                v = LoadPatches.onScriptComments(s);
                return v != null;
            } catch (Throwable t) { ScriptComments.guard.fail(t); return false; }
        }

        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) String ret, @Patch.Local("v") Object v) {
            if (v != null) ret = (String) v;
        }
    }
}