package pzbutter;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Properties;

/** Zomboid/PZButter.properties. Read once at mod load; any failure leaves every feature off. */
public final class Settings {
    static final String VERSION = "0.2";

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
      + "luaSkipEmpty=true\n"
      + "# luaTiming: once a minute, the ten slowest Lua event handlers to the console (and lua.out with frameLog).\n"
      + "luaTiming=false\n"
      + "# luaRawgetFast: Lua table reads do one hash lookup instead of two (measured slower than stock; off).\n"
      + "luaRawgetFast=false\n"
      + "# luaInternConstants: string constants of loaded Lua are interned, so table lookups match by identity first.\n"
      + "luaInternConstants=true\n"
      + "#\n"
      + "# UI.\n"
      + "# uiTickStagger: each window runs its 10-per-second Lua update in its own slot instead of all in one frame.\n"
      + "uiTickStagger=true\n"
      + "# uiLuaFast: the inventory item list skips its second drawing pass when nothing is dragged.\n"
      + "uiLuaFast=true\n"
      + "# textWidthCache: measured text widths are kept (context menus re-measure every option on every opening).\n"
      + "textWidthCache=true\n"
      + "#\n"
      + "# World map.\n"
      + "# mapStreetMemo: street lengths and names kept per view and language; one index map per label sort.\n"
      + "mapStreetMemo=true\n"
      + "# mapStreetCache: the street-label layout is reused while the map view is unchanged.\n"
      + "mapStreetCache=true\n"
      + "# mapVisitedFast: the explored-area texture is built a row at a time.\n"
      + "mapVisitedFast=true\n"
      + "#\n"
      + "# Sound.\n"
      + "# soundTickHz: ambience and zombie sound parameters refresh at this rate (0 = stock; 60 refreshes them at 60 Hz).\n"
      + "soundTickHz=0\n"
      + "# emitterIdleSkip: a silent zombie skips its sound parameters and emitter ticks.\n"
      + "emitterIdleSkip=false\n"
      + "# worldSoundCleanupFast: world sounds are cleaned up with fewer chunk scans per frame, and the same sounds are heard.\n"
      + "worldSoundCleanupFast=false\n"
      + "# hearingHoist: zombies pick the loudest sound they hear with less repeated work, and the same sound is picked.\n"
      + "hearingHoist=false\n"
      + "# worldSoundFast: fish scaring is worked out once per game minute for each sound position, so repeated sounds cost less.\n"
      + "worldSoundFast=false\n"
      + "# soundZoneCache: ambient zone sound levels are reused while you stay on one square, so the zone lookup costs less each frame.\n"
      + "soundZoneCache=false\n"
      + "# electricityLevelRange: the power check scans only the height range of loaded chunks, so the same result comes back with less work.\n"
      + "electricityLevelRange=false\n"
      + "# fliesToggleFix: moving between chunks next to an unchanged corpse pile no longer redraws that chunk, which removes a hitch.\n"
      + "fliesToggleFix=false\n"
      + "# fmodNullHandleFix: when FMOD is out of event slots, the emitter sound is dropped instead of crashing the game.\n"
      + "fmodNullHandleFix=false\n"
      + "# corpseFliesCap: flies around corpse piles are counted up to 12, which also lowers corpse sickness next to piles above 12.\n"
      + "corpseFliesCap=false\n"
      + "# audioLimiter: a limiter on the FMOD master output through FMOD's own library (Plan 4; off until its in-game check).\n"
      + "audioLimiter=false\n"
      + "# audioLimiterCeilingDb: the limiter's ceiling in dB.\n"
      + "audioLimiterCeilingDb=-2\n"
      + "# audioLimiterStereoFold: with a stereo output, the limiter folds channels by FMOD channel format.\n"
      + "audioLimiterStereoFold=true\n"
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
            byte[] bytes = Files.readAllBytes(f.toPath());
            int bom = bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF ? 3 : 0;
            p.load(new java.io.ByteArrayInputStream(bytes, bom, bytes.length - bom));   // a UTF-8 mark (Notepad) is not a key
            props = p;
            upgrade(f, p);
            enabled = bool(p, "enabled", false);
            frameLog = bool(p, "frameLog", false);
        } catch (Throwable t) {
            enabled = frameLog = false;
            props = new Properties();
            System.out.println("[PZButter] settings failed, all features off: " + t);
        }
    }

    /**
     * Brings the file up to the current template: every known key with its comment and the user's value (or the
     * default), then any keys this version doesn't know, as they were. A one-time .bak keeps the original.
     * Nothing is written when the file is already current. A failure here leaves the file and the loaded values alone.
     */
    static void upgrade(File f, Properties p) {
        try {
            String text = render(p);
            String old = new String(Files.readAllBytes(f.toPath()), StandardCharsets.ISO_8859_1);
            if (text.equals(old)) return;
            File bak = new File(f.getPath() + ".bak");
            if (!bak.exists()) Files.copy(f.toPath(), bak.toPath());
            java.nio.file.Path tmp = f.toPath().resolveSibling(f.getName() + ".tmp");   // a failed write never truncates the file
            Files.write(tmp, text.getBytes(StandardCharsets.ISO_8859_1));
            Files.move(tmp, f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Throwable t) {
            System.out.println("[PZButter] settings file not upgraded: " + t);
        }
    }

    /** The template with p's values in place, then p's unknown keys. */
    static String render(Properties p) {
        StringBuilder b = new StringBuilder();
        java.util.Set<String> known = new java.util.HashSet<>();
        for (String line : DEFAULTS.split("\n")) {
            int eq = line.indexOf('=');
            if (line.startsWith("#") || eq < 0) { b.append(line).append('\n'); continue; }
            String key = line.substring(0, eq);
            known.add(key);
            String v = p.getProperty(key);
            b.append(v != null ? entry(key, v) : line).append('\n');
        }
        java.util.List<String> unknown = new java.util.ArrayList<>();
        for (String k : p.stringPropertyNames()) if (!known.contains(k)) unknown.add(k);
        if (!unknown.isEmpty()) {
            java.util.Collections.sort(unknown);
            b.append("#\n# Keys this version of PZ Butter doesn't use (kept as they were):\n");
            for (String k : unknown) b.append(entry(k, p.getProperty(k))).append('\n');
        }
        return b.toString();
    }

    /** key=value escaped the way Properties.store writes it (backslashes, line breaks, \\uXXXX past Latin-1). */
    static String entry(String key, String value) {
        try {
            Properties one = new Properties();
            one.setProperty(key, value);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            one.store(out, null);
            String s = out.toString(StandardCharsets.ISO_8859_1);   // a date comment line, then the entry
            return s.substring(s.indexOf('\n') + 1, s.length() - System.lineSeparator().length());
        } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }

    /** A feature switch: never on without the master switch; a missing key takes its default. */
    /** Features that passed their in-game check and default to on (still only with the master switch). */
    static final java.util.Set<String> ON_BY_DEFAULT = java.util.Set.of("limiterSleep", "inputLatch", "keyboardFresh", "frameStartGate",
        "reflexSleep", "vsyncAdaptive", "luaBudget",
        "luaInternConstants", "luaSkipEmpty", "uiTickStagger", "textWidthCache", "uiLuaFast", "mapStreetMemo", "mapStreetCache", "mapVisitedFast");   // these two only act with vsync on

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
