import ds.perf.DamnSpawns;
import java.util.*;
public class SetCheck {
    public static void main(String[] a) {
        Random r = new Random(1);
        Set<Long> ref = new HashSet<>();
        for (int i = 0; i < 5000; i++) {
            int x = 1 + r.nextInt(20000), y = 1 + r.nextInt(20000);
            DamnSpawns.register(x, y); DamnSpawns.register(x + 0.7, y); // duplicate must not count twice
            ref.add(((long) x << 32) | y);
        }
        if (DamnSpawns.count() != ref.size()) throw new AssertionError("count " + DamnSpawns.count() + " vs " + ref.size());
        for (long k : ref) if (!DamnSpawns.isTarget(k >> 32, k & 0xFFFFFFFFL)) throw new AssertionError("miss");
        for (int i = 0; i < 200000; i++) {
            int x = 1 + r.nextInt(20000), y = 1 + r.nextInt(20000);
            if (DamnSpawns.isTarget(x, y) != ref.contains(((long) x << 32) | y)) throw new AssertionError("mismatch");
        }
        DamnSpawns.clear();
        if (DamnSpawns.count() != 0 || DamnSpawns.isTarget(1, 1)) throw new AssertionError("clear");
        System.out.println("java ok");
    }
}
