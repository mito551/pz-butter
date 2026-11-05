--[[
    ISH_Perf: load-after performance patch for Sunflower's Ultimate Character
    Overhaul (mod id SunflowersUltimateCharacterOverhaul, Lua namespace ISH).

    ISH.applyVisual used to run every tick for every local player and, in
    OnTick, for every loaded survivor. Each call allocated a ~110 key body
    table and made ~118 setVariable calls into Java, to write values that
    almost never change. This replaces it with the same result for far less:

      N1  a variable is written only when its value differs from what was last
          written to that player
      N2  nothing is allocated on the unchanged path; the body values are
          recomputed (with the mod's own ISH.getBody) only when the body table
          is a different table than last time, which is how the mod changes it
      N3  options are read as plain table lookups into a small signature; the
          derived values (jiggle, height stats) are recomputed by the mod's own
          functions only when the signature changes
      N4  the engine-reset check below runs at most once per tick per player
      U1  one getVariableFloat("ISH_VisualScale") read per tick is the canary
          that the variables are still on the character. If the engine ever
          clears them, the next tick rewrites everything. Everything is also
          rewritten every 3 seconds regardless.
      R1  survivors are tracked from OnCreateLivingCharacter / death events
          instead of walking every zombie in the cell four times a second; a
          sliced reconcile scan every 10 s catches whatever an event missed
      R2  (single player / host only) the mod's own OnPlayerUpdate and OnTick
          listeners are replaced by one OnTick listener: local players every
          tick, other survivors round-robin over 4 ticks
      G1  frame-time governor: when the tick rate stays low, hair physics,
          accessory fitting and jiggle are switched off in steps, and back on
          once it has been above governorHighFps for a while. It ignores the
          first 30 s of a game (chunk loading); a step back down that is soon
          undone doubles the time needed for the next one (up to 2 min)
      L1  distance LOD: survivors far from every local player get hair,
          accessories and jiggle switched off through the mod's own variables

    Config (define the global ISH_PerfPatchConfig before this file loads):
      disabled, takeOver (R2), governor (G1), lod (L1) = false to switch off;
      governorLowFps, governorHighFps, governorLowSeconds, governorHighSeconds,
      lodTiles to tune.
]]

local cfg = rawget(_G, "ISH_PerfPatchConfig") or {}

pcall(require, "ISH/Config")
pcall(require, "ISH/Client")

if cfg.disabled or type(ISH) ~= "table" or type(ISH.applyVisual) ~= "function" then return end
if ISH.PerfPatched then return end
ISH.PerfPatched = true

local FULL_REFRESH_TICKS = 180      -- rewrite everything every ~3 s; the canary covers engine resets
local CANARY_EPSILON = 1e-4
local RECONCILE_MS = 10000          -- full scan to catch what events missed
local SCAN_SLICE = 300              -- objects looked at per tick while scanning
local STAGGER_TICKS = 4             -- non-local survivors are refreshed this often
local OTHERS_REFRESH_TICKS = 30

local TAKE_OVER   = cfg.takeOver ~= false
local USE_GOV     = cfg.governor ~= false
local USE_LOD     = cfg.lod ~= false
local GOV_LOW_FPS   = cfg.governorLowFps or 24
local GOV_HIGH_FPS  = cfg.governorHighFps or 28   -- under a 30 fps cap, so capped machines recover
local GOV_LOW_SECS  = cfg.governorLowSeconds or 5
local GOV_HIGH_SECS = cfg.governorHighSeconds or 10
local GOV_MAX_TIER  = 4
local GOV_WARMUP_MS = 30000
local GOV_FLAP_MS   = 60000         -- a step up this soon after a step down counts as a flap
local GOV_MAX_HIGH_SECS = 120
local STATE_TTL_TICKS = 600         -- per-player state not touched for this long is dropped
local LOD_TILES_SQ  = (cfg.lodTiles or 48) ^ 2
local LOD_PERIOD    = 30

local origLoadedSurvivors = ISH.loadedSurvivors
local origOption = ISH.option
local origJiggleEnabled = ISH.jiggleEnabled

local frame = 0
local states = setmetatable({}, { __mode = "k" })
local canaryUsable = nil            -- nil = not probed yet

---------------------------------------------------------------------------
-- G1: quality tier, applied by wrapping the mod's own option readers
---------------------------------------------------------------------------

local gov = { tier = 0, last = nil, acc = 0, n = 0, windowStart = 0, low = 0, high = 0,
              startAt = nil, downAt = nil, highSecs = GOV_HIGH_SECS }

function ISH.option(name, fallback)
    local tier = gov.tier
    if tier > 0 then
        if name == "EnableHairPhysics" then return false end
        if tier >= 2 and name == "FitAccessories" then return false end
        if tier >= 4 and name == "EnableJiggle" then return false end
    end
    return origOption(name, fallback)
end

function ISH.jiggleEnabled(actor)
    if gov.tier >= 3 and actor and not actor:isLocalPlayer() then return false end
    return origJiggleEnabled(actor)
end

local function sampleFrame(now)
    if not USE_GOV then return end
    local last = gov.last
    gov.last = now
    if last == nil then gov.windowStart = now; return end
    if gov.startAt == nil then gov.startAt = now end
    if now - gov.startAt < GOV_WARMUP_MS then gov.windowStart = now; return end
    local dt = now - last
    if dt <= 0 or dt > 500 then         -- pause, alt-tab or a one-off hitch: start over
        gov.windowStart, gov.acc, gov.n = now, 0, 0
        return
    end
    gov.acc, gov.n = gov.acc + dt, gov.n + 1
    if now - gov.windowStart < 1000 then return end
    local fps = 1000 * gov.n / gov.acc
    gov.windowStart, gov.acc, gov.n = now, 0, 0
    if fps < GOV_LOW_FPS then
        gov.low, gov.high = gov.low + 1, 0
        if gov.low >= GOV_LOW_SECS and gov.tier < GOV_MAX_TIER then
            gov.tier, gov.low = gov.tier + 1, 0
            if gov.downAt and now - gov.downAt < GOV_FLAP_MS then
                gov.highSecs = math.min(gov.highSecs * 2, GOV_MAX_HIGH_SECS)
            end
            print("[ISH_Perf] tick rate " .. math.floor(fps) .. " fps: quality tier " .. gov.tier)
        end
    elseif fps > GOV_HIGH_FPS then
        gov.high, gov.low = gov.high + 1, 0
        if gov.high >= gov.highSecs and gov.tier > 0 then
            gov.tier, gov.high, gov.downAt = gov.tier - 1, 0, now
            print("[ISH_Perf] tick rate " .. math.floor(fps) .. " fps: quality tier " .. gov.tier)
        end
    else
        gov.low, gov.high = 0, 0
    end
end

---------------------------------------------------------------------------
-- L1: is this survivor far from every local player?
---------------------------------------------------------------------------

local function computeFar(player)
    if player:isLocalPlayer() then return false end
    local px, py = player:getX(), player:getY()
    local sawLocal = false
    for i = 0, getNumActivePlayers() - 1 do
        local lp = getSpecificPlayer(i)
        if lp and not lp:isDead() then
            sawLocal = true
            local dx, dy = lp:getX() - px, lp:getY() - py
            if dx * dx + dy * dy <= LOD_TILES_SQ then return false end
        end
    end
    return sawLocal
end

---------------------------------------------------------------------------
-- applyVisual
---------------------------------------------------------------------------

local function stateFor(player)
    local st = states[player]
    if st == nil then
        st = { written = {}, partValues = {}, full = -FULL_REFRESH_TICKS, canaryFrame = -1,
               lodFrame = -LOD_PERIOD, far = false }
        states[player] = st
    end
    st.seen = frame
    return st
end

local function put(player, st, force, name, value)
    if force or st.written[name] ~= value then
        player:setVariable(name, value)
        st.written[name] = value
    end
end

local function option(name, fallback)
    return ISH.option(name, fallback)
end

--- True when the variables are no longer on the character (engine reset).
local function canaryFailed(player, st)
    if canaryUsable == false or st.canaryFrame == frame or st.scale == nil then return false end
    st.canaryFrame = frame
    if canaryUsable == nil then
        local ok = pcall(player.getVariableFloat, player, "ISH_VisualScale", -1)
        canaryUsable = ok
        if not ok then return false end
    end
    local value = player:getVariableFloat("ISH_VisualScale", -1)
    return math.abs(value - st.scale) > CANARY_EPSILON
end

function ISH.applyVisual(player)
    if not player or player:isDead() then return end
    local st = stateFor(player)

    if USE_LOD and frame - st.lodFrame >= LOD_PERIOD then
        st.lodFrame = frame
        st.far = computeFar(player)
    end
    local far, tier = st.far, gov.tier

    -- cheap signature of every input the original read
    local preview = ISH.preview[player]
    local percent = preview or ISH.getPercent(player)
    local bodyPreview = ISH.bodyPreview[player]
    local bodyRef = bodyPreview or player:getModData()[ISH.BODY_DATA]
    local heightOpt = option("HeightAffectsStats", false)
    local jiggleA, jiggleB, jiggleC = option("EnableJiggle", true), option("FemaleJiggle", true), option("MaleJiggle", false)
    local hairOpt = option("EnableHairPhysics", true)
    local accessoriesOpt = option("FitAccessories", true)

    local force = frame - st.full >= FULL_REFRESH_TICKS or canaryFailed(player, st)

    if not force
            and st.percent == percent and st.bodyRef == bodyRef and st.bodyIsPreview == (bodyPreview ~= nil)
            and st.heightOpt == heightOpt and st.jiggleA == jiggleA and st.jiggleB == jiggleB
            and st.jiggleC == jiggleC and st.hairOpt == hairOpt and st.accessoriesOpt == accessoriesOpt
            and st.farApplied == far and st.tierApplied == tier then
        return
    end

    if force then
        st.full = frame
        st.written = {}
        st.bodyRef = nil            -- recompute the body values too
    end

    if force or st.heightOpt ~= heightOpt then
        st.heightStats = ISH.heightStatsEnabled() and 1 or 0
    end
    if force or st.jiggleA ~= jiggleA or st.jiggleB ~= jiggleB or st.jiggleC ~= jiggleC
            or st.tierApplied ~= tier then
        st.jiggle = ISH.jiggleEnabled(player) and 1 or 0
    end

    put(player, st, force, "ISH_HeightStats", st.heightStats)
    put(player, st, force, "ISH_JiggleEnabled", far and 0 or st.jiggle)
    put(player, st, force, "ISH_HairEnabled", (hairOpt and not far) and 1 or 0)
    put(player, st, force, "ISH_AccessoriesEnabled", (accessoriesOpt and not far) and 1 or 0)
    st.scale = percent / 100
    put(player, st, force, "ISH_VisualScale", st.scale)

    if force or st.bodyRef ~= bodyRef or st.bodyIsPreview ~= (bodyPreview ~= nil) or st.body == nil then
        -- the mod replaces its body tables instead of editing them, so a
        -- different table (or none) is the only way the body can change
        st.body = bodyPreview or ISH.getBody(player)
        local parts = ISH.BODY_PARTS
        local values = st.partValues
        for i = 1, #parts do
            values[i] = st.body[parts[i].key] / 100
        end
    end
    local body = st.body
    put(player, st, force, "ISH_AutoFit", (body.autoFit ~= false) and 1 or 0)
    put(player, st, force, "ISH_SyncBreasts", body.syncBreasts and 1 or 0)
    put(player, st, force, "ISH_SyncGlutes", body.syncGlutes and 1 or 0)
    local parts, values = ISH.BODY_PARTS, st.partValues
    for i = 1, #parts do
        put(player, st, force, parts[i].variable, values[i])
    end

    st.percent, st.bodyRef, st.bodyIsPreview = percent, bodyRef, bodyPreview ~= nil
    st.heightOpt, st.jiggleA, st.jiggleB, st.jiggleC = heightOpt, jiggleA, jiggleB, jiggleC
    st.hairOpt, st.accessoriesOpt = hairOpt, accessoriesOpt
    st.farApplied, st.tierApplied = far, tier
end

---------------------------------------------------------------------------
-- R1: survivor registry from events, reconciled by a sliced scan
---------------------------------------------------------------------------

local registry = setmetatable({}, { __mode = "k" })     -- player -> ms it was seen
local seeded = false
local building, buildSeen, buildStart, cursor = nil, nil, 0, 0
local nextReconcile = 0

local function remember(character)
    if instanceof(character, "IsoPlayer") then registry[character] = getTimestampMs() end
end
local function forget(character)
    registry[character] = nil
end
Events.OnCreateLivingCharacter.Add(remember)
Events.OnCharacterDeath.Add(forget)
Events.OnPlayerDeath.Add(forget)

local function finishReconcile()
    for p, seenAt in pairs(registry) do
        -- an entry added by an event after the scan began is newer than the scan
        if seenAt < buildStart and not buildSeen[p] then registry[p] = nil end
    end
    local now = getTimestampMs()
    for p in pairs(buildSeen) do
        if registry[p] == nil then registry[p] = now end
    end
    building, buildSeen = nil, nil
end

local function reconcileSlice()
    local cell = getCell and getCell()
    if not cell then building, buildSeen = nil, nil; return end
    local objects = cell:getObjectListForLua()
    local size = objects:size()
    if building == nil then
        building, buildSeen, cursor, buildStart = true, {}, 0, getTimestampMs()
    end
    local last = math.min(size, cursor + SCAN_SLICE)
    for i = cursor, last - 1 do
        local p = objects:get(i)
        if instanceof(p, "IsoPlayer") then buildSeen[p] = true end
    end
    cursor = last
    if cursor >= size then finishReconcile() end
end

function ISH.loadedSurvivors()
    if not seeded then
        -- first call: the exact answer, once
        local result = origLoadedSurvivors()
        local now = getTimestampMs()
        for _, p in ipairs(result) do registry[p] = now end
        seeded = true
        return result
    end
    local result, added = {}, {}
    local function add(p)
        if p and not added[p] and not p:isDead() then
            added[p] = true
            result[#result + 1] = p
        end
    end
    for i = 0, getNumActivePlayers() - 1 do add(getSpecificPlayer(i)) end
    for p in pairs(registry) do add(p) end
    return result
end

---------------------------------------------------------------------------
-- R2: one listener instead of the mod's OnPlayerUpdate + OnTick (SP/host)
---------------------------------------------------------------------------

local tookOver = false
local removedListeners = nil           -- kept so they can be given back on the main menu
local others, othersAt, othersCursor = {}, -OTHERS_REFRESH_TICKS, 1

local function isOurs(callback)
    local info = ZombieBuddy.getClosureInfo(callback)
    local file = info and info.filename
    return type(file) == "string" and file:find("ISH/Client.lua", 1, true) ~= nil
end

local function takeOver()
    if tookOver or not TAKE_OVER or isClient() or isServer() then return end
    if type(ZombieBuddy) ~= "table" or type(ZombieBuddy.getClosureInfo) ~= "function"
            or type(ZombieBuddy.Events) ~= "table" then return end
    local found = {}
    for _, name in ipairs({ "OnPlayerUpdate", "OnTick" }) do
        local list = ZombieBuddy.Events.getByName(name)
        local match = {}
        if list then
            for i = 0, list:size() - 1 do
                local callback = list:get(i)
                if isOurs(callback) then match[#match + 1] = callback end
            end
        end
        -- exactly one listener per event, or leave everything as it is
        if #match ~= 1 then return end
        found[name] = match[1]
    end
    Events.OnPlayerUpdate.Remove(found.OnPlayerUpdate)
    Events.OnTick.Remove(found.OnTick)
    removedListeners = found
    tookOver = true
    ISH.loadedSurvivors()               -- seed the registry now
    print("[ISH_Perf] replaced the mod's OnPlayerUpdate/OnTick listeners with one")
end

local function updatePlayers()
    for i = 0, getNumActivePlayers() - 1 do
        local p = getSpecificPlayer(i)
        if p then
            if ISH.consumeCreationDraft then ISH.consumeCreationDraft(p) end
            ISH.applyVisual(p)
        end
    end
    if frame - othersAt >= OTHERS_REFRESH_TICKS then
        othersAt = frame
        local list, locals = {}, {}
        for i = 0, getNumActivePlayers() - 1 do locals[getSpecificPlayer(i)] = true end
        for p in pairs(registry) do
            if not locals[p] and not p:isDead() then list[#list + 1] = p end
        end
        others, othersCursor = list, 1
    end
    local n = #others
    if n == 0 then return end
    local per = math.ceil(n / STAGGER_TICKS)
    for _ = 1, per do
        if othersCursor > n then othersCursor = 1 end
        local p = others[othersCursor]
        othersCursor = othersCursor + 1
        if p and not p:isDead() then ISH.applyVisual(p) end
    end
end

---------------------------------------------------------------------------
-- the one OnTick of this patch
---------------------------------------------------------------------------

Events.OnTick.Add(function()
    frame = frame + 1
    local now = getTimestampMs()
    sampleFrame(now)
    if tookOver then updatePlayers() end
    -- Kahlua may not honour __mode, so players that left (MP) are dropped here
    if frame % STATE_TTL_TICKS == 0 then
        for p, st in pairs(states) do
            if frame - st.seen > STATE_TTL_TICKS then states[p] = nil end
        end
    end
    if isClient() then return end       -- the original only scans the cell off-client
    if building ~= nil then
        reconcileSlice()
    elseif seeded and now >= nextReconcile then
        nextReconcile = now + RECONCILE_MS
        reconcileSlice()
    end
end)

Events.OnGameStart.Add(takeOver)

Events.OnMainMenuEnter.Add(function()
    states = setmetatable({}, { __mode = "k" })
    registry = setmetatable({}, { __mode = "k" })
    seeded, building, buildSeen, cursor = false, nil, nil, 0
    others, othersAt, othersCursor = {}, -OTHERS_REFRESH_TICKS, 1
    gov.tier, gov.low, gov.high, gov.last, gov.acc, gov.n = 0, 0, 0, nil, 0, 0
    gov.startAt, gov.downAt, gov.highSecs = nil, nil, GOV_HIGH_SECS
    -- a multiplayer game may follow a single player one in this session, and
    -- it needs the mod's own listeners back
    if removedListeners then
        Events.OnPlayerUpdate.Add(removedListeners.OnPlayerUpdate)
        Events.OnTick.Add(removedListeners.OnTick)
        removedListeners = nil
    end
    tookOver = false
end)
