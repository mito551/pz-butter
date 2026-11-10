package pzbutter;

import me.zed_0xff.zombie_buddy.Patch;

import java.io.File;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * modScanWalk (mod scanning, Plan 11b). Inside {@code ZomboidFileSystem.loadMod(id)} only: {@code searchFolders(File)} is answered
 * from one directory walk of the whole root (no per-entry OS stat on top of the walk, no per-folder yield/frame pump) and
 * {@code getRelativeFile(URI, String)} for the walked elements is answered from a map built in the same pass, each entry computed
 * with the game's own URI arithmetic so the strings are identical. The list, order and strings must match stock exactly; any odd
 * root (a link or junction, a {@code %} or non-ASCII name, a {@code .}/{@code ..} segment, a trailing {@code .} or space, an
 * unreadable folder) is handed back to the game's code whole.
 */
public final class ModScanPatches {
    private ModScanPatches() {}

    public static final Guard guard = new Guard("modScanWalk");

    static void init() {
        try { guard.on = Settings.flag("modScanWalk"); } catch (Throwable t) { guard.fail(t); }
    }

    // ---- loadMod window ----

    static volatile boolean loadModActive;
    static volatile URI activeBase;
    static volatile Map<String, String> relMap;

    public static void loadModEnter() {
        if (guard.on) loadModActive = true;
    }

    public static void loadModExit() {
        loadModActive = false;
        activeBase = null;
        relMap = null;
    }

    // ---- advice faces ----

    /** True when the walk answered the whole root; false sends stock in. Other calls stay stock: the window is loadMod only. */
    public static boolean searchFolders(Object self, File dir) {
        if (!loadModActive || dir == null) return false;
        List<String> els = walkList(dir);
        if (els == null) return false;
        URI base = new File(dir.getPath().toLowerCase(Locale.ENGLISH)).toURI();
        relMap = buildRels(els, base);
        activeBase = base;
        return append(self, els);
    }

    /** The walked answer for getRelativeFile(URI, String); null sends stock in (also every out-of-window or foreign-URI caller). */
    public static String relFor(URI uri, String s) {
        if (!loadModActive || uri == null || !uri.equals(activeBase) || relMap == null) return null;
        return relMap.get(s);
    }

    // ---- the walk ----

    private static final class Abort extends RuntimeException {
        private Abort() {}
        private static final Abort INSTANCE = new Abort();
        @Override public synchronized Throwable fillInStackTrace() { return this; }
    }

    /** The elements searchFolders would add, stock-ordered; null when the root holds anything stock-only-safe (fallback to game code). */
    static List<String> walkList(File root) {
        List<String> out = new ArrayList<>();
        try {
            Files.walkFileTree(root.toPath(),
                new SimpleFileVisitor<>() {
                    @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (attrs == null || attrs.isSymbolicLink() || !attrs.isDirectory() || !okName(dir.getFileName())) throw Abort.INSTANCE;
                        String el = norm(dir.toFile().getAbsolutePath());
                        if (el.contains("media/maps/")) out.add(el);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (attrs == null || attrs.isSymbolicLink() || !attrs.isRegularFile() || !okName(file.getFileName())) throw Abort.INSTANCE;
                        out.add(norm(file.toFile().getAbsolutePath()));
                        return FileVisitResult.CONTINUE;
                    }

                    @Override public FileVisitResult visitFileFailed(Path file, java.io.IOException exc) { throw Abort.INSTANCE; }
                });
            return out;
        } catch (Abort e) {
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    static String norm(String abs) {
        return abs.replace("\\", "/").replace("./", "");
    }

    /** ASCII, no . / .. segment, no trailing . or space, no % (links are caught by the walk attrs). */
    static boolean okName(Path name) {
        if (name == null) return false;
        String n = name.toString();
        if (n.equals(".") || n.equals("..") || n.contains("%") || n.endsWith(".") || n.endsWith(" ")) return false;
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c < 32 || c > 126) return false;
        }
        return true;
    }

    // ---- rel arithmetic, byte-for-byte the game's getRelativeFile(URI, String) ----

    static String relCalc(URI base, String s) {
        URI child = new File(new File(s).getAbsolutePath().toLowerCase(Locale.ENGLISH)).toURI();
        URI rel = base.relativize(child);
        if (rel.equals(child)) return s;
        String path = rel.getPath();
        if (s.endsWith("/") && !path.endsWith("/")) path = path + "/";
        return path;
    }

    static Map<String, String> buildRels(List<String> els, URI base) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String el : els) map.put(el, relCalc(base, el));
        return map;
    }

    // ---- loadList append ----

    private static Field loadListField;

    static boolean append(Object self, List<String> els) {
        try {
            if (loadListField == null) {
                loadListField = self.getClass().getDeclaredField("loadList");
                loadListField.setAccessible(true);
            }
            List<?> list = (List<?>) loadListField.get(self);
            if (list == null) return false;
            @SuppressWarnings("unchecked") List<String> l = (List<String>) list;
            l.addAll(els);
            return true;
        } catch (Throwable t) {
            guard.fail(t);
            return false;
        }
    }

    // ---- holders ----

    @Patch(className = "zombie.ZomboidFileSystem", methodName = "loadMod")
    public static class ModScan {
        @Patch.OnEnter
        public static void enter() { ModScanPatches.loadModEnter(); }

        @Patch.OnExit(onThrowable = Throwable.class)
        public static void exit() { ModScanPatches.loadModExit(); }
    }

    @Patch(className = "zombie.ZomboidFileSystem", methodName = "searchFolders")
    public static class WalkFolders {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object self, @Patch.Argument(0) File dir) {
            ModReadAhead.warm(dir);
            if (!ModScanPatches.guard.on) return false;
            try {
                return ModScanPatches.searchFolders(self, dir);
            } catch (Throwable t) {
                ModScanPatches.guard.fail(t);
                return false;
            }
        }
    }

    @Patch(className = "zombie.ZomboidFileSystem", methodName = "getRelativeFile", strictMatch = true)
    public static class RelAnswer {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) URI uri, @Patch.Argument(1) String s, @Patch.Local("r") String r) {
            if (!ModScanPatches.guard.on) return false;
            try {
                r = ModScanPatches.relFor(uri, s);
                return r != null;
            } catch (Throwable t) {
                ModScanPatches.guard.fail(t);
                return false;
            }
        }

        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) String ret, @Patch.Local("r") String r) {
            if (r != null) ret = r;
        }
    }
}
