package pzbutter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import zombie.core.network.ByteBufferReader;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunkMap;
import zombie.iso.IsoWorld;
import zombie.network.IConnection;
import zombie.network.packets.vehicle.VehicleFullUpdatePacket;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehicleManager;

/**
 * Multiplayer client rows of Plan 18 (SPEC 6.7.3 vehicleEarlyParse, 6.6.1
 * chunkRefill). The decision logic lives here so it can be tested without a
 * running game; MpClientPatches supplies the game seams.
 */
public final class MpClient {

    private MpClient() {}

    static final Guard guard = new Guard("vehicleEarlyParse");
    static final Guard refillGuard = new Guard("chunkRefill");

    public enum Action { UPDATE, REPLACE }

    private static final long KEEP_MS = 30_000;
    private static final Map<Short, Kept> KEPT = new LinkedHashMap<>();
    private static final Refill REFILL = new Refill();
    private static String testMode;
    private static long parseAttempts;
    private static long verifyEqual;
    private static long verifyDifferent;

    /** Default off; "verify" keeps and compares, "on" also places the car early. */
    public static String mode() {
        String m = testMode != null ? testMode : Settings.text("vehicleEarlyParse", "off");
        m = m == null ? "off" : m.trim().toLowerCase(Locale.ROOT);
        return m.equals("on") || m.equals("verify") ? m : "off";
    }

    public static boolean reparseEnabled() {
        return mode().equals("on");
    }

    public static void keep(short id, byte[] packet, long nowMs) {
        keep(id, packet, null, nowMs);
    }

    public static void keep(short id, byte[] packet, IConnection conn, long nowMs) {
        if (packet == null || packet.length == 0) return;
        KEPT.put(id, new Kept(id, packet.clone(), conn, nowMs));
    }

    /** The kept body, or null once 30 s old or superseded by another packet. */
    public static byte[] takeFor(short id, long nowMs) {
        Kept k = KEPT.get(id);
        if (k == null) return null;
        if (nowMs - k.atMs >= KEEP_MS) {
            KEPT.remove(id);
            return null;
        }
        KEPT.remove(id);
        return k.bytes.clone();
    }

    public static void forget(short id) {
        KEPT.remove(id);
    }

    /** A later full update for the same car: equal bytes update, anything else replaces. */
    public static Action decide(byte[] kept, byte[] answer) {
        return Arrays.equals(kept, answer) ? Action.UPDATE : Action.REPLACE;
    }

    /** Fresh kept packets for the early parse at the end of GameClient.update. */
    public static List<Kept> due(long nowMs) {
        List<Kept> out = new ArrayList<>();
        for (Kept k : new ArrayList<>(KEPT.values())) {
            if (nowMs - k.atMs >= KEEP_MS) KEPT.remove(k.id);
            else out.add(k);
        }
        return out;
    }

    /** Called before the answer packet is parsed, so a replace can remove the early car first. */
    public static void beforeAnswer(byte[] body, long nowMs) {
        try {
            if (!mode().equals("on") || body == null || body.length < 2) return;
            short id = (short) (((body[0] & 0xff) << 8) | (body[1] & 0xff));
            Kept k = KEPT.get(id);
            if (k == null) return;
            if (decide(k.bytes, body) == Action.REPLACE) removeVehicle(id);
            KEPT.remove(id);
        } catch (Throwable t) {
            guard.fail(t);
        }
    }

    /** Called after a full update was parsed; counts the verify comparison when one was kept. */
    public static void answerSeen(short id, byte[] body, long nowMs) {
        Kept k = KEPT.get(id);
        if (k == null) return;
        KEPT.remove(id);
        if (!mode().equals("verify")) return;
        if (decide(k.bytes, body) == Action.UPDATE) verifyEqual++;
        else verifyDifferent++;
    }

    public static void noteParse() {
        parseAttempts++;
    }

    public static long verifyEqual() { return verifyEqual; }
    public static long verifyDifferent() { return verifyDifferent; }
    public static long parseAttemptsForTest() { return parseAttempts; }
    public static int keptCountForTest() { return KEPT.size(); }

    private static void removeVehicle(short id) {
        try {
            VehicleManager vm = VehicleManager.instance;
            if (vm == null) return;
            BaseVehicle v = vm.getVehicleByID(id);
            if (v != null) vm.removeFromWorld(v);
        } catch (Throwable t) {
            guard.fail(t);
        }
    }

    /** The end-of-update work: early parse in "on", and chunk holes when chunkRefill is on. */
    public static void tick() {
        long now = System.currentTimeMillis();
        if (reparseEnabled()) reparse(now);
        if (Settings.flag("chunkRefill")) refill(now);
    }

    private static void reparse(long now) {
        for (Kept k : due(now)) {
            try {
                VehicleFullUpdatePacket p = new VehicleFullUpdatePacket();
                ByteBufferReader r = new ByteBufferReader(java.nio.ByteBuffer.wrap(k.bytes));
                noteParse();
                p.parse(r, k.conn);
                if (k.conn == null || p.isConsistent(k.conn)) KEPT.remove(k.id);
            } catch (Throwable t) {
                KEPT.remove(k.id);
                guard.fail(t);
            }
        }
    }

    public static void hole(int x, int y, long nowMs) {
        REFILL.observe(x, y, nowMs);
    }

    public static void present(int x, int y) {
        REFILL.present(x, y);
    }

    public static List<Hole> holes(long nowMs, boolean busy) {
        return REFILL.pass(nowMs, busy);
    }

    private static void refill(long now) {
        try {
            if (IsoWorld.instance == null || IsoWorld.instance.currentCell == null) return;
            // ponytail: streamer busy is not observable here; the quiet 4 s rule is the
            // safe one, and moving within one chunk is rare enough to wait for it.
            List<Hole> due = holes(now, false);
            if (due.isEmpty()) return;
            IsoCell cell = IsoWorld.instance.currentCell;
            IsoChunkMap map = cell.getChunkMap(0);
            if (map == null) return;
            IsoChunkMap.bSettingChunk.lock();
            try {
                for (Hole h : due) {
                    IsoChunkMap.SharedChunks.remove((h.x() << 16) + h.y());
                    map.LoadChunkForLater(h.x(), h.y(), 0, 0);
                }
            } finally {
                IsoChunkMap.bSettingChunk.unlock();
            }
        } catch (Throwable t) {
            refillGuard.fail(t);
        }
    }

    public static long backoffMs(int asks) {
        return asks <= 1 ? 15_000 : asks == 2 ? 30_000 : asks == 3 ? 60_000 : 120_000;
    }

    static void setModeForTest(String m) {
        testMode = m;
    }

    static void resetForTest() {
        testMode = null;
        KEPT.clear();
        REFILL.clear();
        parseAttempts = 0;
        verifyEqual = 0;
        verifyDifferent = 0;
    }

    public static final class Kept {
        public final short id;
        final byte[] bytes;
        final IConnection conn;
        final long atMs;

        Kept(short id, byte[] bytes, IConnection conn, long atMs) {
            this.id = id;
            this.bytes = bytes;
            this.conn = conn;
            this.atMs = atMs;
        }

        public byte[] bytes() { return bytes.clone(); }
    }

    public static final class Hole {
        private final int x;
        private final int y;
        private final long firstMs;
        private int asks;
        private long nextMs;

        Hole(int x, int y, long firstMs) {
            this.x = x;
            this.y = y;
            this.firstMs = firstMs;
            this.nextMs = firstMs;
        }

        public int x() { return x; }
        public int y() { return y; }
        public int asks() { return asks; }
        public long nextAtMs() { return nextMs; }
    }

    /** Hole bookkeeping: 4 s quiet / 25 s busy, one pass every 3 s, at most 6 asks. */
    public static final class Refill {
        static final long QUIET_MS = 4_000;
        static final long BUSY_MS = 25_000;
        static final long PASS_MS = 3_000;
        static final int MAX_PER_PASS = 6;

        private final Map<Long, Hole> holes = new LinkedHashMap<>();
        private long lastPassMs = Long.MIN_VALUE;

        private static long key(int x, int y) {
            return ((long) x << 32) ^ (y & 0xffffffffL);
        }

        public void observe(int x, int y, long nowMs) {
            holes.putIfAbsent(key(x, y), new Hole(x, y, nowMs));
        }

        public void present(int x, int y) {
            holes.remove(key(x, y));
        }

        public List<Hole> pass(long nowMs, boolean busy) {
            List<Hole> out = new ArrayList<>();
            if (lastPassMs != Long.MIN_VALUE && nowMs - lastPassMs < PASS_MS) return out;
            long wait = busy ? BUSY_MS : QUIET_MS;
            for (Hole h : holes.values()) {
                if (out.size() >= MAX_PER_PASS) break;
                if (nowMs < h.nextMs) continue;
                if (nowMs - h.firstMs < wait) continue;
                out.add(h);
                h.asks++;
                h.nextMs = nowMs + backoffMs(h.asks);
            }
            if (!out.isEmpty()) lastPassMs = nowMs;
            return out;
        }

        public void clear() {
            holes.clear();
            lastPassMs = Long.MIN_VALUE;
        }
    }
}
