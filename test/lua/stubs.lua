-- Minimal Kahlua-shaped stubs for the Lua tests. Not loaded by the game.
PZButter = { handlerCensus = true }

Stubs = { files = {}, lines = {}, prints = {} }

function Stubs.setSource(fn, file, line)
    Stubs.files[fn] = file
    Stubs.lines[fn] = line or 1
end

function getFilenameOfClosure(fn) return Stubs.files[fn] end
function getFirstLineOfClosure(fn) return Stubs.lines[fn] end
function isServer() return false end
-- shaped like the game's java.util.List: size()/get(i), not a Lua table
function getActivatedMods()
    local ids = { "modA", "modB" }
    return { size = function(self) return #ids end, get = function(self, i) return ids[i + 1] end }
end
function getModInfoByID(id)
    if id ~= "modA" and id ~= "modB" then return nil end
    return { getDir = function() return "/mods/" .. id end }
end

local real_print = print
print = function(...)
    local parts = {}
    for i = 1, select("#", ...) do parts[#parts + 1] = tostring(select(i, ...)) end
    Stubs.prints[#Stubs.prints + 1] = table.concat(parts, " ")
end
Stubs.realPrint = real_print

-- Every event the census wraps, plus the two boot events it registers on.
Events = {}
local names = {
    "OnTick", "OnTickEvenPaused", "OnPlayerUpdate", "OnRenderTick",
    "OnRenderUpdate", "OnFETick", "OnPreUIDraw", "OnPostUIDraw",
    "OnGameStart", "OnGameBoot",
}
for _, name in ipairs(names) do
    local callbacks = {}
    Events[name] = {
        callbacks = callbacks,
        Add = function(fn) callbacks[#callbacks + 1] = fn end,
        Remove = function(fn)
            for i = 1, #callbacks do
                if callbacks[i] == fn then table.remove(callbacks, i); return end
            end
        end,
    }
end

function Stubs.count(event, fn)
    local n = 0
    for _, cb in ipairs(Events[event].callbacks) do
        if cb == fn then n = n + 1 end
    end
    return n
end
