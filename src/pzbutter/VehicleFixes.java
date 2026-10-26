package pzbutter;

/**
 * Vehicle fixes (Plan 10a Task 1: gloveBoxFix). A vehicle whose GloveBox part has no item container no longer throws
 * when adding a key, by skipping the whole method body in that case (the key is not added; the container is missing anyway).
 */
final class VehicleFixes {
    private VehicleFixes() {}

    static final Guard gloveGuard = new Guard("gloveBoxFix");

    /** Calls skipped since start (or the last resetForTest). */
    static volatile long gloveSkipped;

    static void init() {
        gloveGuard.on = Settings.flag("gloveBoxFix");
    }

    /** True iff the part exists but has no container: the only case stock would throw. Pure. */
    static boolean skipGloveBox(boolean partExists, boolean containerNull) {
        return partExists && containerNull;
    }

    static void resetForTest() {
        gloveGuard.on = false;
        gloveSkipped = 0;
    }
}