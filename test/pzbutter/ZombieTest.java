package pzbutter;

/** Plan 8 checks (zombies and entities; Task 1: keys and template, Task 2: zero-length facing). Run from SelfTest.main. Later tasks append their calls to run(). */
final class ZombieTest {
    private ZombieTest() {}

    static void run() throws Exception {
        plan8KeysDefaults();
        settingsTemplateHasPlan8Keys();
    }

    static final String[] PLAN8_FLAGS = {"zeroFacingFix", "playerLosFast", "zombieCullSortFast", "statsNoBox", "popmanFlagsFlyweight", "entityTiming"};

    static void plan8KeysDefaults() {
        Settings.load(SelfTest.write(Settings.render(new java.util.Properties()).replace("\nenabled=false\n", "\nenabled=true\n")));
        for (String k : PLAN8_FLAGS) assert !Settings.flag(k) : k;
        assert Settings.num("numberZombiesBlended", -1, -1, 99) == 0 && Settings.num("zombieLodFixed3d", -1, -1, 999) == 0;
        assert Settings.num("zombieModelAddBudgetUs", -1, -1, 99999) == 0 && Settings.num("zombieSimLodTiles", -1, -1, 999) == 0;
        assert Settings.num("zombieSimLodSteps", -1, -1, 9) == 1;
    }

    static void settingsTemplateHasPlan8Keys() {
        String t = Settings.render(new java.util.Properties());
        for (String k : new String[] {"zeroFacingFix=false", "numberZombiesBlended=0", "zombieLodFixed3d=0", "zombieModelAddBudgetUs=0",
                "zombieSimLodTiles=0", "zombieSimLodSteps=1", "playerLosFast=false", "zombieCullSortFast=false", "statsNoBox=false",
                "popmanFlagsFlyweight=false", "entityTiming=false"})
            assert t.contains("\n" + k + "\n") : k;
        assert t.indexOf("\nbloodRebakeCoalesceMs=250\n") < t.indexOf("\nzeroFacingFix=false\n") && t.indexOf("\nentityTiming=false\n") < t.indexOf("\ninputLatch=true\n");
        for (String k : new String[] {"zombieLodFixed3d", "zombieModelAddBudgetUs", "zombieSimLodTiles"})
            assert t.contains("# " + k + ": changes the simulation") : k;
    }
}
