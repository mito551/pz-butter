--[[--------------------------------------------------------------------
BGI_PowerRange.lua — Better Generator Info (BGI)
Build 42.x (SP)

Purpose
  • Show a per-generator power range ring even when the generator is OFF,
    using the engine's reach test: IsoGenerator.isPoweringSquare(...).
  • Draw the exact OUTER perimeter (unpowered tiles with ≥1 powered neighbour).
  • Optional union overlay (all currently powered tiles) in powder blue.

Notes
  • Colours: main ring uses getCore():getGoodHighlitedColor() / getBadHighlitedColor().
  • Union overlay is a custom powder blue with low alpha.
  • Rendering is an overlay (Lua cannot draw “behind” world sprites).
----------------------------------------------------------------------]]--

if BGI_PowerRange ~= nil then
    if BGI_PowerRange.Stop then BGI_PowerRange.Stop() end
end

BGI_PowerRange = {
    -- ===== Config =====
    PerGenAlpha          = 0.08,
    DefaultTileRange     = 20,           -- Fallback if SandboxVars.GeneratorTileRange is absent
    ProbePad             = 2,            -- Sweep R+pad tiles; ensures the OUTER ring lies within the scan
    GenVerticalLimit     = 3,
    UnionOverlayAlpha    = 0.28,
    DebugUnionRebuilds   = false,        -- Optional overlay diagnostics
    UnionRefreshStride   = 120,          -- frames between union (haveElectricity) refreshes while visible
    -- Performance knobs
    UnionBuildCurrentZOnly = true,       -- build union edges only for the player's current Z
    -- Visual policy
    UnionProjectToCurrentZ = true,       -- If true, when building union for player Z with no gens on that Z, project all active gens onto player Z
    UnionRespectVerticalLimits = true,   -- When projecting: only include generators whose ΔZ to the target Z lies _BGI_withinVerticalLimits

    -- ===== Runtime =====
    Enabled              = false,
    TargetGen            = nil,          -- IsoGenerator
    ShowPerGen           = true,         -- user toggle (persisted per player) for the focused-gen ring
    ShowUnion            = false,        -- user toggle (persisted per player)
    PerGenEdgesByZ       = {},           -- [z] = { {x,y}, ... }
    UnionEdgesByZ        = {},           -- [z] = { {x,y}, ... }
    UnionPoweredByZ      = {},           -- [z] = set { [x][y]=true } nested set of powered tiles (persistent across frames)
    _needsPerGenRebuild  = false,
    _needsUnionRebuild   = false,
    _lastGenOnState      = nil,
    _unionGenStates      = {},           -- map[obj] = last seen isActivated() (true/false); drives state-change rebuilds
    _overridesApplied    = false,
    _unionPollTicker      = 0,           -- counts down to next ON/OFF poll while Union visible
    _lastPlayerZ         = nil,
}

-----------------------------------------------------------------------
-- Generator Registry (event-driven, Build 42.x, SP)
-- Keeps a list of loaded generators without scanning the world.
-- Safe Alternative — replaces predictive sniff/BFS.
-----------------------------------------------------------------------
local BGI_GenRegistry = {
    live = {},   -- array of { obj=IsoGenerator, x, y, z }
    byKey = {},  -- map "x|y|z" -> entry
}

local function _k3(x,y,z) return tostring(x).."|"..tostring(y).."|"..tostring(z or 0) end
local function _k2(x,y) return tostring(x).."|"..tostring(y) end
local function _isGen(o) return o and instanceof and instanceof(o, "IsoGenerator") end

local function _addGen(obj)
    if not _isGen(obj) then return false end
    local sq = obj:getSquare(); if not sq then return false end
    local x,y,z = sq:getX(), sq:getY(), sq:getZ()
    local k = _k3(x,y,z)
    local existing = BGI_GenRegistry.byKey[k]
    if existing and existing.obj == obj then return false end
    if existing then
        for i = #BGI_GenRegistry.live, 1, -1 do
            if BGI_GenRegistry.live[i] == existing then
                table.remove(BGI_GenRegistry.live, i)
                break
            end
        end
    end
    local rec = { obj=obj, x=x, y=y, z=z }
    table.insert(BGI_GenRegistry.live, rec)
    BGI_GenRegistry.byKey[k] = rec
    return true
end

local function _removeGen(obj)
    if not _isGen(obj) then return end
    for i=#BGI_GenRegistry.live,1,-1 do
        local rec = BGI_GenRegistry.live[i]
        if rec.obj == obj then
            BGI_GenRegistry.byKey[_k3(rec.x, rec.y, rec.z)] = nil
            table.remove(BGI_GenRegistry.live, i)
            break
        end
    end
end

-- Public getters (don’t expose internals)
function BGI_GenRegistry.getAll()
    return BGI_GenRegistry.live
end

-- Weak-keyed map[obj] = last seen isActivated(); created on demand.
local function _ensureStates()
    local states = BGI_PowerRange._unionGenStates
    if not states then
        states = setmetatable({}, { __mode = "k" })
        BGI_PowerRange._unionGenStates = states
    end
    return states
end

-- 8-neighbour offsets
local _BGI_NEIGHBORS = {
    {-1,-1},{0,-1},{1,-1},
    {-1, 0},       {1, 0},
    {-1, 1},{0, 1},{1, 1},
}

-----------------------------------------------------------------------
-- Helpers
-----------------------------------------------------------------------

-- SP player
local function _BGI_getPlayer()
    if getSpecificPlayer then return getSpecificPlayer(0) end
    if getPlayer then return getPlayer() end
    return nil
end

-- Stair movement has fractional character Z. Overlay slices use integer floors.
local function _BGI_getPlayerFloor(player)
    if not player then return nil end
    local square = player.getSquare and player:getSquare() or nil
    if square then return square:getZ() end
    if player.getZ then return math.floor(player:getZ()) end
    return nil
end

-- True if targetZ lies within the vertical range (SandboxVar or fallback)
function BGI_PowerRange:_getEffectiveVerticalRange()
    local sv = rawget(_G, "SandboxVars")
    if sv and type(sv.GeneratorVerticalPowerRange) == "number" then
        return math.max(0, math.floor(sv.GeneratorVerticalPowerRange))
    end
    -- Fallback
    return self.GenVerticalLimit or 3
end

local function _BGI_withinVerticalLimits(genZ, targetZ)
    local vr = BGI_PowerRange:_getEffectiveVerticalRange()
    local zMin = math.max(0, genZ - vr)
    local zMax = genZ + vr
    return targetZ >= zMin and targetZ <= zMax
end

-- Global master switch (ModOptions): returns true when overlay is enabled
local function _isOverlayEnabled()
    local opts = BGI and BGI.Options
    if opts and opts.EnablePowerRangeOverlay ~= nil then
        return opts.EnablePowerRangeOverlay == true
    end
    return true  -- default to ON if options aren't loaded yet
end

-- Per Gen Color (respects ON/OFF)
-- Inputs : isOn (boolean or nil)
-- Output : {r,g,b,a}
local function _perGenColor(isOn)
    local alpha = BGI_PowerRange.PerGenAlpha or 0.08
    local opts  = BGI and BGI.Options
    -- ON: use user colour if available; else core "good"
    if isOn == true then
        if opts and opts.getGenColor then
            local c = opts:getGenColor(alpha)
            if c then return c end
        end
        local good = getCore():getGoodHighlitedColor()
        return { r = good:getR(), g = good:getG(), b = good:getB(), a = alpha }
    end
    -- OFF: core "bad"
    local bad = getCore():getBadHighlitedColor()
    return { r = bad:getR(), g = bad:getG(), b = bad:getB(), a = alpha }
end

-- Union Gen Color
local function _BGI_UnionGenCol()
    local opts = BGI and BGI.Options
    if opts and opts.getUnionColor then
        return opts:getUnionColor(BGI_PowerRange.UnionOverlayAlpha)
    end
    return { r = 0.690, g = 0.878, b = 0.902, a = BGI_PowerRange.UnionOverlayAlpha } -- powder blue (#B0E0E6)
end

-- Effective generator tile range
function BGI_PowerRange:_getEffectiveTileRange()
    local sv = rawget(_G, "SandboxVars")
    if sv and type(sv.GeneratorTileRange) == "number" then
        return math.max(1, math.floor(sv.GeneratorTileRange))
    end
    return self.DefaultTileRange
end

-- Generator square & cell guard
local function _BGI_getGenSquareAndCell(gen)
    if not gen or not gen.getSquare then return nil, nil end
    local sq = gen:getSquare()
    if not sq then return nil, nil end
    local cell = getCell and getCell() or nil
    if not cell then return nil, nil end
    return sq, cell
end

-- True if we already have cached union edges for the given Z
local function _BGI_hasWarmUnionCache(z)
    local u = BGI_PowerRange and BGI_PowerRange.UnionEdgesByZ
    return (u and u[z] and #u[z] > 0) == true
end

-- Seed _unionGenStates from the current registry without causing rebuilds.
local function _BGI_seedUnionSnapshot()
    _ensureStates()

    local gens = BGI_GenRegistry.getAll()  -- safe: BGI_GenRegistry is in scope now
    if not gens or #gens == 0 then return end

    for i = 1, #gens do
        local go = gens[i].obj
        if go and go.isActivated then
            BGI_PowerRange._unionGenStates[go] = (go:isActivated() == true)
        end
    end
end

-- Load persisted per-player toggles (a missing value keeps the current one).
local function _BGI_loadToggles()
    local p = _BGI_getPlayer()
    local md = p and p.getModData and p:getModData() or nil
    if not md then return end
    if md.BGI_ShowUnion ~= nil then BGI_PowerRange.ShowUnion = md.BGI_ShowUnion == true end
    if md.BGI_ShowPerGen ~= nil then BGI_PowerRange.ShowPerGen = md.BGI_ShowPerGen == true end
end

-- Re-read the toggles and flag a union rebuild only if it is visible and not cached.
local function _BGI_syncUnionFromPersisted()
    _BGI_loadToggles()
    local z = _BGI_getPlayerFloor(_BGI_getPlayer())
    BGI_PowerRange._needsUnionRebuild =
        BGI_PowerRange.ShowUnion == true and not (z ~= nil and _BGI_hasWarmUnionCache(z))
end

-----------------------------------------------------------------------
-- Power Stencil (ΔZ = 0): precompute once per (R, ProbePad)
-- Reuses engine truth to build a reusable mask & edge offsets.
-----------------------------------------------------------------------
-- Nested numeric sets: poweredSet[x][y] = true. Avoids string key churn.
local function _setHas(set, x, y)
    local row = set[x]
    return row ~= nil and row[y] ~= nil
end
local function _setAdd(set, x, y)
    local row = set[x]
    if not row then row = {}; set[x] = row end
    if row[y] == nil then row[y] = true end
end

BGI_Stencil = {
    keyR = nil, keyPad = nil,
    poweredOffsets = nil, -- array of {dx,dy}
    edgeOffsets    = nil, -- array of {dx,dy} (outer circumference)
    poweredSet     = nil, -- nested set [dx][dy]=true of poweredOffsets
}

local function _BGI_buildStencil(R, pad)
    pad = pad or 0
    -- Prefer the compiled helper when ZombieBuddy exposes it.
    local bgiStencil = rawget and rawget(_G, "bgiStencil")
    if type(bgiStencil) == "function" then
        local ok, res = pcall(bgiStencil, R, pad)
        if ok and type(res) == "table"
           and type(res.poweredOffsets) == "table"
           and type(res.edgeOffsets) == "table" then
            BGI_Stencil.keyR, BGI_Stencil.keyPad = R, pad
            BGI_Stencil.poweredOffsets = res.poweredOffsets
            BGI_Stencil.edgeOffsets    = res.edgeOffsets
            return
        end
    end

    local reach = R + pad
    local minX, maxX = -reach, reach
    local minY, maxY = -reach, reach

    -- 1) Powered mask at origin (0,0, z=0)
    local powered = {}
    local poweredOffsets = {}
    for y = minY, maxY do
        powered[y] = {}
        for x = minX, maxX do
            local p = IsoGenerator.isPoweringSquare(0, 0, 0, x, y, 0) == true
            powered[y][x] = p
            if p then poweredOffsets[#poweredOffsets+1] = {x,y} end
        end
    end

    -- 2) Edge offsets (unpowered tiles touching any powered)
    local edgeOffsets = {}
    for y = minY, maxY do
        for x = minX, maxX do
            if not powered[y][x] then
                local touches = false
                for i = 1, #_BGI_NEIGHBORS do
                    local nx, ny = x + _BGI_NEIGHBORS[i][1], y + _BGI_NEIGHBORS[i][2]
                    if nx >= minX and nx <= maxX and ny >= minY and ny <= maxY then
                        if powered[ny][nx] then touches = true; break end
                    end
                end
                if touches then edgeOffsets[#edgeOffsets+1] = {x,y} end
            end
        end
    end

    BGI_Stencil.keyR, BGI_Stencil.keyPad = R, (pad or 0)
    BGI_Stencil.poweredOffsets = poweredOffsets
    BGI_Stencil.edgeOffsets    = edgeOffsets
end

local function _ensureStencil(R, pad)
    if BGI_Stencil.keyR ~= R or BGI_Stencil.keyPad ~= (pad or 0)
       or not BGI_Stencil.poweredOffsets or not BGI_Stencil.edgeOffsets then
        _BGI_buildStencil(R, pad)

        -- Index the powered offsets once for O(1) membership tests.
        local set, po = {}, BGI_Stencil.poweredOffsets
        for i = 1, #po do _setAdd(set, po[i][1], po[i][2]) end
        BGI_Stencil.poweredSet = set
    end
end

-- Drop edges that lie inside the coverage of the generator at (gx, gy).
local function _BGI_dropCoveredBy(edges, gx, gy)
    local pset = BGI_Stencil.poweredSet
    local out = {}
    for i = 1, #edges do
        local e = edges[i]
        if not _setHas(pset, e[1] - gx, e[2] - gy) then out[#out + 1] = e end
    end
    return out
end

-- Stamp one generator's powered offsets into a sparse powered set (bounds-clipped)
local function _BGI_stampGenToSet(set, baseX, baseY, minX, maxX, minY, maxY)
    local po = BGI_Stencil.poweredOffsets
    for i = 1, #po do
        local x = baseX + po[i][1]
        local y = baseY + po[i][2]
        if x >= minX and x <= maxX and y >= minY and y <= maxY then
            _setAdd(set, x, y)
        end
    end
end

-- Merge: keep existing edges outside bbox, replace inside with newEdges
local function _BGI_mergeEdgesReplaceInBox(oldEdges, newEdges, minX, maxX, minY, maxY)
    local out = {}
    if oldEdges then
        for i = 1, #oldEdges do
            local x, y = oldEdges[i][1], oldEdges[i][2]
            if not (x >= minX and x <= maxX and y >= minY and y <= maxY) then
                out[#out+1] = oldEdges[i]
            end
        end
    end
    for i = 1, #newEdges do out[#out+1] = newEdges[i] end
    return out
end

-- Discover generators as their squares stream in, but deliberately retain
-- their records when those squares stream out. This keeps known power rings
-- visible beyond the player's currently loaded IsoCell.
-- getGenerator() is the engine's own lookup (it checks only the square's special
-- objects, in Java), so a streamed-in square costs one call instead of a Lua loop
-- over every object.
Events.LoadGridsquare.Add(function(sq)
    local gen = sq and sq:getGenerator()
    if not gen then return end

    _addGen(gen)
    _ensureStates()[gen] = gen:isActivated() == true

    if BGI_PowerRange.Enabled and BGI_PowerRange.ShowUnion then
        BGI_PowerRange:_localizedUnionUpdateForGen(gen)
    else
        BGI_PowerRange._needsUnionRebuild = true
    end
end)

Events.OnObjectAboutToBeRemoved.Add(function(o)
    -- DebugLog.log(DebugType.General, "[BGI] Object Removed: " .. tostring(o))
    if not _isGen(o) then return end
    _removeGen(o)
    if BGI_PowerRange._unionGenStates then BGI_PowerRange._unionGenStates[o] = nil end
    BGI_PowerRange._needsUnionRebuild = true
end)

-- Do not purge on ReuseGridsquare. Registry entries are intentionally kept as
-- coordinate snapshots so previously discovered rings remain drawable.

-----------------------------------------------------------------------
-- Edge building (outer perimeter from a boolean map)
-----------------------------------------------------------------------

-- Build OUTER edge list (unpowered tiles that touch a powered tile) for a Z slice.
-- Returns an array of {x,y} coords (immutable; safe across unload/reload).
local function _BGI_buildOuterEdgeInBox(minX, maxX, minY, maxY, getPowered)
    local edges = {}

    -- Memoize per-rebuild: each (x,y) is evaluated once
    local memo = {}
    local function gp(x, y)
        local ry = y - minY
        local rx = x - minX
        local row = memo[ry]; if not row then row = {}; memo[ry] = row end
        local v = row[rx]
        if v == nil then
            v = getPowered(x, y) and true or false
            row[rx] = v
        end
        return v
    end

    for y = minY, maxY do
        for x = minX, maxX do
            if not gp(x, y) then
                local touchesPowered = false
                for i = 1, #_BGI_NEIGHBORS do
                    local nx, ny = x + _BGI_NEIGHBORS[i][1], y + _BGI_NEIGHBORS[i][2]
                    if nx >= minX and nx <= maxX and ny >= minY and ny <= maxY then
                        if gp(nx, ny) then touchesPowered = true; break end
                    end
                end
                if touchesPowered then
                    edges[#edges + 1] = { x, y } -- store coordinates only
                end
            end
        end
    end
    return edges
end

-- Build the outer edge directly from a sparse powered set. Runtime and memory
-- scale with powered tiles, not with the distance between generator clusters.
local function _BGI_buildOuterEdgeFromSet(poweredSet)
    local edges = {}
    local seen = {}

    for x, row in pairs(poweredSet) do
        for y in pairs(row) do
            for i = 1, #_BGI_NEIGHBORS do
                local nx = x + _BGI_NEIGHBORS[i][1]
                local ny = y + _BGI_NEIGHBORS[i][2]
                if not _setHas(poweredSet, nx, ny) then
                    local key = _k2(nx, ny)
                    if not seen[key] then
                        seen[key] = true
                        edges[#edges + 1] = { nx, ny }
                    end
                end
            end
        end
    end

    return edges
end

-----------------------------------------------------------------------
-- Rebuild per-generator edge cache using the engine reach test
-----------------------------------------------------------------------

function BGI_PowerRange:_rebuildPerGenEdges()
    --- Rebuilds the focused generator's ring cache and (optionally) mirrors it onto the player's Z.
    --- Inputs : self.TargetGen (IsoGenerator), self.PerGenEdgesByZ (table)
    --- Output : self.PerGenEdgesByZ[z] = { {x,y}, ... } for relevant z-slices (player Z + gen Z)
    local genSq, cell = _BGI_getGenSquareAndCell(self.TargetGen)
    if not genSq then
        self.PerGenEdgesByZ = {}
        self._needsPerGenRebuild = false
        return
    end

    local gx, gy, gz = genSq:getX(), genSq:getY(), genSq:getZ()
    local R   = self:_getEffectiveTileRange()
    local pad = (self.ProbePad or 0)

    -- Ensure stencil (ΔZ=0)
    _ensureStencil(R, pad)

    -- Build edges at generator coords once
    local builtEdges = {}
    for i = 1, #BGI_Stencil.edgeOffsets do
        local dx, dy = BGI_Stencil.edgeOffsets[i][1], BGI_Stencil.edgeOffsets[i][2]
        builtEdges[#builtEdges+1] = { gx + dx, gy + dy }
    end

    -- Reset and populate gen Z
    self.PerGenEdgesByZ = {}
    self.PerGenEdgesByZ[gz] = builtEdges

    -- Mirror to player's current Z always (visual guide only; engine power remains ΔZ-limited)
    -- We still colour based on “powers this Z” in _drawCurrentZ().
    do
        local p = _BGI_getPlayer()
        local pz = _BGI_getPlayerFloor(p)
        if pz ~= nil and pz ~= gz then
            local copy = {}
            for i = 1, #builtEdges do
                local e = builtEdges[i]; copy[i] = { e[1], e[2] }
            end
            self.PerGenEdgesByZ[pz] = copy
        end
    end

    self._needsPerGenRebuild = false
end

-- Rebuild union (event-driven): union of ALL eligible (activated && connected) gens via stencil stamping.
function BGI_PowerRange:_rebuildUnionEdges(onlyZ)
    --- Rebuilds union edges for a Z slice (or a span). If UnionProjectToCurrentZ is true and we are
    --- asked to build the player's current Z but no active generators exist on that Z, we project
    --- all active generators' coverage onto the player's Z (same x,y) for visual guidance.
    local R   = self:_getEffectiveTileRange()
    local pad = (self.ProbePad or 0)
    local reachBox = R + pad

    _ensureStencil(R, pad)

    local gens = BGI_GenRegistry.getAll()

    -- Eligible = activated && connected
    local active = {}
    for i = 1, #gens do
        local go = gens[i].obj
        if go and go.isActivated and go:isActivated() == true then
            active[#active+1] = gens[i]
        end
    end
    if #active == 0 then
        self.UnionEdgesByZ = {}
        self.UnionPoweredByZ = {}
        self._needsUnionRebuild = false
        return
    end

    -- Seed = the focused generator; with none (window closed) any active generator.
    local genSq = _BGI_getGenSquareAndCell(self.TargetGen)
    local seedGX, seedGY, seedGZ
    if genSq then
        seedGX, seedGY, seedGZ = genSq:getX(), genSq:getY(), genSq:getZ()
    else
        seedGX, seedGY, seedGZ = active[1].x, active[1].y, active[1].z
    end

    -- Figure out build range (by default: limited span around the selected gen)
    local vr   = self:_getEffectiveVerticalRange()
    local zMin = math.max(0, seedGZ - vr)
    local zMax = seedGZ + vr
    local buildZmin, buildZmax
    if onlyZ ~= nil then buildZmin, buildZmax = onlyZ, onlyZ else buildZmin, buildZmax = zMin, zMax end

    self.UnionEdgesByZ   = self.UnionEdgesByZ or {}
    self.UnionPoweredByZ = self.UnionPoweredByZ or {}

    for z = buildZmin, buildZmax do
        -- 1) Check if there are any active generators on this Z
        local anyOnZ = false
        for i = 1, #active do
            if active[i].z == z then anyOnZ = true; break end
        end

        local projectForPlayerZ = false
        do
            -- If we're explicitly building the player's current Z but no gens are on this Z,
            -- we can project the union visually onto this Z if allowed by policy.
            local p = _BGI_getPlayer()
            local pz = _BGI_getPlayerFloor(p)
            if pz ~= nil and z == pz and not anyOnZ and (self.UnionProjectToCurrentZ == true) then
                projectForPlayerZ = true
            end
        end

        if not anyOnZ and not projectForPlayerZ then
            -- Nothing to stamp here (and not projecting) → empty slice
            self.UnionEdgesByZ[z]   = {}
            self.UnionPoweredByZ[z] = {}
        else
            -- 2) Build powered set
            local poweredSet = {}

            local skipCompute = false

            if projectForPlayerZ then
                -- Project onto the player's Z. Optionally respect vertical limits.
                local listProjected = active
                if self.UnionRespectVerticalLimits == true then
                    listProjected = {}
                    for i = 1, #active do
                        local g = active[i] -- {obj, x, y, z}
                        if _BGI_withinVerticalLimits(g.z, z) then
                            listProjected[#listProjected+1] = g
                        end
                    end
                end

                if #listProjected == 0 then
                    -- Nothing eligible to project onto this Z. Mark empty and skip edge build.
                    self.UnionEdgesByZ[z]   = {}
                    self.UnionPoweredByZ[z] = {}
                    skipCompute = true
                else
                    for i = 1, #listProjected do
                        local g = listProjected[i]
                        _BGI_stampGenToSet(
                            poweredSet, g.x, g.y,
                            g.x - reachBox, g.x + reachBox,
                            g.y - reachBox, g.y + reachBox
                        )
                    end
                end
            else
                -- Normal: only generators actually on this Z
                local listOnZ = {}
                for i = 1, #active do if active[i].z == z then listOnZ[#listOnZ+1] = active[i] end end
                if #listOnZ == 0 then
                    self.UnionEdgesByZ[z]   = {}
                    self.UnionPoweredByZ[z] = {}
                    skipCompute = true
                else
                    for i = 1, #listOnZ do
                        local g = listOnZ[i]
                        _BGI_stampGenToSet(
                            poweredSet, g.x, g.y,
                            g.x - reachBox, g.x + reachBox,
                            g.y - reachBox, g.y + reachBox
                        )
                    end
                end
            end

            -- 3) Edge from powered set (only if we stamped anything)
            if not skipCompute then
                local edges = _BGI_buildOuterEdgeFromSet(poweredSet)

                -- 4) Hide selected-gen coverage when it's ON on its native Z only (unchanged rule)
                local selOn = self.TargetGen and self.TargetGen.isActivated and self.TargetGen:isActivated()
                if selOn and not projectForPlayerZ and z == seedGZ then
                    edges = _BGI_dropCoveredBy(edges, seedGX, seedGY)
                end

                self.UnionPoweredByZ[z] = poweredSet
                self.UnionEdgesByZ[z]   = edges
            end
        end
    end

    self._needsUnionRebuild = false
end

-- Time full rebuilds only, keeping steady-state frames free of diagnostic output.
local _BGI_rebuildUnionEdges = BGI_PowerRange._rebuildUnionEdges
function BGI_PowerRange:_rebuildUnionEdges(onlyZ, reason)
    if not self.DebugUnionRebuilds then
        return _BGI_rebuildUnionEdges(self, onlyZ)
    end
    local started = getTimestampMs()
    _BGI_rebuildUnionEdges(self, onlyZ)
    local elapsed = getTimestampMs() - started
    local edgeCount = 0
    for z, edges in pairs(self.UnionEdgesByZ) do
        if onlyZ == nil or z == onlyZ then edgeCount = edgeCount + #edges end
    end
    print("[BGI][Union] rebuild reason=" .. tostring(reason or "unspecified")
        .. " z=" .. tostring(onlyZ) .. " generators=" .. tostring(#BGI_GenRegistry.getAll())
        .. " edges=" .. tostring(edgeCount) .. " ms=" .. tostring(elapsed))
end

-- Localized union update for a single generator that just became available.
-- Fast path: stamps into the persistent powered set and recomputes edges only in the gen's AABB.
function BGI_PowerRange:_localizedUnionUpdateForGen(gen)
    if not (gen and gen.getSquare and gen.isActivated) then return end
    if gen:isActivated() ~= true then return end
    local sq = gen:getSquare(); if not sq then return end

    local gx, gy, gz = sq:getX(), sq:getY(), sq:getZ()
    local R   = self:_getEffectiveTileRange()
    local pad = (self.ProbePad or 0)
    local reachBox = R + pad

    _ensureStencil(R, pad)

    self.UnionPoweredByZ = self.UnionPoweredByZ or {}
    self.UnionEdgesByZ   = self.UnionEdgesByZ   or {}

    -- If we don't have a powered set for this Z yet, fall back to a full rebuild (once).
    local setZ = self.UnionPoweredByZ[gz]
    if not setZ then
        -- Build only current Z to seed the map
        self:_rebuildUnionEdges(gz, "stream-cache-miss")
        setZ = self.UnionPoweredByZ[gz]
        if not setZ then return end
    end

    -- 1) Stamp this generator into the set (clip to a local bbox)
    local minX, maxX = gx - reachBox, gx + reachBox
    local minY, maxY = gy - reachBox, gy + reachBox
    _BGI_stampGenToSet(setZ, gx, gy, minX, maxX, minY, maxY)

    -- 2) Rebuild edges only inside the affected bbox (+1 tile padding for correctness)
    local bxMin, bxMax = minX - 1, maxX + 1
    local byMin, byMax = minY - 1, maxY + 1
    local function getPoweredLocal(x, y) return _setHas(setZ, x, y) end
    local newEdges = _BGI_buildOuterEdgeInBox(bxMin, bxMax, byMin, byMax, getPoweredLocal)

    -- 3) Apply selected-gen hide rule locally if needed
    local seedSq = self.TargetGen and self.TargetGen.getSquare and self.TargetGen:getSquare() or nil
    if seedSq and self.TargetGen.isActivated and self.TargetGen:isActivated() and seedSq:getZ() == gz then
        newEdges = _BGI_dropCoveredBy(newEdges, seedSq:getX(), seedSq:getY())
    end

    -- 4) Merge into UnionEdges for this Z (replace edges inside bbox)
    local old = self.UnionEdgesByZ[gz] or {}
    self.UnionEdgesByZ[gz] = _BGI_mergeEdgesReplaceInBox(old, newEdges, bxMin, bxMax, byMin, byMax)
end

-----------------------------------------------------------------------
-- Drawing
-----------------------------------------------------------------------

-- Draw one Z-slice of the edges with the provided colour (table .r .g .b .a)
-- Now edges are stored as immutable {x,y} coords (not IsoGridSquare objects),
-- so drawing is chunk/load agnostic (no hitches, no gaps).
local function _drawEdgeList(edgeList, z, color)
    if not edgeList or #edgeList == 0 then return end
    local r, g, b, a = color.r, color.g, color.b, color.a
    for i = 1, #edgeList do
        local e = edgeList[i]
        if e then
            local x, y = e[1], e[2]
            addAreaHighlight(x, y, x + 1, y + 1, z, r, g, b, a)
        end
    end
end

-- Draw overlays for the player's current Z
function BGI_PowerRange:_drawCurrentZ()
    if not _isOverlayEnabled() then return end
    local p = _BGI_getPlayer(); if not p or not p.getZ then return end
    local z = _BGI_getPlayerFloor(p)
    if z == nil then return end

    -- Micro-opt: if neither list has entries for this Z, skip work altogether
    local hasUnion = self.ShowUnion and self.UnionEdgesByZ and self.UnionEdgesByZ[z] and #self.UnionEdgesByZ[z] > 0
    local hasPer   = self.ShowPerGen and self.PerGenEdgesByZ and self.PerGenEdgesByZ[z] and #self.PerGenEdgesByZ[z] > 0
    if not hasUnion and not hasPer then return end

    -- 1) Union overlay first (optional, powder blue)
    if hasUnion then
        local col = _BGI_UnionGenCol()
        _drawEdgeList(self.UnionEdgesByZ[z], z, col)
    end

    -- 2) Per-generator ring on top (colour by “does this gen power THIS Z?”)
    if hasPer then
        local isOn = self.TargetGen and self.TargetGen.isActivated and self.TargetGen:isActivated() or false

        -- Determine if the focused generator powers the player's current Z.
        local powersThisZ = false
        do
            local gsq = self.TargetGen and self.TargetGen.getSquare and self.TargetGen:getSquare() or nil
            if gsq then
                local gz = gsq:getZ()
                -- Powers this Z only if same Z OR within vertical limits.
                powersThisZ = (z == gz) or _BGI_withinVerticalLimits(gz, z)  -- uses helper from Helpers section
            end
        end

        -- If the gen is ON but doesn’t power this Z, treat as OFF for colour choice.
        local color = _perGenColor(isOn and powersThisZ)
        _drawEdgeList(self.PerGenEdgesByZ[z], z, color)
    end
end

-----------------------------------------------------------------------
-- Public API
-----------------------------------------------------------------------

function BGI_PowerRange.Start(gen)
    BGI_PowerRange.Enabled             = true
    BGI_PowerRange.TargetGen           = gen or BGI_PowerRange.TargetGen
    BGI_PowerRange._needsPerGenRebuild = true

    -- Load persisted toggles before deciding whether any overlay work is needed.
    _BGI_loadToggles()

    -- Keep the controller ready in case ModOptions is enabled while this
    -- window is open, but do no registry or edge work while disabled.
    if not _isOverlayEnabled() then
        BGI_PowerRange._needsUnionRebuild = true
        BGI_PowerRange._lastGenOnState = nil
        return
    end

    -- Ensure the selected generator is known even if its LoadGridsquare event
    -- preceded this module's event registration.
    _addGen(BGI_PowerRange.TargetGen)

    BGI_PowerRange._lastGenOnState = nil
    _BGI_seedUnionSnapshot()  -- no false diffs on open

    -- Remember the player's floor and warm the union cache for it so toggling ON is instant.
    local pz = _BGI_getPlayerFloor(_BGI_getPlayer())
    BGI_PowerRange._lastPlayerZ = pz
    if pz ~= nil and not _BGI_hasWarmUnionCache(pz) then
        BGI_PowerRange:_rebuildUnionEdges(pz, "window-open")  -- clears _needsUnionRebuild
    end
    BGI_PowerRange._needsUnionRebuild = (pz == nil)
end

function BGI_PowerRange.Stop()
    BGI_PowerRange.Enabled             = false
    BGI_PowerRange.TargetGen           = nil
    BGI_PowerRange.PerGenEdgesByZ      = {}
    BGI_PowerRange.UnionEdgesByZ       = {}
    BGI_PowerRange.UnionPoweredByZ     = {}
    BGI_PowerRange._needsPerGenRebuild = false
    BGI_PowerRange._needsUnionRebuild  = false
    BGI_PowerRange._lastGenOnState     = nil
    BGI_PowerRange._unionGenStates     = nil
end

function BGI_PowerRange.SetTargetGenerator(gen)
    if BGI_PowerRange.TargetGen ~= gen then
        BGI_PowerRange.TargetGen           = gen
        BGI_PowerRange._needsPerGenRebuild = true
        BGI_PowerRange._needsUnionRebuild  = true
        BGI_PowerRange._unionGenStates     = nil
    end
end

-- Called each frame from the window's prerender override
function BGI_PowerRange.Update()
    if not BGI_PowerRange.Enabled then return end
    -- Master switch via ModOptions
    if not _isOverlayEnabled() then
        return  -- no rebuilds, no drawing
    end

    local gen = BGI_PowerRange.TargetGen
    if not gen then return end

    -- Floor change: rebuild union for the new Z immediately (warm cache), and refresh per-gen projection.
    do
        local p    = _BGI_getPlayer()
        local curZ = _BGI_getPlayerFloor(p)
        if curZ ~= nil and curZ ~= BGI_PowerRange._lastPlayerZ then
            BGI_PowerRange._lastPlayerZ = curZ

            -- Rebuild union edges for THIS Z so a later toggle-on is instant and drawing stays correct.
            BGI_PowerRange:_rebuildUnionEdges(curZ, "floor-change")
            BGI_PowerRange._needsUnionRebuild = false

            -- Also refresh the per-gen ring so projection appears on the new floor if needed.
            BGI_PowerRange._needsPerGenRebuild = true
        end
    end

    -- Rebuild per-gen edges on ON/OFF flip or when flagged dirty
    local isOn = gen:isActivated()
    if BGI_PowerRange._lastGenOnState == nil or BGI_PowerRange._lastGenOnState ~= isOn then
        BGI_PowerRange._lastGenOnState = isOn
        -- Per-gen edge geometry is R/position based; activation only affects colour,
        -- which _drawCurrentZ resolves. Only the union eligibility changes here.
        BGI_PowerRange._needsUnionRebuild = true
    end

    -- Rebuild per-gen ring only if shown (works when OFF via reach test)
    if BGI_PowerRange.ShowPerGen and BGI_PowerRange._needsPerGenRebuild then
        BGI_PowerRange:_rebuildPerGenEdges()
    end

    -- Union refresh policy (single scan, stride-gated)
    if BGI_PowerRange.ShowUnion then
        local need = BGI_PowerRange._needsUnionRebuild
        local rebuildReason = "dirty-state"

        -- Poll ON/OFF only every UnionRefreshStride frames to avoid overhead
        BGI_PowerRange._unionPollTicker = (BGI_PowerRange._unionPollTicker or 0) - 1
        local shouldPoll = (BGI_PowerRange._unionPollTicker <= 0)

        if not need and shouldPoll then
            local gens = BGI_GenRegistry.getAll()
            if gens and #gens > 0 then
                for i = 1, #gens do
                    local go = gens[i].obj
                    if go and go.isActivated then
                        local cur  = (go:isActivated() == true)
                        local prev = BGI_PowerRange._unionGenStates and BGI_PowerRange._unionGenStates[go]
                        -- Only trigger rebuild on a real change (prev ~= nil and prev ~= cur)
                        if prev ~= nil and prev ~= cur then
                            need = true
                            rebuildReason = "activation-poll"
                        end
                        -- Save changed states too, then finish the scan before rebuilding once.
                        _ensureStates()
                        BGI_PowerRange._unionGenStates[go] = cur
                    end
                end
            end
            -- Reset stride ticker (use at least 1 to avoid negative churn)
            BGI_PowerRange._unionPollTicker = math.max(1, (BGI_PowerRange.UnionRefreshStride or 120))
        end

        if need then
            local p = _BGI_getPlayer()
            local z = _BGI_getPlayerFloor(p)
            BGI_PowerRange:_rebuildUnionEdges(z, rebuildReason)
            BGI_PowerRange._needsUnionRebuild = false
        end
    end
    BGI_PowerRange:_drawCurrentZ()
end

-----------------------------------------------------------------------
-- UI integration: ISGeneratorInfoWindow overrides + checkbox
-----------------------------------------------------------------------
do
    if ISGeneratorInfoWindow and not BGI_PowerRange._overridesApplied then

        -- Create / place our checkbox once UI children exist
        local _legacy_createChildren = ISGeneratorInfoWindow.createChildren
        function ISGeneratorInfoWindow:createChildren()
            if _legacy_createChildren then _legacy_createChildren(self) end

            -- === Title-bar toggle button for Union Overlay ===
            if not self.bgiUnionBtn then
                -- Small text label; keep it short so it fits in the title bar
                local label = getText and getText("UI_BGI_ShowAllPowered_Short") or "All"

                -- Create a tiny title-bar button; size auto-fits the text
                local txtW = getTextManager() and getTextManager():MeasureStringX(UIFont.Small, label) or 18
                local btnW, btnH = math.max(26, txtW + 8), 18
                self.bgiUnionBtn = ISButton:new(0, 0, btnW, btnH, label, self,
                    function()
                        if not _isOverlayEnabled() then return end
                        local turningOn = not (BGI_PowerRange.ShowUnion == true)
                        BGI_PowerRange.ShowUnion = turningOn

                        -- Persist per-player
                        local p = _BGI_getPlayer()
                        if p and p.getModData then p:getModData().BGI_ShowUnion = turningOn end

                        -- Turning OFF keeps the cache; turning ON rebuilds only if it is cold.
                        if turningOn then
                            local z = _BGI_getPlayerFloor(p)
                            BGI_PowerRange._needsUnionRebuild = z == nil or not _BGI_hasWarmUnionCache(z)
                        end
                    end
                )
                self.bgiUnionBtn:initialise()
                self.bgiUnionBtn:instantiate()
                self.bgiUnionBtn.borderColor = { r=1, g=1, b=1, a=0.5 }
                self.bgiUnionBtn.tooltip = getTextOrNull("UI_BGI_ShowAllPowered_Tooltip")
                    or "Outline tiles powered by all nearby active generators."
                self:addChild(self.bgiUnionBtn)
            end

            -- === Title-bar toggle button for Per-Gen Ring ===
            if not self.bgiThisBtn then
                local labelThis = getTextOrNull and getTextOrNull("UI_BGI_ShowThis_Short") or "This"
                local txtW2 = getTextManager() and getTextManager():MeasureStringX(UIFont.Small, labelThis) or 20
                local btnW2, btnH2 = math.max(28, txtW2 + 8), 18
                self.bgiThisBtn = ISButton:new(0, 0, btnW2, btnH2, labelThis, self,
                    function()
                        if not _isOverlayEnabled() then return end
                        local turningOn = not (BGI_PowerRange.ShowPerGen == true)
                        BGI_PowerRange.ShowPerGen = turningOn
                        local p = _BGI_getPlayer()
                        if p and p.getModData then p:getModData().BGI_ShowPerGen = turningOn end
                        if turningOn then
                            BGI_PowerRange._needsPerGenRebuild = true
                        else
                            -- optional: drop cached edges to free memory
                            BGI_PowerRange.PerGenEdgesByZ = {}
                        end
                    end
                )
                self.bgiThisBtn:initialise()
                self.bgiThisBtn:instantiate()
                self.bgiThisBtn.borderColor = { r=1, g=1, b=1, a=0.5 }
                self.bgiThisBtn.tooltip = getTextOrNull("UI_BGI_ShowThis_Tooltip") or "Outline tiles powered by this generator"
                self:addChild(self.bgiThisBtn)
            end
        end

        -- Keep the checkbox anchored to the bottom; drive Update()
        local _legacy_prerender = ISGeneratorInfoWindow.prerender
        function ISGeneratorInfoWindow:prerender()
            local overlayEnabled = _isOverlayEnabled()
            if self.bgiUnionBtn then self.bgiUnionBtn:setVisible(overlayEnabled) end
            if self.bgiThisBtn  then self.bgiThisBtn:setVisible(overlayEnabled)  end
            if not overlayEnabled then
                BGI_PowerRange.Update(); if _legacy_prerender then _legacy_prerender(self) end
                return
            end

            -- Shared metrics
            local tbH = 20
            if self.titleBarHeight ~= nil then
                tbH = (type(self.titleBarHeight)=="function") and self:titleBarHeight()
                or (type(self.titleBarHeight)=="number"   and self.titleBarHeight or tbH)
            end
            local rightGutter = 56
            local rightLimit  = self:getWidth() - rightGutter

            -- Union button
            if self.bgiUnionBtn then
                self.bgiUnionBtn:setY(math.max(1, math.floor((tbH - self.bgiUnionBtn:getHeight())/2)))
                self.bgiUnionBtn:setX(math.max(4, rightLimit - self.bgiUnionBtn:getWidth()))
            end

            -- THIS button (reuse Union Y; fallback to tbH math)
            if self.bgiThisBtn then
                self.bgiThisBtn:setY(self.bgiUnionBtn and self.bgiUnionBtn:getY()
                    or math.max(1, math.floor((tbH - self.bgiThisBtn:getHeight())/2)))
                local unionX = self.bgiUnionBtn and self.bgiUnionBtn:getX() or rightLimit
                self.bgiThisBtn:setX(math.max(4, unionX - 4 - self.bgiThisBtn:getWidth()))
            end

            BGI_PowerRange.Update(); if _legacy_prerender then _legacy_prerender(self) end
        end

        -- Set object (selected generator) and mirror checkbox from persisted state
        local _legacy_setObject = ISGeneratorInfoWindow.setObject
        function ISGeneratorInfoWindow:setObject(object)
            BGI_PowerRange.SetTargetGenerator(object)
            -- Mirror persisted state to the toggle (no callback)
            if self.bgiUnionBtn then _BGI_syncUnionFromPersisted() end
            if _legacy_setObject then return _legacy_setObject(self, object) end
        end

        -- Start/Stop overlay with window visibility
        local _legacy_setVisible = ISGeneratorInfoWindow.setVisible
        function ISGeneratorInfoWindow:setVisible(visible, ...)
            if visible then
                BGI_PowerRange.Start(self and self.object or nil)
                if self.bgiUnionBtn then _BGI_syncUnionFromPersisted() end
            else
                BGI_PowerRange.Stop()
            end
            if _legacy_setVisible then
                return _legacy_setVisible(self, visible, ...)
            end
        end

        -- Ensure stop on close
        local _legacy_remove = ISGeneratorInfoWindow.removeFromUIManager
        function ISGeneratorInfoWindow:removeFromUIManager()
            BGI_PowerRange.Stop()
            if _legacy_remove then return _legacy_remove(self) end
        end

        BGI_PowerRange._overridesApplied = true
    end
end

-- Build 42.x — Safe Alternative: hook generator connect/disconnect + activate/deactivate
-- Purpose: keep registry in sync and rebuild union exactly once on real changes.

-- Ensure classes exist
if not ISPlugGenerator then require "TimedActions/ISPlugGenerator" end
if not ISActivateGenerator then require "TimedActions/ISActivateGenerator" end

-- Helper: ensure a gen is present in the registry
local function _BGI_ensureGenRegistered(gen)
    if not gen or not gen.getSquare then return false end
    local sq = gen:getSquare(); if not sq then return false end
    return _addGen(gen)
end

-- Helper: schedule a single union rebuild (and warm cache if hidden)
local function _BGI_requestUnionRebuild()
    BGI_PowerRange._needsUnionRebuild = true
    if not BGI_PowerRange.ShowUnion then
        local p = _BGI_getPlayer()
        local onlyZ = BGI_PowerRange.UnionBuildCurrentZOnly and _BGI_getPlayerFloor(p) or nil
        BGI_PowerRange:_rebuildUnionEdges(onlyZ, "timed-action-hidden")
    end
end

-- Track only the generator explicitly requested for activation. The poll waits
-- for its actual synchronized state, so a canceled or failed start adds no power.
if not ISWorldObjectContextMenu then require "ISUI/ISWorldObjectContextMenu" end
if ISWorldObjectContextMenu and ISWorldObjectContextMenu.onActivateGenerator
   and not ISWorldObjectContextMenu._BGI_wrapActivateGenerator then
    local oldActivateGenerator = ISWorldObjectContextMenu.onActivateGenerator
    function ISWorldObjectContextMenu.onActivateGenerator(worldobjects, enable, generator, player)
        local result = oldActivateGenerator(worldobjects, enable, generator, player)
        if enable and generator and generator.isActivated then
            local added = _BGI_ensureGenRegistered(generator)
            _ensureStates()
            if BGI_PowerRange._unionGenStates[generator] == nil then
                BGI_PowerRange._unionGenStates[generator] = (generator:isActivated() == true)
            end
            if added then BGI_PowerRange._needsUnionRebuild = true end
            if BGI_PowerRange.DebugUnionRebuilds then
                print("[BGI][Union] activation-request added=" .. tostring(added)
                    .. " generators=" .. tostring(#BGI_GenRegistry.getAll()))
            end
        end
        return result
    end
    ISWorldObjectContextMenu._BGI_wrapActivateGenerator = true
end

-- ========== Connect / Disconnect ==========
do
    if ISPlugGenerator and ISPlugGenerator.complete and not ISPlugGenerator._BGI_wrapComplete then
        local _oldComplete = ISPlugGenerator.complete

        --- Runs when the connect/disconnect timed action completes.
        function ISPlugGenerator:complete()
            local ok = _oldComplete(self) -- vanilla sets setConnected(self.plug)
            local gen = self.generator
            if gen then
                -- Make sure it’s tracked (covers cases where add didn’t fire)
                _BGI_ensureGenRegistered(gen)

                -- Seed/update activation snapshot (connection may gate eligibility)
                _ensureStates()
                BGI_PowerRange._unionGenStates[gen] = (gen.isActivated and gen:isActivated() == true) or false

                -- Rebuild union once (connected → include; disconnected → exclude)
                _BGI_requestUnionRebuild()
            end
            return ok
        end

        ISPlugGenerator._BGI_wrapComplete = true
    end
end

-- ========== Activate / Deactivate ==========
do
    if ISActivateGenerator and ISActivateGenerator.complete and not ISActivateGenerator._BGI_wrapComplete then
        local _oldComplete = ISActivateGenerator.complete

        --- Runs when the power toggle timed action completes (setActivated + sync).
        function ISActivateGenerator:complete()
            local ok = _oldComplete(self)
            local gen = self.generator
            if gen and gen.isActivated then
                -- Ensure listed & seed snapshot to “current truth”
                _BGI_ensureGenRegistered(gen)
                _ensureStates()
                BGI_PowerRange._unionGenStates[gen] = (gen:isActivated() == true)

                -- Union may change; request a single rebuild
                _BGI_requestUnionRebuild()

                -- If the selected gen toggled, refresh its per-gen ring too
                if BGI_PowerRange.TargetGen == gen then
                    BGI_PowerRange._needsPerGenRebuild = true
                end
            end
            return ok
        end

        ISActivateGenerator._BGI_wrapComplete = true
    end
end

-- ==========================================================
-- [BGI] Expose internal helpers for optional external modules
-- ==========================================================
BGI_PowerRange.drawEdgeList     = _drawEdgeList
BGI_PowerRange.ensureStencil    = _ensureStencil
BGI_PowerRange.getPerGenColor   = _perGenColor
BGI_PowerRange.isOverlayEnabled = _isOverlayEnabled
