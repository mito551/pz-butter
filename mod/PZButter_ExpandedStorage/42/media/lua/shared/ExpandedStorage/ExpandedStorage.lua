-- Expanded Storage (ZB): load-after replacement for ExpandedStorage's own ExpandedStorage.lua.
--
-- Same behaviour, but the per-square scan and the per-object OnObjectAdded handler stand
-- down once the Java hooks (ExpandedStorageZB.jar) are live: they set the same capacities
-- when objects are created or loaded. Without Java (no ZombieBuddy, jar failed to load)
-- everything below runs exactly as in the original.
--
-- Keep CAPACITY_BY_SPRITE in sync with tools/resources/expandedstorage/capacities.properties
-- (tools/test/check_sync.py verifies it).

local CAPACITY_BY_SPRITE = {
    ["carpentry_01_16"] = 80,
    ["carpentry_01_17"] = 80,
    ["carpentry_01_18"] = 80,
    ["carpentry_01_19"] = 60,
    ["carpentry_01_20"] = 60,
    ["carpentry_01_21"] = 60,
    ["location_military_generic_01_0"] = 100,
    ["location_military_generic_01_1"] = 100,
    ["location_military_generic_01_2"] = 100,
    ["location_military_generic_01_3"] = 100,
    ["location_military_generic_01_4"] = 100,
    ["location_military_generic_01_5"] = 100,
    ["location_military_generic_01_8"] = 100,
    ["location_military_generic_01_9"] = 100,
    ["location_military_generic_01_10"] = 100,
    ["location_military_generic_01_11"] = 100,
    ["location_military_generic_01_12"] = 100,
    ["location_military_generic_01_13"] = 100,
    ["location_military_generic_01_22"] = 100,
    ["location_military_generic_01_23"] = 100,
    ["location_military_generic_01_30"] = 100,
    ["location_military_generic_01_31"] = 100,
    ["furniture_storage_02_8"] = 60,
    ["furniture_storage_02_9"] = 60,
    ["furniture_storage_02_10"] = 60,
    ["furniture_storage_02_11"] = 60,
    ["furniture_storage_02_28"] = 20,
    ["furniture_storage_02_29"] = 20,
    ["furniture_storage_02_30"] = 20,
    ["furniture_storage_02_31"] = 20,
    ["furniture_shelving_01_24"] = 100,
    ["furniture_shelving_01_25"] = 100,
    ["furniture_shelving_01_26"] = 100,
    ["furniture_shelving_01_27"] = 100,
    ["location_shop_greenes_01_35"] = 50,
    ["location_shop_greenes_01_36"] = 50,
    ["crafted_05_16"] = 80,
    ["crafted_05_17"] = 80,
    ["crafted_05_18"] = 80,
    ["crafted_05_19"] = 80,
    ["furniture_storage_02_0"] = 100,
    ["furniture_storage_02_1"] = 100,
    ["furniture_storage_02_2"] = 100,
    ["furniture_storage_02_3"] = 100,
    ["crafted_05_20"] = 50,
    ["crafted_05_21"] = 50,
    ["crafted_05_22"] = 50,
    ["crafted_05_23"] = 50,
    ["furniture_shelving_01_40"] = 60,
    ["furniture_shelving_01_41"] = 60,
    ["furniture_shelving_01_42"] = 60,
    ["furniture_shelving_01_43"] = 60,
    ["furniture_shelving_01_44"] = 60,
    ["furniture_shelving_01_45"] = 60,
    ["furniture_shelving_01_46"] = 60,
    ["furniture_shelving_01_47"] = 60,
    ["furniture_shelving_01_9"] = 60,
    ["furniture_shelving_01_10"] = 60,
    ["furniture_shelving_01_11"] = 60,
    ["furniture_shelving_01_12"] = 60,
    ["furniture_shelving_01_8"] = 100,
    ["furniture_shelving_01_13"] = 100,
    ["furniture_shelving_01_14"] = 100,
    ["furniture_shelving_01_15"] = 100,
    ["carpentry_02_64"] = 40,
    ["carpentry_02_65"] = 40,
    ["carpentry_02_66"] = 40,
    ["carpentry_02_67"] = 40,
    ["crafted_04_92"] = 30,
    ["crafted_04_93"] = 30,
    ["crafted_04_94"] = 30,
    ["crafted_04_95"] = 30,
}

-- Sprite properties are set once so newly built containers start with the right capacity.
local function applySpriteCapacities()
    local spriteManager = IsoSpriteManager.instance
    if not spriteManager then
        return
    end

    for spriteName, capacity in pairs(CAPACITY_BY_SPRITE) do
        local sprite = spriteManager:getSprite(spriteName)
        local properties = sprite and sprite:getProperties()
        if properties then
            properties:set("ContainerCapacity", tostring(capacity))
        end
    end
end

-- Latches true once Java has proven it is live, so later calls cost one upvalue read.
local javaLive = false
local function javaHandlesContainers()
    if javaLive then
        return true
    end
    javaLive = esZBActive ~= nil and esZBActive() == true
    return javaLive
end

local function applyObjectCapacity(object)
    local sprite = object and object:getSprite()
    local capacity = sprite and CAPACITY_BY_SPRITE[sprite:getName()]
    if not capacity then
        return
    end

    local container = object:getContainer()
    if container and container:getCapacity() ~= capacity then
        container:setCapacity(capacity)
    end
end

local function onObjectAdded(object)
    if javaHandlesContainers() then
        return
    end
    applyObjectCapacity(object)
end

local function applySquareCapacities(square)
    if not square or javaHandlesContainers() then
        return
    end

    -- One pass per square, remembered in the square's own modData.
    local modData = square.getModData and square:getModData() or nil
    if modData then
        if modData.ES_capApplied then
            return
        end
        modData.ES_capApplied = true
    end

    local objects = square:getObjects()
    if not objects then
        return
    end

    for index = 0, objects:size() - 1 do
        applyObjectCapacity(objects:get(index))
    end
end

local initialized = false
local function initialize()
    if initialized then
        return
    end
    initialized = true
    applySpriteCapacities()
end

-- Set sprite properties as early as possible so placed objects inherit capacity.
if Events.OnGameBoot then
    Events.OnGameBoot.Add(initialize)
end
Events.OnGameStart.Add(initialize)
Events.OnServerStarted.Add(initialize)
Events.OnObjectAdded.Add(onObjectAdded)
Events.LoadGridsquare.Add(applySquareCapacities)

-- Once the world is in and the Java hooks have proven live, the two per-object and
-- per-square listeners have nothing left to do: drop them, so a streamed square costs
-- no Lua call at all. (Not done from inside the listeners: removing a listener while
-- its event is being dispatched can make the engine skip the next one.)
Events.OnGameStart.Add(function()
    if javaHandlesContainers() then
        Events.OnObjectAdded.Remove(onObjectAdded)
        Events.LoadGridsquare.Remove(applySquareCapacities)
    end
end)
