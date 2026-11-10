package pzbutter;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** modScanWalk: inside loadMod, searchFolders + getRelativeFile answer from one walk per folder; any odd root takes stock. */
final class ModScanTest {
    private ModScanTest() {}

    static sun.misc.Unsafe unsafe() throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (sun.misc.Unsafe) f.get(null);
    }

    static void run() throws Exception {
        defaultsOff();
        walkParityClean();
        oddNamesFallback();
        linksFallback();
        relMatchesStock();
        windowAppendAndLookup();
        modFloorPackCompress();
        System.out.println("[ModScanTest] ok");
    }

    // ---- modFloorPackCompress: mod .floor packs keep the texture-compression bit ----

    static void modFloorPackCompress() throws Exception {
        Settings.load(SelfTest.write("enabled=true\n"));
        ModScanPatches.init();
        assert !ModScanPatches.floorGuard.on : "modFloorPackCompress defaults off";

        boolean option = zombie.core.textures.TextureID.useCompressionOption;
        try {
            Settings.load(SelfTest.write("enabled=true\nmodFloorPackCompress=true\n"));
            ModScanPatches.init();
            assert ModScanPatches.floorGuard.on : "template enables modFloorPackCompress";

            zombie.core.textures.TextureID.useCompressionOption = true;
            assert ModScanPatches.floorSet("media/texture/HD.floor", 64) : "floor pack with the bit unset gets it set";
            assert !ModScanPatches.floorSet("media/texture/HD.floor", 68) : "floor pack already compressed is untouched";
            assert !ModScanPatches.floorSet("media/texture/Tiles1x.pack", 64) : "non-floor pack untouched";
            assert !ModScanPatches.floorSet(null, 64) : "null name safe";

            zombie.core.textures.TextureID.useCompressionOption = false;
            assert !ModScanPatches.floorSet("media/texture/HD.floor", 64) : "option off means bit 4 is zero anyway";
        } finally {
            zombie.core.textures.TextureID.useCompressionOption = option;
            Settings.load(SelfTest.write("enabled=true\n"));
            ModScanPatches.init();
            assert !ModScanPatches.floorGuard.on : "default restored";
        }
    }

    static void defaultsOff() throws Exception {
        Settings.load(SelfTest.write("enabled=true\n"));
        ModScanPatches.init();
        assert !ModScanPatches.guard.on : "modScanWalk defaults off";
        ModScanPatches.loadModEnter();
        assert !ModScanPatches.loadModActive : "window stays off while guard off";
        ModScanPatches.loadModExit();
    }

    // ---- the walk: parity with the stock recursion (File.list order, pre-order, files; media/maps dirs too) ----

    /** The stock algorithm, replicated for the oracle: add me for the diff check. */
    static List<String> oracle(File dir) {
        List<String> out = new ArrayList<>();
        oracleRec(dir, out);
        return out;
    }

    static void oracleRec(File file, List<String> out) {
        if (file.isDirectory()) {
            String el = file.getAbsolutePath().replace("\\\\", "/").replace("./", "");
            if (el.contains("media/maps/")) out.add(el);
            String abs = file.getAbsolutePath();
            for (String name : file.list()) oracleRec(new File(abs + File.separator + name), out);
        } else {
            out.add(file.getAbsolutePath().replace("\\\\", "/").replace("./", ""));
        }
    }

    static File tree(String... want) throws Exception {
        Path root = Files.createTempDirectory("modscan");
        for (String w : want) {
            Path p = root.resolve(w);
            if (w.endsWith("/")) Files.createDirectories(p);
            else {
                Files.createDirectories(p.getParent());
                Files.writeString(p, "x");
            }
        }
        return root.toFile();
    }

    static void walkParityClean() throws Exception {
        File root = tree("media/lua/shared/a.lua", "media/lua/shared/zz/zz.lua", "media/textures/x.png",
            "root.txt", "sp ace interior.txt", "media/maps/MyMap/", "media/maps/MyMap/inner.txt", "media/maps/MyMap/sub/");
        ModScanPatches.loadModEnter();
        List<String> els = ModScanPatches.walkList(root);
        List<String> want = oracle(root);
        assert els != null : "clean tree walks";
        assert els.equals(want) : "walk order/strings match stock: " + els + " vs " + want;
        ModScanPatches.loadModExit();
    }

    static void oddNamesFallback() throws Exception {
        ModScanPatches.loadModEnter();
        for (String odd : new String[] {"a%.lua", "tail.", "tail space ", "tail. /x.txt", "caf\u00e9.lua"}) {
            File root = tree(odd);
            assert ModScanPatches.walkList(root) == null : "odd name falls back to stock: " + odd;
        }
        ModScanPatches.loadModExit();
    }

    static void linksFallback() throws Exception {
        Path root = Files.createTempDirectory("modscan");
        Path dir = root.resolve("real");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("a.lua"), "x");
        ModScanPatches.loadModEnter();
        Files.createSymbolicLink(root.resolve("linkdir"), root.toAbsolutePath().relativize(dir.toAbsolutePath()));
        assert ModScanPatches.walkList(root.toFile()) == null : "symlink to dir falls back";
        Files.createSymbolicLink(root.resolve("linkfile"), root.resolve("real/a.lua").toAbsolutePath().relativize(root.toAbsolutePath()));
        assert ModScanPatches.walkList(root.toFile()) == null : "symlink to file falls back";
        ModScanPatches.loadModExit();
    }

    // ---- rel: identical to the game's getRelativeFile (bare instance: no instance state) ----

    static void relMatchesStock() throws Exception {
        File root = tree("media/lua/shared/a.lua", "media/maps/MyMap/", "x.txt", "deeper/y/z.png");
        zombie.ZomboidFileSystem bare = (zombie.ZomboidFileSystem) unsafe().allocateInstance(zombie.ZomboidFileSystem.class);
        java.net.URI base = new File(root.getPath().toLowerCase(Locale.ENGLISH)).toURI();
        for (String s : new String[] {"media/lua/shared/a.lua", "media/maps/MyMap/", "media/maps/MyMap", "x.txt", "deeper/y/z.png", "deeper/y/z.png/", "outside/other.txt", ""}) {
            assert ModScanPatches.relCalc(base, s).equals(bare.getRelativeFile(base, s)) : "rel parity: " + s;
        }
    }

    // ---- the advice face: window, append to loadList, rel answer, miss stock ----

    static void windowAppendAndLookup() throws Exception {
        ModScanPatches.loadModExit();
        File root = tree("media/lua/a.lua", "media/lua/b.lua");
        List<String> els = ModScanPatches.walkList(root);
        assert els != null : "clean tree again";
        java.net.URI base = new File(root.getPath().toLowerCase(Locale.ENGLISH)).toURI();

        // no window yet: rel answers nothing
        assert ModScanPatches.relFor(base, els.get(0)) == null : "rel answers only inside loadMod";

        // append to a bare instance's loadList
        zombie.ZomboidFileSystem self = (zombie.ZomboidFileSystem) unsafe().allocateInstance(zombie.ZomboidFileSystem.class);
        Field f = zombie.ZomboidFileSystem.class.getDeclaredField("loadList");
        f.setAccessible(true);
        List<String> loadList = new ArrayList<>();
        f.set(self, loadList);

        Settings.load(SelfTest.write("enabled=true\nmodScanWalk=true\n"));
        ModScanPatches.init();
        assert ModScanPatches.guard.on : "template turns the guard on";

        ModScanPatches.loadModEnter();
        assert ModScanPatches.searchFolders(self, root) : "searchFolders handled";
        assert loadList.equals(els) : "loadList got the walk order: " + loadList + " vs " + els;
        for (String el : els) {
            assert ModScanPatches.relFor(base, el).equals(bareRel(base, el)) : "rel matches game: " + el;
        }
        assert ModScanPatches.relFor(base, "media/lua/unknown.lua") == null : "unknown string stock";
        java.net.URI other = new File("/somewhere/else/".toLowerCase(Locale.ENGLISH)).toURI();
        assert ModScanPatches.relFor(other, els.get(0)) == null : "other base stock";
        ModScanPatches.loadModExit();
        assert ModScanPatches.relFor(base, els.get(0)) == null : "window closed";
        assert !ModScanPatches.searchFolders(self, root) : "stock after the window";

        Settings.load(SelfTest.write("enabled=true\n"));
        ModScanPatches.init();
        assert !ModScanPatches.guard.on : "defaults restored";
    }

    static String bareRel(java.net.URI base, String s) throws Exception {
        zombie.ZomboidFileSystem bare = (zombie.ZomboidFileSystem) unsafe().allocateInstance(zombie.ZomboidFileSystem.class);
        return bare.getRelativeFile(base, s);
    }
}
