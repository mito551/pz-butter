local here = debug.getinfo(1, "S").source:sub(2)
local root = here:gsub("[/\\]?test/lua/ltpe_seed_test.lua$", "")
if root == "" then root = "." end

-- The LTPE seed-loot override must leave every distribution with the same entries (item,
-- weight, count) as the original's ten walks. Compared against the installed original; skipped
-- when it is not installed.
local ORIGINAL = (os.getenv("PZ_WORKSHOP") or "/home/mitori/Sonic/SteamLibrary/steamapps/workshop/content/108600")
    .. "/3597673472/mods/LongTermPreservationExtended/42.20/media/lua/server/items/LTPESeedPacketLoot.lua"
local probe = io.open(ORIGINAL)
if not probe then print("ltpe_seed_test: skipped (original not installed)") return end
probe:close()

local realRequire = require
function require(name) if name:match("^Items/") then return end return realRequire(name) end
local realPrint = print
print = function() end

local function world()
    local shared = { items = { "WheatBagSeed", 4, "Base.JalapenoBagSeed", 4, "JalapenoBagSeed", 4, "Hammer", 1 } }
    SuburbsDistributions = {
        all = { shelves = { rolls = 1, items = { "WheatBagSeed", 2, "WheatBagSeed", 2, "JalapenoBagSeed", 2,
                                                  "LTPE.OatBagSeed", 2, "WheatSeed", 1, "WheatSheafDried", 6 } } },
        farm = { crate = shared, other = { junk = { items = { "Base.WheatSeed", 3, "Base.WheatSeed", 3 } } } },
        loop = {},
    }
    SuburbsDistributions.loop.back = SuburbsDistributions   -- a cycle, as real tables may alias
    ProceduralDistributions = { list = {
        CrateGardening = { rolls = 4, items = { "WheatBagSeed", 10, "WheatSeed", 2, "WheatSheafDried", 1 } },
        CrateFarming = { rolls = 4, items = { "JalapenoBagSeed", 8 } },
        GardenStoreSeeds = { items = { "WheatBagSeed", 6, "JalapenoBagSeed", 6, "JalapenoBagSeed", 6 } },
        Shared = shared,
    } }
    BagsAndContainers = { Gardening = { items = { "WheatBagSeed", 1, "JalapenoBagSeed", 1, "WheatSeed", 5 } } }
end

-- every items list reachable from the three roots, as "item|weight" -> count
local function snapshot()
    local out, seen = {}, {}
    local function visit(t, path)
        if type(t) ~= "table" or seen[t] then return end
        seen[t] = true
        if type(t.items) == "table" then
            local counts = {}
            for i = 1, #t.items, 2 do
                local k = tostring(t.items[i]) .. "|" .. tostring(t.items[i + 1])
                counts[k] = (counts[k] or 0) + 1
            end
            out[path] = counts
        end
        local keys = {}
        for k, v in pairs(t) do if type(v) == "table" and k ~= "items" then keys[#keys + 1] = tostring(k) end end
        table.sort(keys)
        for _, k in ipairs(keys) do visit(t[k], path .. "." .. k) end
    end
    visit(SuburbsDistributions, "S"); visit(ProceduralDistributions, "P"); visit(BagsAndContainers, "B")
    return out
end

local function run(file)
    world()
    local register
    Events = { OnPreDistributionMerge = { Add = function(fn) register = fn end } }
    assert(loadfile(file))()
    assert(register, "no OnPreDistributionMerge handler in " .. file)
    register()
    return snapshot()
end

local want = run(ORIGINAL)
local got = run(root .. "/mod/PZButter_LongTermPreservationExtended/42/media/lua/server/items/LTPESeedPacketLoot.lua")
print = realPrint

local lists, added = 0, 0
for path, counts in pairs(want) do
    lists = lists + 1
    assert(got[path], "override lost the list " .. path)
    for k, n in pairs(counts) do
        assert(got[path][k] == n, path .. ": " .. k .. " x" .. tostring(got[path][k]) .. ", original x" .. n)
        if k:match("^LTPE") then added = added + n end
    end
    for k, n in pairs(got[path]) do assert(counts[k] == n, path .. ": extra " .. k .. " x" .. n) end
end
for path in pairs(got) do assert(want[path], "override has an extra list " .. path) end
assert(added > 5, "the fixture mirrored nothing")
print("ltpe_seed_test: ok (" .. lists .. " lists, " .. added .. " oat entries)")
