# Butter Frames

Butter Frames is a Project Zomboid Build 42 mod that aims for smoother frames. It is Java code, loaded by [ZombieBuddy](https://github.com/zed-0xff/ZombieBuddy) and patching the game at runtime. 

Butter Frames covers the work of many performance mods in one: sound, lighting, wall cutaways, weather, zombie updates, vehicles, UI redraws, texture memory and loading. Every feature has its own switch.

Two groups of mods are listed below: [mods that inspire Butter Frames](#mods-that-inspire-butter-frames) and [mods that get optimizations](#mods-that-get-optimizations).

## Vanilla vs PZ Optimization vs Butter Frames

| Scene | Metric | Vanilla | PZ Optimization | Butter Frames |
|---|---|---|---|---|
| S3 (town walk) | p50 frame ms | TBD | TBD | TBD |
| S3 (town walk) | p99 frame ms | TBD | TBD | TBD |
| S3 (town walk) | 1% low fps | TBD | TBD | TBD |
| S6 (horde) | p99 frame ms | TBD | TBD | TBD |
| Boot | seconds to main menu | TBD | TBD | TBD |
| Load | seconds to playable | TBD | TBD | TBD |

<sub>Test machine: Intel Core i7-8700K (6 cores, 12 threads) at 3.7 GHz, NVIDIA GeForce RTX 3080, 16 GB RAM, Fedora Linux, Project Zomboid Build 42.21, vanilla game with no other mods. Three runs per setting, median shown.</sub>

## Mechanics it covers

- Sound: ambience and zombie sound updates, world-sound cleanup, hearing, audio limiter, FMOD slot-exhaustion crash
- Lighting: light lookups, light switches, lighting thread rate, rebake budgets
- Walls, roofs and cutaways: cutaway walk, occlusion grid, roof flicker, buildings-in-front scan
- Weather: rain splashes, weather mask, puddles, fog
- Trees, chunks and glass: tree cutaway, chunk texture baking and its scheduler, translucent tiles
- Zombies: model add budget, simulation level of detail, line of sight, culling sort, facing fix
- Vehicles and driving: idle animation skip, chunk handoff, smooth driving look, glove box fix
- Frame pacing: frame clock smoothing, input latency, zoom ease
- UI: windows that did not change are drawn from a recorded draw list; tooltips always run live
- Lua: a per-frame Lua time budget, throttled mod file writes, log de-duplication
- Memory: texture release, persistent vertex buffers, steady VRAM uploads
- Boot and loading: mod options loading

Every setting is listed in [SETTINGS.md](SETTINGS.md).

## For mod authors

If Butter Frames patches your mod or - god forbid - contains your code - please let me know and I will remove it. I would gladly share any code that optimizes your mods so that you can integrate on your side, and remove it from my mod. 

## FAQ

**Do I need anything besides ZombieBuddy?** No. Butter Frames is a Java preload mod that ZombieBuddy loads. On the first start ZombieBuddy asks you to approve it; approve it and trust the author, since ZombieBuddy registers a preload only for a signed, trusted author.

**What does it change in the game?** It patches game methods in memory; no game files are modified. Most features return the same result with less work. The ones that change what you see or hear (for example `audioLimiter`, `lightingFps`, `cutawayRadius`) say so in [SETTINGS.md](SETTINGS.md).

**How do I turn one feature off?** Set its key to `false` in `~/Zomboid/PZButter.properties` (read once at start), or use Options > Mods. `enabled=false` turns everything off and the game runs stock.

**Can I run it with PZ Optimization, Tempo or another optimizer?** You can, but their patches can overlap. Set `modCompat=report` to list the overlaps in the console; `auto` switches off the overlapping Butter Frames patch.

**How are the numbers measured?** Every frame is timed (`frames.out`) and each feature is judged by an A/B run of a scripted scene, comparing p50, p99 and 1% low frame times plus boot and load time, not by feel. A feature is on by default only if it is a clear win or the tradeoff holds up in testing.

**Is it a copy of those mods?** No. It is clean-room: written from behaviour descriptions and the game's own bytecode, with none of their code.

**I wrote a mod you patch. Can I opt out or get the code?** Yes to both. See [For mod authors](#for-mod-authors).

## How it works

- **Loading.** Butter Frames is a ZombieBuddy preload mod: a Java jar whose patches are applied to the game's classes in memory at start. No game code is copied, modified on disk or decompiled into the jar.
- **Patches.** Each feature is a small patch on one game method or a few. It either skips work whose result cannot have changed, does the same work with fewer calls, or spreads work over frames under a time budget. A feature that changes output is documented as such in [Settings](#settings).
- **Switches.** Every feature has a key in `~/Zomboid/PZButter.properties`, read once at start, and a page in Options > Mods. A feature that is off leaves the game's own behaviour in place.
- **Lua.** Mod Lua is timed per function. A Lua time budget (`LuaBudget`) can throttle callers that overrun, a retained-UI layer redraws windows from a recorded draw list when nothing they read has changed, and mod file writes can be batched.
- **Frames.** Frame work is measured every frame (`frames.out`), so a change is judged on frame-time percentiles from a scripted run, not on how the game feels.
- **Checks.** Each feature must pass the self-tests (`./build.sh`) and an A/B run in the game before it is switched on by default.
- **Compatibility.** `modCompat` finds other Java mods that patch the same game method and can switch the overlapping Butter Frames patch off.

## Requirements

- Project Zomboid Build 42.21 or later.
- ZombieBuddy, installed and enabled as its own instructions describe.
- **OPTIONAL:** ZombieBuddy Extensions, for hosting a multiplayer game from the menu.

## Install from source

Building needs JDK 25 and a local Project Zomboid install.

```sh
./build.sh --install
```

This builds the mod, runs the self-tests, and copies the mod to `~/Zomboid/mods/PZButter`. If the folder already exists, nothing happens. Then enable **ZombieBuddy** and **Butter Frames** in the game's mod list.

`build.sh` finds the JDK and the game through two environment variables, with these defaults:

| Variable | Default |
|---|---|
| `JDK` | `/usr/lib/jvm/java-25-openjdk` |
| `PZ_GAME` | `<Steam library>/steamapps/common/ProjectZomboid/projectzomboid` |

Hopefully it won't need any code changes between versions. It targets B42.21 initially and 42.22 eventually. That would be the real test on whether I can just make a build script for vanilla PZ and leave it up to the community to make new builds.

## Settings

Every key, its default and what it changes is listed in [SETTINGS.md](SETTINGS.md). The notes below apply to all of them.

Butter Frames is a ZombieBuddy preload mod. On the first start, ZombieBuddy asks you to approve it; approve it and also choose to trust its author, since ZombieBuddy registers a preload only for a signed jar whose author is trusted. The first start after installing or updating Butter Frames runs without the preload, so workers are off until one restart. A game update switches workers off (status `revision X != list Y`) until Butter Frames is updated. Unticking Butter Frames in the mod list makes it print `[PZButter] not in the active mod list; nothing is switched on` and turn nothing on; ZombieBuddy still keeps it as a preload, so its jar keeps loading at start with every feature off, and to remove it completely you delete or unsubscribe the mod, which lets ZombieBuddy drop a preload whose jar is gone. The console status lines are: `[PZButter] workers: active (...)` means the transform and pool are on; `... needs a restart` means the settings ask for workers but the preload did not run this start; `... off (<reason>)` means workers are off for that reason.

With any of `zeroFacingFix`, `numberZombiesBlended`, `zombieLodFixed3d`, `zombieModelAddBudgetUs`, `playerLosFast` or `zombieCullSortFast` on, the console gets one `[PZButter] zombies:` line a minute when any of them did something: culls and grants counted, zombies the 3D cap and the model budget turned into sprites (`capped`, `deferred`), sorts done and score misses, sight-list swaps and rebuilds, and zero-length facings kept. `zombieCullSortFast` and `playerLosFast` also log one line when they start working (`bound`, `player N swapped`).

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

## Mods that inspire Butter Frames

Butter Frames is inspired by the mods below and aims to cover what they do, in one mod. It is a clean-room project: it contains none of their code. It is written from behaviour descriptions of each mod and from the game's own bytecode.

| Mod | Author | Workshop |
|---|---|---|
| PZ_Optimization (pzopt) | xD3I | no longer on the Workshop (the old link 3805285544 is dead) |
| [B42] PZ Optimization Preloader | Antikristianos | [3818209799](https://steamcommunity.com/sharedfiles/filedetails/?id=3818209799) |
| Undying Optimizer | Mister K | [3777958977](https://steamcommunity.com/sharedfiles/filedetails/?id=3777958977) |
| Viewpoint - Smooth Frames | EhChad | [3816784011](https://steamcommunity.com/sharedfiles/filedetails/?id=3816784011) |
| Viewpoint Turbo | Fatalitiko | [3812559973](https://steamcommunity.com/sharedfiles/filedetails/?id=3812559973) |
| Viewpoint Fixes | zirmango | [3817842243](https://steamcommunity.com/sharedfiles/filedetails/?id=3817842243) |
| Viewpoint PZO Eclipse Shader Compatibility | Hesin | [3815225881](https://steamcommunity.com/sharedfiles/filedetails/?id=3815225881) |
| VRAM Steady | VRAM Steady authors | [3819844505](https://steamcommunity.com/sharedfiles/filedetails/?id=3819844505) |
| Character Texture VRAM Leak Fix [ZombieBuddy] | syeon | [3821525282](https://steamcommunity.com/sharedfiles/filedetails/?id=3821525282) |
| Project Zomboid Optimizer (PZO) - B42 FPS Boost | prop11 | [3787481250](https://steamcommunity.com/sharedfiles/filedetails/?id=3787481250) |
| Tempo - A Performance & FPS Optimizer | Vyper | [3736629791](https://steamcommunity.com/sharedfiles/filedetails/?id=3736629791) |
| Oz's Fast Boot & Loading Screens (OzFastBoot) | Oz (Oz Server Modpack) | [3819476262](https://steamcommunity.com/sharedfiles/filedetails/?id=3819476262) |
| Zed's Better FPS KA | zed_0xff, Kaoeutsu | [3793137588](https://steamcommunity.com/sharedfiles/filedetails/?id=3793137588) |
| PzPerformanceFixes | Alex | [3826630779](https://steamcommunity.com/sharedfiles/filedetails/?id=3826630779) |
| Smooth Performance Optimizer | Erunest | [3826681202](https://steamcommunity.com/sharedfiles/filedetails/?id=3826681202) |
| Lua Budget | mito551 | not on the Workshop; merged into Butter Frames (its rules stay in `Zomboid/LuaBudget.properties`; remove the LuaBudget mod from the mod list) |
The table lists what the project aims to cover; not everything each mod does is covered yet. The Viewpoint add-ons above need the Viewpoint mod itself ([3809306528](https://steamcommunity.com/sharedfiles/filedetails/?id=3809306528)). Butter Frames covers what the add-ons do, not Viewpoint.

## Mods that get optimizations

These mods stay as they are. For each one there is a local fix mod, a separate mod that loads after the target and patches only the named hot spots in the target's Lua (and, where noted, in Java). The status column says where each stands:

- **ships**: measured gain, part of 1.0.
- **in development**: built or started, not yet measured.
- **paused: no measured gain**: the 2026-11 A/B runs (full mod list, 3 runs per arm) showed p99 within noise, so it is held back until a scene shows a gain.
- **paused: target unavailable**: the target mod is gone from the Workshop for now.

| Mod | Author | Workshop | Status | Fix mod |
|---|---|---|---|---|
| that DAMN Library | KI5, bikinihorst | [3171167894](https://steamcommunity.com/sharedfiles/filedetails/?id=3171167894) | ships | square index (`PZButter_DamnLib`) |
| that DAMN Library (forced vehicle spawns) | KI5, bikinihorst | [3171167894](https://steamcommunity.com/sharedfiles/filedetails/?id=3171167894) | in development | `PZButter_DamnSpawns` (from `DAMNSpawnsPerf`): one native call per streamed square instead of a Lua scan |
| KI5 vehicle mods | KI5 | several; see DAMN Library | ships | `PZButter_KI5` runs their `OnPlayerUpdate` handlers together |
| Immersive Snow | elu | [3810071828](https://steamcommunity.com/sharedfiles/filedetails/?id=3810071828) | paused: no measured gain | `PZButter_ImmersiveSnow` |
| Companion Dogs | Björn, Freylith | [3740052292](https://steamcommunity.com/sharedfiles/filedetails/?id=3740052292) | paused: no measured gain | `PZButter_CompanionDogs` |
| Cye's Push Doors! | Cye's | [3780683663](https://steamcommunity.com/sharedfiles/filedetails/?id=3780683663) | paused: no measured gain | `PZButter_CyesPushDoors` |
| Evolving Traits World | MusicManiac | [2914075159](https://steamcommunity.com/sharedfiles/filedetails/?id=2914075159) | paused: no measured gain | `PZButter_EvolvingTraitsWorld` |
| Whose Key Is This? | [MLT]뉴비 까마귀 | [3784348098](https://steamcommunity.com/sharedfiles/filedetails/?id=3784348098) | paused: no measured gain | `PZButter_WhoseKeyIsThis` |
| Tsar's Common Library B42 | iBrRus, Tchernobill | [3402491515](https://steamcommunity.com/sharedfiles/filedetails/?id=3402491515) | paused: no measured gain | `PZButter_TsarsLib` |
| Wind Sway | armaku | [3782670683](https://steamcommunity.com/sharedfiles/filedetails/?id=3782670683) | in development | `PZButter_WindSway` |
| Xen's Enhanced GUI | Xen | [3819012597](https://steamcommunity.com/sharedfiles/filedetails/?id=3819012597) | in development | `PZButter_XensEnhancedGUI` |
| QoL Compendium | Aspct | [3781012462](https://steamcommunity.com/sharedfiles/filedetails/?id=3781012462) | in development | `PZButter_QoLCompendium` |
| Project Viewpoint QOL | KodeMan | [3811256204](https://steamcommunity.com/sharedfiles/filedetails/?id=3811256204) | in development | `PZButter_ProjectViewpointQOLLegacy` |
| Long Term Preservation Extended | [NKD] SyCO PRiNCESS | [3597673472](https://steamcommunity.com/sharedfiles/filedetails/?id=3597673472) | in development | `PZButter_LongTermPreservationExtended` and `_Index` |
| Project A-Life NPCs | Vice, Link | [3803984183](https://steamcommunity.com/sharedfiles/filedetails/?id=3803984183) | in development | `PZButter_ProjectALifeNPCs`: skips per-zombie engine calls for zombies without mod data and thins out timer-gated per-frame handlers; one tick box per patch |
| Authentic Z | Authentic Peach | [2335368829](https://steamcommunity.com/sharedfiles/filedetails/?id=2335368829) | in development | `PZButter_AuthenticZ` (from `AZ_Perf`): glowstick ground-light scanning moved to Java |
| Better Generator Info | Eurymachus | [3576056135](https://steamcommunity.com/sharedfiles/filedetails/?id=3576056135) | in development | `PZButter_BetterGeneratorInfo` (from `BGI_Perf`) |
| Expanded Storage | Bing | [3777973578](https://steamcommunity.com/sharedfiles/filedetails/?id=3777973578) | in development | `PZButter_ExpandedStorage` (from `ExpandedStorageZB`): container capacity overrides in Java |
| Sunflower's Ultimate Character Overhaul | - | not on the Workshop now | paused: target unavailable | `PZButter_SunflowersUltimateCharacterOverhaul` |
| Viewpoint Extended Support | - | not on the Workshop now | paused: target unavailable | `PZButter_ViewpointExtendedSupport`; returns with the next Viewpoint version |
| Viewpoint Living Props | - | not on the Workshop now | paused: target unavailable | `PZButter_ViewpointLivingProps`; returns with the next Viewpoint version |

## License

Butter Frames is free software under the GNU General Public License, version 3 (see `LICENSE`). You may use, change and share it. If you distribute it, or a work based on it, the whole work must be released under the same license, with its source.

The GPL's copyleft covers Butter Frames and works based on it. It does not reach:
- **Project Zomboid itself.** The game and its code are not covered.
- **ZombieBuddy.** It stays under its own license.
- **Optimized Mods / Perf modules.** If there's optimized code from a different mod that re-implements some logic, it's up to the original mod author to decide what they allow or not allow to do with their code.
- **Other mods installed alongside Butter Frames**, for example in a modpack. They stay under their own licenses, unless they copy or adapt Butter Frames's code.

As a copyright holder, I grant permission to combine Butter Frames with, and run it inside, all three of the above.

Credit is appreciated but not required beyond what the GPL asks.
