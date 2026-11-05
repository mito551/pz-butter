import wkt.perf.SeenBuffer;

import java.util.List;

public class SeenBufferTest {
    public static void main(String[] args) {
        SeenBuffer<String> b = new SeenBuffer<>();
        if (!b.add("a")) throw new AssertionError("first add");
        if (b.add("a")) throw new AssertionError("duplicate accepted");
        // fill to cap (one already present)
        for (int i = 0; i < SeenBuffer.CAP - 1; i++) {
            if (!b.add("v" + i)) throw new AssertionError("fill " + i);
        }
        if (b.add("z")) throw new AssertionError("overflow accepted");
        List<String> d = b.drain();
        if (d == null || d.size() != SeenBuffer.CAP) throw new AssertionError("drain size");
        if (!d.get(0).equals("a")) throw new AssertionError("order 0");
        if (b.drain() != null) throw new AssertionError("second drain");
        if (!b.add("x")) throw new AssertionError("post-drain add");
        System.out.println("SeenBufferTest OK");
    }
}
