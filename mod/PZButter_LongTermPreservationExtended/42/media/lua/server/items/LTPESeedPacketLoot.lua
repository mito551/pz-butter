require "Items/SuburbsDistributions"
require "Items/ProceduralDistributions"
require "Items/Distributions"

-- PZ Butter fix, LTPE-1: same inserted entries and weights as the original, but the ten per-source
-- recursive walks are collapsed into three (one per root), each mirroring every source->target
-- pair it handles in a single traversal of that root. A table's path is joined only when a
-- line is logged for it. Derived from LTPESeedPacketLoot.lua;
-- if that file changes, this override must be re-checked against the new version.
local LOG_PREFIX = "[LTP Extended][Seed Loot] "

local OAT_SEED_PACKET = "LTPE.OatBagSeed"
local OAT_SEED = "LTPE.OatSeed"
local OAT_SHEAF = "LTPE.OatSheaf"
local OAT_SHEAF_DRIED = "LTPE.OatSheafDried"

local registrationComplete = false

local function log(message)
    print(LOG_PREFIX .. message)
end

local function getDistribution(root, path)
    if not root or not path then
        return nil
    end

    local current = root

    for part in string.gmatch(path, "[^%.]+") do
        if type(current) ~= "table" then
            return nil
        end

        current = current[part]

        if current == nil then
            return nil
        end
    end

    return current
end

-- Count occurrences of an item at a particular weight.
local function countItemWeight(items, itemType, weight)
    if type(items) ~= "table" then
        return 0
    end

    local count = 0

    for i = 1, #items, 2 do
        if items[i] == itemType and items[i + 1] == weight then
            count = count + 1
        end
    end

    return count
end

local function ensureItemWeightCount(
    distribution,
    itemType,
    weight,
    targetCount,
    distributionName
)
    if not distribution then
        log("WARNING: Distribution not found: " .. distributionName)
        return
    end

    if type(distribution.items) ~= "table" then
        log("WARNING: No items table in " .. distributionName)
        return
    end

    local items = distribution.items
    local currentCount = countItemWeight(items, itemType, weight)

    while currentCount < targetCount do
        table.insert(items, itemType)
        table.insert(items, weight)

        currentCount = currentCount + 1

        log(
            "Added " .. itemType
            .. " to " .. distributionName
            .. " (weight " .. tostring(weight) .. ")"
        )
    end
end

local function addSuburbs(path, itemType, weight)
    local distribution = getDistribution(SuburbsDistributions, path)

    ensureItemWeightCount(
        distribution,
        itemType,
        weight,
        1,
        "SuburbsDistributions." .. path
    )
end

local function addProcedural(name, itemType, weight)
    local root = ProceduralDistributions and ProceduralDistributions.list
    local distribution = root and root[name]

    ensureItemWeightCount(
        distribution,
        itemType,
        weight,
        1,
        "ProceduralDistributions.list." .. name
    )
end

-- Match both legacy unqualified Base item names and fully qualified names.
-- Mirror every source->target pair in pairList (a list of {source, target}) into one
-- distribution's items list. A target shared by several sources is raised to the maximum
-- count among them, exactly as the original's repeated walks left it.
-- bySource maps an item name to the pairs it is a source of (see mirrorAcrossTables).
-- pathOf() gives the distribution's name; it is called only when a line is logged.
local function mirrorItemsInDistribution(distribution, pairList, bySource, pathOf)
    if type(distribution) ~= "table"
        or type(distribution.items) ~= "table" then
        return
    end

    local items = distribution.items
    local pairCount = #pairList

    -- existing[target][weight] = entries already present
    -- required[target][weight] = most entries any of its sources has at that weight
    local existing, required, sourceCounts = {}, {}, {}
    for k = 1, pairCount do
        local target = pairList[k].target
        existing[target] = existing[target] or {}
        required[target] = required[target] or {}
        sourceCounts[k] = {}
    end

    for i = 1, #items, 2 do
        local itemName = items[i]
        local weight = items[i + 1]
        if weight ~= nil then
            local t = existing[itemName]
            if t then
                t[weight] = (t[weight] or 0) + 1
            else
                local ks = bySource[itemName]
                if ks then
                    for j = 1, #ks do
                        local c = sourceCounts[ks[j]]
                        c[weight] = (c[weight] or 0) + 1
                    end
                end
            end
        end
    end

    for k = 1, pairCount do
        local r = required[pairList[k].target]
        for weight, sourceCount in pairs(sourceCounts[k]) do
            if sourceCount > (r[weight] or 0) then
                r[weight] = sourceCount
            end
        end
    end

    local handledTarget = {}
    for k = 1, pairCount do
        local p = pairList[k]
        if not handledTarget[p.target] then
            handledTarget[p.target] = true
            local t = existing[p.target]
            for weight, sourceCount in pairs(required[p.target]) do
                local currentCount = t[weight] or 0
                while currentCount < sourceCount do
                    table.insert(items, p.target)
                    table.insert(items, weight)
                    currentCount = currentCount + 1
                    log(
                        "Mirrored " .. p.source
                        .. " -> " .. p.target
                        .. " in " .. pathOf()
                        .. " (weight " .. tostring(weight) .. ")"
                    )
                end
            end
        end
    end
end

-- One walk per root; pairList is a list of {source, target}.
local function mirrorAcrossTables(root, rootName, pairList)
    if type(root) ~= "table" then
        log("WARNING: Distribution root not found: " .. rootName)
        return
    end

    -- item name -> indexes of the pairs it is a source of; with and without the Base. module, as the original matches
    local bySource = {}
    for k = 1, #pairList do
        local source = pairList[k].source
        for _, name in ipairs({ source, "Base." .. source }) do
            local ks = bySource[name]
            if not ks then
                ks = {}
                bySource[name] = ks
            end
            ks[#ks + 1] = k
        end
    end

    local visited = {}
    -- the keys from the root down to the table being visited; joined only for a log line
    local names, depth = { rootName }, 1
    local function pathOf()
        local path = names[1]
        for i = 2, depth do
            path = path .. "." .. names[i]
        end
        return path
    end

    local function visit(current)
        if visited[current] then
            return
        end

        visited[current] = true

        if type(current.items) == "table" then
            mirrorItemsInDistribution(current, pairList, bySource, pathOf)
        end

        for key, value in pairs(current) do
            if type(value) == "table" and key ~= "items" then
                depth = depth + 1
                names[depth] = tostring(key)
                visit(value)
                depth = depth - 1
            end
        end
    end

    visit(root)
end

local function registerOatLoot()
    if registrationComplete then
        return
    end

    log("Registering oat loot distributions...")

    -- Suburbs distributions: WheatBagSeed and JalapenoBagSeed both mirror into the oat seed
    -- packet, WheatSeed into loose oat seed, WheatSheafDried into dried oat sheaf.
    mirrorAcrossTables(
        SuburbsDistributions,
        "SuburbsDistributions",
        {
            { source = "WheatBagSeed", target = OAT_SEED_PACKET },
            { source = "JalapenoBagSeed", target = OAT_SEED_PACKET },
            { source = "WheatSeed", target = OAT_SEED },
            { source = "WheatSheafDried", target = OAT_SHEAF_DRIED },
        }
    )

    -- Procedural distributions.
    mirrorAcrossTables(
        ProceduralDistributions and ProceduralDistributions.list,
        "ProceduralDistributions.list",
        {
            { source = "WheatBagSeed", target = OAT_SEED_PACKET },
            { source = "JalapenoBagSeed", target = OAT_SEED_PACKET },
            { source = "WheatSeed", target = OAT_SEED },
            { source = "WheatSheafDried", target = OAT_SHEAF_DRIED },
        }
    )

    -- Shared bag/container distributions.
    if type(BagsAndContainers) == "table" then
        mirrorAcrossTables(
            BagsAndContainers,
            "BagsAndContainers",
            {
                { source = "WheatBagSeed", target = OAT_SEED_PACKET },
                { source = "JalapenoBagSeed", target = OAT_SEED_PACKET },
            }
        )
    else
        log(
            "WARNING: BagsAndContainers not found; "
            .. "shared container loot was not scanned."
        )
    end

    -- Explicit oat seed packet locations.
    addSuburbs(
        "all.Outfit_Farmer",
        OAT_SEED_PACKET,
        1
    )

    addProcedural(
        "CrateGardening",
        OAT_SEED_PACKET,
        5
    )

    -- Add loose oat seeds to gardening crates at weight 2.
    addProcedural(
        "CrateGardening",
        OAT_SEED,
        2
    )

    -- UNDried oat sheaf: vanilla has no separate WheatSheaf.
    addProcedural(
        "CrateFarming",
        OAT_SHEAF,
        8
    )

    registrationComplete = true

    log("Oat loot distribution registration complete.")
end

if Events and Events.OnPreDistributionMerge then
    Events.OnPreDistributionMerge.Add(registerOatLoot)
else
    log(
        "ERROR: OnPreDistributionMerge event not found; "
        .. "oat loot was not registered."
    )
end