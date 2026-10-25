package pzbutter;

public class Main {
    public static void main(String[] args) {
        java.util.Set<String> mods = ZbAccess.modSet();
        if (mods != null && !Preload.inModList(mods)) {
            System.out.println("[PZButter] not in the active mod list; nothing is switched on");
            return;
        }
        Settings.load();
        ModCompat.init();   // before any feature init reads its key: modCompat=auto answers stock for the keys it switches off
        FrameClock.init();
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
        pzbutter.luabudget.Budget.init();
        LuaFast.init();
        LuaTiming.init();
        LuaGate.init();
        FrameBatch.init();
        LuaFiles.init();
        LogDedupe.init();
        ZombiePatches.init();
        GpuPatches.init();
        UiTiming.init();
        UiCensus.init();
        UiRetain.init();
        UiTick.init();
        TextWidths.init();
        MapFast.init();
        SoundTick.init();
        WorldSounds.init();
        SoundFixes.init();
        LightChecks.init();
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
        SoundPatches.onFmodInit();
        LuaPatches.uiLuaFast = Settings.flag("uiLuaFast");
    }

    /** True when the system class loader can load the class (not initialised). */
    public static boolean classPresent(String name) {
        try { Class.forName(name, false, Main.class.getClassLoader()); return true; }
        catch (ClassNotFoundException | LinkageError e) { return false; }
    }
}
