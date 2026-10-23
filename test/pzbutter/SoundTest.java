package pzbutter;

/** Plan 4 sound checks (Task 1: soundTickHz, emitterIdleSkip). Run from SelfTest.main; failures are AssertionErrors. */
final class SoundTest {
    private SoundTest() {}

    static void run() throws Exception {
        gateOpensAtSixtyHz();
        gateNeverSkipsAtSixtyFps();
        gateOffIsAlwaysOpen();
        idleSkipNeverSkipsQueuedSound();
        idleSkipOnlyZombies();
        soundKeysOffByDefault();
        cleanupSweepsBeforeRelease();
        hearingAttenuationMatchesStockConstants();
        hearingAttenuationExactValues();
        fishMemoOncePerMinute();
        zoneCacheHitsOnSameSquare();
        zoneCacheDoesNotHoldListener();
        levelRangeOverLoadedChunks();
        fliesDeferSameSquareIsNoToggle();
        fliesDeferClearsOldSquare();
        nullHandleOnlyInsideAddSound();
        limiterFindsLibraryOnPath();
        limiterCeilingClamped();
        limiterAttachesOnceWhenFmodIsUp();
        stereoFoldOnByDefault();
        forceParamsOnlyForStartingSound();
        skipTickHonoursForce();
        gateOpensAfterClockStepsBack();
        skipTickOffWhenFrameClockOff();
        fliesPendingClearRunsWhenGuardOff();
    }

    static void forceParamsOnlyForStartingSound() {
        boolean[] b = {false, true};
        for (boolean tick : b) for (boolean zombie : b) for (boolean starting : b)
            assert SoundTick.forceParams(tick, zombie, starting) == (tick && zombie && starting) : tick + " " + zombie + " " + starting;
    }

    static void skipTickHonoursForce() {
        SoundTick.resetForTest(60);
        SoundTick.on = true;
        SoundPatches.tickGuard.on = true;
        FrameClock.on = true;
        FrameClock.frameNo = 7;
        assert SoundTick.gateOpen(6, 1000);
        assert !SoundTick.gateOpen(7, 1001);       // the gate is closed for frame 7
        assert SoundPatches.skipTick();
        SoundTick.forceBegin();
        try { assert !SoundPatches.skipTick(); }   // inside the force window the parameters update for real
        finally { SoundTick.forceEnd(); }
        assert SoundPatches.skipTick();
        SoundTick.on = false;
        SoundPatches.tickGuard.on = false;
        FrameClock.on = false;
    }

    static void gateOpensAfterClockStepsBack() {
        SoundTick.resetForTest(60);
        assert SoundTick.gateOpen(1, 10_000);
        assert SoundTick.gateOpen(2, 5_000);       // the wall clock went back: open, not closed for good
    }

    static void skipTickOffWhenFrameClockOff() {
        SoundTick.resetForTest(60);
        SoundTick.on = true;
        SoundPatches.tickGuard.on = true;
        FrameClock.frameNo = 7;
        assert SoundTick.gateOpen(6, 1000);
        assert !SoundTick.gateOpen(7, 1001);       // closed for frame 7
        FrameClock.on = false;
        assert !SoundPatches.skipTick();           // a stopped frame counter never freezes a closed memo
        FrameClock.on = true;
        assert SoundPatches.skipTick();
        SoundTick.on = false;
        SoundPatches.tickGuard.on = false;
        FrameClock.on = false;
    }

    static void fliesPendingClearRunsWhenGuardOff() {
        SoundFixes.resetForTest();
        SoundPatches.fliesGuard.on = false;
        Object sq = new Object();
        SoundFixes.fliesEnter();
        assert SoundFixes.fliesSet(sq, false);     // a deferred clear is pending
        SoundPatches.onFliesExit();                // runs the clear (the non-square here fails inside, and is caught)
        assert SoundFixes.fliesExit() == null : "a pending clear survived the update's exit";
    }

    static void limiterFindsLibraryOnPath() throws Exception {
        java.nio.file.Path d = java.nio.file.Files.createTempDirectory("pzb-natives");
        java.nio.file.Files.createFile(d.resolve("libfmod.so"));
        assert AudioLimiter.findLibrary("/nope:" + d, "Linux").equals(d.resolve("libfmod.so"));
        assert AudioLimiter.findLibrary("/nope", "Linux") == null;
        java.nio.file.Files.createFile(d.resolve("fmod.dll"));
        assert AudioLimiter.findLibrary(d.toString(), "Windows 11").equals(d.resolve("fmod.dll"));
    }

    static void limiterAttachesOnceWhenFmodIsUp() {
        assert SoundPatches.fmodUp(false, 7L);                      // FMODManager.init ran to its end: the group handle is set
        assert !SoundPatches.fmodUp(false, 0L) && !SoundPatches.fmodUp(true, 7L);   // init not run, or sound disabled
        AudioLimiter.resetForTest();
        assert AudioLimiter.claim() && !AudioLimiter.claim();       // the init call and the hook can both ask; only one wins
    }

    static void limiterCeilingClamped() {
        assert AudioLimiter.ceiling(-2) == -2f && AudioLimiter.ceiling(5) == 0f && AudioLimiter.ceiling(-40) == -12f;
    }

    static void stereoFoldOnByDefault() {
        Settings.load(SelfTest.write("enabled=true\n"));
        assert Settings.flag("audioLimiterStereoFold");
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

    static void cleanupSweepsBeforeRelease() {
        WorldSounds.resetForTest();
        assert !WorldSounds.needsChunkSweep();
        WorldSounds.afterCountdown(true);            // frame N: a sound reached life 0
        assert WorldSounds.needsChunkSweep();        // frame N+1 sweeps chunks before its countdown releases it
        WorldSounds.afterCountdown(false);
        assert !WorldSounds.needsChunkSweep();
    }

    static void hearingAttenuationMatchesStockConstants() {
        // same room, both indoors: plain distance falloff; z counts three times
        float a = WorldSounds.attenuation(10, 0, 0, 0, 0, 1, 50f, true, true, false);
        float b = WorldSounds.attenuation(10, 0, 0, 0, 0, 0, 50f, true, true, false);
        assert a < b;
        float sameRoom = WorldSounds.attenuation(10, 0, 0, 0, 0, 0, 50f, true, true, false);
        float otherRoom = WorldSounds.attenuation(10, 0, 0, 0, 0, 0, 50f, true, false, false);
        float oneOutdoors = WorldSounds.attenuation(10, 0, 0, 0, 0, 0, 50f, true, false, true);
        assert otherRoom < sameRoom && oneOutdoors < otherRoom;
    }

    static void hearingAttenuationExactValues() {
        // hand-computed from stock: ratio = 100 / 50^2 = 0.04; other room x1.2; one outdoors x1.2 x1.4
        assert Math.abs(WorldSounds.attenuation(10, 0, 0, 0, 0, 0, 50f, true, true, false) - 0.96f) < 1e-4f;
        assert Math.abs(WorldSounds.attenuation(10, 0, 0, 0, 0, 0, 50f, true, false, true) - 0.9328f) < 1e-4f;
    }

    static void fishMemoOncePerMinute() {
        WorldSounds.resetForTest();
        assert !WorldSounds.fishSeen(100, 200, 16, 5);
        assert WorldSounds.fishSeen(100, 200, 16, 5);
        assert !WorldSounds.fishSeen(100, 200, 16, 6);
        assert !WorldSounds.fishSeen(101, 200, 16, 6);
    }

    static void zoneCacheHitsOnSameSquare() {
        SoundFixes.resetForTest();
        Object p = new Object(), l = new Object();
        assert SoundFixes.zoneCached(p, l, 10, 20, 0) == null;
        SoundFixes.zoneStore(p, l, 10, 20, 0, 7f);
        assert SoundFixes.zoneCached(p, l, 10, 20, 0) == 7f;
        assert SoundFixes.zoneCached(p, l, 11, 20, 0) == null;          // listener moved a square
        assert SoundFixes.zoneCached(p, new Object(), 10, 20, 0) == null; // another listener
        assert SoundFixes.zoneCached(p, l, 10, 20, 1) == null;          // a zone was added or removed
    }

    static void zoneCacheDoesNotHoldListener() {
        SoundFixes.resetForTest();
        Object p = new Object();
        Object l = new Object();
        SoundFixes.zoneStore(p, l, 10, 20, 0, 7f);
        java.lang.ref.WeakReference<Object> ref = new java.lang.ref.WeakReference<>(l);
        l = null;
        long end = System.currentTimeMillis() + 2000;
        while (ref.get() != null && System.currentTimeMillis() < end) {
            System.gc();
            try { Thread.sleep(20); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        assert ref.get() == null : "the zone cache keeps the listener alive";
        assert p != null;   // the parameter key is still in use here
    }

    static void levelRangeOverLoadedChunks() {
        int[] r = SoundFixes.levelRange(new int[] {0, -1, 0}, new int[] {2, 0, 5});
        assert r[0] == -1 && r[1] == 5;
        assert SoundFixes.levelRange(new int[0], new int[0]) == null;      // nothing loaded: nothing to scan
    }

    static void fliesDeferSameSquareIsNoToggle() {
        SoundFixes.resetForTest();
        Object sq = new Object();
        SoundFixes.fliesEnter();
        assert SoundFixes.fliesSet(sq, false);     // the clear is deferred
        SoundFixes.fliesSet(sq, true);             // the set runs (stock no-op: the value is still true)
        assert SoundFixes.fliesExit() == null;     // same square: nothing to clear
    }

    static void fliesDeferClearsOldSquare() {
        SoundFixes.resetForTest();
        Object oldSq = new Object(), newSq = new Object();
        SoundFixes.fliesEnter();
        assert SoundFixes.fliesSet(oldSq, false);
        SoundFixes.fliesSet(newSq, true);
        assert SoundFixes.fliesExit() == oldSq;    // cleared for real, once
        assert SoundFixes.fliesExit() == null;
    }

    static void nullHandleOnlyInsideAddSound() {
        assert SoundFixes.nullHandle(0, 1) == -1;
        assert SoundFixes.nullHandle(0, 0) == 0;   // Helicopter and others: stock
        assert SoundFixes.nullHandle(12345, 1) == 12345 && SoundFixes.nullHandle(-3, 1) == -3;
    }
}
