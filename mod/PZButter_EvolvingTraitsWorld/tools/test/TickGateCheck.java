import java.util.HashMap;
import se.krka.kahlua.j2se.KahluaTableImpl;
import se.krka.kahlua.vm.KahluaTable;

/** Gate check for etw.perf.Tick: java -cp <out>:<projectzomboid.jar> TickGateCheck.java (run with -ea). */
public class TickGateCheck {
    static KahluaTable t(Object... kv) {
        KahluaTable t = new KahluaTableImpl(new HashMap<>());
        for (int i = 0; i < kv.length; i += 2) t.rawset(kv[i], kv[i + 1]);
        return t;
    }

    public static void main(String[] a) throws Exception {
        var etw = etw.perf.Tick.class.getDeclaredMethod("etwStateActive", KahluaTable.class);
        var moodle = etw.perf.Tick.class.getDeclaredMethod("sleepMoodleIncomplete", KahluaTable.class);
        etw.setAccessible(true); moodle.setAccessible(true);
        assert !(boolean) etw.invoke(null, t());
        assert !(boolean) etw.invoke(null, t("EvolvingTraitsWorld", t("InjurySnapshotSystem", t("InjuredBodyParts", t()), "IndefatigableProtectionExpiresAt", 0.0)));
        assert (boolean) etw.invoke(null, t("EvolvingTraitsWorld", t("InjurySnapshotSystem", t("BrokenBodyParts", t("Hand_L", t())))));
        assert (boolean) etw.invoke(null, t("EvolvingTraitsWorld", t("IndefatigableProtectionExpiresAt", 12345.0)));
        assert (boolean) moodle.invoke(null, t());
        assert (boolean) moodle.invoke(null, t("Moodles", t("SleepHealthMoodle", t("Level", 0.0, "Value", 0.5))));
        assert !(boolean) moodle.invoke(null, t("Moodles", t("SleepHealthMoodle", t("Level", 0.0, "GoodBadNeutral", 0.0, "Value", 0.5))));
        System.out.println("ok");
    }
}
