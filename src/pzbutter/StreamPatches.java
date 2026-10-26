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
            hotsaveGuard.on = sec > 0;
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
}
