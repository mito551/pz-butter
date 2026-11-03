package pzbutter;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Server tick log seams (Plan 18), inside GameServer.startServer's loop:
 *
 * <pre>
 *   2822  done check / System.nanoTime          tick start
 *   2833  PoolCaps.updateServerCaps()           -> ServerTick.begin
 *   3599  ServerMap.preupdate()                 -> net work is over
 *   3875  IngameState.update()                  world
 *   4835  WorldMapVisitedServer.update()        -> ServerTick.end (probe close follows)
 *   3554  goto 2822, sleep after the tick
 * </pre>
 */
public final class ServerPatches {
    private ServerPatches() {}

    @Patch(className = "zombie.popman.PoolCaps", methodName = "updateServerCaps")
    public static class TickStart {
        @Patch.OnEnter
        public static void enter() { ServerTick.begin(); }
    }

    @Patch(className = "zombie.network.ServerMap", methodName = "preupdate")
    public static class NetDone {
        @Patch.OnEnter
        public static void enter() { ServerTick.netDone(); }
    }

    @Patch(className = "zombie.worldMap.WorldMapVisitedServer", methodName = "update")
    public static class TickEnd {
        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit() { ServerTick.end(); }
    }

    @Patch(className = "zombie.network.GameServer", methodName = "startServer")
    public static class StopOnExit {
        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit() { ServerTick.stop(); }
    }

    @Patch(className = "zombie.network.GameServer", methodName = "close")
    public static class StopOnClose {
        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit() { ServerTick.stop(); }
    }
}
