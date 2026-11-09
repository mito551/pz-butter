package pzbutter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PZBClaim {
    public interface Installer { void run(); }
    public interface StandDown { void run(String winner); }

    private static final class Entry {
        final String section, mod;
        final int priority;
        final Installer install;
        final StandDown standDown;
        Entry(String s, String m, int p, Installer i, StandDown sd) {
            section = s; mod = m; priority = p; install = i; standDown = sd;
        }
    }

    private static final Map<String, Map<String, Integer>> PRIORITY = new HashMap<>();
    static {
        // authoritative section -> mod -> priority (index/shared plan C1). Omitted (section, mod) pairs fall back to 0.
        put("state:cursor.visibility", "core CursorLatch", 100);
        put("state:cursor.visibility", "XEG", 60);
        put("state:cursor.visibility", "VQOL", 40);
        put("state:frame.id", "core", 100);
        put("state:frame.id", "XEG", 60);
        put("state:frame.id", "QoLC", 30);
        put("state:frame.phase", "core", 100);
        put("state:handler.stride", "core LuaBudget", 100);
        put("state:handler.stride", "ISnow", 60);
        put("event:LoadGridsquare", "core mask dispatcher", 100);
        put("event:LoadGridsquare", "mod wrapper", 40);
        put("data:Distributions", "core roll-time insertion", 100);
        put("data:Distributions", "LTPE-2 index", 80);
        put("data:Distributions", "LTPE-1", 60);
        put("state:wind.field", "WindSway", 60);
        put("java:pzbutter", "core", 100);
    }

    private static void put(String section, String mod, int p) {
        PRIORITY.computeIfAbsent(section, k -> new HashMap<>()).put(mod, p);
    }

    private static int priorityFor(String section, String mod, int fallback) {
        Map<String, Integer> m = PRIORITY.get(section);
        Integer v = m == null ? null : m.get(mod);
        return v == null ? fallback : v;
    }

    private static final Map<String, List<Entry>> CLAIMS = new HashMap<>();
    private static final Map<String, String> OWNER = new HashMap<>();
    private static final List<String> LOG = new ArrayList<>();
    private static boolean resolved;

    private PZBClaim() {}

    public static synchronized boolean claim(String section, String mod, int priority, Installer install, StandDown standDown) {
        Entry e = new Entry(section, mod, priority, install, standDown);
        CLAIMS.computeIfAbsent(section, k -> new ArrayList<>()).add(e);
        if (resolved) {
            String w = OWNER.get(section);
            if (w == null) {
                OWNER.put(section, e.mod);
                if (e.install != null) try { e.install.run(); } catch (Throwable t) {}
            } else if (!w.equals(mod)) {
                if (e.standDown != null) try { e.standDown.run(w); } catch (Throwable t) {}
            }
            return w == null || w.equals(mod);
        }
        return true;
    }

    public static synchronized boolean claim(String section, String mod, Installer install, StandDown standDown) {
        return claim(section, mod, priorityFor(section, mod, 0), install, standDown);
    }

    public static synchronized void resolve() {
        if (resolved) return;
        for (String section : new ArrayList<>(CLAIMS.keySet())) {
            List<Entry> list = CLAIMS.get(section);
            if (list == null || list.isEmpty()) continue;
            Entry winner = null;
            for (Entry e : list) {
                if (winner == null || e.priority > winner.priority
                        || (e.priority == winner.priority && e.mod.compareTo(winner.mod) < 0)) {
                    winner = e;
                }
            }
            final Entry w = winner;
            OWNER.put(section, w.mod);
            if (w.install != null) try { w.install.run(); } catch (Throwable t) {}
            StringBuilder losers = new StringBuilder();
            for (Entry e : list) {
                if (e == w) continue;
                if (losers.length() > 0) losers.append(", ");
                losers.append(e.mod);
                if (e.standDown != null) try { e.standDown.run(w.mod); } catch (Throwable t) {}
            }
            LOG.add("[PZB-claim] " + section + ": " + w.mod + (losers.length() > 0 ? " over " + losers : ""));
        }
        resolved = true;
    }

    public static synchronized String owner(String section) {
        return OWNER.get(section);
    }

    public static synchronized void resetForTest() {
        CLAIMS.clear();
        OWNER.clear();
        LOG.clear();
        resolved = false;
    }
}