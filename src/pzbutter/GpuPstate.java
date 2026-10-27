package pzbutter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * gpuPstate (AMD on Linux): a steady GPU clock level from each frame's GPU time. Off by default.
 *
 * Runs a PZ Butter daemon thread; never touches the game or render thread. The sysfs paths live under
 * {@code /sys/class/drm/cardN/device}.
 */
public final class GpuPstate {
    private GpuPstate() {}

    public static final Guard guard = new Guard("gpuPstate");

    /** The drm root this instance was initialised against. */
    static volatile Path drmRoot = Paths.get("/sys/class/drm");

    /** Power level read at start; written back on restore. */
    static volatile String originalLevel = null;

    /** The card's device directory (the one with both files). */
    static volatile Path cardDevice = null;

    /** pp_dpm_sclk path. */
    static volatile Path sclkPath = null;

    /** power_dpm_force_performance_level path. */
    static volatile Path powerPath = null;

    /** Daemon thread. */
    static volatile Thread thread = null;

    /** Frames spent below 50 % of the period (accumulator, 30 per poll). */
    static volatile int lowFrames = 0;

    /** Current applied level (index). */
    static volatile int currentLevel = -1;

    /** Whether restore has already happened. */
    static final AtomicBoolean restored = new AtomicBoolean(false);

    /** Tracks whether the card's files were writable at init. */
    static volatile boolean writable = false;

    static void init() {
        init(Paths.get("/sys/class/drm"));
    }

    public static void init(Path inRoot) {
        guard.on = Settings.flag("gpuPstate");
        resetRuntime();
        if (!guard.on) {
            restored.set(false);
            return;
        }
        drmRoot = (inRoot == null) ? Paths.get("/sys/class/drm") : inRoot;
        Path dev = null;
        try { dev = findCard(drmRoot); } catch (Throwable t) { dev = null; }
        if (dev == null) {
            guard.on = false;
            System.out.println("[PZButter] gpuPstate off: no amdgpu card (no pp_dpm_sclk / power_dpm_force_performance_level)");
            restored.set(false);
            return;
        }
        Path sclk = dev.resolve("pp_dpm_sclk");
        Path power = dev.resolve("power_dpm_force_performance_level");
        if (!Files.isWritable(sclk) || !Files.isWritable(power)) {
            guard.on = false;
            writable = false;
            System.out.println("[PZButter] gpuPstate off: sysfs files not writable at " + dev + " (udev rule untested here: ACTION==\"add\", SUBSYSTEM==\"drm\", KERNEL==\"card[0-9]\", DRIVERS==\"amdgpu\", RUN+=\"/bin/chgrp video ...\")");
            restored.set(false);
            return;
        }
        cardDevice = dev;
        sclkPath = sclk;
        powerPath = power;
        writable = true;
        String orig = null;
        try { orig = Files.readString(power).trim(); } catch (Throwable t) { orig = null; }
        if (orig == null || !orig.trim().equalsIgnoreCase("auto")) {
            guard.on = false;
            originalLevel = orig;
            System.out.println("[PZButter] gpuPstate off: power level is not auto (" + (orig == null ? "unreadable" : orig) + ")");
            restored.set(false);
            return;
        }
        originalLevel = orig;
        restored.set(false);
        // manual is written by the daemon thread as its first action, not here
        // read current applied level
        try { currentLevel = currentLevel(Files.readString(sclk)); } catch (Throwable t) { currentLevel = 1; }
        if (currentLevel < 0) currentLevel = 1;
        lowFrames = 0;
        thread = new Thread(GpuPstate::run, "PZButter gpuPstate");
        thread.setDaemon(true);
        thread.start();
        // shutdown hook
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(GpuPstate::restore, "PZButter gpuPstate restore"));
        } catch (Throwable ignored) { }
        System.out.println("[PZButter] gpuPstate on");
    }

    private static void resetRuntime() {
        Thread t = thread;
        thread = null;
        if (t != null) { try { t.interrupt(); } catch (Throwable ignored) { } }
        cardDevice = null;
        sclkPath = null;
        powerPath = null;
        originalLevel = null;
        lowFrames = 0;
        currentLevel = -1;
        writable = false;
        restored.set(false);
    }

    /** Finds the amdgpu card device directory. Returns null when none. */
    public static Path findCard(Path drmRoot) {
        if (drmRoot == null || !Files.exists(drmRoot)) return null;
        List<Path> candidates = new ArrayList<>();
        try (var stream = Files.list(drmRoot)) {
            stream.filter(p -> {
                String n = p.getFileName() == null ? "" : p.getFileName().toString();
                return n.startsWith("card");
            }).forEach(candidates::add);
        } catch (IOException e) { return null; }
        candidates.sort(Comparator.comparing(p -> p.getFileName().toString()));
        for (Path card : candidates) {
            Path dev = card.resolve("device");
            Path sclk = dev.resolve("pp_dpm_sclk");
            Path power = dev.resolve("power_dpm_force_performance_level");
            if (Files.exists(dev) && Files.exists(sclk) && Files.exists(power)) return dev;
        }
        // also scan nested one level just in case
        return null;
    }

    static int currentLevel(String sclkText) {
        int cur = -1;
        if (sclkText == null) return cur;
        for (String line : sclkText.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            boolean star = t.contains("*");
            // lines like "1: 500Mhz *" or "1: 500Mhz*"
            String first = t.split(":")[0].trim();
            int idx;
            try { idx = Integer.parseInt(first); } catch (NumberFormatException e) { continue; }
            if (star) cur = idx;
        }
        return cur;
    }

    static int maxLevel(String sclkText) {
        int max = -1;
        if (sclkText == null) return max;
        for (String line : sclkText.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            String first = t.split(":")[0].trim();
            int idx;
            try { idx = Integer.parseInt(first); } catch (NumberFormatException e) { continue; }
            if (idx > max) max = idx;
        }
        return max;
    }

    /** Per-poll decision: returns {newLevel, newLowFrames}. */
    public static int[] step(int cur, int max, double gpuMs, double periodMs, int lowFrames) {
        if (periodMs <= 0) periodMs = 16.67;
        double upThreshold = 0.85 * periodMs;
        double downThreshold = 0.50 * periodMs;
        int newLow = lowFrames;
        int newLevel = cur;
        if (gpuMs >= 0 && gpuMs < downThreshold) {
            newLow = lowFrames + 30;
        } else {
            newLow = 0;
        }
        if (gpuMs > upThreshold) {
            newLevel = Math.min(cur + 1, max);
            newLow = 0;
        } else if (gpuMs >= 0 && gpuMs < downThreshold && newLow >= 120) {
            newLevel = Math.max(cur - 1, 1);
            newLow = 0;
        }
        if (max > 0 && newLevel > max) newLevel = max;
        if (newLevel < 1) newLevel = 1;
        return new int[] { newLevel, newLow };
    }

    /** Pure seam: whether the daemon should stop this poll given sample counters. */
    public static boolean stopOnStale(boolean timerGuardOn, long samplesBefore, long samplesNow, int consecutiveStale) {
        if (!timerGuardOn) return true;
        if (samplesNow == samplesBefore && consecutiveStale + 1 >= 4) return true;
        return false;
    }

    private static void run() {
        Path p0 = powerPath;
        if (p0 != null) {
            try { Files.writeString(p0, "manual"); }
            catch (Throwable t) { guard.on = false; System.out.println("[PZButter] gpuPstate off: " + t); restore(); return; }
        }
        long lastSamples = GpuTimer.samples;
        int stale = 0;
        while (!Thread.currentThread().isInterrupted() && guard.on) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (!guard.on) break;
            if (Thread.interrupted()) { Thread.currentThread().interrupt(); break; }
            try {
                if (!GpuTimer.guard.on) {
                    restore(); guard.on = false; System.out.println("[PZButter] gpuPstate off: no GPU time samples"); break;
                }
                long sc = GpuTimer.samples;
                if (sc == lastSamples) stale++; else { stale = 0; lastSamples = sc; }
                if (stale >= 4) {
                    restore(); guard.on = false; System.out.println("[PZButter] gpuPstate off: no GPU time samples"); break;
                }
                double gpu = GpuTimer.lastGpuMs;
                if (gpu < 0) {
                    // no sample yet: count as stale already? continue
                    continue;
                }
                double period = periodMs();
                Path sp = sclkPath;
                if (sp == null) continue;
                String text = Files.readString(sp);
                int cur = currentLevel(text);
                if (cur < 0) cur = currentLevel;
                int max = maxLevel(text);
                if (max <= 0) continue;
                int[] r = step(cur, max, gpu, period, lowFrames);
                lowFrames = r[1];
                int next = r[0];
                if (next != cur) {
                    Files.writeString(sp, String.valueOf(next));
                    currentLevel = next;
                    lowFrames = 0;
                } else {
                    currentLevel = cur;
                }
            } catch (Throwable t) {
                guard.on = false;
                System.out.println("[PZButter] gpuPstate off: " + t);
                restore();
                break;
            }
        }
    }

    static double periodMs() {
        try {
            int cap = zombie.core.PerformanceSettings.getLockFPS();  // may not exist; guard
            if (cap <= 0) return 16.666666666666668;
            return 1000.0 / cap;
        } catch (Throwable t) {
            return 16.666666666666668;
        }
    }

    public static void restore() {
        Thread t = thread;
        thread = null;
        if (t != null) try { t.interrupt(); } catch (Throwable ignored) { }
        String orig = originalLevel;
        if (orig == null) return; // never read an original level: nothing to restore
        if (restored.getAndSet(true)) return; // second restore writes nothing
        Path pp = powerPath;
        if (pp != null) {
            try {
                if (Files.isWritable(pp)) Files.writeString(pp, orig);
            } catch (Throwable ignored) { }
        }
    }

    public static void resetForTest() {
        Thread t = thread;
        thread = null;
        if (t != null) t.interrupt();
        guard.on = false;
        currentLevel = -1;
        lowFrames = 0;
        restored.set(false);
        writable = false;
        originalLevel = null;
        cardDevice = null;
        sclkPath = null;
        powerPath = null;
        drmRoot = Paths.get("/sys/class/drm");
    }
}
