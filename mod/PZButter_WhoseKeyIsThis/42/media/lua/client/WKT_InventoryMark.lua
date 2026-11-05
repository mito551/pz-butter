--[[
    WKT_Perf build of WKT_InventoryMark.lua. Regenerate with WKT_Perf/tools/build_wkt.py after every update
    of Whose Key Is This?.
]]
do
    local ok, r = pcall(getModFileReader, "WhoseKeyIsThis", "media/lua/client/WKT_InventoryMark.lua", false)
    if ok and r then
        local n, line = 0, r:readLine()
        while line do n = n + #line; line = r:readLine() end
        r:close()
        if n ~= 7881 then
            print("[WKT_Perf] WARNING: WKT_InventoryMark.lua of Whose Key Is This? changed since WKT_Perf was built; regenerate WKT_Perf with tools/build_wkt.py")
        end
    end
end

--[[
    Whose Key Is This?  /  이 열쇠는 어디꺼야?
    Marks keys with the pointer switched on inside the ordinary inventory
    panes, the same way the map list does: a coloured disc behind the icon
    and the name in the category colour.

    NO SORTING HERE EITHER. Lifting starred keys to the top of the inventory
    used to live in this file, hooked into the pane's own refresh - which the
    game runs whenever a container changes, so in front of a stack of crates
    or a full car boot it ran every frame over every item. 별표를 어디에
    두었는지는 지도의 열쇠 목록이 이미 보여 주므로, 그 값을 치를 이유가
    없어 걷어냈습니다.

    NO STARS HERE. The mod keeps its own note of which keys you have starred,
    but it does not draw it in the inventory: the game draws a star of its
    own for its own favourite flag, and a second one beside it was just
    confusing. The star belongs to the key list on the map, which is where
    starring a key is done and where it changes what the list does.

    Build 42.20
]]

require "ISUI/ISInventoryItem"
require "ISUI/ISInventoryPane"
require "WKT_Shared"

local circleTex = nil

---------------------------------------------------------------------------
-- Which keys are marked, refreshed on a timer rather than per row
---------------------------------------------------------------------------

local marked = {}       -- item -> {r, g, b}
local markedNames = {}  -- display name -> {r, g, b}
local colourCache = {}  -- text -> colour|false
local anyMarked = false
local nextRefresh = 0

local function refresh()
    local now = getTimestampMs()
    if now < nextRefresh then return end
    nextRefresh = now + 1000

    marked = {}
    markedNames = {}
    colourCache = {}
    anyMarked = false

    if not WKT.isEnabled() then return end
    local playerObj = getSpecificPlayer(0)
    if not playerObj or playerObj:isDead() then return end

    for _, item in ipairs(WKT.getPlayerKeys(playerObj)) do
        if WKT.isPointing(item) then
            local data = WKT.getData(item)
            if data and WKT.canReadLocation(playerObj, data, item) then
                local r, g, b = WKT.markerColor(playerObj, item, data)
                local colour = { r = r, g = g, b = b }
                marked[item] = colour
                local ok, name = pcall(function() return item:getName() end)
                if ok and name then markedNames[name] = colour end
                anyMarked = true
            end
        end
    end
end

---------------------------------------------------------------------------
-- The disc, drawn behind the icon
--
-- Wrapping renderItemIcon rather than the pane itself means this lands in
-- every list that draws items - the player's inventory, loot windows, and
-- anything else built on the same helper.
---------------------------------------------------------------------------

if WKT.claimPatch("renderItemIcon") then
local origRenderItemIcon = ISInventoryItem.renderItemIcon

ISInventoryItem.renderItemIcon = function(self, _item, _x, _y, _alpha, _w, _h)
    if anyMarked and _item and marked[_item] and _w and _h then
        circleTex = circleTex or getTexture("media/ui/circle.png")
        if circleTex then
            local c = marked[_item]
            -- Tucked inside the icon box rather than haloed around it. The
            -- inventory row is tight, and a disc any bigger than the icon
            -- swamps the line it sits on.
            local size = math.min(_w, _h) * 0.8
            self:drawTextureScaledAspect(circleTex,
                _x + (_w - size) / 2, _y + (_h - size) / 2,
                size, size, 1, c.r, c.g, c.b)
        end
    end
    return origRenderItemIcon(self, _item, _x, _y, _alpha, _w, _h)
end

end

---------------------------------------------------------------------------
-- The star on the container buttons
--
-- The one place the mod still draws a star of its own, because it is the one
-- place the game draws none: the strip of container buttons down the side of
-- the window is built from the container, not from the item, so a starred
-- key ring showed a star in the list and nothing at all on the button you
-- actually click. Nothing to duplicate, nothing to argue with.
--
-- Each button gets its own render rather than the class being wrapped: the
-- buttons are pooled and handed back out, so setting it as they are made
-- covers every one of them without touching every other button in the game.
--
-- WHAT WE DRAW OVER, AND WHAT WE MUST NOT TAKE AWAY. The first version of
-- this replaced the button's render outright and called ISButton.render from
-- inside it. That answers "draw a plain button, then my star" - but a button
-- does not have to be a plain button. Another mod may have put its own render
-- on it, and the game may hand back one with a render of its own. Ours went
-- in last and threw all of that away, so their drawing simply stopped
-- happening. A user reported exactly that.
--
-- So we keep whatever render the button already has and add to it rather than
-- replacing it. The mark stops us wrapping the same button twice, which
-- matters because the buttons are pooled: without it, every reuse would stack
-- another layer.
---------------------------------------------------------------------------

local starTex = nil

--- The star itself. Drawn after whatever the button normally draws.
local function drawStar(button)
    local now = getTimestampMs()
    local c = button.wktStarCache
    local item = nil
    -- buttons are pooled and handed to other containers, so the cache is per container too
    if not (c and c.inv == button.inventory and (now - c.at) < 500) then
        if button.inventory then
            local ok
            ok, item = pcall(button.inventory.getContainingItem, button.inventory)
            if not ok then item = nil end
        else
            item = nil
        end
        local fav = false
        if item ~= nil then fav = WKT.isFavorite(item) end
        button.wktStarCache = { inv = button.inventory, item = item, fav = fav, at = now }
        c = button.wktStarCache
    else
        item = c.item
    end
    if not c or c.item == nil then return end
    if not c.fav then return end

    starTex = starTex or getTexture("media/ui/FavoriteStar.png")
    if starTex == nil then return end

    -- bottom left, clear of the item icon in the middle and of the capacity
    -- bar the button draws along its bottom edge
    local size = math.max(8, math.floor(button.height * 0.32))
    button:drawTextureScaledAspect(starTex, 2, button.height - size - 3,
                                   size, size, 1, 1, 1, 1)
end

if WKT.claimPatch("ISInventoryPage.addContainerButton") then
local origAddContainerButton = ISInventoryPage.addContainerButton

function ISInventoryPage:addContainerButton(container, texture, name, tooltip)
    local button = origAddContainerButton(self, container, texture, name, tooltip)
    if button == nil or button.wktStarred == true then return button end

    button.wktStarred = true

    -- 이 단추가 지금 쓰고 있는 render 를 그대로 들고 간다. 다른 모드가
    -- 얹어 둔 것일 수도, 하위 클래스의 것일 수도 있다. 없을 때만 기본을 쓴다.
    local inner = button.render
    button.render = function(self)
        if inner ~= nil then inner(self) else ISButton.render(self) end
        drawStar(self)
    end

    return button
end

end

---------------------------------------------------------------------------
-- The name, in the category colour
--
-- The pane bakes its text colour into a local, so there is no per-item hook
-- to use. Swapping drawText for the duration of one render is narrow enough
-- to be safe: it only touches strings that exactly match a marked key, and
-- it is put back immediately afterwards.
---------------------------------------------------------------------------

local function colourFor(text)
    if type(text) ~= "string" then return nil end
    if colourCache[text] ~= nil then
        local c = colourCache[text]
        if c == false then return nil end
        return c
    end
    local colour = markedNames[text]
    if colour then colourCache[text] = colour; return colour end
    -- the pane appends " (3)" to stacked rows
    local base = string.match(text, "^(.*) %(%d+%)$")
    if base then
        local bc = markedNames[base]
        colourCache[text] = (bc == nil) and false or bc
        return bc
    end
    colourCache[text] = false
    return nil
end

if WKT.claimPatch("ISInventoryPane.render") then
local origPaneRender = ISInventoryPane.render

function ISInventoryPane:render()
    refresh()
    if not anyMarked then
        return origPaneRender(self)
    end

    local origDrawText = self.drawText
    self.drawText = function(element, text, x, y, r, g, b, a, font)
        local colour = colourFor(text)
        if colour then r, g, b = colour.r, colour.g, colour.b end
        return origDrawText(element, text, x, y, r, g, b, a, font)
    end

    local ok, err = pcall(origPaneRender, self)

    self.drawText = nil  -- back to the class method
    if not ok then error(err) end
end

end
