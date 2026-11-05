--[[
    WKT_Perf build of WKT_GroundKeys.lua. Regenerate with WKT_Perf/tools/build_wkt.py after every update
    of Whose Key Is This?.
]]
do
    local ok, r = pcall(getModFileReader, "WhoseKeyIsThis", "media/lua/client/WKT_GroundKeys.lua", false)
    if ok and r then
        local n, line = 0, r:readLine()
        while line do n = n + #line; line = r:readLine() end
        r:close()
        if n ~= 10252 then
            print("[WKT_Perf] WARNING: WKT_GroundKeys.lua of Whose Key Is This? changed since WKT_Perf was built; regenerate WKT_Perf with tools/build_wkt.py")
        end
    end
end

--[[
    Whose Key Is This?  /  이 열쇠는 어디꺼야?
    Keys lying on the ground.

    A key dropped on a road tile is a few pixels of grey on grey, and the one
    you want is usually the one you never saw. This puts its name over it
    while it is on the floor in front of you.

    It only ever announces keys you have never held. The moment a key reaches
    your inventory it is marked, permanently, so one you later throw away
    stays quiet - a discarded key calling you back to itself would be worse
    than no search at all.

    HOW THE SEARCH IS PACED. Forty tiles of ground is five thousand squares,
    and asking the engine about all of them in one go is a stutter you can
    feel. It used to run once a second in a single lump, which put a hitch in
    every second and still left the label up to a second late - it looked
    like the mod was thinking it over. Now a sweep is cut into slices, a few
    columns per frame, working outwards from the character so the ground you
    are about to walk over is read first. A key found mid-sweep is announced
    on the spot; keys are only taken off the list when a whole sweep has gone
    by without seeing them, so nothing flickers.

    The labels are drawn by WKT_Pointer, which already owns a screen overlay
    that is careful not to swallow mouse input. This file only decides what
    there is to draw.

    Build 42.20
]]

require "WKT_Shared"

WKT.Ground = WKT.Ground or {}
local Ground = WKT.Ground

-- keyed by player number, since split screen has a viewport each
Ground.entries = {}

-- sweep in progress, per player
local sweeps = {}
-- when the last sweep for a player began
local started = {}
-- how far each label has faded in, per item
local fade = {}

-- Labels are worth having on a doorstep, not across the street: too many at
-- once and the search becomes the clutter it was meant to cut through.
local MAX_LABELS = 10

-- A sweep begins this often and is spread over this many frames. Two sweeps
-- a second is quick enough that walking up to a key feels immediate, and a
-- slice of a few hundred squares is small enough to disappear into a frame.
local SWEEP_PERIOD_MS = 500
local SLICES = 8

-- A label fades in over this long, and fades out over the last few tiles of
-- the search range. Between them a key never pops in or out - which matters
-- more than it sounds, because a pop reads as a glitch and a fade reads as
-- something coming into view.
local FADE_MS = 220
local EDGE_TILES = 6


--- What the last sweep found for this player. Never nil, so callers can
--- just count it.
function Ground.get(playerNum)
    return Ground.entries[playerNum] or {}
end

local function makeEntry(worldObj, item, dist)
    local r, g, b = WKT.kindColor(nil, item)
    return {
        item = item,
        name = item:getName(),
        x = worldObj:getX(), y = worldObj:getY(), z = worldObj:getZ(),
        dist = dist,
        r = r, g = g, b = b,
    }
end

local function alreadyListed(list, item)
    for _, entry in ipairs(list) do
        if entry.item == item then return true end
    end
    return false
end

--- Starts a sweep, remembering where the character stood when it began.
local function beginSweep(playerNum, playerObj, radius)
    local wx, wy = playerObj:getX(), playerObj:getY()

    -- column order: the character's own column first, then outwards on
    -- alternate sides, so the nearest ground is read in the first slice
    local columns = { 0 }
    for d = 1, radius do
        table.insert(columns, d)
        table.insert(columns, -d)
    end

    return {
        wx = wx, wy = wy,
        px = math.floor(wx), py = math.floor(wy), pz = math.floor(playerObj:getZ()),
        radius = radius, limit = radius * radius,
        columns = columns, next = 1,
        perSlice = math.max(1, math.ceil(#columns / SLICES)),
        found = {},
    }
end

--- Reads one column of the circle. Everything the engine is asked here can
--- come back nil on unloaded ground; none of it raises, so the one pcall in
--- the caller is enough.
local function sweepColumn(sweep, cell, playerNum, published)
    local dx = sweep.px + sweep.columns[sweep.next] + 0.5 - sweep.wx
    local x = sweep.px + sweep.columns[sweep.next]

    -- the chord of the circle at this column, so the corners of the square
    -- that encloses it are never visited at all
    local span = math.floor(math.sqrt(math.max(0, sweep.limit - dx * dx)))

    for y = sweep.py - span, sweep.py + span do
        local square = cell:getGridSquare(x, y, sweep.pz)
        -- Only squares the character can actually see. Naming keys through
        -- walls would make this a wallhack.
        if square ~= nil and square:isCouldSee(playerNum) then
            local objects = square:getWorldObjects()
            if objects ~= nil then
                for i = 1, objects:size() do
                    local worldObj = objects:get(i - 1)
                    local item = worldObj and worldObj:getItem() or nil
                    if WKT.isKeyItem(item) and WKT.getKeyId(item) ~= -1
                        and not WKT.isFound(item) then
                        local dy = y + 0.5 - sweep.wy
                        local entry = makeEntry(worldObj, item,
                            math.sqrt(dx * dx + dy * dy))
                        table.insert(sweep.found, entry)

                        -- Announce it now rather than at the end of the
                        -- sweep. Waiting would put the label up to half a
                        -- second after you walked into range, which is
                        -- exactly the lag this pacing is here to remove.
                        if #published < MAX_LABELS and not alreadyListed(published, item) then
                            table.insert(published, entry)
                        end
                    end
                end
            end
        end
    end
end

--- Advances the search. Called every frame.
---
--- WKT_Perf: the circle is no longer re-read twice a second.
---   * With WKTPerf.jar, keys the renderer drew since last frame are checked as they come
---     (wktDrainGroundKeys), and one native sweep (wktGroundKeysNear) runs every
---     PERF_FULL_MS or after PERF_MOVE tiles of movement as the safety net.
---   * Without it, a full sweep (spread over SLICES frames, as before) runs on the same
---     triggers, or when the radius or the zoom changes. It reads only the rows of each
---     column that are on screen (labels off screen are never drawn), and remembers the
---     squares that held any item; every PERF_RECHECK_MS in between only those are read.
--- Labels are only ever taken off the list when a full sweep ends, as before.
--- Switches: WKT_PerfPatchConfig.groundRender / .groundClip = false.
local PERF_FULL_MS, PERF_RECHECK_MS, PERF_MOVE = 2000, 500, 3
local perf = {}          -- playerNum -> state
local squareSrc = {}     -- playerNum -> { cell, src, get }

local function perfOn(key)
    local c = rawget(_G, "WKT_PerfPatchConfig")
    return not (type(c) == "table" and c[key] == false)
end

local function jarOn()
    return type(wktDrainGroundKeys) == "function" and type(wktGroundKeysNear) == "function"
        and perfOn("groundRender")
end

--- The chunk map's getGridSquare has one (int,int,int) overload, the cell's three: Kahlua
--- resolves less per call. Probed once per player and cell; the cell's is the fallback.
local function squaresFor(playerNum, cell)
    local s = squareSrc[playerNum]
    if s and s.cell == cell then return s.src, s.get end
    local src, get = cell, cell.getGridSquare
    local ok, cm = pcall(cell.getChunkMap, cell, playerNum)
    if ok and cm and pcall(cm.getGridSquare, cm, 0, 0, 0) then src, get = cm, cm.getGridSquare end
    squareSrc[playerNum] = { cell = cell, src = src, get = get }
    return src, get
end

--- The same tests sweepColumn applies, for a world object found another way.
local function accept(worldObj, playerNum, wx, wy, pz, limit)
    local item = worldObj:getItem()
    if not (WKT.isKeyItem(item) and WKT.getKeyId(item) ~= -1 and not WKT.isFound(item)) then return nil end
    local sq = worldObj:getSquare()
    if sq == nil or sq:getZ() ~= pz or not sq:isCouldSee(playerNum) then return nil end
    local dx, dy = sq:getX() + 0.5 - wx, sq:getY() + 0.5 - wy
    local d2 = dx * dx + dy * dy
    if d2 > limit then return nil end
    return makeEntry(worldObj, item, math.sqrt(d2))
end

local function announce(published, entry)
    if entry and #published < MAX_LABELS and not alreadyListed(published, entry.item) then
        table.insert(published, entry)
    end
end

--- End of a full sweep: the nearest MAX_LABELS become the list, fade states are pruned to it.
local function publish(playerNum, found)
    table.sort(found, function(a, b) return a.dist < b.dist end)
    local kept = {}
    for i = 1, math.min(#found, MAX_LABELS) do kept[i] = found[i] end
    Ground.entries[playerNum] = kept
    local live = {}
    for _, entry in ipairs(kept) do live[entry.item] = fade[entry.item] end
    fade = live
end

--- The screen as seen from the world: iso -> screen is affine on one floor, so three
--- projections give it. Padded so a label above a key just off the bottom edge still counts.
local function viewOf(playerNum, px, py, pz)
    local x0, y0 = math.floor(px), math.floor(py)
    local sx, sy = isoToScreenX(playerNum, x0, y0, pz), isoToScreenY(playerNum, x0, y0, pz)
    local v = {
        ax = isoToScreenX(playerNum, x0 + 1, y0, pz) - sx, ay = isoToScreenY(playerNum, x0 + 1, y0, pz) - sy,
        bx = isoToScreenX(playerNum, x0, y0 + 1, pz) - sx, by = isoToScreenY(playerNum, x0, y0 + 1, pz) - sy,
    }
    v.c, v.d = sx - v.ax * x0 - v.bx * y0, sy - v.ay * x0 - v.by * y0
    local pad = 64 + 4 * math.abs(v.ay)
    v.l, v.t = getPlayerScreenLeft(playerNum) - pad, getPlayerScreenTop(playerNum) - pad
    v.r, v.b = v.l + getPlayerScreenWidth(playerNum) + 2 * pad, v.t + getPlayerScreenHeight(playerNum) + 2 * pad
    return v
end

--- Narrows rows lo..hi of column x to those whose square centre lands inside v.
local function clipColumn(v, x, lo, hi)
    local X = x + 0.5
    local limits = { { v.bx, v.ax * X + v.c, v.l, v.r }, { v.by, v.ay * X + v.d, v.t, v.b } }
    for i = 1, 2 do
        local k, m, L, H = limits[i][1], limits[i][2], limits[i][3], limits[i][4]
        if math.abs(k) < 1e-6 then
            if m < L or m > H then return nil end
        else
            local y1, y2 = (L - m) / k - 0.5, (H - m) / k - 0.5
            if y1 > y2 then y1, y2 = y2, y1 end
            lo, hi = math.max(lo, math.ceil(y1)), math.min(hi, math.floor(y2))
        end
    end
    if lo > hi then return nil end
    return lo, hi
end

local function inView(v, x, y)
    local X, Y = x + 0.5, y + 0.5
    local sx, sy = v.ax * X + v.bx * Y + v.c, v.ay * X + v.by * Y + v.d
    return sx >= v.l and sx <= v.r and sy >= v.t and sy <= v.b
end

--- One column of a Lua full sweep: like sweepColumn, plus clipping and the item-square list.
local function perfColumn(sweep, playerNum, published)
    local col = sweep.columns[sweep.next]
    local x = sweep.px + col
    local dx = x + 0.5 - sweep.wx
    local span = math.floor(math.sqrt(math.max(0, sweep.limit - dx * dx)))
    local lo, hi = sweep.py - span, sweep.py + span
    if sweep.view then lo, hi = clipColumn(sweep.view, x, lo, hi) end
    if lo == nil then return end
    local src, get = sweep.src, sweep.get
    for y = lo, hi do
        local square = get(src, x, y, sweep.pz)
        if square ~= nil and square:isCouldSee(playerNum) then
            local objects = square:getWorldObjects()
            local n = objects and objects:size() or 0
            if n > 0 then
                sweep.squares[#sweep.squares + 1] = x
                sweep.squares[#sweep.squares + 1] = y
            end
            for i = 0, n - 1 do
                local worldObj = objects:get(i)
                local item = worldObj and worldObj:getItem() or nil
                if WKT.isKeyItem(item) and WKT.getKeyId(item) ~= -1 and not WKT.isFound(item) then
                    local dy = y + 0.5 - sweep.wy
                    local entry = makeEntry(worldObj, item, math.sqrt(dx * dx + dy * dy))
                    table.insert(sweep.found, entry)
                    announce(published, entry)
                end
            end
        end
    end
end

--- Between full sweeps: read again only the squares that held items.
local function recheck(st, playerNum, published)
    local src, get = st.src, st.get
    local list, view, limit = st.squares, st.view, st.radius * st.radius
    for i = 1, #list, 2 do
        local x, y = list[i], list[i + 1]
        local dx, dy = x + 0.5 - st.wx, y + 0.5 - st.wy
        if dx * dx + dy * dy <= limit and (not view or inView(view, x, y)) then
            local square = get(src, x, y, st.pz)
            if square ~= nil and square:isCouldSee(playerNum) then
                local objects = square:getWorldObjects()
                for j = 0, (objects and objects:size() or 0) - 1 do
                    local worldObj = objects:get(j)
                    if worldObj then announce(published, accept(worldObj, playerNum, st.wx, st.wy, st.pz, limit)) end
                end
            end
        end
    end
end

function Ground.advance(playerNum, playerObj)
    local jar = jarOn()
    -- the render buffer is drained every frame, so it never holds stale objects
    local drained = nil
    if jar then
        local ok, res = pcall(wktDrainGroundKeys)
        if ok then drained = res end
    end

    if not WKT.showing(WKT.SHOW_GROUND) or not playerObj or playerObj:isDead() then
        Ground.entries[playerNum] = {}
        perf[playerNum] = nil
        return
    end

    local cell = getCell()
    if cell == nil then return end

    local now = getTimestampMs()
    local radius = math.floor(WKT.mopt("GroundKeyRadius", 30))
    if radius < 1 then
        Ground.entries[playerNum] = {}
        perf[playerNum] = nil
        return
    end

    local wx, wy = playerObj:getX(), playerObj:getY()
    local pz = math.floor(playerObj:getZ())
    local zoom = getCore():getZoom(playerNum)
    local published = Ground.entries[playerNum] or {}
    Ground.entries[playerNum] = published

    local st = perf[playerNum]
    local due = st == nil or st.fullAt == nil or now - st.fullAt >= PERF_FULL_MS or st.radius ~= radius or st.pz ~= pz
        or math.abs(wx - st.wx) >= PERF_MOVE or math.abs(wy - st.wy) >= PERF_MOVE
        or math.abs(zoom - st.zoom) > 0.01 * st.zoom

    if jar then
        local limit = radius * radius
        if drained then
            for i = 1, #drained do announce(published, accept(drained[i], playerNum, wx, wy, pz, limit)) end
        end
        if due then
            local ok, near = pcall(wktGroundKeysNear, wx, wy, pz, radius, playerNum)
            if ok and type(near) == "table" then
                local found = {}
                for i = 1, #near do
                    local entry = accept(near[i], playerNum, wx, wy, pz, limit)
                    if entry then found[#found + 1] = entry end
                end
                publish(playerNum, found)
                perf[playerNum] = { fullAt = now, wx = wx, wy = wy, pz = pz, radius = radius, zoom = zoom }
                return
            end
            -- the native sweep failed: fall through to the Lua one
        else
            return
        end
    end

    local sweep = st and st.sweep
    if sweep == nil and due then
        local columns = { 0 }
        for d = 1, radius do
            columns[#columns + 1] = d
            columns[#columns + 1] = -d
        end
        local src, get = squaresFor(playerNum, cell)
        sweep = {
            wx = wx, wy = wy, px = math.floor(wx), py = math.floor(wy), pz = pz,
            limit = radius * radius, columns = columns, next = 1,
            perSlice = math.max(1, math.ceil(#columns / SLICES)),
            found = {}, squares = {}, src = src, get = get,
            view = perfOn("groundClip") and viewOf(playerNum, wx, wy, pz) or nil,
        }
        st = st or {}
        st.sweep = sweep
        perf[playerNum] = st
    end

    if sweep then
        local ok = pcall(function()
            local last = math.min(#sweep.columns, sweep.next + sweep.perSlice - 1)
            while sweep.next <= last do
                perfColumn(sweep, playerNum, published)
                sweep.next = sweep.next + 1
            end
        end)
        if not ok or sweep.next > #sweep.columns then
            publish(playerNum, sweep.found)
            perf[playerNum] = {
                fullAt = now, wx = sweep.wx, wy = sweep.wy, pz = pz, radius = radius, zoom = zoom,
                squares = sweep.squares, view = sweep.view, src = sweep.src, get = sweep.get,
                recheckAt = now,
            }
        end
        return
    end

    if st.squares and now - st.recheckAt >= PERF_RECHECK_MS then
        st.recheckAt = now
        pcall(recheck, st, playerNum, published)
    end
end

--- Draws the labels. Coordinates are element-relative, so the caller passes
--- the viewport origin it has already worked out.
---
--- Distance is measured here, every frame, rather than being read back from
--- the sweep. The sweep is half a second wide, and a brightness that only
--- changed twice a second while you walked was the other half of why this
--- felt sluggish.
function Ground.draw(ui, playerNum, ox, oy, w, h, tilePx, fontHeight)
    local entries = Ground.get(playerNum)
    if #entries == 0 then return end

    local playerObj = getSpecificPlayer(playerNum)
    if playerObj == nil then return end

    local range = math.max(1, WKT.mopt("GroundKeyRadius", 30))
    local px, py = playerObj:getX(), playerObj:getY()
    local now = getTimestampMs()

    for _, entry in ipairs(entries) do
        local dx, dy = entry.x - px, entry.y - py
        local dist = math.sqrt(dx * dx + dy * dy)

        -- full strength close by, easing off over the last few tiles of the
        -- range so leaving it behind is a fade rather than a disappearance
        local edge = math.min(1, (range - dist) / EDGE_TILES)
        local near = 0.55 + 0.45 * math.max(0, math.min(1, 1 - dist / range))

        -- a key picked up since the last sweep is no longer a find, and its
        -- label should not linger over the spot it came from
        if edge > 0 and not WKT.isFound(entry.item) then
            local sx = isoToScreenX(playerNum, entry.x, entry.y, entry.z) - ox
            local sy = isoToScreenY(playerNum, entry.x, entry.y, entry.z) - oy

            if sx > 0 and sx < w and sy > 0 and sy < h then
                local state = fade[entry.item]
                if state == nil then
                    state = { t0 = now }
                    fade[entry.item] = state
                end
                local grown = math.min(1, (now - state.t0) / FADE_MS)

                local text = entry.name
                local tw = getTextManager():MeasureStringX(UIFont.Small, text)
                -- just above the key itself, where a floor item's own
                -- tooltip sits
                WKT.outlineText(ui, text, sx - tw / 2, sy - tilePx * 0.5 - fontHeight,
                    entry.r, entry.g, entry.b, edge * near * grown, UIFont.Small)
            end
        end
    end
end
