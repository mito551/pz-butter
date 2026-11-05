# ETW_Perf benchmark plan

## Goal
Compare Evolving Traits World performance with and without ETW_Perf.

## Setup
1. Single-player game, one test save with active combat near a large horde.
2. Run 1: enable ETW only.
3. Run 2: enable ETW + ETW_Perf (do not edit installed ETW files).
4. Keep all other mods and settings identical.

## Metrics
- Game-thread tick time during active combat.
- Time spent in `ETW_EventsOrchestrator.everyTickUpdate`.
- Nearby-zombie traversal time / calls per frame.
- Network `syncETWModDataToClient` traffic on minute updates (MP only).
- Frame spikes during `oneMinuteUpdate` and `oneHourUpdate`.

## Benchmarks
- 10-minute horde encounter at close range.
- 60-minute idle survival session.
- 10-minute MP server with 4 players.

## Pass criteria
- No functional trait changes compared to ETW baseline.
- No errors in console.
- Tick-time p99 reduction in combat scenario.
- No slower path for normal daily play.

## Item 10: single Java tick callback
All ETW per-tick trait work now runs from one ZombieBuddy patch on `IngameState.onTick` (exit),
`etw.perf.Tick`, which calls Lua only when a Java-side gate says there is work:
- `ETW_EventsOrchestrator` per player: any tick trait, starting-injury snapshots, or Indefatigable protection.
- `ETW_Alcoholic` withdrawal stress, GymRat stiffness decay, client Indefatigable protection: registered only while active.
- `ETW_NearbyZombieScanner`: local player with Indefatigable or Immunocompromised.
- `ETW_Moodles` SleepHealth repair: only while its moodle modData is incomplete.
Without the jar, `ETW_PerfTick` falls back to plain `Events.OnTick` (upstream behavior).

Checks: `tools/java/build.sh`, then
`java -ea -cp tools/java/out:$PZ/projectzomboid.jar tools/test/TickGateCheck.java` and
`luajit tools/test/perftick_check.lua 42/media/lua/shared/ETW_PerfTick.lua`.
In game: idle player with no tick traits should show no ETW entries under "On Tick" in the profiler.
