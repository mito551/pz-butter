package pzbutter;

import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Plan 11d: mod-watch registration off the game thread.
 * DebugFileWatcher.addDirectoryRecurse is advised to hand its path to one daemon worker (FIFO)
 * that walks the tree and registers each directory under the shared lock.
 */
public final class ModWatch {
    private ModWatch() {}

    public static final Guard guard = new Guard("modWatchAsync");

    /** Shared lock held while touching DebugFileWatcher's maps. */
    public static final ReentrantLock lock = new ReentrantLock();

    public static boolean lockHeldOwner() { return lock.isHeldByCurrentThread(); }

    public static boolean lockEnter() {
        if (!guard.on) return false;
        try { lock.lock(); return true; }
        catch (Throwable t) { guard.fail(t); return false; }
    }

    public static void lockExit(boolean locked) {
        if (locked) try { lock.unlock(); } catch (Throwable t) { guard.fail(t); }
    }

    static void init() {
        try {
            guard.on = Settings.enabled && Settings.flag("modWatchAsync");
        } catch (Throwable t) {
            guard.fail(t);
            guard.on = false;
        }
        if (guard.on) {
            try {
                registerH = Game.method(zombie.DebugFileWatcher.class, "registerDir",
                    java.lang.invoke.MethodType.methodType(void.class, java.nio.file.Path.class));
                // bind the instance lazily via an accessor
            } catch (Throwable t) { guard.fail(t); guard.on = false; }
        }
    }

    private static java.lang.invoke.MethodHandle registerH;
    /** Package-private register seam used by the worker (tests may replace). */
    public static volatile java.util.function.Consumer<java.nio.file.Path> register = p -> {
        Object inst = watcherInstance();
        if (inst == null || registerH == null) return;
        try { registerH.invoke(inst, p); }
        catch (Throwable t) { guard.fail(t); }
    };

    private static volatile Object watcherInstanceLocked;

    static java.lang.invoke.MethodHandle registerHandle() { return registerH; }

    static Object watcherInstance() {
        if (watcherSupplier != null) return watcherSupplier.get();
        return zombie.DebugFileWatcher.instance;
    }

    /** Supplier seam for the watcher field (null means stop). */
    public static volatile java.util.function.Supplier<Object> watcherSupplier = () -> {
        try {
            java.lang.invoke.VarHandle vh = Game.field(zombie.DebugFileWatcher.class, "watcher", java.nio.file.WatchService.class);
            if (vh == null) return null;
            return vh.get(zombie.DebugFileWatcher.instance);
        } catch (Throwable t) { return null; }
    };

    private static final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private static volatile Thread worker;
    private static volatile boolean printed;
    private static final AtomicInteger dirsProcessed = new AtomicInteger();

    public static void resetForTest() {
        guard.on = false;
        printed = false;
        dirsProcessed.set(0);
        queue.clear();
        worker = null;
        register = null;
        registerH = null;
        watcherSupplier = () -> null;
    }

    /** Called from the advised addDirectoryRecurse: true skips stock. */
    public static boolean onAddDirectoryRecurse(String path) {
        if (path == null) return false;
        if (!guard.on) return false;
        try {
            if (!zombie.DebugFileWatcher.instance.isInitialized()) return false;   // stock handles pre-init queueing
            queue.offer(path);
            ensureWorker();
            return true;
        } catch (Throwable t) { guard.fail(t); return false; }
    }

    private static synchronized void ensureWorker() {
        if (worker != null && worker.isAlive()) return;
        Thread t = new Thread(ModWatch::loop, "PZButter modWatch");
        t.setDaemon(true);
        worker = t;
        t.start();
    }

    private static void loop() {
        boolean hadWork = false;
        long t0 = System.nanoTime();
        int n = 0;
        while (true) {
            String path;
            try { path = queue.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            if (path == null) {
                // queue empty
                if (hadWork && !printed) {
                    printed = true;
                    long ms = (System.nanoTime() - t0) / 1_000_000L;
                    System.out.println("[PZButter] modWatchAsync: " + n + " directories in " + ms + " ms");
                }
                // if queue empty for a while, exit? Keep alive but parked; check watcher? We'll continue
                // Stop only if watcher null and queue empty:
                Object w = watcherSupplier.get();
                if (w == null) return;
                continue;
            }
            if (!hadWork) { t0 = System.nanoTime(); n = 0; hadWork = true; printed = false; } // first work phase; but printed guards across full
            // Actually printed must only fire first queue empty; keep across? Plan first time. Fine.
            // Process
            Object watcher = watcherSupplier.get();
            if (watcher == null) { return; }
            try {
                Path root = Path.of(path);
                Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        Object w2 = watcherSupplier.get();
                        if (w2 == null) return FileVisitResult.TERMINATE;
                        lock.lock();
                        try { if (register != null) register.accept(dir); dirsProcessed.incrementAndGet(); }
                        finally { lock.unlock(); }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (Throwable t) { guard.fail(t); }
        }
    }

    /** Package-private sync entry for tests: walks one root and returns (no thread). */
    public static void runSync(String path) {
        long t0 = System.nanoTime();
        int n = 0;
        Object watcher = watcherSupplier.get();
        if (watcher == null) return;
        try {
            Path root = Path.of(path);
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    Object w2 = watcherSupplier.get();
                    if (w2 == null) return FileVisitResult.TERMINATE;
                    lock.lock();
                    try { if (register != null) register.accept(dir); }
                    finally { lock.unlock(); }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Throwable t) { guard.fail(t); }
    }

    /** A handle to the stock registerDir for production binding after init. */
    public static void bindRegister() {
        try {
            registerH = Game.method(zombie.DebugFileWatcher.class, "registerDir",
                java.lang.invoke.MethodType.methodType(void.class, java.nio.file.Path.class));
        } catch (Throwable t) { guard.fail(t); }
    }

    static Object watcherInstanceLockedDummy() { return watcherInstanceLocked; }
}
