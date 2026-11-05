-- Plan 17 Task 3: PZButter_TsarsLib (poll) and PZButter_DamnLib (square index), fix mods.
local here = debug.getinfo(1, "S").source:sub(2)
local root = here:gsub("[/\\]?test/lua/tsar_damn_test.lua$", "")
if root == "" then root = "." end

dofile(root .. "/test/lua/stubs.lua")
dofile(root .. "/mod/PZButter/42/media/lua/shared/PZButter/HandlerCensus.lua")

local now = 0
getTimestampMs = function() return now end

-- damnlib fake: source names decide whether the wrapper is allowed.
local checks, adds = 0, 0
local byLocation = { ["10_20"] = {} }
local function chain(...) return ... end
local function checkSquare(self, square)
    checks = checks + 1
    local cfg = self.byLocation[tostring(square:getX()) .. "_" .. tostring(square:getY())]
    if cfg == nil then return nil end
    return false
end
local function addInternal(self, script, x, y, options)
    adds = adds + 1
    self.byLocation[tostring(x) .. "_" .. tostring(y)] = {}
end
DAMN = { Spawns = { byLocation = byLocation, checkSquare = checkSquare, addInternal = addInternal } }
Stubs.setSource(checkSquare, "/mods/modA/DAMN_Spawns.lua", 12)
Stubs.setSource(addInternal, "/mods/modA/DAMN_Spawns.lua", 30)

local originalCheck, originalAdd = DAMN.Spawns.checkSquare, DAMN.Spawns.addInternal
-- damnStandalone: no PZButter global, and the fix mod still loads and installs
local savedPZButter = PZButter
PZButter = nil
dofile(root .. "/mod/PZButter_DamnLib/42/media/lua/shared/PZButter_DamnLib/Damn.lua")
local Damn = PZButterDamnLib
assert(Damn and Damn.install, "Damn.lua did not load without PZButter")

-- Foreign source: refuse, leave both functions alone.
Stubs.setSource(checkSquare, "/mods/modA/other.lua", 1)
assert(Damn.install() == false)
assert(DAMN.Spawns.checkSquare == originalCheck and DAMN.Spawns.addInternal == originalAdd)

-- A bad key refuses the whole index and leaves the function alone.
Stubs.setSource(checkSquare, "/mods/modA/DAMN_Spawns.lua", 12)
DAMN.Spawns.byLocation = { ["nope"] = {} }
assert(Damn.install() == false)
assert(DAMN.Spawns.checkSquare == originalCheck)
DAMN.Spawns.byLocation = byLocation

-- Correct source: install and index.
assert(Damn.install() == true)
assert(DAMN.Spawns.checkSquare ~= originalCheck and DAMN.Spawns.addInternal ~= originalAdd)

local hit = { getX = function() return 10 end, getY = function() return 20 end }
assert(Damn.check(DAMN.Spawns, hit) == false)
assert(checks == 1, "hit calls original")
local miss = { getX = function() return 1 end, getY = function() return 2 end }
assert(Damn.check(DAMN.Spawns, miss) == true)
assert(checks == 1, "miss did not call original")

-- An added location marks the index stale and is a hit on the next check.
DAMN.Spawns:addInternal("script", 30, 40, nil)
assert(adds == 1)
assert(Damn.check(DAMN.Spawns, { getX = function() return 30 end, getY = function() return 40 end }) == false)
assert(checks == 2, "stale index rebuilt after add")
PZButter = savedPZButter

-- tsarRoutedOnce: Tsar.lua loads before the census finishes (as in the game); it takes the handler over at OnGameStart
local requests = 0
ModData = { request = function(name) requests = requests + 1 end }
local tsarFn = function() end
Stubs.setSource(tsarFn, "/mods/modA/tsarlibModDataRequest.lua", 9)
Events.OnTick.Add(tsarFn)
dofile(root .. "/mod/PZButter_TsarsLib/42/media/lua/client/PZButter_TsarsLib/Tsar.lua")
assert(Stubs.count("OnTick", tsarFn) == 1, "Tsar.lua took the handler over before OnGameStart")
for _, cb in ipairs(Events.OnGameStart.callbacks) do cb() end
local tsar = PZButter.Tsar
tsar.now = function() return now end
assert(Stubs.count("OnTick", tsarFn) == 0, "original tsar handler was not routed away at OnGameStart")
assert(tsar.minutes == 5, "the interval is a constant 5 min")

for _, fn in ipairs(Events.OnTick.callbacks) do fn() end
assert(requests == 1, "first tick requests")
for _, fn in ipairs(Events.OnTick.callbacks) do fn() end
assert(requests == 1, "inside the interval no request")
now = now + 5 * 60000 - 1
for _, fn in ipairs(Events.OnTick.callbacks) do fn() end
assert(requests == 1, "just before the interval no request")
now = now + 1
for _, fn in ipairs(Events.OnTick.callbacks) do fn() end
assert(requests == 2, "interval elapsed: request")

Stubs.realPrint("tsar_damn_test: ok")
