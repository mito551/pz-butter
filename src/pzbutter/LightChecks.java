package pzbutter;

import java.lang.invoke.VarHandle;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.WeakHashMap;
import zombie.core.Core;
import zombie.core.PerformanceSettings;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.LightingJNI;
import zombie.iso.fboRenderChunk.FBORenderCell;
import zombie.iso.fboRenderChunk.FBORenderChunk;
import zombie.iso.fboRenderChunk.FBORenderLevels;

/**
 * Plan 5a lighting checks (Task 1): occlusionSkipLightingOnly, lightInfoOncePerFrame, lightSwitchCheckFrames and
 * lightingFps. The pure cores are tested in WorldTest; the holders in WorldPatches call them.
 */
final class LightChecks {
    private LightChecks() {}

    /** Every dirty flag except the lighting one (DIRTY_LIGHTING = 32). */
    static final long NON_LIGHT = ~FBORenderChunk.DIRTY_LIGHTING;

    static int switchTtl;
    static int lightingFps;
    private static boolean fpsDone;

    // private game members, read through handles (null when a member is missing: stock runs)
    private static VarHandle perPlayer, onScreenChunks;
    private static VarHandle square, updateTick, playerIndex;

    static void init() {
        WorldPatches.occlusionGuard.on = Settings.flag("occlusionSkipLightingOnly");
        WorldPatches.infoGuard.on = Settings.flag("lightInfoOncePerFrame");
        switchTtl = Settings.num("lightSwitchCheckFrames", 0, 0, 120);
        WorldPatches.switchGuard.on = Settings.enabled && switchTtl > 0;
        lightingFps = Settings.num("lightingFps", 0, 0, 120);
        WorldPatches.fpsGuard.on = Settings.enabled && lightingFps > 0;
        if (Settings.enabled) initHandles();
    }

    private static void initHandles() {
        try {
            Class<?> data = Class.forName("zombie.iso.fboRenderChunk.FBORenderCell$PerPlayerData", false,
                                          FBORenderCell.class.getClassLoader());
            perPlayer = Game.field(FBORenderCell.class, "perPlayerData", Array.newInstance(data, 0).getClass());
            onScreenChunks = Game.field(data, "onScreenChunks", ArrayList.class);
            square = Game.field(LightingJNI.JNILighting.class, "square", IsoGridSquare.class);
            updateTick = Game.field(LightingJNI.JNILighting.class, "updateTick", int.class);
            playerIndex = Game.field(LightingJNI.JNILighting.class, "playerIndex", int.class);
        } catch (Throwable t) {
            System.out.println("[PZButter] lighting handles not found: " + t);
        }
    }

    /** True when some on-screen level is dirty with a flag other than lighting. Levels dirty only for lighting do not count. */
    static boolean anyDirtyIgnoringLighting(long[] dirtyFlags, boolean[] dirty, boolean[] onScreen) {
        for (int i = 0; i < dirty.length; i++)
            if (onScreen[i] && dirty[i] && (dirtyFlags[i] & NON_LIGHT) != 0) return true;
        return false;
    }

    /**
     * FBORenderCell.hasAnyDirtyChunkTextures for player p: the same walk over the on-screen chunks, asking each on-screen
     * level for dirt outside the lighting flag. Null when a handle is missing (the stock method then runs).
     */
    static Boolean anyDirtyOutsideLighting(Object cell, int p) {
        if (perPlayer == null || onScreenChunks == null) return null;
        Object[] players = (Object[]) perPlayer.get(cell);
        ArrayList<?> chunks = (ArrayList<?>) onScreenChunks.get(players[p]);
        float zoom = Core.getInstance().getZoom(p);
        for (int i = 0; i < chunks.size(); i++) {
            IsoChunk c = (IsoChunk) chunks.get(i);
            FBORenderLevels levels = c.getRenderLevels(p);
            for (int lvl = c.minLevel; lvl <= c.maxLevel; lvl++)
                if (levels.isOnScreen(lvl) && levels.isDirty(lvl, NON_LIGHT, zoom)) return Boolean.TRUE;
        }
        return Boolean.FALSE;
    }

    /** True when a square's stamp should move to this counter: a real counter that differs from the stamp. */
    static boolean stampClean(int updateTick, int counter) {
        return counter != -1 && updateTick != counter;
    }

    /** JNILighting.updateFBORenderChunk exit: a clean square takes the current counter, so later queries skip the native call. */
    static void lightInfoStamp(Object jni) {
        if (square == null || updateTick == null || playerIndex == null) return;
        IsoGridSquare sq = (IsoGridSquare) square.get(jni);
        if (sq.chunk == null) return;
        int counter = LightingJNI.getUpdateCounter((int) playerIndex.get(jni));
        int tick = (int) updateTick.get(jni);
        if (stampClean(tick, counter)) updateTick.set(jni, counter);
    }

    // lightSwitchCheckFrames: the answer per switch, with the frame it was asked on. Weak keys; identity (IsoObject
    // does not override equals).
    private static final WeakHashMap<Object, long[]> switches = new WeakHashMap<>();

    /** The stored answer when it is younger than ttl frames, else null (ask again). */
    static synchronized Boolean switchMemo(Object sw, long frame, int ttl) {
        long[] e = switches.get(sw);
        if (e == null) return null;
        long age = frame - e[0];
        if (age < 0 || age >= ttl) return null;
        return e[1] != 0 ? Boolean.TRUE : Boolean.FALSE;
    }

    static synchronized void switchStore(Object sw, long frame, boolean answer) {
        switches.put(sw, new long[] {frame, answer ? 1 : 0});
    }

    static synchronized void resetForTest() { switches.clear(); }

    /** IngameState.enter exit: the lighting thread's rate, set once per run (the game clamps it to 1..120). */
    static void applyLightingFps() {
        if (fpsDone) return;
        PerformanceSettings.instance.setLightingFPS(lightingFps);
        fpsDone = true;
    }
}
