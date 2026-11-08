package pzbutter;

import java.util.ArrayList;
import java.util.List;

/** Cell spread: same order stock, chunk-budget floor, near-player safety net, re-arrival, measured chunk cost. */
final class CellSpreadTest {
    private CellSpreadTest() {}

    public static void main(String[] args) { run(); }

    static void run() {
        sameStepsSameOrder();
        budgetFloor();
        safetyNetNearPlayer();
        cellReArrives();
        chunkCostIsMeasured();
    }

    private static final class FakeChunk { final int id; FakeChunk(int id) { this.id = id; } }
    private static final class FakeDef { final int id; FakeDef(int id) { this.id = id; } }
    private static final FakeDef[] ROOMS = new FakeDef[8];
    static { for (int i = 0; i < ROOMS.length; i++) ROOMS[i] = new FakeDef(i); }

    /** Fake server map: an 8x8 cell of 64 fake chunks, one level, deterministic rooms, stepped clock. */
    private static final class Fake {
        final List<String> log = new ArrayList<>();
        boolean loaded;
        long clockNs;

        final CellSpread.Host host = new CellSpread.Host() {
            public long nanoTime() { return clockNs += 1_000_000L; }   // every poll costs 1 fake ms
            public java.util.List<Object> currentRooms() { return new ArrayList<>(); }
            public Object chunk(int cx, int cy) { return cx >= 0 && cx < 8 && cy >= 0 && cy < 8 ? new FakeChunk(cy * 8 + cx) : null; }
            public int minLevel(Object chunk) { return 0; }
            public int maxLevel(Object chunk) { return 0; }
            public void ensureAt(int x, int y, int level) {
                if (fakeSquareWithObjects(x, y)) log.add("ensure " + x + "," + y);
            }
            public void borderRecalcAt(int x, int y, int level) {
                if (fakeSquareWithObjects(x, y)) log.add("recalc " + x + "," + y);
            }
            public void finishChunk(Object chunk, List<Object> roomsOut) {
                FakeChunk c = (FakeChunk) chunk;
                roomsOut.add(ROOMS[c.id % ROOMS.length]);
                roomsOut.add(ROOMS[(c.id + 1) % ROOMS.length]);
            }
            public void doLoadGridsquare(Object chunk) {
                log.add("loadsq " + ((FakeChunk) chunk).id);
            }
            public void roomDec(Object def) { log.add("dec " + ((FakeDef) def).id); }
            public void roomInc(Object def) { log.add("inc " + ((FakeDef) def).id); }
            public void setLoaded(boolean b) { loaded = b; log.add("loaded " + b); }
            public void loadVehicles() { log.add("vehicles"); }
        };

        final Object[] chunks = new Object[64];
        Fake() { for (int cy = 0; cy < 8; cy++) for (int cx = 0; cx < 8; cx++) chunks[cy * 8 + cx] = host.chunk(cx, cy); }

        static boolean fakeSquareWithObjects(int x, int y) {
            return (x == 128 + 32 && y == 64 + 10) || (x == 128 - 1 && y == 64 + 5)
                    || (x == 128 && y == 64 + 20) || (x == 128 + 64 && y == 64 + 63);
        }
    }

    private static List<String> stripLoadedFlags(List<String> log) {
        List<String> out = new ArrayList<>();
        for (String s : log) if (!s.startsWith("loaded ")) out.add(s);
        return out;
    }

    // ---- 1. slicing 64 chunks over multiple ticks keeps the stock call order ----

    private static void sameStepsSameOrder() {
        Fake stock = new Fake();
        CellSpread.resetForTest();
        CellSpread.spread(stock.host, null, 128, 64, Long.MAX_VALUE);   // one tick, everything
        assert stock.loaded && !stock.log.contains("loaded false") : stock.log;

        Fake spread = new Fake();
        CellSpread.resetForTest();
        // fake clock: every unit costs 1 ms -> a 20 ms budget is ~20 units per tick
        for (int tick = 0; tick < 200 && !spread.loaded; tick++) {
            CellSpread.spread(spread.host, null, 128, 64, 20);
            spread.clockNs += 100_000_000L;   // next tick's slice starts later anyway
        }
        assert spread.loaded : "slices must end fully loaded";
        List<String> order = stripLoadedFlags(spread.log);
        assert stripLoadedFlags(stock.log).equals(order)
                : "order differs:\nstock " + stock.log + "\nspread " + order;
        assert order.contains("loadsq 0") && order.contains("loadsq 63") : "all 64 chunks loaded";
        assert spread.log.get(spread.log.size() - 2).equals("loaded true")
                && spread.log.get(spread.log.size() - 1).equals("vehicles") : spread.log;
        assert spread.log.contains("loaded false") : "partial slice ended with isLoaded=false:\n" + spread.log;
    }

    // ---- 2. the budget floor: a full tick still gets one chunk ----

    private static void budgetFloor() {
        CellSpread.resetForTest();
        CellSpread.noteChunkCostMs(8.0);
        // rest of the tick is large and the tick is already over: the floor still floats the cost of one chunk
        long b = CellSpread.budgetMs(100, 20, 50, 200);   // target 100, margin 20, rest 50, 200 used
        assert b >= 8 : "budget " + b + " < one chunk (8 ms)";
        // inside the budget the free part is target - margin - rest - used
        long free = CellSpread.budgetMs(100, 20, 10, 30);
        assert free == 40 : "free " + free;
        assert CellSpread.budgetMs(100, 20, 40, 30) == 10 : "rest estimate is subtracted";
        // decayed rest estimate: capped at 50, decays 10 % per tick
        assert CellSpread.restOfTickMs() == 0 : "empty rest estimate";
        CellSpread.noteRestTick(80);
        assert CellSpread.restOfTickMs() == 50 : "capped at 50";
        CellSpread.noteRestTick(0);
        assert CellSpread.restOfTickMs() == 45 : "decays 10 % a tick";
        CellSpread.noteRestTick(0);
        assert CellSpread.restOfTickMs() == 40 : "decays 10 % again";
    }

    // ---- 3. near the player, at least the safety budget ----

    private static void safetyNetNearPlayer() {
        assert CellSpread.safetyNet(0, 5, 5, 5, 5) >= 120 : "same cell";
        assert CellSpread.safetyNet(0, 5, 5, 6, 6) >= 120 : "adjacent cell";
        assert CellSpread.safetyNet(0, 5, 5, 7, 5) == 0 : "two cells away";
        assert CellSpread.safetyNet(300, 5, 5, 5, 5) == 300 : "never lowers a bigger budget";
    }

    // ---- 4. a finished cell that arrives again is spread again, not marked loaded untouched ----

    private static void cellReArrives() {
        Object cell = new Object();
        Fake first = new Fake();
        CellSpread.resetForTest();
        CellSpread.spread(first.host, cell, 128, 64, Long.MAX_VALUE);
        assert first.loaded && first.log.contains("loadsq 63");
        Fake again = new Fake();
        CellSpread.spread(again.host, cell, 128, 64, Long.MAX_VALUE);   // same cell object, re-used for new data
        assert stripLoadedFlags(again.log).equals(stripLoadedFlags(first.log))
                : "re-arrival must repeat every step:\nfirst " + first.log + "\nagain " + again.log;
    }

    // ---- 5. the budget floor follows the measured time of a chunk ----

    private static void chunkCostIsMeasured() {
        CellSpread.resetForTest();
        Fake f = new Fake();
        CellSpread.spread(f.host, null, 128, 64, Long.MAX_VALUE);
        // tick already over budget, so the budget is the floor; the fake clock makes a chunk 1 ms, the start value is 10 ms
        assert CellSpread.budgetMs(100, 20, 10, 95) < 10 : "floor still the fixed 10 ms: " + CellSpread.budgetMs(100, 20, 10, 95);
    }
}
