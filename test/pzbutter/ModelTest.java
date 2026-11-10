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
}