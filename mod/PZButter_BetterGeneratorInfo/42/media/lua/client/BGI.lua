require("BGI/BGI_Options")
local H = require("BGI/BGI_Helpers")

BGI = BGI or {}

BGI.days = 0
BGI.hours = 0
BGI.hoursDec = 0

function BGI:_calcFuelLeft(generator, fuelPct)
    -- One source of truth:
    self.hoursDec = H.getHoursRemainingCached(generator, fuelPct or 0) or 0
    self.hours = math.floor(self.hoursDec)
    self.days = math.floor(self.hours / 24)
    if self.days >= 1 then
        self.hours = self.hours % 24
    end
end

function BGI:toString(generator, fuel)
    if not generator:isActivated() or not H.hasReqElecLvl() then
        return ""
    end

    self:_calcFuelLeft(generator, fuel)

    local days, hours = self.days, self.hours
    local hourWord = getText(hours > 1 and "Tooltip_BGI_Hours" or "Tooltip_BGI_Hour")

    if days >= 1 then
        local dayWord = getText(days > 1 and "Tooltip_BGI_Days" or "Tooltip_BGI_Day")
        if hours >= 1 then
            return string.format(" (%.0f %s, %.0f %s)", days, dayWord, hours, hourWord)
        end
        return string.format(" (%.0f %s)", days, dayWord)
    end

    if hours >= 1 then
        return string.format(" (%.0f %s)", hours, hourWord)
    end
    return string.format(" (%.0f %s)", self.hoursDec * 60, getText("Tooltip_BGI_Minutes"))
end

function ISGeneratorInfoWindow.getRichText(object, displayStats)
    local square = object:getSquare()
    if not displayStats then
        local text = " <INDENT:10> "
        if square and not square:isOutside() and square:getBuilding() then
            text = text .. " <RED> " .. getText("IGUI_Generator_IsToxic")
        end
        return text
    end
    local fuelRaw = H.getFuelPercentRaw(object)
    local fuelShow = math.ceil(fuelRaw)
    local fuelLeft = BGI:toString(object, fuelRaw)
    local condition = object:getCondition()
    local text = getText("IGUI_Generator_FuelAmount", fuelShow) .. fuelLeft .. " <LINE> " ..
                     getText("IGUI_Generator_Condition", condition) .. " <LINE> "
    if object:isActivated() then
        text = text .. " <LINE> " .. getText("IGUI_PowerConsumption") .. ": <LINE> ";
        text = text .. " <INDENT:10> "
        local items = object:getItemsPowered()
        for i = 0, items:size() - 1 do
            text = text .. "   " .. items:get(i) .. " <LINE> ";
        end
        text = text .. getText("IGUI_Generator_TypeGas") .. " (" .. object:getBasePowerConsumptionString() ..
                   ") <LINE> "
        text = text .. getText("IGUI_Total") .. ": " .. object:getTotalPowerUsingString() .. " <LINE> ";
    end
    if square and not square:isOutside() and square:getBuilding() then
        text = text .. " <LINE> <RED> " .. getText("IGUI_Generator_IsToxic")
    end
    return text
end
