package pzbutter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.function.Supplier;
import zombie.Lua.LuaManager.GlobalObject.LuaFileWriter;

/**
 * luaFileWriteThrottle: Lua's {@code getFileWriter} writes that repeat within a window are held in memory and the latest state is
 * written once the window ends (SPEC 6.1; the plan keeps the text instead of dropping it). A heartbeat-like file is held for 2 s
 * after its last real write (any mode), any non-append rewrite for 0.5 s. The held writer is a stock {@link LuaFileWriter} over
 * our own {@link PrintWriter}, so Lua cannot tell it from stock's. Only names stock accepted before are ever held (stock checks the
 * path and the extension), and a name a mod reads back is flushed and never held again.
 *
 * Lua thread for the open and read hooks, the game thread for {@link #tick}, the JVM shutdown thread for {@link #flushAll}: the
 * package-visible methods are synchronized on the class.
 */
final class LuaFiles {
    private LuaFiles() {}

    static final Guard guard = new Guard("luaFileWriteThrottle");
    static final long REPEAT_NS = 2_000_000_000L, REWRITE_NS = 500_000_000L;
    static final int MAX_HELD_CHARS = 1 << 20;
    private static final int MAX_KEYS = 8192;
    private static final long NEVER = Long.MIN_VALUE / 2;
    private static final long STAT_NS = 60_000_000_000L;

    /** The Lua cache directory (the game's; tests set a temp dir). */
    static Supplier<String> cacheDir = zombie.Lua.LuaManager::getLuaCacheDir;
    private static final boolean WINDOWS = File.separatorChar == '\\';

    static long heldOpens, diskWrites;

    /** One file: its first-seen name and what stock and we did with it. */
    private static final class Entry {
        final String name;
        final boolean heartbeat;
        long lastOpenNs = NEVER, lastRewriteNs = NEVER, dueNs;
        boolean readBack;
        StringBuilder base, tail;       // base != null: the file is to be replaced by base + tail; else tail is appended
        Entry(String name) { this.name = name; this.heartbeat = heartbeatName(name); }
        boolean pending() { return base != null || tail != null; }
        int size() { return (base == null ? 0 : base.length()) + (tail == null ? 0 : tail.length()); }
    }

    private static final HashMap<String, Entry> entries = new HashMap<>();
    /** Earliest due time of any held text (Long.MAX_VALUE: nothing held). Read by the frame tick without the lock. */
    private static volatile long wake = Long.MAX_VALUE;
    private static volatile long lastStatNs;
    private static long statHeld;

    static void init() {
        guard.on = Settings.flag("luaFileWriteThrottle");
        cacheDir = zombie.Lua.LuaManager::getLuaCacheDir;
        if (guard.on) Runtime.getRuntime().addShutdownHook(new Thread(LuaFiles::flushAll, "PZButter-luafiles"));
    }

    /** The throttle is on, or text is still held from before it went off (it still has to reach the disk). */
    static boolean active() { return guard.on || wake != Long.MAX_VALUE; }

    // ---- pure rules ----

    static boolean heartbeatName(String name) {
        int s = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1;
        int e = name.lastIndexOf('.');
        if (e < s) e = name.length();
        boolean temp = false, log = false;
        int i = s;
        while (i < e) {
            while (i < e && !Character.isLetter(name.charAt(i))) i++;
            int t0 = i;
            if (i < e) {
                i++;
                while (i < e && Character.isLetter(name.charAt(i)) && !(Character.isUpperCase(name.charAt(i)) && Character.isLowerCase(name.charAt(i - 1)))) i++;
            }
            if (i == t0) break;
            String tok = name.substring(t0, i).toLowerCase(Locale.ROOT);
            switch (tok) {
                case "heartbeat", "pulse", "ping", "templog", "tmplog" -> { return true; }
                case "temp", "tmp" -> temp = true;
                case "log" -> log = true;
                default -> { }
            }
        }
        return temp && log;
    }

    static String key(String name, boolean windows) {
        String k = name.indexOf('\\') < 0 ? name : name.replace('\\', '/');
        return windows ? k.toLowerCase(Locale.ROOT) : k;
    }

    static File resolve(String cacheDir, String name) {
        String sep = File.separator;
        return new File((cacheDir + sep + name).replace("/", sep).replace("\\", sep));
    }

    // ---- the hooks' cores ----

    /** getFileWriter entry: a held writer, or null to run stock. */
    static Object onOpen(String name, boolean create, boolean append, long nowNs) {
        if (!guard.on && wake == Long.MAX_VALUE) return null;       // off and nothing held: no lock
        return open(name, create, append, nowNs);
    }

    private static synchronized Object open(String name, boolean create, boolean append, long nowNs) {
        if (!guard.on) { if (wake != Long.MAX_VALUE) writeAll(); return null; }
        Entry e = entries.get(key(name, WINDOWS));
        if (e == null || e.readBack) return null;
        boolean pending = e.pending();
        long due = NEVER;
        if (e.heartbeat && nowNs - e.lastOpenNs < REPEAT_NS) due = e.lastOpenNs + REPEAT_NS;
        if (!append && nowNs - e.lastRewriteNs < REWRITE_NS) due = Math.max(due, e.lastRewriteNs + REWRITE_NS);
        // create=false: stock's FileOutputStream would create a missing file anyway, but we only extend text we already hold
        // (a file we know exists); a create=false open with nothing pending runs stock
        if (due != NEVER && e.size() < MAX_HELD_CHARS && (create || pending)) {
            if (!append) { e.base = new StringBuilder(); e.tail = null; }
            else if (e.base == null && e.tail == null) e.tail = new StringBuilder();
            e.dueNs = pending ? Math.min(e.dueNs, due) : due;
            if (e.dueNs < wake) wake = e.dueNs;
            heldOpens++;
            return new LuaFileWriter(new PrintWriter(new HeldText(e)));
        }
        if (pending) {
            if (append) writeEntry(e, nowNs);        // stock appends after the held text
            else e.base = e.tail = null;             // stock truncates anyway
        }
        return null;
    }

    /** getFileWriter exit after a stock open: the name was accepted (stockWriter != null). */
    static synchronized void onStockOpened(String name, boolean append, Object stockWriter, long nowNs) {
        if (stockWriter == null) return;
        Entry e = entry(name);
        if (e == null) return;
        e.lastOpenNs = nowNs;
        if (!append) e.lastRewriteNs = nowNs;
    }

    /**
     * getFileReader / getFileInput / getFileOutput entry: the held text reaches the disk first, the name is never held again. Only a
     * name that has an entry (stock opened it for writing before) is marked: a mod that merely reads many names cannot fill the table.
     */
    static synchronized void beforeRead(String name) {
        Entry e = entries.get(key(name, WINDOWS));
        if (e == null) return;
        e.readBack = true;
        if (e.pending()) writeEntry(e, System.nanoTime());
    }

    static synchronized int entryCount() { return entries.size(); }

    /** Frame end (game thread): writes the held text whose window ended. */
    static void tick(long nowNs) {
        if (nowNs < wake && nowNs - lastStatNs < STAT_NS) return;
        synchronized (LuaFiles.class) {
            if (nowNs >= wake) {
                long next = Long.MAX_VALUE;
                for (Entry e : entries.values()) {
                    if (!e.pending()) continue;
                    if (nowNs >= e.dueNs) writeEntry(e, nowNs);
                    else if (e.dueNs < next) next = e.dueNs;
                }
                wake = next;
            }
            if (nowNs - lastStatNs >= STAT_NS) {
                if (lastStatNs != 0 && heldOpens != statHeld) System.out.println(statLine());
                statHeld = heldOpens;
                lastStatNs = nowNs;
            }
        }
    }

    static synchronized void flushAll() { writeAll(); }

    static String statLine() { return "[PZButter] luaFileWriteThrottle: " + heldOpens + " opens held, " + diskWrites + " disk writes"; }

    static synchronized void resetForTest() {
        entries.clear();
        wake = Long.MAX_VALUE;
        lastStatNs = 0; statHeld = 0; heldOpens = 0; diskWrites = 0;
        guard.on = false;
        cacheDir = zombie.Lua.LuaManager::getLuaCacheDir;
    }

    // ---- internals (class lock held) ----

    private static Entry entry(String name) {
        String k = key(name, WINDOWS);
        Entry e = entries.get(k);
        if (e == null && entries.size() < MAX_KEYS) entries.put(k, e = new Entry(name));
        return e;
    }

    private static void writeAll() {
        for (Entry e : entries.values()) if (e.pending()) writeEntry(e, System.nanoTime());
        wake = Long.MAX_VALUE;
    }

    /** Writes the held text now. It counts as an accepted open at nowNs, so a mod that keeps writing gets one disk write a window. */
    private static void writeEntry(Entry e, long nowNs) {
        StringBuilder base = e.base, tail = e.tail;
        e.base = e.tail = null;
        if (base == null && tail == null) return;
        boolean replace = base != null;
        try {
            File f = resolve(cacheDir.get(), e.name);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            String text = replace ? (tail == null ? base.toString() : base.append(tail).toString()) : tail.toString();
            try (FileOutputStream o = new FileOutputStream(f, !replace)) { o.write(text.getBytes(StandardCharsets.UTF_8)); }
            diskWrites++;
        } catch (IOException | RuntimeException x) {
            guard.fail(x);                                   // stock would have failed its open too: the text is dropped
        }
        e.lastOpenNs = nowNs;
        if (replace) e.lastRewriteNs = nowNs;
    }

    /** What a held {@link LuaFileWriter} writes into: the entry's held text (base when a truncation is pending, else tail). */
    private static final class HeldText extends Writer {
        private final Entry e;
        HeldText(Entry e) { this.e = e; }

        @Override public void write(char[] buf, int off, int len) { add(new String(buf, off, len)); }
        @Override public void write(String s, int off, int len) { add(off == 0 && len == s.length() ? s : s.substring(off, off + len)); }
        @Override public void write(String s) { add(s); }
        @Override public void flush() { }
        @Override public void close() { }

        private void add(String s) {
            synchronized (LuaFiles.class) {
                if (e.size() + s.length() > MAX_HELD_CHARS) writeEntry(e, System.nanoTime());     // the cap is a memory bound: write what is held now
                if (e.base != null) e.base.append(s);
                else {
                    if (e.tail == null) {                 // written after a flush: due at the next tick
                        e.tail = new StringBuilder();
                        e.dueNs = System.nanoTime();
                        if (e.dueNs < wake) wake = e.dueNs;
                    }
                    e.tail.append(s);
                }
            }
        }
    }
}
