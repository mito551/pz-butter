package wkt.perf;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Thread-safe deduplicating buffer capped at {@link #CAP}. */
public final class SeenBuffer<T> {
    public static final int CAP = 4096;

    private final LinkedHashSet<T> items = new LinkedHashSet<>();

    public SeenBuffer() {}

    /** @return false if the item is null, already present, or the buffer is full. */
    public synchronized boolean add(T item) {
        if (item == null) return false;
        if (items.size() >= CAP) return false;
        return items.add(item);
    }

    /** @return the buffered items in insertion order, or null when empty. */
    public synchronized List<T> drain() {
        if (items.isEmpty()) return null;
        List<T> out = new ArrayList<>(items);
        items.clear();
        return out;
    }
}
