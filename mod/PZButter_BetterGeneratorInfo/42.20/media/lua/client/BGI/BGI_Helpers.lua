-- Purpose: One source of truth for generator fuel %, liters, consumption, and hover text.
BGI = BGI or {}
BGI.Helpers = BGI.Helpers or {}

local H = BGI.Helpers
local O = require("BGI/BGI_Options")

-- One implementation: use the window player when present, else global player.
function H.hasReqElecLvl(win)
    local min = O.ReqElecLvl or 0
    if min == 0 then
        return true
    end

    local p
    if type(win) == "table" and type(win.playerNum) == "number" and getSpecificPlayer then
        p = getSpecificPlayer(win.playerNum)
    end
    if not p and getPlayer then p = getPlayer() end
    if not p or not p.getPerkLevel then return false end
    return p:getPerkLevel(Perks.Electricity) >= min
end

function H.getDayLength()
    return getSandboxOptions():getDayLengthMinutes()
end

function H.convertToRT(hoursDec)
    local realTime = 60 * 24
    local dayLength = H.getDayLength()

    if dayLength ~= realTime then
        local hoursRT = hoursDec / 24 * dayLength / 60
        return hoursRT
    end
    return hoursDec
end

-- ─────────────────────────────────────────────────────────────────────────────
-- Public: Fuel percent (0..100) as used by vanilla UI
-- ─────────────────────────────────────────────────────────────────────────────
function H.getFuelPercentRaw(gen)
    if not gen or not gen.getFuelPercentage then
        return 0
    end
    local ok, pct = pcall(gen.getFuelPercentage, gen)
    if not ok or type(pct) ~= "number" then
        return 0
    end
    if pct < 0 then
        pct = 0
    elseif pct > 100 then
        pct = 100
    end
    return pct
end

-- ─────────────────────────────────────────────────────────────────────────────
-- Public: Liters (rounded 0.1) if engine exposes capacity/amount
-- ─────────────────────────────────────────────────────────────────────────────
function H.getLiters(gen)
    if not gen then return nil end
    local fuelPct = H.getFuelPercentRaw(gen)
    local tank = 10.0
    if type(gen.getFuelTankCapacity) == "function" then
        local ok, cap = pcall(gen.getFuelTankCapacity, gen)
        if ok and type(cap) == "number" and cap > 0 then tank = cap end
    end
    return (fuelPct / 100.0) * tank
end

-- ─────────────────────────────────────────────────────────────────────────────
-- Internal: sandbox fuel multiplier (the engine burns totalPowerUsing x this per hour)
-- ─────────────────────────────────────────────────────────────────────────────
local function getFuelMultiplier()
    local opt = getSandboxOptions() and getSandboxOptions():getOptionByName("GeneratorFuelConsumption")
    return (opt and opt.getValue and opt:getValue()) or 0.1
end

-- ─────────────────────────────────────────────────────────────────────────────
-- Public: consumption as (value, unit); the unit is always "lph" (liters per hour).
-- PZ Butter: IsoGenerator.update() burns getTotalPowerUsing() x GeneratorFuelConsumption
-- liters per hour, and the vanilla window's "Total" line shows that same product. The
-- target's 42.20 file returns getTotalPowerUsing() alone, which is off by the sandbox
-- factor; this computes the product directly instead of parsing the formatted string.
-- ─────────────────────────────────────────────────────────────────────────────
function H.getLphOrPctPerHour(gen)
    if not gen then
        return 0, "lph"
    end

    local ok, raw = pcall(gen.getTotalPowerUsing, gen)
    if not ok or type(raw) ~= "number" or raw <= 0 then
        return 0, "lph"
    end

    return raw * getFuelMultiplier(), "lph"
end

-- ─────────────────────────────────────────────────────────────────────────────
-- Public: hours remaining (decimal). If convertToRT=true, adjusts for day length.
-- ─────────────────────────────────────────────────────────────────────────────

-- Prefer the engine's fuel amount (liters); fall back to percent x tank size.
local function getFuelAmount(gen)
    if gen.getFuelAmount then
        local ok, a = pcall(gen.getFuelAmount, gen)
        if ok and type(a) == "number" then return a end
    end
    return H.getLiters(gen)
end

-- Pure calculation from already-read inputs.
local function calcHours(cons, unit, amt, fuelPct)
    local hoursDec = 0

    if cons > 0 then hoursDec = amt / cons end

    if O.ConvertToRT then
        hoursDec = H.convertToRT(hoursDec)
    end

    return hoursDec
end

function H.getHoursRemaining(gen, fuelPct)
    if not gen then return 0 end
    fuelPct = math.max(0, math.min(100, fuelPct or 0))

    local cons, unit = H.getLphOrPctPerHour(gen)
    return calcHours(cons, unit, unit == "lph" and getFuelAmount(gen) or nil, fuelPct)
end

-- Cached wrapper: repeated calls with unchanged inputs return the previous result.
-- Keyed weakly by generator object. The key includes everything the result depends
-- on, including the real-time option and the sandbox day length.
H._hoursCache = setmetatable({}, { __mode = "k" })

function H.getHoursRemainingCached(gen, fuelPct)
    if not gen then return 0 end
    fuelPct = math.max(0, math.min(100, fuelPct or 0))

    local cons, unit = H.getLphOrPctPerHour(gen)
    local amt = unit == "lph" and getFuelAmount(gen) or nil
    local rt = O.ConvertToRT and H.getDayLength() or false

    local cache = H._hoursCache[gen]
    if cache and cache.cons == cons and cache.unit == unit and cache.amt == amt
       and cache.fuelPct == fuelPct and cache.rt == rt then
        return cache.hours
    end

    local hours = calcHours(cons, unit, amt, fuelPct)
    H._hoursCache[gen] = { cons = cons, unit = unit, amt = amt, fuelPct = fuelPct, rt = rt, hours = hours }
    return hours
end

-- ─────────────────────────────────────────────────────────────────────────────
-- Public: consolidated hover text with translation fallbacks
-- ─────────────────────────────────────────────────────────────────────────────
function H.formatHover(percent, liters, hours)
    local p = tonumber(percent) or 0
    local l = (liters ~= nil) and tonumber(liters) or nil
    local h = (hours ~= nil) and tonumber(hours) or nil

    local pct = string.format("%d", math.ceil(p)) -- no percent sign here; we add it in the translation with %1%
    local lits = l and string.format("%.1f", l) or nil
    local hrs = h and string.format("%.1f", h) or nil

    if lits and hrs then
        return getText("UI_BGI_Fuel_Hover", pct, lits, hrs)
    elseif lits then
        return getText("UI_BGI_Fuel_HoverLiters", pct, lits)
    elseif hrs then
        return getText("UI_BGI_Fuel_HoverHours", pct, hrs) -- ← matches %2 in the key
    else
        return getText("UI_BGI_Fuel_HoverSimple", pct)
    end
end

return BGI.Helpers
