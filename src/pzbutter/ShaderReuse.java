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
    public static Function<Object[], Object> lookup = args -> {
        if (!bind()) throw new IllegalStateException("getShader handle not bound");
        try {
            return getShaderH.invoke(zombie.core.skinnedmodel.shader.ShaderManager.instance, args[0], args[1], args[2]);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    };

    static boolean bind() {
        if (!bound) {
            synchronized (ShaderReuse.class) {
                if (!bound) {
                    bound = true;
                    getShaderH = Game.method(zombie.core.skinnedmodel.shader.ShaderManager.class, "getShader",
                        MethodType.methodType(zombie.core.skinnedmodel.shader.Shader.class, String.class, boolean.class, boolean.class));
                    if (getShaderH == null) guard.on = false;
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
            Object r = lookup.apply(new Object[] { name, isStatic, false });
            if (r == null) { sent++; return false; }
            if (model instanceof zombie.core.skinnedmodel.model.Model m) {
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
        System.out.println("[PZButter] shaderCache: " + reused + " reused, " + sent + " sent to the render thread");
    }

    static void resetForTest() {
        guard.on = false;
        reused = 0;
        sent = 0;
        printed = false;
        bound = false;
        getShaderH = null;
    }
}