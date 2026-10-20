package pzbutter;

import java.util.concurrent.TimeUnit;

/**
 * reflexSleep (SPEC 3.8): the render thread measures how long each swap blocks (the frame queue / vsync wait);
 * the smoothed wait is added once per frame before the game thread starts it, so frames start later and queue less.
 * reflexBoost: NVIDIA's "prefer maximum performance" (GPUPowerMizerMode=1 through nvidia-settings) while in a world;
 * the mode read before is restored on world exit and at JVM shutdown. nvidia-settings runs off the game threads.
 */
final class Reflex {
    static final Guard sleepGuard = new Guard("reflexSleep"), boostGuard = new Guard("reflexBoost");
    static volatile long smoothedWaitNs;
    private static long takenForFrame = -1;

    private static volatile Integer savedMode;
    private static volatile boolean boosted;

    private Reflex() {}

    static void init() {
        sleepGuard.on = Settings.flag("reflexSleep");
        boostGuard.on = Settings.flag("reflexBoost");
        if (sleepGuard.on) PresentLog.guard.on = true;
        if (boostGuard.on) Runtime.getRuntime().addShutdownHook(new Thread(Reflex::restoreNow, "PZButter reflexBoost restore"));
    }

    static long ewma(long prev, long sample) { return prev + (sample - prev) / 8; }

    /** Render thread, after each swap. */
    static void onSwap(long swapNs) { smoothedWaitNs = ewma(smoothedWaitNs, swapNs); }

    /** The extra delay for the frame about to start: once per frame, and only once it is due. */
    static long extraFor(long frame, long takenFor, long extraNs, long sleepLeftNs) {
        if (extraNs <= 0 || sleepLeftNs > 0 || takenFor == frame) return 0;
        return extraNs;
    }

    /** Game thread, from the limiter's check. */
    static long take(long sleepLeftNs) {
        long frame = FrameClock.frameNo;
        long e = extraFor(frame, takenForFrame, smoothedWaitNs, sleepLeftNs);
        if (e > 0) takenForFrame = frame;
        return e;
    }

    // ---- reflexBoost ----

    /** The last line of nvidia-settings -q ... -t as a number, or null. */
    static Integer parseMode(String out) {
        if (out == null) return null;
        String[] lines = out.trim().split("\n");
        try { return Integer.valueOf(lines[lines.length - 1].trim()); }
        catch (NumberFormatException e) { return null; }
    }

    static void enterWorld() {
        if (!boostGuard.on || boosted) return;
        background(() -> {
            Integer m = parseMode(run("-q", "[gpu:0]/GPUPowerMizerMode", "-t"));
            if (m == null) {
                boostGuard.on = false;
                System.out.println("[PZButter] reflexBoost off: nvidia-settings can't read GPUPowerMizerMode");
                return;
            }
            savedMode = m;
            if (m == 1 || run("-a", "[gpu:0]/GPUPowerMizerMode=1") != null) boosted = true;
        });
    }

    static void exitWorld() {
        if (boosted) background(Reflex::restoreNow);
    }

    private static synchronized void restoreNow() {
        Integer m = savedMode;
        if (!boosted || m == null) return;
        boosted = false;
        if (m != 1) run("-a", "[gpu:0]/GPUPowerMizerMode=" + m);
    }

    private static void background(Runnable r) {
        Thread t = new Thread(() -> { try { r.run(); } catch (Throwable e) { boostGuard.fail(e); } }, "PZButter reflexBoost");
        t.setDaemon(true);
        t.start();
    }

    /** nvidia-settings with args; its stdout, or null on failure. */
    private static String run(String... args) {
        try {
            String[] cmd = new String[args.length + 1];
            cmd[0] = "nvidia-settings";
            System.arraycopy(args, 0, cmd, 1, args.length);
            Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            // wait first, so a hung nvidia-settings can't block the shutdown hook; its output is a few bytes
            if (!p.waitFor(5, TimeUnit.SECONDS)) { p.destroyForcibly(); return null; }
            return p.exitValue() == 0 ? new String(p.getInputStream().readAllBytes()) : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
