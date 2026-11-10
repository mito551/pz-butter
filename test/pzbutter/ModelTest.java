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
}
