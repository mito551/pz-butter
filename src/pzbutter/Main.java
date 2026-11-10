package pzbutter;

public class Main {
    public static void main(String[] args) {
        DevAudit.report("main");
        java.util.Set<String> mods = ZbAccess.modSet();
        if (mods != null && !Preload.inModList(mods)) {
            System.out.println("[PZButter] not in the active mod list; nothing is switched on");
            return;
        }
        Settings.load();
        if (Settings.enabled) {
            long t0 = System.nanoTime();
            int n = ZbAccess.retransformLoadedTargets();
            System.out.println("[PZButter] patches: " + (n < 0 ? "no ZombieBuddy Instrumentation, early classes may stay unpatched"
                : "re-applied to " + n + " classes loaded before ZombieBuddy patched them (" + (System.nanoTime() - t0) / 1_000_000 + " ms)"));
        }
        ModCompat.init();   // before any feature init reads its key: modCompat=auto answers stock for the keys it switches off
        FrameClock.init();
        SpikeContext.init();
        PresentLog.init();
        LimiterSleep.init();
        RenderIdle.init();
        InputLatch.init();
        if (InputLatch.latch) SwapHold.serveDuringWait = DeliveryPatches::serveInputRequest;
        Vrr.init();
        SwapHold.init();
        Caps.init();
        GpuQueue.init();
        Reflex.init();
        VblankLock.init();
        CursorLatch.init();
        AimHold.init();
        System.out.println("[PZButter] " + Settings.VERSION + ", enabled=" + Settings.enabled);
        System.out.println("[PZButter] workers: " + Preload.status());
        if (Settings.flag("devCensus")) System.out.println("[PZButter] census: " + Census.status());
        LuaBudget.init();
        LuaFast.init();
        LuaTiming.init();
        HitchSampler.init(); ServerTick.init(); CellSpread.init(); NetStats.init();
        StallProbe.init();
        LuaGate.init();
        LuaPatches.initPoolPut();
        LuaPatches.initBodyLoc();
        ScriptPatches.initOnce();
        ScriptPatches.initKept();
        FrameBatch.init();
        LuaFiles.init();
        LogDedupe.init();
        LogPatches.initQuiet();
        LogPatches.initErrorBox();
        ZombiePatches.init();
        DevMarks.init();
        SceneDriver.init();
        RuntimePatches.init();
        Cores.init();
        GpuPatches.init();
        LoadFixes.init();
        GpuPstate.init();
        ModWatch.init();
        ShaderReuse.init();
        ModelPatches.init();
        WorldPatches.initDoorNpe();
        ChunkPatches.initChunkZExtents();
        VehicleZones.init();
        ScriptComments.init();
        FileKnobs.init();
        TexCompress.init();
        TexPatches.init();
        EnumPatches.init();
        GpuOverTarget.init();
        UiTiming.init();
        UiCensus.init();
        UiRetain.init();
        UiTick.init();
        TextWidths.init();
        MapFast.init();
        SoundTick.init();
        WorldSounds.init();
        SoundFixes.init();
        VehiclePatches.init();
        MpClientPatches.initDropItems();
        StreamPatches.init();
        RecalcMemo.init();
        CameraPatches.init();
        DriveTrace.init();
        ClockPatches.init();
        LightChecks.init();
        FramePhase.init();
        GetterMemo.init();
        PZButterSquares.init();
        GridGate.init();
        Cutaways.init();
        BakeHold.init();
        Weather.init();
        ChunkTrees.init();
        BakeMisc.init();
        EdgeTest.init();
        TranslucentBake.init();
        ZoomEase.init();
        AudioLimiter.init();
        RemoteVehicles.init();
        SoundPatches.onFmodInit();
        LuaPatches.uiLuaFast = Settings.flag("uiLuaFast");
        LuaPatches.modOptionsFast = Settings.flag("modOptionsFast");
        DistributionInsert.init();
    }

    /** True when the system class loader can load the class (not initialised). */
    public static boolean classPresent(String name) {
        try { Class.forName(name, false, Main.class.getClassLoader()); return true; }
        catch (ClassNotFoundException | LinkageError e) { return false; }
    }
}
