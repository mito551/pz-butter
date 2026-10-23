package pzbutter;

import java.util.ArrayList;
import zombie.iso.IsoChunk;
import zombie.iso.IsoChunkLevel;
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
    static final Guard cutawayGuard = new Guard("cutawayVisit");
    static final Guard fastGuard = new Guard("cutawayFast");
    static final Guard splashGuard = new Guard("rainSplashesFast");
    static final Guard maskGuard = new Guard("weatherMaskIdleSkip");

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

    /** FBORenderCutaways.doCutawayVisitSquares(int, ArrayList) entry: true when the rewrite ran (stock skipped); false runs stock. */
    public static boolean onCutawayEnter(Object cutaways, int p, ArrayList<?> chunks) {
        if (!cutawayGuard.on) return false;
        try { return Cutaways.visit(cutaways, p, chunks); }
        catch (Throwable t) { cutawayGuard.fail(t); return false; }
    }

    /** Public overload only: the private (IsoGridSquare, long, ArrayList) overload fails the int/ArrayList argument filter. */
    @Patch(className = "zombie.iso.fboRenderChunk.FBORenderCutaways", methodName = "doCutawayVisitSquares")
    public static class CutawayVisit {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object self, @Patch.Argument(0) int p, @Patch.Argument(1) ArrayList<?> chunks) {
            return onCutawayEnter(self, p, chunks);
        }
    }

    /** ChunkLevelData.calculateOccludingSquares entry: TRUE when a current stored mask was replayed (stock skipped); null runs stock. */
    public static Boolean onOccludingEnter(Object level, int p, int gx1, int gy1, int gx2, int gy2, int[] grid) {
        if (!fastGuard.on) return null;
        try { return Cutaways.occludingEnter(level, p, gx1, gy1, gx2, gy2, grid); }
        catch (Throwable t) { fastGuard.fail(t); return null; }
    }

    /** calculateOccludingSquares exit after a stock compute: marks the stored mask current for its box. */
    public static void onOccludingExit(Object level, int p) {
        if (!fastGuard.on) return;
        try { Cutaways.occludingExit(level, p); }
        catch (Throwable t) { fastGuard.fail(t); }
    }

    /** prepareChunkForUpdating exit: the chunk's stored masks for player p are stale. */
    public static void onChunkPrepared(int p, IsoChunk chunk) {
        if (!fastGuard.on || chunk == null) return;
        try { Cutaways.markChunkStale(p, chunk); }
        catch (Throwable t) { fastGuard.fail(t); }
    }

    /** ChunkLevelData.calculateOccludingSquares: a replayed mask skips stock (returns false); a stock compute marks its box. */
    @Patch(className = "zombie.iso.fboRenderChunk.FBORenderCutaways$ChunkLevelData", methodName = "calculateOccludingSquares")
    public static class OccluderReplay {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object self, @Patch.Argument(0) int p, @Patch.Argument(1) int gx1,
                                    @Patch.Argument(2) int gy1, @Patch.Argument(3) int gx2, @Patch.Argument(4) int gy2,
                                    @Patch.Argument(5) int[] grid, @Patch.Local("v") Object v) {
            v = onOccludingEnter(self, p, gx1, gy1, gx2, gy2, grid);
            return v != null;
        }

        @Patch.OnExit
        public static void exit(@Patch.This Object self, @Patch.Argument(0) int p, @Patch.Return(readOnly = false) boolean ret,
                                @Patch.Local("v") Object v) {
            if (v != null) ret = false;   // replayed: the mask did not change
            else onOccludingExit(self, p);
        }
    }

    /** FBORenderCell.prepareChunkForUpdating exit: every level of the chunk is stale for that player. */
    @Patch(className = "zombie.iso.fboRenderChunk.FBORenderCell", methodName = "prepareChunkForUpdating")
    public static class ChunkPrepared {
        @Patch.OnExit
        public static void exit(@Patch.Argument(0) int p, @Patch.Argument(1) IsoChunk chunk) { onChunkPrepared(p, chunk); }
    }

    @Patch(className = "zombie.gameStates.IngameState", methodName = "enter")
    public static class IngameEnter {
        @Patch.OnExit
        public static void exit() { onIngameEnter(); }
    }

    /** IsoChunkLevel.updateRainSplashes entry: true when the rewrite ran (stock skipped); false runs stock. */
    public static boolean onSplashEnter(IsoChunkLevel level) {
        if (!splashGuard.on) return false;
        try { Weather.splashes(level); return true; }
        catch (Throwable t) { splashGuard.fail(t); return false; }
    }

    /** WeatherFxMask.renderFxMask entry: true when no weather is on screen (stock skipped); false runs stock. */
    public static boolean onMaskEnter() {
        if (!maskGuard.on) return false;
        try { return Weather.maskSkip(); }
        catch (Throwable t) { maskGuard.fail(t); return false; }
    }

    @Patch(className = "zombie.iso.IsoChunkLevel", methodName = "updateRainSplashes")
    public static class RainSplashes {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This IsoChunkLevel self) { return onSplashEnter(self); }
    }

    @Patch(className = "zombie.iso.weather.fx.WeatherFxMask", methodName = "renderFxMask")
    public static class WeatherMask {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter() { return onMaskEnter(); }
    }
}
