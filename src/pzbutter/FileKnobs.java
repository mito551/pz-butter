package pzbutter;

import java.lang.invoke.VarHandle;
import java.util.function.LongSupplier;

public final class FileKnobs {
    private FileKnobs() {}

    public static final Guard guard = new Guard("filePipeline");

    public static int textureBufferMb;
    public static int fileThreads;
    public static int fileInflight;

    /** Package-private seam: default DirectBufferAllocator::getBytesAllocated. */
    static LongSupplier bytes = zombie.core.utils.DirectBufferAllocator::getBytesAllocated;

    /** Package-private seam: default Thread::sleep. */
    @FunctionalInterface
    interface Sleeper { void sleep(long ms) throws InterruptedException; }
    static Sleeper sleeper = Thread::sleep;

    /** Parses the keys; resizes the file pool, which already exists (GameWindow.<clinit> 58 builds it, before any mod loads). */
    static void init() {
        guard.on = false;
        textureBufferMb = 0;
        fileThreads = 0;
        fileInflight = 0;
        try {
            if (Settings.enabled) {
                // a key forced off by modCompat answers stock (Settings.num would give the non-stock default)
                int tb = Settings.forcedOff.contains("textureBufferMb") ? 0 : Settings.num("textureBufferMb", TEXTURE_BUFFER_DEFAULT, 0, 2048);
                textureBufferMb = (tb <= 0) ? 0 : Math.max(16, tb);
                int ft = Settings.num("fileThreads", -1, -1, 32);
                fileThreads = ft < 0 ? autoThreads(Runtime.getRuntime().availableProcessors()) : ft;
                int fi = Settings.num("fileInflight", INFLIGHT_DEFAULT, 0, 256);
                fileInflight = (fi <= 0) ? 0 : Math.max(4, fi);
            }
        } catch (Throwable t) { guard.fail(t); textureBufferMb = 0; fileThreads = 0; fileInflight = 0; }
        guard.on = textureBufferMb > 0 || fileThreads > 0 || fileInflight > 0;
        if (fileThreads > 0) {
            String line = resize(zombie.GameWindow.fileSystem, fileThreads);
            if (line != null) System.out.println("[PZButter] " + line);
        }
    }

    /** The in-flight cap (stock 16 when the key is unset). Used by the rewritten FileSystemImpl. */
    public static int inflight() {
        return fileInflight <= 0 ? 16 : fileInflight;
    }

    /** Defaults from the Plan 11c sweep (12 cores): all three together cut the world load from ~54 s to ~45 s; each alone ~50 s. */
    static final int TEXTURE_BUFFER_DEFAULT = 256, INFLIGHT_DEFAULT = 64;

    /** fileThreads=-1 (auto): stock's pool (cores > 4 ? 4 : 2) widened to cores - 4, at most 8; never below stock, so 8 cores or fewer keep stock. */
    static int autoThreads(int cores) {
        int stock = cores > 4 ? 4 : 2;
        int want = Math.min(8, cores - 4);
        return want > stock ? want : 0;   // 0: leave the pool alone
    }

    public static long bufferCapBytes() {
        return textureBufferMb <= 0 ? 52428800L : (long) textureBufferMb * 1024L * 1024L;
    }

    /** OnEnter (skipOn) of TextureIDAssetManager.waitFileTask: stock's loop (0-23) with the configured cap. */
    public static boolean onWaitFileTask() {
        if (!guard.on || textureBufferMb <= 0) return false;
        try {
            long cap = bufferCapBytes();
            while (bytes.getAsLong() > cap) {
                try { sleeper.sleep(20); }
                catch (InterruptedException e) { }   // stock catches it and loops (19-20)
            }
            return true;
        } catch (Throwable t) { guard.fail(t); return false; }
    }

    private static VarHandle executorH;
    private static boolean bound;

    /** Resizes fs's ThreadPoolExecutor to target threads; returns the log line, or null when nothing was done. */
    static String resize(Object fs, int target) {
        if (fs == null || target <= 0) return null;
        try {
            if (!bound) { executorH = Game.field(zombie.fileSystem.FileSystemImpl.class, "executor", java.util.concurrent.ExecutorService.class); bound = true; }
            if (executorH == null || !(fs instanceof zombie.fileSystem.FileSystemImpl)) return null;
            if (!(executorH.get(fs) instanceof java.util.concurrent.ThreadPoolExecutor pool)) return null;
            int stock = pool.getCorePoolSize();
            if (resizeOrder(stock, target)[0] == 0) { pool.setMaximumPoolSize(target); pool.setCorePoolSize(target); }
            else { pool.setCorePoolSize(target); pool.setMaximumPoolSize(target); }
            return "fileThreads: pool " + target + " (stock " + stock + ")";
        } catch (Throwable t) { guard.fail(t); return null; }
    }

    /** {0}: grow (set the maximum first), {1}: shrink or same (set the core first); never core > max in between. */
    public static int[] resizeOrder(int current, int target) {
        return new int[] { target > current ? 0 : 1 };
    }

    static void resetForTest() {
        guard.on = false;
        textureBufferMb = 0;
        fileThreads = 0;
        fileInflight = 0;
        bound = false;
        executorH = null;
        bytes = zombie.core.utils.DirectBufferAllocator::getBytesAllocated;
        sleeper = Thread::sleep;
    }
}