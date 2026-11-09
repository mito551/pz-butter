--// Weapon Condition Indicator
--// aspctt - 10.08.2026
--// Draws a small condition bar under the weapon in each hand and under every item on
--// the hotbar, so a weapon about to break is visible without hovering it.
--//
--// Vanilla knows an item's condition and uses it, but never shows it. ISHotbar:render
--// draws the slot border, its number, the item icon and an equipped marker and stops
--// there, and ISEquippedItem:render only reads getCondition to decide whether an item
--// can be dragged into a hand at all.
--//
--// Written from scratch against getCondition and getConditionMax. Nothing is bundled
--// and no art is needed, because a bar is two rectangles.
--//
--// The hotbar half is drawn from qolc_reorder_hotbar.lua rather than by overriding
--// ISHotbar.render again. That file already owns the render, and a second override of
--// the same function is how mods end up silently erasing each other.
--//
--// Stands down entirely when a mod that already draws condition on these slots is
--// installed, see CONFLICTING_MODS. Clean HotBar is the case this was written for: it
--// chains ISEquippedItem.render rather than replacing it, so without this both its
--// indicator and ours are drawn on the same two hand slots.

--// Textures
-- The equipped hand slots are round. A rectangular fill spills out of them at the
-- corners, so those get a disc clipped to however much should be filled. Hotbar slots
-- are square and take a plain rectangle.
local TextureDisc = getTexture("media/textures/GUI/qolc_slot_disc.png")

--// Tuning
-- The whole slot is the gauge. It fills from the bottom, so how much colour is showing
-- is how much of the weapon is left, readable at a glance without reading anything.
--
-- Drawn before the icon rather than over it, so the icon stays crisp and the colour
-- reads as the slot's background. That is also why this can be as strong as it is.
local FILL_ALPHA = 0.55

-- A nearly broken weapon still shows a sliver, so "almost gone" never looks like
-- "nothing here".
local MINIMUM_FILL = 2

-- How much of a hand slot the circle actually occupies. Measured from vanilla's own
-- artwork rather than guessed: at 128 the main hand draws a 118 wide circle in a 128 box
-- and the off hand an 81 wide one in a box 92 tall. The tighter of the two is used, so
-- the fill sits inside the ring in both instead of bleeding a pixel or two past it.
local DISC_SCALE = 0.88

-- Condition runs 0 to getConditionMax, which differs per item, so it is always compared
-- as a fraction. Below these the fill turns amber, then red.
local LEVEL_CAUTION = 0.5
local LEVEL_DANGER = 0.25

-- Green drains out of the fill as the weapon wears, rather than the three being three
-- unrelated colours. That way the change reads even to someone who cannot separate red
-- from green, because the fill also gets visibly duller.
local COLOUR_HEALTHY = { r = 0.30, g = 0.78, b = 0.30 }
local COLOUR_CAUTION = { r = 0.85, g = 0.68, b = 0.20 }
local COLOUR_DANGER = { r = 0.85, g = 0.22, b = 0.22 }

-- Those three are the fallback. By default the fill takes the game's own 'Good' and 'Bad'
-- highlight colours from its Accessibility options instead, so a player who has set those
-- for colour blindness gets the same pair here without setting anything twice. Asked for
-- by a player, and one tick box brings these three back.

--// Conflicts
-- Mods that draw their own condition on the same slots. Both would be drawn otherwise,
-- because Clean HotBar chains ISEquippedItem.render rather than replacing it, so ours
-- still runs underneath its own indicator and the hand slots end up with two.
--
-- Keyed by mod id, which is the id= line in the other mod's mod.info and what
-- getActivatedMods returns. Adding another one here is the whole of adding a conflict.
local CONFLICTING_MODS = { CleanHotBar = true }

local ConflictChecked = false
local Conflicting = false
-- PZ Butter QoLC-4 cache store.
local DrawCache = {}
--// Options
local OPTIONS_ID = "QoLC"
local Options = {}

--// Functions
-- Returns 0 to 1, or nil when the item has no condition to speak of. Food, books and
-- most everything that is not a weapon or a tool report a max of zero.
function QolcConditionFraction(Item)
	if not Item or not Item.getConditionMax then return nil end

	local Max = Item:getConditionMax()
	if not Max or Max <= 0 then return nil end

	local Current = Item:getCondition() or 0
	local Fraction = Current / Max

	if Fraction < 0 then return 0 end
	if Fraction > 1 then return 1 end

	return Fraction
end

local function AccessibleColoursEnabled()
	if not Options.AccessibleColours then return true end
	return Options.AccessibleColours:getValue() and true or false
end

-- Returns the fill's red, green and blue. Three numbers rather than a table, because this
-- runs for every drawn slot on every frame.
local function ColourFor(Fraction)
	if AccessibleColoursEnabled() then
		-- Read on every draw rather than kept, so a colour changed in the options shows on
		-- the next frame. Warning is halfway between the two, the way vanilla shades a
		-- moveable's health between the same pair in ISMoveableSpriteProps.
		local Core = getCore()
		local Good = Core:getGoodHighlitedColor()
		local Bad = Core:getBadHighlitedColor()

		if Good and Bad then
			if Fraction <= LEVEL_DANGER then return Bad:getR(), Bad:getG(), Bad:getB() end

			if Fraction <= LEVEL_CAUTION then
				return (Good:getR() + Bad:getR()) / 2,
					(Good:getG() + Bad:getG()) / 2,
					(Good:getB() + Bad:getB()) / 2
			end

			return Good:getR(), Good:getG(), Good:getB()
		end
	end

	local Colour = COLOUR_HEALTHY
	if Fraction <= LEVEL_DANGER then
		Colour = COLOUR_DANGER
	elseif Fraction <= LEVEL_CAUTION then
		Colour = COLOUR_CAUTION
	end

	return Colour.r, Colour.g, Colour.b
end

-- Answered once and remembered. The mod list cannot change without restarting the game,
-- and this is asked on every drawn slot on every frame.
function QolcConditionConflict()
	if ConflictChecked then return Conflicting end
	ConflictChecked = true

	local Mods = getActivatedMods and getActivatedMods()
	if not Mods then return Conflicting end

	-- A java ArrayList of mod ids, so contains does the work. Same shape as the guard in
	-- qolc_sling_hotbar.lua.
	for Name in pairs(CONFLICTING_MODS) do
		if Mods:contains(Name) then
			Conflicting = true
			return true
		end
	end

	return false
end

function QolcConditionEnabled()
	-- Ahead of the option, because a mod already drawing this wins over a tick box. The
	-- alternative is two indicators on one slot and a player who cannot turn either off
	-- without knowing which mod drew which.
	if QolcConditionConflict() then return false end

	if not Options.Enabled then return true end
	return Options.Enabled:getValue() and true or false
end

-- Fills the box given from the bottom up, in proportion to what is left. Silently does
-- nothing for an item with no condition, which is most of them.
--
-- Round is for the equipped hand slots. Those are circles, so the fill is a disc clipped
-- to the filled height rather than a rectangle that would poke out at the corners.
function QolcDrawCondition(Panel, X, Y, Width, Height, Item, Round)
	if not Panel or not QolcConditionEnabled() then return false end
	if Width <= 0 or Height <= 0 then return false end

	-- PZ Butter QoLC-4: cache the fraction and colour for an item whose condition (and the
	-- current accessibility option state) has not changed, so repeated draws recompute nothing.
	local ItemId = Item and type(Item.getID) == "function" and Item:getID() or nil
	local MaxCond = ItemId and Item:getConditionMax() or 0
	local CurCond = Item and Item.getCondition and (Item:getCondition() or 0) or 0
	local OptionKey = (Options.AccessibleColours and (Options.AccessibleColours:getValue() and 1 or 0) or 0)
		+ (Options.Enabled and (Options.Enabled:getValue() and 2 or 0) or 0)
	local CacheKey = ItemId and (tostring(ItemId) .. "|" .. tostring(CurCond) .. "|" .. tostring(MaxCond) .. "|" .. tostring(OptionKey)) or nil

	local cached = CacheKey and DrawCache[CacheKey]
	if cached == nil and CacheKey then
		local Fraction0 = QolcConditionFraction(Item)
		if Fraction0 == nil then
			DrawCache[CacheKey] = false
			cached = false
		else
			cached = { Fraction = Fraction0 }
			DrawCache[CacheKey] = cached
		end
	end
	if cached == false then return false end

	local Fraction = cached and cached.Fraction or QolcConditionFraction(Item)
	if not Fraction and Fraction ~= 0 then return false end
	if Fraction == nil then return false end

	local R, G, B = ColourFor(Fraction)
	if Round and TextureDisc then
		-- A circle centred in the box, never the box stretched to fit. The off hand's
		-- box is three quarters as tall as it is wide, so filling it would give an
		-- ellipse rather than the round slot the player sees.
		local Diameter = math.min(Width, Height) * DISC_SCALE
		local DiscX = X + ((Width - Diameter) / 2)
		local DiscY = Y + ((Height - Diameter) / 2)

		local Filled = Diameter * Fraction
		if Fraction > 0 and Filled < MINIMUM_FILL then Filled = MINIMUM_FILL end
		if Filled > Diameter then Filled = Diameter end
		if Filled <= 0 then return false end

		-- Draw the whole disc but let only the filled band through, which gives a
		-- circle filling from the bottom rather than a shrinking dot
		Panel:setStencilRect(DiscX, DiscY + Diameter - Filled, Diameter, Filled)
		Panel:drawTextureScaled(TextureDisc, DiscX, DiscY, Diameter, Diameter,
			FILL_ALPHA, R, G, B)
		Panel:clearStencilRect()

		return true
	end

	local Filled = Height * Fraction
	if Fraction > 0 and Filled < MINIMUM_FILL then Filled = MINIMUM_FILL end
	if Filled > Height then Filled = Height end
	if Filled <= 0 then return false end

	Panel:drawRect(X, Y + Height - Filled, Width, Filled, FILL_ALPHA, R, G, B)
	return true
end

--// Equipped Hands
-- Wrapped rather than replaced, so whatever else vanilla draws in the panel still runs.
-- Drawn before it, not after, because vanilla draws the item icon in this same call and
-- the colour belongs behind that icon rather than washed across it.
local VanillaEquippedRender = ISEquippedItem.render
function ISEquippedItem:render()
	if self.chr then
		local Hands = {
			{ Box = self.mainHand, Item = self.chr:getPrimaryHandItem() },
			{ Box = self.offHand, Item = self.chr:getSecondaryHandItem() }
		}

		for _, Hand in ipairs(Hands) do
			local Box = Hand.Box
			if Box and Hand.Item then
				QolcDrawCondition(self, Box.x, Box.y, Box.width, Box.height, Hand.Item, true)
			end
		end
	end

	VanillaEquippedRender(self)
end

--// Mod Options
-- One shared category for the whole compendium, see qolc_immersive_overlays.lua.
local function CreateModOptions()
	if not PZAPI or not PZAPI.ModOptions then return end

	local ModOptions = PZAPI.ModOptions:getOptions(OPTIONS_ID)
	if not ModOptions then
		ModOptions = PZAPI.ModOptions:create(OPTIONS_ID, "UI_options_QoLC")
	end

	ModOptions:addTitle("UI_options_QoLC_Condition")
	ModOptions:addDescription("UI_options_QoLC_Condition_Desc")

	Options.Enabled = ModOptions:addTickBox("ConditionEnabled", "UI_options_QoLC_Condition_Enabled", true, "UI_options_QoLC_Condition_Enabled_tooltip")
	Options.AccessibleColours = ModOptions:addTickBox("ConditionAccessibleColours", "UI_options_QoLC_Condition_Accessible", true, "UI_options_QoLC_Condition_Accessible_tooltip")
end

CreateModOptions()
