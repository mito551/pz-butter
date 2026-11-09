--// Hotbar Binding Sync
--// aspctt - 20.08.2026
--// Keeps the server's copy of every attached item's slot binding current, whoever wrote
--// it and by whatever route.
--//
--// Three fixes were made by finding a call site that changed an item's attached slot
--// without telling the server and adding the call there. Each was a real omission and
--// each was still incomplete, because the binding is written from more places than one
--// can enumerate by reading: our reorder, our attach override, vanilla's timed actions,
--// and the take off and put back at the tail of ISHotbar:refresh, at least. A player
--// kept losing a screwdriver through a path none of those covered.
--//
--// So this stops guessing at call sites. It watches the two fields the game actually
--// persists, remembers what was last sent for each item, and sends again when they
--// differ. It does not care who changed them.
--//
--// Cheap by construction. Nothing is sent while nothing changes, the comparison is two
--// values per attached item, and there are rarely more than a handful. syncItemFields is
--// a no-op outside a multiplayer client, so this idles completely in singleplayer.
--//
--// Client only. The client is the side that knows what it did.

--// Tuning
-- Often enough that a disconnect shortly after attaching still catches it, and idle
-- enough that it costs nothing: this is a table lookup per attached item.
local CHECK_SECONDS = 5

--// State
-- What the server was last told for each item, by item id.
local Told = {}
local NextCheck = 0

--// Functions
local function Key(Item)
	if Item and Item.getID then return Item:getID() end

	return Item
end

-- The pair the game persists and a rejoin restores from.
local function Binding(Item)
	local Slot = Item.getAttachedSlot and Item:getAttachedSlot() or -1
	local Type = Item.getAttachedSlotType and Item:getAttachedSlotType() or nil

	return tostring(Slot) .. "/" .. tostring(Type)
end

local function Reconcile(Player)
	if not Player or not syncItemFields then return end

	local Inventory = Player:getInventory()
	local Items = Inventory and Inventory:getItems()
	if not Items then return end

	for Index = 0, Items:size() - 1 do
		local Item = Items:get(Index)

		-- Only items that are on the bar. One at -1 is either genuinely off it or has
		-- never been on it, and in both cases there is nothing the server needs.
		if Item and Item.getAttachedSlot and Item:getAttachedSlot() > -1 then
			local Now = Binding(Item)
			local Id = Key(Item)

			if Told[Id] ~= Now then
				syncItemFields(Player, Item)
				Told[Id] = Now
			end
		end
	end
end

--// Connections
-- OnPlayerUpdate runs every frame, so this counts real time and acts rarely.
function QolcHotbarSyncUpdate(Player)
	if not Player or Player ~= getSpecificPlayer(0) then return end

	local Now = getTimestampMs and getTimestampMs() or 0
	if Now < NextCheck then return end

	NextCheck = Now + CHECK_SECONDS * 1000
	Reconcile(Player)
end

-- Forgotten on a fresh character, so the first pass after joining sends everything once
-- and re establishes what the server has been told.
local function OnCreatePlayer()
	Told = {}
	NextCheck = 0
end

-- merged registration moved to qolc_merged_player_update.lua
Events.OnCreatePlayer.Add(OnCreatePlayer)
