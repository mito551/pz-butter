-- Viewpoint: Living Props - fireplace logs and fire in 3D (client side only, visual only).
--
-- The game adds the log pile (and the flame) to a fireplace as extra "attached" images while running.
-- The log image is created WITHOUT a name, so a model pack has nothing to bind to. This script gives that
-- image a name that depends on the fireplace state; the pack binds each name to a 3D model:
--     "lp_logs_<fireplace sprite>"      -> unlit pile of logs
--     "lp_fire_a/b/c_<fireplace sprite>" -> burning logs with flames (3 flame shapes that alternate = flicker)
-- It does not change anything in the world or the save, and nothing is sent to a server.

-- Time (milliseconds) between two flame shapes. Set to 0 for a still flame (lightest on performance).
local P = ViewpointLivingPropsPerf or { tag = function() end, fn = function(_, f) return f end, pcall = function(_, f, ...) return pcall(f, ...) end, count = function() end, gauge = function() end }
P.tag("Fireplace")

local FLICKER_MS = 350   -- (0 = still flame). The flame only starts to flicker once all its pictures are loaded, so it does not blink
-- Only fireplaces closer than this many tiles to the player flicker; far ones keep a still flame.
local FLICKER_RANGE = 30
-- Order in which the 3 flame shapes are shown.
local SEQUENCE = { 1, 2, 3, 2 }

local LOGS_PREFIX = "lp_logs_"
local FIRE_PREFIX = { "lp_fire_a_", "lp_fire_b_", "lp_fire_c_" }

local FIREBOX = {}
for i = 0, 5 do FIREBOX["fixtures_fireplaces_01_" .. i] = true end
-- the game's other fireplace-type fires (same kind of object, same attached log / flame images): Cooking Pits, Brazier, Metal Drums
-- (both colours) and the Barrel Oven (west and north sides; the east and south sides get no flame image from the game)
for _, n in ipairs({ "camping_03_16", "camping_03_19", "crafted_02_42", "crafted_01_26", "crafted_01_30", "crafted_05_4", "crafted_05_5" }) do
    FIREBOX[n] = true
end

local DEBUG = false      -- writes a line to console.txt when a fireplace is found or lit/extinguished
local tracked = {}      -- "x,y,z" -> fireplace object
P.gauge("Fireplace.tracked", function() local n = 0 for _ in pairs(tracked) do n = n + 1 end return n end)
local litSeen = {}      -- "x,y,z" -> last lit state that was printed
local ticks = 0

local function baseName(obj)
    local spr = obj:getSprite()
    if not spr then return nil end
    return spr:getName()
end

local function trackSquare(sq)
    if not sq then return end
    local objs = sq:getObjects()
    if not objs then return end
    for i = 0, objs:size() - 1 do
        local o = objs:get(i)
        if o and instanceof(o, "IsoFireplace") then
            local n = baseName(o)
            if n and FIREBOX[n] then
                local key = sq:getX() .. "," .. sq:getY() .. "," .. sq:getZ()
                if DEBUG and not tracked[key] then
                    print("[ViewpointLivingProps] fireplace@" .. key .. " " .. n .. " found, lit=" .. tostring(o:isLit()))
                end
                tracked[key] = o
            end
        end
    end
end

local function isOurs(name)
    return string.sub(name, 1, 8) == LOGS_PREFIX or string.sub(name, 1, 8) == "lp_fire_"
end

local function nearPlayer(obj)
    local p = getPlayer()
    local sq = obj:getSquare()
    if not p or not sq then return false end
    return math.abs(p:getX() - sq:getX()) <= FLICKER_RANGE and math.abs(p:getY() - sq:getY()) <= FLICKER_RANGE
end

-- Flame pictures are loaded only when the fireplace is lit (never at start-up). Until Project Viewpoint has all of them ready
-- the first flame stays still; then it flickers. Asks the Java helper (see ViewpointLivingPropsAppliances.lua for the same idea).
local viewApi, viewApiTries = nil, 0
local function getViewApi()
    if viewApi then return viewApi end
    if viewApiTries < 50 then
        viewApiTries = viewApiTries + 1
        local ok, api = pcall(function() return ViewpointLivingProps and ViewpointLivingProps.View end)
        if ok and api and api.preload then viewApi = api end
    end
    return viewApi
end

local flameState = {}       -- fireplace sprite -> { ok = all loaded?, t = time of the last check (ms) }
local function flamesReady(base, now)
    local st = flameState[base]
    if st then
        local age = now - st.t
        if st.ok and age < 1000 then return true end
        if not st.ok and age < 200 then return false end
    end
    local ok = true
    local api = getViewApi()
    if api then
        local okc, res = P.pcall("preload", api.preload, FIRE_PREFIX[1] .. base .. "," .. FIRE_PREFIX[2] .. base .. "," .. FIRE_PREFIX[3] .. base)
        ok = (not okc) or res == true
    end
    flameState[base] = { ok = ok, t = now }
    return ok
end

local function wantedName(obj, base, now)
    if obj:isLit() then
        local idx = 1
        if FLICKER_MS > 0 and now and nearPlayer(obj) and flamesReady(base, now) then
            idx = SEQUENCE[(math.floor(now / FLICKER_MS) % #SEQUENCE) + 1]
        end
        return FIRE_PREFIX[idx] .. base
    end
    return LOGS_PREFIX .. base
end

local function nameLogs(obj, now)
    local base = baseName(obj)
    if not base then return end
    local list = obj:getAttachedAnimSprite()
    if not list then return end
    local active = ViewpointLivingPropsIsActive()
    local changed = false
    for i = 0, list:size() - 1 do
        local inst = list:get(i)
        local ps = inst and inst:getParentSprite() or nil
        if ps then
            local cur = ps:getName()
            -- only the log image: it is either still unnamed or already carries one of our names
            if cur == nil or isOurs(cur) then
                local want = nil
                if active then want = wantedName(obj, base, now) end
                if cur ~= want then
                    ps:setName(want)
                    changed = true
                end
            end
        end
    end
    if changed then
        -- ask the game to rebuild this spot (the same call the game makes when it adds the log)
        pcall(function() obj:invalidateRenderChunkLevel(256) end)
    end
end

local function onTick()
    ticks = ticks + 1
    if ticks < 10 then return end
    ticks = 0
    local now = nil
    pcall(function() now = getTimestampMs() end)
    for key, obj in pairs(tracked) do
        local ok = pcall(function()
            if obj:getSquare() == nil then
                tracked[key] = nil
            else
                nameLogs(obj, now)
                if DEBUG then
                    local lit = obj:isLit()
                    if litSeen[key] ~= lit then
                        litSeen[key] = lit
                        print("[ViewpointLivingProps] fireplace@" .. key .. " lit=" .. tostring(lit))
                    end
                end
            end
        end)
        if not ok then tracked[key] = nil end
    end
end

local function safeTrackSquare(sq) pcall(trackSquare, sq) end

local function onObjectAdded(obj)
    pcall(function()
        if obj and instanceof(obj, "IsoFireplace") then trackSquare(obj:getSquare()) end
    end)
end

-- (timed only when ViewpointLivingProps_perf.txt exists)
onTick = P.fn("Fireplace.onTick", onTick)
safeTrackSquare = P.fn("Fireplace.safeTrackSquare", safeTrackSquare)
Events.LoadGridsquare.Add(safeTrackSquare)
Events.ReuseGridsquare.Add(safeTrackSquare)
Events.OnObjectAdded.Add(onObjectAdded)
Events.OnTick.Add(onTick)

-- Started by the Java helper (a server that does not list the mod): the squares around the player were loaded
-- before this script existed, so the events above never told it about their fireplaces. Look around a few times.
if ViewpointLivingPropsJava then
    local sweeps, wait = 0, 0
    Events.OnTick.Add(function()
        if sweeps >= 6 then return end
        wait = wait + 1
        if wait < 120 then return end
        wait = 0
        local p = getPlayer()
        local psq = p and p:getSquare() or nil
        if not psq then return end
        sweeps = sweeps + 1
        local cell = getCell()
        for dx = -30, 30 do
            for dy = -30, 30 do
                safeTrackSquare(cell:getGridSquare(psq:getX() + dx, psq:getY() + dy, psq:getZ()))
            end
        end
    end)
end
P.tag(nil)
