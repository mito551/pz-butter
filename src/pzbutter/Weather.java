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

    private static final ThreadLocal<SplittableRandom> RNG = ThreadLocal.withInitial(SplittableRandom::new);

    // protected static in the game; read through a handle (null when missing: stock runs)
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
        boolean every = n <= 1;
        double logQ = every ? 0 : Math.log1p(-1.0 / n);   // ln(1 - p)
        int skip = every ? 0 : skipCount(logQ, u.getAsDouble());
        for (int k = 0; k < frame.length; k++) {
            if (frame[k] < 0) {
                if (every || skip == 0) {
                    frame[k] = 0f;
                    flags[k] = (byte) (flags[k] | 2);
                    if (!every) skip = skipCount(logQ, u.getAsDouble());
                } else {
                    skip--;
                }
            } else {
                frame[k] += step;
                if (frame[k] >= 1f) frame[k] = -1f;
            }
        }
    }

    /** Idle squares to pass before the next trigger: floor(ln(U) / ln(1 - p)), with 1 - U in (0, 1]. */
    static int skipCount(double logQ, double u) {
        double k = Math.floor(Math.log1p(-u) / logQ);
        return k >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) k;
    }

    /** Stock's not-raining fill. */
    static void stopSplashes(float[] frame) {
        Arrays.fill(frame, -1f);
    }

    /** True when nothing the idle pass would draw is on screen: no clouds, no particle fog (quality 2), no precipitation. */
    static boolean maskIdle(boolean clouds, boolean fogQuality2AndFog, boolean precipOptionAndPrecip) {
        return !clouds && !fogQuality2AndFog && !precipOptionAndPrecip;
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
        updateSplashes(level.rainSplashFrame, level.rainFlags, n, step, RNG.get()::nextDouble);
    }

    /** WeatherFxMask.renderFxMask: true when the idle pass has nothing to draw and stock can be skipped. */
    static boolean maskSkip() {
        IsoWeatherFX fx = fxInstance == null ? null : (IsoWeatherFX) fxInstance.get();
        if (fx == null) return false;
        boolean clouds = fx.hasCloudsToRender();
        boolean fog = PerformanceSettings.fogQuality == 2 && fx.hasFogToRender();
        boolean precip = Core.getInstance().getOptionRenderPrecipitation() == 1 && fx.hasPrecipitationToRender();
        return maskIdle(clouds, fog, precip);
    }
}
