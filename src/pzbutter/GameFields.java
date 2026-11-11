package pzbutter;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

/**
 * Game int fields read at run time. {@code Core.tileScale} and {@code FBORenderChunk.PIXELS_PER_LEVEL} are plain fields on some game builds and
 * {@code static final} constants on others; a read written as {@code Core.tileScale} is compiled against whichever build the jar is built on,
 * and against a constant javac bakes the value into PZ Butter, which then keeps it on a build where it is a live field. The handles here are
 * bound by name when the class loads (a {@code static final} handle is a constant to the JIT, so a read costs what the field read did).
 */
final class GameFields {
    private GameFields() {}

    /** A getter for the named static int field, or null when the class has no such field. */
    static MethodHandle getter(Class<?> c, String name) {
        try {
            return MethodHandles.lookup().findStaticGetter(c, name, int.class);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /** The field's current value, or fallback when there is no such field. */
    static int read(MethodHandle h, int fallback) {
        if (h == null) return fallback;
        try {
            return (int) h.invokeExact();
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static final MethodHandle TILE_SCALE = getter(zombie.core.Core.class, "tileScale");
    private static final MethodHandle PIXELS_PER_LEVEL = getter(zombie.iso.fboRenderChunk.FBORenderChunk.class, "PIXELS_PER_LEVEL");

    /** {@code zombie.core.Core.tileScale}; 2 when the game has none. */
    static int tileScale() { return read(TILE_SCALE, 2); }

    /** {@code FBORenderChunk.PIXELS_PER_LEVEL}; 192 when the game has none. */
    static int pixelsPerLevel() { return read(PIXELS_PER_LEVEL, 192); }
}
