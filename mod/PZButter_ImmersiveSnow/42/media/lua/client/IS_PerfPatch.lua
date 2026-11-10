--[[
    IS_Perf: load-after patch for ImmersiveSnow. Currently changes nothing.

    It used to move every ISnow.listen handler of OnTick, OnTickEvenPaused, OnPlayerUpdate,
    OnPostRender and RenderOpaqueObjectsInWorld behind one listener per event, calling each
    under pcall. Measured against the game's own Kahlua (42.21, 27 trivial handlers, see
    mod/PZButter_CompanionDogs/tools/bench/BenchMerge.java): one engine call per handler
    costs about 5.5 us per event, the merged listener about 12 us. The interpreter's
    Lua-to-Lua call is dearer than the engine's Java-to-Lua call, so merging was a loss; it
    also moved the handlers to the position of the first one. It was removed.

    A real gain here needs in-game numbers first: ImmersiveSnow's own ISnow.bench(n, true)
    prints microseconds per handler and per tick, by module.
]]
