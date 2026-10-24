package pzbutter;

import java.lang.invoke.VarHandle;
import java.util.Map;
import zombie.popman.ZombieStateFlags;

/**
 * statsNoBox (SPEC 4.7) and popmanFlagsFlyweight (SPEC 6.1): two tiny allocation savers. Any thread.
 * <p>
 * {@code Stats.get(stat)} is {@code stats.getOrDefault(stat, Float.valueOf(stat.getDefaultValue())).floatValue()}: the default is
 * boxed on every read. {@link #statsGet} reads the map itself and boxes nothing; the answer travels packed in a long.
 * {@code ZombieStateFlags.fromInt(v)} is {@code new ZombieStateFlags(v)}: for 0 to 63 the 64 possible objects are built once and
 * shared (no caller in the game stores one or changes one; research, mismatch 7).
 */
final class ZombieMisc {
    private ZombieMisc() {}

    static final Guard statsGuard = new Guard("statsNoBox");
    static final Guard flagsGuard = new Guard("popmanFlagsFlyweight");

    /** Stats.stats (private final Map). A static final in a holder, so the JIT folds the handle into the read (a plain static field is not). */
    private static final class StatsMap {
        static final VarHandle H = Game.field(zombie.characters.Stats.class, "stats", Map.class);
    }

    /** Bit 32 marks an answer; the low 32 bits are the float's bits. */
    private static final long ANSWER = 0x1_0000_0000L;

    /** getOrDefault's "key absent" answer: a Float no stat map holds. */
    private static final Float ABSENT = Float.valueOf(Float.NaN);

    /** The 64 shared flag objects; published by a volatile write once all are built. */
    private static volatile ZombieStateFlags[] shared;

    static void init() {
        statsGuard.on = Settings.flag("statsNoBox");
        flagsGuard.on = Settings.flag("popmanFlagsFlyweight");
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

    /** The shared object for a flag value 0 to 63; null (stock) for anything else or with the key off. */
    static ZombieStateFlags flags(int v) {
        if (!flagsGuard.on || v < 0 || v >= 64) return null;
        ZombieStateFlags[] s = shared;
        if (s == null) {
            s = new ZombieStateFlags[64];
            for (int i = 0; i < 64; i++) s[i] = new ZombieStateFlags(i);
            shared = s;
        }
        return s[v];
    }

    static void resetForTest() {
        statsGuard.on = flagsGuard.on = false;
        shared = null;
    }
}
