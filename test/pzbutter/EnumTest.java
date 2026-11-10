package pzbutter;

import java.lang.reflect.Field;
import java.util.Locale;

/**
 * Task 13 (enumParseCache): tryParseEnum and the NetworkVariables fromString family answer from
 * hash maps; results must equal the stock linear/throw paths for hits and misses alike.
 */
public final class EnumTest {
    private EnumTest() {}

    public static void run() throws Exception {
        defaultsOff();
        parseAgrees();
        fieldFamilyAgrees();
        nullAndGuard();
    }

    private static void check(String what, boolean ok) {
        if (!ok) throw new AssertionError(what);
    }    private static void defaultsOff() throws Exception {
        Settings.load(SelfTest.write("enabled=true\n"));
        EnumPatches.init();
        check("enumParseCache defaults off", !EnumPatches.guard.on);
        check("byName on a guarded-off map still answers (core is pure)",
            EnumPatches.byName(zombie.network.NetworkVariables.ThumpType.class, "TTDoor",
                zombie.network.NetworkVariables.ThumpType.TTNone)
                == zombie.network.NetworkVariables.ThumpType.TTDoor);
    }

    private static void parseAgrees() throws Exception {
        EnumPatches.guard.on = true;
        var types = zombie.network.NetworkVariables.ThumpType.class;
        var stock = zombie.util.StringUtils.class;

        // every stock hit equals the map answer; wrong case and unknown miss to the default
        for (Object c : types.getEnumConstants()) {
            String n = ((Enum<?>) c).name();
            check("byName hit " + n,
                EnumPatches.byName(types, n, null) == c);
            check("stock valueOf agrees " + n, java.lang.Enum.valueOf(types, n) == c);
        }
        check("wrong case misses", EnumPatches.byName(types, "ttdoor", "def") == "def");
        check("unknown misses", EnumPatches.byName(types, "zzz", "def") == "def");
        check("null name misses", EnumPatches.byName(types, null, "def") == "def");
        check("unknown enum values are still distinct", EnumPatches.byName(types, "TTNone", null)
            == zombie.network.NetworkVariables.ThumpType.TTNone);

        EnumPatches.guard.on = false;
    }

    private static void fieldFamilyAgrees() throws Exception {
        EnumPatches.guard.on = true;
        // differential: for every distinct field value (and a case variant), byField == stock fromString
        var thump = zombie.network.NetworkVariables.ThumpType.class;
        Field thumpField = thump.getDeclaredField("thumpType");
        thumpField.setAccessible(true);
        java.util.Set<String> inputs = new java.util.HashSet<>();
        for (Object c : thump.getEnumConstants()) inputs.add((String) thumpField.get(c));
        for (String v : inputs) for (String i : java.util.List.of(v, v.toUpperCase(Locale.ROOT))) {
            var theirs = zombie.network.NetworkVariables.ThumpType.fromString(i);
            check("thump agrees on '" + i + "'",
                EnumPatches.byField(thump, "thumpType", true, i,
                    zombie.network.NetworkVariables.ThumpType.TTNone) == (Object) theirs);
        }
        check("thump uppercase input agrees",
            EnumPatches.byField(thump, "thumpType", true, "DOOR",
                zombie.network.NetworkVariables.ThumpType.TTNone)
                == zombie.network.NetworkVariables.ThumpType.TTDoor);
        check("thump unknown misses to TTNone",
            EnumPatches.byField(thump, "thumpType", true, "zzz",
                zombie.network.NetworkVariables.ThumpType.TTNone)
                == zombie.network.NetworkVariables.ThumpType.TTNone);
        check("thump null input misses to TTNone (stock: all compares false)",
            EnumPatches.byField(thump, "thumpType", true, null,
                zombie.network.NetworkVariables.ThumpType.TTNone)
                == zombie.network.NetworkVariables.ThumpType.TTNone);

        var walk = zombie.network.NetworkVariables.WalkType.class;
        Field walkField = walk.getDeclaredField("walkType");
        walkField.setAccessible(true);
        inputs.clear();
        for (Object c : walk.getEnumConstants()) inputs.add((String) walkField.get(c));
        for (String v : inputs) for (String i : java.util.List.of(v, v.toUpperCase(Locale.ROOT))) {
            var theirs = zombie.network.NetworkVariables.WalkType.fromString(i);
            check("walk agrees on '" + i + "'",
                EnumPatches.byField(walk, "walkType", true, i,
                    zombie.network.NetworkVariables.WalkType.WT1) == (Object) theirs);
        }
        check("walk unknown misses to WT1",
            EnumPatches.byField(walk, "walkType", true, "zzz", zombie.network.NetworkVariables.WalkType.WT1)
                == zombie.network.NetworkVariables.WalkType.WT1);

        var state = zombie.network.NetworkVariables.ZombieState.class;
        Field stateField = state.getDeclaredField("zombieState");
        stateField.setAccessible(true);
        inputs.clear();
        for (Object c : state.getEnumConstants()) inputs.add((String) stateField.get(c));
        for (String v : inputs) for (String i : java.util.List.of(v, v.toUpperCase(Locale.ROOT))) {
            var theirs = zombie.network.NetworkVariables.ZombieState.fromString(i);
            check("state agrees on '" + i + "'",
                EnumPatches.byField(state, "zombieState", false, i,
                    zombie.network.NetworkVariables.ZombieState.Idle) == (Object) theirs);
        }
        check("state null input is Idle (stock short-circuit)",
            EnumPatches.byField(state, "zombieState", false, null,
                zombie.network.NetworkVariables.ZombieState.Idle)
                == zombie.network.NetworkVariables.ZombieState.Idle);
        EnumPatches.guard.on = false;
    }

    private static void nullAndGuard() throws Exception {
        Settings.load(SelfTest.write("enabled=true\nenumParseCache=true\n"));
        EnumPatches.init();
        check("template turns the cache on", EnumPatches.guard.on);
        // one-time failures leave the hook off and answer with the stock-shape default
        EnumPatches.guard.on = true;
        check("non-enum class answers with default via catch",
            EnumPatches.byName(String.class, "x", "def") == "def");
        EnumPatches.guard.on = false;
        Settings.load(SelfTest.write("enabled=true\n"));
        EnumPatches.init();
        check("back off again", !EnumPatches.guard.on);
    }
}
