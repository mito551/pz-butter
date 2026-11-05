package cd.perf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Weak-keyed, synchronized store of up to 128 values per key, with a begin stamp.
 * Used by Spotted to remember zombies an animal perceived during its last updateLOS.
 */
public class SpotBook<K, V> {
    public static final int CAP = 128;

    public SpotBook() {}

    private static final class Entry<V> {
        boolean watched;
        boolean begun;
        long stamp;
        final List<V> values = new ArrayList<>();
    }

    private final Map<K, Entry<V>> map = Collections.synchronizedMap(new WeakHashMap<>());

    public void watch(K key, boolean on) {
        if (key == null) return;
        synchronized (map) {
            if (on) {
                Entry<V> e = map.get(key);
                if (e == null) {
                    e = new Entry<>();
                    map.put(key, e);
                }
                e.watched = true;
            } else {
                map.remove(key);
            }
        }
    }

    /** Starts a new record for a watched key; false (and nothing done) when not watched. */
    public boolean begin(K key, long nowMs) {
        if (key == null) return false;
        synchronized (map) {
            Entry<V> e = map.get(key);
            if (e == null || !e.watched) return false;
            e.values.clear();
            e.stamp = nowMs;
            e.begun = true;
            return true;
        }
    }

    public void add(K key, V value) {
        if (key == null) return;
        synchronized (map) {
            Entry<V> e = map.get(key);
            if (e == null || !e.watched || !e.begun) return;
            if (e.values.size() >= CAP) return;
            e.values.add(value);
        }
    }

    public List<V> get(K key) {
        if (key == null) return null;
        synchronized (map) {
            Entry<V> e = map.get(key);
            if (e == null || !e.watched || !e.begun) return null;
            return new ArrayList<>(e.values);
        }
    }

    public long stamp(K key) {
        if (key == null) return 0L;
        synchronized (map) {
            Entry<V> e = map.get(key);
            if (e == null) return 0L;
            return e.stamp;
        }
    }
}
