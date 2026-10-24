package pzbutter;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.Attribute;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleParameterAnnotationsAttribute;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * modCompat (SPEC 3.10): other Java mods that patch a game method PZ Butter's holders patch, where at least one side
 * replaces stock. ZombieBuddy already knows every approved jar when our main runs (Loader.g_jarLoadStatus); their @Patch
 * annotations are read straight from the class files, nothing is loaded. report lists the overlaps; auto also answers
 * stock for the PZ Butter keys of the holders involved (Settings.forcedOff). Runs once, from Main, before any feature
 * init reads its key. Every failure ends in one log line and no change.
 */
final class ModCompat {
    private ModCompat() {}

    /** A @Patch class: the game class and method pattern it targets; where = the jar file name, or Outer.Holder for ours. */
    record Target(String className, String methodName, boolean replaces, String where) {}

    /** The classes whose nested classes are PZ Butter's holders. */
    static final Class<?>[] OUTERS = {Patches.class, DeliveryPatches.class, LuaPatches.class, UiPatches.class, MapPatches.class,
        SoundPatches.class, WorldPatches.class, ChunkPatches.class, LogPatches.class};

    private static final String PATCH = "Lme/zed_0xff/zombie_buddy/Patch;";
    private static final String ON_ENTER = "Lme/zed_0xff/zombie_buddy/Patch$OnEnter;";
    private static final String RETURN = "Lme/zed_0xff/zombie_buddy/Patch$Return;";
    private static final String ARGUMENT = "Lme/zed_0xff/zombie_buddy/Patch$Argument;";
    private static final String THIS = "Lme/zed_0xff/zombie_buddy/Patch$This;";
    private static final String ALL_ARGUMENTS = "Lme/zed_0xff/zombie_buddy/Patch$AllArguments;";
    private static final byte[] PATCH_ASCII = "me/zed_0xff/zombie_buddy/Patch".getBytes(StandardCharsets.US_ASCII);

    // ---- the keys each holder's advice reaches (the feature switches only: never enabled or frameLog) ----

    private static String[] k(String... keys) { return keys; }

    private static final String[] INPUT = k("inputLatch", "keyboardFresh", "frameStartGate");
    private static final String[] CAPS = k("vrrCap", "reflexSleep", "vsyncAdaptive");
    private static final String[] UI_READS = k("uiCensus", "uiRetained", "uiRetainCheck");
    private static final String[] UI_RETAIN = k("uiRetained", "uiRetainCheck");

    /**
     * Outer.Holder to the settings keys whose code path that holder's advice reaches. Forcing these off makes the
     * holder's advice a pass-through (a key that is stock-true, such as driveTreeCutaway, is answered true; see
     * Settings.STOCK_ON). A new holder without an entry fails keysTableCoversEveryHolder.
     */
    static final Map<String, String[]> KEYS = Map.ofEntries(
        // frame loop: everything driven by the frame counter, the main thread mark or the frame ticks
        Map.entry("Patches.Frame", k("luaWorkerGate", "luaFileWriteThrottle", "logDedupe", "luaBudget", "luaTiming", "frameStartGate",
            "presentPacing", "reflexSleep", "bakeScheduler", "lightSwitchCheckFrames", "gridStackInterval", "roofHideDebounceFrames",
            "soundTickHz", "mapStreetMemo", "mapStreetCache", "textWidthCache", "uiTiming", "uiCensus", "uiRetained", "uiRetainCheck")),
        // frame delivery
        Map.entry("DeliveryPatches.Step", k("limiterSleep", "reflexSleep")),
        Map.entry("DeliveryPatches.InvokeWake", k("limiterSleep", "reflexSleep")),
        Map.entry("DeliveryPatches.QueryWake", k("limiterSleep", "reflexSleep")),
        Map.entry("DeliveryPatches.QueueWake", k("limiterSleep", "reflexSleep")),
        Map.entry("DeliveryPatches.Push", k("frameStartGate", "presentPacing", "cursorLatch")),
        Map.entry("DeliveryPatches.Acquire", k("renderIdleWait", "frameStartGate", "presentPacing", "cursorLatch")),
        Map.entry("DeliveryPatches.KeyboardUpdate", INPUT),
        Map.entry("DeliveryPatches.KeyboardSwap", k("inputLatch")),
        Map.entry("DeliveryPatches.MouseSwap", k("inputLatch")),
        Map.entry("DeliveryPatches.RenderFlush", k("inputLatch")),
        Map.entry("DeliveryPatches.KeyboardPolled", INPUT),
        Map.entry("DeliveryPatches.CursorRecord", k("cursorLatch")),
        Map.entry("DeliveryPatches.RightDelay", k("aimHoldMs")),
        Map.entry("DeliveryPatches.PostRender", k("cursorLatch")),
        Map.entry("DeliveryPatches.SpriteNotify", k("renderIdleWait")),
        Map.entry("DeliveryPatches.RenderNotify", k("renderIdleWait")),
        Map.entry("DeliveryPatches.ContextWake", k("renderIdleWait")),
        Map.entry("DeliveryPatches.WorldEnter", k("reflexBoost")),
        Map.entry("DeliveryPatches.WorldExit", k("reflexBoost")),
        Map.entry("DeliveryPatches.OptionsWrite", CAPS),
        Map.entry("DeliveryPatches.LockFps", CAPS),
        Map.entry("DeliveryPatches.Uncapped", CAPS),
        Map.entry("DeliveryPatches.Vsync", k("vsyncAdaptive")),
        Map.entry("DeliveryPatches.DisplayModeSet", k("borderlessFullscreen")),
        Map.entry("DeliveryPatches.IsFullscreen", k("borderlessFullscreen")),
        Map.entry("DeliveryPatches.Swap", k("gpuMaxFrames", "presentPacing", "vblankLock", "frameStartGate", "reflexSleep", "vrrCap",
            "vsyncAdaptive", "vrr", "borderlessFullscreen")),
        // Lua
        Map.entry("LuaPatches.Rawget", k("luaRawgetFast", "uiCensus", "uiRetained", "uiRetainCheck")),
        Map.entry("LuaPatches.Rawset", UI_RETAIN),
        Map.entry("LuaPatches.Iterate", UI_READS),
        Map.entry("LuaPatches.LoadStream", k("luaInternConstants")),
        Map.entry("LuaPatches.LoadString", k("luaInternConstants")),
        Map.entry("LuaPatches.LuaLoad", k("uiLuaFast")),
        Map.entry("LuaPatches.Trigger", k("luaBudget", "luaTiming")),
        Map.entry("LuaPatches.Call", k("luaBudget", "luaSkipEmpty", "luaTiming", "luaWorkerGate")),
        Map.entry("LuaPatches.Pcall", k("luaSkipEmpty", "luaWorkerGate")),
        Map.entry("LuaPatches.PcallBoolean", k("luaWorkerGate")),
        Map.entry("LuaPatches.PcallReturn", k("luaWorkerGate")),
        Map.entry("LuaPatches.ProtectedBoolean", k("luaWorkerGate")),
        Map.entry("LuaPatches.ProtectedCall", k("luaWorkerGate")),
        Map.entry("LuaPatches.TriggerEvent", k("luaWorkerGate")),
        Map.entry("LuaPatches.TriggerHook", k("luaWorkerGate")),
        Map.entry("LuaPatches.FileWriter", k("luaFileWriteThrottle")),
        Map.entry("LuaPatches.FileReader", k("luaFileWriteThrottle")),
        Map.entry("LuaPatches.FileInput", k("luaFileWriteThrottle")),
        Map.entry("LuaPatches.FileOutput", k("luaFileWriteThrottle")),
        // log
        Map.entry("LogPatches.Writeln", k("logDedupe")),
        Map.entry("LogPatches.PrefixLine", k("logDedupe")),
        // UI
        Map.entry("UiPatches.MeasureX", k("textWidthCache")),
        Map.entry("UiPatches.FontsInit", k("textWidthCache", "uiRetained", "uiRetainCheck", "mapStreetMemo", "mapStreetCache")),
        Map.entry("UiPatches.Update", k("uiTickStagger")),
        Map.entry("UiPatches.JavaCall", UI_READS),
        Map.entry("UiPatches.JavaAnswer", UI_READS),
        Map.entry("UiPatches.OsCall", UI_READS),
        Map.entry("UiPatches.RandomCall", UI_READS),
        Map.entry("UiPatches.ItemIcon", UI_READS),
        Map.entry("UiPatches.UiRender", k("uiTiming", "uiRetained", "uiRetainCheck")),
        Map.entry("UiPatches.ElementRender", k("uiTiming", "uiCensus", "uiRetained", "uiRetainCheck")),
        Map.entry("UiPatches.LuaError", UI_RETAIN),
        Map.entry("UiPatches.Resize", UI_RETAIN),
        Map.entry("UiPatches.TexDestroy", UI_RETAIN),
        Map.entry("UiPatches.TexReload", UI_RETAIN),
        Map.entry("UiPatches.OptionFbo", UI_RETAIN),
        // world map
        Map.entry("MapPatches.Length", k("mapStreetMemo")),
        Map.entry("MapPatches.Name", k("mapStreetMemo")),
        Map.entry("MapPatches.StreetsRender", k("mapStreetMemo")),
        Map.entry("MapPatches.Compare", k("mapStreetMemo")),
        Map.entry("MapPatches.StreetInit", k("mapStreetCache")),
        Map.entry("MapPatches.Visited", k("mapVisitedFast")),
        // sound
        Map.entry("SoundPatches.ParameterUpdate", k("soundTickHz")),
        Map.entry("SoundPatches.ObjectEmittersUpdate", k("soundTickHz")),
        Map.entry("SoundPatches.AmbientWallsUpdate", k("soundTickHz")),
        Map.entry("SoundPatches.EmitterIdle", k("soundTickHz", "emitterIdleSkip")),
        Map.entry("SoundPatches.WorldUpdate", k("worldSoundCleanupFast")),
        Map.entry("SoundPatches.BiggestSoundZomb", k("hearingHoist")),
        Map.entry("SoundPatches.FishNoise", k("worldSoundFast")),
        Map.entry("SoundPatches.ZoneValue", k("soundZoneCache")),
        Map.entry("SoundPatches.ZoneAdded", k("soundZoneCache")),
        Map.entry("SoundPatches.ZoneRemoved", k("soundZoneCache")),
        Map.entry("SoundPatches.Electricity", k("electricityLevelRange")),
        Map.entry("SoundPatches.FliesUpdate", k("fliesToggleFix")),
        Map.entry("SoundPatches.FliesSet", k("fliesToggleFix")),
        Map.entry("SoundPatches.AddSound", k("fmodNullHandleFix")),
        Map.entry("SoundPatches.EventInstance", k("fmodNullHandleFix")),
        Map.entry("SoundPatches.LimiterInit", k("audioLimiter")),
        // world: lighting, cutaways, weather
        Map.entry("WorldPatches.DirtyChunks", k("occlusionSkipLightingOnly", "occlusionGrantedOnly", "bakeScheduler")),
        Map.entry("WorldPatches.BuildingsInFront", k("gridStackInterval", "roofHideDebounceFrames")),
        Map.entry("WorldPatches.LightInfoStamp", k("lightInfoOncePerFrame")),
        Map.entry("WorldPatches.SwitchPower", k("lightSwitchCheckFrames")),
        Map.entry("WorldPatches.CutawayVisit", k("cutawayInvalidateChanged", "cutawayVisitPrefilter", "cutawayRadius")),
        Map.entry("WorldPatches.OccluderReplay", k("cutawayFast")),
        Map.entry("WorldPatches.ChunkPrepared", k("cutawayFast", "bakeScheduler")),
        Map.entry("WorldPatches.IngameEnter", k("lightingFps")),
        Map.entry("WorldPatches.RainSplashes", k("rainSplashesFast")),
        Map.entry("WorldPatches.WeatherMask", k("weatherMaskIdleSkip")),
        // chunk textures
        Map.entry("ChunkPatches.BloodBake", k("bloodFadeFix")),
        Map.entry("ChunkPatches.BlockedTo", k("edgeTestFast")),
        Map.entry("ChunkPatches.BakeMips", k("bakeMipLevels")),
        Map.entry("ChunkPatches.TreeEveryFrame", k("treesInChunkTexture")),
        Map.entry("ChunkPatches.TranslucentTree", k("driveTreeCutaway")),
        Map.entry("ChunkPatches.ObjectLayer", k("translucentTilesInChunkTexture")),
        Map.entry("ChunkPatches.Plan", k("bakeScheduler", "treeBakeMaxChunksPerSec")),
        Map.entry("ChunkPatches.BakeGate", k("bakeScheduler", "treeBakeMaxChunksPerSec")),
        Map.entry("ChunkPatches.ZoomScroll", k("zoomEaseMs")),
        Map.entry("ChunkPatches.ZoomUpdate", k("zoomEaseMs")));

    // ---- reading @Patch annotations without loading the class ----

    /**
     * The @Patch of one class file: className and methodName, and whether the patch replaces stock (isAdvice false, an
     * OnEnter with skipOn true, a Return, Argument, This or AllArguments with readOnly false). Empty for a class without @Patch.
     */
    static List<Target> scanClass(byte[] classFile, String where) {
        if (classFile == null || indexOf(classFile, PATCH_ASCII) < 0) return List.of();   // most classes: skip the parse
        ClassModel cm = ClassFile.of().parse(classFile);
        String cls = null, method = null;
        boolean replaces = false, patched = false;
        for (Attribute<?> a : cm.attributes()) {
            if (!(a instanceof RuntimeVisibleAnnotationsAttribute rva)) continue;
            for (Annotation an : rva.annotations()) {
                if (!an.className().stringValue().equals(PATCH)) continue;
                patched = true;
                for (var e : an.elements()) {
                    String n = e.name().stringValue();
                    if (n.equals("className") && e.value() instanceof AnnotationValue.OfString s) cls = s.stringValue();
                    else if (n.equals("methodName") && e.value() instanceof AnnotationValue.OfString s) method = s.stringValue();
                    else if (n.equals("isAdvice") && e.value() instanceof AnnotationValue.OfBoolean b && !b.booleanValue()) replaces = true;
                }
            }
        }
        if (!patched || cls == null || method == null) return List.of();
        for (MethodModel mm : cm.methods()) {
            for (Attribute<?> a : mm.attributes()) {
                if (a instanceof RuntimeVisibleAnnotationsAttribute rva) {
                    for (Annotation an : rva.annotations())
                        if (an.className().stringValue().equals(ON_ENTER) && flagIs(an, "skipOn", true, false)) replaces = true;
                } else if (a instanceof RuntimeVisibleParameterAnnotationsAttribute rpa) {
                    for (List<Annotation> one : rpa.parameterAnnotations())
                        for (Annotation an : one) {
                            String n = an.className().stringValue();
                            if ((n.equals(RETURN) || n.equals(ARGUMENT) || n.equals(THIS) || n.equals(ALL_ARGUMENTS)) && flagIs(an, "readOnly", false, true)) replaces = true;
                        }
                }
            }
        }
        return List.of(new Target(cls, method, replaces, where));
    }

    /** True when the annotation's boolean element has the wanted value; dflt is the element's declared default. */
    private static boolean flagIs(Annotation an, String element, boolean wanted, boolean dflt) {
        for (var e : an.elements())
            if (e.name().stringValue().equals(element) && e.value() instanceof AnnotationValue.OfBoolean b) return b.booleanValue() == wanted;
        return dflt == wanted;
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0, last = hay.length - needle.length; i <= last; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    /** scanClass over every class entry of a jar; an entry that does not parse is skipped. */
    static List<Target> scanJar(Path jar) throws IOException {
        List<Target> out = new ArrayList<>();
        String where = String.valueOf(jar.getFileName());
        try (ZipFile z = new ZipFile(jar.toFile())) {
            for (var en = z.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory() || !e.getName().endsWith(".class")) continue;
                try (InputStream in = z.getInputStream(e)) { out.addAll(scanClass(in.readAllBytes(), where)); }
                catch (RuntimeException | LinkageError bad) { /* a class this parser cannot read patches nothing we can see */ }
            }
        }
        return out;
    }

    /** PZ Butter's own holders, from the class files of the nested classes of OUTERS. */
    static List<Target> ours() {
        List<Target> out = new ArrayList<>();
        for (Class<?> outer : OUTERS)
            for (Class<?> c : outer.getDeclaredClasses()) {
                try { out.addAll(scanClass(classBytes(c), outer.getSimpleName() + "." + c.getSimpleName())); }
                catch (IOException | RuntimeException | LinkageError e) { System.out.println("[PZButter] modCompat: cannot read " + c.getName() + ": " + e); }
            }
        return out;
    }

    private static byte[] classBytes(Class<?> c) throws IOException {
        String n = c.getName();
        try (InputStream in = c.getResourceAsStream(n.substring(n.lastIndexOf('.') + 1) + ".class")) { return in == null ? null : in.readAllBytes(); }
    }

    // ---- conflicts ----

    /** ZombieBuddy's method pattern (SyntaxSugar.methodMatcher): * all, &lt;init&gt;, /regex/, *x*, *x, x*, else equal. */
    static boolean nameMatches(String pattern, String method) {
        if (pattern.equals("*")) return true;
        if (pattern.equals("<init>")) return method.equals("<init>");
        int n = pattern.length();
        if (n >= 2 && pattern.startsWith("*") && pattern.endsWith("*")) return method.contains(pattern.substring(1, n - 1));
        if (pattern.startsWith("*")) return method.endsWith(pattern.substring(1));
        if (pattern.endsWith("*")) return method.startsWith(pattern.substring(0, n - 1));
        if (n >= 2 && pattern.startsWith("/") && pattern.endsWith("/")) {
            try { return method.matches(pattern.substring(1, n - 1)); }
            catch (RuntimeException badRegex) { return false; }
        }
        return method.equals(pattern);
    }

    /** {ourWhere, class.method, theirWhere, side} for each pair on one game method where at least one side replaces stock. */
    static List<String[]> conflicts(List<Target> ours, List<Target> theirs) {
        List<String[]> out = new ArrayList<>();
        for (Target o : ours)
            for (Target t : theirs) {
                if (!o.className().equals(t.className()) || !nameMatches(t.methodName(), o.methodName())) continue;
                if (!o.replaces() && !t.replaces()) continue;   // two observing advices compose
                out.add(new String[] {o.where(), o.className() + "." + o.methodName(), t.where(), o.replaces() && t.replaces() ? "both" : o.replaces() ? "ours" : "theirs"});
            }
        return out;
    }

    /**
     * off: nothing. report: one log line per overlap. auto: the same, plus the keys of every holder involved, returned for
     * Settings.forcedOff and listed in a last line. report returns an empty set.
     */
    static Set<String> apply(String mode, List<Target> ours, List<Target> theirs, Consumer<String> log) {
        if (!mode.equals("report") && !mode.equals("auto")) return Set.of();
        Set<String> lines = new LinkedHashSet<>();
        Set<String> keys = new TreeSet<>();
        for (String[] c : conflicts(ours, theirs)) {
            String[] held = KEYS.get(c[0]);
            if (held == null) held = new String[0];
            String who = c[3].equals("ours") ? "PZ Butter replaces" : c[3].equals("theirs") ? "the other mod replaces" : "both replace";
            lines.add("[PZButter] modCompat: " + c[2] + " patches " + c[1] + " (" + who + " the game method); PZ Butter " + c[0] + ": "
                + (held.length == 0 ? "no key" : String.join(", ", held)));
            keys.addAll(List.of(held));
        }
        lines.forEach(log);
        if (!mode.equals("auto")) return Set.of();
        if (!keys.isEmpty()) log.accept("[PZButter] modCompat: switched off " + String.join(", ", keys) + " (modCompat=auto)");
        return keys;
    }

    // ---- ZombieBuddy's jar list ----

    /** off without the master switch; else modCompat as report or auto, anything else off (the profile may set it). */
    static String mode() {
        if (!Settings.enabled) return "off";
        String v = Settings.raw("modCompat");
        v = v == null ? "off" : v.trim().toLowerCase(java.util.Locale.ROOT);
        return v.equals("report") || v.equals("auto") ? v : "off";
    }

    /**
     * The approved Java mod jars ZombieBuddy knows (Loader.g_jarLoadStatus values with decision TRUE) except ours; null,
     * after one log line, when ZombieBuddy has no such list (another version).
     */
    static List<Path> jarList() {
        try {
            Class<?> loader = Class.forName("me.zed_0xff.zombie_buddy.Loader", false, ModCompat.class.getClassLoader());
            VarHandle status = MethodHandles.privateLookupIn(loader, MethodHandles.lookup()).findStaticVarHandle(loader, "g_jarLoadStatus", Map.class);
            return jarsFrom((Map<?, ?>) status.get(), ownJar());
        } catch (Throwable t) {
            System.out.println("[PZButter] modCompat: no ZombieBuddy jar list");
            return null;
        }
    }

    /** jarList, empty when there is none. */
    static List<Path> otherJars() {
        List<Path> l = jarList();
        return l == null ? List.of() : l;
    }

    /** The jars of the JavaModLoadState values (record accessors decision() and jarPath()) that were loaded, minus own. */
    static List<Path> jarsFrom(Map<?, ?> status, Path own) throws Throwable {
        List<Path> out = new ArrayList<>();
        Collection<?> states = status.values();
        MethodHandle decision = null, jarPath = null;
        for (Object s : states) {
            if (s == null) continue;
            if (decision == null) {
                MethodHandles.Lookup l = MethodHandles.privateLookupIn(s.getClass(), MethodHandles.lookup());
                decision = l.findVirtual(s.getClass(), "decision", MethodType.methodType(Boolean.class));
                jarPath = l.findVirtual(s.getClass(), "jarPath", MethodType.methodType(Path.class));
            }
            if (!Boolean.TRUE.equals((Boolean) decision.invoke(s))) continue;
            Path p = (Path) jarPath.invoke(s);
            if (p != null && !isOurs(p, own)) out.add(p);
        }
        return out;
    }

    /** The jar (or directory) this class was loaded from, or null. */
    static Path ownJar() {
        try { return Path.of(ModCompat.class.getProtectionDomain().getCodeSource().getLocation().toURI()); }
        catch (Throwable t) { return null; }
    }

    private static boolean isOurs(Path p, Path own) {
        try {
            if (own != null && (p.toAbsolutePath().normalize().equals(own.toAbsolutePath().normalize()) || p.toRealPath().equals(own.toRealPath()))) return true;
            if (java.nio.file.Files.isRegularFile(p))   // a copy of our jar under another path
                try (ZipFile z = new ZipFile(p.toFile())) { return z.getEntry("pzbutter/ModCompat.class") != null; }
        } catch (Throwable t) { /* unreadable: scanned (and reported) later */ }
        return false;
    }

    /** Main, before any feature init: scan, report, and for auto answer stock for the keys of the holders involved. */
    static void init() {
        String m = mode();
        if (m.equals("off")) return;
        try {
            List<Path> jars = jarList();
            if (jars == null) return;
            List<Target> theirs = new ArrayList<>();
            for (Path p : jars) {
                try { theirs.addAll(scanJar(p)); }
                catch (Throwable t) { System.out.println("[PZButter] modCompat: cannot scan " + p.getFileName() + ": " + t); }
            }
            Set<String> off = apply(m, ours(), theirs, System.out::println);
            Settings.forcedOff.addAll(off);
            System.out.println("[PZButter] modCompat=" + m + ": " + jars.size() + " other Java mod" + (jars.size() == 1 ? "" : "s") + " scanned"
                + (off.isEmpty() ? "" : ", " + off.size() + " key" + (off.size() == 1 ? "" : "s") + " switched off"));
        } catch (Throwable t) {
            System.out.println("[PZButter] modCompat failed: " + t);
        }
    }
}
