local root = arg[0]:match("^(.*)/test/lua/[^/]+$") or "."
SuburbsDistributions = {
    first = { items = { "Base.Apple", 2 } },
}
ProceduralDistributions = { list = {} }
BagsAndContainers = {}

local D = dofile(root .. "/mod/PZButter/42/media/lua/shared/PZButter/Distributions.lua")
local function count(items, item, weight)
    local n = 0
    for i = 1, #items, 2 do
        if items[i] == item and items[i + 1] == weight then n = n + 1 end
    end
    return n
end

D.mirror("Apple", "Butter")
assert(count(SuburbsDistributions.first.items, "Butter", 2) == 1)

SuburbsDistributions.later = { items = { "Apple", 3 } }
D.mirror("Base.Apple", "Butter")
assert(count(SuburbsDistributions.later.items, "Butter", 3) == 1,
    "mirror must see lists added after the first indexed lookup")

SuburbsDistributions.latest = { items = { "Base.Apple", 4 } }
D.mirrorAll({ { source = "Apple", target = "Orange" } })
assert(count(SuburbsDistributions.latest.items, "Orange", 4) == 1,
    "mirrorAll must see lists added after the first indexed lookup")

print("distributions_test: ok")
