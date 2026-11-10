package pzbutter;

import java.util.HashMap;

public final class TexTest {
    private TexTest() {}

    static void check(String what, boolean ok) {
        if (!ok) throw new AssertionError(what);
    }

    public static void run() throws Exception {
        HashMap<String, Object> table = TexPatches.table();
        table.clear();
        table.put("Item_Beta", null);
        table.put("Item_BETA", null);
        table.put("media/ui/Container_box.png", null);
        table.put("unrelated/other", null);
        String last = null;
        for (String k : table.keySet())
            if (k.equals("Item_Beta") || k.equals("Item_BETA")) last = k;
        HashMap<String, String> names = new HashMap<>();
        HashMap<String, String> icons = new HashMap<>();
        check("snapshot fills", TexPatches.collectAllIconsSnapshot(names, icons));
        check("unrelated skipped", !names.containsKey("other") && !icons.containsKey("unrelated/other"));
        check("container stripped", "media/ui/Container_box.png".equals(icons.get("box")) && "box".equals(names.get("box")));
        check("collision winner by order", last != null && last.equals(icons.get("beta")));
        check("collision name value", last != null && names.get("beta").equals(last.replaceFirst("Item_", "")));
        check("table untouched", table.containsKey("Item_Beta") && table.containsKey("Item_BETA") && table.containsKey("media/ui/Container_box.png"));
        // mutators take the same lock the snapshot does: a writer thread cannot disturb the collector.
        Thread writer = new Thread(() -> {
            for (int i = 0; i < 3000; i++) TexPatches.putSharedUnderLock("Item_B" + i, null);
        });
        writer.start();
        for (int i = 0; i < 200; i++) {
            HashMap<String, String> n = new HashMap<>();
            HashMap<String, String> ic = new HashMap<>();
            if (!TexPatches.collectAllIconsSnapshot(n, ic)) check("stress snapshot", false);
        }
        writer.join();
        check("put under lock", TexPatches.putSharedUnderLock("Item_Z_test", null) && table.containsKey("Item_Z_test"));
        check("clear under lock", TexPatches.clearSharedUnderLock() && table.isEmpty());
        table.clear();
    }
}
