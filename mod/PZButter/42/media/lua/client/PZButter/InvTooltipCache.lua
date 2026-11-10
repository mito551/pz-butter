-- PZButter invTooltipCache: vanilla ISToolTipInv:render runs item:DoTooltip twice per frame — a measure-only pass
-- (setWidth(50) + setMeasureOnly(true)) to learn the tooltip's width and height for positioning, then the draw pass.
-- On a cache hit the measure pass is skipped and its (width, height) reused. The draw pass always runs, so the
-- content stays current. The cache key carries a 250 ms time bucket, forcing a re-measure at least about four
-- times a second; after drawing, if the tooltip's real size differs from what was measured, the entry is dropped
-- so the next frame re-measures. Stand-downs: never caches while TooltipLib is loaded, and never installs when
-- another mod has already wrapped ISToolTipInv.render before this file loads (the stock function it captures).
if not PZButter then return end

local stock = ISToolTipInv.render
local cache = {} -- key -> { width, height }

local function key(item)
    local k = tostring(item)
    local ok, v = pcall(function() return tostring(item:getCondition()) end)
    if ok then k = k .. "|" .. v end
    ok, v = pcall(function() return tostring(item:getWeight()) end)
    if ok then k = k .. "|" .. v end
    ok, v = pcall(function() return tostring(item:getFavorite()) end)
    if ok then k = k .. "|" .. v end
    return k .. "|" .. tostring(math.floor(Calendar.getInstance():getTimeInMillis() / 250))
end

function ISToolTipInv:render()
    -- we render the tool tip for inventory item only if there's no context menu showed (vanilla's own guard)
    if not ISContextMenu.instance or not ISContextMenu.instance.visibleCheck then
        if not PZButter.setting("invTooltipCache") or TooltipLib ~= nil or self.item == nil or self.tooltip == nil then
            stock(self)
            return
        end

        local item = self.item
        local k = key(item)
        local hit = cache[k]

        local mx = getMouseX() + 24;
        local my = getMouseY() + 24;
        if not self.followMouse then
            mx = self:getX()
            my = self:getY()
            if self.anchorBottomLeft then
                mx = self.anchorBottomLeft.x
                my = self.anchorBottomLeft.y
            end
        end

        local PADX = 0

        self.tooltip:setX(mx + PADX);
        self.tooltip:setY(my);

        if hit then
            self.tooltip:setWidth(hit[1])
            self.tooltip:setHeight(hit[2])
        else
            self.tooltip:setWidth(50)
            self.tooltip:setMeasureOnly(true)
            item:DoTooltip(self.tooltip)
            self.tooltip:setMeasureOnly(false)
        end

        -- clampy x, y
        local myCore = getCore();
        local maxX = myCore:getScreenWidth();
        local maxY = myCore:getScreenHeight();

        local tw = self.tooltip:getWidth();
        local th = self.tooltip:getHeight();

        self.tooltip:setX(math.max(0, math.min(mx + PADX, maxX - tw - 1)));
        if not self.followMouse and self.anchorBottomLeft then
            self.tooltip:setY(math.max(0, math.min(my - th, maxY - th - 1)));
        else
            self.tooltip:setY(math.max(0, math.min(my, maxY - th - 1)));
        end

        if self.contextMenu and self.contextMenu.joyfocus then
            local playerNum = self.contextMenu.player
            self.tooltip:setX(getPlayerScreenLeft(playerNum) + 60)
            self.tooltip:setY(getPlayerScreenTop(playerNum) + 60)
        elseif self.contextMenu and self.contextMenu.currentOptionRect then
            if self.contextMenu.currentOptionRect.height > 32 then
                self:setY(my + self.contextMenu.currentOptionRect.height)
            end
            self:adjustPositionToAvoidOverlap(self.contextMenu.currentOptionRect)
        end

        self:setX(self.tooltip:getX() - PADX);
        self:setY(self.tooltip:getY());
        self:setWidth(tw + PADX);
        self:setHeight(th);

        if self.followMouse and (self.contextMenu == nil) then
            self:adjustPositionToAvoidOverlap({ x = mx - 24 * 2, y = my - 24 * 2, width = 24 * 2, height = 24 * 2 })
        end

        self:drawRect(0, 0, self.width, self.height, self.backgroundColor.a, self.backgroundColor.r, self.backgroundColor.g, self.backgroundColor.b);
        self:drawRectBorder(0, 0, self.width, self.height, self.borderColor.a, self.borderColor.r, self.borderColor.g, self.borderColor.b);
        if self.item then self.item:DoTooltip(self.tooltip) end;

        if hit then
            if self.tooltip:getWidth() ~= hit[1] or self.tooltip:getHeight() ~= hit[2] then
                cache[k] = nil -- the drawn size differs from the measure: re-measure next frame
            end
        else
            cache[k] = { self.tooltip:getWidth(), self.tooltip:getHeight() }
        end
    end
end
