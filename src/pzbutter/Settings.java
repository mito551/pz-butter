package pzbutter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.util.Locale;
import java.util.Properties;

/** Zomboid/PZButter.properties. Read once at mod load; any failure leaves every feature off. */
public final class Settings {
    static final String VERSION = "0.1";

    private static final String DEFAULTS =
        "# PZ Butter. Read at game start.\n"
      + "#\n"
      + "# enabled: master switch. When false every feature below stays off and the game runs stock.\n"
      + "enabled=false\n"
      + "#\n"
      + "# frameLog: one line per frame in Zomboid/pzbutter/frames.out:\n"
      + "#   <epoch ms> <frame no> <ms since the last frame started> <ms in the frame step> <its main-thread CPU ms>\n"
      + "# and per presented frame in present.out, per second in threads.out.\n"
      + "frameLog=false\n"
      + "#\n"
      + "# Frame delivery. Modes take off, on or auto. limiterSleep, the three input keys, reflexSleep and\n"
      + "# vsyncAdaptive are on by default: they passed in-game checks. Everything else stays off until you switch it on.\n"
      + "# limiterSleep: the frame limiter sleeps until 0.2 ms before the frame instead of spinning.\n"
      + "limiterSleep=true\n"
      + "# renderIdleWait: the render thread waits for the next frame instead of spinning.\n"
      + "renderIdleWait=false\n"
      + "# presentPacing: each frame's swap is held to a steady time after the game moment it shows (auto: with VRR).\n"
      + "# Low-lag: the hold is the 95th percentile of how late frames are ready, so only unusually early frames wait.\n"
      + "presentPacing=off\n"
      + "# The longest hold in % of a frame, and a margin added to the percentile.\n"
      + "presentPacingMaxPct=100\n"
      + "presentPacingMarginPct=0\n"
      + "# vrr: variable refresh (auto: detected from the display driver).\n"
      + "vrr=off\n"
      + "# vrrCap: with VRR, uncapped or above the panel's max caps at refresh - refresh^2/3600.\n"
      + "vrrCap=false\n"
      + "# borderlessFullscreen: the borderless window becomes a fullscreen window at the desktop mode (auto: with VRR).\n"
      + "borderlessFullscreen=off\n"
      + "# vsyncAdaptive: swap interval -1, a late frame tears instead of waiting a refresh.\n"
      + "vsyncAdaptive=true\n"
      + "# gpuMaxFrames: at most this many frames queued for the GPU (0: off). Acts only when the GPU falls behind.\n"
      + "gpuMaxFrames=2\n"
      + "# reflexSleep: each frame measures its queue wait and the next starts that much later.\n"
      + "reflexSleep=true\n"
      + "# reflexBoost: NVIDIA's prefer-maximum-performance while in a world.\n"
      + "reflexBoost=false\n"
      + "# reflexCapFps: with vsync, the cap (0: auto, refresh - refresh^2/3600).\n"
      + "reflexCapFps=0\n"
      + "# vblankLock: with vsync, frames start a measured time before vblank (X11 + NVIDIA).\n"
      + "vblankLock=false\n"
      + "#\n"
      + "# luaBudget: the merged LuaBudget mod. Its rules stay in Zomboid/LuaBudget.properties; no rules, no effect.\n"
      + "luaBudget=true\n"
      + "# luaSkipEmpty: Lua functions with an empty body (function() end) are not called.\n"
      + "luaSkipEmpty=false\n"
      + "# luaTiming: once a minute, the ten slowest Lua event handlers to the console (and lua.out with frameLog).\n"
      + "luaTiming=false\n"
      + "# luaRawgetFast: Lua table reads do one hash lookup instead of two.\n"
      + "luaRawgetFast=false\n"
      + "# luaInternConstants: string constants of loaded Lua are interned, so table lookups match by identity first.\n"
      + "luaInternConstants=false\n"
      + "#\n"
      + "# UI.\n"
      + "# uiTickStagger: each window runs its 10-per-second Lua update in its own slot instead of all in one frame.\n"
      + "uiTickStagger=false\n"
      + "#\n"
      + "# Input.\n"
      + "# inputLatch: a fresh input poll when the frame starts.\n"
      + "inputLatch=true\n"
      + "# keyboardFresh: the keyboard is read from the poll this frame swapped in.\n"
      + "keyboardFresh=true\n"
      + "# frameStartGate: wait for the render thread before reading input.\n"
      + "frameStartGate=true\n"
      + "# cursorLatch: the cursor is moved to the newest pointer position just before the frame is drawn.\n"
      + "cursorLatch=false\n"
      + "# aimHoldMs: right-button hold before aiming starts.\n"
      + "aimHoldMs=150\n";

    public static volatile boolean enabled;
    public static volatile boolean frameLog;
    private static volatile Properties props = new Properties();

    private Settings() {}

    public static void load() { load(new File(System.getProperty("user.home"), "Zomboid/PZButter.properties")); }

    public static void load(File f) {
        enabled = frameLog = false;
        props = new Properties();
        try {
            if (!f.exists()) try (FileWriter w = new FileWriter(f)) { w.write(DEFAULTS); }
            Properties p = new Properties();
            try (FileInputStream in = new FileInputStream(f)) { p.load(in); }
            props = p;
            enabled = bool(p, "enabled", false);
            frameLog = bool(p, "frameLog", false);
        } catch (Throwable t) {
            enabled = frameLog = false;
            props = new Properties();
            System.out.println("[PZButter] settings failed, all features off: " + t);
        }
    }

    /** A feature switch: never on without the master switch; a missing key takes its default. */
    /** Features that passed their in-game check and default to on (still only with the master switch). */
    static final java.util.Set<String> ON_BY_DEFAULT = java.util.Set.of("limiterSleep", "inputLatch", "keyboardFresh", "frameStartGate",
        "reflexSleep", "vsyncAdaptive", "luaBudget");   // these two only act with vsync on

    public static boolean flag(String key) { return enabled && bool(props, key, ON_BY_DEFAULT.contains(key)); }

    /** A number key, clamped to [min, max]; the default when missing or unreadable. */
    public static int num(String key, int dflt, int min, int max) {
        String v = props.getProperty(key);
        int n = dflt;
        if (v != null) try { n = Integer.parseInt(v.trim()); } catch (NumberFormatException e) { n = dflt; }
        return Math.max(min, Math.min(max, n));
    }

    /** "off", "on" or "auto"; "off" when the master switch is off or the value is unreadable. */
    public static String mode(String key) {
        if (!enabled) return "off";
        String v = props.getProperty(key);
        if (v == null) return "off";
        switch (v.trim().toLowerCase(Locale.ROOT)) {
            case "on": case "true": case "yes": case "1": return "on";
            case "auto": return "auto";
            default: return "off";
        }
    }

    static boolean bool(Properties p, String key, boolean dflt) {
        String v = p.getProperty(key);
        if (v == null) return dflt;
        switch (v.trim().toLowerCase(Locale.ROOT)) {
            case "true": case "yes": case "on": case "1": return true;
            case "false": case "no": case "off": case "0": return false;
            default: return dflt;
        }
    }
}
