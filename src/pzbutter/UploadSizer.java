package pzbutter;

import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.util.IdentityHashMap;
import java.util.Locale;

/**
 * vboSizeClasses (covers VRAM Steady): the game's {@code GLVertexBufferObject.bufferData} re-specifies a buffer's storage at the exact
 * byte size of every batch, twice per {@code VBORenderer.flush}. Ours gives each buffer storage of a power-of-two size class and writes
 * the batch with {@code glBufferSubData} at offset 0. {@code orphan} re-specifies the same size with no data before each write (the
 * driver may recycle the block) and follows the class down as well as up; {@code subdata} only ever grows. A lag guard (as VRAM Steady's:
 * over 250 ms of upload time per second for 3 windows in a row) puts the game's upload back for the session. Render thread only.
 */
final class UploadSizer {
    private UploadSizer() {}

    static final Guard guard = new Guard("vboSizeClasses");

    static final int OFF = 0, ORPHAN = 1, SUBDATA = 2;
    static int mode;
    /** The smallest size class in bytes. */
    static long minBytes = 65_536L;

    static void init() {
        String m = Settings.enabled ? Settings.text("vboSizeClasses", "off").toLowerCase(Locale.ROOT) : "off";
        mode = m.equals("orphan") ? ORPHAN : m.equals("subdata") ? SUBDATA : OFF;
        int kb = Integer.highestOneBit(Settings.num("vboSizeClassMinKb", 64, 4, 4096));
        minBytes = kb * 1024L;
        guard.on = mode != OFF;
        if (guard.on) System.out.println("[PZButter] vboSizeClasses " + (mode == ORPHAN ? "orphan" : "subdata") + ", size classes from " + kb + " KB");
    }

    /** The storage size for a batch of n bytes: min up to min, else the next power of two, n itself when that overflows. */
    static long sizeClass(long n, long min) {
        if (n <= min) return min;
        long c = Long.highestOneBit(n - 1) << 1;
        return c <= 0 ? n : c;
    }

    static final int ALLOC = 0, RESPEC = 1, SUB = 2;

    /** What to do with a buffer: new storage of the class, the same size again (orphan), or just write. */
    static int decide(boolean known, boolean idChanged, long capacity, long n, long cls, int mode) {
        if (!known || idChanged || capacity < n || (mode == ORPHAN && capacity != cls)) return ALLOC;
        return mode == ORPHAN ? RESPEC : SUB;
    }

    /** The lag guard (VRAM Steady 3.4): upload time summed per window of at least 1 s; over 250 ms/s for 3 windows in a row trips it. */
    static final class Guard3 {
        private long windowStart, cost;
        private int over;
        /** The cost per second of the window that closed last, in ns. */
        double lastPerSec;

        boolean add(long ns, long nowNs) {
            cost += ns;
            long dt = nowNs - windowStart;
            if (dt < 1_000_000_000L) return false;
            lastPerSec = cost * 1e9 / dt;
            if (lastPerSec > 250e6) over++; else over = 0;
            windowStart = nowNs;
            cost = 0;
            return over >= 3;
        }
    }

    static Guard3 lag = new Guard3();

    /** The two GL calls of an upload; tests swap them. */
    interface Uploader {
        void alloc(int type, long bytes, int usage);
        void sub(int type, ByteBuffer data);
    }

    private static final Uploader LWJGL = new Uploader() {
        public void alloc(int type, long bytes, int usage) { org.lwjgl.opengl.GL15.glBufferData(type, bytes, usage); }
        public void sub(int type, ByteBuffer data) { org.lwjgl.opengl.GL15.glBufferSubData(type, 0L, data); }
    };
    static Uploader gl = LWJGL;

    /** Per buffer object: [capacity, id it was made for]. */
    private static final IdentityHashMap<Object, long[]> state = new IdentityHashMap<>();

    static long uploads, bytes, reallocs, passed;

    private static VarHandle typeH, usageH, idH;
    private static volatile boolean bound;

    private static boolean bind() {
        Class<?> c = zombie.core.VBO.GLVertexBufferObject.class;
        typeH = Game.field(c, "type", int.class);
        usageH = Game.field(c, "usage", int.class);
        idH = Game.field(c, "id", int.class);
        bound = true;
        if (typeH == null || usageH == null || idH == null) {
            mode = OFF;
            guard.on = false;
            System.out.println("[PZButter] vboSizeClasses off: cannot reach GLVertexBufferObject fields");
            return false;
        }
        return true;
    }

    /** True when the batch was uploaded here (the game's bufferData is skipped); false leaves it to the game. */
    static boolean upload(Object vbo, ByteBuffer data) {
        if (mode == OFF) return false;
        if (!(zombie.core.VBO.GLVertexBufferObject.funcs instanceof zombie.core.VBO.GLBufferObject15) || data == null || data.remaining() <= 0) {
            passed++;
            return false;
        }
        if (!bound && !bind()) return false;
        long t0 = System.nanoTime();
        int type = (int) typeH.get(vbo), usage = (int) usageH.get(vbo), id = (int) idH.get(vbo);
        long n = data.remaining();
        long cls = sizeClass(n, minBytes);
        long[] st = state.get(vbo);
        int d = decide(st != null, st != null && st[1] != id, st == null ? 0 : st[0], n, cls, mode);
        if (d == ALLOC) {
            gl.alloc(type, cls, usage);
            if (st == null) { st = new long[2]; state.put(vbo, st); }
            st[0] = cls;
            st[1] = id;
            reallocs++;
        } else if (d == RESPEC) {
            gl.alloc(type, st[0], usage);
        }
        gl.sub(type, data);
        uploads++;
        bytes += n;
        long now = System.nanoTime();
        if (lag.add(now - t0, now)) {
            mode = OFF;
            guard.on = false;
            System.out.println("[PZButter] vboSizeClasses: uploads took " + Math.round(lag.lastPerSec / 1e6) + " ms per second here, back to the game's upload for this session");
        }
        return true;
    }

    /** Back to stock: mode off, guard off, no remembered buffers, counters 0, the real GL calls. */
    static void resetForTest() {
        mode = OFF;
        minBytes = 65_536L;
        guard.on = false;
        lag = new Guard3();
        state.clear();
        uploads = bytes = reallocs = passed = 0;
        gl = LWJGL;
    }
}
