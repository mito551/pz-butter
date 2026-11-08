-- PZ Butter: modOptionsFast. Stock PZAPI.ModOptions:load() re-reads and re-parses ModOptions.ini once per mod during a
-- Lua (re)load. Here the file is read once per Lua load (game boot, and a save load that reloads Lua because its mod
-- list differs); the only two triggers. Each .ini line is applied once to each option and an option registered later
-- still gets its saved value, as stock. Stock load() is back at the main menu.
if not (PZButter and PZButter.modOptionsFast) then return end
if not (PZAPI and PZAPI.ModOptions and PZAPI.ModOptions.load) then return end

local originalLoad = PZAPI.ModOptions.load
local lines = nil   -- { {raw = line, t = split fields, applied = option it was last applied to}, ... }
local stringtoboolean = { ["true"] = true, ["false"] = false }

local function readLines()
    local out = {}
    local file = getFileReader("ModOptions.ini", true)
    if not file then return out end
    while true do
        local line = file:readLine()
        if line == nil then file:close(); break end
        out[#out + 1] = { raw = line, t = luautils.split(line, "|") }
    end
    return out
end

-- the body of stock load() for one matching line
local function apply(opt, t)
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

PZAPI.ModOptions.load = function(self)
    lines = lines or readLines()
    local other = {}
    local dict = PZAPI.ModOptions.Dict
    for _, e in ipairs(lines) do
        local t = e.t
        local mod = dict[t[2]]
        local opt = mod ~= nil and mod.dict[t[3]] or nil
        if opt ~= nil then
            if e.applied ~= opt then   -- a repeat call keeps what the mod has set since; a new option takes its saved value
                e.applied = opt
                apply(opt, t)
            end
        else
            other[#other + 1] = e.raw   -- not (yet) registered: kept for the next save, as stock
        end
    end
    PZAPI.ModOptions.OtherOptions = other
end

Events.OnMainMenuEnter.Add(function()
    PZAPI.ModOptions.load = originalLoad
    lines = nil
end)
