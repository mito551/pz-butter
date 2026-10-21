package pzbutter;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.util.List;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.core.Translator;
import zombie.worldMap.UIWorldMap;
import zombie.worldMap.UIWorldMapV3;
import zombie.worldMap.WorldMap;
import zombie.worldMap.WorldMapRenderer;
import zombie.worldMap.WorldMapVisited;
import zombie.worldMap.streets.WorldMapStreet;
import zombie.worldMap.streets.WorldMapStreets;
import zombie.worldMap.streets.WorldMapStreetsV1;

/**
 * World map hooks (MapFast), one holder per game method; each advice is one call to a public static method here
 * (advice is inlined into the game class, so it may only reach public members).
 */
public final class MapPatches {
    private MapPatches() {}

    static final Guard guard = new Guard("map speed-ups");

    // ---- the view's projection, once per frame per map ----

    private static Object viewMap;
    private static long viewFrame = -1;
    private static final double[] view = new double[4];
    private static boolean viewLinear;

    /** The linear part of world→UI for this map this frame; false when the projection isn't linear. */
    private static boolean view(UIWorldMap map) {
        if (map == viewMap && FrameClock.frameNo == viewFrame) return viewLinear;
        viewMap = map;
        viewFrame = FrameClock.frameNo;
        UIWorldMapV3 api = map.getAPI();
        float cx = 0, cy = 0, d = 100;
        double x0 = api.worldToUIX(cx, cy), y0 = api.worldToUIY(cx, cy);
        double xa = api.worldToUIX(cx + d, cy), ya = api.worldToUIY(cx + d, cy);
        double xb = api.worldToUIX(cx, cy + d), yb = api.worldToUIY(cx, cy + d);
        double xc = api.worldToUIX(cx + d, cy + d), yc = api.worldToUIY(cx + d, cy + d);
        view[0] = (xa - x0) / d; view[1] = (xb - x0) / d; view[2] = (ya - y0) / d; view[3] = (yb - y0) / d;
        viewLinear = Math.abs(xc - (xa + xb - x0)) < 0.01 && Math.abs(yc - (ya + yb - y0)) < 0.01;
        if (!viewLinear && MapFast.memo) {
            MapFast.memo = false;
            System.out.println("[PZButter] mapStreetMemo off: the map projection is not linear");
        }
        return viewLinear;
    }

    // ---- mapStreetMemo ----

    /** WorldMapStreet.getLength entry: the cached length, or null to compute it. */
    public static Object cachedLength(Object street, Object map) {
        if (!MapFast.memo || !guard.on) return null;
        try { return view((UIWorldMap) map) ? MapFast.length(street, view) : null; }
        catch (Throwable t) { guard.fail(t); return null; }
    }

    public static void storeLength(Object street, Object map, float length) {
        if (!MapFast.memo || !guard.on) return;
        try { if (view((UIWorldMap) map)) MapFast.putLength(street, view, length); }
        catch (Throwable t) { guard.fail(t); }
    }

    /** WorldMapStreet.getTranslatedText entry: the cached text for this language, or null. */
    public static String cachedName(Object street) {
        if (!MapFast.memo || !guard.on) return null;
        try {
            WorldMapStreet s = (WorldMapStreet) street;
            return MapFast.name(s, s.getUntranslatedText(), Translator.getLanguage());
        } catch (Throwable t) { guard.fail(t); return null; }
    }

    public static void storeName(Object street, String text) {
        if (!MapFast.memo || !guard.on) return;
        try {
            WorldMapStreet s = (WorldMapStreet) street;
            MapFast.putName(s, s.getUntranslatedText(), Translator.getLanguage(), text);
        } catch (Throwable t) { guard.fail(t); }
    }

    private static VarHandle streetsField;
    private static boolean streetsBound;

    /** WorldMapStreets.render entry: a new sort. */
    public static void onStreetsRender() { if (MapFast.memo) MapFast.newSort(); }

    /** WorldMapStreets.lambda$render$0 (the sort comparator, indexOf(a) - indexOf(b)): the answer, or null. */
    public static Object compareStreets(Object streets, Object a, Object b) {
        if (!MapFast.memo || !guard.on) return null;
        try {
            if (!streetsBound) { streetsBound = true; streetsField = Game.field(WorldMapStreets.class, "streets", java.util.ArrayList.class); }
            if (streetsField == null) return null;
            List<?> list = (List<?>) streetsField.get((WorldMapStreets) streets);
            return MapFast.indexOf(list, a) - MapFast.indexOf(list, b);
        } catch (Throwable t) { guard.fail(t); return null; }
    }

    // ---- mapStreetCache ----

    /** StreetRenderData.init entry: true skips it (the layout this object holds is for the same view). */
    public static boolean skipStreetInit(Object renderData, Object map, Object renderer) {
        if (!MapFast.cache || !guard.on) return false;
        try {
            UIWorldMap m = (UIWorldMap) map;
            WorldMapRenderer r = (WorldMapRenderer) renderer;
            WorldMap wm = m.getWorldMap();
            if (m.isMapEditor() || wm == null || wm.combinedStreets == null || !view(m)) return false;   // editor: init checks edits
            UIWorldMapV3 v3 = m.getAPI();
            WorldMapStreetsV1 api = v3.getStreetsAPI();
            float zoom = r.getDisplayZoomF();
            MapFast.ViewKey key = new MapFast.ViewKey(new double[] {
                r.getCenterWorldX(), r.getCenterWorldY(), zoom, r.getWorldScale(zoom), view[0], view[1], view[2], view[3],
                m.getWidth(), m.getHeight(), r.getBoolean("ShowStreetNames") ? 1 : 0, r.getBoolean("HighlightStreet") ? 1 : 0,
                v3.getBoolean("OutlineStreets") ? 1 : 0, api.getStreetDataCount(), api.getMouseOverStreetWorldX(),
                api.getMouseOverStreetWorldY(), UiPatches.fontsLoaded},
                new Object[] {api.getMouseOverStreet(), Translator.getLanguage(), wm, v3.getStyle()});
            return !MapFast.shouldInit(renderData, key, wm.combinedStreets.isDirty());
        } catch (Throwable t) { guard.fail(t); return false; }
    }

    // ---- mapVisitedFast ----

    private static VarHandle changed, x1, x2, y1, y2, visited;
    private static MethodHandle flags;
    private static boolean visitedBound;

    /** WorldMapVisited.updateTextureData entry: Boolean answer when done here, or null to run stock. */
    public static Object fillVisited(Object self, ByteBuffer buf, int width) {
        if (!MapFast.visited || !guard.on) return null;
        try {
            if (!visitedBound) {
                visitedBound = true;
                Class<?> c = WorldMapVisited.class;
                changed = Game.field(c, "changed", boolean.class);
                x1 = Game.field(c, "changeX1", int.class); x2 = Game.field(c, "changeX2", int.class);
                y1 = Game.field(c, "changeY1", int.class); y2 = Game.field(c, "changeY2", int.class);
                visited = Game.field(c, "visited", byte[].class);
                flags = MethodHandles.privateLookupIn(c, MethodHandles.lookup())
                    .findStatic(c, "getFlags", MethodType.methodType(int.class, int.class, int.class, byte[].class));
            }
            if (changed == null || x1 == null || x2 == null || y1 == null || y2 == null || visited == null) return null;
            if (!(boolean) changed.get(self)) return Boolean.FALSE;
            changed.set(self, false);
            byte[] v = (byte[]) visited.get(self);
            MapFast.fillRows(buf, width, (int) x1.get(self), (int) x2.get(self), (int) y1.get(self), (int) y2.get(self), (x, y) -> {
                try { return (int) flags.invokeExact(x, y, v); } catch (Throwable t) { throw new RuntimeException(t); }
            });
            x1.set(self, Integer.MAX_VALUE); y1.set(self, Integer.MAX_VALUE);
            x2.set(self, Integer.MIN_VALUE); y2.set(self, Integer.MIN_VALUE);
            return Boolean.TRUE;
        } catch (Throwable t) { guard.fail(t); return null; }
    }

    // ---- holders ----

    @Patch(className = "zombie.worldMap.streets.WorldMapStreet", methodName = "getLength")
    public static class Length {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object self, @Patch.Argument(0) Object map, @Patch.Local("v") Object v) {
            v = cachedLength(self, map);
            return v != null;
        }

        @Patch.OnExit
        public static void exit(@Patch.This Object self, @Patch.Argument(0) Object map, @Patch.Return(readOnly = false) float ret,
                                @Patch.Local("v") Object v) {
            if (v != null) ret = (Float) v;
            else storeLength(self, map, ret);
        }
    }

    @Patch(className = "zombie.worldMap.streets.WorldMapStreet", methodName = "getTranslatedText")
    public static class Name {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object self, @Patch.Local("v") String v) {
            v = cachedName(self);
            return v != null;
        }

        @Patch.OnExit
        public static void exit(@Patch.This Object self, @Patch.Return(readOnly = false) String ret, @Patch.Local("v") String v) {
            if (v != null) ret = v;
            else storeName(self, ret);
        }
    }

    @Patch(className = "zombie.worldMap.streets.WorldMapStreets", methodName = "render")
    public static class StreetsRender {
        @Patch.OnEnter
        public static void enter() { onStreetsRender(); }
    }

    @Patch(className = "zombie.worldMap.streets.WorldMapStreets", methodName = "lambda$render$0")
    public static class Compare {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object self, @Patch.Argument(0) Object a, @Patch.Argument(1) Object b,
                                    @Patch.Local("v") Object v) {
            v = compareStreets(self, a, b);
            return v != null;
        }

        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) int ret, @Patch.Local("v") Object v) {
            if (v != null) ret = (Integer) v;
        }
    }

    @Patch(className = "zombie.worldMap.streets.StreetRenderData", methodName = "init")
    public static class StreetInit {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object self, @Patch.Argument(0) Object map, @Patch.Argument(1) Object renderer) {
            return skipStreetInit(self, map, renderer);
        }
    }

    @Patch(className = "zombie.worldMap.WorldMapVisited", methodName = "updateTextureData")
    public static class Visited {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object self, @Patch.Argument(0) ByteBuffer buf, @Patch.Argument(1) int width,
                                    @Patch.Local("v") Object v) {
            v = fillVisited(self, buf, width);
            return v != null;
        }

        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) boolean ret, @Patch.Local("v") Object v) {
            if (v != null) ret = (Boolean) v;
        }
    }
}
