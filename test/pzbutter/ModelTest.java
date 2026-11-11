package pzbutter;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
        characterMaskLookup();
        modelPathCache();
        animTransformPool();
        matrixRecycledIdentity();
        animFolderMissing();
        animFolderScope();
        skinnedUniformSkip();
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

    // ---- animFolderScope: one dirs pass memoizes the state rels per root; files passes skip missing roots ----

    static class Rec implements zombie.ZomboidFileSystem.IWalkFilesVisitor {
        final List<String> got;
        Rec(List<String> got) { this.got = got; }
        public void visit(java.io.File f, String rel) { got.add(rel); }
    }

    static void invokeInternal(zombie.ZomboidFileSystem bare, File root, String rel, boolean deep, zombie.ZomboidFileSystem.IWalkFilesVisitor v) throws Exception {
        var m = zombie.ZomboidFileSystem.class.getDeclaredMethod("walkGameAndModFilesInternal", File.class, String.class, boolean.class, zombie.ZomboidFileSystem.IWalkFilesVisitor.class);
        m.setAccessible(true);
        m.invoke(bare, root, rel, deep, v);
    }

    static zombie.ZomboidFileSystem bareZfs() throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (zombie.ZomboidFileSystem) ((sun.misc.Unsafe) f.get(null)).allocateInstance(zombie.ZomboidFileSystem.class);
    }

    static void animFolderScope() throws Exception {
        Settings.load(SelfTest.write("enabled=true\nanimFolderScope=true\n"));
        ModelPatches.initAnimScope();
        assert ModelPatches.scopeGuard.on : "template on for animFolderScope";
        assert !ModelPatches.animScopeWindow : "window off at first";

        zombie.ZomboidFileSystem bare = bareZfs();
        Path gameRootP = Files.createTempDirectory("animscope");
        Files.createDirectories(gameRootP.resolve("media/AnimSets/ZombieWalk/sub"));
        Files.writeString(gameRootP.resolve("media/AnimSets/ZombieWalk/a.xml"), "x");
        Files.writeString(gameRootP.resolve("media/AnimSets/ZombieWalk/sub/b.xml"), "x");
        File gameRoot = gameRootP.toFile();
        Path modRootP = Files.createTempDirectory("animscope2");
        Files.createDirectories(modRootP.resolve("media/lua"));
        File modRoot = modRootP.toFile();

        ModelPatches.animScopeLoadEnter();
        List<String> stockGot = new ArrayList<>();
        zombie.ZomboidFileSystem.IWalkFilesVisitor v = new Rec(stockGot);
        Object w = ModelPatches.animScopeVisit(gameRoot, "media/AnimSets/ZombieWalk", false, v);
        assert w != v && w != null : "dirs pass wraps the visitor";
        invokeInternal(bare, gameRoot, "media/AnimSets/ZombieWalk", false, (zombie.ZomboidFileSystem.IWalkFilesVisitor) w);
        assert stockGot.size() == 2 : "wrapper delegated every visit: " + stockGot;

        assert ModelPatches.animScopeVisit(gameRoot, "media/AnimSets/ZombieWalk/sub", true, v) == v : "recorded rel runs stock";
        assert ModelPatches.animScopeVisit(gameRoot, "media/AnimSets/ZombieWalk/Nope", true, v) == ModelPatches.ANIM_SCOPE_SKIP : "unknown rel skipped";
        assert ModelPatches.animScopeVisit(modRoot, "media/AnimSets/ZombieWalk/Nope", true, v) == v : "root without a dirs pass stock";
        assert ModelPatches.animScopeVisit(gameRoot, "media/maps/X", true, v) == v : "other prefix stock";

        List<String> none = new ArrayList<>();
        invokeInternal(bare, modRoot, "media/AnimSets/ZombieWalk/Nope", true, new Rec(none));
        assert none.isEmpty() : "a skipped root would visit nothing (skip == stock outcome)";

        ModelPatches.animScopeLoadExit();
        assert !ModelPatches.animScopeWindow : "window closes";
        assert ModelPatches.animScopeVisit(gameRoot, "media/AnimSets/ZombieWalk/Nope", true, v) == v : "window cleared: stock again";

        Settings.load(SelfTest.write("enabled=true\n"));
        ModelPatches.initAnimScope();
        assert !ModelPatches.scopeGuard.on : "defaults off";
        ModelPatches.animScopeLoadEnter();
        assert ModelPatches.animScopeVisit(gameRoot, "media/AnimSets/ZombieWalk", false, v) == v : "guard off: no wrap";
        ModelPatches.animScopeLoadExit();
    }

    // ---- characterMaskLookup: count/fromInt answer from a seeded table ----

    static void characterMaskLookup() throws Exception {
        Settings.load(SelfTest.write("enabled=true\n"));
        ModelPatches.initMask();
        assert !ModelPatches.maskGuard.on : "characterMaskLookup defaults off";

        Settings.load(SelfTest.write("enabled=true\ncharacterMaskLookup=true\n"));
        ModelPatches.initMask();
        assert ModelPatches.maskGuard.on : "template turns the guard on";

        assert ModelPatches.count() == zombie.core.skinnedmodel.model.CharacterMask.Part.values().length : "count agrees with the enum";
        for (int i = -2; i <= ModelPatches.count() + 1; i++) {
            assert ModelPatches.fromInt(i) == zombie.core.skinnedmodel.model.CharacterMask.Part.fromInt(i)
                : "fromInt(" + i + ") agrees with stock";
        }
        assert ModelPatches.PARTS == ModelPatches.partTable() : "the table seeds once";

        Settings.load(SelfTest.write("enabled=true\n"));
        ModelPatches.initMask();
        assert !ModelPatches.maskGuard.on : "defaults restored";
    }

    // ---- skinnedUniformSkip: an unchanged uniform upload is skipped, per shader and per slot ----

    static void skinnedUniformSkip() throws Exception {
        Settings.load(SelfTest.write("enabled=true\n"));
        ModelPatches.initSkinnedUniform();
        assert !ModelPatches.suGuard.on : "skinnedUniformSkip defaults off";

        Settings.load(SelfTest.write("enabled=true\nskinnedUniformSkip=true\n"));
        ModelPatches.initSkinnedUniform();
        assert ModelPatches.suGuard.on : "template turns the guard on";

        Object sh = new Object(), other = new Object();
        assert !ModelPatches.suSkip1(sh, 0, 0.0f) : "the first value uploads (no false NaN skip)";
        assert ModelPatches.suSkip1(sh, 0, 0.0f) : "the same value skips";
        assert !ModelPatches.suSkip1(sh, 0, 0.5f) : "a change uploads";
        assert ModelPatches.suSkip1(sh, 0, 0.5f) : "and then skips";
        assert !ModelPatches.suSkip1(other, 0, 0.5f) : "another shader instance uploads its own";
        assert !ModelPatches.suSkip3(sh, 7, 1, 2, 3) : "a vector uploads";
        assert ModelPatches.suSkip3(sh, 7, 1, 2, 3) : "a same vector skips";
        assert !ModelPatches.suSkip3(sh, 7, 1, 2, 4) : "a changed vector uploads";
        assert ModelPatches.suSkip1(sh, 0, 0.5f) : "vector slots do not disturb scalar slots";

        Settings.load(SelfTest.write("enabled=true\n"));
        ModelPatches.initSkinnedUniform();
        assert !ModelPatches.suGuard.on : "defaults restored";
        assert !ModelPatches.suSkip1(sh, 0, 0.5f) : "off: stock (no skip)";
    }

    // ---- modelPathCache: normalised model paths answered from a per-input cache ----

    static void modelPathCache() throws Exception {
        Field uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        uf.setAccessible(true);
        Object animModel = ((sun.misc.Unsafe) uf.get(null)).allocateInstance(zombie.core.skinnedmodel.advancedanimation.AnimatedModel.class);
        java.lang.reflect.Method stock = zombie.core.skinnedmodel.advancedanimation.AnimatedModel.class
            .getDeclaredMethod("processModelFileName", String.class);
        stock.setAccessible(true);
        String[] in = {"Media\\X\\Y.OBJ", "plain/OBJ", "A\\B", "C:/D\\E.FBX"};
        for (String s : in) {
            String want = (String) stock.invoke(animModel, s);
            assert want.equals(ModelPatches.normalize(s)) : "normalise agrees with stock: " + s;
        }
        assert ModelPatches.normalize(in[0]) == ModelPatches.normalize(in[0]) : "cached answer is the same object";
        assert !ModelPatches.normalize(in[0]).equals(ModelPatches.normalize(in[1])) : "distinct keys distinct answers";
        assert ModelPatches.normalize(null) == null : "null stays null (advice declines to stock)";

        Settings.load(SelfTest.write("enabled=true\nmodelPathCache=true\n"));
        ModelPatches.initPath();
        assert ModelPatches.pathGuard.on : "template turns the guard on";
        Settings.load(SelfTest.write("enabled=true\n"));
        ModelPatches.initPath();
        assert !ModelPatches.pathGuard.on : "defaults restored";
    }

    // ---- animTransformPool: pooled Matrix4f elements for model/skin transform sizing ----

    private static sun.misc.Unsafe theUnsafe() throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (sun.misc.Unsafe) f.get(null);
    }

    static void animTransformPool() throws Exception {
        org.lwjgl.util.vector.Matrix4f[] fresh = ModelPatches.sizedTransforms(null, 3);
        assert fresh.length == 3 : "fresh array sized";
        assert fresh[0] != null && fresh[1] != null && fresh[2] != null : "supplier filled";

        org.lwjgl.util.vector.Matrix4f[] same = ModelPatches.sizedTransforms(fresh, 3);
        assert same == fresh : "same-length returns the same array";

        // grow keeps the head elements, fills the tail
        org.lwjgl.util.vector.Matrix4f[] grown = ModelPatches.sizedTransforms(fresh, 5);
        assert grown != fresh && grown.length == 5 : "grow allocates a new array";
        assert grown[0] == fresh[0] && grown[1] == fresh[1] : "head identity kept";
        assert grown[4] != null : "tail filled";

        // shrink frees the surplus; growing again hands the same transforms back
        org.lwjgl.util.vector.Matrix4f tailX = grown[3];
        org.lwjgl.util.vector.Matrix4f tailY = grown[4];
        org.lwjgl.util.vector.Matrix4f[] shrunk = ModelPatches.sizedTransforms(grown, 3);
        assert shrunk.length == 3 && shrunk[0] == grown[0] : "shrink keeps identity";
        org.lwjgl.util.vector.Matrix4f[] regrown = ModelPatches.sizedTransforms(shrunk, 5);
        assert regrown[3] == tailX && regrown[4] == tailY : "freed transforms are not allocated again";

        // freeMatrix(null) is safe; the pool never grows past its cap
        ModelPatches.freeMatrix(null);
        for (int i = 0; i < 8300; i++) ModelPatches.freeMatrix(new org.lwjgl.util.vector.Matrix4f());
        Field poolF = ModelPatches.class.getDeclaredField("TRANSFORM_POOL");
        poolF.setAccessible(true);
        assert ((java.util.ArrayDeque<?>) poolF.get(null)).size() <= ModelPatches.TRANSFORM_POOL_CAP
            : "pool respects its cap";

        // setSkinnedPool mirrors the stock setSkinnedTo body
        Object sstd = theUnsafe().allocateInstance(Class.forName("zombie.core.skinnedmodel.animation.AnimationPlayer$SkinTransformData"));
        zombie.core.skinnedmodel.model.SkinningData sd = new zombie.core.skinnedmodel.model.SkinningData(
            new java.util.HashMap<>(), new java.util.ArrayList<>(), new java.util.ArrayList<>(),
            new java.util.ArrayList<>(), new java.util.ArrayList<>(), new java.util.HashMap<>());
        ModelPatches.setSkinnedPool(sstd, sd);
        java.lang.reflect.Field ft = ModelPatches.class.getDeclaredField("fTransforms2");
        ft.setAccessible(true);
        java.lang.reflect.Field fd = ModelPatches.class.getDeclaredField("fDirty2");
        fd.setAccessible(true);
        java.lang.reflect.Field fs = ModelPatches.class.getDeclaredField("fSkinnedTo");
        fs.setAccessible(true);
        assert ft.get(sstd) != null : "transforms sized";
        java.lang.reflect.Field tr2 = (java.lang.reflect.Field) ft.get(null);
        tr2.setAccessible(true);
        java.lang.reflect.Field di2 = (java.lang.reflect.Field) fd.get(null);
        di2.setAccessible(true);
        java.lang.reflect.Field sk2 = (java.lang.reflect.Field) fs.get(null);
        sk2.setAccessible(true);
        assert tr2.get(sstd) != null : "transforms sized";
        assert di2.get(sstd) == Boolean.TRUE : "dirty set";
        assert sk2.get(sstd) == sd : "skinnedTo stored";
        Settings.load(SelfTest.write("enabled=true\nanimTransformPool=true\n"));
        ModelPatches.initATP();
        assert ModelPatches.atpGuard.on : "template turns the guard on";
        Settings.load(SelfTest.write("enabled=true\n"));
        ModelPatches.initATP();
        assert !ModelPatches.atpGuard.on : "defaults restored";
    }

    /** R18S-B3: a recycled matrix must come back as a fresh identity, never with stale values. */
    static void matrixRecycledIdentity() throws Exception {
        Field poolF = ModelPatches.class.getDeclaredField("TRANSFORM_POOL");
        poolF.setAccessible(true);
        ((java.util.ArrayDeque<?>) poolF.get(null)).clear();
        org.lwjgl.util.vector.Matrix4f m = ModelPatches.matrix();
        m.m00 = 5f;
        m.m01 = 7f;
        m.m32 = 9f;
        ModelPatches.freeMatrix(m);
        org.lwjgl.util.vector.Matrix4f again = ModelPatches.matrix();
        assert again == m : "recycled matrix identity";
        assert again.m00 == 1f && again.m01 == 0f && again.m11 == 1f
            && again.m32 == 0f && again.m33 == 1f : "recycled matrix reset to identity";
    }
}