-- usage: lua tools/test/governor_test.lua 42/media/lua/client/ISH_PerfPatch.lua
local ticks, T = {}, 0
local function ev() return { Add = function(f) ticks[#ticks+1] = f end, Remove = function() end } end
Events = setmetatable({}, { __index = function(t, k) local e = { Add = function(f) if k == "OnTick" then ticks[#ticks+1] = f end end, Remove = function() end }; rawset(t, k, e); return e end })
ISH = { applyVisual = function() end, option = function() return true end, jiggleEnabled = function() return true end, loadedSurvivors = function() return {} end }
getTimestampMs = function() return T end
isClient = function() return false end; isServer = function() return false end
getNumActivePlayers = function() return 0 end; getCell = function() return nil end
instanceof = function() return false end
dofile(arg[1])
local function run(fps, secs) local dt = 1000 / fps; for _ = 1, math.floor(secs * fps) do T = T + dt; for _, f in ipairs(ticks) do f() end end end
local function hair() return ISH.option("EnableHairPhysics") end
run(15, 20); assert(hair(), "warm-up must ignore load stutter")
run(60, 15)
run(20, 7); assert(not hair(), "low fps must step quality down")
run(30, 12); assert(hair(), "a 30 fps cap must recover")
print("governor OK")
