package pzbutter;

/** Plan 15 Task 8: VehTrack and RemoteVehicles (covers SmoothRide). Run with -ea. */
final class VehTrackTest {
    private VehTrackTest() {}

    static void run() {
        retimeOnly150();
        hermiteFallsBack();
        teleportEmpties();
        lateSampleInOrder();
        hitchStaleNotMeasured();
        delayRateLimited();
        holdThenHandBack();
        correctionNoOvershoot();
        engineAndWheelsCarried();
        trailerUsesTowInstant();
        turnAndSpeedShaping();
        predictionArcs();
        selfTestFailLeavesVanilla();
        RemoteVehicles.resetForTest();
    }

    /** x, z, vx, vz; level, unit rotation, 4 wheels. */
    private static float[] s(float x, float z, float vx, float vz) {
        float[] a = VehTrack.newSample();
        a[0] = x; a[2] = z; a[7] = vx; a[9] = vz; a[10] = 4;
        return a;
    }

    private static boolean near(float a, float b, float eps) { return Math.abs(a - b) <= eps; }

    static void retimeOnly150() {
        assert RemoteVehicles.retime(150, 33) == 33 : "150 is retimed";
        assert RemoteVehicles.retime(300, 33) == 300 : "the parked 300 ms ease-off stays";
        assert RemoteVehicles.retime(100, 33) == 100 : "another mod's period stays";
    }

    static void hermiteFallsBack() {
        float[] out = new float[VehTrack.POSE + 16];
        // a 3.2 s span (over the 3 s guard, under the 3.4 s bridge): the straight line, not the curve the velocities ask for
        // (the bend is under the deviation guard, 0.94 m against 1 m, so only the span guard keeps it straight)
        VehTrack t = new VehTrack();
        t.add(0, 0, s(0, 0, 0, 1), false);
        assert t.add(3200, 3200, s(10, 0, 0, 1), false) == VehTrack.Insert.KEPT : "a 3.2 s gap is kept";
        assert t.pose(800 + 120, 0, out, null) : "pose";
        assert near(out[0], 2.5f, 1e-3f) && near(out[2], 0, 1e-3f) : "3.2 s span: straight line, got " + out[0] + "," + out[2];
        // a curve 2 m off a 5 m chord: the straight line
        VehTrack t2 = new VehTrack();
        t2.add(0, 0, s(0, 0, 0, 8), false);
        t2.add(1000, 1000, s(5, 0, 0, -8), false);
        assert t2.pose(500 + 120, 0, out, null);
        assert near(out[0], 2.5f, 1e-3f) && near(out[2], 0, 1e-3f) : "2 m off a 5 m chord: straight line, got " + out[2];
        // a gentle curve (0.25 m off) is drawn as the curve
        VehTrack t3 = new VehTrack();
        t3.add(0, 0, s(0, 0, 5, 1), false);
        t3.add(1000, 1000, s(5, 0, 5, -1), false);
        assert t3.pose(500 + 120, 0, out, null);
        assert near(out[2], 0.25f, 1e-3f) : "gentle curve kept, got " + out[2];
    }

    static void teleportEmpties() {
        VehTrack t = new VehTrack();
        t.add(0, 0, s(0, 0, 0, 0), false);
        assert t.add(400, 400, s(40, 0, 0, 0), false) == VehTrack.Insert.TELEPORT : "40 m at 100 m/s is a teleport";
        assert t.sampleCount() == 1 : "a teleport empties the buffer";
        VehTrack r = new VehTrack();
        r.add(0, 0, s(0, 0, 0, 0), false);
        float[] turned = s(1, 0, 0, 0);
        turned[4] = (float) Math.sin(Math.toRadians(45)); turned[6] = (float) Math.cos(Math.toRadians(45));   // 90 degrees about y
        assert r.add(100, 100, turned, false) == VehTrack.Insert.TELEPORT : "90 degrees in 100 ms is a teleport";
    }

    static void lateSampleInOrder() {
        VehTrack t = new VehTrack();
        t.add(200, 200, s(10, 0, 0, 0), false);
        assert t.add(100, 210, s(5, 0, 0, 0), false) == VehTrack.Insert.KEPT : "an older stamp is not a teleport";
        assert t.sampleCount() == 2;
        float[] out = new float[VehTrack.POSE];
        assert t.pose(150 + 120, 0, out, null);
        assert near(out[0], 7.5f, 1e-3f) : "the late sample is history in order, got " + out[0];
    }

    static void hitchStaleNotMeasured() {
        VehTrack t = new VehTrack();
        for (int i = 0; i < 6; i++) t.add(i * 33, i * 33 + 40, s(i, 0, 0, 0), false);
        assert t.latenessCount() == 6;
        long lastArrival = 5 * 33 + 40;
        // 400 ms of silence, then a sample 60 ms later than the 40 ms lateness: kept, not measured
        t.add(lastArrival + 400 - 100, lastArrival + 400, s(6, 0, 0, 0), false);
        assert t.sampleCount() == 7 : "the stale sample is kept";
        assert t.latenessCount() == 6 : "the stale sample is not measured";
        // after the 60 ms burst window the same lateness is measured again
        t.add(lastArrival + 480 - 100, lastArrival + 480, s(7, 0, 0, 0), false);
        assert t.latenessCount() == 7 : "measured after the burst window";
    }

    static void delayRateLimited() {
        VehTrack t = new VehTrack();
        for (int i = 0; i < 8; i++) t.add(i * 33, i * 33, s(0, 0, 0, 0), false);
        t.forceDelay(20);
        long now = 8 * 33;
        // lateness jumps to 290 ms: the target jumps to about 320 ms
        for (int i = 0; i < 6; i++) { now += 16; t.add(now - 290, now, s(0, 0, 0, 0), false); }
        assert t.delayTarget() > 300 : "target " + t.delayTarget();
        float[] out = new float[VehTrack.POSE];
        float lastDelay = t.delayMs(), lastRate = t.delayRate();
        boolean grew = false;
        for (int f = 0; f < 200; f++) {
            now += 16;
            t.add(now - 290, now, s(0, 0, 0, 0), false);
            t.pose(now, 16, out, null);
            float d = t.delayMs(), r = t.delayRate();
            assert d - lastDelay <= 0.15f * 16 + 1e-3f : "grew faster than 0.15 ms per ms: " + (d - lastDelay);
            assert Math.abs(r - lastRate) <= 0.3f * 0.016f + 1e-5f : "rate changed faster than 0.3 per s";
            if (d > lastDelay) grew = true;
            lastDelay = d; lastRate = r;
        }
        assert grew && lastDelay > 20 : "the delay moved toward the target";
    }

    static void holdThenHandBack() {
        float[] out = new float[VehTrack.POSE];
        VehTrack moving = new VehTrack();
        moving.add(0, 0, s(0, 0, 0, 10), false);
        assert moving.pose(3000 + 120, 16, out, null) : "a moving car silent for 3 s is held";
        assert !moving.pose(10_000 + 120, 16, out, null) : "and handed back at 10 s";
        VehTrack parked = new VehTrack();
        parked.add(0, 0, s(0, 0, 0, 0), false);
        assert !parked.pose(3000 + 120, 16, out, null) : "a parked car is handed back after the 2.9 s window";
    }

    static void correctionNoOvershoot() {
        VehTrack t = new VehTrack();
        float[] out = new float[VehTrack.POSE];
        t.add(0, 0, s(0, 0, 0, 0), false);
        t.add(100, 100, s(0, 0, 0, 0), false);
        long now = 170;
        t.pose(now, 0, out, null);
        float before = out[0];
        t.add(100, 101, s(2, 0, 0, 0), false);   // replaces the sample under the car: a 2 m jump in the curve
        now += 16;
        t.pose(now, 16, out, null);
        assert near(out[0], before, 0.05f) : "the jump is folded, not drawn: " + before + " -> " + out[0];
        float sign = Math.signum(t.correctionX());
        assert sign != 0 : "a correction was folded";
        for (int i = 0; i < 150; i++) {
            now += 16;
            t.pose(now, 16, out, null);
            float c = t.correctionX();
            assert c == 0 || Math.signum(c) == sign : "the correction overshot at frame " + i;
        }
        assert t.correctionMag() < 0.01f : "decayed under 1 cm: " + t.correctionMag();
    }

    static void engineAndWheelsCarried() {
        VehTrack t = new VehTrack();
        float[] a = s(0, 0, 0, 0), b = s(1, 0, 0, 0);
        a[VehTrack.ENGINE] = 1000; b[VehTrack.ENGINE] = 2000;
        a[VehTrack.ENGINE + 1] = 0.2f; b[VehTrack.ENGINE + 1] = 0.6f;
        a[VehTrack.POSE] = 0.1f; b[VehTrack.POSE] = 0.3f;           // wheel 0 steering
        a[VehTrack.POSE + 3] = 0.4f; b[VehTrack.POSE + 3] = 0.2f;   // wheel 0 suspension
        t.add(0, 0, a, false);
        t.add(100, 100, b, false);
        float[] out = new float[VehTrack.POSE + 4 * 4];
        float[] eng = new float[2];
        assert t.pose(50 + 120, 0, out, eng);
        assert near(eng[0], 1500, 1e-2f) && near(eng[1], 0.4f, 1e-4f) : "engine speed and throttle interpolated";
        assert out[10] == 4 : "wheel count written";
        assert near(out[VehTrack.POSE], 0.2f, 1e-4f) && near(out[VehTrack.POSE + 3], 0.3f, 1e-4f) : "wheel data interpolated";
        float[] small = new float[VehTrack.POSE + 4];
        t.pose(60 + 120, 10, small, null);
        assert small[10] == 1 : "the wheel count is capped by the buffer";
    }

    static void trailerUsesTowInstant() {
        VehTrack tow = new VehTrack(), trailer = new VehTrack();
        for (int i = 0; i <= 10; i++) {
            tow.add(i * 100, i * 100, s(i, 0, 10, 0), false);
            trailer.add(i * 100, i * 100, s(i, 0, 10, 0), false);
        }
        tow.forceDelay(100);
        trailer.forceDelay(300);   // a different delay of its own
        float[] a = new float[VehTrack.POSE], b = new float[VehTrack.POSE];
        tow.pose(900, 0, a, null, null, 7);
        trailer.pose(900, 0, b, null, tow, 7);
        assert near(a[0], b[0], 1e-3f) : "the trailer draws at the tow's instant: " + a[0] + " vs " + b[0];
        trailer.pose(900, 0, b, null, tow, 8);   // the tow was not posed in frame 8: the trailer's own instant
        assert near(b[0], a[0], 1e-3f) : "time never runs backwards under a car (kept instant)";
        VehTrack alone = new VehTrack();
        for (int i = 0; i <= 10; i++) alone.add(i * 100, i * 100, s(i, 0, 10, 0), false);
        alone.forceDelay(300);
        alone.pose(900, 0, b, null, tow, 8);
        assert near(b[0], 6, 1e-3f) : "a stale tow is ignored: own instant 600 ms, got " + b[0];
    }

    static void turnAndSpeedShaping() {
        assert near(VehTrack.turn(1, 0.3f), 0.3f, 1e-5f) : "the rate is held for 0.6 s";
        float later = VehTrack.turn(1, 3f);
        assert later > 0.6f && later < 0.6f + 0.9f : "then fades over 0.9 s: " + later;
        assert near(VehTrack.turn(2.5f, 3f), (float) (Math.PI / 2), 1e-5f) : "total turn capped at 90 degrees";
        assert near(VehTrack.speedAt(10, 0, 1), 10, 1e-4f) : "no accel, no change before the limit";
        assert VehTrack.speedAt(10, 0, 2.9f) < 10 * 0.05f : "the stop tail after 2.5 s";
        assert VehTrack.speedAt(1, -10, 1) == 0 : "speed never goes negative";
    }

    static void predictionArcs() {
        // heading along +z at 10 m/s, turning: two samples 100 ms apart with the velocity rotated by 0.1 rad
        VehTrack t = new VehTrack();
        t.add(0, 0, s(0, 0, 0, 10), false);
        float vx = 10 * (float) Math.sin(0.1), vz = 10 * (float) Math.cos(0.1);
        t.add(100, 100, s(0.05f, 1, vx, vz), false);
        float[] out = new float[VehTrack.POSE];
        assert t.pose(1100 + 120, 0, out, null) : "predicting 1 s past the newest sample";
        float straightX = 0.05f + vx * 1f;
        assert out[0] > straightX + 0.5f : "the path bends with the measured turn: x " + out[0] + " vs straight " + straightX;
        float dist = (float) Math.hypot(out[0] - 0.05f, out[2] - 1);
        assert dist > 9 && dist < 10.01f : "about 10 m along the arc: " + dist;
    }

    static void selfTestFailLeavesVanilla() {
        RemoteVehicles.resetForTest();
        RemoteVehicles.probe();
        assert RemoteVehicles.probe == 1 : "the game build's fields resolve";
        RemoteVehicles.testOnline = true;
        RemoteVehicles.keyOn = true;
        RemoteVehicles.nowMs = 100_000;
        // a get that answers with other coordinates: the key goes off, intake never skips vanilla
        RemoteVehicles.selfTestGet = (vi, o, e) -> { o[0] = 1; o[1] = 2; o[2] = 3; return true; };
        RemoteVehicles.selfTest();
        assert RemoteVehicles.selfTest == -1 : "the self-test failed";
        assert !RemoteVehicles.active() : "inactive after a failed self-test";
        assert !RemoteVehicles.onIntake(new Object(), null, null, 0) : "add never skips vanilla";
        // the hooked get (our onPose) answers with the planted place: the self-test passes
        RemoteVehicles.resetForTest();
        RemoteVehicles.probe();
        RemoteVehicles.testOnline = true;
        RemoteVehicles.keyOn = true;
        RemoteVehicles.nowMs = 100_000;
        RemoteVehicles.selfTestGet = (vi, o, e) -> RemoteVehicles.onPose(vi, o, e, null);
        RemoteVehicles.selfTest();
        assert RemoteVehicles.selfTest == 1 : "the self-test passed through onPose";
        assert RemoteVehicles.active() : "active on an MP client with the key on";
        RemoteVehicles.testOnline = false;
        assert !RemoteVehicles.active() : "never active off an MP client";
    }
}
