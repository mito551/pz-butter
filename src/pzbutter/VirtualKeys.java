package pzbutter;

/** In-memory held movement keys, answered true by the GameKeyboard.isKeyDown holder. */
public final class VirtualKeys {
    private VirtualKeys() {}

    static final int SIZE = 512;
    static final boolean[] held = new boolean[SIZE];
    public static volatile int heldCount = 0;

    public static void hold(int code) {
        if (code < 0 || code >= held.length) return;
        synchronized (held) {
            if (!held[code]) {
                held[code] = true;
                heldCount++;
            }
        }
    }

    public static void release(int code) {
        if (code < 0 || code >= held.length) return;
        synchronized (held) {
            if (held[code]) {
                held[code] = false;
                if (heldCount > 0) heldCount--;
            }
        }
    }

    public static void releaseAll() {
        synchronized (held) {
            for (int i = 0; i < held.length; i++) held[i] = false;
            heldCount = 0;
        }
    }

    public static boolean held(int code) {
        if (code < 0 || code >= held.length) return false;
        synchronized (held) {
            return held[code];
        }
    }
}
