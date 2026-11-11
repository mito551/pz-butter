package pzbutter;

import java.lang.reflect.Field;

import zombie.characters.IsoPlayer;
import zombie.network.PacketTypes;

public final class NetTest {
    private NetTest() {}

    private static IsoPlayer barePlayer() throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        sun.misc.Unsafe u = (sun.misc.Unsafe) f.get(null);
        // No-ctor player: only its runtime type matters for the instanceof gate.
        return (IsoPlayer) u.allocateInstance(IsoPlayer.class);
    }

    /** PacketTypes$PacketType.clinit runs ServerOptions, which calls Rand.Next: give the headless random a working rand. */
    static void initRandomizer() throws Exception {
        Field f = zombie.core.random.RandAbstract.class.getDeclaredField("rand");
        f.setAccessible(true);
        f.set(zombie.core.random.RandStandard.INSTANCE, new java.util.Random());
        zombie.core.random.RandAbstract.class.getMethod("init").invoke(zombie.core.random.RandStandard.INSTANCE);
    }

    public static void run() throws Exception {
        initRandomizer();
        PacketTypes.PacketType drop = PacketTypes.PacketType.PlayerDropHeldItems;
        Object[] player = { barePlayer() };
        Object[] odd = { new Object() };
        check("player drop not skipped", !MpClientPatches.skipDrop(drop, player));
        check("zombie drop skipped", MpClientPatches.skipDrop(drop, odd));
        check("null arg0 drop skipped", MpClientPatches.skipDrop(drop, new Object[] { null }));
        check("empty args drop skipped", MpClientPatches.skipDrop(drop, new Object[0]));
        check("null args skipped", MpClientPatches.skipDrop(drop, null));
        check("other packet kept", !MpClientPatches.skipDrop(PacketTypes.PacketType.Equip, odd));
        check("null type kept", !MpClientPatches.skipDrop(null, odd));
        serverNioPool();
    }

    /** serverNioPool: oversized packets' readers pool around instead of one ZomboidNetData per packet. */
    static void serverNioPool() throws Exception {
        // Server-side key: Settings.flag side-gates it off on a client JVM, so the pool core is exercised with the
        // guard set directly (the Side.SERVER list membership is pinned in SideTests/Settings template).
        Settings.load(SelfTest.write("enabled=true\nserverNioPool=true\n"));
        ServerPatches.initNioPool();
        ServerPatches.nioGuard.on = true;

        zombie.network.ZomboidNetData first = ServerPatches.pooledBig(4096);
        check("oversized packet answered", first != null);
        check("buffer is direct off-heap", first.buffer != null && first.buffer.bb != null && first.buffer.bb.isDirect());
        check("capacity kept", first.buffer.bb.capacity() == 4096);
        first.type = null;

        // discard recycles every non-stock capacity.
        ServerPatches.nioRecycle(first);
        zombie.network.ZomboidNetData second = ServerPatches.pooledBig(4096);
        check("recycled buffer reused", second == first);

        // stock-sized packets belong the game's own pool.
        zombie.network.ZomboidNetData small = new zombie.network.ZomboidNetData(2048);
        check("stock capacity not ours", !ServerPatches.nioRecycle(small));
        check("small not pooled", ServerPatches.pooledBig(2048) == null);

        ServerPatches.nioRecycle(second);
        check("pool bounded", ServerPatches.bigPoolCount() <= ServerPatches.BIG_CAP);
        for (int i = 0; i < 80; i++) {
            zombie.network.ZomboidNetData d = new zombie.network.ZomboidNetData(8192);
            ServerPatches.nioRecycle(d);
        }
        check("pool cap honoured", ServerPatches.bigPoolCount() <= ServerPatches.BIG_CAP);

        // guard off: stock behaviour (fresh allocation each ask, no recycling).
        Settings.load(SelfTest.write("enabled=true\n"));
        ServerPatches.initNioPool();
        check("defaults off", !ServerPatches.nioGuard.on);
        zombie.network.ZomboidNetData off = ServerPatches.pooledBig(4096);
        check("guard off declines", off == null);
        check("guard off no recycle", !ServerPatches.nioRecycle(new zombie.network.ZomboidNetData(4096)));
    }

    private static void check(String what, boolean ok) {
        if (!ok) throw new AssertionError(what);
    }
}
