package pzbutter;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Properties;

/** Zomboid/PZButter.properties. Read once at mod load; any failure leaves every feature off. */
public final class Settings {
    static final String VERSION = "0.2";

    private static final String DEFAULTS =
        "# PZ Butter. Read at game start.\n"
      + "#\n"
      + "# enabled: master switch. When false every feature below stays off and the game runs stock.\n"
      + "enabled=false\n"
      + "#\n"
      + "# frameLog: one line per frame in Zomboid/pzbutter/frames.out:\n"
      + "#   <epoch ms> <frame no> <ms since the last frame started> <ms in the frame step> <its main-thread CPU ms>\n"
      + "# and per presented frame in present.out, per second in threads.out.\n"
      + "frameLog=false\n"
      + "#\n"
      + "# Frame delivery. Modes take off, on or auto. limiterSleep, the three input keys, reflexSleep and\n"
      + "# vsyncAdaptive are on by default: they passed in-game checks. Everything else stays off until you switch it on.\n"
      + "# limiterSleep: the frame limiter sleeps until 0.2 ms before the frame instead of spinning.\n"
      + "limiterSleep=true\n"
      + "# renderIdleWait: the render thread waits for the next frame instead of spinning.\n"
      + "renderIdleWait=false\n"
      + "# presentPacing: each frame's swap is held to a steady time after the game moment it shows (auto: with VRR).\n"
      + "# Low-lag: the hold is the 95th percentile of how late frames are ready, so only unusually early frames wait.\n"
      + "presentPacing=off\n"
      + "# The longest hold in % of a frame, and a margin added to the percentile.\n"
      + "presentPacingMaxPct=100\n"
      + "presentPacingMarginPct=0\n"
      + "# vrr: variable refresh (auto: detected from the display driver).\n"
      + "vrr=off\n"
      + "# vrrCap: with VRR, uncapped or above the panel's max caps at refresh - refresh^2/3600.\n"
      + "vrrCap=false\n"
      + "# borderlessFullscreen: the borderless window becomes a fullscreen window at the desktop mode (auto: with VRR).\n"
      + "borderlessFullscreen=off\n"
      + "# vsyncAdaptive: swap interval -1, a late frame tears instead of waiting a refresh.\n"
      + "vsyncAdaptive=true\n"
      + "# gpuMaxFrames: at most this many frames queued for the GPU (0: off). Acts only when the GPU falls behind.\n"
      + "gpuMaxFrames=2\n"
      + "# reflexSleep: each frame measures its queue wait and the next starts that much later.\n"
      + "reflexSleep=true\n"
      + "# reflexBoost: NVIDIA's prefer-maximum-performance while in a world.\n"
      + "reflexBoost=false\n"
      + "# reflexCapFps: with vsync, the cap (0: auto, refresh - refresh^2/3600).\n"
      + "reflexCapFps=0\n"
      + "# vblankLock: with vsync, frames start a measured time before vblank (X11 + NVIDIA).\n"
      + "vblankLock=false\n"
      + "#\n"
      + "# luaBudget: the merged LuaBudget mod. Its rules stay in Zomboid/LuaBudget.properties; no rules, no effect.\n"
      + "luaBudget=true\n"
      + "# luaSkipEmpty: Lua functions with an empty body (function() end) are not called.\n"
      + "luaSkipEmpty=true\n"
      + "# luaTiming: once a minute, the ten slowest Lua event handlers to the console (and lua.out with frameLog).\n"
      + "luaTiming=false\n"
      + "# uiTiming: once a minute, the UI's time per frame and the 15 slowest windows go to the console and ui.out (diagnostic).\n"
      + "uiTiming=false\n"
      + "# uiCensus: diagnostic; with uiTiming on, also counts what each UI window reads per frame and how often it read the same values twice in a row (Plan 3c).\n"
      + "uiCensus=false\n"
      + "# uiRetained: a window that read nothing new since its last frame is drawn from its recorded draw list.\n"
      + "uiRetained=false\n"
      + "# uiRetainedChildren: panels inside a redrawn window are reused when nothing they read changed (works with uiRetained or uiRetainCheck).\n"
      + "uiRetainedChildren=false\n"
      + "# uiVerify: uiRetained checks everything a window read before reusing it; uiRetained needs it.\n"
      + "uiVerify=true\n"
      + "# uiRetainedMods: windows from mods are reused too (false: only the game's own windows). With modProfile=custom.\n"
      + "uiRetainedMods=true\n"
      + "# uiRetainExclude: windows that are always drawn live (Java class name or Lua Type), comma separated.\n"
      + "uiRetainExclude=ObjectTooltip,ISToolTip,ISToolTipInv\n"
      + "# uiRetainCheck: diagnostic; never reuses a window, but compares each draw list it would have reused with the live one and logs differences.\n"
      + "uiRetainCheck=false\n"
      + "# luaRawgetFast: Lua table reads do one hash lookup instead of two (measured slower than stock; off).\n"
      + "luaRawgetFast=false\n"
      + "# luaInternConstants: string constants of loaded Lua are interned, so table lookups match by identity first.\n"
      + "luaInternConstants=true\n"
      + "# luaWorkerGate: Lua reached from PZ Butter's frame workers waits its turn and runs as the game thread. Needed by the zombie and entity updates on workers (a later version); until then it changes nothing.\n"
      + "luaWorkerGate=false\n"
      + "# luaFileWriteThrottle: changes when mod files reach the disk. A heartbeat, pulse, ping or temp-log file opened again within 2 s, or any file rewritten within 0.5 s, is kept in memory and written once the time is up. The last content always reaches the disk, and a mod that reads the file back gets it first.\n"
      + "luaFileWriteThrottle=false\n"
      + "# logDedupe: changes the log. A line printed again within 3 s is dropped and counted; one summary line per repeated line every 3 s.\n"
      + "logDedupe=false\n"
      + "#\n"
      + "# Mod compatibility.\n"
      + "# modProfile: custom uses modCompat and uiRetainedMods as written; performance = modCompat report and uiRetainedMods true; compatibility = modCompat auto and uiRetainedMods false.\n"
      + "modProfile=custom\n"
      + "# modCompat: other Java mods that patch a game method PZ Butter replaces, or replace one PZ Butter patches. off; report (list them at start); auto (also switch those PZ Butter features off).\n"
      + "modCompat=off\n"
      + "#\n"
      + "# UI.\n"
      + "# uiTickStagger: each window runs its 10-per-second Lua update in its own slot instead of all in one frame.\n"
      + "uiTickStagger=true\n"
      + "# uiLuaFast: the inventory item list skips its second drawing pass when nothing is dragged.\n"
      + "uiLuaFast=true\n"
      + "# textWidthCache: measured text widths are kept (context menus re-measure every option on every opening).\n"
      + "textWidthCache=true\n"
      + "#\n"
      + "# World map.\n"
      + "# mapStreetMemo: street lengths and names kept per view and language; one index map per label sort.\n"
      + "mapStreetMemo=true\n"
      + "# mapStreetCache: the street-label layout is reused while the map view is unchanged.\n"
      + "mapStreetCache=true\n"
      + "# mapVisitedFast: the explored-area texture is built a row at a time.\n"
      + "mapVisitedFast=true\n"
      + "#\n"
      + "# Sound.\n"
      + "# soundTickHz: ambience and zombie sound parameters refresh at this rate (0 = stock; 60 refreshes them at 60 Hz).\n"
      + "soundTickHz=0\n"
      + "# emitterIdleSkip: a silent zombie skips its sound parameters and emitter ticks.\n"
      + "emitterIdleSkip=false\n"
      + "# worldSoundCleanupFast: world sounds are cleaned up with fewer chunk scans per frame, and the same sounds are heard.\n"
      + "worldSoundCleanupFast=false\n"
      + "# hearingHoist: zombies pick the loudest sound they hear with less repeated work, and the same sound is picked.\n"
      + "hearingHoist=false\n"
      + "# worldSoundFast: fish scaring is worked out once per game minute for each sound position, so repeated sounds cost less.\n"
      + "worldSoundFast=false\n"
      + "# soundZoneCache: ambient zone sound levels are reused while you stay on one square, so the zone lookup costs less each frame.\n"
      + "soundZoneCache=false\n"
      + "# electricityLevelRange: the power check scans only the height range of loaded chunks, so the same result comes back with less work.\n"
      + "electricityLevelRange=false\n"
      + "# fliesToggleFix: moving between chunks next to an unchanged corpse pile no longer redraws that chunk, which removes a hitch.\n"
      + "fliesToggleFix=false\n"
      + "# fmodNullHandleFix: when FMOD is out of event slots, the emitter sound is dropped instead of crashing the game.\n"
      + "fmodNullHandleFix=false\n"
      + "# corpseFliesCap: flies around corpse piles are counted up to 12, which also lowers corpse sickness next to piles above 12.\n"
      + "corpseFliesCap=false\n"
      + "# audioLimiter: changes what you hear. Loud peaks are held under the ceiling below, using FMOD's own limiter on the master output.\n"
      + "audioLimiter=false\n"
      + "# audioLimiterCeilingDb: the highest level the limiter lets through, in dB (-12 to 0; -2 leaves some headroom).\n"
      + "audioLimiterCeilingDb=-2\n"
      + "# audioLimiterStereoFold: with a stereo output, the limiter works on the stereo mix. Only acts together with audioLimiter.\n"
      + "audioLimiterStereoFold=true\n"
      + "# occlusionSkipLightingOnly: the wall occlusion grid is rebuilt only when something other than light changed, so light updates cost less.\n"
      + "occlusionSkipLightingOnly=false\n"
      + "# lightInfoOncePerFrame: a clean square's light is read once per lighting update instead of on every query, so the light lookups cost less.\n"
      + "lightInfoOncePerFrame=false\n"
      + "# lightSwitchCheckFrames: a light switch reuses its power answer for this many frames (0 = stock; 15 = 0.25 s at 60 fps), so a power change shows that late.\n"
      + "lightSwitchCheckFrames=0\n"
      + "# lightingFps: the lighting thread's rate, 1 to 120 (0 = leave the game's value); it changes lighting smoothness, not main-frame time.\n"
      + "lightingFps=0\n"
      + "# gridStackInterval: the buildings-in-front scan runs every this many frames while you stay put (0 = stock; 8), so a hide may show a few frames late.\n"
      + "gridStackInterval=0\n"
      + "# roofHideDebounceFrames: a carport roof's hide or show must hold this many frames before it changes (0 = stock; 8), so it doesn't flicker.\n"
      + "roofHideDebounceFrames=0\n"
      + "# cutawayInvalidateChanged: only chunks where a wall's cutaway actually changed are redrawn, so fewer chunks are rebuilt.\n"
      + "cutawayInvalidateChanged=false\n"
      + "# cutawayVisitPrefilter: walls that cannot cut away are skipped before their squares are checked, so the cutaway pass costs less.\n"
      + "cutawayVisitPrefilter=false\n"
      + "# cutawayRadius: cutaway wall checks only reach chunks this close to you (0 = stock; 6); only far zoomed-out views change.\n"
      + "cutawayRadius=0\n"
      + "# cutawayFast: clean chunks reuse their stored cutaway occluder masks instead of testing every square again.\n"
      + "cutawayFast=false\n"
      + "# rainSplashesFast: rain splashes start by skipping idle squares with a local random generator; the same odds, with less work per frame.\n"
      + "rainSplashesFast=false\n"
      + "# weatherMaskIdleSkip: with no clouds, fog or precipitation active (and none fading out), the weather mask is not drawn.\n"
      + "weatherMaskIdleSkip=false\n"
      + "# puddleEarlyZ: puddles take their depth from the vertex, so hidden wet ground is rejected before the costly shader; the look is the same.\n"
      + "puddleEarlyZ=false\n"
      + "# fogEarlyZ: fog takes its depth from the vertex, so hidden fog is rejected before the costly shader; the look is the same.\n"
      + "fogEarlyZ=false\n"
      + "#\n"
      + "# Chunk textures (Plan 6). Every key is off or at the stock value until its in-game check.\n"
      + "# treesInChunkTexture: trees that are not fading or cut away are drawn into the chunk textures instead of every frame (the game's own tree bake, switched on).\n"
      + "treesInChunkTexture=false\n"
      + "# driveTreeCutaway: true is the game's rule (while driving every tree near the car turns see-through); false uses Build 42.20's rule while driving.\n"
      + "driveTreeCutaway=true\n"
      + "# treeBakeMaxChunksPerSec: with treesInChunkTexture, while more new chunk textures than this arrive a second their trees stay per frame (0 = off).\n"
      + "treeBakeMaxChunksPerSec=0\n"
      + "# edgeTestFast: the wall, window and door \"is the way blocked\" test is written out directly; the same answers with less work per zombie.\n"
      + "edgeTestFast=false\n"
      + "# translucentTilesInChunkTexture: fences, railings, signs and wall decorations are drawn into the chunk textures instead of every frame.\n"
      + "translucentTilesInChunkTexture=false\n"
      + "# glassTilesPerFrame: with translucentTilesInChunkTexture, glass tiles stay per frame so characters behind them stay visible.\n"
      + "glassTilesPerFrame=true\n"
      + "# translucentLightsPerFrame: with translucentTilesInChunkTexture, lit fixtures stay per frame (drawn in, they showed through floors).\n"
      + "translucentLightsPerFrame=true\n"
      + "# bloodFadeFix: blood pushed out of a chunk's 1,000 splats disappears at the next redraw instead of fading over hundreds of redraws.\n"
      + "bloodFadeFix=false\n"
      + "# bakeMipLevels: chunk textures keep only this many detail levels after a redraw (0 = all of them, the game's way; 3 keeps the full one and two smaller, enough for the widest zoom).\n"
      + "bakeMipLevels=0\n"
      + "# zoomEaseMs: a mouse-wheel zoom takes this long on a smooth curve at any frame rate (0 = the game's fixed step per frame).\n"
      + "zoomEaseMs=0\n"
      + "# zoomEase: the zoom curve as a cubic Bezier x1,y1,x2,y2 (CSS \"ease\"); only with zoomEaseMs.\n"
      + "zoomEase=0.25,0.1,0.25,1.0\n"
      + "# bakeScheduler: chunk texture redraws are spread over frames under the budgets below; a waiting texture keeps its last picture. New chunks and object changes never wait.\n"
      + "bakeScheduler=false\n"
      + "# bakeFrameBudget: with bakeScheduler, the most redraws that may wait their turn in one frame (overdue ones always go).\n"
      + "bakeFrameBudget=8\n"
      + "# bakeBudgetAdaptive: with bakeScheduler, the budget halves after a frame over 90 % of the frame cap's time and grows by one under 60 %.\n"
      + "bakeBudgetAdaptive=false\n"
      + "# occlusionGrantedOnly: with bakeScheduler, the hidden-behind-buildings check ignores textures that wait this frame.\n"
      + "occlusionGrantedOnly=false\n"
      + "# rebakeBudget: with bakeScheduler, cutaway, tree and redraw redraws a frame (0 = only bakeFrameBudget limits them).\n"
      + "rebakeBudget=4\n"
      + "# rebakeMaxFrames: with bakeScheduler, the longest a cutaway, tree or redraw redraw waits, in frames.\n"
      + "rebakeMaxFrames=3\n"
      + "# lightingRebakeBudget: with bakeScheduler, light-only redraws a frame (0 = only bakeFrameBudget limits them).\n"
      + "lightingRebakeBudget=8\n"
      + "# lightingRebakeMaxFrames: with bakeScheduler, the longest a light-only or blood redraw waits, in frames.\n"
      + "lightingRebakeMaxFrames=30\n"
      + "# lightingRebakeMs: with bakeScheduler, the shortest time between two light-only redraws of one texture (0 = no limit).\n"
      + "lightingRebakeMs=250\n"
      + "# lightingStrongDelta: with bakeScheduler, a texture where a square's light moved this much (0-255) since it was drawn goes first, like a torch beam (0 = every light change).\n"
      + "lightingStrongDelta=6\n"
      + "# lightingStrongBudget: with bakeScheduler, strong-light redraws a frame (0 = only bakeFrameBudget limits them).\n"
      + "lightingStrongBudget=8\n"
      + "# lightingStrongFrameMs: with bakeScheduler, a frame step longer than this halves the next frame's strong-light redraws.\n"
      + "lightingStrongFrameMs=9\n"
      + "# lightingGlobalDeltaPct: with bakeScheduler, a light change across most of the screen above this % (a flash, a fast dusk) is spread like a slow one (0 = off).\n"
      + "lightingGlobalDeltaPct=2\n"
      + "# bloodRebakeCoalesceMs: with bakeScheduler, a texture whose only change is new blood waits this long so several splats share one redraw (0 = at once).\n"
      + "bloodRebakeCoalesceMs=250\n"
      + "#\n"
      + "# Zombies (Plan 8). Every key is off or at the game's value until its in-game check.\n"
      + "# zeroFacingFix: a character asked to face a zero-length direction keeps its facing instead of the game stopping with \"Forward Direction cannot be zero length vector\".\n"
      + "zeroFacingFix=false\n"
      + "# numberZombiesBlended: how many of the most relevant zombies get blended animations (0 = the game's 20; 1 to 64). Fewer looks stiffer and costs less.\n"
      + "numberZombiesBlended=0\n"
      + "# zombieLodFixed3d: changes the simulation. At most this many zombies are drawn as 3D models, the rest as flat sprites that update less often, as the game does past 510 (0 = the game's 510; 50 to 510).\n"
      + "zombieLodFixed3d=0\n"
      + "# zombieModelAddBudgetUs: changes the simulation for a few frames. Microseconds a frame for zombies getting their 3D model; past it they stay sprites a frame longer (0 = no limit, as the game).\n"
      + "zombieModelAddBudgetUs=0\n"
      + "# zombieSimLodTiles: changes the simulation. Zombies and animals farther than this many tiles from every player update less often, one step more than the game's 30/60/80 (0 = off).\n"
      + "zombieSimLodTiles=0\n"
      + "# zombieSimLodSteps: with zombieSimLodTiles, how many extra steps, each at twice the previous distance (1 to 3).\n"
      + "zombieSimLodSteps=1\n"
      + "# playerLosFast: the player's sight pass checks \"seen this one before\" with a set instead of searching a list; the same answers.\n"
      + "playerLosFast=false\n"
      + "# zombieCullSortFast: the sort that picks which zombies get 3D models works each zombie's score out once; the same order.\n"
      + "zombieCullSortFast=false\n"
      + "# statsNoBox: character stat reads skip a throwaway object per read; the same values.\n"
      + "statsNoBox=false\n"
      + "# entityTiming: diagnostic; once a minute, the time per frame of the zombie update phases goes to the console (and entity.out with frameLog).\n"
      + "entityTiming=false\n"
      + "# frameThreads: width of PZ Butter's frame worker pool for the zombie update on workers. Needs luaWorkerGate and one restart after it changes (0 or 1 = no pool, no class changes). Max 32.\n"
      + "frameThreads=0\n"
      + "# devCensus: diagnostic for the zombie-update work; counts, in play, which game calls a calm zombie's update reaches (Zomboid/pzbutter/census.out once a minute). Needs the preload (one restart). Off by default.\n"
      + "devCensus=false\n"
      + "#\n"
      + "# Sprite buffers and GPU (Plan 9). Every key is off or at the game's value until its in-game check.\n"
      + "# persistentVbo: the sprite and puddle ring buffers keep persistently mapped storage (OpenGL 4.4) instead of new storage and a map per 64 KB batch.\n"
      + "persistentVbo=false\n"
      + "# persistentVboSlots: with persistentVbo, storages per buffer, used in turn (1 to 8; fewer when the rings would pass 64 MB).\n"
      + "persistentVboSlots=4\n"
      + "# persistentVboFrameSync: acts only with persistentVbo. One fence per frame decides when a storage can be written again (false: one fence per batch).\n"
      + "persistentVboFrameSync=true\n"
      + "# vboBatchKb: batch size in KB of the drawer for lines, shapes, the map, fog and rain (0 = the game's 4; 8 to 1792).\n"
      + "vboBatchKb=0\n"
      + "# vboSizeClasses: that drawer's uploads go into storage of a few fixed sizes, orphan or subdata, instead of new storage of the exact size each time (off = the game's way).\n"
      + "vboSizeClasses=off\n"
      + "# vboSizeClassMinKb: with vboSizeClasses, the smallest storage size in KB (4 to 4096, rounded down to a power of two).\n"
      + "vboSizeClassMinKb=64\n"
      + "# smartTextureRelease: a character's or item's old combined texture goes back to the pool when it is rebuilt, instead of staying in video memory until the model is removed.\n"
      + "smartTextureRelease=false\n"
      + "# bakeNoSync: texture and framebuffer names come from a pool; framebuffer checks, uniform locations and the current shader are answered without asking the driver.\n"
      + "bakeNoSync=false\n"
      + "# bakeNoSyncCheck: diagnostic for bakeNoSync; every 256th answer is checked against the driver and mismatches go to the console.\n"
      + "bakeNoSyncCheck=false\n"
      + "# bakeBudgetGpu: with bakeBudgetAdaptive, the bake budget also counts the GPU's time for the frame (GPU timestamps read three frames later, never waited for).\n"
      + "bakeBudgetGpu=false\n"
      + "# gpuStats: diagnostic; once a minute, what the Plan 9 keys did goes to the console (and gpu.out with frameLog).\n"
      + "gpuStats=false\n"
      + "#\n"
      + "# Input.\n"
      + "# inputLatch: a fresh input poll when the frame starts.\n"
      + "inputLatch=true\n"
      + "# keyboardFresh: the keyboard is read from the poll this frame swapped in.\n"
      + "keyboardFresh=true\n"
      + "# frameStartGate: wait for the render thread before reading input.\n"
      + "frameStartGate=true\n"
      + "# cursorLatch: the cursor is moved to the newest pointer position just before the frame is drawn.\n"
      + "cursorLatch=false\n"
      + "# aimHoldMs: right-button hold before aiming starts.\n"
      + "aimHoldMs=150\n"
      + "#\n"
      + "# Vehicles (Plan 10).\n"
      + "# gloveBoxFix: a vehicle whose GloveBox part has no item container no longer throws when adding a key.\n"
      + "gloveBoxFix=false\n"
      + "#\n"
      + "# vehicleAnimIdleSkip: parked car parts skip unchanged animation updates.\n"
      + "vehicleAnimIdleSkip=false\n"+ "#\n"
+ "# Chunk hand-off pacing (Plan 10a).\n"
+ "# chunkHandoffDivisor: joins per frame capped by 1 + queued/divisor (0 = off).\n"
+ "chunkHandoffDivisor=0\n"
+ "# chunkHandoffFrameBudgetUs: budget in microseconds for joins this frame (0 = off).\n"
+ "chunkHandoffFrameBudgetUs=0\n"
+ "#\n"
+ "# Hot save (Plan 10a). Stock queues hot saves at most once a second.\n"
+ "# hotsaveIntervalSec: hot-save interval in seconds (0 = stock 1 s; 30 = 30 s). A crash can lose at least that many seconds of the world, and several times that when many chunks changed (the game writes at most about 10 chunks per hot save). Saving and quitting always write everything.\n"
+ "hotsaveIntervalSec=0\n";

    public static volatile boolean enabled;
    public static volatile boolean frameLog;
    private static volatile Properties props = new Properties();

    /** Keys modCompat=auto switched off (ModCompat.init, after load): they answer the stock value whatever the file says. Cleared by load. */
    static final java.util.Set<String> forcedOff = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** What modProfile sets, whatever the file says for these keys (SPEC 3.10). */
    static final java.util.Map<String, String> PERFORMANCE = java.util.Map.of("modCompat", "report", "uiRetainedMods", "true");
    static final java.util.Map<String, String> COMPATIBILITY = java.util.Map.of("modCompat", "auto", "uiRetainedMods", "false");

    /** Switches whose stock value is true (they only add a PZ Butter deviation when false): forced off means true. */
    static final java.util.Set<String> STOCK_ON = java.util.Set.of("driveTreeCutaway", "glassTilesPerFrame", "translucentLightsPerFrame");

    private Settings() {}

    public static void load() { load(new File(System.getProperty("user.home"), "Zomboid/PZButter.properties")); }

    public static void load(File f) {
        enabled = frameLog = false;
        props = new Properties();
        forcedOff.clear();
        try {
            if (!f.exists()) try (FileWriter w = new FileWriter(f)) { w.write(DEFAULTS); }
            Properties p = new Properties();
            byte[] bytes = Files.readAllBytes(f.toPath());
            int bom = bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF ? 3 : 0;
            p.load(new java.io.ByteArrayInputStream(bytes, bom, bytes.length - bom));   // a UTF-8 mark (Notepad) is not a key
            props = p;
            upgrade(f, p);
            enabled = bool(p, "enabled", false);
            frameLog = bool(p, "frameLog", false);
        } catch (Throwable t) {
            enabled = frameLog = false;
            props = new Properties();
            System.out.println("[PZButter] settings failed, all features off: " + t);
        }
    }

    /**
     * Brings the file up to the current template: every known key with its comment and the user's value (or the
     * default), then any keys this version doesn't know, as they were. A one-time .bak keeps the original.
     * Nothing is written when the file is already current. A failure here leaves the file and the loaded values alone.
     */
    static void upgrade(File f, Properties p) {
        try {
            String text = render(p);
            String old = new String(Files.readAllBytes(f.toPath()), StandardCharsets.ISO_8859_1);
            if (text.equals(old)) return;
            File bak = new File(f.getPath() + ".bak");
            if (!bak.exists()) Files.copy(f.toPath(), bak.toPath());
            java.nio.file.Path tmp = f.toPath().resolveSibling(f.getName() + ".tmp");   // a failed write never truncates the file
            Files.write(tmp, text.getBytes(StandardCharsets.ISO_8859_1));
            Files.move(tmp, f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Throwable t) {
            System.out.println("[PZButter] settings file not upgraded: " + t);
        }
    }

    /** The template with p's values in place, then p's unknown keys. */
    static String render(Properties p) {
        StringBuilder b = new StringBuilder();
        java.util.Set<String> known = new java.util.HashSet<>();
        for (String line : DEFAULTS.split("\n")) {
            int eq = line.indexOf('=');
            if (line.startsWith("#") || eq < 0) { b.append(line).append('\n'); continue; }
            String key = line.substring(0, eq);
            known.add(key);
            String v = p.getProperty(key);
            b.append(v != null ? entry(key, v) : line).append('\n');
        }
        java.util.List<String> unknown = new java.util.ArrayList<>();
        for (String k : p.stringPropertyNames()) if (!known.contains(k)) unknown.add(k);
        if (!unknown.isEmpty()) {
            java.util.Collections.sort(unknown);
            b.append("#\n# Keys this version of PZ Butter doesn't use (kept as they were):\n");
            for (String k : unknown) b.append(entry(k, p.getProperty(k))).append('\n');
        }
        return b.toString();
    }

    /** key=value escaped the way Properties.store writes it (backslashes, line breaks, \\uXXXX past Latin-1). */
    static String entry(String key, String value) {
        try {
            Properties one = new Properties();
            one.setProperty(key, value);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            one.store(out, null);
            String s = out.toString(StandardCharsets.ISO_8859_1);   // a date comment line, then the entry
            return s.substring(s.indexOf('\n') + 1, s.length() - System.lineSeparator().length());
        } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }

    /** A feature switch: never on without the master switch; a missing key takes its default. */
    /** Features that passed their in-game check and default to on (still only with the master switch). */
    static final java.util.Set<String> ON_BY_DEFAULT = java.util.Set.of("limiterSleep", "inputLatch", "keyboardFresh", "frameStartGate",
        "reflexSleep", "vsyncAdaptive", "luaBudget",
        "luaInternConstants", "luaSkipEmpty", "uiVerify", "uiRetainedMods", "uiTickStagger", "textWidthCache", "uiLuaFast", "mapStreetMemo", "mapStreetCache", "mapVisitedFast",   // these two only act with vsync on
        "audioLimiterStereoFold",
        "persistentVboFrameSync",   // acts only with persistentVbo (Plan 9)
        "driveTreeCutaway", "glassTilesPerFrame", "translucentLightsPerFrame");   // stock-equivalent values (Plan 6), not passed features

    public static boolean flag(String key) {
        if (!enabled) return false;
        if (forcedOff.contains(key)) return STOCK_ON.contains(key);
        return bool(raw(key), ON_BY_DEFAULT.contains(key));
    }

    /**
     * The value for a key: the one modProfile sets (performance, compatibility) for the two keys it owns, else the file's;
     * null when missing.
     */
    static String raw(String key) {
        Properties p = props;
        String profile = p.getProperty("modProfile");
        if (profile != null) {
            java.util.Map<String, String> m = switch (profile.trim().toLowerCase(Locale.ROOT)) {
                case "performance" -> PERFORMANCE;
                case "compatibility" -> COMPATIBILITY;
                default -> null;
            };
            if (m != null && m.containsKey(key)) return m.get(key);
        }
        return p.getProperty(key);
    }

    /** A number key, clamped to [min, max]; the default when missing or unreadable. */
    public static int num(String key, int dflt, int min, int max) {
        String v = forcedOff.contains(key) ? null : raw(key);
        int n = dflt;
        if (v != null) try { n = Integer.parseInt(v.trim()); } catch (NumberFormatException e) { n = dflt; }
        return Math.max(min, Math.min(max, n));
    }

    /** A text key; the default when missing. */
    public static String text(String key, String dflt) {
        String v = forcedOff.contains(key) ? null : raw(key);
        return v == null ? dflt : v.trim();
    }

    /** "off", "on" or "auto"; "off" when the master switch is off or the value is unreadable. */
    public static String mode(String key) {
        if (!enabled || forcedOff.contains(key)) return "off";
        String v = raw(key);
        if (v == null) return "off";
        switch (v.trim().toLowerCase(Locale.ROOT)) {
            case "on": case "true": case "yes": case "1": return "on";
            case "auto": return "auto";
            default: return "off";
        }
    }

    static boolean bool(Properties p, String key, boolean dflt) { return bool(p.getProperty(key), dflt); }

    static boolean bool(String v, boolean dflt) {
        if (v == null) return dflt;
        switch (v.trim().toLowerCase(Locale.ROOT)) {
            case "true": case "yes": case "on": case "1": return true;
            case "false": case "no": case "off": case "0": return false;
            default: return dflt;
        }
    }
}
