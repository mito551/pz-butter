package pzbutter;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedDeque;

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

    /** serverNioPool: the stock pool recycles only 2048-capacity packet readers; oversized packets
     *  (> 2048, from GameServer.addIncoming's getLong branch) allocate one ZomboidNetData heap buffer
     *  per packet. Pool those around and back them with direct off-heap buffers. */
    public static final Guard nioGuard = new Guard("serverNioPool");
    public static final int BIG_CAP = 64;
    private static final ConcurrentLinkedDeque<zombie.network.ZomboidNetData> BIG_POOL = new ConcurrentLinkedDeque<>();
    private static sun.misc.Unsafe theUnsafe;
    private static long bufferOffset = -1;

    public static void initNioPool() {
        try {
            nioGuard.on = Settings.flag("serverNioPool");
        } catch (Throwable t) {
            nioGuard.fail(t);
        }
    }

    static int bigPoolCount() {
        return BIG_POOL.size();
    }

    /** Called in place of ZomboidNetDataPool.getLong(int): a pooled oversized packet, else null (stock runs). */
    public static zombie.network.ZomboidNetData pooledBig(int n) {
        if (!nioGuard.on || n <= 2048) return null;
        try {
            zombie.network.ZomboidNetData d = BIG_POOL.poll();
            if (d == null) {
                d = new zombie.network.ZomboidNetData(n);
                backWithDirect(d, n);
            }
            return d;
        } catch (Throwable t) {
            nioGuard.fail(t);
            return null;
        }
    }

    /** Called in place of ZomboidNetDataPool.discard(d): every non-stock capacity recycles here. */
    public static boolean nioRecycle(zombie.network.ZomboidNetData d) {
        if (!nioGuard.on || d == null) return false;
        try {
            if (d.buffer == null || d.buffer.bb == null || d.buffer.bb.capacity() == 2048) return false;
            d.reset();
            if (BIG_POOL.size() < BIG_CAP) BIG_POOL.add(d);
            return true;
        } catch (Throwable t) {
            nioGuard.fail(t);
            return false;
        }
    }

    private static void backWithDirect(zombie.network.ZomboidNetData d, int n) throws Exception {
        if (theUnsafe == null) {
            Field f0 = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f0.setAccessible(true);
            theUnsafe = (sun.misc.Unsafe) f0.get(null);
            Field f = zombie.network.ZomboidNetData.class.getDeclaredField("buffer"); // public final; Field.set throws
            bufferOffset = theUnsafe.objectFieldOffset(f);
        }
        java.nio.ByteBuffer direct = org.lwjglx.BufferUtils.createByteBuffer(n);
        theUnsafe.putObject(d, bufferOffset, new zombie.core.network.ByteBufferReader(direct));
    }

    @Patch(className = "zombie.network.ZomboidNetDataPool", methodName = "getLong")
    public static class NioBig {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) int n, @Patch.Local("r") Object r) {
            if (!nioGuard.on) return false;
            try {
                r = pooledBig(n);
                return r != null;
            } catch (Throwable t) {
                nioGuard.fail(t);
                return false;
            }
        }

        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) zombie.network.ZomboidNetData ret, @Patch.Local("r") Object r) {
            if (r != null) ret = (zombie.network.ZomboidNetData) r;
        }
    }

    @Patch(className = "zombie.network.ZomboidNetDataPool", methodName = "discard")
    public static class NioRecycle {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) zombie.network.ZomboidNetData d) {
            if (!nioGuard.on) return false;
            try {
                return nioRecycle(d);
            } catch (Throwable t) {
                nioGuard.fail(t);
                return false;
            }
        }
    }
}
