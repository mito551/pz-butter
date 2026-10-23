package pzbutter;

import java.io.File;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * audioLimiter (Plan 4): FMOD's own limiter DSP at the head of the master channel group, called through FFM on the
 * libfmod that the game already loaded. Installed once: at pzbutter's init when FMODManager.init has already returned (ZB loads mods after it), else from its OnExit (SoundPatches). Every
 * FMOD_RESULT other than 0 logs one line and stops; nothing here throws into the game.
 */
final class AudioLimiter {
    static volatile boolean on;
    static volatile int ceilingDb = -2;
    static volatile boolean fold;
    private static boolean installed;
    private static Fmod fmod;

    private static final int DSP_TYPE_LIMITER = 11, DSP_LIMITER_CEILING = 1, SPEAKER_STEREO = 3, CHANNEL_MASK_STEREO = 0x3;
    private static final int DRIVER_NAME_LEN = 256, GUID_BYTES = 16;

    private AudioLimiter() {}

    static void init() {
        on = Settings.flag("audioLimiter");
        ceilingDb = Settings.num("audioLimiterCeilingDb", -2, -12, 0);
        fold = Settings.flag("audioLimiterStereoFold");
    }

    /** The limiter's ceiling in dB, clamped to FMOD's range. */
    static float ceiling(int db) {
        return Math.max(-12f, Math.min(0f, db));
    }

    /** The first libfmod along the library path for this OS, or null. Relative entries resolve against the working directory. */
    static Path findLibrary(String javaLibraryPath, String osName) {
        if (javaLibraryPath == null || osName == null) return null;
        String name = osName.startsWith("Windows") ? "fmod.dll" : osName.startsWith("Mac") ? "libfmod.dylib" : "libfmod.so";
        for (String entry : javaLibraryPath.split(Pattern.quote(File.pathSeparator))) {
            if (entry.isEmpty()) continue;
            Path p = Paths.get(entry).toAbsolutePath().resolve(name).normalize();
            if (Files.isRegularFile(p)) return p;
        }
        return null;
    }

    /** True for the first caller only: pzbutter's init and the FMODManager.init hook may both ask. */
    static synchronized boolean claim() {
        if (installed) return false;
        installed = true;
        return true;
    }

    static void resetForTest() { installed = false; }

    /** Attaches the limiter to the master group, once per run. Called from SoundPatches.onFmodInit, which has checked the keys. */
    static void install(long masterChannelGroup) throws Throwable {
        if (!claim()) return;
        Path lib = findLibrary(System.getProperty("java.library.path"), System.getProperty("os.name"));
        if (lib == null) {
            System.out.println("[PZButter] audioLimiter: libfmod not found on java.library.path");
            return;
        }
        if (fmod == null) fmod = new Fmod(Linker.nativeLinker(), SymbolLookup.libraryLookup(lib, Arena.global()));
        Fmod f = fmod;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cg = MemorySegment.ofAddress(masterChannelGroup);
            MemorySegment outSys = arena.allocate(ADDRESS);
            if (!ok("FMOD_ChannelGroup_GetSystemObject", (int) f.getSystemObject.invokeExact(cg, outSys))) return;
            MemorySegment sys = outSys.get(ADDRESS, 0);

            MemorySegment outDsp = arena.allocate(ADDRESS);
            if (!ok("FMOD_System_CreateDSPByType", (int) f.createDspByType.invokeExact(sys, DSP_TYPE_LIMITER, outDsp))) return;
            MemorySegment dsp = outDsp.get(ADDRESS, 0);

            if (!ok("FMOD_DSP_SetParameterFloat", (int) f.setParameterFloat.invokeExact(dsp, DSP_LIMITER_CEILING, ceiling(ceilingDb)))) return;

            String foldState = "off";
            if (fold) {
                MemorySegment driver = arena.allocate(JAVA_INT);
                if (!ok("FMOD_System_GetDriver", (int) f.getDriver.invokeExact(sys, driver))) return;
                MemorySegment rate = arena.allocate(JAVA_INT), mode = arena.allocate(JAVA_INT), channels = arena.allocate(JAVA_INT);
                MemorySegment name = arena.allocate(DRIVER_NAME_LEN), guid = arena.allocate(GUID_BYTES);
                if (!ok("FMOD_System_GetDriverInfo", (int) f.getDriverInfo.invokeExact(sys, driver.get(JAVA_INT, 0), name,
                        DRIVER_NAME_LEN, guid, rate, mode, channels))) return;
                if (mode.get(JAVA_INT, 0) == SPEAKER_STEREO) {
                    if (!ok("FMOD_DSP_SetChannelFormat", (int) f.setChannelFormat.invokeExact(dsp, CHANNEL_MASK_STEREO, 2, SPEAKER_STEREO))) return;
                    foldState = "on";
                } else {
                    foldState = "n/a";
                }
            }

            if (!ok("FMOD_ChannelGroup_AddDSP", (int) f.addDsp.invokeExact(cg, 0, dsp))) return;
            System.out.println("[PZButter] audioLimiter: on, ceiling " + ceilingDb + " dB, stereo fold " + foldState);
        }
    }

    private static boolean ok(String call, int result) {
        if (result == 0) return true;
        System.out.println("[PZButter] audioLimiter: " + call + " failed (" + result + ")");
        return false;
    }

    /** The FMOD core calls used here, bound once. Signatures from FMOD 2.03 fmod.h. */
    private static final class Fmod {
        final MethodHandle getSystemObject, createDspByType, setParameterFloat, getDriver, getDriverInfo, setChannelFormat, addDsp;

        Fmod(Linker l, SymbolLookup lib) {
            // FMOD_RESULT FMOD_ChannelGroup_GetSystemObject(FMOD_CHANNELGROUP*, FMOD_SYSTEM**)
            getSystemObject = h(l, lib, "FMOD_ChannelGroup_GetSystemObject", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            // FMOD_RESULT FMOD_System_CreateDSPByType(FMOD_SYSTEM*, FMOD_DSP_TYPE, FMOD_DSP**)
            createDspByType = h(l, lib, "FMOD_System_CreateDSPByType", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
            // FMOD_RESULT FMOD_DSP_SetParameterFloat(FMOD_DSP*, int index, float value)
            setParameterFloat = h(l, lib, "FMOD_DSP_SetParameterFloat", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_FLOAT));
            // FMOD_RESULT FMOD_System_GetDriver(FMOD_SYSTEM*, int* driver)
            getDriver = h(l, lib, "FMOD_System_GetDriver", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            // FMOD_RESULT FMOD_System_GetDriverInfo(FMOD_SYSTEM*, int id, char* name, int namelen, FMOD_GUID*,
            //                                       int* systemrate, FMOD_SPEAKERMODE*, int* speakermodechannels)
            getDriverInfo = h(l, lib, "FMOD_System_GetDriverInfo",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
            // FMOD_RESULT FMOD_DSP_SetChannelFormat(FMOD_DSP*, FMOD_CHANNELMASK, int channels, FMOD_SPEAKERMODE)
            setChannelFormat = h(l, lib, "FMOD_DSP_SetChannelFormat", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
            // FMOD_RESULT FMOD_ChannelGroup_AddDSP(FMOD_CHANNELGROUP*, int index, FMOD_DSP*)
            addDsp = h(l, lib, "FMOD_ChannelGroup_AddDSP", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        }

        private static MethodHandle h(Linker l, SymbolLookup lib, String name, FunctionDescriptor fd) {
            return l.downcallHandle(lib.find(name).orElseThrow(), fd);
        }
    }
}
