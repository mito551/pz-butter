package pzbutter;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.OnExit;

public final class StreamPatches {
    private StreamPatches() {}

    public static final Guard hotsaveGuard = new Guard("hotsaveIntervalSec");

    public static void init() {
        ChunkPacer.init();
        try {
            int sec = Settings.num("hotsaveIntervalSec", 0, 0, 600);
            hotsaveGuard.on = Settings.enabled && sec > 0;
        } catch (Throwable t) {
            hotsaveGuard.fail(t);
            hotsaveGuard.on = false;
        }
    }

    public static long hotsaveMs(int sec) {
        long ms = (long) sec * 1000L;
        if (ms < 1_000L) ms = 1_000L;
        if (ms > 600_000L) ms = 600_000L;
        return ms;
    }

    @Patch(className = "zombie.iso.IsoChunkMap", methodName = "updateInternal")
    public static class ChunkUpdate {
        @Patch.OnEnter
        public static void enter() { ChunkPacer.enter(); }

        @OnExit(suppress = Throwable.class, onThrowable = Throwable.class)
        public static void exit() { ChunkPacer.exit(); }
    }

    @Patch(className = "zombie.iso.IsoChunk", methodName = "doLoadGridsquare")
    public static class Join {
        @Patch.OnEnter
        public static void enter() { ChunkPacer.joinStart(); }

        @OnExit(suppress = Throwable.class, onThrowable = Throwable.class)
        public static void exit() { ChunkPacer.joinEnd(); }
    }

    @Patch(className = "zombie.util.CappedConcurrentQueue", methodName = "poll")
    public static class Poll {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object queue) { return ChunkPacer.onPoll(queue); }
    }

    @Patch(className = "zombie.iso.IsoChunkMap", methodName = "<init>")
    public static class HotSaveInit {
        @Patch.OnExit
        public static void exit(@Patch.This Object self) { onHotSaveInit(self); }
    }

    public static void onHotSaveInit(Object self) {
        if (!hotsaveGuard.on) return;
        try {
            java.lang.reflect.Field f = self.getClass().getDeclaredField("hotSaveFrequency");
            f.setAccessible(true);
            Object ul = f.get(self);
            int sec = Settings.num("hotsaveIntervalSec", 0, 0, 600);
            ((zombie.core.utils.UpdateLimit) ul).setUpdatePeriod(hotsaveMs(sec));
        } catch (Throwable t) {
            hotsaveGuard.fail(t);
        }
    }

    private static final long MINUTE_NS = 60_000_000_000L;
    private static boolean streamStarted;
    private static long streamNextNs;

    /** Once a minute: print pacing and vehicle lines when something happened. */
    public static void tick(long nowNs) {
        boolean pacing = ChunkPacer.guard.on;
        boolean veh = VehicleFixes.animGuard.on;
        if (!pacing && !veh) return;
        if (!streamStarted) {
            streamStarted = true;
            streamNextNs = nowNs + MINUTE_NS;
            return;
        }
        if (nowNs < streamNextNs) return;
        streamNextNs = nowNs + MINUTE_NS;
        if (pacing) {
            long j = ChunkPacer.joins;
            String line = ChunkPacer.drain();
            if (j > 0) System.out.println(line);
        }
        if (veh) {
            long checked = VehicleFixes.animChecked;
            long skipped = VehicleFixes.animSkipped;
            if (checked > 0) System.out.println("[PZButter] vehicle parts: " + skipped + " of " + checked + " parked-part updates skipped");
            long f = FrameClock.frameNo;
            VehicleFixes.animStates.entrySet().removeIf(e -> f - e.getValue().seen > 1800);
            VehicleFixes.animChecked = 0;
            VehicleFixes.animSkipped = 0;
        }
    }
}
