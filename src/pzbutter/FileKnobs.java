package pzbutter;

import java.lang.invoke.VarHandle;
import java.util.function.LongSupplier;

public final class FileKnobs {
    private FileKnobs() {}

    public static final Guard guard = new Guard("filePipeline");

    public static int textureBufferMb;
    public static int fileThreads;

    /** Package-private seam: default DirectBufferAllocator::getBytesAllocated. */
    static LongSupplier bytes = zombie.core.utils.DirectBufferAllocator::getBytesAllocated;

    /** Package-private seam: default Thread::sleep. */
    @FunctionalInterface
    interface Sleeper { void sleep(long ms) throws InterruptedException; }
    static Sleeper sleeper = Thread::sleep;

    /** Guard-owned parsed values; call once from Main. */
    static void init() {
        guard.on = false;
        textureBufferMb = 0;
        fileThreads = 0;
        try {
            if (Settings.enabled) {
                int tb = Settings.num("textureBufferMb", 0, 0, 2048);
                textureBufferMb = (tb <= 0) ? 0 : Math.max(16, Math.min(2048, tb));
                fileThreads = Settings.num("fileThreads", 0, 0, 32);
            }
        } catch (Throwable t) { guard.fail(t); textureBufferMb = 0; fileThreads = 0; }
        guard.on = textureBufferMb > 0 || fileThreads > 0;
    }

    public static long bufferCapBytes() {
        return textureBufferMb <= 0 ? 52428800L : (long) textureBufferMb * 1024L * 1024L;
    }

    /** OnEnter (skipOn) of TextureIDAssetManager.waitFileTask. */
    public static boolean onWaitFileTask() {
        if (!guard.on || textureBufferMb <= 0) return false;
        try {
            long cap = bufferCapBytes();
            while (bytes.getAsLong() > cap) sleeper.sleep(20);
            return true;
        } catch (Throwable t) { guard.fail(t); return false; }
    }

    private static VarHandle executorH;
    private static volatile boolean bound;

    public static void onFileSystemCreated(Object fs) {
        if (fileThreads <= 0 || fs == null) return;
        try {
            if (!bound) { bound = true; executorH = Game.field(fs.getClass(), "executor", java.util.concurrent.ExecutorService.class); }
            if (executorH == null) return;
            Object ex = executorH.get(fs);
            if (!(ex instanceof java.util.concurrent.ThreadPoolExecutor pool)) return;
            int current = pool.getCorePoolSize();
            int target = fileThreads;
            int stock = current;
            int[] order = resizeOrder(current, target);
            if (order.length > 0 && order[0] == 0) {
                pool.setMaximumPoolSize(target);
                pool.setCorePoolSize(target);
            } else {
                pool.setCorePoolSize(target);
                pool.setMaximumPoolSize(target);
            }
            System.out.println("[PZButter] fileThreads: pool " + target + " (stock " + stock + ")");
        } catch (Throwable t) { guard.fail(t); }
    }

    public static int[] resizeOrder(int current, int target) {
        return new int[] { target > current ? 0 : 1 };
    }

    static void resetForTest() {
        guard.on = false;
        textureBufferMb = 0;
        fileThreads = 0;
        bound = false;
        executorH = null;
        bytes = zombie.core.utils.DirectBufferAllocator::getBytesAllocated;
        sleeper = Thread::sleep;
    }
}