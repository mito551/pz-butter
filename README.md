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

The mod creates `~/Zomboid/PZButter.properties` on first start and reads it once at game start. Every key is off by default.

| Key | What it does |
|---|---|
| `enabled` | Master switch. While it is `false`, the mod does nothing and the game runs stock. |
| `frameLog` | Writes one line per frame to `~/Zomboid/pzbutter/frames.out`. |

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
| `tools/merge-luabudget.sh` | Folds the LuaBudget mod's sources into `pzbutter.luabudget`. `--check` tries the merge in a temporary copy and builds it. |

`SPEC.md` describes the optimizations PZ Butter re-implements, and `ANALYSIS-SPEC.md` the analysis work that follows them. The plan is in `docs/superpowers/plans/`.

PZ Butter is a clean-room implementation, written only from those specs and the game's own bytecode. `SPEC.md` documents xD3I's PZ_Optimization (pzopt). PZ Butter contains none of its code.

## License

PZ Butter is free software under the GNU General Public License, version 3 (see `LICENSE`). You may use, change and share it. If you distribute it, or a work based on it, the whole work must be released under the same license, with its source.

The GPL's copyleft covers PZ Butter and works based on it. It does not reach:
- **Project Zomboid itself.** The game and its code are not covered.
- **ZombieBuddy.** It stays under its own license.
- **Other mods installed alongside PZ Butter**, for example in a modpack. They stay under their own licenses, unless they copy or adapt PZ Butter's code.

As a copyright holder, I grant permission to combine PZ Butter with, and run it inside, all three of the above.

Credit is appreciated but not required beyond what the GPL asks.
