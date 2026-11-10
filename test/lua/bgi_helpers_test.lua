local here = debug.getinfo(1, "S").source:sub(2)
local root = here:gsub("[/\\]?test/lua/bgi_helpers_test.lua$", "")
if root == "" then root = "." end

-- Hours left = liters / (getTotalPowerUsing() x sandbox GeneratorFuelConsumption): what
-- IsoGenerator.update() burns per hour in 42.21.
BGI = {Options = {ReqElecLvl = 0, ConvertToRT = false}}
local realRequire = require
function require(name)
    if name == "BGI/BGI_Options" then return BGI.Options end
    return realRequire(name)
end

local multiplier = 0.1
function getSandboxOptions()
    return {
        getOptionByName = function() return {getValue = function() return multiplier end} end,
        getDayLengthMinutes = function() return 60 end,
    }
end

dofile(root .. "/mod/PZButter_BetterGeneratorInfo/42.20/media/lua/client/BGI/BGI_Helpers.lua")
local H = BGI.Helpers

local power = 0.5
local gen = {
    getFuelPercentage = function() return 50 end,       -- 5 L of a 10 L tank
    getTotalPowerUsing = function() return power end,
}

local function near(a, b) return math.abs(a - b) < 1e-9 end
assert(near(H.getHoursRemaining(gen, 50), 5 / (0.5 * 0.1)), "hours must use power x sandbox factor")
assert(near(H.getHoursRemainingCached(gen, 50), 100), "cached path differs")
multiplier = 1.0
assert(near(H.getHoursRemainingCached(gen, 50), 10), "cache must follow the sandbox factor")
power = 0
assert(H.getHoursRemainingCached(gen, 50) == 0, "no consumption: 0 hours")
assert(H.getHoursRemainingCached(nil, 50) == 0, "nil generator: 0 hours")
print("bgi_helpers_test: ok")
