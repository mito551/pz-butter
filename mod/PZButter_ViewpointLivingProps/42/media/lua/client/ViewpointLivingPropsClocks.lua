-- VLP_Perf override of ViewpointLivingProps 1.0.3. A newer original is shadowed by this copy.
do
    local m = getModInfoByID and getModInfoByID("ViewpointLivingProps")
    local v = m and m:getModVersion()
    if v and v ~= "1.0.3" then
        print("[VLP_Perf] WARNING: ViewpointLivingProps is " .. tostring(v) .. ", this override was built from 1.0.3; disable VLP_Perf until it is rebuilt")
    end
end

-- Viewpoint: Living Props - wall clocks that tell the time in Project Viewpoint's own 3D view.
--
-- The game draws the hands of a wall clock by itself in the isometric renderer (the clock sprite is swapped for a hand-less face and the
-- hands are painted on top from the game time). Project Viewpoint does not run that code, so a clock there only showed a flat picture with
-- hands frozen at some time. This mod now draws the clock face in 3D (pack.properties) and this script moves the hands:
--   * the minute hand is the object's overlay sprite  lp_clkm_<kind>_<0..59>   (one 3D hand per minute)
--   * the hour hand is an attached sprite             lp_clkh_<kind>_<0..59>   (one 3D hand per 12 minutes = 6 degrees)
-- Both names are "virtual" sprites (no picture of their own): they only exist so the pack can bind a 3D hand to each of them.
-- Like the game's own clock, a clock that has no power stops (it keeps the last time it was powered).
-- Only while Project Viewpoint's view is showing: in the game's normal isometric view everything this script hung on a clock is taken off again,
-- so the isometric view is exactly the game's. Nothing is written to any game or mod file; only in-memory state of the objects near you.
-- An empty text file called ViewpointLivingProps_clocks_still.txt in the Zomboid\Lua folder (restart the game) makes every clock show 10:10
-- and not move (in case something goes wrong with the moving hands).

local P = ViewpointLivingPropsPerf or { tag = function() end, fn = function(_, f) return f end, pcall = function(_, f, ...) return pcall(f, ...) end, count = function() end, gauge = function() end }
P.tag("Clocks")

local RANGE = 12              -- tiles around the player where clocks are looked for / kept moving
local FLOOR_RANGE = 8         -- include tower clocks above/below the player's floor
local SCAN_TICKS = 12         -- one floor per step: spread the search over time
local MIN_MS = 1500           -- a clock changes its picture at most this often (real time), so a fast game speed does not rebuild the map piece all the time
local FAST_MS = 500           -- ... but when the game runs fast (the hands are FAST_GAP minutes or more behind) it may change this often, so the hands keep turning
local FAST_GAP = 3
local ONSCREEN_ONLY = true    -- a clock the Viewpoint camera does not show keeps its last time until it is looked at again

-- clock sprite -> kind (the letter pair of the hand sprites / binds): s = round school clock, b = square blue clock, t = big tower clock; n = on a north wall, w = on a west wall
local CLOCKS = {
    location_community_school_01_32 = "sn",
    location_community_school_01_33 = "sw",
    walls_decoration_01_105 = "bn",
    walls_decoration_01_104 = "bw",
    -- the big two-tile tower clock: the left half (seen from the room) owns the hands; the other half is plain stone
    walls_detailing_02_56 = "tn",
    walls_detailing_02_58 = "tw",
}
local MIN_PREFIX = "lp_clkm_"
local HOUR_PREFIX = "lp_clkh_"

local function flag(name)
    local ok, r = pcall(getFileReader, name, false)
    if ok and r then
        pcall(function() r:close() end)
        return true
    end
    return false
end
local STILL = flag("ViewpointLivingProps_clocks_still.txt")
local DEBUG = flag("ViewpointLivingProps_debug.txt")

local tracked = {}            -- clock object -> { code = kind, t = time (ms) of its last picture change, on = hung on this object }
P.gauge("Clocks.tracked", function() local n = 0 for _ in pairs(tracked) do n = n + 1 end return n end)
local ticks = 0
local warned = false

local function nameOf(spr) return spr and spr:getName() or nil end

-- A sprite made up from a name alone (getSprite) comes back WITHOUT its name filled in: the game only fills it in itself for an overlay
-- (IsoObject.setOverlaySprite). Project Viewpoint finds a 3D model by that name, so an attached hand with no name is never drawn.
-- This gives the sprite its name (the same sprite object is reused every time, so it only happens once per hand picture).
local function vsprite(name, o, restore)
    local spr = getSprite(name)
    if not spr then return nil end
    if spr:getName() == nil then
        pcall(function() spr:setName(name) end)
    end
    if spr:getName() == nil and o then
        -- the game fills the name in when the sprite is used as an overlay: do that for a moment, then put the minute hand back
        pcall(function() o:setOverlaySprite(name, false) end)
        if restore then pcall(function() o:setOverlaySprite(restore, false) end) end
    end
    if spr:getName() == nil then return nil end
    return spr
end

-- ---------------------------------------------------------------- the small Java helper (optional): "is Viewpoint's view showing", "is it on screen", "load this 3D hand"
local viewApi = nil
local viewApiTries = 0
local function getViewApi()
    if viewApi then return viewApi end
    if viewApiTries < 50 then
        viewApiTries = viewApiTries + 1
        local ok, api = pcall(function() return ViewpointLivingProps and ViewpointLivingProps.View end)
        if ok and api and api.sees then viewApi = api end
    end
    return viewApi
end

-- true = Project Viewpoint's own view, false = the game's isometric view, nil = cannot tell (no Java helper): then it is treated as Viewpoint's view
-- (the hands are virtual sprites with no picture, so they are invisible in the isometric view anyway)
local function viewState()
    return ViewpointLivingPropsIsActive()
end

local function onScreen(o)
    if not ONSCREEN_ONLY then return true end
    local api = getViewApi()
    if not api then return true end
    local ok, res = pcall(function()
        local sq = o:getSquare()
        if not sq then return false end
        return api.sees(sq:getX() + 0.5, sq:getY() + 0.5, sq:getZ() + 0.75)
    end)
    if ok then return res end
    return true
end

-- VLP_Perf: the Viewpoint sees() query is the costliest part of a sync; reuse it for 250 ms per clock.
local function onScreenCached(o, s, now)
    if not now then return onScreen(o) end
    if not s.osSeen or now - s.osSeen >= 250 then
        s.osVal = onScreen(o)
        s.osSeen = now
    end
    return s.osVal ~= false
end

local function near(o, r)
    local p = getPlayer()
    local sq = o:getSquare()
    if not p or not sq then return false end
    if math.abs(p:getX() - sq:getX()) > r or math.abs(p:getY() - sq:getY()) > r then return false end
    return math.abs(math.floor(p:getZ()) - sq:getZ()) <= FLOOR_RANGE
end

local function isStillThere(o)
    local sq = o:getSquare()
    if not sq then return false end
    local cur = getCell():getGridSquare(sq:getX(), sq:getY(), sq:getZ())
    if not cur then return false end
    local objs = cur:getObjects()
    for i = 0, objs:size() - 1 do
        if objs:get(i) == o then return true end
    end
    return false
end

-- ---------------------------------------------------------------- what time does this clock show?
local function toNum(v)
    if type(v) == "number" then return v end
    if v ~= nil then
        local n = nil
        pcall(function() n = tonumber(tostring(v)) end)
        return n
    end
    return nil
end

-- the same test the game uses for its own clock hands: inside a building with the grid on, or a generator powering the square
local function powered(o)
    local sq = o:getSquare()
    if not sq then return false end
    local inside, grid, gen = false, false, false
    pcall(function() inside = sq:isInARoom() or sq:getBuilding() ~= nil end)
    pcall(function() grid = o:hasGridPower() end)
    pcall(function() gen = sq:haveElectricity() end)
    return (inside and grid) or gen
end

local function shownTime(o, tod)
    if STILL then return 10 + 10 / 60 end
    local md = o:getModData()
    if powered(o) then
        md.lpClkT = tod                       -- the time it stopped at if the power goes off (our own key: the game's "LastTimeofDay" must stay a Float)
        return tod
    end
    local t = toNum(md.lpClkT)
    if t == nil then t = toNum(md.LastTimeofDay) end
    return t or 7.0                           -- the game's own default for a clock that never had power
end

local function two(n) return (n < 10 and "0" or "") .. n end

-- hours (0..24, fraction = minutes) -> minute-hand picture 0..59, hour-hand picture 0..59 (6 degrees each, nearest)
local function indices(t)
    local tm = math.floor(t * 60 + 0.0001)
    local m = tm % 60
    local h = math.floor(((tm % 720) + 6) / 12) % 60
    return m, h
end

-- ---------------------------------------------------------------- hang the hands on / take them off a clock
local function hourNameOf(o)
    local list = o:getAttachedAnimSprite()
    if list and list:size() == 1 then
        local inst = list:get(0)
        if inst then return inst:getName() end
    end
    return nil
end

local function isOursMin(n) return n ~= nil and n:sub(1, #MIN_PREFIX) == MIN_PREFIX end

local function clean(o)
    local cur = nameOf(o:getOverlaySprite())
    if isOursMin(cur) then
        P.count("overlay changes (clocks)")
        o:setOverlaySprite("", false)
    end
    local md = o:getModData()
    if md.lpClkAtt then
        pcall(function() o:RemoveAttachedAnims() end)
        md.lpClkAtt = nil
    end
end

-- "lp_clkm_sn_47" -> 47
local function minuteOf(name)
    return name and tonumber(name:sub(-2)) or nil
end

local function syncOne(o, s, now, tod)
    local t = shownTime(o, tod)
    local m, h = indices(t)
    local nm = MIN_PREFIX .. s.code .. "_" .. two(m)        -- the newest time
    local nh = HOUR_PREFIX .. s.code .. "_" .. two(h)
    local wm, wh = nm, nh                                    -- what is shown now (an older time while the newest one is still loading)
    local curM = nameOf(o:getOverlaySprite())
    local curH = hourNameOf(o)
    if curM == nm and curH == nh then s.pm, s.ph = nil, nil return end
    local api = getViewApi()
    local preloading = api ~= nil and api.preload ~= nil
    if isOursMin(curM) and curH ~= nil then
        -- already showing a time: change it only when the clock is in view, not too often, and once both 3D hands are loaded (so it never blinks)
        if not onScreenCached(o, s, now) then return end
        local sm = minuteOf(curM)
        local fast = false
        if sm then
            local d = (m - sm + 60) % 60
            if d > 30 then d = 60 - d end
            fast = d >= FAST_GAP
        end
        if preloading then
            -- The hands are loaded one step ahead: the time asked for on an earlier update is shown as soon as it is ready, and the NEWEST
            -- time is asked for next. (Asking only for the newest time every time never gets anything ready when the game time runs fast,
            -- and the hands stood still while the time was sped up.)
            if not s.pm then s.pm, s.ph, s.pt = nm, nh, now end
            local okc, ready = P.pcall("preload", api.preload, s.pm .. "," .. s.ph)
            if okc and ready ~= true then
                if now - s.pt > 4000 then s.pm, s.ph = nil, nil end       -- never gets ready: ask for the newest time again
                return
            end
            wm, wh = s.pm, s.ph
        end
        if s.t and now - s.t < (fast and FAST_MS or MIN_MS) then return end
        s.pm, s.ph = nil, nil
    end
    if curM ~= wm then
        P.count("overlay changes (clocks)")
        o:setOverlaySprite(wm, false)
    end
    if curH ~= wh then
        local hs = vsprite(wh, o, wm)
        if hs then
            P.count("overlay changes (clocks)")
            o:RemoveAttachedAnims()
            o:addAttachedAnimSprite(hs)
            o:getModData().lpClkAtt = true
        elseif not warned then
            warned = true
            print("[ViewpointLivingProps] clock hands: cannot give the hour hand sprite a name (" .. wh .. ")")
        end
    end
    s.t = now
    s.on = true
    if preloading and (wm ~= nm or wh ~= nh) then
        -- the picture shown is older than the newest time (the game runs fast): start loading the newest right away
        s.pm, s.ph, s.pt = nm, nh, now
        P.pcall("preload", api.preload, nm .. "," .. nh)
    end
    if DEBUG then
        local sq = o:getSquare()
        print("[ViewpointLivingProps] clock@" .. sq:getX() .. "," .. sq:getY() .. " " .. s.code .. " " .. string.format("%.2f", t) .. " -> " .. wm .. " " .. wh)
    end
end

-- ---------------------------------------------------------------- find the clocks near the player
local scanFloor = 0
local function scanNear()
    local p = getPlayer()
    if not p then return end
    local sq = p:getSquare()
    if not sq then return end
    local cell = getCell()
    -- Own floor, then +1, -1, +2, -2, ... . One floor costs the same as the old scan.
    local k = scanFloor % (2 * FLOOR_RANGE + 1)
    scanFloor = scanFloor + 1
    local dz = math.floor((k + 1) / 2) * (k % 2 == 1 and 1 or -1)
    local z = sq:getZ() + dz
    if z < -32 or z > 31 then return end
    for dx = -RANGE, RANGE do
        for dy = -RANGE, RANGE do
            local s = cell:getGridSquare(sq:getX() + dx, sq:getY() + dy, z)
            if s then
                local objs = s:getObjects()
                for i = 0, objs:size() - 1 do
                    local o = objs:get(i)
                    if not tracked[o] then
                        local spr = o:getSprite()
                        local n = spr and spr:getName()
                        local code = n and CLOCKS[n]
                        if code then
                            tracked[o] = { code = code }
                            -- left over from a save made while the hands were hung on it: take them off (they are put back right away if Viewpoint's view is on)
                            pcall(clean, o)
                        end
                    end
                end
            end
        end
    end
end

local function update()
    local st = viewState()
    local iso = (st == false)
    local now = nil
    pcall(function() now = getTimestampMs() end)
    local tod = nil
    pcall(function() tod = GameTime.getInstance():getTimeOfDay() end)
    if not now or not tod then return end
    for o, s in pairs(tracked) do
        if o:getSquare() == nil or not isStillThere(o) then
            tracked[o] = nil
        elseif not near(o, RANGE + 3) then
            -- going out of range: take the hands off now, while we still can (a chunk that unloads saves what is hung on its objects)
            if s.on then pcall(clean, o) s.on = false end
            tracked[o] = nil
        elseif iso then
            if s.on then
                pcall(clean, o)
                s.on = false
            end
        else
            local ok, err = pcall(syncOne, o, s, now, tod)
            if not ok and not warned then
                warned = true
                print("[ViewpointLivingProps] clock hands: " .. tostring(err))
            end
        end
    end
end

scanNear = P.fn("Clocks.scanNear", scanNear)
update = P.fn("Clocks.update", update)

Events.OnTick.Add(function()
    ticks = ticks + 1
    if ticks % 30 == 0 then pcall(update) end
    if ticks % SCAN_TICKS == 7 then pcall(scanNear) end
end)

-- before the game saves, take every hand off (the objects are saved with whatever is hung on them); the next update puts them back
local function cleanAll()
    for o, s in pairs(tracked) do
        pcall(clean, o)
        s.on = false
        s.t = nil
        s.pm, s.ph = nil, nil
    end
end
if Events.OnSave then Events.OnSave.Add(function() pcall(cleanAll) end) end
if Events.OnPostSave then Events.OnPostSave.Add(function() ticks = 29 end) end
