package pzbutter;

import java.util.ArrayList;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.iso.IsoCell;

/**
 * Server tick log seams (Plan 15), inside GameServer.startServer's loop:
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

    /** serverCellSpread: Load2's RecalcAll2 body runs budgeted per tick (state lives in CellSpread). */
    @Patch(className = "zombie.network.ServerMap$ServerCell", methodName = "Load2")
    public static class Load2Spread {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This zombie.network.ServerMap.ServerCell cell) { return CellSpread.onLoad2Enter(cell); }
    }

    /** serverCellSpread: while a cell is mid-spread, a direct RecalcAll2 call waits for the slices. */
    @Patch(className = "zombie.network.ServerMap$ServerCell", methodName = "RecalcAll2")
    public static class RecalcAll2Spread {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This zombie.network.ServerMap.ServerCell cell) { return CellSpread.onRecalcAll2Enter(cell); }
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

    /** sendAddItemToContainer with no destination at all: stock would return without sending. */
    @Patch(className = "zombie.network.GameServer", methodName = "sendAddItemToContainer")
    public static class SendGuard {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(ItemContainer c, InventoryItem item) { return ServerFixes.skipSend(c); }
    }

    /** Batch process-item adds in one pass; stock scans the whole list once per item. */
    @Patch(className = "zombie.iso.IsoCell", methodName = "addToProcessItems", strictMatch = true)
    public static class ProcessItems {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This IsoCell self, @Patch.Argument(0) ArrayList<InventoryItem> list) {
            if (!Settings.flag("processItemsMerge") || zombie.network.GameClient.client
                || list == null || !ServerFixes.batch(list.size())) return false;
            try {
                ServerFixes.mergeProcessItems(self.getProcessItems(), list, self.getProcessItemsRemove());
                return true;
            } catch (Throwable t) {
                return false;   // stock runs on any doubt
            }
        }
    }
}
