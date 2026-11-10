package pzbutter;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** idleBakeBoost: the stand-still bake-budget boost on BakeHold's Budget seam (SPEC 6.x). */
public final class BoostTest {

    public static void run() {
        defaultsHeadlessSafe();
        machineOnSeams();
    }

    private static void defaultsHeadlessSafe() {
        assert !BakeHold.BUSY_SEAM.getAsBoolean() : "no busy player headless";
        assert !BakeHold.IDLE_SEAM.getAsBoolean() : "the seam needs an unchanged position first";
    }

    private static BakeHold.Budget budget() {
        BakeHold.Budget b = new BakeHold.Budget();
        b.total = 8;
        b.rebake = 4;
        return b;
    }

    private static void machineOnSeams() {
        AtomicBoolean idle = new AtomicBoolean(false);
        AtomicBoolean busy = new AtomicBoolean(false);
        BooleanSupplier wasIdle = BakeHold.IDLE_SEAM, wasBusy = BakeHold.BUSY_SEAM;
        boolean wasOn = BakeHold.idleBoostOn;
        try {
            BakeHold.IDLE_SEAM = idle::get;
            BakeHold.BUSY_SEAM = busy::get;
            BakeHold.idleBoostOn = true;
            BakeHold.resetBoost();

            // feature off: nothing ever changes
            idle.set(true);
            BakeHold.resetBoost();
            BakeHold.idleBoostOn = false;
            BakeHold.Budget b = budget();
            BakeHold.applyBoost(b, 50_000, 1000);
            assert b.total == 8 && b.rebake == 4 : "boost off leaves the budget";
            BakeHold.idleBoostOn = true;
            BakeHold.resetBoost();

            // not idle, not waiting: no boost
            b = budget();
            BakeHold.applyBoost(b, 50_000, 1000);
            assert b.total == 8 && b.rebake == 4 && !BakeHold.boosting() : "no idle, no boost";

            // idle but nothing waiting: still no boost
            idle.set(true);
            BakeHold.applyBoost(b, 50_100, 1001);
            assert !BakeHold.boosting() : "nothing waiting, no boost";

            // the boost needs 1 s of stillness while bakes wait
            BakeHold.resetBoost();
            BakeHold.noteWaiting(true, 0);
            idle.set(true);
            b = budget();
            BakeHold.applyBoost(b, 0, 1002);        // stillness starts
            assert !BakeHold.boosting();
            b = budget();
            BakeHold.applyBoost(b, 500, 1003);
            assert !BakeHold.boosting() : "still for only half a second";
            b = budget();
            BakeHold.applyBoost(b, 1_000, 1004);
            assert BakeHold.boosting() : "idle 1 s plus waiting bakes: boost";
            assert b.total == 12 && b.rebake == 6 : "the boost adds its pairs, got " + b.total;
            // same frame again: the pairs are added once
            b = budget();
            BakeHold.applyBoost(b, 1_000, 1004);
            assert b.total == 12 : "one boost per frame";

            // the player goes busy: the boost ends and rest blocks a re-entry for 30 s
            busy.set(true);
            b = budget();
            BakeHold.applyBoost(b, 1_100, 1005);
            assert !BakeHold.boosting() : "busy ends the boost";
            busy.set(false);
            b = budget();
            BakeHold.applyBoost(b, 2_000, 1006);
            assert !BakeHold.boosting() : "rest 30 s";

            // movement ends a boost too
            BakeHold.resetBoost();
            BakeHold.noteWaiting(true, 0);
            b = budget();
            BakeHold.applyBoost(b, 0, 2000);
            assert !BakeHold.boosting();
            BakeHold.applyBoost(b, 1_000, 2001);
            assert BakeHold.boosting() : "boost entered";
            idle.set(false);
            BakeHold.applyBoost(b, 1_100, 2002);
            assert !BakeHold.boosting() : "movement ends the boost";

            // 45 s cap: a long boost ends by itself
            BakeHold.resetBoost();
            BakeHold.noteWaiting(true, 10_000);
            idle.set(true);
            b = budget();
            BakeHold.applyBoost(b, 10_000, 3000);
            assert !BakeHold.boosting();
            BakeHold.applyBoost(b, 11_000, 3001);
            assert BakeHold.boosting() : "boost entered";
            b = budget();
            BakeHold.applyBoost(b, 56_400, 3002);
            assert !BakeHold.boosting() : "45 s cap ends the boost";

            // the waits lapsed 2 s ago: the boost ends
            BakeHold.resetBoost();
            BakeHold.noteWaiting(true, 20_000);
            idle.set(true);
            BakeHold.applyBoost(b, 20_000, 4000);
            BakeHold.applyBoost(b, 21_000, 4001);
            assert BakeHold.boosting() : "boost entered";
            BakeHold.noteWaiting(true, 21_000);
            b = budget();
            BakeHold.applyBoost(b, 22_300, 4002);
            assert BakeHold.boosting() : "waits still seen";
            b = budget();
            BakeHold.applyBoost(b, 24_000, 4003);
            assert !BakeHold.boosting() : "no waits for 2 s ends the boost";
        } finally {
            BakeHold.IDLE_SEAM = wasIdle;
            BakeHold.BUSY_SEAM = wasBusy;
            BakeHold.idleBoostOn = wasOn;
            BakeHold.resetBoost();
        }
    }

    private BoostTest() {}
}
