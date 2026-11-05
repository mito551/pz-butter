--[[
    CD_Perf build of Companion Dogs. Same behaviour, much less work per tick.
    Regenerate with tools/build_cd.py after every update of Companion Dogs.
]]
do
    local m = getModInfoByID and getModInfoByID("CompanionDogs")
    local v = m and m:getModVersion()
    if v and v ~= "0.7.4" then
        print("[CD_Perf] WARNING: Companion Dogs is " .. tostring(v) .. ", this override was built from 0.7.4; regenerate CD_Perf with tools/build_cd.py")
    end
end

CompanionDogs = CompanionDogs or {}
local CD = CompanionDogs

local function worldMinutes()
    return getGameTime():getWorldAgeHours() * 60
end

local function isThreatZombie(o)
    if not instanceof(o, "IsoZombie") or o:isDead() then return false end
    if CD.isFriendlyNPC(o) then return false end
    local grappleOnly = o:isReanimatedForGrappleOnly()
    local fake = o:isFakeDead() or o:getVariableBoolean("bfakedead")
    return not grappleOnly and not fake
end

-- getSquare() ~= nil nao prova que o zumbi existe a remoçao só o tira de movingObjects e nunca
-- zera o campo. Quem responde e a lista de movingObjects do square.
---@param o IsoMovingObject|nil
---@return boolean
---@nodiscard
function CD.inWorldNow(o)
    if not o then return false end
    local sq = o:getSquare()
    if not sq then return false end
    local mo = sq:getMovingObjects()
    for i = 0, mo:size() - 1 do
        if mo:get(i) == o then return true end
    end
    return false
end

CD.zbox = CD.zbox or {}
CD.zseq = CD.zseq or 0

---Threat zombies in the square window of radius R around a tile; reuses this tick's snapshot of the dog when a wider window already covers it.
---@param animal IsoAnimal
---@param cx integer window center tile
---@param cy integer
---@param z integer
---@param R integer radius in tiles
---@param perfKey string|nil
---@return IsoZombie[]
function CD.zsnap(animal, cx, cy, z, R, perfKey)
    local id = animal:getOnlineID()
    local m = CD.zbox[id]
    if m and m.seq == CD.zseq and m.z == z
        and math.abs(cx - m.cx) + R <= m.R and math.abs(cy - m.cy) + R <= m.R then
        if cx == m.cx and cy == m.cy and R == m.R then return m.list end
        local out = {}
        for i = 1, #m.list do
            local o = m.list[i]
            if math.abs(math.floor(o:getX()) - cx) <= R and math.abs(math.floor(o:getY()) - cy) <= R then
                out[#out + 1] = o
            end
        end
        return out
    end

    local list = CD.PerfNear.zombies(animal, cx, cy, z, R)
    CD.zbox[id] = { seq = CD.zseq, cx = cx, cy = cy, z = z, R = R, list = list }
    return list
end

---True when this pass skips the zombie scan; each call spends one of the skips a quiet streak earned.
---@param d table companion ModData
---@return boolean
function CD.scanQuietSkip(d)
    if CD.SCAN_QUIET_SKIP_MAX <= 0 then return false end
    if (d.zSkip or 0) > 0 then
        d.zSkip = d.zSkip - 1
        return true
    end
    return false
end

---After SCAN_QUIET_STREAK empty scans in a row, the next SCAN_QUIET_SKIP_MAX scans are skipped.
---@param d table companion ModData
---@param found boolean the scan saw a zombie
function CD.scanQuietResult(d, found)
    if found then
        d.zQuiet, d.zSkip = 0, 0
        return
    end
    d.zQuiet = (d.zQuiet or 0) + 1
    if d.zQuiet >= CD.SCAN_QUIET_STREAK then
        d.zSkip = CD.SCAN_QUIET_SKIP_MAX
    end
end

---Something is going on: drops the quiet streak and any pending skip, so the next pass scans.
---@param d table companion ModData
function CD.scanAlert(d)
    d.zQuiet, d.zSkip = 0, 0
end

local function pathBlocked(fromObj, toObj)
    local blocked = false
    if fromObj and toObj then
        blocked = LosUtil.lineClearCollide(
            math.floor(toObj:getX()), math.floor(toObj:getY()), math.floor(toObj:getZ()),
            math.floor(fromObj:getX()), math.floor(fromObj:getY()), math.floor(fromObj:getZ()),
            false)
    end
    return blocked
end
CD.isThreatZombie, CD.pathBlocked = isThreatZombie, pathBlocked

local SCAN_LOS_CAP = 8
local function scanZombies(animal, radius, owner, ref, allowNil, detourMult)
    local ax = math.floor(animal:getX())
    local ay = math.floor(animal:getY())
    local az = math.floor(animal:getZ())
    local R = math.ceil(radius)
    local refZ = ref and math.floor(ref:getZ()) or az
    local cx, cy, Rwin = ax, ay, R
    local ownerSide = ref ~= nil and ref ~= animal
    if ownerSide then
        local dOR = CD.dist2D(animal, ref)
        if dOR > 1 then
            cx = math.floor((animal:getX() + ref:getX()) / 2)
            cy = math.floor((animal:getY() + ref:getY()) / 2)
            Rwin = math.min(R + math.ceil(dOR / 2), R + 6)
        end
    end
    local region, regionTried
    local unr = CD.combatUnreach[animal:getOnlineID()]
    local unrNow = unr and getTimestampMs() or 0
    local cands, count, threatensUs = {}, 0, false
    local zlist = CD.zsnap(animal, cx, cy, az, Rwin, "sq.zombies")
    for zi = 1, #zlist do
        local o = zlist[zi]
        local coolUntil = unr and unr[o]
        if coolUntil and coolUntil <= unrNow then
            unr[o] = nil
            coolUntil = nil
        end
        if not coolUntil then
            local dDog = CD.dist2D(animal, o)
            local dRef = ref and CD.dist2D(ref, o) or dDog
            if dDog <= radius or (ownerSide and dRef <= radius) then
                local tgtOwner, tgtDog = false, false
                if instanceof(o, "IsoZombie") then
                    local t = o:getTarget()
                    if owner and t == owner then
                        tgtOwner = true
                    end
                    if t == animal then
                        tgtDog = true
                    end
                end
                if tgtOwner or tgtDog then
                    threatensUs = true
                end
                if not regionTried then
                    regionTried = true
                    region = CD.combatReachRegion(animal)
                end
                local reach = true
                if region and math.floor(o:getZ()) == az then
                    local zx, zy = math.floor(o:getX()), math.floor(o:getY())
                    local depth = region.set[zx * CD.TILEKEY_STRIDE + zy]
                    local inWindow = math.abs(zx - region.sx) <= region.radius
                        and math.abs(zy - region.sy) <= region.radius
                    if not region.truncated and inWindow then
                        if not depth then
                            reach = false
                        elseif depth > dDog * (detourMult or CD.COMBAT_DETOUR_FACTOR) + CD.COMBAT_DETOUR_ADD + (region.drift or 0) then
                            reach = false
                        end
                    end
                end
                if dDog <= radius then
                    count = count + 1
                end
                if reach then
                    cands[#cands + 1] = {
                        o = o, dDog = dDog, dRef = dRef,
                        active = tgtOwner or tgtDog,
                        sameZ = math.floor(o:getZ()) == refZ,
                    }
                end
            end
        end
    end
    if #cands == 0 then return nil, count, threatensUs end

    table.sort(cands, function(a, b)
        if a.active ~= b.active then return a.active end
        if a.dRef ~= b.dRef then return a.dRef < b.dRef end
        return a.dDog < b.dDog
    end)

    local fallback, tested = nil, 0
    for _, c in ipairs(cands) do
        if c.active or c.sameZ then
            if not fallback then
                fallback = c.o
            end
            if tested < SCAN_LOS_CAP then
                tested = tested + 1
                if not pathBlocked(animal, c.o) then return c.o, count, threatensUs end
            end
        end
    end

    if fallback then return fallback, count, threatensUs end
    if allowNil then return nil, count, threatensUs end
    return cands[1].o, count, threatensUs
end
CD.scanZombies = scanZombies

local function playerFighting(owner)
    return owner ~= nil and owner:isAttacking()
end
CD.playerFighting = playerFighting

local function warnRefusalInCombat(animal, owner, d)
    if not owner or not playerFighting(owner) then return end
    local now = worldMinutes()
    if d.lastRefuseWarnMin and (now - d.lastRefuseWarnMin) < CD.REFUSE_WARN_COOLDOWN_MIN then return end
    d.lastRefuseWarnMin = now
    CD.notifyOwner(owner, "refused", { reason = CD.refusalReason(animal), name = d.name, breed = CD.getBreed(animal) })
end

local function updatePanicNotify(animal, owner, d)
    local tier = CD.panicTier(animal)
    if tier >= 2 and (d.lastPanicTier or 0) < 2 then
        CD.notifyOwner(owner, "panic", { name = d.name, breed = CD.getBreed(animal) })
    end
    d.lastPanicTier = tier
end

CD.updatePanicNotify = updatePanicNotify
CD.warnRefusalInCombat = warnRefusalInCombat
