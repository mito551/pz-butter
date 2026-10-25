package pzbutter;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.Function;

public final class Census {
    public static final Guard guard = new Guard("devCensus");
    static final long MINUTE_NS = 60_000_000_000L;

    static volatile Thread gameThread;
    static CensusSites sites;
    static volatile long[] calm;
    static volatile long[] other;
    static int calmDepth;
    static int otherDepth;
    static long calmUpdates;
    static long otherUpdates;
    static long minutes;
    static long minuteStartNs;
    static boolean started;

    static File dir = new File(System.getProperty("user.home"), "Zomboid/pzbutter");
    static Function<Object, Calm.Facts> facts = Calm::of;

    static {
        resetForTest();
    }

    public static void install(java.lang.instrument.Instrumentation inst) {
        try {
            if (inst == null) return;
            String rev = Preload.gameRevision();
            CensusSites s = (rev == null || rev.isEmpty()) ? null : CensusSites.load(rev);
            if (s == null) return;
            sites = s;
            allocate(s.size());
            CensusTransform t = new CensusTransform(s, null);
            inst.addTransformer(t, false);
            for (Class<?> c : inst.getAllLoadedClasses()) {
                if (s.listed(c.getName().replace('.', '/'))) CensusTransform.missOne();
            }
            guard.on = true;
        } catch (Throwable t) {
            guard.fail(t);
        }
    }

    public static void hit(int id) {
        if (Thread.currentThread() != gameThread) return;
        if (calmDepth > 0) {
            long[] a = calm;
            if (a != null && id >= 0 && id < a.length) a[id]++;
        } else if (otherDepth > 0) {
            long[] a = other;
            if (a != null && id >= 0 && id < a.length) a[id]++;
        }
    }

    public static void enter(Object self) {
        Thread t = Thread.currentThread();
        if (gameThread == null) gameThread = t;
        // only proceed on game thread? tests run on test thread which becomes game thread.
        Calm.Facts f = null;
        try { f = facts.apply(self); } catch (Throwable e) { f = new Calm.Facts(false, false, false, false, false,false,false,false,false,false,false,false,false,false,false, Float.MAX_VALUE); }
        boolean calm;
        try { calm = Calm.test(f); } catch (Throwable e) { calm = false; }
        if (calmDepth == 0 && otherDepth == 0) {
            if (calm) { calmDepth = 1; calmUpdates++; }
            else { otherDepth = 1; otherUpdates++; }
        } else {
            if (calmDepth > 0) calmDepth++;
            else otherDepth++;
        }
    }

    public static void exit(Object self) {
        if (calmDepth > 0) {
            if (--calmDepth < 0) calmDepth = 0;
        } else if (otherDepth > 0) {
            if (--otherDepth < 0) otherDepth = 0;
        }
    }

    public static void frameEnd(long nowNs) {
        try {
            if (!started) { started = true; minuteStartNs = nowNs; return; }
            if (nowNs - minuteStartNs < MINUTE_NS) return;
            minuteStartNs = nowNs;
            minutes++;
            if (dir != null) dir.mkdirs();
            File f = new File(dir, "census.out");
            try (FileWriter w = new FileWriter(f, StandardCharsets.UTF_8, false)) {
                CensusSites s = sites;
                if (s == null) s = CensusSites.load("4a0e9546ec");
                StringBuilder hdr = new StringBuilder();
                hdr.append("revision ").append(s == null ? "" : s.revision()).append(" minutes ").append(minutes)
                    .append(" calmUpdates ").append(calmUpdates).append(" otherUpdates ").append(otherUpdates)
                    .append(" missed ").append(CensusTransform.missed());
                w.write(hdr.toString());
                w.write("\n");
                if (s != null) {
                    long[] c = calm, o = other;
                    for (int i = 0; i < s.size(); i++) {
                        long ch = (c != null && i < c.length) ? c[i] : 0L;
                        long oh = (o != null && i < o.length) ? o[i] : 0L;
                        if (ch == 0 && oh == 0) continue;
                        CensusSites.Site st = s.site(i);
                        w.write(i + " " + st.family() + " " + ch + " " + oh + " " + st.owner() + " " + st.method() + " " + st.offset() + " " + st.target());
                        w.write("\n");
                    }
                }
            }
        } catch (Throwable t) {
            guard.fail(t);
        }
    }

    static void resetForTest() {
        guard.on = false;
        gameThread = null;
        calmDepth = 0;
        otherDepth = 0;
        calmUpdates = 0;
        otherUpdates = 0;
        minutes = 0;
        minuteStartNs = 0;
        started = false;
        if (sites == null) sites = CensusSites.load("4a0e9546ec");
        if (sites == null) {
            calm = new long[0];
            other = new long[0];
        } else {
            allocate(sites.size());
        }
        // reset transform miss counter
        try { CensusTransform.resetMissed(); } catch (Throwable ignored) {}
    }

    static void allocate(int n) {
        if (n < 0) n = 0;
        calm = new long[n];
        other = new long[n];
    }
}