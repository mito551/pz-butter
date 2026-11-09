-- FILE: AuthenticZ_Glowstick.lua

local lightByPlayer = {}
local updateCountByPlayer = {}

local ACTIVE_UPDATE_INTERVAL = 3
local INACTIVE_UPDATE_INTERVAL = 10
-- Keep LIGHT_RADIUS and the colours below in sync with azperf/AZPerf.java
-- (ground glowsticks) so carried and dropped sticks look identical.
local LIGHT_RADIUS = 7

local workingGlowSticksData = {
    ["AuthenticGlowstick_Blue_On"]   = { 0.0,  0.0,  1.0 },
    ["AuthenticGlowstick_Red_On"]    = { 1.0,  0.0,  0.0 },
    ["AuthenticGlowstick_Green_On"]  = { 0.0,  1.0,  0.0 },
    ["AuthenticGlowstick_Purple_On"] = { 1.0,  0.0,  1.0 },
    ["AuthenticGlowstick_Yellow_On"] = { 1.0,  1.0,  0.0 },
    ["AuthenticGlowstick_Orange_On"] = { 1.0,  0.30, 0.0 },
    ["AuthenticGlowstick_Pink_On"]   = { 1.0,  0.0,  0.25 },
    ["AuthenticGlowstick_White_On"]  = { 1.0,  1.0,  1.0 },
}

-- Light state
local function getPlayerLights(player)
    local lights = lightByPlayer[player]

    if not lights then
        lights = {}
        lightByPlayer[player] = lights
    end

    return lights
end


local function hasActiveLights(player)
    local lights = lightByPlayer[player]

    if not lights then
        return false
    end

    for _, _ in pairs(lights) do
        return true
    end

    return false
end


local function removeLight(player, positionKey)
    local lights = lightByPlayer[player]

    if not lights then
        return
    end

    local state = lights[positionKey]

    if not state then
        return
    end

    if state.light then
        state.light:setActive(false)
        getCell():removeLamppost(state.light)
    end

    lights[positionKey] = nil
end


-- Create/update light only when something actually changed
local function updateLight(player, item, positionKey)
    -- Lights are client-side rendering; a dedicated server has nothing to light.
    if isServer() then
        return
    end

    local itemType = item:getType()
    local color = workingGlowSticksData[itemType]

    if not color then
        return
    end

    local x = math.floor(player:getX())
    local y = math.floor(player:getY())
    local z = math.floor(player:getZ())

    local lights = getPlayerLights(player)
    local state = lights[positionKey]


    -- Same item, type and tile = existing light is still correct.
    if state
    and state.item == item
    and state.itemType == itemType
    and state.x == x
    and state.y == y
    and state.z == z then
        return
    end


    -- Something changed, remove the previous light for this slot.
    if state then
        removeLight(player, positionKey)
    end


    local light = IsoLightSource.new(
        x,
        y,
        z,
        color[1],
        color[2],
        color[3],
        LIGHT_RADIUS
    )

    light:setActive(true)
    getCell():addLamppost(light)


    lights[positionKey] = {
        light = light,
        item = item,
        itemType = itemType,
        x = x,
        y = y,
        z = z
    }
end

-- Replace depleted glowstick
local function replaceDepletedGlowstick(player, item)
    local wasPrimary =
        item == player:getPrimaryHandItem()

    local wasSecondary =
        item == player:getSecondaryHandItem()


    -- Remove it from an attachment slot if necessary.
    if item:getAttachedSlot() ~= -1 then
        player:removeAttachedItem(item)

        item:setAttachedSlot(-1)
        item:setAttachedSlotType(nil)
        item:setAttachedToModel(nil)
    end


    local newItem =
        player:getInventory():AddItem(
            "AuthenticGlowstick_Blank"
        )


    if wasPrimary then
        player:setPrimaryHandItem(newItem)
    end

    if wasSecondary then
        player:setSecondaryHandItem(newItem)
    end


    player:getInventory():Remove(item)
end

-- Process one carried glowstick
local function processGlowstick(
    player,
    item,
    positionKey,
    seenKeys,
    seenItems
)
    if not item then
        return
    end


    -- Prevent the same item being processed twice.
    if seenItems[item] then
        return
    end


    if not workingGlowSticksData[item:getType()] then
        return
    end


    seenItems[item] = true
    seenKeys[positionKey] = true


    -- Depleted glowstick.
    if item:getCurrentUsesFloat() <= 0 then

        removeLight(
            player,
            positionKey
        )

        replaceDepletedGlowstick(
            player,
            item
        )

        return
    end


    updateLight(
        player,
        item,
        positionKey
    )
end

-- Carried glowstick update
local function glowstickUpdate(player)
    if not player then
        return
    end


    -- Players already carrying an active light update more often.
    local updateInterval =
        hasActiveLights(player)
        and ACTIVE_UPDATE_INTERVAL
        or INACTIVE_UPDATE_INTERVAL


    local updateCount =
        (updateCountByPlayer[player] or 0) + 1


    if updateCount < updateInterval then
        updateCountByPlayer[player] = updateCount
        return
    end


    updateCountByPlayer[player] = 0


    local seenKeys = {}
    local seenItems = {}


    -- Primary hand.
    processGlowstick(
        player,
        player:getPrimaryHandItem(),
        "PRIMARY",
        seenKeys,
        seenItems
    )


    -- Secondary hand.
    processGlowstick(
        player,
        player:getSecondaryHandItem(),
        "SECONDARY",
        seenKeys,
        seenItems
    )


    -- Attached / hotbar items.
    local attached =
        player:getAttachedItems()


    if attached then
        for i = 0, attached:size() - 1 do
            local item =
                attached:getItemByIndex(i)


            if item then
                local location =
                    attached:getLocation(item)


                local positionKey =
                    "ATTACHED:"
                    .. tostring(
                        location
                        or item:getAttachedSlot()
                    )


                processGlowstick(
                    player,
                    item,
                    positionKey,
                    seenKeys,
                    seenItems
                )
            end
        end
    end


    -- Remove lights belonging to items that are no longer equipped.
    local lights =
        lightByPlayer[player]


    if lights then
        local staleKeys = {}


        for positionKey, _ in pairs(lights) do
            if not seenKeys[positionKey] then
                staleKeys[#staleKeys + 1] =
                    positionKey
            end
        end


        for _, positionKey in ipairs(staleKeys) do
            removeLight(
                player,
                positionKey
            )
        end
    end
end

Events.OnPlayerUpdate.Add(glowstickUpdate)

-- Drop per-player state so dead/removed players aren't kept alive by these tables.
local function clearPlayerState(player)
    local lights = lightByPlayer[player]
    if lights then
        for positionKey, _ in pairs(lights) do
            removeLight(player, positionKey)
        end
    end

    lightByPlayer[player] = nil
    updateCountByPlayer[player] = nil
end

Events.OnPlayerDeath.Add(clearPlayerState)

-- Ground glowstick drain
local GROUND_SCAN_RADIUS = 20

local function forEachGlowstickPlayer(callback)
    if isServer() then

        local players =
            getOnlinePlayers()

        if not players then
            return
        end


        for i = 0, players:size() - 1 do
            local player =
                players:get(i)

            if player then
                callback(player)
            end
        end

    else

        for i = 0, getNumActivePlayers() - 1 do
            local player =
                getSpecificPlayer(i)

            if player then
                callback(player)
            end
        end
    end
end

local function updateGroundGlowsticks()
    -- In MP the server drains and syncs; a client draining too would double it.
    if isClient() then return end

    -- Shared across players so an item near several of them drains once per minute.
    local processed = {}

    forEachGlowstickPlayer(function(player)
        local px = math.floor(player:getX())
        local py = math.floor(player:getY())
        local pz = math.floor(player:getZ())
        local cell = getCell()
        if not cell then return end

        local radius = GROUND_SCAN_RADIUS
        local radiusSq = radius * radius

        for x = px - radius, px + radius do
            local dx = x - px

            for y = py - radius, py + radius do
                local dy = y - py

                if dx * dx + dy * dy <= radiusSq then
                    local square = cell:getGridSquare(x, y, pz)

                    if square then
                        local objects = square:getWorldObjects()

                        for i = 0, objects:size() - 1 do
                            local worldObj = objects:get(i)

                            if worldObj and not processed[worldObj] then
                                processed[worldObj] = true

                                local item = worldObj:getItem()

                                if item
                                and workingGlowSticksData[item:getType()]
                                and instanceof(item, "DrainableComboItem") then
                                    item:UseAndSync()
                                end
                            end
                        end
                    end
                end
            end
        end
    end)
end

Events.EveryOneMinute.Add(updateGroundGlowsticks)
