package pzbutter;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Vehicle fixes (Plan 10a Task 1 and 4: gloveBoxFix, vehicleAnimIdleSkip). A vehicle whose GloveBox part has no item
 * container no longer throws when adding a key; parked vehicle parts skip unchanged animation updates.
 */
final class VehicleFixes {
    private VehicleFixes() {}

    static final Guard gloveGuard = new Guard("gloveBoxFix");
    static final Guard animGuard = new Guard("vehicleAnimIdleSkip");

    /** Calls skipped since start (or the last resetForTest). */
    static volatile long gloveSkipped;

    /** Parked-part checks and skips since start (or the last resetForTest). */
    static volatile long animChecked;
    static volatile long animSkipped;

    /** Set while inside BaseVehicle.updateAnimationPlayer with a non-null part. */
    public static volatile boolean inPart;

    /** Identity-keyed state for the parked-part skip decision. */
    static final IdentityHashMap<Object, AnimState> animStates = new IdentityHashMap<>();

    static long lastReapFrame = 0;
    static boolean reapedOnce = false;

    static final class AnimState {
        Object track;
        Object skin;
        int timeBits;
        int blendBits;
        int speedBits;
        int stable;
        long seen;
    }

    static void init() {
        gloveGuard.on = Settings.flag("gloveBoxFix");
        animGuard.on = Settings.flag("vehicleAnimIdleSkip");
    }

    /** True iff the part exists but has no container: the only case stock would throw. Pure. */
    static boolean skipGloveBox(boolean partExists, boolean containerNull) {
        return partExists && containerNull;
    }

    /**
     * Pure state step over animStates. player is the AnimationPlayer. track/skin must be identity-compared. Returns true
     * when the update can be skipped (third and later consecutive identical observations).
     */
    static boolean decide(Object player, Object track, Object skin, float time, float blend, float speed, long frame) {
        AnimState st = animStates.get(player);
        int tb = Float.floatToIntBits(time);
        int bb = Float.floatToIntBits(blend);
        int sb = Float.floatToIntBits(speed);
        if (st == null) {
            st = new AnimState();
            st.track = track;
            st.skin = skin;
            st.timeBits = tb;
            st.blendBits = bb;
            st.speedBits = sb;
            st.stable = 0;
            st.seen = frame;
            animStates.put(player, st);
        } else if (st.track == track && st.skin == skin
                && st.timeBits == tb && st.blendBits == bb && st.speedBits == sb) {
            if (st.stable < 2) st.stable++;
            st.seen = frame;
        } else {
            st.track = track;
            st.skin = skin;
            st.timeBits = tb;
            st.blendBits = bb;
            st.speedBits = sb;
            st.stable = 0;
            st.seen = frame;
        }
        // reap states older than 1800 frames when more than 1800 frames passed since the last reap
        if (!reapedOnce || frame - lastReapFrame > 1800) {
            long f = frame;
            animStates.entrySet().removeIf(e -> f - e.getValue().seen > 1800);
            lastReapFrame = frame;
            reapedOnce = true;
        }
        return st.stable >= 2;
    }

    static void removeState(Object player) {
        animStates.remove(player);
    }

    static void resetForTest() {
        gloveGuard.on = false;
        animGuard.on = false;
        gloveSkipped = 0;
        animChecked = 0;
        animSkipped = 0;
        inPart = false;
        animStates.clear();
        lastReapFrame = 0;
        reapedOnce = false;
    }
}