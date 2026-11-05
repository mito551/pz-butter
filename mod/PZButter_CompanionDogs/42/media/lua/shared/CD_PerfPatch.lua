--[[
    CD_Perf: load-after performance patch for Companion Dogs.

    Companion Dogs routes its calls through a built-in profiler (CD.pz,
    CD.evAdd, CD.perfCount ...). Boot.lua defines zero-cost stand-ins for all
    of them and Perf.lua then replaces those with the measuring versions.
    This file restores the stand-ins, and pins the measurement off so a save
    that had it switched on cannot bring the overhead back.

    Load order: shared files of every mod load before any client or server
    file, so this runs after CompanionDogs' shared/Perf.lua and before the
    client and server files that register the handlers and call CD.evAdd.

      N1  PIN_PERF_OFF       CD.PERF_DEBUG / PERF_CSV stay false whatever the
                             save, the admin commands or the Kennel UI say
      N2  evAdd              plain Event.Add, no per-handler wrapper closure
      N3  pz/pzd/perf*       straight passthroughs / no-ops
      U1  MERGE_HANDLERS     every CD.evAdd on OnTick (and OnPlayerUpdate)
                             shares ONE engine listener, so each tick crosses
                             the Java -> Lua boundary once instead of ~30 times

    To profile again: set PIN_PERF_OFF = false (and MERGE_HANDLERS = false to
    get the original per-handler zones back), or disable this mod.
]]

local cfg = rawget(_G, "CD_PerfPatchConfig") or {}
local PIN_PERF_OFF   = cfg.pinPerfOff ~= false
local MERGE_HANDLERS = cfg.mergeHandlers ~= false

pcall(require, "CompanionDogs/Perf")
pcall(require, "CompanionDogs/PerfReport")
pcall(require, "CompanionDogs/config/Tuning")

CompanionDogs = CompanionDogs or {}
local CD = CompanionDogs

if CD._perfPatchLoaded then return end
CD._perfPatchLoaded = true

local MAX_LOGGED_ERRORS = 3

---------------------------------------------------------------------------
-- N1 + N3: measurement pinned off, instrumentation reduced to passthroughs
---------------------------------------------------------------------------

if PIN_PERF_OFF then
    local function pinOff()
        CD.PERF_DEBUG = false
        CD.PERF_CSV = false
        CD._perfLv = 0
    end
    pinOff()

    -- the tune store of an old save, or an admin command, may say "on"
    local origApplyTuning = CD.applyTuning
    if type(origApplyTuning) == "function" then
        CD.applyTuning = function(tbl)
            origApplyTuning(tbl)
            pinOff()
        end
    end
    CD.perfApplyLevel = function() pinOff() return 0 end
    CD.perfCsvArm = function(on)
        pinOff()
        if on and CD.log then CD.log("[CD_Perf] measurement is pinned off by CD_Perf; CSV not armed") end
    end
    CD.perfCalibrate = function() end

    -- six arguments like the original, so call sites do not allocate closures
    CD.pz = function(_k, fn, a, b, c, d, e, f) return fn(a, b, c, d, e, f) end
    CD.pzd = CD.pz
    CD.pzWrap = function(_k, fn) return fn end
    CD.perfEnter = function() end
    CD.perfExit = function() end
    CD.perfCount = function() end
    CD.perfGauge = function() end
    CD.perfIn = function() return nil end
    CD.perfIn2 = function() return nil end
    CD.perfOut = function() end
    CD.perfOut2 = function() end
    CD.perfSample = function() end
end

---------------------------------------------------------------------------
-- N2 + U1: handler registration
---------------------------------------------------------------------------

local merged = {}

local function mergedListener(ev)
    local m = merged[ev]
    if m then return m end
    m = { fns = {}, keys = {}, errors = {}, n = 0 }
    merged[ev] = m
    local fns, keys, errors = m.fns, m.keys, m.errors
    ev.Add(function(a, b, c, d)
        -- the engine isolates each listener's errors; keep that
        for i = 1, m.n do
            local ok, err = pcall(fns[i], a, b, c, d)
            if not ok then
                local count = (errors[i] or 0) + 1
                errors[i] = count
                -- the first few in full, then a reminder every 1000 so a broken handler stays visible
                if count <= MAX_LOGGED_ERRORS or count % 1000 == 0 then
                    print("[CD_Perf] handler '" .. tostring(keys[i]) .. "' raised (" .. count .. "x): " .. tostring(err))
                end
            end
        end
    end)
    return m
end

if PIN_PERF_OFF then
    CD.evAdd = function(ev, fn, key)
        if MERGE_HANDLERS and (ev == Events.OnTick or ev == Events.OnPlayerUpdate) then
            local m = mergedListener(ev)
            local n = m.n + 1
            m.fns[n], m.keys[n] = fn, key
            m.n = n
        else
            ev.Add(fn)
        end
    end
end

---------------------------------------------------------------------------
-- after every Companion Dogs file has loaded: drop the meter's own
-- listeners, which are no-ops while the measurement is off
---------------------------------------------------------------------------

local finished = false
local function finish()
    if finished then return end
    finished = true
    if not PIN_PERF_OFF then return end
    local pinned = { CD.perfTickEnd, CD.perfSampleClient }
    for _, fn in ipairs(pinned) do
        -- registered once per side (client and server file) in single player
        for _ = 1, 3 do
            pcall(Events.OnTick.Remove, fn)
        end
    end
    CD.PERF_DEBUG = false
    CD._perfLv = 0
end

if Events.OnGameStart then Events.OnGameStart.Add(finish) end
if Events.OnServerStarted then Events.OnServerStarted.Add(finish) end

---------------------------------------------------------------------------
-- M1: CD.data and CD.ownerKey answered once per frame per dog / player.
-- Single player and server only: a multiplayer client can have a dog's ModData
-- table replaced by a packet at any time. The frame counter runs on
-- OnTickEvenPaused, so it also advances while the game is paused.
--
-- Why the memo stays correct where Companion Dogs replaces the subtable
-- (getModData().CompanionDogs = ...), all under server/:
--   Carry.lua:256, 518, 570; Stash.lua:66, 140; Kennel.lua:96 - each assigns into
--   the dog's own ModData table, which is the table the memo holds, so the next
--   CD.data reads the new subtable from it in the same frame.
-- CD.data's body is the original's minus the profiler counter (pinned off above).
---------------------------------------------------------------------------

if cfg.dataMemo ~= false then
    local origOwnerKey = CD.ownerKey
    local frame, memoOn = 0, false
    local mdOf, dataFrame = {}, {}       -- dog -> its ModData table, frame it was read
    local keyOf, keyFrame = {}, {}       -- player -> owner key, frame it was computed
    local PRUNE_FRAMES = 600

    Events.OnTickEvenPaused.Add(function()
        if frame == 0 then memoOn = not isClient() end
        frame = frame + 1
        if frame % PRUNE_FRAMES == 0 then
            for a, f in pairs(dataFrame) do
                if frame - f > PRUNE_FRAMES then dataFrame[a], mdOf[a] = nil, nil end
            end
            for pl, f in pairs(keyFrame) do
                if frame - f > PRUNE_FRAMES then keyFrame[pl], keyOf[pl] = nil, nil end
            end
        end
    end)

    function CD.data(animal)
        local md
        if memoOn and dataFrame[animal] == frame then
            md = mdOf[animal]
        else
            md = animal:getModData()
            if memoOn then mdOf[animal], dataFrame[animal] = md, frame end
        end
        md.CompanionDogs = md.CompanionDogs or {}
        return md.CompanionDogs
    end

    function CD.ownerKey(player)
        if not memoOn or player == nil then return origOwnerKey(player) end
        if keyFrame[player] == frame then return keyOf[player] end
        local key = origOwnerKey(player)
        keyOf[player], keyFrame[player] = key, frame
        return key
    end
end
