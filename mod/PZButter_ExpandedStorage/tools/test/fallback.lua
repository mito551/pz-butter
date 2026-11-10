-- Run: luajit tools/test/fallback.lua   (stubs the game; checks Lua stands down when Java is live)
local handlers, capSet = {}, 0
local removed, starts = {}, {}
local function collect(name)
    return { Add = function(f) handlers[name] = f; if name == "start" then starts[#starts + 1] = f end end,
             Remove = function(f) if handlers[name] == f then removed[name] = true end end }
end
Events = { OnGameBoot = collect("boot"), OnGameStart = collect("start"), OnServerStarted = collect("srv"),
           OnObjectAdded = collect("added"), LoadGridsquare = collect("square") }
IsoSpriteManager = { instance = { getSprite = function() return nil end } }
dofile("42/media/lua/shared/ExpandedStorage/ExpandedStorage.lua")

local function obj(sprite, cap)
    local c = { cap = cap, getCapacity = function(s) return s.cap end, setCapacity = function(s, v) s.cap = v; capSet = capSet + 1 end }
    return { c = c, getSprite = function() return { getName = function() return sprite end } end, getContainer = function() return c end }
end
local function square(...)
    local list, md = { ... }, {}
    return { getModData = function() return md end,
             getObjects = function() return { size = function() return #list end, get = function(_, i) return list[i + 1] end } end }
end

-- 1) No Java: Lua fallback fixes capacities, once per square.
local o = obj("carpentry_01_16", 10)
local sq = square(o)
handlers.square(sq); handlers.square(sq)
assert(o.c.cap == 80 and capSet == 1, "fallback should set capacity once")
local o2 = obj("carpentry_01_17", 10); handlers.added(o2); assert(o2.c.cap == 80, "OnObjectAdded fallback")
assert(obj("unknown_sprite", 7).c.cap == 7)

for _, f in ipairs(starts) do f() end
assert(not removed.added and not removed.square, "no Java: listeners must stay")

-- 2) Java live: handlers stand down permanently.
esZBActive = function() return true end
local o3 = obj("carpentry_01_18", 10)
handlers.square(square(o3)); handlers.added(o3)
assert(o3.c.cap == 10, "Lua must not touch containers when Java is live")
esZBActive = function() return false end -- latched: stays down
handlers.added(o3); assert(o3.c.cap == 10, "latch")

-- 3) Game start with Java live drops both listeners; without Java it keeps them.
assert(not removed.added and not removed.square, "listeners dropped too early")
for _, f in ipairs(starts) do f() end
assert(removed.added and removed.square, "live Java: both listeners should be removed at game start")
print("fallback ok")
