package pzbutter;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.core.raknet.UdpConnection;
import zombie.network.PacketTypes.PacketType;
import zombie.network.ZomboidNetData;

/**
 * Counters for netStats (D10). One send holder tags the thread with the packet
 * type; the UdpConnection.endPacket holder records the packet and its writer
 * position. Receives are counted where the game hands a decoded packet to the
 * main loop.
 */
public final class NetStatsPatches {
    private NetStatsPatches() {}

    /** Set while PacketType.send runs on this thread; endPacket runs inside it. */
    public static final ThreadLocal<PacketType> SENDING = new ThreadLocal<>();

    private static final Field BB = bbField();

    private static Field bbField() {
        try {
            Field f = UdpConnection.class.getDeclaredField("bb");
            f.setAccessible(true);
            return f;
        } catch (Throwable e) {
            return null;
        }
    }

    public static void sendEnter(PacketType type) {
        SENDING.set(type);
    }

    public static void sendExit() {
        SENDING.remove();
    }

    /** Called at endPacket; counts only packets sent through PacketType.send. */
    public static void sent(UdpConnection c) {
        PacketType type = SENDING.get();
        if (type == null) return;
        NetStats.out(type, position(c));
    }

    static int position(UdpConnection c) {
        Field f = BB;
        if (f == null || c == null) return 0;
        try {
            ByteBuffer bb = (ByteBuffer) f.get(c);
            return bb == null ? 0 : bb.position();
        } catch (Throwable e) {
            return 0;
        }
    }

    public static void received(ZomboidNetData d) {
        if (d != null) NetStats.in(d.type, d.length);
    }

    @Patch(className = "zombie.network.PacketTypes$PacketType", methodName = "send")
    public static class PacketSend {
        @Patch.OnEnter
        public static void enter(@Patch.This PacketType type) {
            sendEnter(type);
        }

        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit() {
            sendExit();
        }
    }

    @Patch(className = "zombie.core.raknet.UdpConnection", methodName = "endPacket")
    public static class PacketSent {
        @Patch.OnEnter
        public static void enter(@Patch.This UdpConnection c) {
            sent(c);
        }
    }

    @Patch(className = "zombie.network.GameClient", methodName = "mainLoopDealWithNetData")
    public static class ClientReceive {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(0) ZomboidNetData d) {
            received(d);
        }
    }

    @Patch(className = "zombie.network.GameClient", methodName = "gameLoadingDealWithNetData")
    public static class ClientLoading {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(0) ZomboidNetData d) {
            received(d);
        }
    }

    @Patch(className = "zombie.network.GameServer", methodName = "mainLoopDealWithNetData")
    public static class ServerReceive {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(0) ZomboidNetData d) {
            received(d);
        }
    }
}
