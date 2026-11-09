package pzbutter;

import me.zed_0xff.zombie_buddy.Patch;

/** Advice hook for LTPE-3 virtual insertion: runs after the game parses item distributions. */
public final class DistributionInsertPatches {
    private DistributionInsertPatches() {}

    @Patch(className = "zombie.inventory.ItemPickerJava", methodName = "Parse")
    public static class AfterParse {
        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit() {
            DistributionInsert.apply();
        }
    }
}