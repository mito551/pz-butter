package pzbutter;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.BitSet;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * corePlacement: on hybrid CPUs, latency threads restrict themselves to the performance cores.
 */
public final class Cores {
    public static final Guard guard = new Guard("corePlacement");

    /** Native symbol lookups performed by this class (R5); stays 0 when the key is off. */
    public static volatile int nativeLookups;

    /** Mask of performance cores detected at init (null when not hybrid). */
    static volatile BitSet performanceCores;

    /** Per-thread pin flag. */
    private static final ThreadLocal<Boolean> pinned = ThreadLocal.withInitial(() -> Boolean.FALSE);

    static void init() {
        guard.on = Settings.flag("corePlacement");
        BitSet cpus = null;
        String reason = "disabled";
        if (guard.on) {
            try {
                cpus = performanceLinux(Paths.get("/"));
                if (cpus == null) { reason = "not hybrid"; guard.on = false; }
            } catch (Throwable t) { reason = t.toString(); guard.on = false; }
        }
        performanceCores = cpus;
        if (guard.on) System.out.println("[PZButter] corePlacement: on (performance cores " + format(cpus) + ")");
        else System.out.println("[PZButter] corePlacement: off (" + (cpus == null && reason.equals("disabled") ? "disabled" : reason) + ")");
    }

    public static void pinOnce() {
        if (pinned.get()) return;
        pinned.set(Boolean.TRUE);
        if (!guard.on || performanceCores == null) return;
        if (!pinSelf(performanceCores)) {
            guard.on = false;
            System.out.println("[PZButter] corePlacement: off (pin failed)");
        }
    }

    static String format(BitSet b) {
        if (b == null || b.isEmpty()) return "";
        StringBuilder s = new StringBuilder();
        int i = b.nextSetBit(0);
        while (i >= 0) {
            int start = i;
            int prev = i;
            i = b.nextSetBit(i + 1);
            while (i == prev + 1) { prev = i; i = b.nextSetBit(i + 1); }
            if (s.length() > 0) s.append(',');
            if (start == prev) s.append(start); else s.append(start).append('-').append(prev);
            if (i >= 0 && i != prev + 1) { /* break already */ }
        }
        // simpler fallback range collapse above may leave gaps; redo cleanly:
        return formatClean(b);
    }

    private static String formatClean(BitSet b) {
        StringBuilder s = new StringBuilder();
        int i = b.nextSetBit(0);
        while (i >= 0) {
            int start = i, end = i;
            int j = b.nextSetBit(i + 1);
            while (j == end + 1) { end = j; j = b.nextSetBit(j + 1); }
            if (s.length() > 0) s.append(',');
            if (start == end) s.append(start); else s.append(start).append('-').append(end);
            i = j;
        }
        return s.toString();
    }

    public static BitSet parseList(String s) {
        BitSet r = new BitSet();
        if (s == null) return r;
        for (String part : s.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            if (p.contains("-")) {
                String[] ab = p.split("-", 2);
                int from = Integer.parseInt(ab[0].trim());
                int to = Integer.parseInt(ab[1].trim());
                for (int i = from; i <= to; i++) if (i >= 0) r.set(i);
            } else {
                int v = Integer.parseInt(p);
                if (v >= 0) r.set(v);
            }
        }
        return r;
    }

    public static BitSet performanceLinux(Path sysRoot) {
        if (sysRoot == null) return null;
        // intel hybrid
        Path core = firstExisting(sysRoot, "devices/cpu_core/cpus", "cpu_core/cpus");
        Path atom = firstExisting(sysRoot, "devices/cpu_atom/cpus", "cpu_atom/cpus");
        if (core != null && atom != null) {
            try {
                String cs = Files.readString(core).trim();
                String as = Files.readString(atom).trim();
                if (!cs.isEmpty() && !as.isEmpty()) return parseList(cs);
            } catch (IOException e) { /* fall through */ }
        }
        // capacities
        Path cpuDir = firstExisting(sysRoot, "devices/system/cpu", "system/cpu", ".");
        if (cpuDir == null) cpuDir = sysRoot;
        int maxCap = -1;
        List<Path> cpus = new ArrayList<>();
        try (var stream = Files.list(cpuDir)) {
            stream.filter(p -> p.getFileName() != null && p.getFileName().toString().matches("cpu\\d+"))
                  .sorted().forEach(cpus::add);
        } catch (IOException e) { return null; }
        if (cpus.isEmpty()) {
            // maybe cpu dirs directly under sysRoot/system? try
        }
        int[] caps = new int[cpus.size()];
        boolean any = false;
        for (int i = 0; i < cpus.size(); i++) {
            Path cf = cpus.get(i).resolve("cpu_capacity");
            try { caps[i] = Integer.parseInt(Files.readString(cf).trim()); any = true; }
            catch (Exception e) { caps[i] = -1; }
        }
        if (!any) return null;
        for (int c : caps) if (c > maxCap) maxCap = c;
        int minCap = Integer.MAX_VALUE;
        for (int c : caps) if (c >= 0 && c < minCap) minCap = c;
        if (maxCap <= 0 || maxCap == minCap) return null;
        BitSet r = new BitSet();
        for (int i = 0; i < caps.length; i++) if (caps[i] == maxCap) r.set(i);
        // however index should be cpu number, not position; derive cpu number from dir name
        // rebuild with actual cpu indices
        r.clear();
        for (int i = 0; i < cpus.size(); i++) {
            if (caps[i] != maxCap) continue;
            String name = cpus.get(i).getFileName().toString();
            int idx = Integer.parseInt(name.substring(3));
            r.set(idx);
        }
        return r.isEmpty() ? null : r;
    }

    private static Path firstExisting(Path root, String... rels) {
        for (String r : rels) {
            Path p = root.resolve(r);
            if (Files.exists(p)) return p;
        }
        return null;
    }

    public static boolean pinSelf(BitSet cpus) {
        if (cpus == null) return false;
        String os = System.getProperty("os.name", "").toLowerCase();
        Linker linker = Linker.nativeLinker();
        if (os.contains("linux")) {
            try (Arena arena = Arena.ofConfined()) {
                SymbolLookup libc = linker.defaultLookup();
                nativeLookups++;
                var fh = libc.find("sched_setaffinity");
                if (fh.isEmpty()) return false;
                MethodHandle h = linker.downcallHandle(fh.get(), FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG, ADDRESS));
                MemorySegment mask = arena.allocate(128);
                // zero
                for (int i = 0; i < 16; i++) mask.setAtIndex(JAVA_LONG, i, 0L);
                for (int c = cpus.nextSetBit(0); c >= 0; c = cpus.nextSetBit(c + 1)) {
                    if (c < 1024) mask.setAtIndex(JAVA_LONG, c >>> 6, mask.getAtIndex(JAVA_LONG, c >>> 6) | (1L << (c & 63)));
                }
                int rc = (int) h.invokeExact(0, 128L, mask);
                return rc == 0;
            } catch (Throwable t) { return false; }
        }
        if (os.contains("windows")) {
            try (Arena arena = Arena.ofConfined()) {
                SymbolLookup k32 = linker.defaultLookup();
                nativeLookups++;
                if (k32.find("GetSystemCpuSetInformation").isEmpty()) {
                    nativeLookups++;
                    k32 = SymbolLookup.libraryLookup("kernel32", arena);
                }
                nativeLookups++;
                if (k32.find("GetSystemCpuSetInformation").isEmpty()) return false;
                nativeLookups++;
                if (k32.find("SetThreadSelectedCpuSets").isEmpty()) return false;
                nativeLookups++;
                if (k32.find("GetCurrentThread").isEmpty()) return false;
                return false;
            } catch (Throwable t) { return false; }
        }
        return false;
    }

    public static void resetForTest() {
        guard.on = false;
        performanceCores = null;
        pinned.remove();
        nativeLookups = 0;
    }

    private Cores() {}
}
