package pzbutter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.util.Locale;
import java.util.Properties;

/** Zomboid/PZButter.properties. Read once at mod load; any failure leaves every feature off. */
public final class Settings {
    static final String VERSION = "0.1";

    private static final String DEFAULTS =
        "# PZ Butter. Read at game start.\n"
      + "#\n"
      + "# enabled: master switch. When false every feature below stays off and the game runs stock.\n"
      + "enabled=false\n"
      + "#\n"
      + "# frameLog: one line per frame in Zomboid/pzbutter/frames.out:\n"
      + "#   <epoch ms> <frame no> <wall ms> <main-thread CPU ms>\n"
      + "frameLog=false\n";

    public static volatile boolean enabled;
    public static volatile boolean frameLog;

    private Settings() {}

    public static void load() { load(new File(System.getProperty("user.home"), "Zomboid/PZButter.properties")); }

    public static void load(File f) {
        enabled = frameLog = false;
        try {
            if (!f.exists()) try (FileWriter w = new FileWriter(f)) { w.write(DEFAULTS); }
            Properties p = new Properties();
            try (FileInputStream in = new FileInputStream(f)) { p.load(in); }
            enabled = bool(p, "enabled", false);
            frameLog = bool(p, "frameLog", false);
        } catch (Throwable t) {
            enabled = frameLog = false;
            System.out.println("[PZButter] settings failed, all features off: " + t);
        }
    }

    static boolean bool(Properties p, String key, boolean dflt) {
        String v = p.getProperty(key);
        if (v == null) return dflt;
        switch (v.trim().toLowerCase(Locale.ROOT)) {
            case "true": case "yes": case "on": case "1": return true;
            case "false": case "no": case "off": case "0": return false;
            default: return dflt;
        }
    }
}
