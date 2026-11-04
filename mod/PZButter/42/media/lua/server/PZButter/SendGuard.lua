-- PZ Butter: containerSendGuard (SPEC 6.8.1). The Java guard drops calls with no destination; this one
-- drops calls with no container or item at all, which the server wrapper would otherwise turn into an
-- error. Every other call goes straight to the original: no pcall, so real errors stay visible.
if not (PZButter and PZButter.containerSendGuard) then return end
if PZButterSendGuard then return end   -- a reload keeps the first wrapper
PZButterSendGuard = true

local send = sendAddItemToContainer
if send ~= nil then
    function sendAddItemToContainer(container, item, ...)
        if container == nil or item == nil then return end
        return send(container, item, ...)
    end
end
