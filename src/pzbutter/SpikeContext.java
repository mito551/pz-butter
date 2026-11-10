package pzbutter;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * spikeContext (Tempo 4.9): each frame over the spike threshold is classified by what the player was doing and
 * appended to Zomboid/pzbutter/spikes.out; once a minute the counts go to the console. Frames of 25-50 ms while
 * moving within the window of a chunk load count as a stream burst (the game catching up after a chunk arrives).
 * The frame step costs one flag check and one compare; the player is read once per slow frame.
 */
public final class SpikeContext {
    private SpikeContext() {}

    static final Guard guard = new Guard("spikeContext");

    static final int BURST_MIN_MS = 25, BURST_MAX_MS = 50;
    static final long MINUTE_MS = 60_000;

    static int thresholdMs = 50, windowMs = 750;

    /** When LoadGridsquare / the context-menu events last ran (epoch ms); 0 = never. */
    static long lastChunkMs, lastMenuMs;

    static int driving, moving, chunk, menu, uncat;
    static long streamBurst;

    private static long minuteStartMs;
    private static boolean minuteStarted;

    // test seams: a chosen player state and output file, and a counter proving the off path reads nothing
    static volatile boolean seamOn, seamMoving;
    static volatile double seamKmh;
    static volatile long stateReads;
    static volatile Path spikesPath;

    private static boolean curMoving;
    private static double curKmh;

    static void init() {
        guard.on = Settings.flag("spikeContext");
        thresholdMs = Settings.num("spikeThresholdMs", 50, 1, 10_000);
        windowMs = Settings.num("spikeContextWindowMs", 750, 0, 60_000);
    }

    /** Called from FrameClock.end: one flag check and one compare per frame when off or fast. */
    static void onFrame(long epochMs, long frameNo, double ms) {
        if (!guard.on || ms < BURST_MIN_MS) return;
        try {
            readState();
            if (ms <= thresholdMs) {
                if ((curMoving || curKmh > 1) && epochMs - lastChunkMs <= windowMs) streamBurst++;
            } else {
                String context = classify(curMoving, curKmh, epochMs - lastChunkMs, epochMs - lastMenuMs, windowMs);
                switch (context) {
                    case "driving" -> driving++;
                    case "moving" -> moving++;
                    case "chunk" -> chunk++;
                    case "menu" -> menu++;
                    default -> uncat++;
                }
                write(epochMs, frameNo, ms, context);
            }
            minute(epochMs);
        } catch (Throwable t) { guard.fail(t); }
    }

    static String classify(boolean moving, double kmh, long sinceChunkMs, long sinceMenuMs, long windowMs) {
        if (kmh > 1) return "driving";
        if (moving) return "moving";
        if (sinceChunkMs <= windowMs) return "chunk";
        if (sinceMenuMs <= windowMs) return "menu";
        return "uncat";
    }

    /** LoadGridsquare and the two context-menu events, from the triggerEvent holder. */
    public static void noteEvent(String event) {
        if (!guard.on) return;
        try {
            long now = System.currentTimeMillis();
            if ("LoadGridsquare".equals(event)) lastChunkMs = now;
            else if ("OnFillWorldObjectContextMenu".equals(event) || "OnFillInventoryObjectContextMenu".equals(event)) lastMenuMs = now;
        } catch (Throwable t) { guard.fail(t); }
    }

    private static void readState() {
        if (seamOn) {
            curMoving = seamMoving;
            curKmh = seamKmh;
            stateReads++;
            return;
        }
        try {
            zombie.characters.IsoPlayer p = zombie.characters.IsoPlayer.getInstance();
            if (p != null) {
                curMoving = p.isPlayerMoving();
                zombie.vehicles.BaseVehicle v = p.getVehicle();
                curKmh = v == null ? 0 : Math.abs(v.getCurrentSpeedKmHour());
            } else {
                curMoving = false;
                curKmh = 0;
            }
            stateReads++;
        } catch (Throwable t) {
            curMoving = false;
            curKmh = 0;
            guard.fail(t);
        }
    }

    private static void write(long epochMs, long frameNo, double ms, String context) {
        try {
            Path f = spikesPath;
            if (f == null) f = new File(System.getProperty("user.home"), "Zomboid/pzbutter/spikes.out").toPath();
            Files.createDirectories(f.getParent());
            try (FileWriter w = new FileWriter(f.toFile(), true)) {
                w.write(epochMs + " " + frameNo + " " + Math.round(ms) + " " + context + "\n");
            }
        } catch (Throwable t) { guard.fail(t); }
    }

    /** The minute line prints on the first activity after a minute, so an idle game writes none. */
    private static void minute(long epochMs) {
        if (!minuteStarted) {
            minuteStarted = true;
            minuteStartMs = epochMs;
            return;
        }
        if (epochMs - minuteStartMs < MINUTE_MS) return;
        minuteStartMs = epochMs;
        System.out.println(summary());
        driving = moving = chunk = menu = uncat = 0;
        streamBurst = 0;
    }

    static String summary() {
        int n = driving + moving + chunk + menu + uncat;
        return "[PZButter] spikes: " + n + " (driving " + driving + ", moving " + moving + ", chunk " + chunk
            + ", menu " + menu + ", uncat " + uncat + "), streamBurst " + streamBurst;
    }

    static void resetForTest() {
        guard.on = false;
        seamOn = seamMoving = false;
        seamKmh = 0;
        stateReads = 0;
        spikesPath = null;
        lastChunkMs = lastMenuMs = 0;
        driving = moving = chunk = menu = uncat = 0;
        streamBurst = 0;
        minuteStarted = false;
        minuteStartMs = 0;
        thresholdMs = 50;
        windowMs = 750;
    }
}
