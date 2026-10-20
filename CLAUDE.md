<!-- claude-reflect: learned rules (saved from the band; edit freely) -->
## Learned rules
- Implement features only from SPEC.md and the game's bytecode; never read or copy pzopt source code (clean-room requirement).
- Do not launch the game for smoke runs without asking first; only run it when the user confirms the PC is unattended.
<!-- end claude-reflect -->

## Low-probability variants (hot path)

Standing request from the user: after writing hot-path code, have subagents re-implement it from deliberately unlikely approaches, benchmark everything, and keep the winner.

- **Scope:** hot-path code only, meaning anything that runs every frame or tick or that the spec names as a cost. Skip glue, settings, build scripts and one-liners.
- **When:** after your own implementation passes its tests.
- **Your estimate:** state how likely your own approach was, as a percentile of what you would normally write (say p = 7%). The percentile is a steering device, not a measurement; neither you nor the subagent can read real token probabilities.
- **Variants:** spawn two subagents per unit:
  - one aiming at about p/3 (7% → 2%, 12% → 4%);
  - one aiming at about p/10 (7% → 0.5%, 4% → 0.1%).
- **Models:** Haiku for a single method, Sonnet for a class or a whole feature.
- **What the subagent gets:** the spec excerpt, the exact interface, the tests, its target percentile, and the project constraints:
  - clean room: SPEC.md and the game's bytecode only, never pzopt source;
  - hooks never throw;
  - `--release 25`, stdlib only.
  - It gets nothing about your implementation: no code and no description.
- **How the subagent aims low:**
  1. List the approaches it would normally write, most likely first.
  2. Drop every approach above its target percentile.
  3. Implement one at the target that still meets the spec.
  4. Report the tier it hit and why.
- **Isolation:** each variant works in its own git worktree and must pass the same tests as yours.
- **Benchmark:**
  - Methods and classes: one shared microbenchmark (warm-up, several runs, median).
  - Features: the in-game frame log, under the game-launch rule above.
- **Decision:** a variant replaces yours only if it passes every test, beats your median by at least 5%, and does so again on a repeat run. Otherwise yours stays.
- **Record:** write the variants, their tiers, the numbers and the decision in the plan's progress ledger.
