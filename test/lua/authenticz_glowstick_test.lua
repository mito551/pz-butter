local here = debug.getinfo(1, "S").source:sub(2)
local root = here:gsub("[/\\]?test/lua/authenticz_glowstick_test.lua$", "")
if root == "" then root = "." end

-- A ground glowstick near two split-screen players drains once a minute, as in the
-- target (its `processed` set is shared by all players). Reverses Plan 18 B-D7-6.
local updateGroundGlowsticks
Events = {
    OnPlayerUpdate = {Add = function() end},
    OnPlayerDeath = {Add = function() end},
    EveryOneMinute = {Add = function(fn) updateGroundGlowsticks = fn end},
}

function isServer() return false end
function getNumActivePlayers() return 2 end

local players = {
    {getX = function() return 10 end, getY = function() return 20 end, getZ = function() return 0 end},
    {getX = function() return 10 end, getY = function() return 20 end, getZ = function() return 0 end},
}
function getSpecificPlayer(index) return players[index + 1] end

local uses = 0
local item = {
    getType = function() return "AuthenticGlowstick_Blue_On" end,
    UseAndSync = function() uses = uses + 1 end,
}
local worldObject = {getItem = function() return item end}
local objects = {
    size = function() return 1 end,
    get = function() return worldObject end,
}
local square = {getWorldObjects = function() return objects end}
local cell = {
    getGridSquare = function(self, x, y, z)
        if x == 10 and y == 20 and z == 0 then return square end
    end,
}
function getCell() return cell end
function instanceof(value, class) return value == item and class == "DrainableComboItem" end

dofile(root .. "/mod/PZButter_AuthenticZ/42/media/lua/server/AuthenticZ_Glowstick.lua")
assert(updateGroundGlowsticks, "ground glowstick drain was not registered")
updateGroundGlowsticks()
assert(uses == 1, "a shared glowstick must drain once per minute; got " .. uses)
print("authenticz_glowstick_test: ok")
