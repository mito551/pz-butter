-- PZButter workshopAutoInstall: on the server's workshop confirm screen (OptionScreens/ServerWorkshopItemScreen.lua),
-- when every required item has its details, no error showed and the not-installed items stay under 3 GB total
-- (Steam's uint32 sizes arrive as signed ints: add 2^32 to a negative size; installed items count 0), the Install
-- button is pressed 1.2 s later. Holding Esc keeps the screen: the press waits for the release.
if not PZButter then return end

local HOLD_MS = 1200
local LIMIT = 3.0 * 1024 * 1024 * 1024

local s = { armed = nil, fired = false, failed = false }

local function reset()
    s.armed = nil
    s.fired = false
    s.failed = false
end

local function underLimit(screen)
    local total = 0
    local items = screen.listbox.items
    for i = 1, #items do
        local data = items[i].item
        if data.details == nil then return false end
        if data.details:getState() ~= "Installed" then
            local size = data.details:getFileSize()
            if size < 0 then size = size + 4294967296 end
            total = total + size
        end
    end
    return #items > 0 and total < LIMIT
end

local function tick(screen)
    if not screen.installButton or not screen:getIsVisible() then reset() return end
    if s.failed or s.fired or not PZButter.setting("workshopAutoInstall") then return end
    if isKeyDown(Keyboard.KEY_ESCAPE) then return end
    if not underLimit(screen) then reset() return end
    local now = Calendar.getInstance():getTimeInMillis()
    if not s.armed then s.armed = now return end
    if now - s.armed >= HOLD_MS then
        s.fired = true
        screen:onButtonInstall()
    end
end

local patched = false
local function patch()
    if patched then return end
    if ServerWorkshopItemScreen == nil or ServerWorkshopItemScreen.render == nil then return end
    local stock = ServerWorkshopItemScreen.render
    function ServerWorkshopItemScreen:render(...)
        stock(self, ...)
        tick(self)
    end
    patched = true
end

Events.OnServerWorkshopItems.Add(function(flag, arg1, arg2, arg3, arg4)
    patch()
    if flag == "Error" and ServerWorkshopItemScreen.instance and ServerWorkshopItemScreen.instance:getIsVisible() then
        s.failed = true
    end
end)
