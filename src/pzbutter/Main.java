package pzbutter;

public class Main {
    public static void main(String[] args) {
        Settings.load();
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
        pzbutter.luabudget.Budget.init();
        LuaFast.init();
        LuaTiming.init();
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
        Weather.init();
        ChunkTrees.init();
        BakeMisc.init();
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
