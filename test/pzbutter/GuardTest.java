package pzbutter;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

final class GuardTest {
    static void run() throws Exception {
        guardTripTransitionIsAtomic();
        guardTripsOnceUnderConcurrency();
    }

    private static void guardTripTransitionIsAtomic() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of("src/pzbutter/Guard.java"));
        assert source.contains("synchronized (this)") : "on-check and disable must be one atomic transition";
    }

    private static void guardTripsOnceUnderConcurrency() throws Exception {
        PrintStream saved = System.out;
        AtomicInteger trips = new AtomicInteger();
        int rounds = 50;
        int writers = 16;
        try {
            System.setOut(new PrintStream(OutputStream.nullOutputStream()) {
                @Override public void println(String line) {
                    if (line.contains("off after repeated failures")) trips.incrementAndGet();
                }
            });
            for (int round = 0; round < rounds; round++) {
                Guard guard = new Guard("concurrent");
                guard.on = true;
                for (int i = 0; i < 4; i++) guard.fail(new RuntimeException("setup"));
                CyclicBarrier start = new CyclicBarrier(writers);
                Thread[] threads = new Thread[writers];
                for (int i = 0; i < writers; i++) threads[i] = failing(guard, start);
                for (Thread thread : threads) thread.start();
                for (Thread thread : threads) thread.join();
                assert !guard.on;
            }
        } finally {
            System.setOut(saved);
        }
        assert trips.get() == rounds : "expected one trip per guard, got " + trips.get() + " for " + rounds;
    }

    private static Thread failing(Guard guard, CyclicBarrier start) {
        return new Thread(() -> {
            try { start.await(); }
            catch (Exception e) { throw new RuntimeException(e); }
            guard.fail(new RuntimeException("race"));
        });
    }
}
