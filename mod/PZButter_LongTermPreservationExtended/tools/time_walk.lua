-- Times the original seed-loot registration against the override on a synthetic tree.
-- Run from the repo root: luajit -joff mod/PZButter_LongTermPreservationExtended/tools/time_walk.lua . <original LTPESeedPacketLoot.lua>
-- 2026-11-10: original 54.5 ms, override 13.6 ms; the withdrawn index variant took 53.0 ms.
local root, orig = arg[1], arg[2]
local realRequire = require
function require(name) if name:match("^Items/") then return end return realRequire(name) end
print = function() end
local function world()
    math.randomseed(7)
    local names = {}
    for i = 1, 3000 do names[i] = "Item" .. i end
    local function list()
        local items = {}
        for i = 1, 40 do items[#items + 1] = names[math.random(#names)]; items[#items + 1] = math.random(10) end
        if math.random(60) == 1 then items[#items + 1] = "WheatBagSeed"; items[#items + 1] = 4 end
        if math.random(80) == 1 then items[#items + 1] = "WheatSeed"; items[#items + 1] = 2 end
        return { rolls = 3, items = items }
    end
    SuburbsDistributions = {}
    for r = 1, 400 do local room = {}; for c = 1, 6 do room["c" .. c] = list() end; SuburbsDistributions["room" .. r] = room end
    ProceduralDistributions = { list = {} }
    for p = 1, 2500 do ProceduralDistributions.list["P" .. p] = list() end
    ProceduralDistributions.list.CrateGardening = list(); ProceduralDistributions.list.CrateFarming = list()
    BagsAndContainers = {}
    for b = 1, 60 do BagsAndContainers["B" .. b] = list() end
end
local function time(file, pre)
    local best = math.huge
    for _ = 1, 7 do
        world()
        if pre then pre() end
        local register
        Events = { OnPreDistributionMerge = { Add = function(fn) register = fn end } }
        assert(loadfile(file))()
        local t0 = os.clock(); register(); local t = os.clock() - t0
        if t < best then best = t end
    end
    return best * 1000
end
local a = time(orig)
local b = time(root .. "/mod/PZButter_LongTermPreservationExtended/42/media/lua/server/items/LTPESeedPacketLoot.lua")
io.write(string.format("original %.1f ms, override %.1f ms (4960 lists, best of 7)\n", a, b))
