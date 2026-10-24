package pzbutter;

import zombie.core.PerformanceSettings;

/**
 * numberZombiesBlended (Tempo 4.5), zombieLodFixed3d (Tempo 4.6, SPEC 3.4) and zombieModelAddBudgetUs (SPEC 3.6): the state and the
 * decision for {@code IsoWorld.sceneCullZombies}. The game's loop hands out 3D models with {@code setSceneCulled(false)} while its
 * counter is under 510 and calls {@code setSceneCulled(true)} past it. Ours counts the loop's grants in stock order and turns the
 * ones past the cap, or past the frame's model-creation budget, into the call stock itself makes past 510. Game thread only: a
 * cull belongs to the thread that opened it, and the shims in ZombiePatches pass every other thread through.
 */
final class ZombieLod {
    private ZombieLod() {}

    static final Guard blendGuard = new Guard("numberZombiesBlended");
    static final Guard capGuard = new Guard("zombieLodFixed3d");
    static final Guard budgetGuard = new Guard("zombieModelAddBudgetUs");
    static final Guard simGuard = new Guard("zombieSimLodTiles");

    /** 0 = stock for each. */
    static int blended;
    static int cap;
    static long budgetNs;
    /** zombieSimLodTiles (0 = off) and zombieSimLodSteps (1 to 3). */
    static int simTiles, simSteps = 1;

    // The cull in progress.
    static boolean inCull;
    static Thread cullThread;
    static int grants, adds, effCap, depth;
    static long addNs;

    /** The setSceneCulled call in flight (set by the enter shim, read and cleared by the exit shim). */
    static boolean inflightRewrite;
    static long inflightT0;

    /** Rewrites by the cap and by the budget since start. */
    static long capped, deferred;

    /** Culls that ran with a key on and the grants of the culls that ended (the zombies line). */
    static long culls, grantsTotal;

    static void init() {
        blended = Settings.enabled ? Settings.num("numberZombiesBlended", 0, 0, 64) : 0;
        int c = Settings.enabled ? Settings.num("zombieLodFixed3d", 0, 0, 510) : 0;
        cap = c == 0 ? 0 : Math.max(50, c);
        budgetNs = Settings.enabled ? Settings.num("zombieModelAddBudgetUs", 0, 0, 100_000) * 1000L : 0;
        blendGuard.on = blended > 0;
        capGuard.on = cap > 0;
        budgetGuard.on = budgetNs > 0;
        simTiles = Settings.enabled ? Settings.num("zombieSimLodTiles", 0, 0, 200) : 0;
        simSteps = Settings.num("zombieSimLodSteps", 1, 1, 3);
        simGuard.on = simTiles > 0;
    }

    /**
     * One more update tier: for each of the first {@code steps} steps, a minimum distance past {@code tiles << i} lowers the level by
     * one (the game's own thresholds are 30, 60 and 80). A NaN distance never lowers.
     */
    static zombie.UpdateSchedulerSimulationLevel lower(zombie.UpdateSchedulerSimulationLevel lvl, float minDist, int tiles, int steps) {
        for (int i = 0; i < steps; i++) if (minDist > (float) (tiles << i)) lvl = lvl.less();
        return lvl;
    }

    /**
     * The guard a failure of the shared cull code is charged to: the cap's while it is on, else the budget's, else the blend count's
     * (the code that threw serves all three; the budget's own clock reads are charged to its guard where they happen).
     */
    static Guard cullGuard() { return capGuard.on ? capGuard : budgetGuard.on ? budgetGuard : blendGuard; }

    /** sceneCullZombies entry: the blend count, then (when the cap or the budget is on) the cull's counters. */
    static void enterCull() {
        culls++;
        if (blendGuard.on && PerformanceSettings.numberZombiesBlended != blended) PerformanceSettings.numberZombiesBlended = blended;
        if (capGuard.on || budgetGuard.on) beginCull(PerformanceSettings.numberZombiesBlended);
    }

    static void beginCull(int blendNow) {
        grants = adds = depth = 0;
        addNs = 0;
        inflightRewrite = false;
        inflightT0 = 0;
        effCap = effectiveCap(capGuard.on ? cap : 0, blendNow);
        cullThread = Thread.currentThread();
        inCull = true;
    }

    static void endCull() {
        grantsTotal += grants;
        inCull = false;
    }

    static void exitCull() { endCull(); }

    /** Tempo: the cap never starves the blend budget. 0 stays 0 (no cap). */
    static int effectiveCap(int cap, int blendNow) { return cap <= 0 ? 0 : Math.max(cap, blendNow); }

    /**
     * The stock call {@code setSceneCulled(culled)} of the cull loop for a zombie ({@code added}: it is already in the model manager).
     * Returns true when a grant must become a culled call. Culled calls are stock's own and pass uncounted.
     */
    static boolean decide(boolean culled, boolean added) {
        if (culled) return false;
        int n = grants++;
        if (effCap > 0 && n >= effCap) { capped++; return true; }
        if (budgetGuard.on && budgetNs > 0 && !added && adds > 0 && addNs >= budgetNs) { deferred++; return true; }
        return false;
    }

    /** After a grant that was not rewritten: a first-time add costs its time against the cull's budget. */
    static void granted(boolean added, long ns) {
        if (!added) { adds++; addNs += ns; }
    }

    static void resetForTest() {
        blendGuard.on = capGuard.on = budgetGuard.on = false;
        blended = cap = 0;
        budgetNs = 0;
        simGuard.on = false;
        simTiles = 0;
        simSteps = 1;
        inCull = false;
        cullThread = null;
        grants = adds = effCap = depth = 0;
        addNs = 0;
        inflightRewrite = false;
        inflightT0 = 0;
        capped = deferred = culls = grantsTotal = 0;
    }
}
