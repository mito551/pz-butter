package pzbutter;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.util.ArrayDeque;
import java.util.function.Consumer;
import java.util.function.Predicate;

import zombie.core.textures.SmartTexture;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureCombiner;

/**
 * smartTextureRelease (Character Texture VRAM Leak Fix): {@code SmartTexture.calculate} stores a new composite in {@code result} and
 * never gives the old one back, so a character's or item's texture stays in video memory until the model goes. Before the game's
 * calculate runs, the old result goes back to the combiner's pool, unless it is already in its pool's free list (a second release of
 * one texture would let two composites share it). Runs wherever calculate runs, under the SmartTexture monitor, so the read, the null
 * and the release are one step against another calculate of the same object. The counters are only moved where calculate runs (the
 * combiner needs the GL context there).
 */
final class SmartRelease {
    private SmartRelease() {}

    static final Guard guard = new Guard("smartTextureRelease");

    /** Whether a texture is already in its pool's free list. Tests swap it. */
    static Predicate<Texture> pooled = SmartRelease::inPool;
    /** Gives a texture back to the combiner (the pool, or destroyed when the pool is full). Tests swap it. */
    static Consumer<Texture> release = t -> TextureCombiner.instance.releaseTexture(t);

    /** Old results given back, and old results left in the pool (already there). */
    static long released, skipped;

    static void init() { guard.on = Settings.flag("smartTextureRelease"); }

    /** TextureCombiner.getFBO(II), private: the pool entry of one size, or null. */
    private static MethodHandle getFBO;
    /** TextureCombiner$CombinerFBO.textures, package-private: the entry's free list. */
    private static VarHandle texturesOf;
    private static volatile boolean bound;

    private static void bind() {
        Class<?> comb = TextureCombiner.class;
        Class<?> cfbo = null;
        for (Class<?> c : comb.getDeclaredClasses()) if (c.getSimpleName().equals("CombinerFBO")) cfbo = c;
        getFBO = Game.method(comb, "getFBO", MethodType.methodType(cfbo, int.class, int.class));
        texturesOf = Game.field(cfbo, "textures", ArrayDeque.class);
        bound = true;
    }

    /** True when t is in the free list of the pool entry of its size. A size with no entry (nothing ever pooled) answers false. */
    static boolean inPool(Texture t) {
        if (!bound) bind();
        try {
            Object fbo = (Object) getFBO.invoke(TextureCombiner.instance, t.getWidth(), t.getHeight());
            if (fbo == null) return false;
            for (Object x : (ArrayDeque<?>) texturesOf.get(fbo)) if (x == t) return true;
            return false;
        } catch (RuntimeException | Error e) { throw e; }
        catch (Throwable e) { throw new IllegalStateException(e); }
    }

    /** The calculate entry: the old result leaves the object and goes to the pool, unless it is already there. */
    static void beforeCalculate(SmartTexture st) {
        synchronized (st) {
            Texture old = st.result;
            if (old == null) return;
            if (pooled.test(old)) { skipped++; return; }
            st.result = null;          // before the release: a throw below never leaves a released texture in result
            release.accept(old);
            released++;
        }
    }

    /** Seams back to the game's, counters 0, guard off. */
    static void resetForTest() {
        pooled = SmartRelease::inPool;
        release = t -> TextureCombiner.instance.releaseTexture(t);
        released = skipped = 0;
        guard.on = false;
    }
}
