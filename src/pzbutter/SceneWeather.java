package pzbutter;

/**
 * Dev: scene weather (S8), applied once at the settle start. Off by default.
 * "off"/-1 call nothing; a build that is already stormy refuses the trigger - the scene still runs.
 */
public final class SceneWeather {
    private SceneWeather() {}

    /** Seam so the tests answer without a game world; the real one calls the game. */
    public interface Weather {
        void storm();
        void setHour(int hour);
    }

    public static volatile Weather weather = new GameWeather();

    /** Acts and returns the log text: empty when nothing is asked, else what was set (or "ignored"). */
    public static String apply(String kind, int hour, Weather w) {
        String k = kind == null ? "off" : kind.trim();
        boolean wOff = k.isEmpty() || "off".equalsIgnoreCase(k);
        if (wOff && hour == -1) return "";
        String out;
        if ("storm".equalsIgnoreCase(k)) {
            w.storm();
            out = "storm";
        } else {
            out = wOff ? "off" : k + " (only storm is built)";
        }
        if (hour >= 0 && hour <= 23) {
            w.setHour(hour);
            out += ", hour " + hour + " set";
        } else if (hour != -1) {
            out += ", hour " + hour + " ignored";
        }
        return out;
    }

    static final class GameWeather implements Weather {
        public void storm() {
            zombie.iso.weather.ClimateManager cm = zombie.iso.weather.ClimateManager.getInstance();
            if (cm == null) return;
            // the game's own single-player debug path (ISAdmPanelWeather "TriggerStorm"); false when a
            // weather period already runs, which the console line then reports
            if (!cm.triggerCustomWeatherStage(zombie.iso.weather.WeatherPeriod.STAGE_STORM, 4.0f))
                System.out.println("[PZButter] devSceneWeather: storm not started (a weather period is already running)");
        }

        public void setHour(int hour) {
            zombie.GameTime gt = zombie.GameTime.getInstance();
            if (gt != null) gt.setTimeOfDay((float) hour);
        }
    }
}
