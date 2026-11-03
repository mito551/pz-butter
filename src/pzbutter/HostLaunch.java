package pzbutter;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Flags for the hosted server's child JVM (Plan 18, SPEC 6.7.3 hostJvmArgs).
 *
 * CoopMaster.launchServer builds its command line locally and only ever hands one
 * string to the list from getGarbageCollector(), so that method's return value is
 * the only seam a game-class patch can reach. The child reads one @argfile from
 * that slot; nested @files are not expanded by the java launcher, so the agent and
 * the user's lines are merged into one temp file.
 */
public final class HostLaunch {

    private HostLaunch() {}

    static final Guard guard = new Guard("hostJvmArgs");
    private static final int MAX_BYTES = 4096;
    private static final int MAX_LINES = 16;
    private static final Pattern XMS = Pattern.compile("-Xms(\\d+)([mMgG])");
    private static final Pattern DRIVE = Pattern.compile("[A-Za-z]:[\\\\/]");
    private static volatile boolean toldTrust;

    /** `-javaagent:` entries that can run headless: ZB with a non-interactive policy. */
    public static List<String> agentArgs(List<String> clientJvmArgs) {
        List<String> out = new ArrayList<>();
        if (clientJvmArgs == null) return out;
        for (String arg : clientJvmArgs) {
            if (arg == null || !arg.startsWith("-javaagent:")) continue;
            String body = arg.substring("-javaagent:".length());
            int eq = body.indexOf('=');
            String jar = eq < 0 ? body : body.substring(0, eq);
            String opts = eq < 0 ? "" : body.substring(eq + 1);
            if (!isZbJar(jar)) continue;
            if (nonInteractive(opts)) out.add(arg);
        }
        return out;
    }

    public static boolean hasZbAgent(List<String> clientJvmArgs) {
        if (clientJvmArgs == null) return false;
        for (String arg : clientJvmArgs) {
            if (arg == null || !arg.startsWith("-javaagent:")) continue;
            String body = arg.substring("-javaagent:".length());
            int eq = body.indexOf('=');
            String jar = eq < 0 ? body : body.substring(0, eq);
            if (isZbJar(jar)) return true;
        }
        return false;
    }

    private static boolean isZbJar(String jar) {
        String lower = jar.toLowerCase(Locale.ROOT);
        return lower.contains("zombiebuddy") || lower.contains("zombie_buddy");
    }

    private static boolean nonInteractive(String opts) {
        for (String opt : opts.split(",")) {
            String s = opt.trim();
            if (s.equals("policy=deny-new") || s.equals("policy=allow-all")) return true;
        }
        return false;
    }

    /**
     * Strict check from SPEC: at most 4 KB, ASCII, at most 16 lines; blank lines,
     * comments, a small -Xms and a plain -Xlog line are the only accepted forms.
     */
    public static Optional<String> userArgsFile(Path p, long hostMemBytes) {
        return userLines(p, hostMemBytes).map(lines -> "@" + p.toAbsolutePath());
    }

    static Optional<List<String>> userLines(Path p, long hostMemBytes) {
        try {
            if (p == null || !Files.isRegularFile(p) || Files.size(p) > MAX_BYTES) return Optional.empty();
            byte[] bytes = Files.readAllBytes(p);
            for (byte b : bytes) {
                if (b == '\n' || b == '\r' || b == '\t') continue;
                if (b < 0x20 || b > 0x7e) return Optional.empty();
            }
            String[] raw = new String(bytes, StandardCharsets.US_ASCII).split("\n", -1);
            int n = raw.length;
            while (n > 0 && raw[n - 1].trim().isEmpty()) n--;
            if (n > MAX_LINES) return Optional.empty();
            List<String> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                String line = raw[i].trim();
                if (line.endsWith("\r")) line = line.substring(0, line.length() - 1).trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (!accepted(line, hostMemBytes)) return Optional.empty();
                out.add(line);
            }
            return Optional.of(out);
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    private static boolean accepted(String line, long hostMemBytes) {
        Matcher m = XMS.matcher(line);
        if (m.matches()) {
            try {
                long value = Long.parseLong(m.group(1));
                long bytes = m.group(2).equalsIgnoreCase("g") ? Math.multiplyExact(value, 1L << 30) : Math.multiplyExact(value, 1L << 20);
                return bytes <= hostMemBytes;
            } catch (ArithmeticException | NumberFormatException e) {
                return false;
            }
        }
        if (!line.startsWith("-Xlog:")) return false;
        if (line.indexOf(' ') >= 0 || line.indexOf('"') >= 0 || line.indexOf('\'') >= 0) return false;
        if (DRIVE.matcher(line).find()) return false;
        int at = line.indexOf("file=");
        if (at < 0) return true;
        int start = at + "file=".length();
        int end = line.indexOf(':', start);
        String file = end < 0 ? line.substring(start) : line.substring(start, end);
        if (file.isEmpty()) return false;
        Path parent;
        try {
            parent = Path.of(file).toAbsolutePath().getParent();
        } catch (Throwable t) {
            return false;
        }
        return parent != null && Files.isDirectory(parent);
    }

    /**
     * The string CoopMaster adds after -Xms/-Xmx, or null to leave stock alone.
     * Only used when the client has no GC flag of its own (getGarbageCollector null).
     */
    public static String hostArg() {
        try {
            if (!Settings.enabled) return null;
            boolean jvm = Settings.flag("hostJvmArgs");
            boolean zb = Settings.flag("hostZombieBuddy");
            if (!jvm && !zb) return null;
            List<String> client = ManagementFactory.getRuntimeMXBean().getInputArguments();
            List<String> agents = zb ? agentArgs(client) : List.of();
            if (zb && agents.isEmpty() && hasZbAgent(client) && !toldTrust) {
                toldTrust = true;
                System.out.println("[PZButter] hostZombieBuddy: off (no headless trust)");
            }
            Path file = userFile();
            List<String> user = jvm && file != null ? userLines(file, Runtime.getRuntime().maxMemory()).orElse(List.of()) : List.of();
            if (agents.isEmpty() && user.isEmpty()) {
                if (jvm) System.out.println("[PZButter] hostJvmArgs: off (no " + file + ")");
                return null;
            }
            if (agents.isEmpty()) return "@" + file.toAbsolutePath();
            return "@" + merged(agents, user);
        } catch (Throwable t) {
            guard.fail(t);
            return null;
        }
    }

    public static Path userFile() {
        return Path.of(System.getProperty("user.home", "."), "Zomboid", "PZButter-host.args");
    }

    private static Path merged(List<String> agents, List<String> user) throws java.io.IOException {
        Path tmp = Files.createTempFile("pzbutter-host-args", ".txt");
        tmp.toFile().deleteOnExit();
        StringBuilder sb = new StringBuilder();
        for (String a : agents) sb.append(a).append('\n');
        for (String u : user) sb.append(u).append('\n');
        Files.writeString(tmp, sb.toString(), StandardCharsets.US_ASCII);
        return tmp;
    }

    static void resetForTest() {
        toldTrust = false;
    }
}
