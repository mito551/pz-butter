--[[
    Author: Konijima
    Made for AuthenticPeach and the community.
    Date: 05/02/2022

    Let modder add items into the world at specific world position.
    Item is saved into an invisible IsoObject on the square to prevent it from spawning again.
    If new items is added to the GroundItemSpawnerItems list they will spawn next time the game is running.
    You cannot spawn multiple instance of the same item on the same square.

    AZ_Perf: Build 42, single-player only. Spawn data is indexed by a numeric
    coordinate key so the LoadGridsquare hot path allocates no strings.
    In MP every client would load the square and spawn its own copy before the
    marker syncs, so the spawner is not registered there.
--]]

if isClient() or isServer() then return end

local initialized = false
local itemsByCoord = {}

-- Numeric key, unique for x/y < 100000 and small z (fits exactly in a double).
local function coordKey(x, y, z)
    return (z * 100000 + y) * 100000 + x
end

local function InitializeGroundItemSpawner()
    if initialized then return end
    initialized = true

    for i = 1, #GroundItemSpawnerItems do
        local data = GroundItemSpawnerItems[i]
        local key = coordKey(data.x, data.y, data.z)
        local list = itemsByCoord[key]
        if not list then
            list = {}
            itemsByCoord[key] = list
        end
        list[#list + 1] = data
    end

    print("GroundItemSpawner: " .. #GroundItemSpawnerItems .. " items registered")
end

-- Spawns one item on the square unless it is already there / was spawned before.
-- Returns the (possibly newly created) marker object and its mod data.
local function spawnItem(square, worldObjects, itemData, groundObject, groundModData)
    local fullType = itemData.itemFullType

    for w = 0, worldObjects:size() - 1 do
        local worldObject = worldObjects:get(w)
        local worldItem = worldObject and worldObject:getItem()
        if worldItem and worldItem:getFullType() == fullType then
            return groundObject, groundModData
        end
    end

    if groundModData and groundModData.spawnedItems and groundModData.spawnedItems[fullType] then
        return groundObject, groundModData
    end

    local invItem = instanceItem(fullType)
    if not invItem then
        print("[AuthenticZ] GroundItemSpawner: Failed to create " .. tostring(fullType))
        return groundObject, groundModData
    end

    -- Apply rotation before placing the item.
    if itemData.rotation == 0 then
        invItem:randomizeWorldZRotation()
    elseif type(itemData.rotation) == "number" then
        invItem:setWorldZRotation(itemData.rotation)
    end

    local placedItem = square:AddWorldInventoryItem(
        invItem,
        itemData.offset.x,
        itemData.offset.y,
        itemData.offset.z,
        false
    )
    if not placedItem then
        print("[AuthenticZ] GroundItemSpawner: Failed to place " .. tostring(fullType))
        return groundObject, groundModData
    end

    local worldItem = placedItem:getWorldItem()
    if worldItem then
        worldItem:setIgnoreRemoveSandbox(true)
    end

    -- Save the persistent spawn marker before optional callbacks.
    if not groundObject then
        groundObject = IsoObject.new(square, nil)
        square:AddSpecialObject(groundObject)
        groundModData = groundObject:getModData()
    end
    groundModData.spawnedItems = groundModData.spawnedItems or {}
    groundModData.spawnedItems[fullType] = true
    groundObject:transmitModData()

    if itemData.onCreateItem and type(_G[itemData.onCreateItem]) == "function" then
        _G[itemData.onCreateItem](invItem, square)
    end
    if itemData.onCreateWorldItem and type(_G[itemData.onCreateWorldItem]) == "function" and worldItem then
        _G[itemData.onCreateWorldItem](worldItem, square)
    end

    return groundObject, groundModData
end

local function onLoadGridSquare(square)
    if not square then return end

    InitializeGroundItemSpawner()

    -- Ignore every square that isn't registered with the spawner.
    local itemsToSpawn = itemsByCoord[coordKey(square:getX(), square:getY(), square:getZ())]
    if not itemsToSpawn then return end

    local squareObjects = square:getObjects()
    local worldObjects = square:getWorldObjects()

    -- Find the persistent marker object if this square already has one.
    local groundObject, groundModData
    for i = 0, squareObjects:size() - 1 do
        local sqObj = squareObjects:get(i)
        local modData = sqObj and sqObj:getModData()
        if modData and modData.spawnedItems then
            groundObject = sqObj
            groundModData = modData
            break
        end
    end

    for i = 1, #itemsToSpawn do
        groundObject, groundModData = spawnItem(square, worldObjects, itemsToSpawn[i], groundObject, groundModData)
    end
end
Events.LoadGridsquare.Add(onLoadGridSquare)
