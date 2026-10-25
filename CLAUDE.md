<!-- claude-reflect: learned rules (saved from the band; edit freely) -->
## Learned rules
- Implement features only from SPEC.md and the game's bytecode; never read or copy pzopt source code (clean-room requirement).
- Do not launch the game for smoke runs without asking first; only run it when the user confirms the PC is unattended.
- In the README, list every mod this project covers or is inspired by, and use the words "inspire" and "cover", not "(re-)implement".
<!-- end claude-reflect -->

## Low-probability variants (hot path)

Standing request from the user: after writing hot-path code, have subagents re-implement it from deliberately unlikely choices, benchmark everything, and keep the winner. Performance often hinges on details, not the overall design, and it is hard to tell which way your own solution leans; that is what this rule is for.

- **Scope:** hot-path code only, meaning anything that runs every frame or tick or that the spec names as a cost. Skip glue, settings, build scripts and one-liners.
- **When:** after your own implementation passes its tests.
- **Percentiles:** a steering device, not a measurement. Neither you nor a subagent can read real token probabilities.

### 1. Decision inventory (you, before spawning)

List every point in the unit where it could reasonably be written more than one way, down to the details: time source, number formatting, buffer type, flush trigger, loop shape, data layout. Mark each point:

- **Settled:** there is one correct way (`n % 2` for odd or even, not a lookup table), or the spec fixes it. Variants get settled points as fixed requirements.
- **Open:** several viable ways exist. Write down your own choice and its percentile p (how often you would write it).

If no point is open, skip the round and say why in the progress ledger.

### 2. Two variant subagents

- **Targets, per open point:** one subagent aims every open point at about p/3 (7% → 2%, 12% → 4%), the other at about p/10 (7% → 0.5%, 4% → 0.1%). Each point uses your p for that point.
- **Models:** Haiku for a single method, Sonnet for a class or a whole feature.
- **What each subagent gets:**
  - the spec excerpt, the exact interface, and the tests;
  - the settled points as requirements;
  - the open points, each with its target percentile;
  - the benchmark and the win condition (section 3);
  - the project constraints: clean room (SPEC.md and the game's bytecode only, never pzopt source), hooks never throw, `--release 25`, stdlib only.
- **What each subagent does not get:** anything about your implementation, including your choices on the open points. Its worktree has your code replaced by an interface stub and any plan text that describes your approach removed. It must not read git history from before that base.
- **What each subagent must do:**
  1. For each open point, rank the options it would normally write with their percentiles, and pick one near its target. If no viable option is that rare, take the lowest viable one and say so.
  2. Commit that list as `VARIANT.md` before writing any code.
  3. Implement, pass the tests, run the benchmark, and commit on its own branch.
  4. Report its choice and percentile for each open point, the test result, the benchmark output verbatim, and the commit hashes.

### 3. Benchmark and decision

- **Benchmark:** fix the scenarios before spawning.
  - Methods and classes: one shared microbenchmark (warm-up, several runs, median), run for every scenario that matters (for example, log off and log on).
  - Features: the in-game frame log, under the game-launch rule above.
- **Who measures:** you run the final numbers, one version at a time, never while subagents are running.
- **Win condition:** a variant replaces yours only if:
  - it passes every test;
  - it beats your median by at least 5% in at least one scenario, on two consecutive rounds;
  - it is not slower in any other scenario;
  - its output is identical to yours wherever the spec fixes the output (for example, log lines).

  Otherwise yours stays.

### 4. Checks and record

- **Verify the work:**
  - `VARIANT.md` must be committed before the code.
  - If a report is empty or says "placeholder", inspect the branch yourself and ask the subagent again.
- **Is it really a variant:** compare each variant's open-point choices with yours. A variant that matches yours on every open point is not a variant; drop it and note that.
- **Record:** write in the plan's progress ledger:
  - the inventory;
  - each variant's choices and percentiles;
  - the numbers;
  - the decision.

  Then delete the variant worktrees and branches.

<!-- rtk-instructions v2 -->
# Command output

Command output here is condensed to save tokens, keeping every signal and
dropping costly noise. Treat it as the complete result: run commands
normally, and batch related commands into one call to avoid extra turns.
Truncated results state their recovery path in their own output. Re-run a
command as `rtk bypass <cmd>` only when its result is unusable: empty when
output was clearly expected, contradicting its exit code, or garbled.
<!-- /rtk-instructions -->