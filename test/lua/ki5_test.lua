local here = debug.getinfo(1, "S").source:sub(2)
local root = here:gsub("[/\\]?test/lua/ki5_test.lua$", "")
if root == "" then root = "." end
dofile(root .. "/test/lua/stubs.lua")
dofile(root .. "/mod/PZButter/42/media/lua/shared/PZButter/HandlerCensus.lua")
dofile(root .. "/mod/PZButter/42/media/lua/shared/PZButter/Fix.lua")

local calls = {sync = 0, every = 0, unchecked = 0, bad = 0, neighbour = 0}
local syncFn = function(player) calls.sync = calls.sync + 1 end
local everyFn = function(player) calls.every = calls.every + 1 end
local uncheckedFn = function(player) calls.unchecked = calls.unchecked + 1 end
local badFn = function(player) calls.bad = calls.bad + 1; error("boom") end
local neighbourFn = function(player) calls.neighbour = calls.neighbour + 1 end
Stubs.setSource(syncFn, "/mods/modA/sync.lua", 3)  -- the game reports the first instruction, inside the function
Stubs.setSource(everyFn, "/mods/modA/every.lua", 1)
Stubs.setSource(uncheckedFn, "/mods/modA/other.lua", 1)
Stubs.setSource(badFn, "/mods/modA/bad.lua", 1)
Stubs.setSource(neighbourFn, "/mods/modA/neighbour.lua", 1)
Events.OnPlayerUpdate.Add(syncFn)
Events.OnPlayerUpdate.Add(everyFn)
Events.OnPlayerUpdate.Add(uncheckedFn)
Events.OnPlayerUpdate.Add(badFn)
Events.OnPlayerUpdate.Add(neighbourFn)

PZButter.ki5Bundle = true
PZButter.ki5SyncMs = 100
local now = 0
dofile(root .. "/mod/PZButter/42/media/lua/client/PZButter/Ki5.lua")
-- the game loads Ki5.lua before ki5table.lua ("K" < "k") and before the census finishes
PZButter.Ki5Table = {
    {id = "modA:sync.lua:1", name = "Sync", kind = "sync", types = {"carA"}, onFoot = "none", last = 4},
    {id = "modA:every.lua:1", name = "Every", kind = "every", types = {}, onFoot = "unchecked"},
    {id = "modA:other.lua:1", name = "Other", kind = "unchecked", types = {}, onFoot = "unchecked"},
    {id = "modA:bad.lua:1", name = "Bad", kind = "every", types = {}, onFoot = "unchecked"},
    {id = "modA:neighbour.lua:1", name = "Neighbour", kind = "every", types = {}, onFoot = "unchecked"},
}
-- the game loads Ki5.lua before the census finishes; the takeover happens at OnGameStart
assert(Stubs.count("OnPlayerUpdate", syncFn) == 1, "Ki5 took handlers over before OnGameStart")
for _, cb in ipairs(Events.OnGameStart.callbacks) do cb() end
assert(Stubs.count("OnPlayerUpdate", syncFn) == 0, "Ki5 did not take the sync handler over at OnGameStart")
Ki5 = PZButter.Ki5
Ki5.now = function() return now end

local function vehicle(t, seat)
    return {seat = seat, getScriptName = function() return t end,
            getSeat = function(self, p) return self.seat end}
end
local carA = vehicle("carA", 0)
local foot = {getVehicle = function() return nil end}
local inCar = {getVehicle = function() return carA end}

Ki5.dispatch(foot)
assert(calls.sync == 0, "sync handler ran on foot")
assert(calls.every == 1 and calls.unchecked == 1 and calls.neighbour == 1, "on-foot skip was too broad")

Ki5.dispatch(inCar)
assert(calls.sync == 1, "sync handler did not run in car")
now = 50; Ki5.dispatch(inCar)
assert(calls.sync == 1, "sync handler ran again within 100 ms")
now = 100; Ki5.dispatch(inCar)
assert(calls.sync == 2, "sync handler did not run at 100 ms")

carA.seat = 1
now = 105; Ki5.dispatch(inCar)
assert(calls.sync == 3, "sync handler did not run immediately on a seat change")

local carB = vehicle("carB", 0)
local other = {getVehicle = function() return carB end}
now = 120; Ki5.dispatch(other)
assert(calls.sync == 3, "sync handler ran for a non-matching type")

local carC = vehicle("carC", 0)
local inCarC = {getVehicle = function() return carC end}
local badBefore = calls.bad
now = 200; Ki5.dispatch(inCarC)
assert(calls.bad == badBefore + 1, "bad handler did not run")
now = 700; Ki5.dispatch(inCarC)
assert(calls.bad == badBefore + 1, "bad handler ran inside its 1 s pause")
now = 1200; Ki5.dispatch(inCarC)
assert(calls.bad == badBefore + 2, "bad handler did not retry after 1 s")
now = 6000; Ki5.dispatch(inCarC)
assert(calls.bad == badBefore + 2, "bad handler ran inside its 5 s pause")
now = 6200; Ki5.dispatch(inCarC)
assert(calls.bad == badBefore + 3, "bad handler did not retry after 5 s")
now = 9000; Ki5.dispatch(inCarC)
assert(calls.bad == badBefore + 3, "bad handler ran inside its 30 s pause")
now = 36200; Ki5.dispatch(inCarC)
assert(calls.bad == badBefore + 4, "bad handler did not retry after 30 s")

Stubs.realPrint("ki5_test: ok")
