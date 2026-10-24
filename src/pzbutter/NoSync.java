package pzbutter;

import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.function.IntSupplier;
import java.util.function.IntUnaryOperator;
import java.util.function.ToIntBiFunction;

/**
 * bakeNoSync: a threaded driver finishes all queued work before it answers a question that returns a value. The game asks a few on its
 * bake and draw paths (a new texture or framebuffer name, a framebuffer completeness check, a uniform location, which program is current).
 * Here they are answered from our own records: names come from pools the driver filled 64 at a time (one question per 64), a framebuffer
 * shape the driver called complete once is complete again, a uniform location is remembered per program until the program is relinked or
 * deleted, and the current program is whatever {@code glUseProgram} last set. Every answer is the driver's own, so it is right for every
 * caller while the record holds. {@code bakeNoSyncCheck} asks the driver again for every 256th answer and logs a difference.
 *
 * <p>The cores here are pure (no GL) and tested offline; the GL calls (the pool top-up, the check's questions) are at the bottom. Render
 * (GL context) thread only; the shims in {@link GpuPatches} check.
 */
final class NoSync {
    private NoSync() {}

    static final Guard guard = new Guard("bakeNoSync");
    /** bakeNoSyncCheck: every 256th answer is also asked of the driver. */
    static boolean check;

    static void init() {
        guard.on = Settings.flag("bakeNoSync");
        check = guard.on && Settings.flag("bakeNoSyncCheck");
        if (guard.on) System.out.println("[PZButter] bakeNoSync on" + (check ? ", checking every 256th answer against the driver" : ""));
    }

    static final int POOL = 64, LOW = 16;
    static final int FRAMEBUFFER_COMPLETE = 0x8CD5, CURRENT_PROGRAM = 0x8B8D, GL_FRAMEBUFFER = 0x8D40;

    /** Driver-made names nobody has bound, handed out last in, first out. */
    static final class NamePool {
        private final int[] names = new int[POOL];
        private int n;

        /** A name, or -1 when the pool is empty. */
        int take() { return n == 0 ? -1 : names[--n]; }
        /** Names {@code <= 0} are not names; a full pool drops the extra. */
        void add(int name) { if (name > 0 && n < POOL) names[n++] = name; }
        boolean low() { return n < LOW; }
        int size() { return n; }
        void clear() { n = 0; }
    }

    /** Framebuffer shapes the driver has called complete. */
    static final class ShapeMemo {
        private final HashSet<Long> complete = new HashSet<>();
        /** The shape of the TextureFBO.initInternal in flight (-1: none, or one never remembered) and how deep its calls nest. */
        long current = -1;
        int depth;

        /**
         * Colour size (16 bits each), depth texture size (15 bits each; a depth texture is present when its size is not 0) and the
         * depth-renderbuffer boolean, in 63 bits so the key is never negative. A size over the field (or a depth texture of size 0) gives
         * -1, which is never remembered.
         */
        static long key(int w, int h, boolean depthTex, int dw, int dh, boolean depthRb) {
            if (w < 0 || h < 0 || w > 0xFFFF || h > 0xFFFF) return -1;
            if (!depthTex) { dw = 0; dh = 0; }
            else if (dw <= 0 || dh <= 0 || dw > 0x7FFF || dh > 0x7FFF) return -1;
            return (long) w | (long) h << 16 | (long) dw << 32 | (long) dh << 47 | (depthRb ? 1L << 62 : 0L);
        }

        boolean complete(long key) { return key >= 0 && complete.contains(key); }
        /** Only a complete answer for a valid key is stored. */
        void remember(long key, int status) { if (key >= 0 && status == FRAMEBUFFER_COMPLETE) complete.add(key); }
        void forget(long key) { complete.remove(key); }
        void enter(long key) { if (depth++ == 0) current = key; }
        void exit() { if (depth > 0 && --depth == 0) current = -1; }
        void clear() { complete.clear(); current = -1; depth = 0; }
    }

    /** Uniform locations per (program, name); -1 (not found) is an answer too. Not hot: three lookups per puddle bake. */
    static final class UniformCache {
        static final int MISS = Integer.MIN_VALUE;
        private final HashMap<Integer, HashMap<String, Integer>> byProgram = new HashMap<>();

        int get(int program, CharSequence name) {
            if (name == null) return MISS;
            HashMap<String, Integer> m = byProgram.get(program);
            if (m == null) return MISS;
            Integer v = m.get(name.toString());
            return v == null ? MISS : v;
        }

        void put(int program, CharSequence name, int loc) {
            if (name == null || loc == MISS) return;
            byProgram.computeIfAbsent(program, p -> new HashMap<>()).put(name.toString(), loc);
        }

        void drop(int program) { byProgram.remove(program); }
        void clear() { byProgram.clear(); }
    }

    /** The program glUseProgram last set; unknown until the first one after install. */
    static final class ProgramRecord {
        static final int UNKNOWN = -1;
        int current = UNKNOWN;

        /** 0 (no program) is a record; a negative program is not a program, so the record is lost. */
        void used(int program) { current = program >= 0 ? program : UNKNOWN; }
        /** The current program for GL_CURRENT_PROGRAM when known, else MISS (ask the driver; other pnames too). */
        int answer(int pname) { return pname == CURRENT_PROGRAM && current >= 0 ? current : UniformCache.MISS; }
        void forget() { current = UNKNOWN; }
    }

    /** bakeNoSyncCheck's cadence and its log budget. */
    static final class CheckCounter {
        private int calls;
        int mismatches;

        boolean due() { return (++calls & 255) == 0; }
        /** Counts a mismatch; true for the first five (those are logged). */
        boolean log() { return ++mismatches <= 5; }
        void clear() { calls = 0; mismatches = 0; }
    }

    static final NamePool textures = new NamePool(), framebuffers = new NamePool(), renderbuffers = new NamePool();
    static final ShapeMemo shapes = new ShapeMemo();
    static final UniformCache uniforms = new UniformCache();
    static final ProgramRecord programs = new ProgramRecord();
    static final CheckCounter checker = new CheckCounter();

    /** Names taken from a pool, framebuffer checks and uniform locations and program queries answered from a record, driver questions that fell through. */
    static long namesPooled, fboAnswered, uniformsAnswered, programAnswered, asked;

    /**
     * The hand-off from a skipping enter advice to its exit advice (same thread, nothing between them): the kind says which answer is
     * waiting, so a stale one is never taken by another method's exit.
     */
    static final int K_NONE = 0, K_TEXTURE = 1, K_FRAMEBUFFER = 2, K_RENDERBUFFER = 3, K_STATUS = 4, K_UNIFORM = 5, K_INTEGER = 6;
    static int pendingKind, pendingInt;

    /** The driver's own answers for the check; tests swap them. */
    static IntUnaryOperator driverIntegerForTest = NoSync::driverInteger;
    static ToIntBiFunction<Integer, CharSequence> driverUniformForTest = NoSync::driverUniform;
    static IntSupplier driverStatusForTest = NoSync::driverStatus;

    // ---- GL parts (render thread) ----

    private static IntBuffer scratch;

    /** Fills every low pool back to 64 names; one driver call a pool. The IntBuffer overloads are not the hooked ()I ones. */
    static void topUp() {
        if (textures.low()) {
            IntBuffer b = scratch(POOL - textures.size());
            org.lwjgl.opengl.GL11.glGenTextures(b);
            drain(b, textures);
        }
        if (framebuffers.low()) {
            IntBuffer b = scratch(POOL - framebuffers.size());
            org.lwjgl.opengl.GL30.glGenFramebuffers(b);
            drain(b, framebuffers);
        }
        if (renderbuffers.low()) {
            IntBuffer b = scratch(POOL - renderbuffers.size());
            org.lwjgl.opengl.GL30.glGenRenderbuffers(b);
            drain(b, renderbuffers);
        }
    }

    private static IntBuffer scratch(int n) {
        if (scratch == null) scratch = java.nio.ByteBuffer.allocateDirect(POOL * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
        scratch.clear();
        scratch.limit(n);
        return scratch;
    }

    private static void drain(IntBuffer b, NamePool p) {
        for (int i = 0; i < b.limit(); i++) p.add(b.get(i));
    }

    /** GL11C.glGetInteger and GL20C.glGetUniformLocation: the entry points under the hooked wrappers, used only by the check. */
    static int driverInteger(int pname) { return org.lwjgl.opengl.GL11C.glGetInteger(pname); }
    static int driverUniform(int program, CharSequence name) { return org.lwjgl.opengl.GL20C.glGetUniformLocation(program, name); }
    static int driverStatus() { return org.lwjgl.opengl.GL30C.glCheckFramebufferStatus(GL_FRAMEBUFFER); }

    static void resetForTest() {
        guard.on = false;
        check = false;
        textures.clear(); framebuffers.clear(); renderbuffers.clear();
        shapes.clear(); uniforms.clear(); programs.forget(); checker.clear();
        namesPooled = fboAnswered = uniformsAnswered = programAnswered = asked = 0;
        pendingKind = K_NONE; pendingInt = 0;
        driverIntegerForTest = NoSync::driverInteger;
        driverUniformForTest = NoSync::driverUniform;
        driverStatusForTest = NoSync::driverStatus;
    }
}
