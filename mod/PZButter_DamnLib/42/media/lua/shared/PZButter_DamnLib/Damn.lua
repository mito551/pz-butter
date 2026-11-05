-- PZButter_DamnLib (fix mod, standalone: no PZ Butter needed). Enabling the mod is the switch.
if _G.PZButterDamnLib then return end   -- a reload keeps the first install
local Damn = {}
_G.PZButterDamnLib = Damn

local original
local originalAdd
local byLocation
local index
local dirty = true
local installed = false

local function source(fn)
    local ok, file = pcall(getFilenameOfClosure, fn)
    if not ok or type(file) ~= "string" then return nil end
    return file:gsub("\\", "/"):lower()
end

-- idx[x][y] = true, from the mod's x_y keys. nil when a key is not x_y.
function Damn.index(by)
    local out = {}
    for key in pairs(by or {}) do
        local x, y = string.match(key, "^(-?%d+)_(-?%d+)$")
        if not x then return nil end
        x, y = tonumber(x), tonumber(y)
        out[x] = out[x] or {}
        out[x][y] = true
    end
    return out
end

function Damn.check(self, square)
    if dirty then
        index = Damn.index(byLocation)
        dirty = false
    end
    if index then
        local x, y = square:getX(), square:getY()
        if not (index[x] and index[x][y]) then return true end
    end
    return original(self, square)
end

function Damn.add(self, ...)
    local result = { originalAdd(self, ...) }
    dirty = true
    return unpack(result)
end

-- Returns true when it took over the two functions, false and untouched otherwise.
function Damn.install()
    if installed then return true end
    local spawns = DAMN and DAMN.Spawns
    if not (spawns and type(spawns.checkSquare) == "function"
            and type(spawns.addInternal) == "function") then
        print("[PZButter_DamnLib] off (no damnlib)")
        return false
    end
    local file = source(spawns.checkSquare)
    if not (file and file:match("/damn_spawns%.lua$")) then
        print("[PZButter_DamnLib] off (not DAMN_Spawns.lua)")
        return false
    end
    local built = Damn.index(spawns.byLocation)
    if not built then
        print("[PZButter_DamnLib] off (key is not x_y)")
        return false
    end
    original, originalAdd, byLocation = spawns.checkSquare, spawns.addInternal, spawns.byLocation
    index, dirty, installed = built, false, true
    spawns.checkSquare = Damn.check
    spawns.addInternal = Damn.add
    local n = 0
    for _ in pairs(spawns.byLocation or {}) do n = n + 1 end   -- x_y keys: # would read 0
    print("[PZButter_DamnLib] on, " .. n .. " locations")
    return true
end

Events.OnGameBoot.Add(Damn.install)
