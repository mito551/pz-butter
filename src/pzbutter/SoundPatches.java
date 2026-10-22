package pzbutter;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.BaseCharacterSoundEmitter;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoZombie;

/**
 * Sound hooks (Plan 4), one holder per game method; each advice is one call to a public static method here
 * (advice is inlined into the game class, so it may only reach public members).
 */
public final class SoundPatches {
    private SoundPatches() {}

    static final Guard tickGuard = new Guard("soundTickHz");
    static final Guard idleGuard = new Guard("emitterIdleSkip");

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
}
