package pzbutter;

/** Plan 4 sound checks (Task 1: soundTickHz, emitterIdleSkip). Run from SelfTest.main; failures are AssertionErrors. */
final class SoundTest {
    private SoundTest() {}

    static void run() {
        gateOpensAtSixtyHz();
        gateNeverSkipsAtSixtyFps();
        gateOffIsAlwaysOpen();
        idleSkipNeverSkipsQueuedSound();
        idleSkipOnlyZombies();
        soundKeysOffByDefault();
    }

    static void gateOpensAtSixtyHz() {
        SoundTick.resetForTest(60);                 // interval 1000/60 - 1 = 15 ms
        assert SoundTick.gateOpen(1, 1000);         // first frame opens
        assert SoundTick.gateOpen(1, 1001);         // same frame, same answer
        assert !SoundTick.gateOpen(2, 1008);        // 8 ms later: closed
        assert SoundTick.gateOpen(3, 1016);         // 16 ms after the last opening
        assert SoundTick.gateOpen(3, 1040);         // same frame keeps its first answer (open)
    }

    static void gateNeverSkipsAtSixtyFps() {
        SoundTick.resetForTest(60);
        for (int f = 0; f < 600; f++) assert SoundTick.gateOpen(f, f * 1000L / 60) : f;   // 16-17 ms frames
    }

    static void gateOffIsAlwaysOpen() {
        SoundTick.resetForTest(0);
        assert SoundTick.gateOpen(1, 0) && SoundTick.gateOpen(2, 1);
    }

    static void idleSkipNeverSkipsQueuedSound() {
        assert SoundTick.skipEmitter(true, true);
        assert !SoundTick.skipEmitter(true, false);   // a queued or playing sound: full path, parameters fresh before tick
    }

    static void idleSkipOnlyZombies() {
        assert !SoundTick.skipEmitter(false, true);   // players, animals
    }

    static void soundKeysOffByDefault() {
        Settings.load(SelfTest.write("enabled=true\n"));
        for (String k : new String[] {"emitterIdleSkip", "worldSoundCleanupFast", "hearingHoist", "worldSoundFast", "soundZoneCache",
                                      "electricityLevelRange", "fliesToggleFix", "fmodNullHandleFix", "corpseFliesCap", "audioLimiter"})
            assert !Settings.flag(k) : k;
        assert Settings.num("soundTickHz", 0, 0, 240) == 0;
    }
}
