package pzbutter;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.OnExit;

public final class StreamPatches {
    private StreamPatches() {}

    public static void init() {
        ChunkPacer.init();
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
}