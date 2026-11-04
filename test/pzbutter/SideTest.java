package pzbutter;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/** Side gate: client keys inert on a server, server keys inert on a client, one line when suppressed. */
final class SideTest {
    private SideTest() {}

    static void run() {
        clientKeyOffOnServer();
        serverKeyOffOnClient();
        bothKeyEverywhere();
        Side.resetForTest();
    }

    private static void clientKeyOffOnServer() {
        Side.forTest(true, false);
        assert Side.server() && !Side.mpClient();
        String out = capture(() -> { assert !Side.allowed("texCompress"); });
        assert out.contains("texCompress: off (server)") : out;
        String again = capture(() -> { assert !Side.allowed("texCompress"); });
        assert again.isEmpty() : "second ask must not log again: " + again;
    }

    private static void serverKeyOffOnClient() {
        Side.forTest(false, true);
        assert !Side.server() && Side.mpClient();
        String out = capture(() -> { assert !Side.allowed("containerSendGuard"); });
        assert out.contains("containerSendGuard: off (client)") : out;
        assert Side.allowed("vehicleEarlyParse");   // plain client key stays readable

        Side.forTest(false, false);
        assert !Side.server() && !Side.mpClient();
        String single = capture(() -> { assert !Side.allowed("serverTickLog"); });
        assert single.contains("serverTickLog: off (client)") : single;
    }

    private static void bothKeyEverywhere() {
        for (boolean server : new boolean[] {true, false}) {
            for (boolean mp : new boolean[] {true, false}) {
                Side.forTest(server, mp);
                assert Side.allowed("frameLog");
                assert Side.allowed("loaderCpuFixes");
            }
        }
        // A client key is readable in single player too.
        Side.forTest(false, false);
        assert Side.allowed("texCompress");
    }

    private static String capture(Runnable r) {
        PrintStream old = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
            r.run();
        } finally {
            System.setOut(old);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }
}
