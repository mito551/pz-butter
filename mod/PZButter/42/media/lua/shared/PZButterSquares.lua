-- PZ Butter shared LoadGridsquare prefilter (modopt plan 03, QoLC-1). Other mods register handlers with a mask
-- instead of Events.LoadGridsquare directly, so the expensive body only runs on squares that can possibly matter.
PZButterSquares = PZButterSquares or {
    MASK_DOOR = 1,
    MASK_WINDOW = 2,
    MASK_VEHICLE = 4,
    handlers = {},
    registered = false,
}

local S = PZButterSquares

function S.add(mask, fn)
    if type(fn) ~= "function" then return false end
    table.insert(S.handlers, { mask = mask or 0, fn = fn })
    if not S.registered then
        S.registered = true
        Events.LoadGridsquare.Add(function(square)
            if not square then return end
            local door = false
            local window = false
            local vehicle = square.getVehicleContainer and square:getVehicleContainer() ~= nil
            pcall(function()
                if square.HasDoor then door = square:HasDoor(true) == true end
            end)
            pcall(function()
                if square.hasWindowOrWindowFrame then window = square:hasWindowOrWindowFrame() == true
                elseif square.hasWindowFrame then window = square:hasWindowFrame() == true end
            end)
            for _, h in ipairs(S.handlers) do
                local run = false
                if h.mask and S.MASK_DOOR and (h.mask % 2) ~= 0 then run = run or door end
                if h.mask and S.MASK_WINDOW and (math.floor(h.mask / 2) % 2) ~= 0 then run = run or window end
                if h.mask and S.MASK_VEHICLE and (math.floor(h.mask / 4) % 2) ~= 0 then run = run or vehicle end
                if run then pcall(h.fn, square) end
            end
        end)
    end
    return true
end