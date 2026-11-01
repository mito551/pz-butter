package pzbutter;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.function.Function;

/** Plan 11b Task 1: model shader lookup on the calling thread (shaderCache). */
public final class ShaderReuse {
    private ShaderReuse() {}

    public static final Guard guard = new Guard("shaderCache");

    /** Reuse count and count sent to the render thread this session. */
    public static int reused;
    public static int sent;

    private static volatile boolean bound;
    private static MethodHandle getShaderH;

    /** Package-private seam: stubbed by tests. Default invokes the bound private handle. */
    static final Function<Object[], Object> REAL_LOOKUP = args -> {
        if (!bind()) throw new IllegalStateException("getShader handle not bound");
        try {
            return getShaderH.invoke(zombie.core.skinnedmodel.shader.ShaderManager.instance, args[0], args[1], args[2]);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    };
    public static Function<Object[], Object> lookup = REAL_LOOKUP;

    static boolean bind() {
        if (!bound) {
            synchronized (ShaderReuse.class) {
                if (!bound) {
                    getShaderH = Game.method(zombie.core.skinnedmodel.shader.ShaderManager.class, "getShader",
                        MethodType.methodType(zombie.core.skinnedmodel.shader.Shader.class, String.class, boolean.class, boolean.class));
                    if (getShaderH == null) guard.on = false;
                    bound = true;   // after the handle: a concurrent first caller never sees bound with no handle
                }
            }
        }
        return getShaderH != null;
    }

    static void init() {
        guard.on = Settings.flag("shaderCache");
        if (guard.on && !bind()) guard.on = false;
    }

    /** OnEnter of Model.CreateShader(String). True skips stock. */
    public static boolean onCreateShader(Object model, String name) {
        if (!guard.on) return false;
        if (name == null || model == null) return false;
        try {
            if (zombie.core.skinnedmodel.ModelManager.noOpenGL) return false;
            boolean isStatic;
            if (model instanceof zombie.core.skinnedmodel.model.Model m) {
                isStatic = m.isStatic;
            } else {
                java.lang.reflect.Field f = model.getClass().getField("isStatic");
                Object v = f.get(model);
                isStatic = (v instanceof Boolean b) ? b : Boolean.parseBoolean(String.valueOf(v));
            }
            Object r;
            try { r = lookup.apply(new Object[] { name, isStatic, false }); }
            catch (IndexOutOfBoundsException | NullPointerException race) { r = null; }   // render thread appending to the list: a miss, not a failure
            catch (RuntimeException e) {
                Throwable c = e.getCause();
                if (c instanceof IndexOutOfBoundsException || c instanceof NullPointerException) r = null; else throw e;
            }
            if (r == null) { sent++; return false; }
            if (model instanceof zombie.core.skinnedmodel.model.Model m) {
                // written on the loader thread (stock: render thread); the render thread sees it through the synchronized
                // invokeOnRenderQueue hand-off and isReady() before any draw
                m.effect = (zombie.core.skinnedmodel.shader.Shader) r;
            } else {
                java.lang.reflect.Field f = model.getClass().getField("effect");
                f.set(model, r);
            }
            reused++;
            return true;
        } catch (Throwable t) {
            guard.fail(t);
            return false;
        }
    }

    private static volatile boolean printed;

    /** GameLoadingState exit: the load's count, once per session, only when the key is on. */
    public static void printSummaryOnce() {
        if (!guard.on || printed) return;
        printed = true;
        System.out.println("[PZButter] shaderCache: " + reused + " reused, " + sent + " built by stock; render queue at load end: " + renderBacklog());
    }

    private static java.lang.invoke.VarHandle queueH;
    private static boolean queueBound;

    /** RenderThread.invokeOnRenderQueue size: stock's per-model wait also flushed it (flushInvokeQueue 119-180), so check what is left at load end. */
    static String renderBacklog() {
        try {
            if (!queueBound) { queueBound = true; queueH = Game.staticField(zombie.core.opengl.RenderThread.class, "invokeOnRenderQueue", java.util.ArrayList.class); }
            if (queueH == null) return "?";
            java.util.ArrayList<?> q = (java.util.ArrayList<?>) queueH.get();
            synchronized (q) { return String.valueOf(q.size()); }
        } catch (Throwable t) { return "?"; }
    }

    static void resetForTest() {
        guard.on = false;
        reused = 0;
        sent = 0;
        printed = false;
        bound = false;
        getShaderH = null;
        lookup = REAL_LOOKUP;
    }
}