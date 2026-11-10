-- PZ Butter shared claim protocol (modopt index, shared item C1). Every fix mod ships an identical copy.
-- Shared mutable table; created once across all mods.
PZB_CLAIM = PZB_CLAIM or { v = 1, c = {} }
local C = PZB_CLAIM

-- authoritative priorities from the C1 table (section -> mod -> priority). Omitted pairs get 0.
local PRIORITY = {
    ["state:cursor.visibility"] = { ["core CursorLatch"] = 100, ["XEG"] = 60, ["VQOL"] = 40 },
    ["state:frame.id"] = { ["core"] = 100, ["XEG"] = 60, ["QoLC"] = 30 },
    ["state:frame.phase"] = { ["core"] = 100 },
    ["state:handler.stride"] = { ["core LuaBudget"] = 100, ["ISnow"] = 60 },
    ["event:LoadGridsquare"] = { ["core mask dispatcher"] = 100, ["mod wrapper"] = 40 },
    ["data:Distributions"] = { ["core roll-time insertion"] = 100, ["LTPE-2 index"] = 80, ["LTPE-1"] = 60 },
    ["state:wind.field"] = { ["WindSway"] = 60 },
    ["java:pzbutter"] = { ["core"] = 100 },
}

local function priorityFor(section, mod, fallback)
    local m = PRIORITY[section]
    if m and m[mod] ~= nil then return m[mod] end
    return fallback or 0
end

local function claim(opts)
    opts = opts or {}
    local section = opts.section
    local mod = opts.mod
    local install = opts.install
    local standDown = opts.standDown
    local priority = opts.priority
    if priority == nil then priority = priorityFor(section, mod, 0) end
    C.c[section] = C.c[section] or { claims = {}, owner = nil, resolved = false }
    local bucket = C.c[section]
    local entry = { mod = mod, priority = priority, install = install, standDown = standDown }
    table.insert(bucket.claims, entry)

    if bucket.resolved then
        local w = bucket.owner
        if w == nil then
            bucket.owner = mod
            if type(install) == "function" then pcall(install) end
        elseif w ~= mod then
            if type(standDown) == "function" then pcall(standDown, w) end
        end
        return w == nil or w == mod
    end
    return true
end

local function resolve()
    for section, bucket in pairs(C.c) do
        if not bucket.resolved and bucket.claims and #bucket.claims > 0 then
            local winner = bucket.claims[1]
            for i = 2, #bucket.claims do
                local e = bucket.claims[i]
                if e.priority > winner.priority or (e.priority == winner.priority and e.mod < winner.mod) then
                    winner = e
                end
            end
            bucket.owner = winner.mod
            if type(winner.install) == "function" then pcall(winner.install) end
            local losers = ""
            for _, e in ipairs(bucket.claims) do
                if e ~= winner then
                    if losers ~= "" then losers = losers .. ", " end
                    losers = losers .. e.mod
                    if type(e.standDown) == "function" then pcall(e.standDown, winner.mod) end
                end
            end
            print(string.format("[PZB-claim] %s: %s%s", section, winner.mod, losers ~= "" and (" over " .. losers) or ""))
            bucket.resolved = true
        end
    end
end

local function owner(section)
    local bucket = C.c[section]
    return bucket and bucket.owner or nil
end

C.claim = claim
C.resolve = resolve
C.owner = owner

if Events and Events.OnGameBoot then
    Events.OnGameBoot.Add(function()
        C.resolve()
    end)
end