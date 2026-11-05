package pzbutter;

import java.util.List;

/** MP client rows of Plan 15 (vehicleEarlyParse, chunkRefill). */
final class MpClientTest {

    private MpClientTest() {}

    static void run() {
        keptExpires();
        answerDiffers();
        verifyNeverParses();
        refillBackoff();
        refillLimit();
    }

    private static void keptExpires() {
        MpClient.resetForTest();
        byte[] a = {1, 2, 3};
        byte[] b = {4, 5, 6};
        MpClient.keep((short) 7, a, 1_000);
        assert MpClient.takeFor((short) 7, 30_999) != null : "kept packet expired early";
        assert MpClient.takeFor((short) 7, 30_999) == null : "takeFor did not consume";

        MpClient.keep((short) 7, a, 1_000);
        assert MpClient.takeFor((short) 7, 31_000) == null : "kept packet outlived 30 s";

        MpClient.keep((short) 7, a, 1_000);
        MpClient.keep((short) 7, b, 1_010);
        byte[] got = MpClient.takeFor((short) 7, 1_011);
        assert got != null && got[0] == 4 : "second packet did not supersede the first";
        assert MpClient.keptCountForTest() == 0;
    }

    private static void answerDiffers() {
        byte[] a = {1, 2, 3, 4};
        assert MpClient.decide(a, a.clone()) == MpClient.Action.UPDATE : "equal bytes should update";
        assert MpClient.decide(a, new byte[] {1, 2, 3, 5}) == MpClient.Action.REPLACE : "different bytes should replace";
        assert MpClient.decide(null, null) == MpClient.Action.UPDATE;
        assert MpClient.decide(a, null) == MpClient.Action.REPLACE;
    }

    private static void verifyNeverParses() {
        MpClient.resetForTest();
        MpClient.setModeForTest("verify");
        assert MpClient.mode().equals("verify");
        assert !MpClient.reparseEnabled() : "verify must not parse early";
        MpClient.keep((short) 9, new byte[] {0, 9, 1}, 0);
        MpClient.tick();
        assert MpClient.parseAttemptsForTest() == 0 : "verify mode parsed a kept packet";
        assert MpClient.keptCountForTest() == 1 : "verify dropped the kept packet";
        assert MpClient.takeFor((short) 9, 1) != null;

        MpClient.setModeForTest("on");
        assert MpClient.reparseEnabled();
        MpClient.resetForTest();
    }

    private static void refillBackoff() {
        MpClient.resetForTest();
        MpClient.hole(1, 2, 0);
        assert MpClient.holes(3_999, false).isEmpty() : "quiet hole counted before 4 s";
        List<MpClient.Hole> a = MpClient.holes(4_000, false);
        assert a.size() == 1 : a.size();
        assert a.get(0).asks() == 1;
        assert a.get(0).nextAtMs() == 19_000 : a.get(0).nextAtMs();
        assert MpClient.holes(4_001, false).isEmpty() : "two passes in 3 s";

        List<MpClient.Hole> b = MpClient.holes(19_000, false);
        assert b.size() == 1 && b.get(0).asks() == 2;
        assert b.get(0).nextAtMs() == 49_000 : b.get(0).nextAtMs();

        List<MpClient.Hole> c = MpClient.holes(49_000, false);
        assert c.size() == 1 && c.get(0).asks() == 3;
        assert c.get(0).nextAtMs() == 109_000 : c.get(0).nextAtMs();

        List<MpClient.Hole> d = MpClient.holes(109_000, false);
        assert d.size() == 1 && d.get(0).asks() == 4;
        assert d.get(0).nextAtMs() == 229_000 : "backoff should cap at 120 s, got " + (d.get(0).nextAtMs() - 109_000);
        assert MpClient.holes(200_000, false).isEmpty() : "asked before the 120 s backoff ended";

        MpClient.resetForTest();
        MpClient.hole(5, 5, 0);
        assert MpClient.holes(24_999, true).isEmpty() : "busy hole counted before 25 s";
        assert MpClient.holes(25_000, true).size() == 1 : "busy hole not counted at 25 s";
    }

    private static void refillLimit() {
        MpClient.resetForTest();
        for (int i = 0; i < 10; i++) MpClient.hole(i, i, 0);
        assert MpClient.holes(4_000, false).size() == 6 : "more than 6 requests in one pass";
        assert MpClient.holes(7_000, false).size() == 4 : "remaining holes not requested next pass";
        assert MpClient.holes(10_000, false).isEmpty() : "holes requested twice before backoff";
        MpClient.resetForTest();
    }
}
