--==========================================================
-- [BGI] Carried Generator Preview Overlay
--==========================================================
-- Draws a translucent generator power range ring around the player
-- while they are carrying a generator (held in both hands).
--==========================================================

if not BGI_PowerRange then
    DebugLog.log(DebugType.General, "[BGI] PowerRange module missing; preview overlay disabled.")
    return
end

local drawEdgeList     = BGI_PowerRange.drawEdgeList
local ensureStencil    = BGI_PowerRange.ensureStencil
local getPerGenColor   = BGI_PowerRange.getPerGenColor
local isOverlayEnabled = BGI_PowerRange.isOverlayEnabled

local _cache = { edges = {}, px = nil, py = nil, pz = nil, R = nil }

-- ----------------------------------------------------------
-- Helper: check if the player is holding a generator
-- ----------------------------------------------------------
-- Returns true if the player is holding a generator in BOTH hands
-- Compatible with B42.12 (string tags) and B42.13 (ItemTag registry)
local function isHoldingGenerator(player)
    if not player then return false end

    local prim = player:getPrimaryHandItem()
    local sec  = player:getSecondaryHandItem()
    if not (prim and sec) then return false end
    if not (prim.hasTag and sec.hasTag) then return false end

    if ItemTag and ItemTag.GENERATOR then
        return prim:hasTag(ItemTag.GENERATOR)
           and sec:hasTag(ItemTag.GENERATOR)
    end

    -- B42.12 fallback
    return prim:hasTag("Generator")
       and sec:hasTag("Generator")
end

-- ----------------------------------------------------------
-- Helper: build or reuse translated edge list
-- ----------------------------------------------------------
local function buildEdges(px, py, pz, R)
    local buf = _cache
    if buf.px == px and buf.py == py and buf.pz == pz and buf.R == R and #buf.edges > 0 then
        return buf.edges
    end

    ensureStencil(R, BGI_PowerRange.ProbePad or 0)
    local eo = BGI_Stencil.edgeOffsets
    local edges = buf.edges
    local n = #eo
    for i = 1, n do
        local e = eo[i]
        local slot = edges[i]
        if slot then
            slot[1] = px + e[1]; slot[2] = py + e[2]
        else
            edges[i] = { px + e[1], py + e[2] }
        end
    end
    -- truncate any stale trailing entries
    for i = n + 1, #edges do edges[i] = nil end

    buf.px, buf.py, buf.pz, buf.R = px, py, pz, R
    return edges
end

-- ----------------------------------------------------------
-- Event: draw live preview ring for held generators
-- ----------------------------------------------------------
if not BGI_PowerRange._heldPreview then
    Events.OnPlayerUpdate.Add(function(player)
        -- respect overlay master toggle
        if not isOverlayEnabled() then return end
        -- if main overlay active (window open), let it handle drawing
        if BGI_PowerRange.Enabled == true then return end
        if not player then return end
        if not isHoldingGenerator(player) then return end

        local sq = player:getSquare()
        if not sq then return end
        local px, py, pz = sq:getX(), sq:getY(), sq:getZ()
        local R = BGI_PowerRange:_getEffectiveTileRange()

        local edges = buildEdges(px, py, pz, R)
        if not edges or #edges == 0 then return end

        -- Slightly translucent “planning” color
        local c = getPerGenColor(true)
        local col = { r = c.r, g = c.g, b = c.b, a = math.max(0.02, (c.a or 0.08) * 0.75) }

        drawEdgeList(edges, pz, col)
        
    end)
    BGI_PowerRange._heldPreview = true
    DebugLog.log(DebugType.General, "[BGI] Carried generator preview module initialized.")
end
