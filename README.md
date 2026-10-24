# PZ Butter

PZ Butter is a Project Zomboid Build 42 mod that aims for smoother frames. It is Java code, loaded by [ZombieBuddy](https://github.com/zed-0xff/ZombieBuddy) and patching the game at runtime. It ships no game code: no modified or decompiled game classes, only patches.

**Status: early.** Version 0.1 contains the foundation only:
- a settings file;
- a frame clock that times every frame;
- an optional per-frame log.

The optimizations come next, one at a time. Each one is off by default until it has passed a check in a real game run.

## Requirements

- Project Zomboid Build 42.21 or later.
- ZombieBuddy, installed and enabled as its own instructions describe.

## Install from source

Building needs JDK 25 and a local Project Zomboid install.

```sh
./build.sh --install
```

This builds the mod, runs the self-tests, and copies the mod to `~/Zomboid/mods/PZButter`. It refuses to overwrite a `PZButter` folder that it did not install itself. Then enable **ZombieBuddy** and **PZ Butter** in the game's mod list.

`build.sh` finds the JDK and the game through two environment variables, with these defaults:

| Variable | Default |
|---|---|
| `JDK` | `/usr/lib/jvm/java-25-openjdk` |
| `PZ_GAME` | `<Steam library>/steamapps/common/ProjectZomboid/projectzomboid` |

## Settings

The mod creates `~/Zomboid/PZButter.properties` on first start and reads it once at game start. The master switch `enabled` is off by default, so the mod does nothing until you turn it on; each key is described in the file itself. At every start the file is brought up to date: new keys appear with their comments, your values are kept, and keys the mod no longer uses are kept at the end. The first such update saves the old file as `PZButter.properties.bak`.

| Key | What it does |
|---|---|
| `enabled` | Master switch. While it is `false`, the mod does nothing and the game runs stock. |
| `frameLog` | Writes one line per frame to `~/Zomboid/pzbutter/frames.out`. |
| `uiTiming` | Diagnostic: once a minute, the UI's time per frame and the 15 slowest windows to the console (and `~/Zomboid/pzbutter/ui.out` with `frameLog`). |
| `uiCensus` | Diagnostic, needs `uiTiming`: per window, what each render reads and calls, and how often it read the same values as its previous render (stable fraction and stable ms/s), and the same for the panels inside each window (reusable ms/s). Printed with the `uiTiming` report. |
| `uiRetained` | A window that read nothing new since its last frame is drawn from its recorded draw list instead of running its Lua again. Off by default. Needs `uiVerify`. |
| `uiRetainedChildren` | Every panel inside a window gets its own record, so a window that has to run its Lua again still reuses the panels in it that did not change, and a clean window calls its panels instead of redrawing them. Off by default. Works with `uiRetained` or `uiRetainCheck`. A window with a Java-drawn child (text box, map, button) still runs its own Lua each time; that child's own panels are reused. |
| `uiVerify` | Before `uiRetained` reuses a window it checks everything the window read: Lua fields, Java answers, item icons, its place and size, and its children. On by default; `uiRetained` stays off without it. |
| `uiRetainedMods` | With `false`, only the game's own windows are reused; windows from mods always run. Used with `modProfile=custom`; the other profiles set it. |
| `uiRetainExclude` | Windows that always run live, by Java class name or Lua `Type`, comma separated. Default `ObjectTooltip,ISToolTip,ISToolTipInv`. |
| `uiRetainCheck` | Diagnostic: never reuses a window, but compares each draw list it would have reused with the live one and logs the first differences per window. |
| `luaWorkerGate` | Lua that PZ Butter's frame workers reach waits its turn and runs as the game thread, so the game's single Lua stack is never used by two threads. Needed by the zombie and entity updates on workers (a later version); until then it changes nothing. Off by default. |
| `luaFileWriteThrottle` | Changes when mod files reach the disk. A heartbeat, pulse, ping or temp-log file a mod opens again within 2 s, or any file it rewrites within 0.5 s, is kept in memory and written once the time is up (one disk write instead of up to 120 a second). The last content always reaches the disk, and a mod that reads the file back gets the latest content first. Off by default. |
| `logDedupe` | Changes the log. A line printed again within 3 s is dropped; every 3 s one summary line says how often it repeated. Lines with values filled in by the game are never dropped. Off by default. |
| `modProfile` | `custom` (default) uses `modCompat` and `uiRetainedMods` as written. `performance` sets `modCompat=report` and `uiRetainedMods=true`; `compatibility` sets `modCompat=auto` and `uiRetainedMods=false`, whatever those two lines say. |
| `modCompat` | Other Java mods that patch a game method PZ Butter replaces, or that replace a method PZ Butter patches. `off` (default); `report` lists them in the console at start; `auto` also switches those PZ Butter features off. Only exact class names are compared (a patch written with a class pattern is not seen), and a pair is listed even when the PZ Butter feature behind it is already off. |
| `soundTickHz` | `0` is stock. `60` refreshes ambience and zombie sound parameters at 60 Hz. |
| `emitterIdleSkip` | A silent zombie skips its sound parameters and emitter ticks. |
| `worldSoundCleanupFast` | World sounds are cleaned up with fewer chunk scans per frame. The same sounds are heard. |
| `hearingHoist` | Zombies pick the loudest sound they hear with less repeated work. The same sound is picked. |
| `worldSoundFast` | Fish scaring is worked out once per game minute for each sound position, so repeated sounds cost less. |
| `soundZoneCache` | Ambient zone sound levels are reused while you stay on one square, so the zone lookup costs less each frame. |
| `electricityLevelRange` | The power check scans only the height range of loaded chunks, so the same result comes back with less work. |
| `fliesToggleFix` | Moving between chunks next to an unchanged corpse pile no longer redraws that chunk, which removes a hitch. |
| `fmodNullHandleFix` | When FMOD is out of event slots, the emitter sound is dropped instead of crashing the game. |
| `corpseFliesCap` | Flies around corpse piles are counted up to 12, which also lowers corpse sickness next to piles above 12. |
| `audioLimiter` | Changes what you hear: loud peaks are held under the ceiling, using FMOD's own limiter on the master output. |
| `audioLimiterCeilingDb` | The highest level the limiter lets through, in dB (-12 to 0; default -2). |
| `audioLimiterStereoFold` | With a stereo output, the limiter works on the stereo mix. Only acts together with `audioLimiter`. |
| `occlusionSkipLightingOnly` | The wall occlusion grid is rebuilt only when something other than light changed. Light updates cost less. |
| `lightInfoOncePerFrame` | A clean square's light is read once per lighting update, so the light lookups cost less. |
| `lightSwitchCheckFrames` | `0` is stock. `15` lets a light switch reuse its power answer for 15 frames (0.25 s at 60 fps). |
| `lightingFps` | `0` leaves the game's lighting rate. `1` to `120` sets the lighting thread's rate; it changes lighting smoothness, not main-frame time. |
| `gridStackInterval` | `0` is stock. `8` runs the buildings-in-front scan every 8 frames while you stay put. |
| `roofHideDebounceFrames` | `0` is stock. `8` holds a carport roof's hide or show for 8 frames, so it doesn't flicker. |
| `cutawayInvalidateChanged` | Only chunks where a wall's cutaway actually changed are redrawn. |
| `cutawayVisitPrefilter` | Walls that cannot cut away are skipped before the cutaway walk, so it costs less. |
| `cutawayRadius` | `0` is stock. `6` limits cutaway wall checks to chunks near you; only far zoomed-out views change. |
| `cutawayFast` | Clean chunks reuse their stored cutaway occluder masks instead of testing every square again. |
| `rainSplashesFast` | Rain splashes start with fewer random-number calls. The same odds. |
| `weatherMaskIdleSkip` | With no clouds, fog or precipitation active (and none fading out), the weather mask is not drawn. |
| `puddleEarlyZ` | Hidden puddles are rejected before the costly shader. The look is the same. |
| `fogEarlyZ` | Hidden fog is rejected before the costly shader. The look is the same. |
| `treesInChunkTexture` | Trees that are not fading or cut away are drawn into the chunk textures instead of every frame (the game has this built in, switched off). Off by default; a large tree near a chunk edge may be clipped. |
| `driveTreeCutaway` | `true` is the game's own rule: while driving, every tree near the car turns see-through. `false` uses Build 42.20's rule: only trees between you and the camera. |
| `treeBakeMaxChunksPerSec` | `0` is off. With `treesInChunkTexture`, while more new chunk textures than this arrive a second (driving), their trees stay per frame; they are drawn in once arrivals slow down. |
| `edgeTestFast` | The wall, window and door "is the way blocked" test is written out directly. The same answers with less work per zombie. |
| `translucentTilesInChunkTexture` | Fences, railings, signs and wall decorations are drawn into the chunk textures instead of every frame. Windows, curtains, glass and lit fixtures stay per frame. |
| `glassTilesPerFrame` | With `translucentTilesInChunkTexture`, glass tiles stay per frame, so characters behind them stay visible. On by default. |
| `translucentLightsPerFrame` | With `translucentTilesInChunkTexture`, lit fixtures stay per frame. On by default. |
| `bloodFadeFix` | Blood pushed out of a chunk's 1,000 splats disappears at the chunk's next redraw instead of fading over hundreds of redraws. |
| `bakeMipLevels` | `0` is the game's way (every detail level). `3` keeps three levels (the full one and two smaller) after each chunk redraw, enough for the widest zoom. |
| `zoomEaseMs` | `0` is the game's fixed zoom step per frame. `300` makes a mouse-wheel zoom take 300 ms on a smooth curve at any frame rate. |
| `zoomEase` | The zoom curve as a cubic Bézier `x1,y1,x2,y2`. Default `0.25,0.1,0.25,1.0` (CSS "ease"). Only with `zoomEaseMs`. |
| `bakeScheduler` | Chunk texture redraws (cutaways, light, blood, redraws) are spread over frames under the budgets below; a waiting texture keeps its last picture. New chunks and object changes never wait. Off by default. |
| `bakeFrameBudget` | With `bakeScheduler`: the most redraws that may wait their turn in one frame. Overdue ones always go. Default `8`. |
| `bakeBudgetAdaptive` | With `bakeScheduler`: the budget halves after a frame over 90 % of the frame cap's time and grows by one under 60 %. |
| `occlusionGrantedOnly` | With `bakeScheduler`: the hidden-behind-buildings check ignores textures that wait this frame. |
| `rebakeBudget` | With `bakeScheduler`: cutaway, tree and redraw redraws a frame. `0`: only `bakeFrameBudget` limits them. Default `4`. |
| `rebakeMaxFrames` | With `bakeScheduler`: the longest a cutaway, tree or redraw redraw waits, in frames. Default `3`. |
| `lightingRebakeBudget` | With `bakeScheduler`: light-only redraws a frame. `0`: only `bakeFrameBudget` limits them. Default `8`. |
| `lightingRebakeMaxFrames` | With `bakeScheduler`: the longest a light-only or blood redraw waits, in frames. Default `30`. |
| `lightingRebakeMs` | With `bakeScheduler`: the shortest time between two light-only redraws of one texture. `0`: no limit. Default `250`. |
| `lightingStrongDelta` | With `bakeScheduler`: a texture where a square's light moved this much (0-255) since it was drawn goes first, like a torch beam. Default `6`. |
| `lightingStrongBudget` | With `bakeScheduler`: strong-light redraws a frame. `0`: only `bakeFrameBudget` limits them. Default `8`. |
| `lightingStrongFrameMs` | With `bakeScheduler`: a frame step longer than this halves the next frame's strong-light redraws. Default `9`. |
| `lightingGlobalDeltaPct` | With `bakeScheduler`: a light change across most of the screen above this % (a flash, a fast dusk) is spread like a slow one. `0`: off. Default `2`. |
| `bloodRebakeCoalesceMs` | With `bakeScheduler`: a texture whose only change is new blood waits this long, so several splats share one redraw. `0`: at once. Default `250`. |
| `zeroFacingFix` | A character asked to face a zero-length direction keeps its facing, instead of the game stopping with "Forward Direction cannot be zero length vector". Only the calls the game would have stopped on change. Off by default. |
| `numberZombiesBlended` | How many of the most relevant zombies get blended animations. `0` keeps the game's 20; `1` to `64`. Fewer looks stiffer and costs less. |
| `zombieLodFixed3d` | Changes the simulation. At most this many zombies are drawn as 3D models; the rest are flat sprites and, like the game's own zombies past 510, update less often. `0` keeps the game's 510; `50` to `510`, never below `numberZombiesBlended`. |
| `zombieModelAddBudgetUs` | Changes the simulation for a few frames. Microseconds a frame for zombies getting their 3D model; past it the next ones stay sprites (and update less often) one frame longer. At least one gets its model each frame. `0`: no limit, as the game. |
| `zombieSimLodTiles` | Changes the simulation. Zombies farther than this many tiles from every player update every other frame (and every 4th, 8th with more steps), one step nearer than the game's own 30, 60 and 80 tiles. `0`: off. |
| `zombieSimLodSteps` | With `zombieSimLodTiles`: extra steps, each at twice the previous distance (`1` to `3`). Default `1`. |
| `playerLosFast` | The player's sight pass checks "have I seen this one before" with a set instead of searching a list of every zombie seen recently (millions of checks a frame in a big horde). The same answers. Off by default. |
| `zombieCullSortFast` | The sort that picks which zombies get 3D models works each zombie's score out once instead of twice per comparison. The same order. Off by default. |
| `statsNoBox` | Character stat reads skip a throwaway object per read. The same values. Off by default. |
| `popmanFlagsFlyweight` | Zombies the population hands over share their state-flag objects instead of one new object each. The same flags; a Java mod that changes such an object would change it for every zombie with the same flags. Off by default. |
| `entityTiming` | Diagnostic. Once a minute the console gets the time per frame of the zombie update phases (sorting into update tiers, update, movement and animation, 3D model picking, the player's sight pass) and the zombie count; with `frameLog`, one line per frame in `Zomboid/pzbutter/entity.out`. Off by default. |

Each line of `frames.out` has five fields:

```
<epoch ms> <frame no> <ms since the previous frame started> <ms in the frame step> <main-thread CPU ms in the frame step>
```

Past 50 MB the file is renamed to `frames.out.1` and a new one starts.

Values can be `true`/`false`, `yes`/`no`, `on`/`off` or `1`/`0`. If the file can't be read, every feature stays off.

## Development

| Command | What it does |
|---|---|
| `./build.sh` | Compiles, runs the self-tests (`test/pzbutter/SelfTest.java`) and builds `build/PZButter.jar`. |
| `./bench.sh` | Microbenchmark of the frame clock's per-frame cost. |

**CI.** Every push builds the mod and runs the self-tests on GitHub Actions; it can also be started by hand (Actions → Build → Run workflow). **Releases.** Bump `modversion` in `mod/PZButter/42/mod.info`, then push a commit to `main` whose message contains `[build]`. GitHub Actions downloads the free Project Zomboid dedicated server (cached) to compile against, builds the mod, runs the self-tests, and publishes a release `v<modversion>` with `PZButter-<modversion>.zip`. Only the newest commit of a push is checked. If that version already has a release, the run fails.

## Mods that inspire PZ Butter

PZ Butter is inspired by the mods below and aims to cover what they do, in one mod. It is a clean-room project: it contains none of their code. It is written from behaviour descriptions of each mod and from the game's own bytecode.

| Mod | Author | Workshop |
|---|---|---|
| PZ_Optimization (pzopt) | xD3I | [3805285544](https://steamcommunity.com/sharedfiles/filedetails/?id=3805285544) |
| [B42] PZ Optimization Preloader | Antikristianos | [3818209799](https://steamcommunity.com/sharedfiles/filedetails/?id=3818209799) |
| Undying Optimizer | Mister K | [3777958977](https://steamcommunity.com/sharedfiles/filedetails/?id=3777958977) |
| Viewpoint - Smooth Frames | EhChad | [3816784011](https://steamcommunity.com/sharedfiles/filedetails/?id=3816784011) |
| Viewpoint Turbo | Fatalitiko | [3812559973](https://steamcommunity.com/sharedfiles/filedetails/?id=3812559973) |
| Viewpoint Fixes | zirmango | [3817842243](https://steamcommunity.com/sharedfiles/filedetails/?id=3817842243) |
| Viewpoint PZO Shader Compatibility | Hesin | [3815225881](https://steamcommunity.com/sharedfiles/filedetails/?id=3815225881) |
| VRAM Steady | VRAM Steady authors | [3819844505](https://steamcommunity.com/sharedfiles/filedetails/?id=3819844505) |
| Character Texture VRAM Leak Fix [ZombieBuddy] | syeon | [3821525282](https://steamcommunity.com/sharedfiles/filedetails/?id=3821525282) |
| Project Zomboid Optimiser (PZO) | prop11 | [3787481250](https://steamcommunity.com/sharedfiles/filedetails/?id=3787481250) |
| Tempo - A Performance & FPS Optimizer | Vyper | [3736629791](https://steamcommunity.com/sharedfiles/filedetails/?id=3736629791) |
| Lua Budget | mito551 | not on the Workshop; merged into PZ Butter (its rules stay in `Zomboid/LuaBudget.properties`; remove the LuaBudget mod from the mod list) |

The table lists what the project aims to cover; not everything each mod does is covered yet. The Viewpoint add-ons above need the Viewpoint mod itself ([3809306528](https://steamcommunity.com/sharedfiles/filedetails/?id=3809306528)). PZ Butter covers what the add-ons do, not Viewpoint.

## License

PZ Butter is free software under the GNU General Public License, version 3 (see `LICENSE`). You may use, change and share it. If you distribute it, or a work based on it, the whole work must be released under the same license, with its source.

The GPL's copyleft covers PZ Butter and works based on it. It does not reach:
- **Project Zomboid itself.** The game and its code are not covered.
- **ZombieBuddy.** It stays under its own license.
- **Other mods installed alongside PZ Butter**, for example in a modpack. They stay under their own licenses, unless they copy or adapt PZ Butter's code.

As a copyright holder, I grant permission to combine PZ Butter with, and run it inside, all three of the above.

Credit is appreciated but not required beyond what the GPL asks.
