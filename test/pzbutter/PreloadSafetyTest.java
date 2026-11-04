package pzbutter;

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ZombieBuddy preloads PZ Butter (mod.info javaPreload=true) at JVM start. Every game class our signatures name is loaded
 * then, and installing the patches retransforms it, which parses its class annotations. A class annotated with
 * zombie.network.PacketSetting brings that annotation's AntiCheat[] default: AntiCheat and ServerOptions initialise
 * before the game's Rand exists, ServerOptions stays broken, and the game dies at its first GameClient use (2026-11-04,
 * VehicleFullUpdatePacket in MpClientPatches). So no PZ Butter signature may name such a class; cast in the body instead.
 */
final class PreloadSafetyTest {
    private PreloadSafetyTest() {}

    static void run() throws Exception {
        assert annotatedPacketSetting(Class.forName("zombie.network.packets.vehicle.VehicleFullUpdatePacket", false,
            PreloadSafetyTest.class.getClassLoader())) : "check must see the annotation";
        Path root = Path.of(PreloadSafetyTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> bad = new ArrayList<>();
        List<Path> files;
        try (var s = Files.walk(root.resolve("pzbutter"))) { files = s.filter(f -> f.toString().endsWith(".class")).toList(); }
        for (Path p : files) {
            String name = root.relativize(p).toString().replace('/', '.').replaceAll("\\.class$", "");
            if (name.replaceAll("\\$.*", "").endsWith("Test")) continue;   // test classes are not in the shipped jar
            Class<?> c = Class.forName(name, false, PreloadSafetyTest.class.getClassLoader());
            Set<Class<?>> types = new HashSet<>();
            for (var m : c.getDeclaredMethods()) { types.add(m.getReturnType()); types.addAll(List.of(m.getParameterTypes())); }
            for (var k : c.getDeclaredConstructors()) types.addAll(List.of(k.getParameterTypes()));
            for (var f : c.getDeclaredFields()) types.add(f.getType());
            for (Class<?> t : types) if (annotatedPacketSetting(t)) bad.add(name + " names " + t.getName());
        }
        assert bad.isEmpty() : "signatures that load @PacketSetting classes at preload: " + bad;
    }

    /** Read from the class file: getDeclaredAnnotations would itself initialise AntiCheat. */
    static boolean annotatedPacketSetting(Class<?> t) throws Exception {
        while (t.isArray()) t = t.getComponentType();
        if (t.isPrimitive() || !t.getName().startsWith("zombie.")) return false;
        try (var in = t.getClassLoader().getResourceAsStream(t.getName().replace('.', '/') + ".class")) {
            if (in == null) return false;
            return ClassFile.of().parse(in.readAllBytes()).findAttribute(Attributes.runtimeVisibleAnnotations())
                .map(a -> a.annotations().stream().anyMatch(x -> x.className().stringValue().equals("Lzombie/network/PacketSetting;")))
                .orElse(false);
        }
    }
}
