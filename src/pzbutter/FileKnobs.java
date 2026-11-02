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

    /** True from GameLoadingState.enter to its exit: the raised limits act only during the world load (in play they stay stock). */
    static volatile boolean inLoad;
    private static int stockPool;
    private static boolean poolPrinted;

    /** Parses the keys (-1 = auto). Nothing changes until the world load starts. */
    static void init() {
        guard.on = false;
        textureBufferMb = 0;
        fileThreads = 0;
        fileInflight = 0;
        try {
            if (Settings.enabled) {
                int cores = Runtime.getRuntime().availableProcessors();
                // a key forced off by modCompat answers stock (Settings.num would give the call-site default)
                int tb = Settings.forcedOff.contains("textureBufferMb") ? 0 : Settings.num("textureBufferMb", -1, -1, 2048);
                fileThreads = resolveThreads(Settings.num("fileThreads", -1, -1, 32), cores);
                textureBufferMb = resolveBuffer(tb, cores);
                fileInflight = resolveInflight(Settings.num("fileInflight", -1, -1, 256), cores);
            }
            guard.on = textureBufferMb > 0 || fileThreads > 0 || fileInflight > 0;
            if (guard.on) System.out.println("[PZButter] filePipeline (world load only): buffer " + (textureBufferMb > 0 ? textureBufferMb + " MB" : "stock")
                + ", in flight " + (fileInflight > 0 ? fileInflight + (FileTransform.transformState ? "" : " (rewrite off: " + FileTransform.transformReason + ")") : "stock")
                + ", pool " + (fileThreads > 0 ? fileThreads : "stock"));
        } catch (Throwable t) { guard.fail(t); textureBufferMb = 0; fileThreads = 0; fileInflight = 0; guard.on = false; }
    }

    /**
     * -1 (auto): the measured setting (12 cores: 8 threads, 64 in flight, 256 MB; the load fell from ~54 s to ~45 s) only where the
     * pool widens; otherwise all three stay stock (never measured with a stock pool). 0: stock. Other values: as set.
     */
    static int resolveThreads(int raw, int cores) { return raw < 0 ? autoThreads(cores) : raw; }
    static int resolveBuffer(int raw, int cores) { int v = raw < 0 ? (autoThreads(cores) > 0 ? 256 : 0) : raw; return v <= 0 ? 0 : Math.max(16, v); }
    static int resolveInflight(int raw, int cores) { int v = raw < 0 ? (autoThreads(cores) > 0 ? 64 : 0) : raw; return v <= 0 ? 0 : Math.max(4, v); }

    /** GameLoadingState.enter (game thread): the world load starts. */
    public static void onLoadEnter() {
        if (!guard.on) return;
        try {
            inLoad = true;
            if (fileThreads > 0) {
                String line = resize(zombie.GameWindow.fileSystem, fileThreads);
                if (line != null && !poolPrinted) { poolPrinted = true; System.out.println("[PZButter] " + line); }
            }
        } catch (Throwable t) { guard.fail(t); }
    }

    /** GameLoadingState.exit: back to stock for play. */
    public static void onLoadExit() {
        if (!inLoad) return;
        inLoad = false;
        try { if (fileThreads > 0 && stockPool > 0) resize(zombie.GameWindow.fileSystem, stockPool); }
        catch (Throwable t) { guard.fail(t); }
    }

    /** The in-flight cap read by the rewritten FileSystemImpl.updateAsyncTransactions (7, 288): stock 16 outside the world load. */
    public static int inflight() {
        return inLoad && fileInflight > 0 ? fileInflight : 16;
    }

    /** fileThreads=-1 (auto): stock's pool (cores > 4 ? 4 : 2) widened to cores - 4, at most 8; 0 when that is not wider than stock. */
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
        if (!guard.on || textureBufferMb <= 0 || !inLoad) return false;
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
            if (stockPool == 0) stockPool = stock;
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
        inLoad = false;
        stockPool = 0;
        poolPrinted = false;
        bound = false;
        executorH = null;
        bytes = zombie.core.utils.DirectBufferAllocator::getBytesAllocated;
        sleeper = Thread::sleep;
    }
}