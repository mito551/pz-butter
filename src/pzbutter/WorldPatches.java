package pzbutter;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * World hooks (Plan 5a), one holder per game method; each advice is one call to a public static method here
 * (advice is inlined into the game class, so it may only reach public members).
 */
public final class WorldPatches {
    private WorldPatches() {}

    static final Guard occlusionGuard = new Guard("occlusionSkipLightingOnly");
    static final Guard infoGuard = new Guard("lightInfoOncePerFrame");
    static final Guard switchGuard = new Guard("lightSwitchCheckFrames");
    static final Guard fpsGuard = new Guard("lightingFps");
    static final Guard gridGuard = new Guard("gridStackInterval");
    static final Guard roofGuard = new Guard("roofHideDebounceFrames");

    /** FBORenderCell.hasAnyDirtyChunkTextures entry: the answer when occlusionSkipLightingOnly is on; null runs stock. */
    public static Boolean onDirtyEnter(Object cell, int p) {
        if (!occlusionGuard.on) return null;
        try { return LightChecks.anyDirtyOutsideLighting(cell, p); }
        catch (Throwable t) { occlusionGuard.fail(t); return null; }
    }

    /** JNILighting.updateFBORenderChunk exit: stamps a clean square with the current lighting counter. */
    public static void onLightInfoExit(Object jni) {
        if (!infoGuard.on) return;
        try { LightChecks.lightInfoStamp(jni); }
        catch (Throwable t) { infoGuard.fail(t); }
    }

    /** IsoLightSwitch.hasElectricityAround entry: a fresh stored answer, or null (stock runs). */
    public static Boolean onSwitchEnter(Object sw) {
        if (!switchGuard.on || !FrameClock.on) return null;
        try { return LightChecks.switchMemo(sw, FrameClock.frameNo, LightChecks.switchTtl); }
        catch (Throwable t) { switchGuard.fail(t); return null; }
    }

    /** IsoLightSwitch.hasElectricityAround exit on a miss: stores the answer the stock body computed. */
    public static void onSwitchExit(Object sw, boolean answer) {
        if (!switchGuard.on || !FrameClock.on) return;
        try { LightChecks.switchStore(sw, FrameClock.frameNo, answer); }
        catch (Throwable t) { switchGuard.fail(t); }
    }

    /** IngameState.enter exit: applies lightingFps once. */
    public static void onIngameEnter() {
        if (!fpsGuard.on) return;
        try { LightChecks.applyLightingFps(); }
        catch (Throwable t) { fpsGuard.fail(t); }
    }

    @Patch(className = "zombie.iso.fboRenderChunk.FBORenderCell", methodName = "hasAnyDirtyChunkTextures")
    public static class DirtyChunks {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object self, @Patch.Argument(0) int p, @Patch.Local("v") Object v) {
            v = onDirtyEnter(self, p);
            return v != null;
        }

        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) boolean ret, @Patch.Local("v") Object v) {
            if (v != null) ret = (Boolean) v;
        }
    }

    /** FBORenderCutaways.CalculateBuildingsToCollapse entry: Boolean.FALSE skips the scan; null runs stock. */
    public static Boolean onGridEnter() {
        if (!gridGuard.on || !FrameClock.on) return null;
        try { return GridGate.gateEnter(FrameClock.frameNo); }
        catch (Throwable t) { gridGuard.fail(t); return null; }
    }

    /** CalculateBuildingsToCollapse exit after a real scan: the cell's roof flag takes the debounced value. */
    public static void onGridScanExit(Object cutaways) {
        if (!roofGuard.on || !FrameClock.on) return;
        try { GridGate.scanned(cutaways, FrameClock.frameNo); }
        catch (Throwable t) { roofGuard.fail(t); }
    }

    @Patch(className = "zombie.iso.fboRenderChunk.FBORenderCutaways", methodName = "CalculateBuildingsToCollapse")
    public static class BuildingsInFront {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Local("v") Object v) {
            v = onGridEnter();
            return v != null;
        }

        @Patch.OnExit
        public static void exit(@Patch.This Object self, @Patch.Return(readOnly = false) boolean ret, @Patch.Local("v") Object v) {
            if (v != null) ret = (Boolean) v;   // skipped scan: the roof flag and its debounce stay as they are
            else onGridScanExit(self);
        }
    }

    @Patch(className = "zombie.iso.LightingJNI$JNILighting", methodName = "updateFBORenderChunk")
    public static class LightInfoStamp {
        @Patch.OnExit
        public static void exit(@Patch.This Object self) { onLightInfoExit(self); }
    }

    @Patch(className = "zombie.iso.objects.IsoLightSwitch", methodName = "hasElectricityAround")
    public static class SwitchPower {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object self, @Patch.Local("v") Object v) {
            v = onSwitchEnter(self);
            return v != null;
        }

        @Patch.OnExit
        public static void exit(@Patch.This Object self, @Patch.Return(readOnly = false) boolean ret, @Patch.Local("v") Object v) {
            if (v != null) ret = (Boolean) v;
            else onSwitchExit(self, ret);
        }
    }

    @Patch(className = "zombie.gameStates.IngameState", methodName = "enter")
    public static class IngameEnter {
        @Patch.OnExit
        public static void exit() { onIngameEnter(); }
    }
}
