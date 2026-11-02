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
        public static void exit() { LoadFixes.loadExitLeave(); ShaderReuse.printSummaryOnce(); ScriptComments.printSummaryOnce(); }
    }

    /** TextureIDAssetManager.waitFileTask entry: true skips stock wait. */
    public static boolean onWaitFileTask() {
        try { return FileKnobs.onWaitFileTask(); }
        catch (Throwable t) { FileKnobs.guard.fail(t); return false; }
    }

    @Patch(className = "zombie.core.textures.TextureIDAssetManager", methodName = "waitFileTask")
    public static class WaitFileTask {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter() { return LoadPatches.onWaitFileTask(); }
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
            // stock's debugln prints only with Core.debug and Vehicle at Debug severity (DebugLogStream.debuglnWithTraceOffset 0-3, writeWithCallerPrefixln 0-5);
            // the line's caller prefix names this holder instead of IsoMetaGrid.checkVehiclesZones (debug runs only)
            if (zombie.core.Core.debug && zombie.debug.DebugLog.isLogEnabled(zombie.debug.DebugType.Vehicle, zombie.debug.LogSeverity.Debug)) {
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

    /** Inputs below this run stock: its replace loop only goes quadratic on large commented files (tileGeometry.txt: 6.4 MB, 21,523 comments, 1.2 s vs 15-45 ms). */
    static final int STRIP_MIN_CHARS = 1 << 16;

    public static String onScriptComments(String s) {
        if (!ScriptComments.guard.on || s == null || s.length() < STRIP_MIN_CHARS) return null;
        try {
            long t0 = System.nanoTime();
            String r = ScriptComments.strip(s);
            ScriptComments.count(System.nanoTime() - t0);
            return r;
        } catch (Throwable t) { ScriptComments.guard.fail(t); return null; }
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

    /** DebugFileWatcher maps: take the shared lock only while modWatchAsync is on. */
    @Patch(className = "zombie.DebugFileWatcher", methodName = "registerDir")
    public static class DFWRegisterDir {
        @Patch.OnEnter
        public static void enter(@Patch.Local("locked") boolean locked) { locked = ModWatch.lockEnter(); }
        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Patch.Local("locked") boolean locked) { ModWatch.lockExit(locked); }
    }

    @Patch(className = "zombie.DebugFileWatcher", methodName = "update")
    public static class DFWUpdate {
        @Patch.OnEnter
        public static void enter(@Patch.Local("locked") boolean locked) { locked = ModWatch.lockEnter(); }
        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Patch.Local("locked") boolean locked) { ModWatch.lockExit(locked); }
    }

    @Patch(className = "zombie.DebugFileWatcher", methodName = "addWatchedFile")
    public static class DFWAddWatchedFile {
        @Patch.OnEnter
        public static void enter(@Patch.Local("locked") boolean locked) { locked = ModWatch.lockEnter(); }
        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Patch.Local("locked") boolean locked) { ModWatch.lockExit(locked); }
    }

    @Patch(className = "zombie.DebugFileWatcher", methodName = "add")
    public static class DFWAdd {
        @Patch.OnEnter
        public static void enter(@Patch.Local("locked") boolean locked) { locked = ModWatch.lockEnter(); }
        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Patch.Local("locked") boolean locked) { ModWatch.lockExit(locked); }
    }

    @Patch(className = "zombie.DebugFileWatcher", methodName = "remove")
    public static class DFWRemove {
        @Patch.OnEnter
        public static void enter(@Patch.Local("locked") boolean locked) { locked = ModWatch.lockEnter(); }
        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Patch.Local("locked") boolean locked) { ModWatch.lockExit(locked); }
    }

    /** addDirectoryRecurse: hand the path to the background worker when the key is on. */
    @Patch(className = "zombie.DebugFileWatcher", methodName = "addDirectoryRecurse")
    public static class DFWAddDirectoryRecurse {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) String path) {
            try { return ModWatch.onAddDirectoryRecurse(path); }
            catch (Throwable t) { ModWatch.guard.fail(t); return false; }
        }
    }
}