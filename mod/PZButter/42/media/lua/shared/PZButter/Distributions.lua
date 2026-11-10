-- PZ Butter core distribution helpers (LTPE plan): an inverted item -> items-list index built
-- lazily on first use, plus mirror/add entry points so fix mods can be O(hits) instead of O(tree).
PZButter = PZButter or {}
PZButter.Distributions = PZButter.Distributions or {}
local D = PZButter.Distributions

local function normName(name)
    if type(name) ~= "string" then return name end
    if name:sub(1, 5) == "Base." then return name:sub(6) end
    return name
end

local function isSourceItem(itemName, sourceItem)
    return normName(itemName) == normName(sourceItem)
end

-- Build (once) an index: normalized item name -> list of items tables that mention it.
local function buildIndex(force)
    if D.index and not force and not D.indexDirty then return D.index end
    D.indexDirty = false
    local index = {}
    local roots = {
        { root = SuburbsDistributions, name = "SuburbsDistributions" },
        { root = (ProceduralDistributions and ProceduralDistributions.list), name = "ProceduralDistributions.list" },
        { root = BagsAndContainers, name = "BagsAndContainers" },
    }
    for _, r in ipairs(roots) do
        if type(r.root) == "table" then
            local visited = {}
            local function visit(current)
                if type(current) ~= "table" or visited[current] then return end
                visited[current] = true
                if type(current.items) == "table" then
                    local items = current.items
                    local seen = {}
                    for i = 1, #items, 2 do
                        local itemName = items[i]
                        if type(itemName) == "string" and not seen[itemName] then
                            seen[itemName] = true
                            local key = normName(itemName)
                            index[key] = index[key] or {}
                            table.insert(index[key], items)
                        end
                    end
                end
                for key, value in pairs(current) do
                    if type(value) == "table" and key ~= "items" then
                        visit(value)
                    end
                end
            end
            visit(r.root)
        end
    end
    D.index = index
    return index
end

local function countItemWeight(items, itemType, weight)
    if type(items) ~= "table" then return 0 end
    local count = 0
    for i = 1, #items, 2 do
        if items[i] == itemType and items[i + 1] == weight then
            count = count + 1
        end
    end
    return count
end

-- Mirror one source item (bare or Base.-qualified) into every items list that lists it.
function D.mirror(sourceItem, targetItem)
    buildIndex()
    local lists = D.index[normName(sourceItem)] or {}
    local seen = {}
    for _, items in ipairs(lists) do
        if type(items) == "table" and not seen[items] then
            seen[items] = true
            local sourceCounts = {}
            local targetCounts = {}
            for i = 1, #items, 2 do
                local itemName = items[i]
                local weight = items[i + 1]
                if weight ~= nil then
                    if isSourceItem(itemName, sourceItem) then
                        sourceCounts[weight] = (sourceCounts[weight] or 0) + 1
                    elseif itemName == targetItem then
                        targetCounts[weight] = (targetCounts[weight] or 0) + 1
                    end
                end
            end
            for weight, sourceCount in pairs(sourceCounts) do
                local targetCount = targetCounts[weight] or 0
                while targetCount < sourceCount do
                    table.insert(items, targetItem)
                    table.insert(items, weight)
                    targetCount = targetCount + 1
                    targetCounts[weight] = targetCount
                end
            end
        end
    end
end

-- Mirror several source->target pairs into every items list that contains any of them,
-- raising a shared target to the maximum count among its sources, as LTPE-1 does.
function D.mirrorAll(pairList)
    buildIndex()
    local uniqueLists = {}
    local listSeen = {}
    for _, p in ipairs(pairList or {}) do
        for _, items in ipairs(D.index[normName(p.source)] or {}) do
            if type(items) == "table" and not listSeen[items] then
                listSeen[items] = true
                table.insert(uniqueLists, items)
            end
        end
    end
    local targets = {}
    local targetSeen = {}
    for _, p in ipairs(pairList or {}) do
        if not targetSeen[p.target] then
            targetSeen[p.target] = true
            table.insert(targets, p.target)
        end
    end
    for _, items in ipairs(uniqueLists) do
        local existing = {}
        for _, t in ipairs(targets) do existing[t] = {} end
        for i = 1, #items, 2 do
            local itemName = items[i]
            local weight = items[i + 1]
            if weight ~= nil then
                for _, t in ipairs(targets) do
                    if itemName == t then
                        local e = existing[t]
                        e[weight] = (e[weight] or 0) + 1
                    end
                end
            end
        end
        local required = {}
        for _, t in ipairs(targets) do required[t] = {} end
        for _, p in ipairs(pairList or {}) do
            local srcCounts = {}
            for i = 1, #items, 2 do
                local itemName = items[i]
                local weight = items[i + 1]
                if weight ~= nil and isSourceItem(itemName, p.source) then
                    srcCounts[weight] = (srcCounts[weight] or 0) + 1
                end
            end
            for weight, sourceCount in pairs(srcCounts) do
                local r = required[p.target]
                if sourceCount > (r[weight] or 0) then
                    r[weight] = sourceCount
                end
            end
        end
        local handled = {}
        for _, p in ipairs(pairList or {}) do
            if not handled[p.target] then
                handled[p.target] = true
                local t = existing[p.target]
                local r = required[p.target]
                for weight, sourceCount in pairs(r) do
                    local currentCount = t[weight] or 0
                    while currentCount < sourceCount do
                        table.insert(items, p.target)
                        table.insert(items, weight)
                        currentCount = currentCount + 1
                        t[weight] = currentCount
                    end
                end
            end
        end
    end
end

function D.add(listPath, itemType, weight)
    local function find(root, path)
        if not root or not path then return nil end
        local current = root
        for part in string.gmatch(path, "[^%.]+") do
            if type(current) ~= "table" then return nil end
            current = current[part]
            if current == nil then return nil end
        end
        return current
    end
    local candidate = nil
    candidate = find(SuburbsDistributions, listPath)
    if not candidate then candidate = find(ProceduralDistributions and ProceduralDistributions.list, listPath) end
    if not candidate then candidate = find(BagsAndContainers, listPath) end
    if type(candidate) ~= "table" or type(candidate.items) ~= "table" then return end
    local items = candidate.items
    -- ensure at least one occurrence
    local currentCount = countItemWeight(items, itemType, weight)
    while currentCount < 1 do
        table.insert(items, itemType)
        table.insert(items, weight)
        currentCount = currentCount + 1
    end
end

-- Invalidate the cached index so the next mirror/mirrorAll rebuilds it (call after other mods edit distribution lists).
function D.rebuildIndex()
    D.index = nil
    D.indexDirty = true
end

-- Claim-friendly helper: called by fix mods if they want. No-op default.
return D