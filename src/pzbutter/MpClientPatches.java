package pzbutter;

import java.nio.ByteBuffer;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.core.network.ByteBufferReader;
import zombie.iso.IsoChunk;
import zombie.network.GameClient;
import zombie.network.IConnection;
import zombie.network.packets.vehicle.VehicleFullUpdatePacket;
import zombie.network.packets.vehicle.VehicleUpdatePacket;
import zombie.vehicles.VehicleInterpolation;
import zombie.vehicles.VehicleManager;

/** Game seams for the MP client rows of Plan 18. */
public final class MpClientPatches {

    private MpClientPatches() {}

    /** Body bytes of the packet whose parse is currently running on this thread. */
    public static final ThreadLocal<int[]> RANGE = new ThreadLocal<>();

    public static byte[] body(ByteBufferReader r, int start, int limit) {
        ByteBuffer d = r.bb.duplicate();
        int from = Math.max(0, Math.min(start, d.capacity()));
        int to = Math.max(from, Math.min(limit, d.capacity()));
        d.position(from);
        d.limit(to);
        byte[] b = new byte[d.remaining()];
        d.get(b);
        return b;
    }

    /** parked car arrives before its chunk: keep the bytes instead of losing the update. */
    @Patch(className = "zombie.network.packets.vehicle.VehicleFullUpdatePacket", methodName = "parse")
    public static class VehicleAnswer {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(0) ByteBufferReader r) {
            int start = r.position();
            RANGE.set(new int[] {start, r.limit()});
            MpClient.beforeAnswer(body(r, start, r.limit()), System.currentTimeMillis());
        }

        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Patch.This Object packet, @Patch.Argument(0) ByteBufferReader r,
                @Patch.Argument(1) IConnection c) {
            int[] range = RANGE.get();
            RANGE.remove();
            if (range == null) return;
            byte[] body = body(r, range[0], range[1]);
            if (!consistent(packet, c)) {
                if (body.length >= 2) MpClient.keep(id(body), body, c, System.currentTimeMillis());
                return;
            }
            if (body.length >= 2) MpClient.answerSeen(id(body), body, System.currentTimeMillis());
        }
    }

    /** A failed IsoChunk.LoadChunk leaves a dead SharedChunks entry; remember it as a hole. */
    @Patch(className = "zombie.iso.IsoChunk", methodName = "LoadChunk")
    public static class ChunkLoad {
        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Patch.This IsoChunk chunk, @Patch.Return boolean ok) {
            if (ok) MpClient.present(chunk.wx, chunk.wy);
            else {
                MpClient.hole(chunk.wx, chunk.wy, System.currentTimeMillis());
                MpClientPatches.noteRefill();
            }
        }
    }

    /** End of the client update: early vehicle parse and re-request of dead chunk slots. */
    @Patch(className = "zombie.network.GameClient", methodName = "update")
    public static class ClientTick {
        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit() {
            MpClient.tick();
        }
    }

    /** Object, not VehicleFullUpdatePacket: a signature naming a @PacketSetting class breaks ZombieBuddy's preload (PreloadSafetyTest). */
    public static boolean consistent(Object p, IConnection c) {
        return ((VehicleFullUpdatePacket) p).isConsistent(c);
    }

    public static short id(byte[] body) {
        return (short) (((body[0] & 0xff) << 8) | (body[1] & 0xff));
    }

    public static void noteRefill() {
        refills++;
    }

    public static volatile long refills;

    /** Remote vehicle intake advice (skip on true means mod took the sample). */
    @Patch(className = "zombie.vehicles.VehicleInterpolation", methodName = "interpolationDataAdd", strictMatch = true)
    public static class VehicleIntake {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object self, @Patch.Argument(0) Object vehicle,
                                    @Patch.Argument(1) Object data, @Patch.Argument(2) long time) {
            try { return RemoteVehicles.onIntake(self, vehicle, data, time); }
            catch (Throwable t) { RemoteVehicles.guard.fail(t); return false; }
        }
    }

    /** The 3-argument overload the manager draws a remote car with (strict match: other callers keep vanilla). */
    @Patch(className = "zombie.vehicles.VehicleInterpolation", methodName = "interpolationDataGet", strictMatch = true)
    public static class VehiclePose {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object self, @Patch.Argument(0) float[] out,
                                    @Patch.Argument(1) float[] engine, @Patch.Argument(2) VehicleInterpolation towing) {
            try { return RemoteVehicles.onPose(self, out, engine, towing); }
            catch (Throwable t) { RemoteVehicles.guard.fail(t); return false; }
        }

        /** Skipped (the track answered) the method returns false by default: put true back. */
        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) boolean ret) {
            try { ret = RemoteVehicles.poseReturn(ret); }
            catch (Throwable t) { RemoteVehicles.guard.fail(t); }
        }
    }

    /** vehicleSendMs: after vanilla sent and set its 150 ms period, the next send waits sendMs instead. */
    @Patch(className = "zombie.vehicles.BaseVehicle", methodName = "updatePhysicsNetwork")
    public static class VehicleSend {
        @Patch.OnExit
        public static void exit(@Patch.This Object self) {
            try { RemoteVehicles.afterSend(self); }
            catch (Throwable t) { RemoteVehicles.sendGuard.fail(t); }
        }
    }

    @Patch(className = "zombie.vehicles.VehicleInterpolation", methodName = "clear")
    public static class VehicleClear {
        @Patch.OnEnter
        public static void enter(@Patch.This Object self) {
            try { RemoteVehicles.onClear(self); }
            catch (Throwable t) { RemoteVehicles.guard.fail(t); }
        }
    }

    @Patch(className = "zombie.network.packets.vehicle.VehicleUpdatePacket", methodName = "parse")
    public static class VehicleParse {
        @Patch.OnEnter
        public static void enter() {
            try { RemoteVehicles.parseDepth++; }
            catch (Throwable t) { RemoteVehicles.guard.fail(t); }
        }

        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit() {
            try { if (RemoteVehicles.parseDepth > 0) RemoteVehicles.parseDepth--; }
            catch (Throwable t) { RemoteVehicles.guard.fail(t); }
        }
    }

    @Patch(className = "zombie.vehicles.VehicleManager", methodName = "clientUpdate")
    public static class VehicleFrame {
        @Patch.OnEnter
        public static void enter() {
            try { RemoteVehicles.frameEnter(); }
            catch (Throwable t) { RemoteVehicles.guard.fail(t); }
        }

    }

    static void resetForTest() {
        refills = 0;
        RANGE.remove();
    }
}
