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

local function troughItemEdible(item, breedKey)
    return CD.isTroughFood(item, breedKey)
end

local function troughHasFood(trough, breedKey)
    local cont = trough:getContainer()
    if not cont then return false end
    local items = cont:getItems()
    for i = 0, items:size() - 1 do
        if troughItemEdible(items:get(i), breedKey) then return true end
    end
    return false
end

local function troughHasWater(trough)
    local w = trough:getWater()
    return w ~= nil and w > 0
end

---@return string|nil protein family of the portion eaten, for the food allergy
local function consumeTroughFood(trough, breedKey)
    local cont = trough:getContainer()
    if not cont then return nil end
    local items = cont:getItems()
    for i = 0, items:size() - 1 do
        local item = items:get(i)
        if troughItemEdible(item, breedKey) then
            local group = CD.vetProteinGroup(item, breedKey)
            if instanceof(item, "DrainableComboItem") then
                -- DrainableComboItem nao tem getUsedDelta() (so o setter): le o preenchimento por getCurrentUsesFloat().
                local du = item:getUseDelta()
                local cur = item:getCurrentUsesFloat()
                local target = cur - du * 5
                if target <= 0.0001 then
                    if isServer() then
                        sendRemoveItemFromContainer(cont, item)
                    end
                    cont:Remove(item)
                else
                    item:setUsedDelta(target)
                    item:syncItemFields()
                end
            else
                local hc = (instanceof(item, "Food") and item:getHungerChange()) or 0
                local portion = CD.TROUGH_FOOD_PORTION
                if hc >= -portion then
                    if instanceof(item, "Food") then
                        item:setHungChange(0)
                        item:UseAndSync()
                    else
                        if isServer() then
                            sendRemoveItemFromContainer(cont, item)
                        end
                        cont:Remove(item)
                    end
                else
                    item:multiplyFoodValues((hc + portion) / hc)
                    item:syncItemFields()
                end
            end
            cont:setDrawDirty(true)
            trough:checkOverlayAfterAnimalEat()
            trough:updateLuaObject()
            return group
        end
    end
end

local function consumeTroughWater(trough, amount)
    trough:removeWater(amount)
    trough:checkOverlayAfterAnimalEat()
    trough:updateLuaObject()
end

local function troughAt(x, y, z)
    local cell = getCell()
    if not cell then return nil end
    local sq = cell:getGridSquare(x, y, z)
    if not sq then return nil end
    local objs = sq:getObjects()
    for i = 0, objs:size() - 1 do
        local o = objs:get(i)
        if instanceof(o, "IsoFeedingTrough") then return o end
    end
    return nil
end

local function dishStock(item, kind)
    local md = item:getModData()
    if kind == "water" then return md.cdWater or 0 end
    return md.cdFoodMeals or 0
end

local function dishAt(x, y, z, wantKind)
    local cell = getCell()
    if not cell then return nil end
    local sq = cell:getGridSquare(x, y, z)
    if not sq then return nil end
    local wobs = sq:getWorldObjects()
    for i = 0, wobs:size() - 1 do
        local wo = wobs:get(i)
        local item = wo:getItem()
        if item and CD.isDishType(item:getFullType()) and dishStock(item, wantKind) > 0 then
            return wo, item
        end
    end
    return nil
end

---@return number meat points eaten
---@return string|nil protein family of those points, for the food allergy
local function consumeDishFood(item, points)
    local fromMeat = 0
    local group = nil
    if item then
        local md = item:getModData()
        local p = points or 0
        md.cdFoodMeals = math.max(0, (md.cdFoodMeals or 0) - p)
        local meat = md.cdFoodMeat or 0
        if meat > 0 and p > 0 then
            fromMeat = math.min(meat, p)
            md.cdFoodMeat = meat - fromMeat
            group = md.cdFoodProtein
            if md.cdFoodMeat <= 0 then
                md.cdFoodProtein = nil
            end
        end
        CD.refreshDishModel(item)
    end
    return fromMeat, group
end

local function consumeDishWater(item, amount)
    if item then
        local md = item:getModData()
        md.cdWater = math.max(0, (md.cdWater or 0) - amount)
        CD.refreshDishModel(item)
    end
end

local troughZoneCalls = {}

local function cfgOn(key)
    local c = rawget(_G, "CD_PerfPatchConfig")
    return not (type(c) == "table" and c[key] == false)
end

local function findTroughNear(animal, radius, wantFood, wantWater)
    local cell = getCell()
    if not cell then return nil end
    local breedKey = CD.getBreed(animal)
    local ax = math.floor(animal:getX())
    local ay = math.floor(animal:getY())
    local az = math.floor(animal:getZ())
    local R = math.ceil(radius)
    local id = animal:getOnlineID()
    local calls = (troughZoneCalls[id] or 0) + 1
    troughZoneCalls[id] = calls
    -- ranch fast path: the engine's own list of the troughs in the dog's animal zones.
    -- Every 3rd search per dog still does the full one, so a closer dish is not missed for long.
    local dzones = cfgOn("troughZone") and calls % 3 ~= 0 and animal:getConnectedDZone()
    if dzones and dzones:size() > 0 then
        local list = animal:getBehavior():getRandomTroughList()
        local best, bestKind, bestDist = nil, nil, radius + 1
        local bx, by, bz
        for i = 0, list:size() - 1 do
            local o = list:get(i)
            if math.floor(o:getZ()) == az then
                local kind
                if wantFood and troughHasFood(o, breedKey) then kind = "food"
                elseif wantWater and troughHasWater(o) then kind = "water" end
                if kind then
                    local dist = CD.dist2D(animal, o)
                    if dist <= radius and dist < bestDist then
                        best, bestKind, bestDist = o, kind, dist
                        bx, by, bz = o:getX(), o:getY(), o:getZ()
                    end
                end
            end
        end
        if best then return best, bestKind, bx, by, bz, false, nil end
    end

    -- troughs and dishes in the original's visiting order (square by square, troughs first),
    -- so equal distances resolve the same way
    local items = CD.PerfNear.feed(animal, R)
    local best, bestKind, bestDist, bestDish, bestItem = nil, nil, radius + 1, false, nil
    local bx, by, bz
    for i = 1, #items do
        local o = items[i]
        if instanceof(o, "IsoFeedingTrough") then
            local kind
            if wantFood and troughHasFood(o, breedKey) then kind = "food"
            elseif wantWater and troughHasWater(o) then kind = "water" end
            if kind then
                local dist = CD.dist2D(animal, o)
                if dist <= radius and dist < bestDist then
                    best, bestKind, bestDist, bestDish, bestItem = o, kind, dist, false, nil
                    bx, by, bz = o:getX(), o:getY(), o:getZ()
                end
            end
        else
            local item = o:getItem()
            if item and CD.isDishType(item:getFullType()) then
                local kind
                if wantFood and dishStock(item, "food") > 0 then kind = "food"
                elseif wantWater and dishStock(item, "water") > 0 then kind = "water" end
                if kind then
                    local dist = CD.dist2D(animal, o)
                    if dist <= radius and dist < bestDist then
                        local sq = o:getSquare()
                        best, bestKind, bestDist, bestDish, bestItem = o, kind, dist, true, item
                        bx, by, bz = sq:getX(), sq:getY(), sq:getZ()
                    end
                end
            end
        end
    end
    if best then return best, bestKind, bx, by, bz, bestDish, bestItem end
    return nil
end


local function walkToFeedTile(animal, d, gx, gy, gz, run, nowMs)
    CD.letMove(animal)
    animal:setVariable("animalRunning", run == true)
    animal:setVariable("animalSpeed", run and CD.runAnimSpeed() or CD.walkAnimSpeed())
    local pathFailed = animal:shouldFollowWall()
    if pathFailed then
        animal:setShouldFollowWall(false)
    end
    CD.pathStatHijack(animal, "tigela")
    local pt = d.feedPathTile
    local sameGoal = pt and pt.x == gx and pt.y == gy and pt.z == gz
    local sinceIssue = (sameGoal and d.feedPathMs) and (nowMs - d.feedPathMs) or 999999
    local stalled = d.feedProgressMs and (nowMs - d.feedProgressMs) >= CD.TROUGH_REPATH_STALL_MS
    if sameGoal and sinceIssue < CD.FOLLOW_PATH_RESOLVE_MS then
        CD.forcePathfind(animal)
        return
    end
    if sameGoal and not stalled and not pathFailed then
        CD.forcePathfind(animal)
        return
    end
    d.feedPathTile = { x = gx, y = gy, z = gz }
    d.feedPathMs = nowMs
    CD.pathStatEmit(animal, "tigela")
    animal:pathToLocation(gx, gy, gz)
    CD.forcePathfind(animal)
end

local function setAutoFeedTag(animal, d, state, kind)
    if d.autoFeeding == state and d.autoFeedKind == kind then return end
    if d.autoFeeding == "eating" then
        CD.emitStopSound(animal, CD.breedVoice(animal, "eat"))
        CD.emitStopSound(animal, CD.breedVoice(animal, "drink"))
        CD.emitEatVar(animal, false, CD.EAT_ANIM_VAR)
        CD.emitEatVar(animal, false, CD.DRINK_ANIM_VAR)
    end
    d.autoFeeding = state
    d.autoFeedKind = state and kind or nil
    if state ~= "going" then
        d.feedPathTile = nil
        d.feedPathMs = nil
        d.feedBestGap = nil
        d.feedProgressMs = nil
        d.feedRun = nil
    end
    if state == "eating" then
        CD.emitEatVar(animal, true, (kind == "water") and CD.DRINK_ANIM_VAR or CD.EAT_ANIM_VAR)
    end
    CD.transmit(animal)
end

local function feedConsume(animal, d, trough, kind, tx, ty, tz, isDish, feedItem, hunger, thirst)
    local gdx, gdy = animal:getX() - (tx + 0.5), animal:getY() - (ty + 0.5)
    local gap = math.sqrt(gdx * gdx + gdy * gdy)
    if gap > CD.TROUGH_REACH_DIST then
        local nowMs = getTimestampMs()
        if not d.feedBestGap or gap < d.feedBestGap - CD.TROUGH_PROGRESS_EPS then
            d.feedBestGap = gap
            d.feedProgressMs = nowMs
        end
        if (nowMs - d.feedProgressMs) > CD.TROUGH_GIVEUP_STALL_MS then
            d.feedingTrough = nil
            d.autoFeedRetryMs = nowMs + CD.TROUGH_RETRY_COOLDOWN_MS
            setAutoFeedTag(animal, d, nil)
            return false
        end
        local run = d.feedRun == true
        if gap > CD.FOLLOW_RUN_DIST then run = true
        elseif gap <= CD.FOLLOW_START_DIST + 1 then run = false end
        run = run and CD.mayRunFrom(d)
        d.feedRun = run
        setAutoFeedTag(animal, d, "going", kind)
        walkToFeedTile(animal, d, math.floor(tx), math.floor(ty), math.floor(tz), run, nowMs)
        return true
    end

    animal:setVariable("animalRunning", false)
    animal:setVariable("animalSpeed", 0)
    animal:faceThisObject(trough)
    animal:stopAllMovementNow()
    CD.holdStill(animal)

    local now = CD.worldMinutes()
    if d.autoFeeding ~= "eating" then
        d.feedStartMin = now
        setAutoFeedTag(animal, d, "eating", kind)
        CD.emitSound(animal, CD.breedVoice(animal, kind == "water" and "drink" or "eat"))
        return true
    end
    if not d.feedStartMin or (now - d.feedStartMin) < CD.TROUGH_EAT_DWELL_MIN then
        return true
    end

    local foodRestore, waterRestore = CD.troughHungerRestore(), CD.troughThirstRestore()
    if isDish then
        if kind == "water" then waterRestore = math.min(waterRestore, dishStock(feedItem, "water") / CD.DISH_POINTS_PER_THIRST)
        else foodRestore = math.min(foodRestore, dishStock(feedItem, "food") / 100) end
    end
    local foodApplied = math.min(foodRestore, hunger)
    local waterApplied = math.min(waterRestore, thirst)
    if kind == "water" then
        CD.setNeed(animal, "thirst", math.max(0, thirst - waterApplied))
    else
        CD.setNeed(animal, "hunger", math.max(0, hunger - foodApplied))
    end
    if kind == "water" then
        if isDish then
            consumeDishWater(feedItem, waterApplied * CD.DISH_POINTS_PER_THIRST)
            if feedItem then
                CD.vetRollDishWater(animal, d, feedItem:getModData())
            end
        else consumeTroughWater(trough, CD.TROUGH_WATER_PER_DRINK) end
    else
        if isDish then
            local meatPts, group = consumeDishFood(feedItem, foodApplied * 100)
            if meatPts > 0 then
                CD.markProteinMeal(d, meatPts)
            end
            CD.vetRollFoodAllergy(animal, group)
        else
            CD.vetRollFoodAllergy(animal, consumeTroughFood(trough, CD.getBreed(animal)))
        end
    end
    if isDish and CD.broadcastDishStock then
        if feedItem then
            CD.broadcastDishStock(tx, ty, tz, feedItem:getModData(), feedItem)
        end
    end
    CD.addCombatStress(animal, -0.2)
    d.feedingTrough = nil
    d.feedStartMin = nil
    d.autoFeedRetryMs = nil
    d.lastTroughEatMin = now
    setAutoFeedTag(animal, d, nil)
    CD.transmit(animal)
    return true
end

local function tryAutoFeed(animal, owner, d)
    local hunger = CD.getHunger(animal)
    local thirst = animal:getThirst()
    local trigger = CD.troughFeedTrigger(owner)
    local hungry = hunger >= trigger
    local thirsty = thirst >= trigger
    if not hungry and not thirsty then
        d.feedingTrough = nil
        d.feedStartMin = nil
        d.autoFeedRetryMs = nil
        setAutoFeedTag(animal, d, nil)
        return false
    end

    if d.autoFeedRetryMs and getTimestampMs() < d.autoFeedRetryMs then
        setAutoFeedTag(animal, d, nil)
        return false
    end

    if animal:getVehicle() ~= nil then
        setAutoFeedTag(animal, d, nil)
        return false
    end

    if d.attackUntilMin and CD.worldMinutes() < d.attackUntilMin then
        setAutoFeedTag(animal, d, nil)
        return false
    end
    if d.feedYieldUntilMin and CD.worldMinutes() < d.feedYieldUntilMin then
        d.feedingTrough = nil
        d.feedStartMin = nil
        setAutoFeedTag(animal, d, nil)
        return false
    end
    if owner and not d.feedingTrough and CD.getState(animal) == CD.STATE_FOLLOW and CD.dist2D(animal, owner) > CD.TELEPORT_DIST then
        setAutoFeedTag(animal, d, nil)
        return false
    end

    local trough, kind, tx, ty, tz, isDish, feedItem
    local cache = d.feedingTrough
    local transientMiss = false
    if cache then
        local hit = false
        if cache.dish then
            local wo, item = dishAt(cache.x, cache.y, cache.z, cache.kind)
            if wo then
                trough, kind, tx, ty, tz, isDish, feedItem = wo, cache.kind, cache.x, cache.y, cache.z, true, item
                hit = true
            end
        else
            local t = troughAt(cache.x, cache.y, cache.z)
            if t and ((cache.kind == "food" and troughHasFood(t, CD.getBreed(animal))) or (cache.kind == "water" and troughHasWater(t))) then
                trough, kind, tx, ty, tz, isDish, feedItem = t, cache.kind, cache.x, cache.y, cache.z, false, nil
                hit = true
            end
        end
        if hit then
            d.feedMissCount = nil
        else
            d.feedMissCount = (d.feedMissCount or 0) + 1
            if d.feedMissCount >= CD.FEED_MISS_TOLERANCE then
                d.feedingTrough = nil
                d.feedStartMin = nil
                d.feedMissCount = nil
            else
                transientMiss = true
            end
        end
    end
    if transientMiss and cache then
        local cx, cy, cz = cache.x, cache.y, cache.z
        local ddx, ddy = animal:getX() - (cx + 0.5), animal:getY() - (cy + 0.5)
        if math.sqrt(ddx * ddx + ddy * ddy) > CD.TROUGH_REACH_DIST then
            setAutoFeedTag(animal, d, "going", cache.kind)
            walkToFeedTile(animal, d, math.floor(cx), math.floor(cy), math.floor(cz), d.feedRun == true, getTimestampMs())
        else
            animal:setVariable("animalRunning", false)
            animal:setVariable("animalSpeed", 0)
            animal:stopAllMovementNow()
            CD.holdStill(animal)
        end
        return true
    end
    if not trough then
        local nowScan = getTimestampMs()
        if d.lastTroughScanMs and (nowScan - d.lastTroughScanMs) < CD.TROUGH_SCAN_THROTTLE_MS then
            setAutoFeedTag(animal, d, nil)
            return false
        end
        d.lastTroughScanMs = nowScan
        local t, k, ox, oy, oz, dsh, item = findTroughNear(animal, CD.troughFeedRadius(), hungry, thirsty)
        if not t then
            setAutoFeedTag(animal, d, nil)
            return false
        end
        trough, kind, tx, ty, tz, isDish, feedItem = t, k, ox, oy, oz, dsh, item
        d.feedingTrough = { x = ox, y = oy, z = oz, kind = k, dish = dsh }
        d.feedBestGap = nil
        d.feedProgressMs = nil
        d.feedPathTile = nil
    end

    if d.inCombat or d.retreating then
        CD.setInCombat(animal, d, false)
        d.retreating = false
    end
    CD.combatTarget[animal:getOnlineID()] = nil

    return feedConsume(animal, d, trough, kind, tx, ty, tz, isDish, feedItem, hunger, thirst)
end
CD.tryAutoFeed, CD.setAutoFeedTag = tryAutoFeed, setAutoFeedTag
CD.findTroughNear = findTroughNear
