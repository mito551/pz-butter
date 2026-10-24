package pzbutter;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * logDedupe hooks: the two line funnels of {@code DebugLogStream}, one holder per game method (advice is inlined into the game class,
 * so it reaches only public members). A skipped line costs nothing further, not even the caller-prefix stack walk.
 */
public final class LogPatches {
    private LogPatches() {}

    /** A line is about to print: true drops it (a repeat inside the window). Any thread. */
    public static boolean onLine(Object stream, Object severity, Object msg, Object[] args) {
        if (!LogDedupe.guard.on) return false;
        try {
            // a line the stream's level filters out prints nothing in stock: it must not start a summary either
            if (stream instanceof zombie.debug.DebugLogStream d && severity instanceof zombie.debug.LogSeverity v && !d.isLogEnabled(v)) return false;
            long now = System.nanoTime();
            if (LogDedupe.due(now)) printSummaries(now);
            return LogDedupe.drop(stream, severity instanceof Enum<?> e ? e.ordinal() : -1, msg, args, now);
        } catch (Throwable t) {
            LogDedupe.guard.fail(t);
            return false;
        }
    }

    /** One summary per repeated message, on the stream of its first line; the stream's own hook lets them through. */
    static void printSummaries(long now) {
        for (Object[] s : LogDedupe.roll(now)) {
            try { ((java.io.PrintStream) s[0]).println((String) s[1]); }
            catch (Throwable t) { LogDedupe.guard.fail(t); }
        }
    }

    /** GameWindow.frameStep exit: a window that ended with no line after it still gets its summaries. */
    public static void onFrameEnd() {
        if (!LogDedupe.guard.on) return;
        try {
            long now = System.nanoTime();
            if (LogDedupe.due(now)) printSummaries(now);
        } catch (Throwable t) { LogDedupe.guard.fail(t); }
    }

    /** {@code writeln(PrintStream, LogSeverity, Object, Object...)}: every println of a DebugLogStream ends here. */
    @Patch(className = "zombie.debug.DebugLogStream", methodName = "writeln")
    public static class Writeln {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object self, @Patch.Argument(1) zombie.debug.LogSeverity sev,
                                    @Patch.Argument(2) Object msg, @Patch.Argument(3) Object[] args) {
            return onLine(self, sev, msg, args);
        }
    }

    /** {@code writeWithCallerPrefixln(PrintStream, LogSeverity, int, boolean, Object, Object...)}: warn, error, debugln with a prefix. */
    @Patch(className = "zombie.debug.DebugLogStream", methodName = "writeWithCallerPrefixln")
    public static class PrefixLine {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object self, @Patch.Argument(1) zombie.debug.LogSeverity sev,
                                    @Patch.Argument(4) Object msg, @Patch.Argument(5) Object[] args) {
            return onLine(self, sev, msg, args);
        }
    }
}
