package pzbutter;

/** Run with java -ea. No framework: each check is a static method, failures are AssertionErrors. */
public final class SelfTest {
    public static void main(String[] args) {
        boolean ea = false;
        assert ea = true;
        if (!ea) throw new IllegalStateException("run with -ea");
        System.out.println("ok");
    }
}
