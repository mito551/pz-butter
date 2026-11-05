package pzbutter;

import java.util.Arrays;

/**
 * One remote vehicle's track for Plan 15 Task 8 (covers SmoothRide): a sample buffer drawn at an adaptive delay, a
 * guarded Hermite curve between samples, arc prediction past the newest one, and a critically damped correction that
 * absorbs every jump. Pure: no game types, main thread only. Every constant is SmoothRide's balanced preset
 * (docs/specs/SmoothRide.md §3.3-3.7).
 *
 * A sample and the drawn pose use the game's own pose buffer layout (VehicleInterpolation.interpolationDataGet):
 * [0..2] x y z, [3..6] qx qy qz qw, [7..9] vx vy vz, [10] wheel count, then per wheel steering, rotation, skid,
 * suspension; a sample also carries engine speed and throttle at {@link #ENGINE}.
 */
public final class VehTrack {
    public enum Insert { KEPT, TELEPORT, IDLE_RESTART, LONG_GAP, REFUSED }
    public enum Mode { STARTING, INTERPOLATING, PREDICTING, HOLDING }

    public static final int WHEELS_MAX = 8;
    public static final int POSE = 11;
    public static final int ENGINE = POSE + 4 * WHEELS_MAX;
    public static final int LEN = ENGINE + 2;

    static final int BUFFER_CAPACITY = 128, LAT_RING = 64, GAP_RING = 32, PRUNE_KEEP_MIN = 3;
    static final long PRUNE_AGE_MS = 1000;
    static final float TELEPORT_DISTANCE = 30f, TELEPORT_SPEED = 70f;
    static final float ROT_TELEPORT_DEG = 60f, ROT_PER_SEC_DEG = 8f;
    static final long ROT_GAP_MS = 500;
    static final float MOVING_SPEED = 0.5f;
    static final float PREDICT_STEP_S = 0.02f, MAX_PREDICT_S = 2.5f, STOP_TAIL_S = 0.4f;
    static final long RATE_LOOKBACK_MS = 600, RATE_PREFER_MS = 90;
    static final float VEL_HEADING_SPEED = 2f, ACCEL_SPEED = 1f, MAX_YAW_RATE = 2.5f, MAX_ACCEL = 10f;
    static final float TURN_HOLD_S = 0.6f, TURN_FADE_S = 0.9f, MAX_TURN = (float) (Math.PI / 2);
    static final float ACCEL_FADE_S = 0.35f, TAIL_EXP_S = 0.1f, VERTICAL_CLAMP = 0.3f;
    static final long IDLE_PARKED_MS = 1500, IDLE_MOVING_MS = 10_000;
    static final float CORRECTION_MS = 120f, OMEGA_FLOOR = 1.2f, SPEED_CAP_FRAC = 0.35f, SPEED_CAP_CONST = 2f;
    static final float CORR_VEL_CAP = 30f, SNAP_DISTANCE = 50f, ROT_RESET_DEG = 60f, ROT_CORR_RATE = 1.2f;
    static final float COUNT_STEP_M = 0.06f, COUNT_STEP_DEG = 1.5f;
    static final float MIN_DELAY = 20f, MAX_DELAY = 700f, INITIAL_DELAY = 120f;
    static final float LAT_PERC = 0.85f, GAP_MULT = 0.6f, MARGIN_MS = 10f, RAW_GAP_FALLBACK = 50f;
    static final float RATE_TIME_MS = 700f, SHRINK_RATE = 0.06f, GROW_RATE = 0.15f, RATE_CHANGE_PER_S = 0.3f;
    static final long HITCH_SILENCE_MS = 250, BURST_MS = 60, STALE_MS = 40;
    static final float HITCH_FACTOR = 4f;
    static final float HERMITE_MAX_SPAN_MS = 3000f, HERMITE_SPEED_LIMIT = 120f, HERMITE_DEV_M = 0.6f, HERMITE_DEV_FRAC = 0.1f;
    static final long WINDOW_MS = (long) ((MAX_PREDICT_S + STOP_TAIL_S) * 1000);
    static final long BRIDGE_MS = Math.max(1000, WINDOW_MS + 500);

    private static final class Sample {
        long time, arrival;
        final float[] d = new float[LEN];
        boolean synthetic;
    }

    // ---- buffer (sorted by time) ----
    private final Sample[] buf = new Sample[BUFFER_CAPACITY];
    private int count;

    // ---- arrival bookkeeping ----
    private final float[] latR = new float[LAT_RING], gapR = new float[GAP_RING];
    private final float[] scratch = new float[LAT_RING];
    private int latN, latPos, gapN, gapPos;
    private boolean hasArrival;
    private long lastArrival, lastStamp, burstUntil;
    private float refLateness;

    // ---- delay ----
    private float delay = INITIAL_DELAY, delayTarget = INITIAL_DELAY, delayRate;

    // ---- render clock ----
    private long lastRender;
    private boolean hasLastRender;
    private long posedFrame = Long.MIN_VALUE;
    private boolean idle = true;
    /** RemoteVehicles' clock for this track's frame delta. */
    long lastPoseNow;

    // ---- correction: drawn = raw + offset, rotation = corrQ * raw ----
    private float offX, offY, offZ, velX, velY, velZ;
    private final float[] corrQ = {0, 0, 0, 1};

    // ---- last frame's raw pose, for folding ----
    private boolean prevValid;
    private final float[] prevPos = new float[3], prevVel = new float[3], prevQ = new float[4];
    private Mode prevMode = Mode.STARTING;
    private Sample prevLeft, prevRight;

    // ---- scratch: no allocation per frame ----
    private final float[] raw = new float[LEN];
    private final float[] held = new float[LEN];
    private Sample heldFor;
    private final float[] q0 = new float[4], q1 = new float[4], qt = new float[4];

    // ---- statistics (read and reset by the minute line) ----
    int framesInterp, framesPred, framesHold, framesStart, snaps, teleports, corrections, longGaps, idleRestarts, stale, hitches;

    // ---- intake ----

    public Insert add(long senderMs, long arrivalMs, float[] sample, boolean synthetic) {
        if (sample == null || sample.length < POSE || !finite(sample, sample.length)) return Insert.REFUSED;
        if (synthetic) return Insert.REFUSED;   // synthetic samples only enter through seed()
        Insert result;
        if (count == 0) {
            result = Insert.IDLE_RESTART;
        } else {
            Sample last = buf[count - 1];
            long dtMs = senderMs - last.time;
            if (dtMs < 0) {
                result = Insert.KEPT;   // a late packet is history, placed in order
            } else if (teleport(last.d, sample, dtMs)) {
                result = Insert.TELEPORT;
            } else if (idle) {
                result = Insert.IDLE_RESTART;   // vanilla drew the car meanwhile: start again from a seed
            } else if (dtMs > BRIDGE_MS) {
                result = Insert.LONG_GAP;
            } else {
                result = Insert.KEPT;
            }
        }
        switch (result) {
            case TELEPORT -> { count = 0; teleports++; resetCorrection(); prevValid = false; }
            case IDLE_RESTART -> { count = 0; if (idle && hasLastRender) idleRestarts++; }
            case LONG_GAP -> { count = 0; longGaps++; }   // the correction survives: the next data blends in
            default -> { }
        }
        Sample s = new Sample();
        s.time = senderMs;
        s.arrival = arrivalMs;
        System.arraycopy(sample, 0, s.d, 0, Math.min(sample.length, LEN));
        insert(s);
        measure(senderMs, arrivalMs);
        idle = false;
        prune();
        return result;
    }

    /** The drawn pose as a synthetic sample at the current render instant: accepted only older than and near the newest packet. */
    public boolean seed(float[] pose) {
        if (pose == null || pose.length < POSE || !finite(pose, pose.length) || count == 0) return false;
        Sample newest = buf[count - 1];
        long at = hasLastRender ? lastRender : newest.time - (long) delay;
        if (at >= newest.time) return false;
        if (dist(newest.d, pose) > TELEPORT_DISTANCE) return false;
        Sample s = new Sample();
        s.time = at;
        s.arrival = newest.arrival;
        s.synthetic = true;
        System.arraycopy(pose, 0, s.d, 0, Math.min(pose.length, LEN));
        insert(s);
        return true;
    }

    private boolean teleport(float[] last, float[] s, long dtMs) {
        float d = dist(last, s);
        if (d > TELEPORT_DISTANCE && (dtMs == 0 || d / (dtMs / 1000f) > TELEPORT_SPEED)) return true;
        if (dtMs <= ROT_GAP_MS) {
            float rot = angleDeg(last[3], last[4], last[5], last[6], s[3], s[4], s[5], s[6]);
            return rot > Math.max(ROT_TELEPORT_DEG, ROT_PER_SEC_DEG * (dtMs / 1000f));
        }
        return false;
    }

    private void insert(Sample s) {
        for (int i = 0; i < count; i++) {
            if (buf[i].time == s.time) {
                if (s.synthetic && !buf[i].synthetic) return;   // a real sample is never replaced by a seed
                buf[i] = s;
                return;
            }
        }
        if (count == BUFFER_CAPACITY) {
            if (s.time <= buf[0].time) return;
            System.arraycopy(buf, 1, buf, 0, count - 1);
            count--;
        }
        int pos = count;
        while (pos > 0 && buf[pos - 1].time > s.time) pos--;
        System.arraycopy(buf, pos, buf, pos + 1, count - pos);
        buf[pos] = s;
        count++;
    }

    private void prune() {
        if (!hasLastRender) return;
        long cutoff = lastRender - PRUNE_AGE_MS;
        int drop = 0;
        while (count - drop > PRUNE_KEEP_MIN && buf[drop].time < cutoff) drop++;
        if (drop == 0) return;
        System.arraycopy(buf, drop, buf, 0, count - drop);
        for (int i = count - drop; i < count; i++) buf[i] = null;
        count -= drop;
    }

    /** Lateness and send-gap rings, the hitch rule, and the delay target (once per real sample). */
    private void measure(long senderMs, long arrivalMs) {
        float lateness = arrivalMs - senderMs;
        if (hasArrival) {
            long silence = arrivalMs - lastArrival;
            long stampGap = senderMs - lastStamp;
            if (stampGap > 0 && stampGap < 1000) {
                gapR[gapPos++ % GAP_RING] = stampGap;
                if (gapN < GAP_RING) gapN++;
            }
            if (silence > Math.max(HITCH_SILENCE_MS, HITCH_FACTOR * medianGap())) {
                hitches++;
                refLateness = latN >= 4 ? percentile(latR, latN, 0.9f) : lateness;
                burstUntil = arrivalMs + BURST_MS;
            }
        }
        boolean isStale = arrivalMs <= burstUntil && lateness > refLateness + STALE_MS;
        if (isStale) {
            stale++;
        } else {
            latR[latPos++ % LAT_RING] = lateness;
            if (latN < LAT_RING) latN++;
        }
        hasArrival = true;
        lastArrival = arrivalMs;
        if (senderMs > lastStamp) lastStamp = senderMs;
        float target = latN < 4 ? INITIAL_DELAY : percentile(latR, latN, LAT_PERC) + GAP_MULT * medianGap() + MARGIN_MS;
        delayTarget = Math.max(MIN_DELAY, Math.min(MAX_DELAY, target));
    }

    private float medianGap() {
        return gapN < 4 ? RAW_GAP_FALLBACK : percentile(gapR, gapN, 0.5f);
    }

    /** Nearest rank on a sorted copy (the rings are at most 64 long). */
    private float percentile(float[] ring, int n, float q) {
        System.arraycopy(ring, 0, scratch, 0, n);
        Arrays.sort(scratch, 0, n);
        int idx = Math.min(n - 1, Math.max(0, (int) Math.ceil(q * n) - 1));
        return scratch[idx];
    }

    // ---- the drawn pose ----

    public boolean pose(long serverNowMs, long frameDtMs, float[] out, float[] engine) {
        return pose(serverNowMs, frameDtMs, out, engine, null, Long.MIN_VALUE);
    }

    /**
     * Writes the drawn pose (game layout) and engine speed / throttle. {@code tow}: the towing car's track; when it
     * was posed in this same {@code frame}, this car draws at its render instant (a trailer stays on its hitch).
     * Returns false to hand the car back to vanilla.
     */
    public boolean pose(long serverNowMs, long frameDtMs, float[] out, float[] engine, VehTrack tow, long frame) {
        if (out == null || out.length < POSE || count == 0 || idle) return false;
        boolean discard = frameDtMs < 0 || frameDtMs > 500;
        float dt = Math.max(0, Math.min(250, frameDtMs)) / 1000f;
        stepDelay(dt);

        long render = serverNowMs - (long) delay;
        if (tow != null && tow != this && tow.posedFrame == frame && frame != Long.MIN_VALUE && tow.hasLastRender) render = tow.lastRender;
        if (hasLastRender && render < lastRender && lastRender - render < 1000) render = lastRender;
        lastRender = render;
        hasLastRender = true;
        posedFrame = frame;

        Sample newest = buf[count - 1];
        int leftIdx = -1;
        while (leftIdx + 1 < count && buf[leftIdx + 1].time <= render) leftIdx++;
        Mode mode;
        Sample left, right = null;
        long age = render - newest.time;
        if (leftIdx < 0) { mode = Mode.STARTING; left = buf[0]; }
        else if (leftIdx == count - 1) { mode = age <= WINDOW_MS ? Mode.PREDICTING : Mode.HOLDING; left = newest; }
        else { mode = Mode.INTERPOLATING; left = buf[leftIdx]; right = buf[leftIdx + 1]; }

        if (mode == Mode.HOLDING) {
            long handBack = horizSpeed(newest.d) < MOVING_SPEED ? Math.max(IDLE_PARKED_MS, WINDOW_MS) : Math.max(IDLE_MOVING_MS, WINDOW_MS);
            if (age >= handBack) {
                idle = true;
                resetCorrection();
                prevValid = false;
                heldFor = null;
                return false;
            }
        }

        switch (mode) {
            case STARTING -> System.arraycopy(left.d, 0, raw, 0, LEN);
            case INTERPOLATING -> interpolate(left, right, render);
            case PREDICTING -> predict(newest, age / 1000f, raw);
            case HOLDING -> {
                if (heldFor != newest) { predict(newest, WINDOW_MS / 1000f, held); heldFor = newest; }
                System.arraycopy(held, 0, raw, 0, LEN);
            }
        }

        if (discard) {
            resetCorrection();
        } else {
            // the correction is first advanced to this instant, then the jump at this instant is folded into it:
            // the drawn car continues exactly where last frame's drawn car was heading
            if (dt > 0) relax(dt);
            if (prevValid && !natural(mode, left, right)) fold(dt);
        }
        write(out, engine);

        System.arraycopy(raw, 0, prevPos, 0, 3);
        System.arraycopy(raw, 7, prevVel, 0, 3);
        System.arraycopy(raw, 3, prevQ, 0, 4);
        prevValid = true;
        prevMode = mode;
        prevLeft = left;
        prevRight = right;
        switch (mode) {
            case INTERPOLATING -> framesInterp++;
            case PREDICTING -> framesPred++;
            case HOLDING -> framesHold++;
            case STARTING -> framesStart++;
        }
        return true;
    }

    /** Rate-limited move toward the target: 700 ms time constant, -0.06..+0.15 ms per ms, rate change 0.3 per s. */
    private void stepDelay(float dt) {
        float desired = (delayTarget - delay) / RATE_TIME_MS;
        desired = Math.max(-SHRINK_RATE, Math.min(GROW_RATE, desired));
        float maxChange = RATE_CHANGE_PER_S * dt;
        desired = Math.max(delayRate - maxChange, Math.min(delayRate + maxChange, desired));
        delayRate = desired;
        delay += delayRate * dt * 1000f;
        if (delay <= MIN_DELAY) { delay = MIN_DELAY; delayRate = 0; }
        if (delay >= MAX_DELAY) { delay = MAX_DELAY; delayRate = 0; }
    }

    private void interpolate(Sample l, Sample r, long render) {
        float spanMs = r.time - l.time;
        float span = spanMs / 1000f;
        float t = spanMs > 0 ? Math.max(0, Math.min(1, (render - l.time) / spanMs)) : 0;
        float[] a = l.d, b = r.d;
        boolean curve = spanMs > 0 && spanMs < HERMITE_MAX_SPAN_MS
            && horizSpeed(a) < HERMITE_SPEED_LIMIT && horizSpeed(b) < HERMITE_SPEED_LIMIT;
        if (curve) {
            float chord = (float) Math.hypot(b[0] - a[0], b[2] - a[2]);
            float limit = Math.max(HERMITE_DEV_M, HERMITE_DEV_FRAC * chord);
            for (int k = 1; k <= 7 && curve; k++) {
                float u = k / 8f;
                float dx = hermite(a[0], a[7] * span, b[0], b[7] * span, u) - (a[0] + (b[0] - a[0]) * u);
                float dz = hermite(a[2], a[9] * span, b[2], b[9] * span, u) - (a[2] + (b[2] - a[2]) * u);
                if (Math.hypot(dx, dz) > limit) curve = false;
            }
        }
        if (curve) {
            raw[0] = hermite(a[0], a[7] * span, b[0], b[7] * span, t);
            raw[2] = hermite(a[2], a[9] * span, b[2], b[9] * span, t);
            raw[7] = hermiteD(a[0], a[7] * span, b[0], b[7] * span, t) / span;
            raw[9] = hermiteD(a[2], a[9] * span, b[2], b[9] * span, t) / span;
        } else {
            raw[0] = a[0] + (b[0] - a[0]) * t;
            raw[2] = a[2] + (b[2] - a[2]) * t;
            raw[7] = span > 0 ? (b[0] - a[0]) / span : a[7];
            raw[9] = span > 0 ? (b[2] - a[2]) / span : a[9];
        }
        raw[1] = a[1] + (b[1] - a[1]) * t;
        raw[8] = a[8] + (b[8] - a[8]) * t;
        q0[0] = a[3]; q0[1] = a[4]; q0[2] = a[5]; q0[3] = a[6];
        q1[0] = b[3]; q1[1] = b[4]; q1[2] = b[5]; q1[3] = b[6];
        slerp(q0, q1, t, qt);
        raw[3] = qt[0]; raw[4] = qt[1]; raw[5] = qt[2]; raw[6] = qt[3];
        int wheels = Math.min(WHEELS_MAX, Math.min((int) a[10], (int) b[10]));
        raw[10] = Math.max(0, wheels);
        for (int i = POSE; i < POSE + 4 * Math.max(0, wheels); i++) raw[i] = a[i] + (b[i] - a[i]) * t;
        raw[ENGINE] = a[ENGINE] + (b[ENGINE] - a[ENGINE]) * t;
        raw[ENGINE + 1] = a[ENGINE + 1] + (b[ENGINE + 1] - a[ENGINE + 1]) * t;
    }

    /** Dead reckoning from the newest sample: 20 ms arc steps, turn held 0.6 s then faded, speed with a faded accel and the stop tail. */
    private void predict(Sample n, float ageS, float[] dst) {
        System.arraycopy(n.d, 0, dst, 0, LEN);
        float[] d = n.d;
        float speed0 = horizSpeed(d);
        float bodyH = bodyHeading(d);
        float velH = (float) Math.atan2(d[7], d[9]);
        float h0 = speed0 > MOVING_SPEED ? velH : bodyH;
        float yawRate = 0, accel = 0;
        Sample p = rateSample(n);
        if (p != null) {
            float span = (n.time - p.time) / 1000f;
            float spP = horizSpeed(p.d);
            yawRate = wrap(bodyH - bodyHeading(p.d)) / span;
            if (speed0 > VEL_HEADING_SPEED && spP > VEL_HEADING_SPEED) yawRate = wrap(velH - (float) Math.atan2(p.d[7], p.d[9])) / span;
            if (speed0 > ACCEL_SPEED) accel = (speed0 - spP) / span;
        }
        yawRate = Math.max(-MAX_YAW_RATE, Math.min(MAX_YAW_RATE, yawRate));
        accel = Math.max(-MAX_ACCEL, Math.min(MAX_ACCEL, accel));

        float x = d[0], z = d[2];
        for (float t = 0; t < ageS; ) {
            float h = Math.min(PREDICT_STEP_S, ageS - t);
            float mid = t + h / 2;
            float heading = h0 + turn(yawRate, mid);
            float sp = speedAt(speed0, accel, mid);
            x += sp * h * (float) Math.sin(heading);
            z += sp * h * (float) Math.cos(heading);
            t += h;
        }
        float turnT = turn(yawRate, ageS);
        float headingT = h0 + turnT;
        float spT = speedAt(speed0, accel, ageS);
        float dy = d[8] * ageS;
        boolean capped = Math.abs(dy) > VERTICAL_CLAMP;
        dy = Math.max(-VERTICAL_CLAMP, Math.min(VERTICAL_CLAMP, dy));
        dst[0] = x;
        dst[1] = d[1] + dy;
        dst[2] = z;
        dst[7] = spT * (float) Math.sin(headingT);
        dst[8] = capped || ageS > MAX_PREDICT_S ? 0 : d[8];
        dst[9] = spT * (float) Math.cos(headingT);
        // the turn about world up applied to the newest rotation; heading and rotation come from the same forward-axis
        // formula (bodyHeading), so the sign follows from it
        float s = (float) Math.sin(turnT / 2), c = (float) Math.cos(turnT / 2);
        float qx = d[3], qy = d[4], qz = d[5], qw = d[6];
        dst[3] = c * qx + s * qz;
        dst[4] = c * qy + s * qw;
        dst[5] = c * qz - s * qx;
        dst[6] = c * qw - s * qy;
    }

    /** The sample rates come from: within 600 ms of the newest, preferring the newest one at least 90 ms older. */
    private Sample rateSample(Sample n) {
        Sample fallback = null;
        for (int i = count - 1; i >= 0; i--) {
            Sample s = buf[i];
            if (s == n || s.synthetic) continue;
            long diff = n.time - s.time;
            if (diff <= 0) continue;
            if (diff > RATE_LOOKBACK_MS) break;
            if (diff >= RATE_PREFER_MS) return s;
            fallback = s;
        }
        return fallback;
    }

    static float turn(float rate, float t) {
        float a = t <= TURN_HOLD_S ? rate * t
            : rate * (TURN_HOLD_S + TURN_FADE_S * (1 - (float) Math.exp(-(t - TURN_HOLD_S) / TURN_FADE_S)));
        return Math.max(-MAX_TURN, Math.min(MAX_TURN, a));
    }

    static float speedAt(float v0, float accel, float t) {
        float v = Math.max(0, v0 + accel * ACCEL_FADE_S * (1 - (float) Math.exp(-t / ACCEL_FADE_S)));
        if (t > MAX_PREDICT_S) v *= (float) Math.exp(-(t - MAX_PREDICT_S) / TAIL_EXP_S);
        return v;
    }

    // ---- correction ----

    /** Same curve as last frame: same mode and bracket, or crossing one snapshot from interpolation. */
    private boolean natural(Mode mode, Sample left, Sample right) {
        if (mode == prevMode && left == prevLeft && right == prevRight) return true;
        return prevMode == Mode.INTERPOLATING && (mode == Mode.INTERPOLATING || mode == Mode.PREDICTING) && left == prevRight;
    }

    /** Folds the jump between last frame's raw pose (advanced to now) and this frame's into the correction. */
    private void fold(float dt) {
        float jx = raw[0] - (prevPos[0] + prevVel[0] * dt);
        float jy = raw[1] - (prevPos[1] + prevVel[1] * dt);
        float jz = raw[2] - (prevPos[2] + prevVel[2] * dt);
        float jump = len(jx, jy, jz);
        if (jump > SNAP_DISTANCE || len(offX - jx, offY - jy, offZ - jz) > SNAP_DISTANCE) {
            snaps++;
            resetCorrection();
            return;
        }
        offX -= jx; offY -= jy; offZ -= jz;
        velX -= raw[7] - prevVel[0];
        velY -= raw[8] - prevVel[1];
        velZ -= raw[9] - prevVel[2];
        // rotation: corrQ' * raw = corrQ * prevRaw  ->  corrQ' = corrQ * prevRaw * conj(raw)
        q1[0] = -raw[3]; q1[1] = -raw[4]; q1[2] = -raw[5]; q1[3] = raw[6];
        mul(prevQ, q1, qt);
        float rotJump = angleOf(qt);
        if (rotJump > ROT_RESET_DEG) {
            corrQ[0] = 0; corrQ[1] = 0; corrQ[2] = 0; corrQ[3] = 1;
        } else {
            mul(corrQ, qt, q0);
            System.arraycopy(q0, 0, corrQ, 0, 4);
            normalize(corrQ);
        }
        if (jump > COUNT_STEP_M || rotJump > COUNT_STEP_DEG) corrections++;
    }

    /** Exact critically damped step; frequency 2000/120 ms, speed-capped, floor 1.2/s; the rotation eases toward identity. */
    private void relax(float dt) {
        float mag = len(offX, offY, offZ);
        float omega = 2000f / CORRECTION_MS;
        if (mag > 1e-6f) {
            float cap = SPEED_CAP_FRAC * (float) Math.hypot(raw[7], raw[9]) + SPEED_CAP_CONST;
            omega = Math.min(omega, cap * (float) Math.E / mag);   // a spring released from rest peaks at omega*mag/e
        }
        omega = Math.max(OMEGA_FLOOR, omega);
        float e = (float) Math.exp(-omega * dt);
        float kx = velX + omega * offX, ky = velY + omega * offY, kz = velZ + omega * offZ;
        offX = (offX + kx * dt) * e;
        offY = (offY + ky * dt) * e;
        offZ = (offZ + kz * dt) * e;
        velX = kx * e - omega * offX;
        velY = ky * e - omega * offY;
        velZ = kz * e - omega * offZ;
        float v = len(velX, velY, velZ);
        if (v > CORR_VEL_CAP) { float sc = CORR_VEL_CAP / v; velX *= sc; velY *= sc; velZ *= sc; }

        // rotation: shrink the correction's angle along its own axis, at most 1.2 rad/s
        if (corrQ[3] < 0) for (int i = 0; i < 4; i++) corrQ[i] = -corrQ[i];
        float angle = 2 * (float) Math.acos(Math.min(1, corrQ[3]));
        float sinHalf = (float) Math.sin(angle / 2);
        if (angle > 1e-6f && sinHalf > 1e-6f) {
            float next = angle - Math.min(angle * (1 - e), ROT_CORR_RATE * dt);
            float sc = (float) Math.sin(next / 2) / sinHalf;
            corrQ[0] *= sc; corrQ[1] *= sc; corrQ[2] *= sc;
            corrQ[3] = (float) Math.cos(next / 2);
        }
    }

    private void resetCorrection() {
        offX = offY = offZ = velX = velY = velZ = 0;
        corrQ[0] = 0; corrQ[1] = 0; corrQ[2] = 0; corrQ[3] = 1;
    }

    private void write(float[] out, float[] engine) {
        out[0] = raw[0] + offX;
        out[1] = raw[1] + offY;
        out[2] = raw[2] + offZ;
        q1[0] = raw[3]; q1[1] = raw[4]; q1[2] = raw[5]; q1[3] = raw[6];
        mul(corrQ, q1, qt);
        normalize(qt);
        if (out.length > 6) { out[3] = qt[0]; out[4] = qt[1]; out[5] = qt[2]; out[6] = qt[3]; }
        if (out.length > 9) { out[7] = raw[7] + velX; out[8] = raw[8] + velY; out[9] = raw[9] + velZ; }
        int wheels = Math.max(0, Math.min((int) raw[10], Math.min(WHEELS_MAX, (out.length - POSE) / 4)));
        out[10] = wheels;
        System.arraycopy(raw, POSE, out, POSE, 4 * wheels);
        if (engine != null && engine.length >= 2) { engine[0] = raw[ENGINE]; engine[1] = raw[ENGINE + 1]; }
    }

    /** clear(): samples and correction go; the rings, delay and rate describe the link, so they stay. */
    public void clear() {
        count = 0;
        Arrays.fill(buf, null);
        resetCorrection();
        prevValid = false;
        prevLeft = prevRight = null;
        heldFor = null;
        idle = true;
    }

    // ---- accessors ----

    public float delayMs() { return delay; }
    float delayRate() { return delayRate; }
    float delayTarget() { return delayTarget; }
    void forceDelay(float ms) { delay = delayTarget = ms; delayRate = 0; }
    int sampleCount() { return count; }
    int latenessCount() { return latN; }
    boolean idle() { return idle; }
    boolean hasArrival() { return hasArrival; }
    long lastArrival() { return lastArrival; }
    float correctionX() { return offX; }
    float correctionMag() { return len(offX, offY, offZ); }

    void resetStats() {
        framesInterp = framesPred = framesHold = framesStart = snaps = teleports = corrections = longGaps = idleRestarts = stale = hitches = 0;
    }

    // ---- math ----

    static float[] newSample() {
        float[] s = new float[LEN];
        s[6] = 1;
        return s;
    }

    private static boolean finite(float[] a, int n) {
        for (int i = 0; i < Math.min(n, LEN); i++) if (!Float.isFinite(a[i])) return false;
        return true;
    }
    private static float dist(float[] a, float[] b) { return len(a[0] - b[0], a[1] - b[1], a[2] - b[2]); }
    private static float len(float x, float y, float z) { return (float) Math.sqrt(x * x + y * y + z * z); }
    private static float horizSpeed(float[] d) { return (float) Math.hypot(d[7], d[9]); }
    private static float wrap(float a) {
        while (a > Math.PI) a -= (float) (2 * Math.PI);
        while (a < -Math.PI) a += (float) (2 * Math.PI);
        return a;
    }
    /** Heading of the body's forward axis (local +z) in the x/z plane, as atan2(x, z). */
    private static float bodyHeading(float[] d) {
        float qx = d[3], qy = d[4], qz = d[5], qw = d[6];
        return (float) Math.atan2(2 * (qx * qz + qw * qy), 1 - 2 * (qx * qx + qy * qy));
    }
    static float hermite(float p0, float m0, float p1, float m1, float t) {
        float t2 = t * t, t3 = t2 * t;
        return (2 * t3 - 3 * t2 + 1) * p0 + (t3 - 2 * t2 + t) * m0 + (-2 * t3 + 3 * t2) * p1 + (t3 - t2) * m1;
    }
    static float hermiteD(float p0, float m0, float p1, float m1, float t) {
        float t2 = t * t;
        return (6 * t2 - 6 * t) * p0 + (3 * t2 - 4 * t + 1) * m0 + (-6 * t2 + 6 * t) * p1 + (3 * t2 - 2 * t) * m1;
    }
    private static float angleDeg(float ax, float ay, float az, float aw, float bx, float by, float bz, float bw) {
        float dot = Math.abs(ax * bx + ay * by + az * bz + aw * bw);
        float na = len(ax, ay, az) * len(ax, ay, az) + aw * aw, nb = len(bx, by, bz) * len(bx, by, bz) + bw * bw;
        if (na <= 0 || nb <= 0) return 0;
        dot /= (float) Math.sqrt(na * nb);
        return (float) Math.toDegrees(2 * Math.acos(Math.min(1, dot)));
    }
    private static float angleOf(float[] q) {
        float n = (float) Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
        if (n <= 0) return 0;
        return (float) Math.toDegrees(2 * Math.acos(Math.min(1, Math.abs(q[3]) / n)));
    }
    /** r = a * b, quaternions as x y z w. r may not alias a or b. */
    private static void mul(float[] a, float[] b, float[] r) {
        r[0] = a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1];
        r[1] = a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0];
        r[2] = a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3];
        r[3] = a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2];
    }
    private static void normalize(float[] q) {
        float n = (float) Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
        if (n > 0) for (int i = 0; i < 4; i++) q[i] /= n; else { q[0] = q[1] = q[2] = 0; q[3] = 1; }
    }
    /** Shortest-path slerp of normalised quaternions into r. */
    private static void slerp(float[] a, float[] b, float t, float[] r) {
        normalize(a);
        normalize(b);
        float dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3];
        float sign = 1;
        if (dot < 0) { dot = -dot; sign = -1; }
        float s0, s1;
        if (dot > 0.9995f) { s0 = 1 - t; s1 = t; }
        else {
            float th = (float) Math.acos(dot), st = (float) Math.sin(th);
            s0 = (float) Math.sin((1 - t) * th) / st;
            s1 = (float) Math.sin(t * th) / st;
        }
        for (int i = 0; i < 4; i++) r[i] = s0 * a[i] + s1 * sign * b[i];
        normalize(r);
    }
}
