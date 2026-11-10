-- Stub test for mod/PZButter_ProjectALifeNPCs: run with `luajit test/lua/alife_patch_test.lua`.
local function ev() local e = { fns = {} }
    function e.Add(f) e.fns[#e.fns + 1] = f end
    function e.Remove(f) for i, g in ipairs(e.fns) do if g == f then table.remove(e.fns, i) return end end end
    return e end
Events = { OnTick = ev(), OnZombieUpdate = ev(), OnGameStart = ev(), OnServerStarted = ev() }
local calls = 0
local S = { records = {} }
function S.guardCollision(npc) calls = calls + 1 end
Events.OnZombieUpdate.Add(S.guardCollision)
local H = { byName = {}, adds = {} }
function H.add(event, name, fn, opts) H.byName[event .. ":" .. name] = { fn = fn, every = opts.every or 1 }; H.adds[name] = opts.every end
H.byName["OnTick:ALifeCombatKnee"] = { fn = function() end, every = 1 }
local skinTick = function() end
Events.OnTick.Add(skinTick)
ProjectALife = { DrivingSeats = S, Handlers = H, CorpseSkin = { onTick = skinTick } }
print = function() end
dofile("mod/PZButter_ProjectALifeNPCs/42/media/lua/shared/PZButter_ALife.lua")
Events.OnGameStart.fns[1]()
assert(PZB_ALIFE.applied.seatGuardSkip and not PZB_ALIFE.applied.gateEvery and not PZB_ALIFE.applied.corpseSkinEvery)
assert(#Events.OnZombieUpdate.fns == 1 and Events.OnZombieUpdate.fns[1] == S.guardCollision)
local bare = { hasModData = function() return false end }
local owned = { hasModData = function() return true end }
local rec = { hasModData = function() return false end }
S.records[rec] = true
S.guardCollision(bare); assert(calls == 0, "vanilla zombie must not reach collisionSeat")
S.guardCollision(owned); assert(calls == 1)
S.guardCollision(rec); assert(calls == 2, "seated record must still run")
assert(H.adds.ALifeCombatKnee == nil and #Events.OnTick.fns == 1 and Events.OnTick.fns[1] == skinTick)
print = io.write; print("ok\n")
