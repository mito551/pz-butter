--[[
    CD_PerfNear: one place that answers "what is near this dog" for Companion Dogs' scans
    (CD.zsnap, scanWildAnimals, findTroughNear in the CD_Perf overrides).

    Source per query, best first:
      1. perception (zombies only): what the engine reported to this dog in its last
         updateLOS (CDPerf.jar), if <= 250 ms old, not truncated, and a real scan ran
         < 2 s ago (safety net)
      2. cdNear (CDPerf.jar): one native walk of the requested box
      3. Lua snapshot per dog. A fresh enough snapshot covering the box answers at once.
         One that is a little old answers while a new one is walked in slices of 400
         squares per tick (all dogs together); that walk collects every category asked for
         in the last 3 s (fused) over the largest box asked for + 2 tiles. With no usable
         snapshot, the requested box alone is walked now - the same work as the original.
    Results are always re-filtered live (dead, removed from the world, out of the box).
    A box wider than 24 tiles is never cached.

    Switches (CD_PerfPatchConfig): perception, nearJar, nearSlice (all default true).
]]

CompanionDogs = CompanionDogs or {}
local CD = CompanionDogs
local M = {}

local function cfgOn(key)
    local c = rawget(_G, "CD_PerfPatchConfig")
    return not (type(c) == "table" and c[key] == false)
end

local SLICE_BUDGET = 400
local SAFETY_NET_MS = 2000
local PERCEPTION_AGE_MS = 250
local PERCEPTION_CAP = 128        -- CDPerf.jar keeps at most this many per dog
local FRESH_MS = { zombies = 500, animals = 1000, feed = 2000 }
local STALE_MAX_MS = 3000
local RECENT_MS = 3000
local FORGET_MS = 10000
local FUSED_CAP = 24
local MARGIN = 2

local safetyLast = {}   -- dog -> ms of its last real (jar or Lua) scan
local snapshots = {}    -- dog -> complete snapshot
local builds = {}       -- dog -> snapshot being walked in slices
local recent = {}       -- dog -> { zombies = {at, R}, animals = {...}, feed = {...} }
local lastQuery = {}    -- dog -> ms of its last query

---------------------------------------------------------------------------
-- squares: the chunk map's getGridSquare has a single (int,int,int) overload,
-- the cell's has three, so Kahlua resolves less per call. Only with one local
-- player off the server: a chunk map covers one player's window.
---------------------------------------------------------------------------

local chunkCell, chunkMap = nil, nil

--- The object and method to read squares with, for one walk.
local function squareSource()
    local cell = getCell()
    if not cell then return nil, nil end
    if isServer() or getNumActivePlayers() > 1 then return cell, cell.getGridSquare end
    if chunkCell ~= cell then
        chunkCell, chunkMap = cell, false
        local ok, cm = pcall(cell.getChunkMap, cell, 0)
        if ok and cm and pcall(cm.getGridSquare, cm, 0, 0, 0) then chunkMap = cm end
    end
    if chunkMap then return chunkMap, chunkMap.getGridSquare end
    return cell, cell.getGridSquare
end

function M.sqAt(x, y, z)
    local src, get = squareSource()
    return src and get(src, x, y, z) or nil
end

---------------------------------------------------------------------------
-- walking squares
---------------------------------------------------------------------------

local function newBox(cx, cy, z, R, want)
    return { cx = cx, cy = cy, z = z, R = R, dx = -R, dy = -R, want = want,
             zombies = {}, animals = {}, feed = {} }
end

local function collect(b, sq)
    local want = b.want
    if want.zombies then
        local mo = sq:getMovingObjects()
        for i = 0, mo:size() - 1 do
            local o = mo:get(i)
            if instanceof(o, "IsoZombie") then b.zombies[#b.zombies + 1] = o end
        end
    end
    if want.animals then
        local an = sq:getAnimals()
        for i = 0, an:size() - 1 do b.animals[#b.animals + 1] = an:get(i) end
    end
    if want.feed then
        -- troughs before dishes within a square, as findTroughNear visits them
        local ob = sq:getObjects()
        for i = 0, ob:size() - 1 do
            local o = ob:get(i)
            if instanceof(o, "IsoFeedingTrough") then b.feed[#b.feed + 1] = o end
        end
        local dishType = CD.DISH_TYPE
        local wo = sq:getWorldObjects()
        for i = 0, wo:size() - 1 do
            local w = wo:get(i)
            local item = w and w:getItem()
            if item and item:getFullType() == dishType then b.feed[#b.feed + 1] = w end
        end
    end
end

--- Walks up to n squares of b (dx outer, dy inner, like the originals).
--- Returns done, squares walked.
local function walk(b, n)
    local src, get = squareSource()
    if not src then return true, n end
    local R, used = b.R, 0
    while used < n and b.dx <= R do
        local sq = get(src, b.cx + b.dx, b.cy + b.dy, b.z)
        if sq then collect(b, sq) end
        b.dy = b.dy + 1
        if b.dy > R then b.dy, b.dx = -R, b.dx + 1 end
        used = used + 1
    end
    return b.dx > R, used
end

local function finish(dog, b)
    b.at = getTimestampMs()
    snapshots[dog] = b
    builds[dog] = nil
    safetyLast[dog] = b.at
end

local function forget(dog)
    safetyLast[dog], snapshots[dog], builds[dog], recent[dog], lastQuery[dog] = nil, nil, nil, nil, nil
    if type(cdWatch) == "function" then pcall(cdWatch, dog, false) end
end

local stepCount = 0

--- Advances the sliced walks, sharing SLICE_BUDGET squares fairly between dogs, and
--- every 600 ticks forgets dogs nobody asked about for FORGET_MS.
function M.step()
    stepCount = stepCount + 1
    if stepCount % 600 == 0 then
        local now = getTimestampMs()
        local gone = {}
        for dog, at in pairs(lastQuery) do
            if now - at > FORGET_MS then gone[#gone + 1] = dog end
        end
        for i = 1, #gone do forget(gone[i]) end
    end
    local budget = SLICE_BUDGET
    while budget > 0 do
        local dogs = {}
        for dog in pairs(builds) do dogs[#dogs + 1] = dog end
        if #dogs == 0 then return end
        local share = math.max(1, math.floor(budget / #dogs))
        for i = 1, #dogs do
            local b = builds[dogs[i]]
            local done, used = walk(b, math.min(share, budget))
            budget = budget - used
            if done then finish(dogs[i], b) end
            if budget <= 0 then return end
        end
    end
end

--- Everything asked for recently, over the largest box asked for, plus a margin, so a
--- dog that moved a tile stays covered.
local function plan(dog, cat, R, now)
    local r = recent[dog]
    if not r then r = {} recent[dog] = r end
    r[cat] = { at = now, R = R }
    local want, maxR = {}, R
    for c, e in pairs(r) do
        if now - e.at <= RECENT_MS then
            want[c] = true
            if e.R > maxR then maxR = e.R end
        end
    end
    return want, math.min(FUSED_CAP, maxR + MARGIN)
end

local function covers(s, cat, cx, cy, z, R)
    return s and s.want[cat] and s.z == z
        and math.abs(cx - s.cx) + R <= s.R and math.abs(cy - s.cy) + R <= s.R
end

--- The snapshot (or one-off box) to answer from, per the precedence in the header.
local function source(dog, cat, cx, cy, z, R)
    local now = getTimestampMs()
    lastQuery[dog] = now
    if R > FUSED_CAP or not cfgOn("nearSlice") then
        local b = newBox(cx, cy, z, R, { [cat] = true })
        walk(b, (2 * R + 1) * (2 * R + 1))
        safetyLast[dog] = now
        return b
    end
    local want, boxR = plan(dog, cat, R, now)
    local s = snapshots[dog]
    if covers(s, cat, cx, cy, z, R) then
        local age = now - s.at
        if age <= FRESH_MS[cat] then return s end
        if age <= STALE_MAX_MS then
            if not builds[dog] then builds[dog] = newBox(cx, cy, z, boxR, want) end
            return s
        end
    end
    -- nothing usable: the requested box alone, now; a sliced fused walk follows
    local b = newBox(cx, cy, z, R, { [cat] = true })
    walk(b, (2 * R + 1) * (2 * R + 1))
    finish(dog, b)
    if not builds[dog] then builds[dog] = newBox(cx, cy, z, boxR, want) end
    return b
end

---------------------------------------------------------------------------
-- live filters
---------------------------------------------------------------------------

local function inBox(o, cx, cy, z, R)
    return math.floor(o:getZ()) == z
        and math.abs(math.floor(o:getX()) - cx) <= R and math.abs(math.floor(o:getY()) - cy) <= R
end

local function liveZombies(list, cx, cy, z, R)
    local out = {}
    for i = 1, #list do
        local o = list[i]
        if inBox(o, cx, cy, z, R) and o:isExistInTheWorld() and CD.isThreatZombie(o) then
            out[#out + 1] = o
        end
    end
    return out
end

local function liveAnimals(list, cx, cy, z, R)
    local out = {}
    for i = 1, #list do
        local o = list[i]
        if inBox(o, cx, cy, z, R) and not o:isDead() and o:isExistInTheWorld() then out[#out + 1] = o end
    end
    return out
end

local function livePlaced(list, cx, cy, z, R)
    local out = {}
    for i = 1, #list do
        local o = list[i]
        local sq = o:getSquare()
        if sq and sq:getZ() == z and math.abs(sq:getX() - cx) <= R and math.abs(sq:getY() - cy) <= R then
            out[#out + 1] = o
        end
    end
    return out
end

local function dogTile(dog)
    return math.floor(dog:getX()), math.floor(dog:getY()), math.floor(dog:getZ())
end

---------------------------------------------------------------------------
-- public
---------------------------------------------------------------------------

--- Threat zombies whose tile is in [cx-R, cx+R] x [cy-R, cy+R] on floor z.
function M.zombies(dog, cx, cy, z, R)
    cx, cy, z, R = math.floor(cx), math.floor(cy), math.floor(z), math.ceil(R)
    lastQuery[dog] = getTimestampMs()
    if cfgOn("perception") and type(cdWatch) == "function" then
        pcall(cdWatch, dog, true)
        local last = safetyLast[dog]
        if last and getTimestampMs() - last < SAFETY_NET_MS and type(cdSpottedZombies) == "function" then
            local ok, rec = pcall(cdSpottedZombies, dog)
            if ok and type(rec) == "table" and type(rec.ageMs) == "number"
                    and rec.ageMs <= PERCEPTION_AGE_MS and #rec < PERCEPTION_CAP then
                return liveZombies(rec, cx, cy, z, R)
            end
        end
    end
    if cfgOn("nearJar") and type(cdNear) == "function" then
        local ok, res = pcall(cdNear, cx, cy, z, R, true, false, false, nil)
        if ok and type(res) == "table" and res.zombies then
            safetyLast[dog] = getTimestampMs()
            return liveZombies(res.zombies, cx, cy, z, R)
        end
    end
    return liveZombies(source(dog, "zombies", cx, cy, z, R).zombies, cx, cy, z, R)
end

--- Animals whose tile is within R of the dog's tile (box), on its floor, in square order.
function M.animals(dog, R)
    local cx, cy, z = dogTile(dog)
    R = math.ceil(R)
    lastQuery[dog] = getTimestampMs()
    if cfgOn("nearJar") and type(cdNear) == "function" then
        local ok, res = pcall(cdNear, cx, cy, z, R, false, true, false, nil)
        if ok and type(res) == "table" and res.animals then
            safetyLast[dog] = getTimestampMs()
            return liveAnimals(res.animals, cx, cy, z, R)
        end
    end
    return liveAnimals(source(dog, "animals", cx, cy, z, R).animals, cx, cy, z, R)
end

--- Troughs and dishes (CD.DISH_TYPE world items) within R of the dog's tile, in one list in
--- square order, troughs before dishes within a square.
function M.feed(dog, R)
    local cx, cy, z = dogTile(dog)
    R = math.ceil(R)
    lastQuery[dog] = getTimestampMs()
    if cfgOn("nearJar") and type(cdNear) == "function" then
        local ok, res = pcall(cdNear, cx, cy, z, R, false, false, true, CD.DISH_TYPE)
        if ok and type(res) == "table" and res.feed then
            safetyLast[dog] = getTimestampMs()
            return livePlaced(res.feed, cx, cy, z, R)
        end
    end
    return livePlaced(source(dog, "feed", cx, cy, z, R).feed, cx, cy, z, R)
end

CD.PerfNear = M

if Events and Events.OnTick then Events.OnTick.Add(M.step) end
