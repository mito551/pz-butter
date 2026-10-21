-- PZ Butter: uiLuaFast (SPEC 3.9). Wraps a vanilla inventory function: the wrapper calls the original and
-- changes only what the key covers. The Java side sets the global PZButter table before Lua loads.
if not (PZButter and PZButter.uiLuaFast) then return end

-- The item list's second pass (render, doDragged = true) draws only dragged items and the selection box;
-- with neither, it is skipped. Everything else it does, the first pass (prerender) already did this frame.
local renderdetails = ISInventoryPane.renderdetails
function ISInventoryPane:renderdetails(doDragged, ...)
    if doDragged and not (self.dragging ~= nil and self.dragStarted) and not self.draggingMarquis then return end
    return renderdetails(self, doDragged, ...)
end
