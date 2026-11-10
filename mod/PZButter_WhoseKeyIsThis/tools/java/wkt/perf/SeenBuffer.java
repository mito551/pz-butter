package wkt.perf;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Thread-safe deduplicating buffer; entries remain until drained. */
public final class SeenBuffer<T> {
    // ponytail: unbounded between drains; revisit only if a caller can stop draining.
    private final LinkedHashSet<T> items = new LinkedHashSet<>();

    public SeenBuffer() {}

    /** @return false if the item is null or already present. */
    public synchronized boolean add(T item) {
        if (item == null) return false;
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
