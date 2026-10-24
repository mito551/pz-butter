package pzbutter;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * logDedupe hooks: the two line funnels of {@code DebugLogStream}, one holder per game method (advice is inlined into the game class,
 * so it reaches only public members). A skipped line costs nothing further, not even the caller-prefix stack walk.
 */
public final class LogPatches {
    private LogPatches() {}

    /**
     * A line is about to print: true drops it (a repeat inside the window). Any thread; it only counts, the summaries leave from
     * {@link #onFrameEnd} (game thread), so a burst of them never lands on the render or a worker thread.
     * {@code repeatable} false is a once-line ({@code warnOnce}, {@code debugOnceln}): stock itself prints it once per JVM and
     * drops every later one, so it is neither recorded nor counted here (a summary would announce repeats stock never shows).
     */
    public static boolean onLine(Object stream, Object severity, Object msg, Object[] args, boolean repeatable) {
        if (!repeatable || !LogDedupe.guard.on) return false;
        try {
            // a line the stream's level filters out prints nothing in stock: it must not start a summary either
            if (stream instanceof zombie.debug.DebugLogStream d && severity instanceof zombie.debug.LogSeverity v && !d.isLogEnabled(v)) return false;
            return LogDedupe.drop(stream, severity instanceof Enum<?> e ? e.ordinal() : -1, msg, args, System.nanoTime());
        } catch (Throwable t) {
            LogDedupe.guard.fail(t);
            return false;
        }
    }

    /** DebugLogStream.writeln(PrintStream, LogSeverity, Object, Object...), protected: bound once, null when the game has no such member. */
    private static final class WriteHandle {
        static final java.lang.invoke.MethodHandle H = Game.method(zombie.debug.DebugLogStream.class, "writeln",
            java.lang.invoke.MethodType.methodType(void.class, java.io.PrintStream.class, zombie.debug.LogSeverity.class, Object.class, Object[].class));
    }

    /**
     * One summary per repeated message, at the severity and on the print stream of its first line (a stream whose level is above
     * General would filter a General summary away), through the stream's own writeln: its hook lets the summary through.
     */
    static void printSummaries(long now) {
        for (Object[] s : LogDedupe.roll(now)) {
            try { printSummary(s[0], (Integer) s[2], (String) s[1]); }
            catch (Throwable t) { LogDedupe.guard.fail(t); }
        }
    }

    /** Where a summary goes; a test swaps it (the game's writeln formats through classes that need a running game). */
    interface Sink { void write(zombie.debug.DebugLogStream d, java.io.PrintStream ps, zombie.debug.LogSeverity v, String text) throws Throwable; }
    static Sink sink = (d, ps, v, text) -> WriteHandle.H.invokeExact(d, ps, v, (Object) text, (Object[]) new Object[0]);

    static boolean writelnBound() { return WriteHandle.H != null; }

    static void printSummary(Object stream, int severity, String text) throws Throwable {
        zombie.debug.LogSeverity[] all = zombie.debug.LogSeverity.values();
        if (stream instanceof zombie.debug.DebugLogStream d && severity >= 0 && severity < all.length && WriteHandle.H != null) {
            zombie.debug.LogSeverity v = all[severity];
            // the print stream stock picks for that severity (DebugLogStream.getPrintStream)
            java.io.PrintStream ps = v == zombie.debug.LogSeverity.Warning ? d.getWrappedWarnStream()
                : v == zombie.debug.LogSeverity.Error ? d.getWrappedErrStream() : d.getWrappedOutStream();
            sink.write(d, ps, v, text);
        } else if (stream instanceof java.io.PrintStream p) p.println(text);
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
            return onLine(self, sev, msg, args, true);
        }
    }

    /**
     * {@code writeWithCallerPrefixln(PrintStream, LogSeverity, int, boolean, Object, Object...)}: warn, error, debugln with a prefix.
     * Argument 3 is false for {@code warnOnce} and {@code debugOnceln} (stock keeps its own once-set): those pass through untouched.
     */
    @Patch(className = "zombie.debug.DebugLogStream", methodName = "writeWithCallerPrefixln")
    public static class PrefixLine {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object self, @Patch.Argument(1) zombie.debug.LogSeverity sev,
                                    @Patch.Argument(3) boolean allowRepeat, @Patch.Argument(4) Object msg, @Patch.Argument(5) Object[] args) {
            return onLine(self, sev, msg, args, allowRepeat);
        }
    }
}
