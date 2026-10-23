package pzbutter;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.BaseCharacterSoundEmitter;
import zombie.audio.FMODParameterUtils;
import zombie.characters.IsoGameCharacter;
import zombie.WorldSoundManager.ResultBiggestSound;
import zombie.characters.IsoZombie;
import zombie.core.Core;
import fmod.fmod.FMODManager;

/**
 * Sound hooks (Plan 4), one holder per game method; each advice is one call to a public static method here
 * (advice is inlined into the game class, so it may only reach public members).
 */
public final class SoundPatches {
    private SoundPatches() {}

    static final Guard tickGuard = new Guard("soundTickHz");
    static final Guard idleGuard = new Guard("emitterIdleSkip");
    static final Guard cleanupGuard = new Guard("worldSoundCleanupFast");
    static final Guard hearingGuard = new Guard("hearingHoist");
    static final Guard fishGuard = new Guard("worldSoundFast");
    static final Guard zoneGuard = new Guard("soundZoneCache");
    static final Guard electricityGuard = new Guard("electricityLevelRange");
    static final Guard fliesGuard = new Guard("fliesToggleFix");
    static final Guard nullGuard = new Guard("fmodNullHandleFix");

    /** The ambience and parameter update entries: true skips this call (the shared gate is closed this frame). */
    public static boolean skipTick() {
        if (!tickGuard.on || !SoundTick.on || !FrameClock.on) return false;   // a stopped frame counter can't freeze the memo
        try {
            if (SoundTick.forcing()) return false;
            long f = FrameClock.frameNo;
            int m = SoundTick.memo(f);
            if (m >= 0) return m == 0;
            return !SoundTick.gateOpen(f, SoundTick.nowMs());
        } catch (Throwable t) { tickGuard.fail(t); return false; }
    }

    /**
     * IsoGameCharacter.updateEmitter entry: true skips a silent zombie's parameter list, bone lookup and ticks.
     * The emitter is still positioned, so a later file-based clip starts where the zombie is.
     */
    public static boolean skipEmitter(Object character) {
        boolean idleOn = SoundTick.idle && idleGuard.on, tickOn = SoundTick.on && tickGuard.on;
        if (!idleOn && !tickOn) return false;
        try {
            IsoGameCharacter c = (IsoGameCharacter) character;
            boolean zombie = c instanceof IsoZombie;
            BaseCharacterSoundEmitter e = c.emitter;
            if (SoundTick.forceParams(tickOn, zombie, zombie && e.hasSoundsToStart())) {
                // a sound is queued and the gate may skip the parameter list: update it for real before the stock path starts the sound
                SoundTick.forceBegin();
                try { c.getFMODParameters().update(); }
                finally { SoundTick.forceEnd(); }
                return false;
            }
            if (!idleOn || !SoundTick.skipEmitter(zombie, zombie && e.isClear())) return false;
            e.set(c.getX(), c.getY(), c.getZ());
            return true;
        } catch (Throwable t) { (idleOn ? idleGuard : tickGuard).fail(t); return false; }
    }

    /**
     * WorldSoundManager.update entry: true skips the stock body. A failure returns true too, because the countdown may
     * already have released sounds, and running the stock body after that would count them down twice.
     */
    public static boolean onWorldUpdate() {
        if (!cleanupGuard.on) return false;
        try { WorldSounds.update(); return true; }
        catch (Throwable t) { cleanupGuard.fail(t); return true; }
    }

    /** getBiggestSoundZomb entry: a non-null result replaces the stock body; null runs stock (it has no side effects). */
    public static ResultBiggestSound onBiggestSoundZomb(int x, int y, int z, boolean ignoreZombieSources, IsoZombie zombie) {
        if (!hearingGuard.on) return null;
        try { return WorldSounds.biggestSoundZomb(x, y, z, ignoreZombieSources, zombie); }
        catch (Throwable t) { hearingGuard.fail(t); return null; }
    }

    /** addSoundNoise entry: true skips the fish walk, the same (x, y, r) was walked in this game minute. */
    public static boolean onFishNoise(int x, int y, int r) {
        if (!fishGuard.on) return false;
        try { return WorldSounds.fishSeen(x, y, r, WorldSounds.gameMinute()); }
        catch (Throwable t) { fishGuard.fail(t); return false; }
    }

    /** ParameterZone.calculateCurrentValue entry: the cached value on a hit, null on a miss (the stock body then runs). */
    public static Float onZoneEnter(Object param) {
        if (!zoneGuard.on) return null;
        try {
            IsoGameCharacter l = FMODParameterUtils.getFirstListener();
            if (l == null) return null;
            return SoundFixes.zoneCached(param, l, SoundFixes.square(l.getX()), SoundFixes.square(l.getY()),
                                         SoundFixes.zoneVersion());
        } catch (Throwable t) { zoneGuard.fail(t); return null; }
    }

    /** ParameterZone.calculateCurrentValue exit on a miss: stores the value the stock body just computed. */
    public static void onZoneExit(Object param, float value) {
        if (!zoneGuard.on) return;
        try {
            IsoGameCharacter l = FMODParameterUtils.getFirstListener();
            if (l == null) return;
            SoundFixes.zoneStore(param, l, SoundFixes.square(l.getX()), SoundFixes.square(l.getY()),
                                 SoundFixes.zoneVersion(), value);
        } catch (Throwable t) { zoneGuard.fail(t); }
    }

    /** IsoMetaGrid.addZone / removeZone exit: the zone set changed, so cached zone values are stale. */
    public static void onZonesChanged() {
        if (!zoneGuard.on) return;
        try { SoundFixes.zonesChanged(); }
        catch (Throwable t) { zoneGuard.fail(t); }
    }

    /** AmbientStreamManager.checkHaveElectricity entry: true skips stock after the re-implemented scan (also on failure). */
    public static boolean onCheckHaveElectricity() {
        if (!electricityGuard.on) return false;
        try { SoundFixes.checkHaveElectricity(); return true; }
        catch (Throwable t) { electricityGuard.fail(t); return true; }
    }

    /** ChunkLevelData.update entry: opens the update's deferred-clear state. */
    public static void onFliesEnter() {
        if (!fliesGuard.on) return;
        try { SoundFixes.fliesEnter(); }
        catch (Throwable t) { fliesGuard.fail(t); }
    }

    /** ChunkLevelData.update exit (also on a throwable): clears the square whose clear was deferred, for real. */
    public static void onFliesExit() {
        try { SoundFixes.clearPendingFlies(); }
        catch (Throwable t) { fliesGuard.fail(t); }
    }

    /** IsoGridSquare.setHasFlies entry: true skips a clear deferred by the current update. */
    public static boolean onFliesSet(Object square, boolean value) {
        if (!fliesGuard.on) return false;
        try { return SoundFixes.fliesSet(square, value); }
        catch (Throwable t) { fliesGuard.fail(t); return false; }
    }

    /** FMODSoundEmitter.addSound entry and exit: the NULL-handle depth counter. */
    public static void onAddSoundEnter() {
        if (!nullGuard.on) return;
        try { SoundFixes.addSoundEnter(); }
        catch (Throwable t) { nullGuard.fail(t); }
    }

    public static void onAddSoundExit() {
        if (!nullGuard.on) return;
        try { SoundFixes.addSoundExit(); }
        catch (Throwable t) { nullGuard.fail(t); }
    }

    /** FMOD_Studio_System_CreateEventInstance exit: a 0 handle becomes -1 inside addSound only. */
    public static long onEventInstance(long handle) {
        if (!nullGuard.on) return handle;
        try { return SoundFixes.nullHandle(handle, SoundFixes.addSoundDepth()); }
        catch (Throwable t) { nullGuard.fail(t); return handle; }
    }

    @Patch(className = "zombie.audio.FMODParameter", methodName = "update")
    public static class ParameterUpdate {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter() { return skipTick(); }
    }

    @Patch(className = "zombie.audio.ObjectAmbientEmitters", methodName = "update")
    public static class ObjectEmittersUpdate {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter() { return skipTick(); }
    }

    @Patch(className = "zombie.audio.FMODAmbientWalls", methodName = "update")
    public static class AmbientWallsUpdate {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter() { return skipTick(); }
    }

    @Patch(className = "zombie.characters.IsoGameCharacter", methodName = "updateEmitter")
    public static class EmitterIdle {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object self) { return skipEmitter(self); }
    }

    @Patch(className = "zombie.WorldSoundManager", methodName = "update")
    public static class WorldUpdate {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter() { return onWorldUpdate(); }
    }

    @Patch(className = "zombie.WorldSoundManager", methodName = "getBiggestSoundZomb")
    public static class BiggestSoundZomb {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) int x, @Patch.Argument(1) int y, @Patch.Argument(2) int z,
                                    @Patch.Argument(3) boolean ignoreZombieSources, @Patch.Argument(4) IsoZombie zombie,
                                    @Patch.Local("v") Object v) {
            v = onBiggestSoundZomb(x, y, z, ignoreZombieSources, zombie);
            return v != null;
        }

        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) ResultBiggestSound ret, @Patch.Local("v") Object v) {
            if (v != null) ret = (ResultBiggestSound) v;
        }
    }

    @Patch(className = "zombie.iso.FishSchoolManager", methodName = "addSoundNoise")
    public static class FishNoise {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) int x, @Patch.Argument(1) int y, @Patch.Argument(2) int r) {
            return onFishNoise(x, y, r);
        }
    }

    @Patch(className = "zombie.audio.parameters.ParameterZone", methodName = "calculateCurrentValue")
    public static class ZoneValue {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object self, @Patch.Local("v") Object v) {
            v = onZoneEnter(self);
            return v != null;
        }

        @Patch.OnExit
        public static void exit(@Patch.This Object self, @Patch.Return(readOnly = false) float ret,
                                @Patch.Local("v") Object v) {
            if (v != null) ret = (Float) v;
            else onZoneExit(self, ret);
        }
    }

    @Patch(className = "zombie.iso.IsoMetaGrid", methodName = "addZone")
    public static class ZoneAdded {
        @Patch.OnExit
        public static void exit() { onZonesChanged(); }
    }

    @Patch(className = "zombie.iso.IsoMetaGrid", methodName = "removeZone")
    public static class ZoneRemoved {
        @Patch.OnExit
        public static void exit() { onZonesChanged(); }
    }

    @Patch(className = "zombie.AmbientStreamManager", methodName = "checkHaveElectricity")
    public static class Electricity {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter() { return onCheckHaveElectricity(); }
    }

    @Patch(className = "zombie.FliesSound$ChunkLevelData", methodName = "update")
    public static class FliesUpdate {
        @Patch.OnEnter
        public static void enter() { onFliesEnter(); }

        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit() { onFliesExit(); }
    }

    @Patch(className = "zombie.iso.IsoGridSquare", methodName = "setHasFlies")
    public static class FliesSet {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object self, @Patch.Argument(0) boolean value) {
            return onFliesSet(self, value);
        }
    }

    @Patch(className = "fmod.fmod.FMODSoundEmitter", methodName = "addSound")
    public static class AddSound {
        @Patch.OnEnter
        public static void enter() { onAddSoundEnter(); }

        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit() { onAddSoundExit(); }
    }

    /** FMODManager.init ran to its end: it sets the in-game channel group last, and sets soundDisabled when it gives up. */
    static boolean fmodUp(boolean soundDisabled, long inGameGroup) {
        return !soundDisabled && inGameGroup != 0;
    }

    /**
     * Attaches audioLimiter when its key is on, the game has sound and the master group exists. Called from Main (ZB
     * loads pzbutter after FMODManager.init returned, so its exit hook is too late) and from that hook (FMOD up later).
     */
    public static void onFmodInit() {
        if (!AudioLimiter.on) return;
        try {
            if (!fmodUp(Core.soundDisabled, FMODManager.instance.channelGroupInGameNonBankSounds)) return;
            long master = fmod.javafmod.FMOD_System_GetMasterChannelGroup();
            if (master != 0) AudioLimiter.install(master);
        } catch (Throwable t) { System.out.println("[PZButter] audioLimiter: " + t); }
    }

    @Patch(className = "fmod.javafmod", methodName = "FMOD_Studio_System_CreateEventInstance")
    public static class EventInstance {
        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) long ret) { ret = onEventInstance(ret); }
    }

    @Patch(className = "fmod.fmod.FMODManager", methodName = "init")
    public static class LimiterInit {
        @Patch.OnExit
        public static void exit() { onFmodInit(); }
    }
}
