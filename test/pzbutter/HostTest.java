package pzbutter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Hosted server child JVM (Plan 15 Task 4). */
final class HostTest {

    private HostTest() {}

    static void run() {
        agentCarried();
        argsFileStrict();
    }

    private static void agentCarried() {
        List<String> in = List.of(
                "-Xmx2g",
                "-javaagent:/mods/ZombieBuddy.jar=policy=deny-new,frontend=auto",
                "-javaagent:/mods/Other.jar=policy=allow-all",
                "-javaagent:/mods/zombie_buddy.jar",
                "-javaagent:/mods/ZombieBuddy.jar=policy=prompt",
                "-javaagent:/mods/ZombieBuddy.jar=allow_unsigned_mods,policy=allow-all");
        List<String> out = HostLaunch.agentArgs(in);
        assert out.size() == 2 : out;
        assert out.get(0).contains("deny-new") : out;
        assert out.get(1).contains("policy=allow-all") : out;
        assert HostLaunch.hasZbAgent(in);
        assert !HostLaunch.hasZbAgent(List.of("-javaagent:/x/other.jar"));
        assert HostLaunch.agentArgs(List.of("-javaagent:/x/other.jar", "-Xmx2g")).isEmpty();
        assert HostLaunch.agentArgs(null).isEmpty();
    }

    private static void argsFileStrict() {
        Path dir = SelfTest.tmp("host").toPath();
        try {
            Files.createDirectories(dir);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }

        Path good = dir.resolve("good.args");
        write(good, "# hosted server\n-Xms2g\n-Xlog:gc:file=" + dir.resolve("gc.log") + ":time\n\n# end\n");
        Optional<String> f = HostLaunch.userArgsFile(good, 8L << 30);
        assert f.isPresent() : "valid file rejected";
        assert f.get().equals("@" + good.toAbsolutePath());

        Path big = dir.resolve("big.args");
        write(big, "x".repeat(5000));
        assert HostLaunch.userArgsFile(big, 8L << 30).isEmpty() : "5 KB accepted";

        Path nonAscii = dir.resolve("nonascii.args");
        write(nonAscii, "-Xms1g\n# caf\u00e9\n");
        assert HostLaunch.userArgsFile(nonAscii, 8L << 30).isEmpty() : "non-ASCII accepted";

        Path many = dir.resolve("many.args");
        write(many, "# x\n".repeat(17));
        assert HostLaunch.userArgsFile(many, 8L << 30).isEmpty() : "17 lines accepted";

        Path xmx = dir.resolve("xmx.args");
        write(xmx, "-Xmx2g\n");
        assert HostLaunch.userArgsFile(xmx, 8L << 30).isEmpty() : "-Xmx accepted";

        Path space = dir.resolve("space.args");
        write(space, "-Xlog:gc:file=/tmp/a b/gc.log\n");
        assert HostLaunch.userArgsFile(space, 8L << 30).isEmpty() : "space accepted";

        Path above = dir.resolve("above.args");
        write(above, "-Xms9g\n");
        assert HostLaunch.userArgsFile(above, 8L << 30).isEmpty() : "Xms above host accepted";

        Path gone = dir.resolve("gone.args");
        write(gone, "-Xlog:gc:file=" + dir.resolve("missing").resolve("gc.log") + "\n");
        assert HostLaunch.userArgsFile(gone, 8L << 30).isEmpty() : "missing folder accepted";

        Path small = dir.resolve("small.args");
        write(small, "-Xms1g\n");
        assert HostLaunch.userArgsFile(small, 512L << 20).isEmpty() : "Xms above small host accepted";

        Path plain = dir.resolve("plain.args");
        write(plain, "-Xms2g\n-Xlog:gc\n");
        assert HostLaunch.userArgsFile(plain, 8L << 30).isPresent() : "plain Xlog rejected";

        assert HostLaunch.userArgsFile(null, 8L << 30).isEmpty();
        assert HostLaunch.userArgsFile(dir.resolve("nope.args"), 8L << 30).isEmpty();
    }

    private static void write(Path p, String text) {
        try {
            Files.writeString(p, text, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }
}
