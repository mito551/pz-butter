package pzbutter;

/** windowPump (SPEC, Windows): while the game thread holds a frame for 300 ms during the join, a daemon thread
 *  pumps the OS message queue so the window does not turn "Not Responding"; stops at the join's end, menu or
 *  after 6 min, ignores close requests during the hold. Linux and macOS do nothing. Untested on Windows. */
public final class WindowPump {
    private WindowPump() {}

    public static final Guard guard = new Guard("windowPump");
    static final long HOLD_AFTER_MS = 300;
    static final long MAX_HOLD_MS = 6L * 60_000L;
    private static final long PUMP_SLEEP_MS = 20;
    /** test seam: production Thread::start. */
    public static volatile java.util.function.Consumer<Thread> LAUNCH = Thread::start;

    public static volatile long lastFrameMs;
    static volatile long holdStartMs;
    /** the daemon; null on Linux/macOS (or when JNA is missing). */
    static volatile Thread pump;

    public static void init() {
        try {
            guard.on = Settings.flag("windowPump");
        } catch (Throwable t) { guard.fail(t); return; }
        if (guard.on && windows() && jnaPresent()) start();
    }

    /** every finished frame marks the game thread alive (piggybacks ClockPatches' FPSTracking hook). */
    public static void mark() {
        if (guard.on) lastFrameMs = System.currentTimeMillis();
    }

    static boolean windows() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ENGLISH);
        return os.contains("win");
    }

    static boolean jnaPresent() {
        try { Class.forName("com.sun.jna.Native"); return true; } catch (Throwable t) { return false; }
    }

    static void start() {
        Thread t = new Thread(WindowPump::daemon, "pzbutter-windowPump");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        LAUNCH.accept(t);
        pump = t;
    }

    enum Cycle { IDLE, HOLD, EXIT }

    static Cycle cycle(long nowMs) {
        long last = lastFrameMs;
        if (last == 0) return Cycle.IDLE;
        if (nowMs - last < HOLD_AFTER_MS) { holdStartMs = 0; return Cycle.IDLE; }
        if (holdStartMs == 0) { holdStartMs = nowMs; return Cycle.HOLD; }
        return nowMs - holdStartMs >= MAX_HOLD_MS ? Cycle.EXIT : Cycle.HOLD;
    }

    static void closeReset() {
        try {
            if (zombie.GameWindow.closeRequested) zombie.GameWindow.closeRequested = false;
        } catch (Throwable ignored) {}
    }

    static boolean joining() {
        try { return zombie.gameStates.ConnectToServerState.instance != null; } catch (Throwable t) { return false; }
    }

    static void daemon() {
        while (guard.on) {
            try { Thread.sleep(PUMP_SLEEP_MS); } catch (InterruptedException ie) { return; }
            Cycle c;
            try { c = cycle(System.currentTimeMillis()); } catch (Throwable t) { guard.fail(t); return; }
            if (c == Cycle.EXIT) return;
            if (c != Cycle.HOLD || !joining()) continue;
            closeReset();
            try { Win.pump(lastFrameMs); } catch (Throwable t) { guard.fail(t); return; }
        }
    }

    /** JNA types load only when a pump actually runs (never on Linux headless). */
    static final class Win {
        interface User32 extends com.sun.jna.Library {
            User32 I = com.sun.jna.Native.load("user32", User32.class, com.sun.jna.win32.W32APIOptions.UNICODE_OPTIONS);
            boolean PeekMessage(MSG h, com.sun.jna.Pointer hwnd, int first, int last, int remove);
            boolean TranslateMessage(MSG h);
            void DispatchMessage(MSG h);
        }
        private static final int PM_REMOVE = 1;
        private static final int WM_CLOSE = 0x10;
        private static final int WM_QUIT = 0x12;

        static final class MSG extends com.sun.jna.Structure {
            public com.sun.jna.Pointer hwnd;
            public int message;
            public com.sun.jna.Pointer wParam;
            public com.sun.jna.Pointer lParam;
            public int time;
            public int ptX;
            public int ptY;
            @Override protected java.util.List<String> getFieldOrder() {
                return java.util.List.of("hwnd", "message", "wParam", "lParam", "time", "ptX", "ptY");
            }
        }

        static void pump(long lastFrameMs) {
            long hwnd = org.lwjglx.opengl.Display.getWindow();
            if (hwnd == 0) return;
            MSG m = new MSG();
            com.sun.jna.Pointer target = new com.sun.jna.Pointer(hwnd);
            while (User32.I.PeekMessage(m, target, 0, 0, PM_REMOVE)) {
                if (m.message == WM_CLOSE || m.message == WM_QUIT) continue; // ignore close during the hold
                User32.I.TranslateMessage(m);
                User32.I.DispatchMessage(m);
            }
        }
    }

    static long lastFrame() { return lastFrameMs; }
    static long holdStartedAt() { return holdStartMs; }
}
