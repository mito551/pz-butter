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
        if (System.getProperty("pzprobe.fail") != null) failCase(list);   // before any listed class is loaded here
        int have = 0;
        for (String c : list.classes()) {
            boolean ok = Preload.hasMarker(c, ClassLoader.getSystemClassLoader());
            if (ok) have++; else System.out.println("missing " + c);
        }
        System.out.println("markers=" + have + "/" + list.classes().size());
        System.out.println("status=" + Preload.status());
        System.out.println("census=" + Census.status());
    }

    /** Test only: installs over a list whose first class does not exist, so the throw comes after addTransformer. */
    static void failCase(WorkerList real) throws Exception {
        String rev = "revision " + real.revision();
        String text;
        try (var in = WorkerList.class.getResourceAsStream("/pzbutter/workers-" + real.revision() + ".txt")) {
            text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        text = text.replace(rev, rev + "\nclass no.such.Missing");   // the real list plus a missing first class
        Preload.state = Preload.State.NEEDS_RESTART;
        Preload.install(ZbAccess.instrumentation(), WorkerList.parse(text));
        System.out.println("fail.state=" + Preload.state);
        System.out.println("fail.reason=" + Preload.reason);
        String c = real.classes().get(0);
        boolean marked;
        try { Class.forName(c, false, ClassLoader.getSystemClassLoader()).getDeclaredField(WorkerTransform.MARKER); marked = true; }
        catch (NoSuchFieldException e) { marked = false; }
        System.out.println("fail.marker=" + marked);
    }
}
