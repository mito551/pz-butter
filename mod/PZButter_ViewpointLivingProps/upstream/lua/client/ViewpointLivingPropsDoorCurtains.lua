-- Viewpoint: Living Props - curtains on doors.
--
-- The game draws the curtain of a door as a flat picture inside the door itself, so Project Viewpoint never shows it.
-- And Viewpoint draws a door as a 3D model of its own, which means anything we hang on the DOOR OBJECT is ignored too.
-- So this script hangs the curtain on the neighbour of the door instead: the wall piece (or door frame) of the same tile.
-- It gives that piece an "overlay" sprite (a made-up name, it has no picture of its own) and the model pack binds that
-- name to a 3D curtain: lp_dcurt_<north><open><inside><c|o>
--   north  = the door is on the north wall            open  = the door is open
--   inside = the curtain hangs on the door's own side c = curtain closed (hanging cloth)   o = curtain open (tied bundle)
--
-- A closed curtain also blocks the light of the room, but Project Viewpoint only re-reads that when something in the chunk
-- changes. Opening/closing a curtain on a door changes nothing it watches, so the room stayed lit until you switched
-- Viewpoint off and on. When the state of a door's curtain changes, this script tells the game that the object changed
-- (the same notice the game sends when anything else is modified), and Viewpoint recomputes the room.
--
-- Everything runs on your own computer; nothing is sent to the server and no game or mod file is changed.

local P = ViewpointLivingPropsPerf or { tag = function() end, fn = function(_, f) return f end, pcall = function(_, f, ...) return pcall(f, ...) end, count = function() end, gauge = function() end }
P.tag("DoorCurtains")

local PREFIX = "lp_dcurt_"
local RANGE = 12                      -- tiles around you that are watched
local DIRTY = 256 + 32                -- "object modified" + "lighting" (FBORenderChunk.DIRTY_OBJECT_MODIFY / DIRTY_LIGHTING)
local DEBUG = false                    -- writes a line to console.txt when a door curtain is found or changes

local tracked = {}                    -- doors found near the player
P.gauge("DoorCurtains.tracked", function() local n = 0 for _ in pairs(tracked) do n = n + 1 end return n end)
local last = {}                       -- door -> last state key
local hostsOf = {}                    -- door -> the wall piece / door frame that carries its curtain
local told = {}                       -- door -> already reported in the log
local ticks = 0
local warned = false

local function nameOf(spr) return spr and spr:getName() or nil end
local function isOurs(name) return name ~= nil and name:sub(1, #PREFIX) == PREFIX end

local function clearOverlay(ob)
    if not ob then return end
    local cur = nameOf(ob:getOverlaySprite())
    if isOurs(cur) then
        if not pcall(function() P.count("overlay changes (door curtains)") ob:setOverlaySprite(nil, false) end) then pcall(function() P.count("overlay changes (door curtains)") ob:setOverlaySprite("", false) end) end
    end
end

-- the curtain hangs on the door's own side (true) or on the other side (false)
local function curtainInside(o, sq, open)
    local ok, res = pcall(function()
        local s = o:getSheetSquare()
        if not s then return nil end
        local own = (s:getX() == sq:getX() and s:getY() == sq:getY())
        if open then return not own end
        return own
    end)
    if ok and res ~= nil then return res end
    return true
end

local function wantedOverlay(o, sq)
    local north = o:getNorth() and 1 or 0
    local open = o:IsOpen()
    local copen = o:isCurtainOpen()
    local inside = curtainInside(o, sq, open) and 1 or 0
    return string.format("%s%d%d%d%s", PREFIX, north, open and 1 or 0, inside, copen and "o" or "c"), copen, open
end

local function relight(o, sq)
    pcall(function() o:invalidateRenderChunkLevel(DIRTY) end)
    pcall(function() sq:invalidateRenderChunkLevel(DIRTY) end)
end

-- objects that are doors themselves can not carry the curtain
local function isDoorLike(ob)
    if instanceof(ob, "IsoDoor") then return true end
    if instanceof(ob, "IsoThumpable") then
        local ok, r = pcall(function() return ob:isDoor() end)
        if ok and r then return true end
    end
    return false
end

local function hasProp(ob, key)
    local ok, r = pcall(function()
        local p = ob:getProperties()
        return p ~= nil and p:has(key)
    end)
    return ok and r == true
end

-- the wall piece with the doorway and/or the door frame standing on the same tile and the same wall as the door
local function findHosts(door, sq)
    local north = door:getNorth()
    local frameKey = north and "doorFrN" or "doorFrW"
    local wallKey = nil
    if IsoFlagType then wallKey = north and IsoFlagType.DoorWallN or IsoFlagType.DoorWallW end
    local list = {}
    local objs = sq:getObjects()
    for i = 0, objs:size() - 1 do
        local ob = objs:get(i)
        if ob ~= door and not isDoorLike(ob) then
            if hasProp(ob, frameKey) or (wallKey ~= nil and hasProp(ob, wallKey)) then list[#list + 1] = ob end
        end
    end
    return list
end

local function validHosts(list, sq)
    if not list then return nil end
    for i = 1, #list do
        local s = list[i]:getSquare()
        if s == nil or s ~= sq then return nil end
    end
    return list
end

local function clearHosts(o)
    local list = hostsOf[o]
    if list then
        for i = 1, #list do pcall(clearOverlay, list[i]) end
    end
    hostsOf[o] = nil
end

local function syncDoor(o)
    local sq = o:getSquare()
    if not sq then return end
    if not ViewpointLivingPropsIsActive() then
        clearOverlay(o)
        clearHosts(o)
        -- Also remove our overlays from a loaded save or an older script session.
        for _, h in ipairs(findHosts(o, sq)) do clearOverlay(h) end
        if last[o] ~= nil then relight(o, sq) end
        last[o] = nil
        told[o] = nil
        return
    end
    clearOverlay(o)                                                   -- (an earlier version hung the curtain on the door itself)
    if not o:HasCurtains() then                                       -- no curtain (or it was taken down)
        clearHosts(o)
        last[o] = nil
        told[o] = nil
        return
    end
    local hosts = validHosts(hostsOf[o], sq)
    if not hosts or #hosts == 0 then
        hosts = findHosts(o, sq)
        hostsOf[o] = hosts
    end
    local want, copen, open = wantedOverlay(o, sq)
    for i = 1, #hosts do
        local h = hosts[i]
        local cur = nameOf(h:getOverlaySprite())
        if cur ~= want and (cur == nil or isOurs(cur)) then P.count("overlay changes (door curtains)") h:setOverlaySprite(want, false) end
    end
    if DEBUG and not told[o] then
        told[o] = true
        local names = ""
        for i = 1, #hosts do names = names .. " " .. tostring(nameOf(hosts[i]:getSprite())) end
        print("[ViewpointLivingProps] door curtain@" .. sq:getX() .. "," .. sq:getY() .. " " .. want .. " carried by" .. (#hosts == 0 and " NOTHING (no wall piece or frame found)" or names))
    end
    local prev = last[o]
    last[o] = want
    if prev ~= nil and prev ~= want then
        relight(o, sq)                                                -- opened/closed the curtain (or the door): redo the room's light
        if DEBUG then print("[ViewpointLivingProps] door curtain@" .. sq:getX() .. "," .. sq:getY() .. " " .. prev .. " -> " .. want) end
    end
end

local function scanNear()
    local p = getPlayer()
    if not p then return end
    local sq = p:getSquare()
    if not sq then return end
    local cell = getCell()
    local z = sq:getZ()
    for dx = -RANGE, RANGE do
        for dy = -RANGE, RANGE do
            local s = cell:getGridSquare(sq:getX() + dx, sq:getY() + dy, z)
            if s then
                local objs = s:getSpecialObjects()
                for i = 0, objs:size() - 1 do
                    local o = objs:get(i)
                    if not tracked[o] and instanceof(o, "IsoDoor") then tracked[o] = true end
                end
            end
        end
    end
end

local function update()
    for o in pairs(tracked) do
        if o:getSquare() == nil then
            clearHosts(o)
            tracked[o] = nil
            last[o] = nil
            told[o] = nil
        else
            local ok, err = pcall(syncDoor, o)
            if not ok and not warned then
                warned = true
                print("[ViewpointLivingProps] door curtain: " .. tostring(err))
            end
        end
    end
end

-- (timed only when ViewpointLivingProps_perf.txt exists)
scanNear = P.fn("DoorCurtains.scanNear", scanNear)
update = P.fn("DoorCurtains.update", update)
Events.OnTick.Add(function()
    ticks = ticks + 1
    if ticks % 10 == 3 then pcall(update) end
    if ticks % 90 == 41 then pcall(scanNear) end
end)
P.tag(nil)
