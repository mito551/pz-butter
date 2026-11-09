-- PZ Butter: modOptionsFast (shared item A1). Reads ModOptions.ini once during the boot window and applies cached
-- values only to entries that have not been applied yet. Restores stock load() at OnMainMenuEnter.
if not (PZButter and PZButter.modOptionsFast) then return end

local originalLoad = PZAPI.ModOptions.load
local originalSave = PZAPI.ModOptions.save
local cacheBuilt = false
local cachedEntries = nil
local cachedOther = nil

local function buildCache()
    if cacheBuilt then return end
    cacheBuilt = true
    cachedEntries = {}
    cachedOther = {}
    local file = getFileReader("ModOptions.ini", true)
    if not file then return end
    while true do
        local line = file:readLine()
        if line == nil then file:close(); break end
        local t = luautils.split(line, "|")
        if PZAPI.ModOptions.Dict[t[2]] ~= nil and PZAPI.ModOptions.Dict[t[2]].dict[t[3]] ~= nil then
            table.insert(cachedEntries, t)
        else
            table.insert(cachedOther, line)
        end
    end
end

local stringtoboolean = { ["true"] = true, ["false"] = false }

local function applyCached()
    buildCache()
    PZAPI.ModOptions.OtherOptions = cachedOther
    for _, t in ipairs(cachedEntries) do
        local opt = PZAPI.ModOptions.Dict[t[2]].dict[t[3]]
        if not opt._pzbApplied then
            opt._pzbApplied = true
            if t[1] == "textentry" and t[4] ~= "" then
                opt.value = t[4]
            elseif t[1] == "tickbox" and stringtoboolean[t[4]] ~= nil then
                opt.value = stringtoboolean[t[4]]
            elseif t[1] == "slider" and tonumber(t[4]) ~= nil then
                opt.value = tonumber(t[4])
            elseif t[1] == "multipletickbox" then
                local t2 = luautils.split(t[4], " ")
                for i, v in ipairs(t2) do
                    if stringtoboolean[v] ~= nil and opt.values[i] ~= nil then
                        opt.values[i].value = stringtoboolean[v]
                    end
                end
            elseif t[1] == "combobox" and tonumber(t[4]) ~= nil then
                opt.selected = tonumber(t[4])
            elseif t[1] == "colorpicker" then
                local t2 = luautils.split(t[4], " ")
                if #t2 == 4 and tonumber(t2[1]) ~= nil and tonumber(t2[2]) ~= nil and tonumber(t2[3]) ~= nil and tonumber(t2[4]) ~= nil then
                    opt.color = { r = tonumber(t2[1]), g = tonumber(t2[2]), b = tonumber(t2[3]), a = tonumber(t2[4]) }
                end
            elseif t[1] == "keybind" and tonumber(t[4]) then
                opt.key = tonumber(t[4])
            end
        end
    end
end

PZAPI.ModOptions.load = function(self)
    applyCached()
end

PZAPI.ModOptions.save = function(self)
    originalSave(self)
    -- invalidate the cache after an apply/re-write
    cacheBuilt = false
    cachedEntries = nil
    cachedOther = nil
    for _, options in ipairs(PZAPI.ModOptions.Data) do
        for _, option in ipairs(options.data) do
            option._pzbApplied = nil
        end
    end
end

Events.OnMainMenuEnter.Add(function()
    PZAPI.ModOptions.load = originalLoad
    PZAPI.ModOptions.save = originalSave
end)