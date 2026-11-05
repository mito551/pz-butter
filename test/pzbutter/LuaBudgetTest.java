package pzbutter;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;


/** The merged LuaBudget. Run from SelfTest.main. */
public final class LuaBudgetTest {
    private LuaBudgetTest() {}

    public static void run() {
        luaBudgetParse();
        duplicateLuaBudgetRefused();
        duplicateLuaBudgetSeenLate();
    }

    static File file(String text) {
        try {
            File f = Files.createTempFile("pzbutter-lb", ".properties").toFile();
            f.deleteOnExit();
            try (FileWriter w = new FileWriter(f)) { w.write(text); }
            return f;
        } catch (IOException e) { throw new RuntimeException(e); }
    }

    // LuaBudget's rule syntax, unchanged by the merge
    static void luaBudgetParse() {
        LuaBudget.Rule r = LuaBudget.parse("Mods/WKT/wkt_pointer.lua|3|OnTick", 40);
        assert r.needle.equals("mods/wkt/wkt_pointer.lua") && r.every == 3 && r.allows("OnTick") && !r.allows("OnSave");
        assert Math.abs(r.budgetMs - 25.0) < 1e-9;
        try { LuaBudget.parse("x.lua|1|OnTick", 40); throw new AssertionError("every 1 accepted"); }
        catch (IllegalArgumentException expected) { }
        try { LuaBudget.parse("x.lua|3|OnGameStart", 40); throw new AssertionError("one-shot event accepted"); }
        catch (IllegalArgumentException expected) { }
    }

    // the old LuaBudget's class is on the class path (the test stub): the merged copy does not start
    static void duplicateLuaBudgetRefused() {
        assert Main.classPresent("pzbutter.SelfTest") && !Main.classPresent("luabudget.NoSuchClass");
        assert Main.classPresent("luabudget.Budget");
        Settings.load(file("enabled=true\n"));
        LuaBudget.init(file("targetFps=40\nrule.1=x.lua|3|OnTick\n"));
        assert !LuaBudget.isEnabled();
    }

    // ZombieBuddy adds the old mod's jar after pz-butter's init (seen in game): the first skip decision checks again
    static void duplicateLuaBudgetSeenLate() {
        Settings.load(file("enabled=true\n"));
        String was = LuaBudget.oldMod;
        try {
            LuaBudget.oldMod = "luabudget.NoSuchClass";
            LuaBudget.init(file("targetFps=40\nrule.1=x.lua|3|OnTick\n"));
            assert LuaBudget.isEnabled();
            LuaBudget.oldMod = was;
            assert !LuaBudget.shouldSkip(new Object(), new Object[0]);
            assert !LuaBudget.isEnabled();
        } finally { LuaBudget.oldMod = was; }
    }
}
