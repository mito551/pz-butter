package pzbutter;

import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.function.DoubleSupplier;
import zombie.core.Core;
import zombie.core.PerformanceSettings;
import zombie.core.math.PZMath;
import zombie.core.random.Rand;
import zombie.iso.IsoCamera;
import zombie.iso.IsoChunkLevel;
import zombie.iso.IsoWorld;
import zombie.iso.objects.RainManager;
import zombie.iso.weather.fx.IsoWeatherFX;

/**
 * Plan 5a weather checks (Task 5): rainSplashesFast and weatherMaskIdleSkip. The holders in WorldPatches call the
 * package-private bodies here; the pure cores (updateSplashes, maskIdle) are tested in WorldTest.
 */
final class Weather {
    private Weather() {}

    private static final byte SPLASH_BIT = 2;
    private static final ThreadLocal<SplittableRandom> RNG = ThreadLocal.withInitial(SplittableRandom::new);

    // IsoWeatherFX.instance is protected (null handle when missing: the mask skip stays off, stock runs)
    private static VarHandle fxInstance;

    static void init() {
        WorldPatches.splashGuard.on = Settings.flag("rainSplashesFast");
        WorldPatches.maskGuard.on = Settings.flag("weatherMaskIdleSkip");
        if (WorldPatches.splashGuard.on) Main.classPresent("zombie.iso.IsoChunkLevel");
        if (WorldPatches.maskGuard.on) {
            Main.classPresent("zombie.iso.weather.fx.WeatherFxMask");
            fxInstance = Game.staticField(IsoWeatherFX.class, "instance", IsoWeatherFX.class);
        }
    }

    /**
     * Stock's per-square pass. Idle squares trigger by geometric skipping: each idle square triggers with p = 1/n
     * (n <= 1 triggers every idle square). Active squares advance by step and reset to -1 at >= 1.
     */
    static void updateSplashes(float[] frame, byte[] flags, int n, float step, DoubleSupplier u) {
        boolean all = n <= 1;
        double invLogQ = all ? 0.0 : 1.0 / Math.log1p(-1.0 / n);
        int skip = all ? 0 : skipCount(invLogQ, u.getAsDouble());
        for (int k = frame.length - 1; k >= 0; k--) {
            float v = frame[k];
            if (v < 0f) {
                if (skip == 0) {
                    frame[k] = 0f;
                    flags[k] |= SPLASH_BIT;
                    if (!all) skip = skipCount(invLogQ, u.getAsDouble());
                } else {
                    skip--;
                }
            } else {
                float t = v + step;
                frame[k] = t >= 1f ? -1f : t;
            }
        }
    }

    /** The hot-path overload (Weather.splashes passes a thread-local generator). The DoubleSupplier overload above is the same loop and draws in the same order. */
    static void updateSplashes(float[] frame, byte[] flags, int n, float step, SplittableRandom r) {
        boolean all = n <= 1;
        double invLogQ = all ? 0.0 : 1.0 / Math.log1p(-1.0 / n);
        int skip = all ? 0 : skipCount(invLogQ, r.nextDouble());
        for (int k = frame.length - 1; k >= 0; k--) {
            float v = frame[k];
            if (v < 0f) {
                if (skip == 0) {
                    frame[k] = 0f;
                    flags[k] |= SPLASH_BIT;
                    if (!all) skip = skipCount(invLogQ, r.nextDouble());
                } else {
                    skip--;
                }
            } else {
                float t = v + step;
                frame[k] = t >= 1f ? -1f : t;
            }
        }
    }

    /**
     * Idle squares to pass before the next trigger, invLogQ = 1 / ln(1 - p): ln(1 - u) <= 0 and invLogQ < 0, so the
     * product is >= 0, the cast floors it, and u = 1 saturates to Integer.MAX_VALUE.
     */
    static int skipCount(double invLogQ, double u) {
        return (int) (Math.log(1.0 - u) * invLogQ);
    }

    /** Stock's not-raining fill. */
    static void stopSplashes(float[] frame) {
        Arrays.fill(frame, -1f);
    }

    /**
     * True when stock's layered pass would draw nothing: no clouds, no particle fog (quality 2), and no precipitation
     * (IsoWeatherFX.render draws it while the option is 2 or lower).
     */
    static boolean maskIdle(boolean clouds, boolean fogQuality2AndFog, int precipOption, boolean precipitating) {
        return !clouds && !fogQuality2AndFog && !(precipOption <= 2 && precipitating);
    }

    /** IsoChunkLevel.updateRainSplashes body: stock's memo, raining test, pause and not-raining steps; the idle trigger is ours. */
    static void splashes(IsoChunkLevel level) {
        if (level.rainSplashFrameNum == IsoCamera.frameState.frameCount) return;
        level.rainSplashFrameNum = IsoCamera.frameState.frameCount;
        int cellRain = IsoWorld.instance.currentCell.getRainIntensity();
        boolean raining = cellRain > 0 || (RainManager.isRaining().booleanValue() && RainManager.rainIntensity > 0);
        if (!raining) {
            if (level.raining) {
                level.raining = false;
                stopSplashes(level.rainSplashFrame);
            }
            return;
        }
        level.raining = true;
        if (IsoCamera.frameState.paused) return;
        int i = cellRain;
        if (i == 0) i = Math.min(PZMath.fastfloor(RainManager.rainIntensity / 0.2f) + 1, 5);
        int n = Rand.AdjustForFramerate((int) (5.0f / i) * 100);   // stock's odds: NextBool(n) is p = 1/n
        float step = 0.08f * (30.0f / (float) PerformanceSettings.getLockFPS());
        updateSplashes(level.rainSplashFrame, level.rainFlags, n, step, RNG.get());
    }

    /**
     * WeatherFxMask.renderFxMask body: true when stock's layered pass has nothing to draw, so stock is skipped. The
     * skipped tile scan only feeds that pass; PlayerFxMask.hasMaskToDraw (read by IsoWeatherFX.update) is set in
     * initMask, which FBORenderCell drives, so it stays as stock.
     */
    static boolean maskSkip() {
        if (fxInstance == null) return false;
        IsoWeatherFX fx = (IsoWeatherFX) fxInstance.get();
        if (fx == null) return false;
        boolean clouds = fx.hasCloudsToRender();
        boolean fog = PerformanceSettings.fogQuality == 2 && fx.hasFogToRender();
        return maskIdle(clouds, fog, Core.getInstance().getOptionRenderPrecipitation(), fx.hasPrecipitationToRender());
    }
}
