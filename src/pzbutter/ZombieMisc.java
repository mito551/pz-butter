package pzbutter;

import java.lang.invoke.VarHandle;
import java.util.Map;

/**
 * statsNoBox (SPEC 4.7): a tiny allocation saver. Any thread. (SPEC 6.1's popmanFlagsFlyweight was built, measured and dropped.)
 * <p>
 * {@code Stats.get(stat)} is {@code stats.getOrDefault(stat, Float.valueOf(stat.getDefaultValue())).floatValue()}: the default is
 * boxed on every read. {@link #statsGet} reads the map itself and boxes nothing; the answer travels packed in a long.
 */
final class ZombieMisc {
    private ZombieMisc() {}

    static final Guard statsGuard = new Guard("statsNoBox");

    /** Stats.stats (private final Map). A static final in a holder, so the JIT folds the handle into the read (a plain static field is not). */
    private static final class StatsMap {
        static final VarHandle H = Game.field(zombie.characters.Stats.class, "stats", Map.class);
    }

    /** Bit 32 marks an answer; the low 32 bits are the float's bits. */
    private static final long ANSWER = 0x1_0000_0000L;

    /** getOrDefault's "key absent" answer: a Float no stat map holds. */
    private static final Float ABSENT = Float.valueOf(Float.NaN);

    static void init() {
        statsGuard.on = Settings.flag("statsNoBox");
        if (statsGuard.on && StatsMap.H == null) {
            statsGuard.on = false;
            System.out.println("[PZButter] statsNoBox off: Stats.stats not found");
        }
    }

    /**
     * The value {@code Stats.get(stat)} returns, as {@code 0x1_0000_0000 | floatBits}; -1 when stock has to run (a stat mapped to null,
     * where stock throws, or no stat).
     */
    static long statsGet(Object stats, Object stat) {
        if (stat == null) return -1;
        Map<Object, Object> m = statsMapOf((zombie.characters.Stats) stats);
        Object v = m.getOrDefault(stat, ABSENT);   // one lookup, as stock; ABSENT is built once, so nothing is boxed per read
        if (v == null) return -1;
        float f = v == ABSENT ? ((zombie.characters.CharacterStat) stat).getDefaultValue() : (Float) v;
        return ANSWER | (Float.floatToRawIntBits(f) & 0xFFFF_FFFFL);
    }

    @SuppressWarnings("unchecked")
    static Map<Object, Object> statsMapOf(zombie.characters.Stats stats) {
        return (Map<Object, Object>) StatsMap.H.get(stats);
    }

    static void resetForTest() {
        statsGuard.on = false;
    }
}
