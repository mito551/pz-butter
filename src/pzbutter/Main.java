package pzbutter;

public class Main {
    public static void main(String[] args) {
        Settings.load();
        FrameClock.init();
        PresentLog.init();
        LimiterSleep.init();
        RenderIdle.init();
        InputLatch.init();
        System.out.println("[PZButter] " + Settings.VERSION + ", enabled=" + Settings.enabled);
        if (Settings.enabled) initIfPresent("pzbutter.luabudget.Budget");   // after tools/merge-luabudget.sh
    }

    /** Runs a merged module's static init() when its class is in the jar. True when it ran. */
    static boolean initIfPresent(String className) {
        Class<?> c;
        try { c = Class.forName(className); } catch (ClassNotFoundException e) { return false; }
        try {
            c.getMethod("init").invoke(null);
            return true;
        } catch (Throwable t) {
            System.out.println("[PZButter] " + className + ".init() failed: " + t);
            return false;
        }
    }
}
