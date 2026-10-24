package pzbutter;

/**
 * zeroFacingFix (Smooth Frames 3.6). {@code IsoGameCharacter.setForwardDirection(float, float)} throws "Forward Direction cannot be
 * zero length vector" when the game's own {@code Vector2.normalize} comes back under 1e-7 ({@code PZMath.equal(len, 0f)}). The call
 * that would throw is skipped whole (the character keeps its previous facing); every other call runs stock. Any thread: the predicate reads only its two arguments (stock's body, 0-52 of
 * the (FF) overload, touches only the character's own vector), so the skip is as safe on a loader or worker thread as on the game's.
 */
final class FacingFix {
    private FacingFix() {}

    static final Guard guard = new Guard("zeroFacingFix");

    /** Calls skipped since start (or the last resetForTest). Written under the class lock, read anywhere. */
    static volatile long kept;

    /** Lines printed so far, at most 3. Under the class lock. */
    private static int lines;

    /** Calls skipped at the last {@link #drain}. */
    private static long drained;

    static void init() {
        guard.on = Settings.flag("zeroFacingFix");
    }

    /**
     * Stock's condition, arithmetic in the same order: {@code Vector2.normalize} returns 1 when {@code |1 - l2| < 1e-5}, 0 for an
     * exact zero {@code l2}, else {@code sqrt(l2)}; the throw is {@code PZMath.equal(len, 0f)}, i.e. {@code |0 - len| < 1e-7}.
     * {@code Math.abs} and the game's {@code PZMath.abs} (value times sign) agree on every comparison made here (NaN fails both).
     */
    static boolean wouldThrow(float x, float y) {
        float l2 = x * x + y * y;
        if (Math.abs(1f - l2) < 1e-5f) return false;
        if (l2 == 0f) return true;
        float len = (float) Math.sqrt(l2);
        return Math.abs(0f - len) < 1e-7f;
    }

    /** True: skip the body. Off, or a call stock accepts: false (the body runs as stock). Any thread. */
    static boolean onSet(float x, float y) {
        if (!guard.on || !wouldThrow(x, y)) return false;
        note();
        return true;
    }

    /** The rare path: counts the skip and prints the first three callers. */
    private static synchronized void note() {
        kept++;
        if (lines < 3) {
            lines++;
            System.out.println("[PZButter] zeroFacingFix: zero-length facing kept (from " + caller() + ")");
        }
    }

    /** Skips since the last call (the per-minute zombies line). */
    static synchronized long drain() {
        long d = kept - drained;
        drained = kept;
        return d;
    }

    /** The first frame outside the game's character class and this mod: the code that asked for the facing. Only on the rare path. */
    private static String caller() {
        try {
            return StackWalker.getInstance().walk(s -> s
                .filter(f -> !f.getClassName().equals("zombie.characters.IsoGameCharacter") && !f.getClassName().startsWith("pzbutter."))
                .findFirst()
                .map(f -> f.getClassName() + "." + f.getMethodName() + ":" + f.getLineNumber())
                .orElse("unknown"));
        } catch (Throwable t) {
            return "unknown";
        }
    }

    static void resetForTest() {
        guard.on = false;
        kept = 0;
        lines = 0;
        drained = 0;
    }
}
