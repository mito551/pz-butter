package pzbutter;

import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.IdentityHashMap;

/**
 * persistentVbo (SPEC 3.7): the sprite ring ({@code SpriteRenderer$RingBuffer}) and the puddle ring ({@code SharedVertexBufferObjects})
 * map a 64 KB buffer and unmap it again for every batch, and the game's {@code map()} re-specifies the storage first (four driver
 * calls a batch that return a value). Here each buffer object keeps a few persistently mapped storages ("slots") and {@code map()} hands
 * out the next one: it writes that storage's GL name into the buffer's private {@code id}, binds it and returns its buffer; {@code unmap()}
 * does nothing. A slot is written again only when the GPU is done with it: a fence per swap (frame sync) or per batch decides, see
 * {@link SlotRing}. Every GL call goes through {@link Gl}, so the decisions run offline against a fake.
 *
 * <p>Immutable storage cannot go back to the game's {@code map()} (its {@code glBufferData} on that name is a GL error), so switching the
 * key off, or any failure, detaches: at the buffer's next {@code map()} it gets a fresh {@code glGenBuffers} name, its slots are unmapped
 * and deleted, and the game's body runs from then on. Render (GL context) thread only; the shims check.
 */
final class PersistentVbo {
    private PersistentVbo() {}

    /** The GL calls of this feature; {@link LwjglGl} in the game, a fake in tests and benches. */
    interface Gl {
        /** OpenGL 4.4 or GL_ARB_buffer_storage (with OpenGL 3.0 map ranges and 3.2 fences). */
        boolean capable();
        int genBuffer();
        /** Immutable storage for the buffer bound to the target: GL_MAP_WRITE | GL_MAP_PERSISTENT | GL_MAP_COHERENT. */
        void storage(int type, long size);
        /** Maps the bound buffer for good with the same flags; null when the driver gives nothing. */
        ByteBuffer mapPersistent(int type, long size);
        void bind(int type, int name);
        /** Binds the name, unmaps it and deletes it. */
        void unmapAndDelete(int type, int name);
        void deleteBuffer(int name);
        long fence();
        boolean signalled(long fence);
        /** Waits at most ns (flushing first); true once signalled, false on a timeout; throws when the driver says the wait failed. */
        boolean await(long fence, long ns);
        void deleteFence(long fence);
        /** glGetError. */
        int error();
        /** The current context's identity, 0 when none. */
        long context();
    }

    private static final int FLAGS = 0x02 | 0x40 | 0x80;   // GL_MAP_WRITE_BIT | GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT

    static final class LwjglGl implements Gl {
        private boolean core44;

        public boolean capable() {
            org.lwjgl.opengl.GLCapabilities c = org.lwjgl.opengl.GL.getCapabilities();
            core44 = c.OpenGL44;
            return (c.OpenGL44 || c.GL_ARB_buffer_storage) && c.OpenGL30 && c.OpenGL32;
        }
        public int genBuffer() { return org.lwjgl.opengl.GL15.glGenBuffers(); }
        public void storage(int type, long size) {
            if (core44) org.lwjgl.opengl.GL44.glBufferStorage(type, size, FLAGS);
            else org.lwjgl.opengl.ARBBufferStorage.glBufferStorage(type, size, FLAGS);
        }
        public ByteBuffer mapPersistent(int type, long size) { return org.lwjgl.opengl.GL30.glMapBufferRange(type, 0L, size, FLAGS); }
        public void bind(int type, int name) { org.lwjgl.opengl.GL15.glBindBuffer(type, name); }
        public void unmapAndDelete(int type, int name) {
            org.lwjgl.opengl.GL15.glBindBuffer(type, name);
            org.lwjgl.opengl.GL15.glUnmapBuffer(type);
            org.lwjgl.opengl.GL15.glDeleteBuffers(name);
        }
        public void deleteBuffer(int name) { org.lwjgl.opengl.GL15.glDeleteBuffers(name); }
        public long fence() { return org.lwjgl.opengl.GL32.glFenceSync(org.lwjgl.opengl.GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0); }
        public boolean signalled(long f) {
            int r = org.lwjgl.opengl.GL32.glClientWaitSync(f, 0, 0L);
            if (r == org.lwjgl.opengl.GL32.GL_WAIT_FAILED) throw new IllegalStateException("glClientWaitSync failed");
            return r == org.lwjgl.opengl.GL32.GL_ALREADY_SIGNALED || r == org.lwjgl.opengl.GL32.GL_CONDITION_SATISFIED;
        }
        public boolean await(long f, long ns) {
            int r = org.lwjgl.opengl.GL32.glClientWaitSync(f, org.lwjgl.opengl.GL32.GL_SYNC_FLUSH_COMMANDS_BIT, ns);
            if (r == org.lwjgl.opengl.GL32.GL_WAIT_FAILED) throw new IllegalStateException("glClientWaitSync failed");
            return r != org.lwjgl.opengl.GL32.GL_TIMEOUT_EXPIRED;
        }
        public void deleteFence(long f) { org.lwjgl.opengl.GL32.glDeleteSync(f); }
        public int error() { return org.lwjgl.opengl.GL11.glGetError(); }
        public long context() { return org.lwjgl.glfw.GLFW.glfwGetCurrentContext(); }
    }

    static final Guard guard = new Guard("persistentVbo");
    static boolean frameSync;
    static int wantSlots = 4;
    static Gl gl = new LwjglGl();

    /** True from the first attached ring until every ring is detached. */
    static boolean anyAttached;
    /** The hand-offs from the map / unmap enter advice to its exit advice (same thread, nothing between them). */
    static ByteBuffer pending;
    static boolean unmapSkipped;
    /** maps taken from the persistent storage (each saves the game's four driver calls with it), slot waits, same-frame stalls, slots a buffer. */
    static long saved, waits, stalls, slotCount;
    /** Bytes of one slot of every ring together; -1 until read from the game (tests set it). */
    static long ringBytes = -1;

    static final class Ring {
        final SlotRing.Buf s;
        final int type;
        final long size;
        int[] names;
        ByteBuffer[] bufs;
        final int origId;
        boolean detached, lost, gone, batchCovered;

        Ring(SlotRing.Buf s, int type, long size, int[] names, ByteBuffer[] bufs, int origId) {
            this.s = s; this.type = type; this.size = size; this.names = names; this.bufs = bufs; this.origId = origId;
        }
    }

    private static final IdentityHashMap<Object, Ring> rings = new IdentityHashMap<>();
    private static int attached;
    private static boolean detachedAll, batchOk = true, loggedSlow;
    private static long contextId;
    static long swapNo;
    static SlotRing.FenceRing frames = new SlotRing.FenceRing();
    private static final long[] pollOut = new long[16];

    private static final long SLICE_NS = 50_000_000L, SLOW_NS = 1_000_000_000L;
    /** A wait this long gives up: the feature goes off and every buffer detaches. Tests shorten it. */
    static long giveUpNs = 2_000_000_000L;

    /** The game's private fields, resolved once when this class first loads (static finals: the JIT treats them as constants). */
    private static final class H {
        static final VarHandle ID, TYPE, SIZE, MAPPED;
        static final boolean ok;

        static {
            Class<?> c = zombie.core.VBO.GLVertexBufferObject.class;
            ID = Game.field(c, "id", int.class);
            TYPE = Game.field(c, "type", int.class);
            SIZE = Game.field(c, "size", long.class);
            MAPPED = Game.field(c, "mapped", boolean.class);
            ok = ID != null && TYPE != null && SIZE != null && MAPPED != null;
        }
    }

    /** The sprite ring's fields (package-private), for the per-batch fence and the memory budget. */
    private static final class R {
        static final VarHandle RING, NUM, BUF, IDX, SEQ, VBO, IBO;
        static final boolean ok;

        static {
            Class<?> rc = null;
            try { rc = Class.forName("zombie.core.SpriteRenderer$RingBuffer"); }
            catch (Throwable t) { System.out.println("[PZButter] persistentVbo: " + t); }
            RING = rc == null ? null : Game.staticField(zombie.core.SpriteRenderer.class, "ringBuffer", rc);
            NUM = rc == null ? null : Game.field(rc, "numBuffers", int.class);
            BUF = rc == null ? null : Game.field(rc, "bufferSize", long.class);
            IDX = rc == null ? null : Game.field(rc, "indexBufferSize", long.class);
            SEQ = rc == null ? null : Game.field(rc, "sequence", int.class);
            VBO = rc == null ? null : Game.field(rc, "vbo", zombie.core.VBO.GLVertexBufferObject[].class);
            IBO = rc == null ? null : Game.field(rc, "ibo", zombie.core.VBO.GLVertexBufferObject[].class);
            ok = NUM != null && BUF != null && IDX != null && SEQ != null && VBO != null && IBO != null;
        }
    }

    static void init() {
        guard.on = Settings.flag("persistentVbo");
        frameSync = Settings.flag("persistentVboFrameSync");
        wantSlots = Settings.num("persistentVboSlots", 4, 1, 8);
        if (guard.on) System.out.println("[PZButter] persistentVbo on: up to " + wantSlots + " storages per buffer, a fence per " + (frameSync ? "frame" : "batch"));
    }

    // ---- map / unmap ----

    /** The persistent buffer for this batch, or null when the game's own map() should run (the buffer's id then is a mutable name). */
    static ByteBuffer map(Object vbo) {
        if (!guard.on && anyAttached && !detachedAll) detachAll();
        Ring r = rings.get(vbo);
        if (r == null) {
            if (!guard.on) return null;
            r = attach(vbo);
            if (r == null) return null;
        }
        if (r.gone) return null;
        if (r.detached) { detachNow(r, vbo); return null; }
        return slotMap(r, vbo);
    }

    /** True when this buffer's map() was served here (so its unmap() has nothing to do). */
    static boolean unmap(Object vbo) {
        Ring r = rings.get(vbo);
        return r != null && !r.gone && !r.detached;
    }

    private static Ring attach(Object vbo) {
        if (!H.ok) return off("cannot reach GLVertexBufferObject fields");
        if (!gl.capable()) return off("the driver has no persistent buffers (OpenGL 4.4 / GL_ARB_buffer_storage)");
        if ((boolean) H.MAPPED.get(vbo)) return null;                       // mid stock map: stay stock now, look again next time
        int type = (int) H.TYPE.get(vbo), id = (int) H.ID.get(vbo);
        long size = (long) H.SIZE.get(vbo);
        if (size <= 0 || size > Integer.MAX_VALUE) return null;
        if (slotCount == 0) decideSlots();
        int n = (int) slotCount;
        int[] names = new int[n];
        ByteBuffer[] bufs = new ByteBuffer[n];
        int made = 0, mapped = 0;
        try {
            gl.error();                                                      // forget earlier errors, so a nonzero answer below is ours
            while (made < n) {
                int name = gl.genBuffer();
                names[made++] = name;
                gl.bind(type, name);
                gl.storage(type, size);
                ByteBuffer b = gl.mapPersistent(type, size);
                if (b == null) throw new IllegalStateException("glMapBufferRange returned nothing");
                bufs[mapped++] = b.order(ByteOrder.nativeOrder());
            }
            int err = gl.error();
            if (err != 0) throw new IllegalStateException("GL error 0x" + Integer.toHexString(err));
        } catch (Throwable t) {
            for (int i = 0; i < made; i++) {
                try { if (i < mapped) gl.unmapAndDelete(type, names[i]); else gl.deleteBuffer(names[i]); } catch (Throwable ignored) { }
            }
            try { gl.bind(type, id); } catch (Throwable ignored) { }
            return off("persistent storage failed: " + t);
        }
        if (contextId == 0) contextId = gl.context();
        // the game's own name is not used again (a detach gives a fresh one); give its storage back
        if (id != 0) try { gl.deleteBuffer(id); } catch (Throwable ignored) { }
        Ring r = new Ring(new SlotRing.Buf(n), type, size, names, bufs, id);
        rings.put(vbo, r);
        attached++;
        anyAttached = true;
        return r;
    }

    private static Ring off(String why) {
        guard.on = false;
        System.out.println("[PZButter] persistentVbo off: " + why);
        return null;
    }

    private static void decideSlots() {
        if (ringBytes < 0) ringBytes = readRingBytes();
        slotCount = SlotRing.slots(wantSlots, ringBytes, SlotRing.BUDGET);
        System.out.println("[PZButter] persistentVbo: " + slotCount + " storages per buffer (" + (ringBytes >> 10) + " KB a storage across the rings, "
            + ((slotCount * ringBytes) >> 20) + " MB in all)");
    }

    /** One storage of every ring: the sprite ring's buffers as built (a debug run has more and bigger ones) and the puddle ring's 48. */
    private static long readRingBytes() {
        long num = 128, buf = 65_536, idx = 5_460;
        try {
            if (R.ok && R.RING != null) {
                Object rb = R.RING.get();
                if (rb != null && (int) R.NUM.get(rb) > 0) { num = (int) R.NUM.get(rb); buf = (long) R.BUF.get(rb); idx = (long) R.IDX.get(rb); }
            }
        } catch (Throwable ignored) { }
        return num * (buf + idx) + 48L * (65_536 + 6_144);
    }

    private static ByteBuffer slotMap(Ring r, Object vbo) {
        SlotRing.Buf b = r.s;
        int s = SlotRing.next(b);
        long w = b.written[s];
        boolean byFrame = frameSync || !batchOk || !r.batchCovered;
        boolean batchDone = !byFrame && w >= 0 && batchSignalled(b, s);
        int d = SlotRing.decide(w, swapNo, frames.completed, byFrame, batchDone);
        if (d == SlotRing.WAIT_FRAME) {
            long f = frames.handleFor(w);
            waits++;
            if (f != 0) waitOn(f);
            else { f = gl.fence(); try { waitOn(f); } finally { gl.deleteFence(f); } }       // its fence is gone: fence now
        } else if (d == SlotRing.FENCE_NOW) {
            waits++;
            stalls++;
            long f = gl.fence();
            try { waitOn(f); } finally { gl.deleteFence(f); }
        } else if (d == SlotRing.WAIT_BATCH) {
            waits++;
            long f = b.batchFence[s];
            waitOn(f);
        }
        if (b.batchFence[s] != 0) { gl.deleteFence(b.batchFence[s]); b.batchFence[s] = 0; }
        b.cur = s;
        b.written[s] = swapNo;
        H.ID.set(vbo, r.names[s]);
        gl.bind(r.type, r.names[s]);
        saved++;
        return r.bufs[s].clear().limit((int) r.size);
    }

    private static boolean batchSignalled(SlotRing.Buf b, int s) {
        long f = b.batchFence[s];
        return f == 0 || gl.signalled(f);
    }

    /** Waits for the fence in 50 ms slices; one log line past 1 s, and past 2 s the feature goes off (the caller's failure path detaches). */
    private static void waitOn(long f) {
        long t0 = System.nanoTime();
        while (!gl.await(f, SLICE_NS)) {
            long dt = System.nanoTime() - t0;
            if (dt > SLOW_NS && !loggedSlow) {
                loggedSlow = true;
                System.out.println("[PZButter] persistentVbo: waited over 1 s for the GPU to finish with a buffer");
            }
            if (dt > giveUpNs) {
                guard.on = false;
                System.out.println("[PZButter] persistentVbo off: the GPU did not finish with a buffer in 2 s");
                throw new IllegalStateException("fence wait timed out");
            }
        }
    }

    // ---- detach ----

    /** Marks every ring for detaching: each gets its fresh name at its next map(). */
    static void detachAll() {
        for (Ring r : rings.values()) if (!r.gone) r.detached = true;
        detachedAll = true;
    }

    /** Fresh mutable name into the buffer, its slots unmapped and deleted, its binding on the new name for the game's map() that follows. */
    private static void detachNow(Ring r, Object vbo) {
        int fresh = gl.genBuffer();
        H.ID.set(vbo, fresh);
        int[] names = r.names;
        r.names = null;
        r.bufs = null;
        r.gone = true;
        for (int i = 0; i < names.length; i++) {
            if (!r.lost) try { gl.unmapAndDelete(r.type, names[i]); } catch (Throwable ignored) { }
            long f = r.s.batchFence[i];
            if (f != 0 && !r.lost) try { gl.deleteFence(f); } catch (Throwable ignored) { }
            r.s.batchFence[i] = 0;
        }
        gl.bind(r.type, fresh);
        if (--attached <= 0) { attached = 0; anyAttached = false; dropFrames(); }
    }

    /** After a failure inside map(): this buffer goes back to the game's own storage right now (the game's map() must not meet immutable storage). */
    static void failover(Object vbo) {
        try {
            Ring r = rings.get(vbo);
            if (r != null && !r.gone) { r.detached = true; detachNow(r, vbo); }
        } catch (Throwable ignored) { }
    }

    private static void dropFrames() {
        int n = frames.pollOrder(pollOut);
        for (int i = 0; i < n; i++) {
            long h = frames.handleFor(pollOut[i]);
            if (h != 0) try { gl.deleteFence(h); } catch (Throwable ignored) { }
        }
        frames = new SlotRing.FenceRing();
    }

    // ---- fences ----

    /** Render thread, after each swap: this frame's fence, then the oldest pending ones are polled until one has not signalled. */
    static void onSwap() {
        long ctx = gl.context();
        if (contextId != 0 && ctx != 0 && ctx != contextId) { contextLost(); return; }
        long swap = swapNo++;
        long old = frames.put(swap, gl.fence());
        if (old != 0) gl.deleteFence(old);
        int n = frames.pollOrder(pollOut);
        for (int i = 0; i < n; i++) {
            long h = frames.handleFor(pollOut[i]);
            if (h == 0) continue;
            if (!gl.signalled(h)) break;
            frames.signalled(pollOut[i]);
            gl.deleteFence(h);
        }
    }

    /** The GL context is another one now: the names, mappings and fences of the old one are gone. Every buffer gets a fresh name in the new one at its next map(). */
    private static void contextLost() {
        guard.on = false;
        System.out.println("[PZButter] persistentVbo off: the GL context changed");
        for (Ring r : rings.values()) if (!r.gone) { r.lost = true; r.detached = true; }
        detachedAll = true;
        frames = new SlotRing.FenceRing();
        contextId = 0;
    }

    /** Per-batch mode, after RingBuffer.render(): a fence behind this batch's draws goes to the slot both of its buffers used. */
    static void afterRingRender(Object ring) {
        if (!R.ok || !batchOk) return;
        int seq = (int) R.SEQ.get(ring);
        fenceBatch(((Object[]) R.VBO.get(ring))[seq]);
        fenceBatch(((Object[]) R.IBO.get(ring))[seq]);
    }

    private static void fenceBatch(Object vbo) {
        Ring r = rings.get(vbo);
        if (r == null || r.gone || r.detached || r.s.cur < 0) return;
        int s = r.s.cur;
        long old = r.s.batchFence[s];
        r.s.batchFence[s] = gl.fence();
        if (old != 0) gl.deleteFence(old);
        r.batchCovered = true;
    }

    /** A failed per-batch fence: from now on every buffer waits for frame fences, which are always made. */
    static void batchFailed() { batchOk = false; }

    static void resetForTest() {
        rings.clear();
        attached = 0;
        anyAttached = false;
        detachedAll = false;
        batchOk = true;
        loggedSlow = false;
        contextId = 0;
        swapNo = 0;
        frames = new SlotRing.FenceRing();
        pending = null;
        unmapSkipped = false;
        saved = waits = stalls = slotCount = 0;
        ringBytes = -1;
        guard.on = false;
        frameSync = false;
        wantSlots = 4;
        gl = new LwjglGl();
        giveUpNs = 2_000_000_000L;
    }
}
