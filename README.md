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
