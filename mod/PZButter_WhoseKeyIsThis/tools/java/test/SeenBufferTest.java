import wkt.perf.SeenBuffer;

import java.util.List;

public class SeenBufferTest {
    public static void main(String[] args) {
        SeenBuffer<String> b = new SeenBuffer<>();
        if (!b.add("a")) throw new AssertionError("first add");
        if (b.add("a")) throw new AssertionError("duplicate accepted");
        int legacyCap = 4096;
        for (int i = 0; i < legacyCap - 1; i++) {
            if (!b.add("v" + i)) throw new AssertionError("fill " + i);
        }
        if (!b.add("z")) throw new AssertionError("entry beyond legacy cap dropped");
        List<String> d = b.drain();
        if (d == null || d.size() != legacyCap + 1) throw new AssertionError("drain size");
        if (!d.get(0).equals("a")) throw new AssertionError("order 0");
        if (!d.get(legacyCap).equals("z")) throw new AssertionError("last entry");
        if (b.drain() != null) throw new AssertionError("second drain");
        if (!b.add("x")) throw new AssertionError("post-drain add");
        System.out.println("SeenBufferTest OK");
    }
}
