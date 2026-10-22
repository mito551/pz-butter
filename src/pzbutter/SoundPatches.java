package pzbutter;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.BaseCharacterSoundEmitter;
import zombie.characters.IsoGameCharacter;
import zombie.WorldSoundManager.ResultBiggestSound;
import zombie.characters.IsoZombie;

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

    /** The ambience and parameter update entries: true skips this call (the shared gate is closed this frame). */
    public static boolean skipTick() {
        if (!SoundTick.on || !tickGuard.on) return false;
        try { return !SoundTick.gateOpen(FrameClock.frameNo, System.currentTimeMillis()); }
        catch (Throwable t) { tickGuard.fail(t); return false; }
    }

    /**
     * IsoGameCharacter.updateEmitter entry: true skips a silent zombie's parameter list, bone lookup and ticks.
     * The emitter is still positioned, so a later file-based clip starts where the zombie is.
     */
    public static boolean skipEmitter(Object character) {
        if (!SoundTick.idle || !idleGuard.on) return false;
        try {
            IsoGameCharacter c = (IsoGameCharacter) character;
            boolean zombie = c instanceof IsoZombie;
            BaseCharacterSoundEmitter e = c.emitter;
            if (!SoundTick.skipEmitter(zombie, zombie && e.isClear())) return false;
            e.set(c.getX(), c.getY(), c.getZ());
            return true;
        } catch (Throwable t) { idleGuard.fail(t); return false; }
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
}
