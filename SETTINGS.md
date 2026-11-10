# Butter Frames settings

Back to the [README](README.md).

The mod creates `~/Zomboid/PZButter.properties` on first start and reads it once at game start. The master switch `enabled` is on by default. Each feature has its own default, chosen from measured gain and risk; each key is described in the file itself. At every start the file is brought up to date: new keys appear with their comments, your values are kept, and keys the mod no longer uses are kept at the end. The first such update saves the old file as `PZButter.properties.bak`.

| Key | What it does |
|---|---|
| `enabled` | Master switch. While it is `false`, the mod does nothing and the game runs stock. |
| `frameLog` | Writes one line per frame to `~/Zomboid/pzbutter/frames.out`. |
| `uiTiming` | Diagnostic: once a minute, the UI's time per frame and the 15 slowest windows to the console (and `~/Zomboid/pzbutter/ui.out` with `frameLog`). |
| `uiCensus` | Diagnostic, needs `uiTiming`: per window, what each render reads and calls, and how often it read the same values as its previous render (stable fraction and stable ms/s), and the same for the panels inside each window (reusable ms/s). Printed with the `uiTiming` report. |
| `uiRetained` | A window that read nothing new since its last frame is drawn from its recorded draw list instead of running its Lua again. Off by default. Needs `uiVerify`. |
| `uiRetainedChildren` | Every panel inside a window gets its own record, so a window that has to run its Lua again still reuses the panels in it that did not change, and a clean window calls its panels instead of redrawing them. Off by default. Works with `uiRetained` or `uiRetainCheck`. A window with a Java-drawn child (text box, map, button) still runs its own Lua each time; that child's own panels are reused. |
| `uiVerify` | Before `uiRetained` reuses a window it checks everything the window read: Lua fields, Java answers, item icons, its place and size, and its children. Lua upvalues (module `local`s) are outside its view: a window that reads only upvalues can replay stale state, so leave `uiRetained` off for such mods. On by default; `uiRetained` stays off without it. |
| `uiRetainedMods` | On by default. With `false`, only the game's own windows are reused; windows from mods always run. Used with `modProfile=custom`; the other profiles set it. |
| `uiRetainExclude` | Windows that always run live, by Java class name or Lua `Type`, comma separated. Default `ObjectTooltip,ISToolTip,ISToolTipInv`. |
| `uiRetainCheck` | Diagnostic: never reuses a window, but compares each draw list it would have reused with the live one and logs the first differences per window. |
| `luaWorkerGate` | Lua that Butter Frames's frame workers reach waits its turn and runs as the game thread, so the game's single Lua stack is never used by two threads. Needed by the zombie and entity updates on workers (a later version); until then it changes nothing. Off by default. |
| `luaFileWriteThrottle` | Changes when mod files reach the disk. A heartbeat, pulse, ping or temp-log file a mod opens again within 2 s, or any file it rewrites within 0.5 s, is kept in memory and written once the time is up (one disk write instead of up to 120 a second). The last content always reaches the disk, and a mod that reads the file back gets the latest content first. Off by default. |
| `logDedupe` | Changes the log. A line printed again within 3 s is dropped; every 3 s one summary line says how often it repeated. Lines with values filled in by the game are never dropped. Off by default. |
| `quietLog` | Changes the log. Vanilla lines the game already worked around (a font falls back to its image, a name is sanitised, a helper bone is ignored, a vehicle script part spec is skipped, a duplicate item id) are left out. Other lines print like before. Off by default. |
| `modProfile` | `custom` (default) uses `modCompat` and `uiRetainedMods` as written. `performance` sets `modCompat=report` and `uiRetainedMods=true`; `compatibility` sets `modCompat=auto` and `uiRetainedMods=false`, whatever those two lines say. |
| `modCompat` | Other Java mods that patch a game method Butter Frames replaces, or that replace a method Butter Frames patches. `off` (default); `report` lists them in the console at start; `auto` also switches those Butter Frames features off. Only exact class names are compared (a patch written with a class pattern is not seen), and a pair is listed even when the Butter Frames feature behind it is already off. |
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
| `lightingSyncPark` | `false` is stock. `true` parks the lighting thread to its next update instead of yield-spinning. |
| `corePlacement` | `off` by default. When on and the CPU is hybrid, the game, render and lighting threads and Butter Frames's frame workers restrict themselves to the performance cores. `off (not hybrid)` when the CPU is not hybrid. On Linux, threads the game starts after the pin inherit the placement. Linux and Windows only; untested on hybrid hardware. |
| `gpuPstate` | `false` by default. AMD GPUs on Linux only. When the sysfs files are writable, a daemon thread keeps the GPU clock at a steady level from each frame's GPU time. Needs a udev rule making the sysfs attributes writable (untested here): `ACTION=="add", SUBSYSTEM=="drm", KERNEL=="card[0-9]", DRIVERS=="amdgpu", RUN+="/bin/chgrp video /sys/%p/device/pp_dpm_sclk /sys/%p/device/power_dpm_force_performance_level", RUN+="/bin/chmod g+w /sys/%p/device/pp_dpm_sclk /sys/%p/device/power_dpm_force_performance_level"`. The first amdgpu card found is used. Restores the original level on shutdown; after a hard crash run `echo auto | sudo tee .../power_dpm_force_performance_level`. Only starts when the original level is `auto`. Untested here (NVIDIA machine). |
| `workerQosMac` | `off` by default. macOS only: sets the calling frame worker's QoS class with `pthread_set_qos_class_self_np` at the start of `run` (`user-interactive`, `user-initiated`, `utility`). Does nothing on Linux and Windows. |
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
| `driveTreeCutaway` | On by default. `true` is the game's own rule: while driving, every tree near the car turns see-through. `false` uses Build 42.20's rule: only trees between you and the camera. |
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
| `gloveBoxFix` | A vehicle whose GloveBox part has no item container no longer throws when adding a key. Off by default. |
| `vehicleAnimIdleSkip` | Parked car parts skip unchanged animation updates. Off by default. |
| `chunkHandoffDivisor` | Caps how many streamed chunks join per frame while driving (0 = off). Try 8. |
| `chunkHandoffFrameBudgetUs` | Microsecond budget for joining streamed chunks per frame (0 = off). Try 1500. |
| `handoffRecalcFast` | A joining chunk's squares recompute their properties once per join instead of on every neighbour pass (the same result as the game's; in testing it skipped 93 % of those recalculations). On by default. |
| `driveLookSmooth` | Above the frame cap the game assumes, the camera's look-ahead (driving, aiming, panning) moves by elapsed time instead of a fixed step per frame. Models, trees, shadows and overlays follow it in fractional pixels; the ground still moves in whole pixels. Off by default. |
| `cameraScreenPixels` | Zoomed in, models, trees, shadows and overlays follow the camera's look-ahead in whole screen pixels instead of whole offscreen pixels (smaller steps); the ground keeps offscreen pixels. Off by default. |
| `frameClockSmooth` | Changes simulation timing. Each frame's simulated time is a whole number of display-refresh periods (with vsync) or frame-cap periods, and the remainder carries into the next frame, so the game neither runs fast nor slow, but frame-time jitter no longer becomes uneven movement steps. No grid without a cap or vsync; without vsync it acts only while the game holds its cap (below it the game's own step is kept). Off by default: it changes simulation timing and nobody has judged the motion in game yet. |
| `wake` | The chunk streamer wakes when a chunk job is queued instead of sleeping through it (in testing, a queued chunk waited 4 ms on average instead of 61 ms). Needs the signed preload start; without it the game's sleep runs. On by default. |
| `streamTiming` | Records chunk streamer add-to-pickup latency once a minute (diagnostic). Off by default. |
| `driveTrace` | Writes a per-frame CSV of car, player and camera values while driving (diagnostic). Off by default. |
| `hotsaveIntervalSec` | Hot-save interval in seconds (0 = stock 1 s; 30 = 30 s). A crash can lose at least that many seconds of the world, and several times that when many chunks changed (the game writes at most about 10 chunks per hot save). Saving and quitting always write everything. |
| `numberZombiesBlended` | How many of the most relevant zombies get blended animations. `0` keeps the game's 20; `1` to `64`. Fewer looks stiffer and costs less. |
| `zombieLodFixed3d` | Changes the simulation. At most this many zombies are drawn as 3D models; the rest are flat sprites and, like the game's own zombies past 510, update less often. `0` keeps the game's 510; `50` to `510`, never below `numberZombiesBlended`. |
| `zombieModelAddBudgetUs` | Changes the simulation for a few frames. Microseconds a frame for zombies getting their 3D model; past it the next ones stay sprites (and update less often) one frame longer. At least one gets its model each frame. `0`: no limit, as the game. |
| `zombieSimLodTiles` | Changes the simulation. Zombies and animals farther than this many tiles from every player update every other frame (and every 4th, 8th with more steps), one step nearer than the game's own 30, 60 and 80 tiles. `0`: off. |
| `zombieSimLodSteps` | With `zombieSimLodTiles`: extra steps, each at twice the previous distance (`1` to `3`). Default `1`. |
| `playerLosFast` | The player's sight pass checks "have I seen this one before" with a set instead of searching a list of every zombie seen recently (millions of checks a frame in a big horde). The same answers. A Java or Lua mod that kept the player's old list from before the swap would see a list that no longer changes. Off by default. |
| `zombieCullSortFast` | The sort that picks which zombies get 3D models works each zombie's score out once instead of twice per comparison. The same order. Off by default. |
| `statsNoBox` | Character stat reads skip a throwaway object per read. The same values. Off by default. |
| `luaGcNoop` | A mod's `collectgarbage()` no longer forces a full collection. Off by default. |
| `entityTiming` | Diagnostic. Once a minute the console gets the time per frame of the zombie update phases (sorting into update tiers, update, movement and animation, 3D model picking, the player's sight pass) and the zombie count (the phases overlap: the sight pass runs inside the update and movement phases, so do not add the columns up); with `frameLog`, one line per frame in `Zomboid/pzbutter/entity.out`. Off by default. |
| `skipIdChecks` | The building and room ID consistency walks the game runs on every continued save run only in debug; outside debug their findings only went to the debug log. Off by default. |
| `noLoadFade` | Changes what you see: no 350 ms fade to black between the finished load and the first game frame. Not on the first new game of a session. Off by default. |
| `shaderCache` | A model whose shader is already built takes it without a render-thread round trip. In testing it removed about 20 s of waits from the loading thread, but the load was no shorter: it then waited for clothing and animation assets instead. Off by default. |
| `nullModelGuard` | A model slot whose model is not loaded yet (a freshly spawned NPC) is skipped for one render pass instead of stopping the render loop and killing the character's 3D view for the session. On by default. |
| `doorToggleNpeGuard` | A zombie opening or closing a door no longer logs an NPE (isLocalPlayer on a null player) after the door's state is already toggled and synced. On by default. |
| `loaderCpuFixes` | Vehicle zones are de-duplicated in one pass (debug string built only when vehicle debug is on). With vehicle debug output on, the duplicate-zone lines are the same but name Butter Frames as their caller. In testing it removed about 3 s of the loading thread's work (the load itself was bounded by asset loading). On by default. |
| `scriptParserFast` | Large script files have their comments stripped in linear time, with the same result (the game's 6.4 MB `tileGeometry.txt`: 1.2 s to about 0.03 s at boot). Small files keep the game's own code. In testing, boot was about 2 s shorter. On by default. |
| `textureBufferMb` | During the world load: decoded texture data that may wait for upload before the decoders pause, in MB (0 = stock 50). Default `-1`: 256 where `fileThreads` auto widens the pool, else stock. Together with the next two, the world load fell from about 54 s to 45 s on a 12-core PC (each alone, to about 50 s); peak memory during the load is about 200 MB higher. In play all three are stock. |
| `fileThreads` | During the world load: width of the file thread pool (0 = stock: 4, or 2 on 4 cores or fewer). Default `-1`: cores minus 4, at most 8, never below stock (machines with 8 cores or fewer keep stock). |
| `fileInflight` | During the world load: file tasks in flight at once (0 = stock 16). Default `-1`: 64 where `fileThreads` auto widens the pool, else stock. Needs the preload start (one restart after a change); the console line `filePipeline` says whether the rewrite is active. |
| `modWatchAsync` | The game watches every installed mod's media folders for changes and walks them all on the game thread at boot. With this key the walk and the watch registration run on a background thread: the watches are the same, complete a few seconds later (a mod file changed in that time goes unnoticed). In testing with 364 workshop items (49,507 folders), boot fell from about 54 s to 38 s. On by default. |
| `texCompress` | With the game's texture compression on, the driver compresses every texture on the render thread while the world loads. Butter Frames encodes them (BC3/DXT5) on the file-loading threads instead and uploads the ready blocks; any texture that does not match exactly is left to the driver. It acts only with the game's texture compression on, and only where the driver itself would store the textures as S3TC (checked once at start with one small texture; a driver that keeps them uncompressed gets no change). Needs the preload start (one restart after a change); the console line `texCompress` says whether it acted. In testing (RTX 3080), the world load fell from about 45 s to about 40 s, and the textures looked the same in a side-by-side check. On by default. |
| `frameThreads` | Width of Butter Frames's frame worker pool for the zombie update on workers. Needs `luaWorkerGate`, and one restart after it changes. `0` or `1`: no pool, no class changes. Max `32`. Nothing uses the pool yet in this version. Off by default (`0`). |
| `zombieAnimDistantSkip` | Zombies farther than `zombieAnimDistantTiles` tiles from every player refill their bone matrices one frame in `zombieAnimDistantEvery` (staggered); animation clock, events and movement stay stock. Changes what you see. Off by default. |
| `zombieAnimDistantTiles` | Distance for `zombieAnimDistantSkip` (`50`). |
| `zombieAnimDistantEvery` | Cadence for `zombieAnimDistantSkip` (`4`). |
| `zombieBoneSkipOffscreen` | Zombies off screen for every player refill their bone matrices one frame in `zombieBoneSkipOffscreenEvery` (staggered); a zombie coming into view can show its pose from up to N frames ago for one frame, the in-game check decides. Animation clock, events and movement stay stock. Changes what you see. Off by default. |
| `zombieBoneSkipOffscreenEvery` | Cadence for `zombieBoneSkipOffscreen` (`8`). |
| `persistentVbo` | The sprite and puddle buffers keep storage that stays mapped (OpenGL 4.4 or `GL_ARB_buffer_storage`) instead of asking the driver for new storage and a mapping every 64 KB of sprites. Off by default; stays off by itself on older drivers. |
| `persistentVboSlots` | With `persistentVbo`: storages per buffer, used in turn (`1` to `8`, default `4`; fewer when the buffers would pass 64 MB). |
| `persistentVboFrameSync` | With `persistentVbo`: one fence per frame decides when a storage may be written again, and a storage from 8 frames ago is reused without asking. `false`: one fence per batch. Default `true`. |
| `vboBatchKb` | Batch size, in KB, of the game's drawer for lines, shapes, the world map, fog and rain (`0` keeps the game's 4 KB; `8` to `1792`). Bigger batches mean fewer uploads and draw calls. |
| `vboSizeClasses` | Covers VRAM Steady. That drawer's uploads go into storage of a few fixed sizes (`orphan` re-specifies the same size, `subdata` only grows) instead of new storage of the exact size each time. Video memory then stays level instead of climbing and dropping. `off` by default; it switches itself off for the session if uploads take over 250 ms a second. Use this or VRAM Steady, not both. |
| `vboSizeClassMinKb` | With `vboSizeClasses`: the smallest storage size in KB (default `64`). |
| `smartTextureRelease` | Covers Character Texture VRAM Leak Fix. When a character's or item's combined texture is rebuilt (blood, dirt, clothes), the old one goes back to the game's texture pool instead of staying in video memory until the model is removed. Never gives a texture back twice. Off by default. Use this or that mod, not both. |
| `bakeNoSync` | Texture and framebuffer names come from a pool, and framebuffer checks, uniform locations and "which shader is active" are answered from Butter Frames's own records instead of asking the driver. Each such question makes a threaded driver finish all queued work first. Off by default. |
| `bakeNoSyncCheck` | Diagnostic for `bakeNoSync`: every 256th answer is checked against the driver and up to five mismatches go to the console. Off by default. |
| `bakeBudgetGpu` | With `bakeBudgetAdaptive`: the bake budget also counts the GPU's time for the frame, from GPU timestamps read three frames later. It never waits for the GPU. Off by default. |
| `gpuStats` | Diagnostic. Once a minute the console gets what the Plan 9 keys did: mappings saved, storage waits, uploads and storage changes, textures given back, names from the pool, answers from the records, GPU time per frame. With `frameLog`, the same as one line a minute in `Zomboid/pzbutter/gpu.out`. Off by default. |
| `limiterSleep` | The frame limiter sleeps until 0.2 ms before the frame instead of spinning. On by default. |
| `renderIdleWait` | The render thread waits for the next frame instead of spinning. Off by default. |
| `presentPacing` | Hold each swap to a steady time after the game moment it shows (`auto` with VRR, `off`, `on`). Off by default. |
| `presentPacingMaxPct` | With `presentPacing`: the longest hold, as a percentage of a frame (default `100`). |
| `presentPacingMarginPct` | With `presentPacing`: a margin added to the hold (default `0`). |
| `vrr` | Variable refresh rate (`auto` detects it from the display driver; `off`/`on` force it). Off by default. |
| `vrrCap` | With VRR: uncapped, or above the panel maximum cap at refresh minus refresh squared over 3600. `false` by default. |
| `borderlessFullscreen` | Make the borderless window a fullscreen window at the desktop mode (`auto` with VRR). Off by default. |
| `vsyncAdaptive` | Use swap interval -1, so a late frame tears instead of waiting a refresh. On by default. |
| `gpuMaxFrames` | At most this many frames queued for the GPU (`0` = off). Acts only when the GPU falls behind (default `2`). |
| `reflexSleep` | Measure each frame's queue wait and start the next frame that much later. On by default. |
| `reflexBoost` | Use NVIDIA's prefer-maximum-performance mode while in a world. Off by default. |
| `reflexCapFps` | With vsync: the frame cap (`0` = automatic, refresh minus refresh squared over 3600). |
| `vblankLock` | With vsync: start frames a measured time before vblank (X11 and NVIDIA only). Off by default. |
| `luaBudget` | The merged LuaBudget mod. Its rules stay in `Zomboid/LuaBudget.properties`; with no rules it has no effect. On by default. |
| `luaSkipEmpty` | Do not call Lua functions with an empty body (`function() end`). On by default. |
| `luaTiming` | Diagnostic. Once a minute, the ten slowest Lua event handlers go to the console, and to `lua.out` with `frameLog`. Off by default. |
| `luaRawgetFast` | Lua table reads do one hash lookup instead of two. Measured slower than stock, so off by default. |
| `luaInternConstants` | Intern the string constants of loaded Lua, so table lookups match by identity first. On by default. |
| `uiTickStagger` | Run each window's ten-per-second Lua update in its own slot instead of all in one frame. On by default. |
| `uiLuaFast` | The inventory item list skips its second drawing pass when nothing is dragged. On by default. |
| `modOptionsFast` | While Lua (re)loads (game boot, and a save load that reloads Lua), `PZAPI.ModOptions.load` reads `ModOptions.ini` once instead of once per mod, about 2 s at boot by the profile. Options registered later still get their saved values; stock `load` is back at the main menu. Off by default until the in-game check. |
| `textWidthCache` | Keep measured text widths; context menus otherwise re-measure every option on every opening. On by default. |
| `mapStreetMemo` | Keep street lengths and names per view and language, and one index map per label sort. On by default. |
| `mapStreetCache` | Reuse the street-label layout while the map view is unchanged. On by default. |
| `mapVisitedFast` | Build the explored-area texture a row at a time. On by default. |
| `inputLatch` | Poll fresh input when the frame starts. On by default. |
| `keyboardFresh` | Read the keyboard from the poll this frame swapped in. On by default. |
| `frameStartGate` | Wait for the render thread before reading input. On by default. |
| `cursorLatch` | Move the cursor to the newest pointer position just before the frame is drawn. Off by default. |
| `aimHoldMs` | Right-button hold before aiming starts, in ms (default `150`; `0` aims at once). |

The options page (Plan 19): Options > Mods > "Butter Frames" offers this main table's boolean keys as tickboxes. A tickbox writes the settings file at once (`Settings.setFromUi` accepts only the canonical `true`/`false` — anything else is rejected, the old value stays, and the tickbox goes back to it) and applies at the next game start. The `ModOptions.ini` file the game saves for every mod is never read back for these values.

### Server and hosting (Plan 15)

These keys act only on a dedicated server or a game hosted from the menu; on a client they stay off with one console line.

| Key | What it does |
|---|---|
| `serverTickLog` | One line per server tick in `Zomboid/pzbutter/ticks.out`, in the same five columns as `frames.out`; once a minute a line gives p50, p99 and max. A tick over 100 ms also names its largest stage (net, Lua event or world). Off by default. |
| `serverLuaSample` | Diagnostic. A watchdog samples the server main thread when a tick runs long and writes `Zomboid/pzbutter/hitches.out`. Off by default. |
| `containerSendGuard` | Skips `sendAddItemToContainer` calls that have no character, parent object or world item to send to; the game returns without sending in exactly those cases. Every working call still goes through. On by default. |
| `processItemsMerge` | Adds a batch of 128 or more items to a cell's process list in one pass instead of scanning the list once per item; the same list and order. Never on a multiplayer client. Off by default. |
| `serverPrewarm` | First server tick loads the animation sets and action groups the first player, vehicle, zombie and every animal would load, and logs the extra heap. Off by default. |
| `serverCellSpread` | Spreads a cell's second-stage load (`RecalcAll2`) over server ticks inside a per-tick budget, at least one chunk per tick; keeps `isLoaded` false between slices so the half-done cell is invisible, keeps a 120 ms floor near a player. Off by default. |
| `serverClassPrewarm` | First server tick loads the game classes Butter Frames patches, so ZombieBuddy's weaving happens before the first join rather than during it. Off by default. |
| `hostZombieBuddy` | Carries a ZombieBuddy `-javaagent:` entry into the hosted server's child JVM, but only when that agent already runs a non-interactive policy (`deny-new` or `allow-all`); an untrusted headless child would otherwise wait on a console prompt. Off by default. |
| `hostJvmArgs` | Uses `Zomboid/PZButter-host.args` as an extra `@file` in the hosted server's child JVM, after the game's `-Xms/-Xmx`. The file is ignored unless it is at most 4 KB, ASCII and 16 lines of blanks, comments, a small `-Xms` or a plain `-Xlog` line. Off by default. |

### Lua handlers (Plan 17)

| Key | What it does |
|---|---|
| `handlerCensus` | Diagnostic and public API. Wraps `Events.<Name>.Add/.Remove` for per-tick and per-frame events, records each handler's function, source file, first line and registration count, then at `OnGameStart`/`OnGameBoot` assigns it to its mod folder and prints one overview line. Run-time registrations only bump a counter. On by default. |

### Multiplayer client (Plan 15)

These keys act only on a multiplayer client; on a server they stay off.

| Key | What it does |
|---|---|
| `vehicleEarlyParse` | `off`, `verify` or `on`. A full vehicle update can arrive before its chunk, in which case the game drops it and the car pops in later. `verify` keeps the bytes and compares the next answer without ever placing the car early; `on` also places it at the end of the client update and removes it before a different answer. Off by default. |
| `zombieDropItemsGuard` | A zombie's item drop no longer sends the PlayerDropHeldItems packet (its setData throws on a zombie, nothing was ever sent). Player drops keep working. On by default. |
| `kahluaPoolDoublePut` | A failed Lua-to-Java call no longer returns its argument object to the game's argument pool twice (the second put made two nested calls share one object and fail far from the real error). Same overload choice, calls, return values and errors. On by default. |
| `chatIconsRace` | Collecting the allowed chat icons reads an ordered snapshot of the shared texture table under a lock it shares with the table's writers, instead of scanning the live table while background loading changes it (which crashed a join). Icon coverage is unchanged, and icon names that only differ in case keep their stock winner. On by default. |
| `bodyLocationOnce` | `getAllItemsForBodyLocation` (used by A-Life's creator) resolves the body-location string once per call instead of once per clothing item it checks. Order, comparisons and results are unchanged. Off by default. |
| `chunkRefill` | Asks again for a chunk slot whose load failed while the player stays in one place, since the streamer keeps the dead entry until the player moves away. A hole is due after 4 s quiet or 25 s busy, at most 6 asks every 3 s, with 15-120 s backoff. Off by default. |
| `vehicleSendMs` | The physics send period of a car you drive, 16-150 ms. Only vanilla's moving period (exactly 150 ms) is shortened; its 300 ms parked period and other mods' periods stay. The server forwards each packet at once, so other players get the shorter period. Covers SmoothRide MP's send boost. Default 150 (stock). |
| `vehicleRemoteSmooth` | Draws other players' cars from a buffer at an adaptive delay (about 60-120 ms with a 33 ms send period, against vanilla's 500 ms), on a curve between packets when the curve stays close to the straight line. Through a short packet drop the car keeps turning and slowing as it was; every jump is blended in over about 120 ms, and a trailer is drawn at its car's instant. It stays off, with one console line, if this game build's fields do not match or its self-test fails. Covers SmoothRide MP's receiver. Off by default. |

### Dev keys



| Key | What it does |
|---|---|
| `devCensus` | Diagnostic. Counts, in play, which game calls a calm zombie's update reaches, and writes `Zomboid/pzbutter/census.out` once a minute. Needs the preload (one restart). Off by default. |
| `devMark` | Diagnostic. A key writes window marks (`mark <name> <epoch ms>`) to `Zomboid/pzbutter/marks.out` next to `frames.out`, for use with the frame log. Key: `devMarkKey` (default `F9`). Off by default. |
| `devMarkKey` | Dev. Key for `devMark` (default `F9`). |
| `spikeContext` | Diagnostic for the frame log. Each frame over `spikeThresholdMs` is classified by what the player was doing (driving, moving, chunk, menu, uncat) and appended to `Zomboid/pzbutter/spikes.out`; once a minute the counts go to the console (`[PZButter] spikes: ...`). Off by default. |
| `spikeThresholdMs` | Dev. With `spikeContext`, a frame longer than this many ms is a spike (default `50`). |
| `spikeContextWindowMs` | Dev. With `spikeContext`, a chunk load or a context menu this many ms before a spike names it (default `750`). |
| `hitchSampler` | Diagnostic for the frame log. A watchdog outside the frame samples the game and render threads while a frame runs far past its recent median, and writes one line per hitch (`frame ms`, main frames, LuaTiming's current function, whether the game thread waits for the render thread, the render thread's deepest frames) to `Zomboid/pzbutter/hitches.out`. Off by default. |
| `netStats` | Diagnostic. Writes per-packet-type counts and bytes once a second to `Zomboid/pzbutter/net.out` on a client and `net-server.out` on a server. Off by default. |
| `stallProbe` | Diagnostic. A sleeper outside the game threads writes one line to `Zomboid/pzbutter/stalls.out` when a 2 ms sleep returns 50 ms or more late: how late, safepoint count/time deltas, GC collection deltas per collector, major page-fault delta and `MemAvailable`. Off by default. |
| `devAutoContinue` | Dev. On the main menu, presses Continue once per process. Off by default. |
| `devScene` | Dev. `off`/`stand`/`walk`/`drive` scene driver that writes scene.start and scene.end marks; needs a scene. Off by default. |
| `devSceneSettleSec` | Dev. Seconds after world ready before `scene.start` (default 20); needs devScene. |
| `devSceneSec` | Dev. Seconds from `scene.start` to `scene.end` (default 60); needs devScene. |
| `devSceneQuit` | Dev. Quit to desktop 3 s after `scene.end`. Needs devScene. Off by default. |
| `devNewSave` | Dev. With `devAutoContinue`, creates a new Sandbox world with this name on the active mod list instead of pressing Continue: default sandbox settings, an unemployed character with no traits. `tools/newsave.sh NAME` sets it for one launch. Empty (off) by default. |
| `devNewSaveAt` | Dev. Where `devNewSave`'s player starts, `x,y,z` in squares. Default `13652,3136,0`, the spot the earlier full test saves used. |
| `devPopulation` | Dev. Zombies spawned once at settle start. Needs devScene. Off by default. |
| `devRoute` | Dev. Square offsets for `devScene=walk`, `dx,dy;dx,dy;...` (default empty: walk acts like stand). Needs devScene. |
| `devDriveCar` | Dev. Vehicle script for `devScene=drive` (default `Base.CarNormal`). Needs devScene. |
| `devDriveKmh` | Dev. Target speed in km/h for `devScene=drive` (default 60). Needs devScene. |
| `devWeather` | Dev. Weather applied once at settle start (`off` = nothing; `storm` = the game's own debug storm trigger, refused where a weather period already runs). Needs devScene. Off by default. |
| `devHour` | Dev. Hour of day set once at settle start (-1 = leave it; 0-23; anything else is ignored). Needs devScene. Off by default. |
| `ltpeVirtualInsertion` | Long Term Preservation Extended: oats are inserted into the parsed distribution data instead of by changing the Lua tables. Off by default; in practice it excludes the LTPE fix mods. |
| `windSwayAutoTreeDetail` | WS-2 for the PZButter_WindSway mod (on by default): samples Butter Frames's GPU frame time and lowers Wind Sway's tree detail (and restores it) when the GPU stays above or below target. Butter Frames is the data source; Wind Sway applies it. |
