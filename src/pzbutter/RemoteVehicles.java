package pzbutter;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.WeakHashMap;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.utils.UpdateLimit;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehicleInterpolation;
import zombie.vehicles.VehicleInterpolationData;

/**
 * Remote vehicles on an MP client (Plan 15 Task 8, covers SmoothRide MP; docs/specs/SmoothRide.md §3). The game
 * seams; the logic is in VehTrack. Main thread only.
 *
 * - vehicleSendMs: the driver's physics send period, 150 ms (stock) down to 16 ms. Only a period of exactly 150 ms
 *   (vanilla's "moving") is retimed; the 300 ms parked ease-off and other mods' periods stay.
 * - vehicleRemoteSmooth: other players' cars are drawn from a VehTrack per VehicleInterpolation instead of vanilla's
 *   500 ms buffer. Active only on an MP client with the server time shift set, after the reflection probe and the
 *   self-test passed; otherwise every hook leaves vanilla alone.
 */
public final class RemoteVehicles {
    private RemoteVehicles() {}

    public static final Guard guard = new Guard("vehicleRemoteSmooth");
    public static final Guard sendGuard = new Guard("vehicleSendMs");

    // ---- per-frame state (VehicleManager.clientUpdate) ----
    static volatile boolean keyOn;
    static volatile int sendMs = 150;
    static long frame;
    static long nowMs;
    public static int parseDepth;
    static boolean answered;
    private static long nextReportMs;

    // ---- reflection: 0 not tried, 1 ok, -1 failed ----
    static int probe;
    static int selfTest;
    static boolean selfTesting;
    private static long selfTestNow;
    private static Field fTime, fX, fY, fZ, fQx, fQy, fQz, fQw, fVx, fVy, fVz, fEngine, fThrottle, fWheels, fSteer, fRot, fSkid, fSusp;
    private static Field fNetworkUpdated, fLimit;

    private static final Map<Object, VehTrack> TRACKS = new WeakHashMap<>();

    // ---- statistics ----
    static long echoes, retimed, taken;

    /** Test seam for the self-test: the call that draws the planted track (default: the game's own 3-argument get). */
    interface Get { boolean get(Object interpolation, float[] out, float[] engine); }
    static final Get GAME_GET = (vi, out, eng) -> ((VehicleInterpolation) vi).interpolationDataGet(out, eng, null);
    static Get selfTestGet = GAME_GET;
    /** Test seam: replaces Side.mpClient() and the server time shift check. */
    static Boolean testOnline;

    public static long retime(long periodMs, int sendMs) {
        return periodMs == 150 ? sendMs : periodMs;
    }

    static boolean online() {
        if (testOnline != null) return testOnline;
        return Side.mpClient() && zombie.GameTime.getServerTimeShiftIsSet();
    }

    static boolean active() {
        return guard.on && keyOn && probe == 1 && selfTest == 1 && online();
    }

    // ---- frame boundary ----

    public static void frameEnter() {
        try {
            frame++;
            keyOn = Settings.flag("vehicleRemoteSmooth");
            sendMs = Settings.enabled ? Settings.num("vehicleSendMs", 150, 16, 150) : 150;
            if (!keyOn && sendMs >= 150) return;
            if (!online()) return;
            nowMs = zombie.GameTime.getServerTimeMills();
            if (probe == 0) probe();
            if (keyOn && probe == 1 && selfTest == 0) selfTest();
            if (nowMs >= nextReportMs) {
                if (nextReportMs != 0) report();
                nextReportMs = nowMs + 60_000;
            }
        } catch (Throwable t) { guard.fail(t); }
    }

    static void probe() {
        try {
            Class<?> d = VehicleInterpolationData.class;
            fTime = f(d, "time"); fX = f(d, "x"); fY = f(d, "y"); fZ = f(d, "z");
            fQx = f(d, "qx"); fQy = f(d, "qy"); fQz = f(d, "qz"); fQw = f(d, "qw");
            fVx = f(d, "vx"); fVy = f(d, "vy"); fVz = f(d, "vz");
            fEngine = f(d, "engineSpeed"); fThrottle = f(d, "throttle"); fWheels = f(d, "wheelsCount");
            fSteer = f(d, "wheelSteering"); fRot = f(d, "wheelRotation"); fSkid = f(d, "wheelSkidInfo"); fSusp = f(d, "wheelSuspensionLength");
            fNetworkUpdated = f(BaseVehicle.class, "networkUpdated");
            fLimit = f(BaseVehicle.class, "limitPhysicSend");
            probe = 1;
        } catch (Throwable t) {
            probe = -1;
            System.out.println("[PZButter] remote vehicles: off, this game build does not match (" + t + "); vanilla vehicle sync is used");
        }
    }

    private static Field f(Class<?> c, String name) throws NoSuchFieldException {
        Field x = c.getDeclaredField(name);
        x.setAccessible(true);
        return x;
    }

    /** Two identical samples 800 ms apart at delay 0, drawn through the hooked 3-argument get: it must answer with that place. */
    static void selfTest() {
        Object vi = null;
        boolean pass = false, returned = false;
        float[] out = new float[VehTrack.POSE + 4 * VehTrack.WHEELS_MAX];
        float[] eng = new float[2];
        try {
            Constructor<VehicleInterpolation> c = VehicleInterpolation.class.getDeclaredConstructor();
            c.setAccessible(true);
            vi = c.newInstance();
            VehTrack t = new VehTrack();
            long now = nowMs;
            float[] s = VehTrack.newSample();
            s[0] = 1234.5f; s[1] = 0.25f; s[2] = 678.75f;
            t.add(now - 800, now - 800, s, false);
            t.add(now, now, s, false);
            t.forceDelay(0);
            TRACKS.put(vi, t);
            selfTesting = true;
            selfTestNow = now;
            returned = selfTestGet.get(vi, out, eng);
            pass = returned && Math.abs(out[0] - 1234.5f) < 1e-3f && Math.abs(out[1] - 0.25f) < 1e-3f && Math.abs(out[2] - 678.75f) < 1e-3f;
        } catch (Throwable t) {
            System.out.println("[PZButter] remote vehicles: self-test threw " + t);
        } finally {
            selfTesting = false;
            if (vi != null) TRACKS.remove(vi);
        }
        selfTest = pass ? 1 : -1;
        if (pass) System.out.println("[PZButter] remote vehicles: self-test passed (returned true at "
            + out[0] + "," + out[1] + "," + out[2] + "); vehicleRemoteSmooth on");
        else System.out.println("[PZButter] vehicleRemoteSmooth: off, the self-test failed (returned " + returned + " at "
            + out[0] + "," + out[1] + "," + out[2] + "); remote vehicles stay on vanilla interpolation");
    }

    // ---- driver side ----

    /** Exit of BaseVehicle.updatePhysicsNetwork: a packet went out at 150 ms, so the next one goes out at sendMs. */
    public static void afterSend(Object vehicle) {
        try {
            if (sendMs >= 150 || probe != 1 || !sendGuard.on || !(vehicle instanceof BaseVehicle v)) return;
            if (!fNetworkUpdated.getBoolean(v)) return;
            UpdateLimit limit = (UpdateLimit) fLimit.get(v);
            if (limit == null) return;
            long period = limit.getDelay();
            long next = retime(period, sendMs);
            if (next != period) { limit.setUpdatePeriod(next); retimed++; }
        } catch (Throwable t) { sendGuard.fail(t); }
    }

    // ---- receiver side ----

    /** Enter of interpolationDataAdd. True: the track took the sample (or dropped a server echo) and vanilla skips it. */
    public static boolean onIntake(Object interpolation, Object vehicle, Object data, long arrivalMs) {
        try {
            if (!active() || interpolation == null || !(vehicle instanceof BaseVehicle v) || !(data instanceof VehicleInterpolationData d)) return false;
            VehTrack t = TRACKS.computeIfAbsent(interpolation, k -> new VehTrack());
            if (parseDepth > 0 && remoteDriven(v) && t.hasArrival() && arrivalMs - t.lastArrival() <= VehTrack.BRIDGE_MS) {
                echoes++;   // the server's own position update for a car another player drives: older than his packets
                return true;
            }
            float[] s = VehTrack.newSample();
            read(d, s);
            VehTrack.Insert r = t.add(fTime.getLong(d), arrivalMs, s, false);
            if (r == VehTrack.Insert.REFUSED) return false;
            if (r == VehTrack.Insert.IDLE_RESTART) {
                VehicleInterpolationData drawn = new VehicleInterpolationData();
                drawn.set(v);   // the pose the game draws now: the curve starts where the car stands
                float[] seed = VehTrack.newSample();
                read(drawn, seed);
                t.seed(seed);
            }
            taken++;
            return true;
        } catch (Throwable t) { guard.fail(t); return false; }
    }

    private static boolean remoteDriven(BaseVehicle v) {
        IsoGameCharacter driver = v.getDriver();
        return driver instanceof IsoPlayer p && !p.isLocalPlayer();
    }

    private static void read(VehicleInterpolationData d, float[] s) throws IllegalAccessException {
        s[0] = fX.getFloat(d); s[1] = fY.getFloat(d); s[2] = fZ.getFloat(d);
        s[3] = fQx.getFloat(d); s[4] = fQy.getFloat(d); s[5] = fQz.getFloat(d); s[6] = fQw.getFloat(d);
        s[7] = fVx.getFloat(d); s[8] = fVy.getFloat(d); s[9] = fVz.getFloat(d);
        float[] steer = (float[]) fSteer.get(d), rot = (float[]) fRot.get(d), skid = (float[]) fSkid.get(d), susp = (float[]) fSusp.get(d);
        int n = Math.max(0, Math.min(VehTrack.WHEELS_MAX, fWheels.getShort(d)));
        s[10] = n;
        for (int i = 0; i < n; i++) {
            int o = VehTrack.POSE + 4 * i;
            s[o] = at(steer, i, 0);
            s[o + 1] = at(rot, i, 0);
            s[o + 2] = at(skid, i, 0);
            s[o + 3] = at(susp, i, 0.3f);   // SmoothRide's fallback for a missing suspension length
        }
        s[VehTrack.ENGINE] = fEngine.getFloat(d);
        s[VehTrack.ENGINE + 1] = fThrottle.getFloat(d);
    }

    private static float at(float[] a, int i, float dflt) {
        return a != null && i < a.length ? a[i] : dflt;
    }

    /** Enter of the 3-argument interpolationDataGet. True: the track wrote the pose; the exit then returns true. */
    public static boolean onPose(Object interpolation, float[] out, float[] engine, Object towing) {
        answered = false;
        try {
            if (!selfTesting && !active()) return false;
            VehTrack t = TRACKS.get(interpolation);
            if (t == null) return false;
            VehTrack tow = towing != null ? TRACKS.get(towing) : null;
            long now = selfTesting ? selfTestNow : nowMs;
            long dt = t.lastPoseNow == 0 ? 0 : now - t.lastPoseNow;
            t.lastPoseNow = now;
            answered = t.pose(now, dt, out, engine, tow, frame);
            return answered;
        } catch (Throwable x) { guard.fail(x); answered = false; return false; }
    }

    /** Exit of the same call: true when the track answered, else vanilla's own value. */
    public static boolean poseReturn(boolean vanilla) {
        boolean r = answered || vanilla;
        answered = false;
        return r;
    }

    /** Enter of VehicleInterpolation.clear: the track's samples and correction go. */
    public static void onClear(Object interpolation) {
        try {
            VehTrack t = TRACKS.get(interpolation);
            if (t != null) t.clear();
        } catch (Throwable x) { guard.fail(x); }
    }

    // ---- minute line ----

    static void report() {
        int n = 0;
        float sum = 0, max = 0;
        long interp = 0, pred = 0, hold = 0, start = 0, snaps = 0, teleports = 0;
        for (VehTrack t : TRACKS.values()) {
            long frames = t.framesInterp + t.framesPred + t.framesHold + t.framesStart;
            if (frames == 0) continue;
            n++;
            sum += t.delayMs();
            max = Math.max(max, t.delayMs());
            interp += t.framesInterp; pred += t.framesPred; hold += t.framesHold; start += t.framesStart;
            snaps += t.snaps; teleports += t.teleports;
            t.resetStats();
        }
        long all = Math.max(1, interp + pred + hold + start);
        if (n > 0 || echoes > 0 || retimed > 0) {
            System.out.println(String.format(java.util.Locale.ROOT,
                "[PZButter] vehicles: %d drawn, delay avg %.0f max %.0f ms, interp/pred/hold %d/%d/%d %%, snaps %d, teleports %d, echoes %d, sends retimed %d",
                n, n > 0 ? sum / n : 0, max, 100 * interp / all, 100 * pred / all, 100 * hold / all, snaps, teleports, echoes, retimed));
        }
        echoes = 0;
        retimed = 0;
        taken = 0;
    }

    /** Main: the guards start on; the keys, the side and the probe decide each frame. */
    public static void init() {
        guard.on = true;
        sendGuard.on = true;
    }

    static void resetForTest() {
        TRACKS.clear();
        probe = 0; selfTest = 0; selfTesting = false; answered = false; parseDepth = 0;
        keyOn = false; sendMs = 150; frame = 0; nowMs = 0; nextReportMs = 0;
        echoes = retimed = taken = 0;
        guard.on = true; sendGuard.on = true;
        selfTestGet = GAME_GET;
        testOnline = null;
    }
}
