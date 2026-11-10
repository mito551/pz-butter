local here = debug.getinfo(1, "S").source:sub(2)
local root = here:gsub("[/\\]?test/lua/authenticz_ground_test.lua$", "")
if root == "" then root = "." end

-- Plan 18 B-D7-2: an MP client must transmit the spawned world item after
-- Authentic Z's creation callbacks, then transmit the persistent marker.
local transmitted = 0
local readyAtTransmit = false
local markerTransmitted = 0

GroundItemSpawnerItems = {{
    x = 10, y = 20, z = 0,
    itemFullType = "Base.Axe",
    offset = {x = 0.1, y = 0.2, z = 0},
    rotation = 90,
    onCreateWorldItem = "afterGroundSpawn",
}}

function afterGroundSpawn(worldItem)
    worldItem.ready = true
end

LuaEventManager = {AddEvent = function() end}
local loadGridSquare
Events = {LoadGridsquare = {Add = function(fn) loadGridSquare = fn end}}

function instanceItem(fullType)
    assert(fullType == "Base.Axe")
    return {setWorldZRotation = function() end}
end

local worldItem = {
    setIgnoreRemoveSandbox = function(self, value) self.ignoreRemove = value end,
    transmitCompleteItemToServer = function(self)
        transmitted = transmitted + 1
        readyAtTransmit = self.ready == true
    end,
}
local placedItem = {getWorldItem = function() return worldItem end}
local emptyList = {size = function() return 0 end}
local marker = {
    data = {},
    getModData = function(self) return self.data end,
    transmitModData = function() markerTransmitted = markerTransmitted + 1 end,
}
IsoObject = {new = function() return marker end}

local square = {
    getX = function() return 10 end,
    getY = function() return 20 end,
    getZ = function() return 0 end,
    getObjects = function() return emptyList end,
    getWorldObjects = function() return emptyList end,
    AddWorldInventoryItem = function() return placedItem end,
    AddSpecialObject = function() end,
}

dofile(root .. "/mod/PZButter_AuthenticZ/42/media/lua/client/AuthenticZ_GroundItemSpawner.lua")
assert(loadGridSquare, "spawner did not register LoadGridsquare")
loadGridSquare(square)

assert(transmitted == 1, "MP spawn was not transmitted to the server")
assert(readyAtTransmit, "world item was transmitted before its creation callback")
assert(worldItem.ignoreRemove == true, "world item cleanup protection was not set")
assert(marker.data.spawnedItems["Base.Axe"] == true, "persistent spawn marker was not saved")
assert(markerTransmitted == 1, "persistent spawn marker was not transmitted")
print("authenticz_ground_test: ok")
