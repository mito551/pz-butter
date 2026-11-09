require "ISUI/ISHotbar"

local FONT_HGT_SMALL = getTextManager():getFontHeight(UIFont.Small)

-- Mod option
local hotbarOptions = PZAPI.ModOptions:create(
    "AuthenticZHotbar",
    getText("UI_AuthenticZ_Hotbar_Title")
)

local hideSlotsOption = hotbarOptions:addTickBox(
    "HideBackpackSlots",
    getText("UI_AuthenticZ_Hotbar_HideSlots"),
    false,
    getText("UI_AuthenticZ_Hotbar_HideSlots_Tooltip")
)

-- Authentic Z backpack slot families (lowercase; matched case-insensitively)
local AZ_SLOT_PREFIXES = {
    "schoolbag",
    "dufflebag",
    "hikingbag",
    "bighiking",
    "alicepack",
    "arvn",
    "bedrollbottom",
}

local function isAuthenticZSlot(slotType)
    if not slotType then return false end

    slotType = string.lower(slotType)
    for _, prefix in ipairs(AZ_SLOT_PREFIXES) do
        if string.sub(slotType, 1, #prefix) == prefix then
            return true
        end
    end

    return false
end

-- The cache is valid while the option, the slot table and every slot's type are
-- unchanged. Comparing slot types (cheap) catches in-place edits and avoids '#'
-- on a table that may have gaps.
local function isCacheValid(hotbar, hide, slots)
    if not hotbar._azVisibleSlots
    or hotbar._azVisibleHide ~= hide
    or hotbar._azVisibleSource ~= slots then
        return false
    end

    local types = hotbar._azVisibleTypes
    local n = 0
    for index, slot in pairs(slots) do
        if types[index] ~= slot.slotType then return false end
        n = n + 1
    end

    return n == hotbar._azVisibleCount
end

local function getVisibleSlots(hotbar)
    local hide = hideSlotsOption:getValue()
    local slots = hotbar.availableSlot or {}

    if isCacheValid(hotbar, hide, slots) then
        return hotbar._azVisibleSlots
    end

    local visible = {}
    local types = {}
    local count = 0

    for index, slot in pairs(slots) do
        types[index] = slot.slotType
        count = count + 1

        if not hide or not isAuthenticZSlot(slot.slotType) then
            visible[#visible + 1] = {
                index = index,
                slot = slot
            }
        end
    end

    table.sort(visible, function(a, b)
        return a.index < b.index
    end)

    hotbar._azVisibleSlots = visible
    hotbar._azVisibleHide = hide
    hotbar._azVisibleSource = slots
    hotbar._azVisibleTypes = types
    hotbar._azVisibleCount = count

    return visible
end

-- Keep the normal hotbar size when the option is disabled.
local originalSetSizeAndPosition = ISHotbar.setSizeAndPosition

function ISHotbar:setSizeAndPosition()
    if not hideSlotsOption:getValue() then
        return originalSetSizeAndPosition(self)
    end

    local visible = getVisibleSlots(self)
    local count = #visible

    local width = count * self.slotWidth

    if count > 1 then
        width = width + (count - 1) * self.slotPad
    end

    self:setWidth(width + self.margins * 2 + 2)

    local screenX = getPlayerScreenLeft(self.playerNum)
    local screenY = getPlayerScreenTop(self.playerNum)
    local screenW = getPlayerScreenWidth(self.playerNum)
    local screenH = getPlayerScreenHeight(self.playerNum)

    self:setX(screenX + (screenW - self.width) / 2)
    self:setY(screenY + screenH - self.height)
end

-- Keep the original mouse lookup when the option is disabled.
local originalGetSlotIndexAt = ISHotbar.getSlotIndexAt

function ISHotbar:getSlotIndexAt(x, y)
    if not hideSlotsOption:getValue() then
        return originalGetSlotIndexAt(self, x, y)
    end

    if x < 0 or x >= self.width or y < 0 or y >= self.height then
        return -1
    end

    local visible = getVisibleSlots(self)

    if #visible == 0 then
        return -1
    end

    local visualIndex =
        math.floor((x - self.margins) / (self.slotWidth + self.slotPad)) + 1

    visualIndex = math.max(visualIndex, 1)
    visualIndex = math.min(visualIndex, #visible)

    return visible[visualIndex].index
end

-- Preserve vanilla rendering when the option is disabled.
local originalRender = ISHotbar.render

function ISHotbar:render()
    if not hideSlotsOption:getValue() then
        return originalRender(self)
    end

    if (self.playerNum > 0) or JoypadState.players[self.playerNum + 1] then
        self:setVisible(false)
    end

    local visible = getVisibleSlots(self)

    self:drawRectBorderStatic(
        0, 0, self.width, self.height,
        self.borderColor.a,
        self.borderColor.r,
        self.borderColor.g,
        self.borderColor.b
    )

    local mouseOverSlotIndex =
        self:getSlotIndexAt(self:getMouseX(), self:getMouseY())

    local draggedItem = nil

    if ISMouseDrag.dragging and mouseOverSlotIndex ~= -1 then
        local dragging =
            ISInventoryPane.getActualItems(ISMouseDrag.dragging)

        local slot = self.availableSlot[mouseOverSlotIndex]

        for _, item in ipairs(dragging) do
            if self:canBeAttached(slot, item) then
                draggedItem = item
                break
            end
        end
    end

    local slotX = self.margins + 1

    for _, entry in ipairs(visible) do
        local i = entry.index
        local slot = entry.slot

        self:drawRectBorderStatic(
            slotX,
            self.margins + 1,
            self.slotWidth,
            self.slotHeight,
            self.borderColor.a,
            self.borderColor.r,
            self.borderColor.g,
            self.borderColor.b
        )

        -- Keep the real hotbar number.
        self:drawText(
            tostring(i),
            slotX + 3,
            self.margins + 2,
            self.textColor.r,
            self.textColor.g,
            self.textColor.b,
            self.textColor.a,
            self.font
        )

        local item = self.attachedItems[i]

        if i == mouseOverSlotIndex then
            local r, g, b = 1, 1, 1

            if draggedItem then
                item = draggedItem
            elseif ISMouseDrag.dragging then
                r, g, b = 1, 0, 0
            end

            self:drawRect(
                slotX,
                self.margins + 1,
                self.slotWidth,
                self.slotHeight,
                0.2, r, g, b, 1
            )

            local slotName =
                getTextOrNull("IGUI_HotbarAttachment_" .. slot.slotType)
                or slot.name

            local textWid =
                getTextManager():MeasureStringX(UIFont.Small, slotName)

            self:drawRect(
                slotX + (self.slotWidth - textWid) / 2 - 2,
                -FONT_HGT_SMALL,
                textWid + 4,
                FONT_HGT_SMALL,
                0.6, 0, 0, 0
            )

            self:drawText(
                slotName,
                slotX + (self.slotWidth - textWid) / 2,
                -FONT_HGT_SMALL,
                self.textColor.r,
                self.textColor.g,
                self.textColor.b,
                self.textColor.a,
                self.font
            )

        elseif item == draggedItem then
            item = nil
        end

        if item then
            local tex = item:getTexture()

            self:drawTexture(
                tex,
                slotX + tex:getWidth() / 2,
                (self.height - tex:getHeight()) / 2,
                1, 1, 1, 1
            )

            if item:isEquipped() then
                tex = self.equippedItemIcon

                self:drawTexture(
                    tex,
                    slotX + self.slotWidth - tex:getWidth() - 5,
                    self.height - self.margins - tex:getHeight() - 5,
                    1, 1, 1, 1
                )
            end

        elseif slot.texture then
            self:drawTexture(
                slot.texture,
                slotX + slot.texture:getWidth() / 2,
                (self.height - slot.texture:getHeight()) / 2,
                0.25, 1, 1, 1
            )
        end

        slotX = slotX + self.slotWidth + self.slotPad
    end
end
