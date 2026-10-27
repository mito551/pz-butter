package pzbutter;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * corePlacement: on hybrid CPUs, latency threads restrict themselves to the performance cores.
 * Windows CPU-set facts from Microsoft documentation:
 *   "SYSTEM_CPU_SET_INFORMATION structure" (learn.microsoft.com) - entry layout, Size @0, Type @4,
 *     CpuSet.Id @8, Group @12, LogicalProcessorIndex @14, CoreIndex @15, LastLevelCacheIndex @16,
 *     NumaNodeIndex @17, EfficiencyClass @18, AllFlags @19, Reserved/SchedulingClass @20, AllocationTag @24 (32 bytes total, x64).
 *   "GetSystemCpuSetInformation function" - call once with Information=NULL, BufferLength=0 to get ReturnedLength
 *     (returns FALSE, ERROR_INSUFFICIENT_BUFFER), allocate that many bytes, call again with Process=NULL, Flags=0.
 *   "SetThreadSelectedCpuSets function" - BOOL SetThreadSelectedCpuSets(HANDLE Thread, const ULONG* CpuSetIds, ULONG CpuSetIdCount).
 *   "GetCurrentThread function" - HANDLE GetCurrentThread(void) returns a pseudo-handle.
 *   Higher EfficiencyClass = more performance; if all entries share one EfficiencyClass the CPU is not hybrid.
 *   Both GetSystemCpuSetInformation and SetThreadSelectedCpuSets (and GetCurrentThread) live in kernel32.
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
        String os = System.getProperty("os.name", "").toLowerCase();
        if (guard.on) {
            try {
                if (os.contains("linux")) {
                    cpus = performanceLinux(Paths.get("/"));
                    if (cpus == null) { reason = "not hybrid"; guard.on = false; }
                } else if (os.contains("windows")) {
                    int[] ids = windowsPerformanceIdsNative();
                    if (ids == null) { reason = "not hybrid"; guard.on = false; }
                    else {
                        BitSet bs = new BitSet();
                        for (int id : ids) bs.set(id);
                        cpus = bs;
                    }
                } else {
                    reason = "unsupported os";
                    guard.on = false;
                }
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

    /** Parse a Windows SYSTEM_CPU_SET_INFORMATION buffer into the performance (highest EfficiencyClass) set ids. */
    public static int[] performanceIdsWindows(MemorySegment buf, long len) {
        if (buf == null || len <= 0) return null;
        List<int[]> entries = new ArrayList<>();
        long pos = 0;
        int maxEff = -1;
        while (pos + 4 <= len) {
            int size = buf.get(JAVA_INT, pos);
            if (size <= 0) break;
            if (pos + size > len) break;
            int type = buf.get(JAVA_INT, pos + 4);
            if (type == 0) {
                int id = buf.get(JAVA_INT, pos + 8);
                byte effB = buf.get(JAVA_BYTE, pos + 18);
                int eff = effB & 0xFF;
                entries.add(new int[]{id, eff});
                if (eff > maxEff) maxEff = eff;
            }
            pos += size;
        }
        if (entries.isEmpty()) return null;
        boolean allSame = true;
        int first = entries.get(0)[1];
        for (int[] e : entries) if (e[1] != first) { allSame = false; break; }
        if (allSame) return null;
        List<Integer> res = new ArrayList<>();
        for (int[] e : entries) if (e[1] == maxEff) res.add(e[0]);
        if (res.isEmpty()) return null;
        int[] arr = new int[res.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = res.get(i);
        return arr;
    }

    private static int[] windowsPerformanceIdsNative() {
        try (Arena arena = Arena.ofConfined()) {
            Linker linker = Linker.nativeLinker();
            SymbolLookup k32 = linker.defaultLookup();
            nativeLookups++;
            if (k32.find("GetSystemCpuSetInformation").isEmpty()) {
                nativeLookups++;
                k32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
            }
            var opt = k32.find("GetSystemCpuSetInformation");
            nativeLookups++;
            if (opt.isEmpty()) return null;
            MethodHandle get = linker.downcallHandle(opt.get(),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
            MemorySegment returned = arena.allocate(JAVA_INT);
            int rc = (int) get.invokeExact(MemorySegment.NULL, 0, returned, MemorySegment.NULL, 0);
            int len = returned.get(JAVA_INT, 0);
            if (len <= 0) return null;
            MemorySegment buf = arena.allocate(len);
            returned.set(JAVA_INT, 0, 0);
            rc = (int) get.invokeExact(buf, len, returned, MemorySegment.NULL, 0);
            if (rc == 0) return null;
            int actual = returned.get(JAVA_INT, 0);
            if (actual <= 0) actual = len;
            return performanceIdsWindows(buf, actual);
        } catch (Throwable t) { return null; }
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
                int[] ids = windowsPerformanceIdsNative();
                if (ids == null || ids.length == 0) return false;
                SymbolLookup k32 = linker.defaultLookup();
                nativeLookups++;
                if (k32.find("SetThreadSelectedCpuSets").isEmpty()) {
                    nativeLookups++;
                    k32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
                }
                var optSet = k32.find("SetThreadSelectedCpuSets");
                nativeLookups++;
                var optGet = k32.find("GetCurrentThread");
                nativeLookups++;
                if (optSet.isEmpty() || optGet.isEmpty()) return false;
                MethodHandle setSets = linker.downcallHandle(optSet.get(),
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
                MethodHandle getCurrent = linker.downcallHandle(optGet.get(),
                        FunctionDescriptor.of(ADDRESS));
                MemorySegment thread = (MemorySegment) getCurrent.invokeExact();
                MemorySegment idBuf = arena.allocate(JAVA_INT, ids.length);
                for (int i = 0; i < ids.length; i++) idBuf.setAtIndex(JAVA_INT, i, ids[i]);
                int rc = (int) setSets.invokeExact(thread, idBuf, ids.length);
                return rc != 0;
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
