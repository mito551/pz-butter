package pzbutter;

/**
 * Headless check for the ZB preload path (see harness/preload-check.sh). The JVM main class; the real PZButter.jar is
 * preloaded by ZombieBuddy, so Preload.premain has already run. Prints its state, reason and which listed classes carry the marker.
 */
public final class PreloadProbe {
    public static void main(String[] args) throws Exception {
        System.out.println("state=" + Preload.state);
        System.out.println("reason=" + Preload.reason);
        var list = WorkerList.load(Preload.gameRevision());
        int have = 0;
        for (String c : list.classes()) {
            boolean ok;
            try {
                Class.forName(c, false, ClassLoader.getSystemClassLoader()).getDeclaredField(WorkerTransform.MARKER);
                ok = true;
            } catch (NoSuchFieldException e) { ok = false; }
            if (ok) have++; else System.out.println("missing " + c);
        }
        System.out.println("markers=" + have + "/" + list.classes().size());
        System.out.println("status=" + Preload.status());
    }
}
