package pzbutter;

import java.io.File;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Variable refresh (SPEC 3.12). vrr=auto reads the display driver (libdrm, Linux): for each connected output its
 * mode, refresh, vrr_capable (connector) and VRR_ENABLED (CRTC). The window's monitor is matched by mode size and
 * refresh; unknown (null) when nothing matches, the matches disagree, or libdrm can't be read.
 * borderlessFullscreen: the game's borderless window becomes a fullscreen window at the desktop mode, so the
 * compositor (KWin, Mutter, gamescope) treats it as fullscreen and can enable VRR.
 */
final class Vrr {
    static final Guard guard = new Guard("vrr");
    static volatile String mode = "off", fullscreenWindow = "off";
    static volatile Boolean probed;
    static volatile boolean fullscreenWindowApplied;

    private static final int OBJ_CRTC = 0xcccccccc, OBJ_CONNECTOR = 0xc0c0c0c0, O_RDONLY_CLOEXEC = 0x80000;

    private Vrr() {}

    static void init() {
        mode = Settings.mode("vrr");
        fullscreenWindow = Settings.mode("borderlessFullscreen");
        guard.on = !mode.equals("off") || !fullscreenWindow.equals("off");
    }

    static Boolean active() { return resolve(mode, probed); }

    static Boolean resolve(String mode, Boolean probed) {
        switch (mode) {
            case "on": return Boolean.TRUE;
            case "auto": return probed;
            default: return Boolean.FALSE;
        }
    }

    /** outputs: {w, h, refresh, vrr_capable, VRR_ENABLED}. VRR for the monitor of that size and refresh, or null. */
    static Boolean pick(int[][] outputs, int w, int h, int refreshHz) {
        Boolean found = null;
        boolean any = false;
        for (int[] o : outputs) {
            if (o[0] != w || o[1] != h) continue;
            if (refreshHz > 0 && o[2] > 0 && Math.abs(o[2] - refreshHz) > 1) continue;
            boolean v = o[3] != 0 && o[4] != 0;
            if (any && found != v) return null;
            any = true;
            found = v;
        }
        return found;
    }

    static boolean wantFullscreenWindow(String mode, Boolean vrr, boolean borderless, boolean fullscreen) {
        if (!borderless || fullscreen) return false;
        return mode.equals("on") || mode.equals("auto") && Boolean.TRUE.equals(vrr);
    }

    /** Render thread, every few seconds with the window's monitor: refresh the auto result. */
    static void update(int[] monitorRect, int refreshHz) {
        if (!mode.equals("auto") || monitorRect == null) return;
        int[][] o = probe("libdrm.so.2");
        Boolean v = o == null ? null : pick(o, monitorRect[2], monitorRect[3], refreshHz);
        if (v != probed) System.out.println("[PZButter] vrr: " + (v == null ? "unknown" : v ? "active" : "not active"));
        probed = v;
    }

    /** Every connected output of every /dev/dri/card*, or null when libdrm can't be used. */
    static int[][] probe(String lib) {
        try {
            Linker l = Linker.nativeLinker();
            SymbolLookup drm = SymbolLookup.libraryLookup(lib, Arena.global());
            SymbolLookup libc = l.defaultLookup();
            MethodHandle open = l.downcallHandle(libc.find("open").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
            MethodHandle close = l.downcallHandle(libc.find("close").orElseThrow(), FunctionDescriptor.of(JAVA_INT, JAVA_INT));
            Drm d = new Drm(l, drm);
            File[] cards = new File("/dev/dri").listFiles((dir, n) -> n.startsWith("card"));
            if (cards == null) return null;
            List<int[]> out = new ArrayList<>();
            for (File card : cards) {
                try (Arena ar = Arena.ofConfined()) {
                    int fd = (int) open.invokeExact(ar.allocateFrom(card.getPath()), O_RDONLY_CLOEXEC);
                    if (fd < 0) continue;
                    try { d.outputs(fd, out); } finally { int ignored = (int) close.invokeExact(fd); }
                }
            }
            return out.toArray(new int[0][]);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The libdrm calls and struct offsets used (xf86drmMode.h). */
    private static final class Drm {
        final MethodHandle getRes, getConn, getEnc, getCrtc, getProps, getProp, freeRes, freeConn, freeEnc, freeCrtc, freeProps, freeProp;

        Drm(Linker l, SymbolLookup drm) {
            getRes = h(l, drm, "drmModeGetResources", FunctionDescriptor.of(ADDRESS, JAVA_INT));
            getConn = h(l, drm, "drmModeGetConnector", FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_INT));
            getEnc = h(l, drm, "drmModeGetEncoder", FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_INT));
            getCrtc = h(l, drm, "drmModeGetCrtc", FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_INT));
            getProps = h(l, drm, "drmModeObjectGetProperties", FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
            getProp = h(l, drm, "drmModeGetProperty", FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_INT));
            freeRes = h(l, drm, "drmModeFreeResources", FunctionDescriptor.ofVoid(ADDRESS));
            freeConn = h(l, drm, "drmModeFreeConnector", FunctionDescriptor.ofVoid(ADDRESS));
            freeEnc = h(l, drm, "drmModeFreeEncoder", FunctionDescriptor.ofVoid(ADDRESS));
            freeCrtc = h(l, drm, "drmModeFreeCrtc", FunctionDescriptor.ofVoid(ADDRESS));
            freeProps = h(l, drm, "drmModeFreeObjectProperties", FunctionDescriptor.ofVoid(ADDRESS));
            freeProp = h(l, drm, "drmModeFreeProperty", FunctionDescriptor.ofVoid(ADDRESS));
        }

        private static MethodHandle h(Linker l, SymbolLookup lib, String name, FunctionDescriptor fd) {
            return l.downcallHandle(lib.find(name).orElseThrow(), fd);
        }

        void outputs(int fd, List<int[]> out) throws Throwable {
            MemorySegment res = (MemorySegment) getRes.invokeExact(fd);
            if (res.equals(MemorySegment.NULL)) return;
            try {
                res = res.reinterpret(64);
                int n = res.get(JAVA_INT, 32);   // count_connectors
                MemorySegment ids = res.get(ADDRESS, 40).reinterpret(4L * n);
                for (int i = 0; i < n; i++) {
                    int cid = ids.getAtIndex(JAVA_INT, i);
                    MemorySegment c = (MemorySegment) getConn.invokeExact(fd, cid);
                    if (c.equals(MemorySegment.NULL)) continue;
                    try {
                        c = c.reinterpret(80);
                        int encId = c.get(JAVA_INT, 4);
                        if (c.get(JAVA_INT, 16) != 1 || encId == 0) continue;   // connection: 1 = connected
                        int crtcId = crtcOf(fd, encId);
                        if (crtcId == 0) continue;
                        MemorySegment cr = (MemorySegment) getCrtc.invokeExact(fd, crtcId);
                        if (cr.equals(MemorySegment.NULL)) continue;
                        try {
                            cr = cr.reinterpret(120);
                            if (cr.get(JAVA_INT, 24) == 0) continue;   // mode_valid
                            out.add(new int[] {cr.get(JAVA_INT, 16), cr.get(JAVA_INT, 20), cr.get(JAVA_INT, 52),
                                (int) prop(fd, cid, OBJ_CONNECTOR, "vrr_capable"), (int) prop(fd, crtcId, OBJ_CRTC, "VRR_ENABLED")});
                        } finally { freeCrtc.invokeExact(cr); }
                    } finally { freeConn.invokeExact(c); }
                }
            } finally { freeRes.invokeExact(res); }
        }

        private int crtcOf(int fd, int encId) throws Throwable {
            MemorySegment e = (MemorySegment) getEnc.invokeExact(fd, encId);
            if (e.equals(MemorySegment.NULL)) return 0;
            try { return e.reinterpret(20).get(JAVA_INT, 8); } finally { freeEnc.invokeExact(e); }
        }

        /** A property value of a DRM object by name; 0 when absent. */
        private long prop(int fd, int obj, int type, String name) throws Throwable {
            MemorySegment ps = (MemorySegment) getProps.invokeExact(fd, obj, type);
            if (ps.equals(MemorySegment.NULL)) return 0;
            try {
                ps = ps.reinterpret(24);
                int n = ps.get(JAVA_INT, 0);
                MemorySegment ids = ps.get(ADDRESS, 8).reinterpret(4L * n), vals = ps.get(ADDRESS, 16).reinterpret(8L * n);
                for (int i = 0; i < n; i++) {
                    MemorySegment p = (MemorySegment) getProp.invokeExact(fd, ids.getAtIndex(JAVA_INT, i));
                    if (p.equals(MemorySegment.NULL)) continue;
                    try { if (p.reinterpret(40).getString(8).equals(name)) return vals.getAtIndex(JAVA_LONG, i); }
                    finally { freeProp.invokeExact(p); }
                }
                return 0;
            } finally { freeProps.invokeExact(ps); }
        }
    }
}
