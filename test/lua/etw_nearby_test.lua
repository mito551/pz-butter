local here = debug.getinfo(1, "S").source:sub(2)
local root = here:gsub("[/\\]?test/lua/etw_nearby_test.lua$", "")
if root == "" then root = "." end

-- The zombie scan must give the same visits with the jar (flat array) and without it
-- (the target's own Lua loop): a dedicated server has no jar.
-- answers any field or call with itself
local any = {}
setmetatable(any, {__index = function() return any end, __call = function() return any end})
package.loaded.ETW_CommonFunctions = any
package.loaded.ETW_Registry = any
-- every other engine global the file touches while loading
setmetatable(_G, {__index = function() return any end})
etwPerfNearbyLivingZombies = false   -- no jar yet
function instanceof(o, class) return o.class == class end

local function zombie(x, y, dead)
    return {class = "IsoZombie", getX = function() return x end, getY = function() return y end,
            isDead = function() return dead end}
end
local near, far, dead = zombie(10.5, 10.5, false), zombie(13.5, 10.5, false), zombie(10.5, 11.5, true)
local squares = {["10,10"] = {near}, ["13,10"] = {far}, ["10,11"] = {dead, {class = "IsoPlayer"}}}
local cell = {getGridSquare = function(_, x, y)
    local list = squares[x .. "," .. y]
    if not list then return nil end
    return {getMovingObjects = function()
        return {size = function() return #list end, get = function(_, i) return list[i + 1] end}
    end}
end}
local player = {getCell = function() return cell end, getX = function() return 10.2 end,
                getY = function() return 10.2 end, getZ = function() return 0 end}

local chunk = assert(loadfile(root .. "/mod/PZButter_EvolvingTraitsWorld/42/media/lua/shared/ETW_CombinedTraitFunctions.lua"))
local T = chunk()

local function visits()
    local seen = {}
    local n = T.forEachNearbyLivingZombie(player, 2, function(z, d2) seen[#seen + 1] = {z, d2} end)
    return n, seen
end

local nLua, lua = visits()
assert(nLua == 1 and lua[1][1] == near, "Lua fallback: one living zombie within 2 tiles")

etwPerfNearbyLivingZombies = function() return {near, lua[1][2]} end
local nJava, java = visits()
assert(nJava == nLua and java[1][1] == near and java[1][2] == lua[1][2], "jar path differs from the Lua loop")
assert(T.forEachNearbyLivingZombie(player, 2, nil) == 1, "count without a visitor")
print("etw_nearby_test: ok")
