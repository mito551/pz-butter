package pzbutter;

/** Checks for the netStats counters (D10). Run with -ea. */
public final class NetStatsTest {
    private NetStatsTest() {}

    public static void run() {
        deltaPerType();
        resetIsNotNegative();
        offIsOneFlagCheck();
    }

    static void deltaPerType() {
        long[] prev = {10, 20, 0};
        long[] cur = {13, 25, 7};
        long[] d = NetStats.delta(prev, cur);
        assert d.length == 3 && d[0] == 3 && d[1] == 5 && d[2] == 7 : "deltaPerType";
    }

    static void resetIsNotNegative() {
        long[] d = NetStats.delta(new long[] {100, 5}, new long[] {7, 5});
        assert d[0] == 7 && d[1] == 0 : "resetIsNotNegative";
    }

    static void offIsOneFlagCheck() {
        Settings.load(SelfTest.write("enabled=true\n"));
        NetStats.resetForTest();
        NetStats.init();
        assert !NetStats.on : "netStats must stay off without the key";
    }
}
