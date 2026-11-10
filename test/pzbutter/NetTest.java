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
    }

    private static void check(String what, boolean ok) {
        if (!ok) throw new AssertionError(what);
    }
}
