package pzbutter;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Plan 11d (modWatchAsync): DebugFileWatcher.addDirectoryRecurse (ZomboidFileSystem.getAllModFoldersAux 302, 332: every installed
 * mod's media trees, ~17 s of boot with 49,507 directories) is handed to one daemon worker. It walks like stock's DebugFileWatcher$1
 * (preVisitDirectory registers, CONTINUE; no links followed) and registers each directory under {@link #lock}, which the holders on
 * DebugFileWatcher's map methods (registerDir, update, addWatchedFile, add, remove) also take while the key is on.
 */
public final class ModWatch {
    private ModWatch() {}

    public static final Guard guard = new Guard("modWatchAsync");

    /** Held while touching DebugFileWatcher's maps (game thread: holders; worker: one directory at a time). */
    public static final ReentrantLock lock = new ReentrantLock();

    /** Holder entry: locks only while the key is on; the returned flag goes to {@link #lockExit}. */
    public static boolean lockEnter() {
        if (!guard.on) return false;
        try { lock.lock(); return true; }
        catch (Throwable t) { guard.fail(t); return false; }
    }

    public static void lockExit(boolean locked) {
        if (locked) try { lock.unlock(); } catch (Throwable t) { guard.fail(t); }
    }

    private static MethodHandle registerH;
    private static VarHandle watcherH;

    /**
     * Premain (Preload.premain, after Settings.load): the switch only. The walk this replaces runs during the boot's
     * ZomboidFileSystem.getAllModFolders, before Main; the handles are bound on first use so premain loads no game class.
     */
    static void preInit() {
        try { guard.on = Settings.enabled && Settings.flag("modWatchAsync"); }
        catch (Throwable t) { guard.on = false; }
    }

    /** Main: the same switch again (a ZombieBuddy start without the preload reaches only this; the boot walk is then stock). */
    static void init() { preInit(); }

    private static boolean bound;

    private static synchronized boolean bind() {
        if (!bound) {
            bound = true;
            registerH = Game.method(zombie.DebugFileWatcher.class, "registerDir", MethodType.methodType(void.class, Path.class));
            watcherH = Game.field(zombie.DebugFileWatcher.class, "watcher", java.nio.file.WatchService.class);
            if (registerH == null || watcherH == null) { guard.on = false; System.out.println("[PZButter] modWatchAsync off: DebugFileWatcher members not found"); }
        }
        return registerH != null && watcherH != null;
    }

    /** Seams (tests replace them): stock's private registerDir, the watcher field (null: stock stopped watching), stock's IOException reaction. */
    static final Consumer<Path> REAL_REGISTER = p -> {
        try { registerH.invoke(zombie.DebugFileWatcher.instance, p); }
        catch (Throwable t) { throw new RuntimeException(t); }
    };
    static volatile Consumer<Path> register = REAL_REGISTER;
    static volatile Supplier<Object> watcherSupplier = () -> watcherH.get(zombie.DebugFileWatcher.instance);
    static volatile Runnable onIOException = () -> watcherH.set(zombie.DebugFileWatcher.instance, (java.nio.file.WatchService) null);   // registerDirRecursive 14-23

    private static final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private static Thread worker;
    private static volatile boolean printed;

    /** OnEnter (skipOn) of DebugFileWatcher.addDirectoryRecurse(String): true hands it to the worker. Before init stock queues it itself. */
    public static boolean onAddDirectoryRecurse(String path) {
        if (!guard.on || path == null) return false;
        try {
            if (!zombie.DebugFileWatcher.instance.isInitialized() || (register == REAL_REGISTER && !bind())) return false;
            queue.offer(path);
            ensureWorker();
            return true;
        } catch (Throwable t) { guard.fail(t); return false; }
    }

    private static synchronized void ensureWorker() {
        if (worker != null && worker.isAlive()) return;
        worker = new Thread(ModWatch::loop, "PZButter modWatch");
        worker.setDaemon(true);
        worker.start();
    }

    private static volatile int dirs, roots;
    private static volatile long busyNs;

    private static void loop() {
        while (guard.on) {
            String path;
            try { path = queue.poll(1, TimeUnit.SECONDS); }
            catch (InterruptedException e) { return; }
            if (path == null) continue;
            long t0 = System.nanoTime();
            dirs += walk(path);
            roots++;
            busyNs += System.nanoTime() - t0;
        }
    }

    /** GameLoadingState exit: what the worker has registered so far (the boot's walk is long done by then). */
    public static void printSummaryOnce() {
        if (!guard.on || printed || roots == 0) return;
        printed = true;
        System.out.println("[PZButter] modWatchAsync: " + roots + " mod media trees, " + dirs + " directories, " + busyNs / 1_000_000 + " ms on the worker");
    }

    /** One stock registerDirRecursive on this thread; returns the directories registered. */
    static int walk(String path) {
        int[] n = {0};
        try {
            Files.walkFileTree(Path.of(path), new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    lock.lock();
                    try {
                        // the guard failing (holders stop locking) or stock dropping its watcher ends the walk
                        if (!guard.on || watcherSupplier.get() == null) return FileVisitResult.TERMINATE;
                        register.accept(dir);
                        n[0]++;
                    } finally { lock.unlock(); }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            lock.lock();
            try { onIOException.run(); } finally { lock.unlock(); }   // as stock: an unreadable tree stops all watching
        } catch (Throwable t) { guard.fail(t); }
        return n[0];
    }

    /** Tests: one walk on the calling thread. */
    static void runSync(String path) { walk(path); }

    static void resetForTest() {
        guard.on = false;
        printed = false;
        dirs = 0; roots = 0; busyNs = 0;
        queue.clear();
        register = p -> {};
        watcherSupplier = () -> null;
        onIOException = () -> {};
    }
}
