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

