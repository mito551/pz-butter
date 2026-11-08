local here = debug.getinfo(1, "S").source:sub(2)
local root = here:gsub("[/\\]?test/lua/modoptionsfast_test.lua$", "")
if root == "" then root = "." end
dofile(root .. "/test/lua/stubs.lua")

-- stubs for what the file needs: the ini, luautils.split, Events.OnMainMenuEnter, PZAPI.ModOptions
local ini = { "tickbox|modA|dark|true", "slider|modA|size|7", "tickbox|modB|late|true", "tickbox|gone|x|true" }
local reads = 0
function getFileReader(name, create)
    reads = reads + 1
    local i = 0
    return { readLine = function() i = i + 1; return ini[i] end, close = function() end }
end
luautils = { split = function(s, sep)
    local out = {}
    for piece in (s .. sep):gmatch("(.-)" .. sep:gsub("%p", "%%%0")) do out[#out + 1] = piece end
    return out
end }
Events.OnMainMenuEnter = { Add = function(fn) Stubs.menuEnter = fn end }
local function option(v) return { value = v } end
PZAPI = { ModOptions = { Dict = {}, OtherOptions = {} } }
local stockCalls = 0
PZAPI.ModOptions.load = function(self) stockCalls = stockCalls + 1 end
local stock = PZAPI.ModOptions.load
PZButter.modOptionsFast = true
dofile(root .. "/mod/PZButter/42/media/lua/client/PZButter/ModOptionsFast.lua")
assert(PZAPI.ModOptions.load ~= stock, "load is replaced")

-- mod A registers, calls load; mod B registers later, calls load again
local darkA, sizeA = option(false), option(1)
PZAPI.ModOptions.Dict.modA = { dict = { dark = darkA, size = sizeA } }
PZAPI.ModOptions:load()
assert(darkA.value == true and sizeA.value == 7, "mod A gets its saved values")
darkA.value = false   -- the mod changes it after the first load
local lateB = option(false)
PZAPI.ModOptions.Dict.modB = { dict = { late = lateB } }
PZAPI.ModOptions:load()
assert(lateB.value == true, "an option registered after the first load still gets its saved value")
assert(darkA.value == false, "an option already applied is not reset by a later load")
assert(reads == 1, "the ini is read once, read " .. reads)
local other = PZAPI.ModOptions.OtherOptions
assert(#other == 1 and other[1] == "tickbox|gone|x|true", "unregistered lines are kept for the next save")

-- the main menu puts stock back
Stubs.menuEnter()
assert(PZAPI.ModOptions.load == stock, "stock load restored at the main menu")
print = Stubs.realPrint
print("modoptionsfast_test: ok")
