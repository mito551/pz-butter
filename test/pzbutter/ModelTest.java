package pzbutter;

import java.util.ArrayList;
import java.lang.reflect.Field;

/** ModelPatches: the nullModelGuard filter (list copy, stock list untouched). */
public class ModelTest {
    /** A Model without its constructor (the real one needs an AssetManager). */
    private static zombie.core.skinnedmodel.model.Model anyModel() throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (zombie.core.skinnedmodel.model.Model) ((sun.misc.Unsafe) f.get(null))
            .allocateInstance(zombie.core.skinnedmodel.model.Model.class);
    }

    public static void run() throws Exception {
        animFolderMissing();
        zombie.core.skinnedmodel.model.Model m = anyModel();
        zombie.core.skinnedmodel.model.ModelInstance full = new zombie.core.skinnedmodel.model.ModelInstance();
        full.model = m;
        zombie.core.skinnedmodel.model.ModelInstance empty = new zombie.core.skinnedmodel.model.ModelInstance();
        if (empty.model != null) throw new AssertionError("fresh ModelInstance should have a null model");

        ArrayList in = new ArrayList();
        in.add(full); in.add(empty); in.add(full);
        ArrayList out = ModelPatches.filterNullModels(in);
        if (out == null) throw new AssertionError("null-model instance not filtered");
        if (out.size() != 2 || out.contains(empty)) throw new AssertionError("filter kept the wrong entries");
        if (in.size() != 3 || !in.contains(empty)) throw new AssertionError("stock list mutated");

        ArrayList healthy = new ArrayList();
        healthy.add(full); healthy.add(full);
        if (ModelPatches.filterNullModels(healthy) != null) throw new AssertionError("healthy list must not be copied");

        if (ModelPatches.filterNullModels(new ArrayList()) != null) throw new AssertionError("empty list must not be copied");

        ArrayList allNull = new ArrayList();
        allNull.add(empty); allNull.add(empty);
        ArrayList outNull = ModelPatches.filterNullModels(allNull);
        if (outNull == null || !outNull.isEmpty()) throw new AssertionError("all-null list must become an empty copy");
    }

    /** animFolderMissing: a missing AnimSets/actiongroups folder skips the walk instead of logging a NoSuchFileException. */
    static void animFolderMissing() throws Exception {
        java.nio.file.Path exist = java.nio.file.Files.createTempDirectory("butter-anim");
        java.nio.file.Path missing = exist.resolve("nope");
        if (ModelPatches.skipFolder(null)) throw new AssertionError("null folder must stay stock");
        if (!ModelPatches.skipFolder(missing)) throw new AssertionError("missing folder must skip the walk");
        if (ModelPatches.skipFolder(exist)) throw new AssertionError("existing folder must stay stock");
        java.nio.file.Path file = exist.resolve("file");
        java.nio.file.Files.writeString(file, "x");
        if (ModelPatches.skipFolder(file)) throw new AssertionError("a file path is not a missing folder — stock walk");
        java.util.List<String> empty = ModelPatches.emptyList();
        if (empty == null || !empty.isEmpty()) throw new AssertionError("skip must yield an empty list");
        if (ModelPatches.emptyList() != empty) throw new AssertionError("empty list should be the shared one");

        // guard wiring: default template leaves it on, forced-off template switches it off
        Settings.load(SelfTest.write("enabled=true\nanimFolderMissing=false\n"));
        ModelPatches.initAnimFolder();
        if (ModelPatches.animFolderGuard.on) throw new AssertionError("forced-off must disable the guard");
        Settings.load(SelfTest.write("enabled=true\n"));
        ModelPatches.initAnimFolder();
        if (!ModelPatches.animFolderGuard.on) throw new AssertionError("default must be on");
    }
}
