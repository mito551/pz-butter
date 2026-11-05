--[[
    WKT_Perf: load-after patch for Whose Key Is This? (WKT_Pointer.lua).

    Same drawing, same options, fewer calculations per frame:
      N1  WKT.mopt answers from a table that is cleared twice a second
      N2  label text and its measured width are cached per key
      N3  no per-frame closures or heading tables
      N4  vehicle extents are read once per vehicle, not once per frame
      N5  Ground.get hands out one shared empty table; render asks it once
      N6  refreshTracked reuses its entry tables instead of rebuilding them
      N7  Ground.advance is skipped while ground labels are off (it cleared
          the list with a new table every frame)
      U1  iso->screen is affine at a fixed height, so the projection is
          measured three times per frame and everything else is arithmetic

    The original file keeps its locals (arrowStandoff, aimPoint,
    drawGroundArrow, animatedLabelPos), which a patch cannot reach, so
    render is reimplemented here and the originals simply stop being called.
    Not reachable: updatePointers is a local on OnTick, so its own per-frame
    Ground.get / WKT.showing calls stay (both are cheap now via N1 and N5).
]]

require "ISUI/ISUIElement"
require "WKT_Shared"
require "WKT_GroundKeys"
require "WKT_KeyRings"
require "WKT_Pointer"

if WKT.PerfPatched then return end
WKT.PerfPatched = true

local FONT_HGT_SMALL = getTextManager():getFontHeight(UIFont.Small)
local ARROW_ASPECT = 0.68
local ARROW_BASE_PX, ARROW_STEP_PX = 26, 13
local EDGE_PAD = 6
local HEAD_TILES = 1.5
local HOLD_DISTANCE = 1.5
local MOVE_MS = 380
local MOPT_TTL_MS = 500

---------------------------------------------------------------------------
-- N1: option lookups
---------------------------------------------------------------------------

local origMopt = WKT.mopt
local moptValue, moptDefault, moptHas = {}, {}, {}

local function clearMopt()
    moptValue, moptDefault, moptHas = {}, {}, {}
end

function WKT.mopt(id, default)
    if moptHas[id] and moptDefault[id] == default then
        return moptValue[id]
    end
    local value = origMopt(id, default)
    moptValue[id], moptDefault[id], moptHas[id] = value, default, true
    return value
end

-- OnTickEvenPaused fires every frame, paused or not, so options changed from
-- the pause menu apply at once
local nextMoptClear = 0
Events.OnTickEvenPaused.Add(function()
    local now = getTimestampMs()
    if now < nextMoptClear then return end
    nextMoptClear = now + MOPT_TTL_MS
    clearMopt()
end)

-- something just changed on purpose (menu, panel): do not serve stale values
local origRefreshPointers = WKT.refreshPointers
function WKT.refreshPointers()
    clearMopt()
    return origRefreshPointers()
end

---------------------------------------------------------------------------
-- N5: Ground.get without a fresh empty table per call
---------------------------------------------------------------------------

local EMPTY = {}
function WKT.Ground.get(playerNum)
    return WKT.Ground.entries[playerNum] or EMPTY
end

-- N7: the original clears once on the way to "off"; after that, calls are no-ops
local origAdvance = WKT.Ground.advance
local groundOff = {}
function WKT.Ground.advance(playerNum, playerObj)
    local off = not WKT.showing(WKT.SHOW_GROUND) or not playerObj or playerObj:isDead()
    if off and groundOff[playerNum] then
        -- keep WKTPerf.jar's render buffer empty while labels are off
        if type(wktDrainGroundKeys) == "function" then pcall(wktDrainGroundKeys) end
        return
    end
    groundOff[playerNum] = off
    return origAdvance(playerNum, playerObj)
end

---------------------------------------------------------------------------
-- N6: refreshTracked, entries reused in place
---------------------------------------------------------------------------

function WKT_Pointer:refreshTracked()
    local tracked = self.tracked
    for i = #tracked, 1, -1 do tracked[i] = nil end

    local playerObj = getSpecificPlayer(self.playerNum)
    if not playerObj or playerObj:isDead() then return end

    local pool = self.trackedPool
    if pool == nil then
        pool = {}
        self.trackedPool = pool
    end
    local gen = (self.trackedGen or 0) + 1
    self.trackedGen = gen

    for _, item in ipairs(WKT.getPlayerKeys(playerObj)) do
        if WKT.isPointing(item) then
            local data = WKT.getData(item)
            if data and WKT.canReadLocation(playerObj, data, item) then
                WKT.markRevealed(item)
                local r, g, b = WKT.markerColor(playerObj, item, data)
                local entry = pool[item]
                if entry == nil then
                    entry = { item = item, key = item, color = {} }
                    pool[item] = entry
                end
                entry.name = item:getName()
                local c = entry.color
                c.r, c.g, c.b = r, g, b
                entry.gen = gen
                tracked[#tracked + 1] = entry
            end
        end
    end

    -- keys no longer followed leave the pool
    for item, entry in pairs(pool) do
        if entry.gen ~= gen then pool[item] = nil end
    end

    -- same one-at-a-time trim as the original
    if WKT.SINGLE_POINTER and #tracked > 1 then
        local last = tracked[#tracked]
        for i = 1, #tracked - 1 do
            WKT.setPointing(tracked[i].item, false)
        end
        for i = #tracked, 1, -1 do tracked[i] = nil end
        tracked[1] = last
    end

    -- a lost key ring is followed without being asked, after the trim
    for _, ring in ipairs(WKT.getRingEntries(playerObj)) do
        tracked[#tracked + 1] = ring
    end
end

---------------------------------------------------------------------------
-- U1: per-frame projection basis
--
-- At a fixed height the engine's iso->screen map is affine in (x, y), so
-- three real calls give the screen position of any ground point by
-- arithmetic. Set once per frame at the top of render.
---------------------------------------------------------------------------

local refX, refY = 0, 0
local p0x, p0y = 0, 0
local exx, exy = 0, 0   -- screen step for +1 world x
local eyx, eyy = 0, 0   -- screen step for +1 world y

local function projX(x, y) return p0x + exx * (x - refX) + eyx * (y - refY) end
local function projY(x, y) return p0y + exy * (x - refX) + eyy * (y - refY) end

---------------------------------------------------------------------------
-- N4: arrow stand-off, one vehicle read per vehicle
---------------------------------------------------------------------------

local cachedVehicle, cachedReach = nil, 3.2

local function vehicleReach(vehicle)
    if vehicle == cachedVehicle then return cachedReach end
    local reach = 3.2
    local ok, extents = pcall(function() return vehicle:getScript():getExtents() end)
    if ok and extents then
        local okv, ex, ey = pcall(function() return extents:x(), extents:y() end)
        ex, ey = tonumber(ex), tonumber(ey)
        if okv and ex and ey then
            reach = math.max(ex, ey) * 0.6 + 1.4
        end
    end
    cachedVehicle, cachedReach = vehicle, reach
    return reach
end

--- same answer as the original, but once per frame rather than per key
local function standoffFor(playerObj, px, py)
    local vehicle = playerObj:getVehicle()
    if vehicle == nil then return px, py, 2.2 end
    return vehicle:getX(), vehicle:getY(), vehicleReach(vehicle)
end

---------------------------------------------------------------------------
-- The arrow
---------------------------------------------------------------------------

local lastDir = {}
local arrowTex = nil

local function drawGroundArrow(self, entry, data, a, r, g, b, size, px, py, wx, wy, standoff)
    -- aim point: nearest point of a building footprint, nil when inside it
    local aimX, aimY = data.x, data.y
    local w, h = data.w, data.h
    if w and h and w > 1 and h > 1 then
        local hw, hh = w / 2, h / 2
        aimX = math.max(data.x - hw, math.min(data.x + hw, px))
        aimY = math.max(data.y - hh, math.min(data.y + hh, py))
        if math.abs(aimX - px) < 0.01 and math.abs(aimY - py) < 0.01 then
            aimX, aimY = nil, nil
        end
    end

    local targetX = aimX or data.x
    local targetY = aimY or data.y
    local ddx, ddy = targetX - wx, targetY - wy
    local d = math.sqrt(ddx * ddx + ddy * ddy)

    local dx, dy
    if aimX and d >= HOLD_DISTANCE then
        dx, dy = (aimX - wx) / d, (aimY - wy) / d
        local held = lastDir[entry.key]
        if held == nil then
            held = {}
            lastDir[entry.key] = held
        end
        held[1], held[2] = dx, dy
    else
        local held = lastDir[entry.key]
        if held == nil then return nil end
        dx, dy = held[1], held[2]
    end

    -- heading on screen from a one-tile ground step; slen is the on-screen
    -- size of a tile along it
    local baseX, baseY = projX(wx, wy), projY(wx, wy)
    local vx = exx * dx + eyx * dy
    local vy = exy * dx + eyy * dy
    local slen = math.sqrt(vx * vx + vy * vy)
    if slen < 0.001 then return nil end
    local sx, sy = vx / slen, vy / slen

    -- stand-off in pixels: the projection is linear, so it is slen * standoff
    local offPx = slen * standoff
    if offPx < 26 then offPx = 26 end

    local length = ARROW_BASE_PX + size * ARROW_STEP_PX
    local halfLen = length / 2
    local halfWid = length * ARROW_ASPECT / 2

    local worldLen = length / slen
    local clearance = standoff + worldLen + 0.5
    local nearFade = 1
    if d < clearance then
        nearFade = (d - standoff) / math.max(0.01, clearance - standoff)
        if nearFade <= 0.02 then return nil end
        if nearFade > 1 then nearFade = 1 end
        a = a * nearFade
    end

    local cx = baseX + sx * (offPx + halfLen)
    local cy = baseY + sy * (offPx + halfLen)
    local qx, qy = -sy, sx
    local tx1, ty1 = cx - sx * halfLen, cy - sy * halfLen
    local tx2, ty2 = cx + sx * halfLen, cy + sy * halfLen
    self:drawTextureAllPoint(arrowTex,
        tx1 + qx * halfWid, ty1 + qy * halfWid,
        tx2 + qx * halfWid, ty2 + qy * halfWid,
        tx2 - qx * halfWid, ty2 - qy * halfWid,
        tx1 - qx * halfWid, ty1 - qy * halfWid,
        r, g, b, a)

    return nearFade
end

---------------------------------------------------------------------------
-- Label movement (unchanged apart from the cube)
---------------------------------------------------------------------------

local labelAnim = {}

local function animatedLabelPos(item, mode, ax, ay, now)
    local state = labelAnim[item]

    if state == nil or state.mode ~= mode then
        local fromX = state and state.x or ax
        local fromY = state and state.y or ay
        state = { mode = mode, t0 = state and now or 0,
                  fromX = fromX, fromY = fromY, x = fromX, y = fromY }
        labelAnim[item] = state
    end

    local u = 1
    if state.t0 > 0 then
        u = (now - state.t0) / MOVE_MS
        if u < 0 then u = 0 elseif u > 1 then u = 1 end
    end

    local v = 1 - u
    local e = 1 - v * v * v
    state.x = state.fromX + (ax - state.fromX) * e
    state.y = state.fromY + (ay - state.fromY) * e

    if u >= 1 then
        state.fromX, state.fromY, state.t0 = ax, ay, 0
        state.x, state.y = ax, ay
    end
    return state.x, state.y
end

---------------------------------------------------------------------------
-- N2: label text and width, rebuilt only when what it shows changes
---------------------------------------------------------------------------

local function distanceBucket(dist)
    if dist == nil then return -1 end
    if dist >= 1000 then return 1e7 + math.floor(dist / 100 + 0.5) end
    return math.floor(dist + 0.5)
end

local function labelFor(entry, data, dist)
    local bucket = distanceBucket(dist)
    local precise = data.precise and true or false
    if entry.lbBucket ~= bucket or entry.lbName ~= entry.name
            or entry.lbPrecise ~= precise then
        local label = entry.name .. "  " .. WKT.formatDistance(dist)
        if not precise then label = label .. " ?" end
        entry.lbText = label
        entry.lbWidth = getTextManager():MeasureStringX(UIFont.Small, label)
        entry.lbBucket, entry.lbName, entry.lbPrecise = bucket, entry.name, precise
    end
    return entry.lbText, entry.lbWidth
end

---------------------------------------------------------------------------
-- render
---------------------------------------------------------------------------

function WKT_Pointer:render()
    if not WKT.isEnabled() then return end

    local pn = self.playerNum
    local tracked = self.tracked
    if #tracked == 0 and #WKT.Ground.get(pn) == 0 then return end

    local playerObj = getSpecificPlayer(pn)
    if not playerObj or playerObj:isDead() then return end

    if ISWorldMap_instance and ISWorldMap_instance:isVisible() then return end

    local ox = getPlayerScreenLeft(pn)
    local oy = getPlayerScreenTop(pn)
    local w = getPlayerScreenWidth(pn)
    local h = getPlayerScreenHeight(pn)

    local pz = playerObj:getZ()
    local px, py = playerObj:getX(), playerObj:getY()

    -- U1: the only projection calls of the frame
    refX, refY = px, py
    p0x = isoToScreenX(pn, px, py, pz)
    p0y = isoToScreenY(pn, px, py, pz)
    exx = isoToScreenX(pn, px + 1, py, pz) - p0x
    exy = isoToScreenY(pn, px + 1, py, pz) - p0y
    eyx = isoToScreenX(pn, px, py + 1, pz) - p0x
    eyy = isoToScreenY(pn, px, py + 1, pz) - p0y

    local footX, footY = p0x - ox, p0y - oy
    local tilePx = math.sqrt(exx * exx + exy * exy)
    if tilePx < 1 then tilePx = 1 end

    WKT.Ground.draw(self, pn, ox, oy, w, h, tilePx, FONT_HGT_SMALL)

    if #tracked == 0 then return end
    if not WKT.showing(WKT.SHOW_POINTER) then return end

    arrowTex = arrowTex or getTexture("media/ui/arrow_right.png")
    if arrowTex == nil then return end

    local headX = footX
    local headY = footY - tilePx * HEAD_TILES
    local stacked = 0
    local now = getTimestampMs()

    local size      = WKT.mopt("ArrowSize", 5)
    local baseAlpha = WKT.mopt("PointerOpacity", 85) / 100
    local fadeOn    = WKT.mopt("PointerFadeByDistance", true) == true
    local fadeStart = math.max(1, WKT.mopt("PointerFadeStart", 50))
    local floorAlpha = math.min(WKT.mopt("ArrowFadeFloor", 20) / 100, baseAlpha)

    local wx, wy, standoff = standoffFor(playerObj, px, py)

    for i = 1, #tracked do
        local entry = tracked[i]
        local data = entry.data or WKT.getData(entry.item)
        if data then
            local dist = WKT.distanceTo(playerObj, data)

            local a = baseAlpha
            if fadeOn then
                local nearness = 1 - ((dist or 0) / fadeStart)
                if nearness < 0 then nearness = 0 elseif nearness > 1 then nearness = 1 end
                a = floorAlpha + (baseAlpha - floorAlpha) * nearness
            end
            if not data.precise then a = a * 0.7 end

            local c = entry.color
            local r, g, b = c.r, c.g, c.b

            local nearFade = drawGroundArrow(self, entry, data, a, r, g, b, size,
                px, py, wx, wy, standoff)
            if nearFade then
                a = a * nearFade

                local la = math.max(a, 0.3)
                local label, tw = labelFor(entry, data, dist)

                local tx = projX(data.x, data.y) - ox
                local ty = projY(data.x, data.y) - oy
                if data.walker == true then
                    ty = ty - tilePx * HEAD_TILES
                end

                local visible = tx >= 0 and tx <= w and ty >= 0 and ty <= h

                local ax, ay
                if visible then
                    ax, ay = tx, ty
                else
                    ax = headX
                    ay = headY - stacked * (FONT_HGT_SMALL + 3)
                    stacked = stacked + 1
                end

                ax, ay = animatedLabelPos(entry.key, visible and "target" or "head",
                    ax, ay, now)

                local lx = math.max(EDGE_PAD, math.min(w - tw - EDGE_PAD, ax - tw / 2))
                local ly = math.max(EDGE_PAD, math.min(h - FONT_HGT_SMALL - EDGE_PAD,
                    ay - FONT_HGT_SMALL / 2))
                WKT.outlineText(self, label, lx, ly, r, g, b, la, UIFont.Small)
            end
        end
    end
end

---------------------------------------------------------------------------
-- Item 7 / item 2 helpers: isFavorite and getPlayerKeys replacements
---------------------------------------------------------------------------

local origIsFavorite = WKT.isFavorite
local origGetPlayerKeys = WKT.getPlayerKeys

local reconciled = {}
local reconciledCount = 0
local keysMemo = {}

function WKT.isFavorite(item)
    if not item then return false end

    local engine = false
    local ok, fav = pcall(item.isFavorite, item)
    if ok and fav == true then engine = true end

    local okHas, has = pcall(item.hasModData, item)
    if okHas and has ~= true and not engine then return false end

    local md = item:getModData()

    if not reconciled[item] then
        if reconciledCount > 4000 then
            reconciled = {}
            reconciledCount = 0
        end
        reconciled[item] = true
        reconciledCount = reconciledCount + 1

        if md ~= nil and md.WKT_fav == true and not engine then
            pcall(item.setFavorite, item, true, true)
            return true
        end
    end

    if md ~= nil then
        if engine then
            md.WKT_fav = true
        elseif md.WKT_fav ~= nil then
            md.WKT_fav = nil
        end
    end
    return engine
end

local origRefreshPointers2 = WKT.refreshPointers
function WKT.refreshPointers()
    clearMopt()
    keysMemo = {}
    return origRefreshPointers2()
end

function WKT.getPlayerKeys(playerObj)
    if not playerObj then
        local out = {}
        return out
    end
    local now = getTimestampMs()
    local c = keysMemo[playerObj]
    if c and (now - c.at) < 1000 then
        local out = {}
        for i = 1, #c.list do out[i] = c.list[i] end
        return out
    end

    local inv = playerObj:getInventory()
    local src = nil
    if type(wktKeysIn) == "function" and inv then
        local ok, list = pcall(wktKeysIn, inv)
        if ok and list then src = list end
    end
    if not src and type(origGetPlayerKeys) == "function" then
        src = origGetPlayerKeys(playerObj)
    end
    if not src then src = {} end

    local out = {}
    for _, item in ipairs(src) do
        if item and WKT.getKeyId(item) ~= -1 and WKT.isCarriedBy(playerObj, item) then
            table.insert(out, item)
        end
    end

    keysMemo[playerObj] = { at = now, list = out }

    local ret = {}
    for i = 1, #out do ret[i] = out[i] end
    return ret
end
