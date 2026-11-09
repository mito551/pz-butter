--// Reasonable Crime Prevention Alarm
--// Reasonable Crime Preventation Alarm, Workshop 1967450889 - Original idea
--// aspctt - 18.08.2026
--// A house that already stands open does not sound its alarm when you walk in, and
--// neither does a car that has been left open. A door hanging ajar or a smashed window
--// means anyone could have walked in long before you did, so there is nothing left for
--// the alarm to catch.
--//
--// Rewritten rather than ported. The original is build 41, and it read a plain text
--// settings file shipped inside the mod folder, which a server cannot override, so that
--// is a sandbox page here instead.
--//
--// Parts are walked with getPartCount and getPartByIndex, which are default methods on
--// the VehiclePartOwner interface rather than members of BaseVehicle. That is why they
--// do not show up against the class, and reading it that way once led to them being
--// taken for removed in build 42. They are not: vanilla's own lua calls them, and
--// getParts is the one that cannot be used, since VehicleParts is not on LuaManager's
--// exposed list and every call on it throws.
--//
--// Server side, and it has to be. IsoGameCharacter's alarm check returns early whenever
--// GameClient.client is set, so a multiplayer client never sounds an alarm at all; the
--// server does, out of its own copy of the BuildingDef. LoadGridsquare is raised on the
--// server too, by ServerMap.ServerCell, so this reaches every square there. Nothing here
--// touches the client's copy, and nothing needs to: doAlarm re-reads the flag itself.

--// Tuning
-- Vanilla's own frequency settings, where one means never. Below two there is no alarm
-- anywhere in the world and nothing for this to do.
local ALARMS_EXIST = 2

-- The house rule, matching the sandbox page. Off, doors and windows standing open, or
-- that plus doors that were never locked in the first place.
local HOUSE_OFF = 1
local HOUSE_STANDING_OPEN = 2
local HOUSE_UNLOCKED_TOO = 3

--// Functions
local function GetSandbox(Name, Default)
	local Vars = SandboxVars and SandboxVars.QoLC
	local Value = Vars and Vars[Name]

	if Value ~= nil then return Value end
	return Default
end

local function GetVanillaSandbox(Name)
	local Value = SandboxVars and tonumber(SandboxVars[Name])
	return Value or 0
end

-- A door or window sits between two squares and the building can be on either side of
-- it, so both have to be tried. getBuildingDef is getBuilding():getDef() without the
-- nil dance in between.
local function BuildingFor(Object)
	local Square = Object:getSquare()
	local Def = Square and Square:getBuildingDef()
	if Def then return Def end

	local Opposite = Object:getOppositeSquare()
	if not Opposite then return nil end

	return Opposite:getBuildingDef()
end

-- isExteriorDoor takes a character in the signature but build 42 ignores it and calls
-- isExterior, which matters because getPlayer() is nil on a dedicated server.
local function DoorLeavesHouseOpen(Door, Rule)
	if not Door:isExterior() then return false end
	if Door:IsOpen() then return true end
	if Rule < HOUSE_UNLOCKED_TOO then return false end

	-- Vanilla rolls each door against the lockedHouses percentage and only a third of
	-- the locked ones need a key, so on default settings most exterior doors are already
	-- unlocked. That is why this is its own step rather than part of the rule above: it
	-- disarms far more houses than an open door does.
	return (not Door:isLocked()) or (not Door:isLockedByKey())
end

-- Windows that are merely unlocked are deliberately not counted. Almost every window in
-- the game is unlocked, so including them would disarm the world.
local function WindowLeavesHouseOpen(Window)
	return Window:IsOpen() or Window:isDestroyed()
end

local function DisarmHouse(Square, Rule)
	local Objects = Square:getSpecialObjects()
	if not Objects then return end

	for Index = 0, Objects:size() - 1 do
		local Object = Objects:get(Index)
		local Open = false

		if instanceof(Object, "IsoDoor") then
			Open = DoorLeavesHouseOpen(Object, Rule)
		elseif instanceof(Object, "IsoWindow") then
			Open = WindowLeavesHouseOpen(Object)
		end

		-- The building is looked up only once something is actually open, because a
		-- square with nothing open on it is the common case by a wide margin
		if Open then
			local Def = BuildingFor(Object)

			-- isAlarmed doubles as the record of having been here already: once a
			-- building is disarmed every later square in it falls straight through
			if Def and Def:isAlarmed() then Def:setAlarmed(false) end
		end
	end
end

local function DisarmVehicle(Vehicle)
	if not Vehicle:isAlarmed() then return end

	if (not Vehicle:areAllDoorsLocked()) or (not Vehicle:isTrunkLocked()) then
		Vehicle:setAlarmed(false)
		return
	end

	-- Counted and fetched from the vehicle, not from getParts(). getParts hands back a
	-- VehicleParts, and that class is not on LuaManager's exposed list, so every call on
	-- it throws "attempted index: size of non-table". Reported by two players as a
	-- stutter whenever a zombie walked past a locked car.
	--
	-- getPartCount and getPartByIndex are default methods on the VehiclePartOwner
	-- interface rather than members of BaseVehicle, which is why they do not show up
	-- against the class and why they were wrongly taken for removed. They are what
	-- vanilla's own lua uses, in ISInventoryPage.lua:1583 among others.
	for Index = 0, Vehicle:getPartCount() - 1 do
		local Part = Vehicle:getPartByIndex(Index)
		local Openable = Part and (Part:getWindow() or Part:getDoor())

		-- No item on the part means the window or door is not there at all, smashed out
		-- or taken off, which leaves the car every bit as open as one standing ajar
		if Openable and (Openable:isOpen() or Part:getInventoryItem() == nil) then
			Vehicle:setAlarmed(false)
			return
		end
	end
end

--// Connections
-- Decided once at world load rather than per square. LoadGridsquare fires for every
-- square that streams in, so when there is nothing to disarm the cheapest thing this can
-- do is never register a handler at all.
local function OnInitWorld()
	-- Through tonumber because an enum reaches lua as its index, and a server config
	-- written by hand is not obliged to have got that right
	local Rule = tonumber(GetSandbox("AlarmHouseRule", HOUSE_UNLOCKED_TOO)) or HOUSE_UNLOCKED_TOO
	local Houses = Rule > HOUSE_OFF and GetVanillaSandbox("Alarm") >= ALARMS_EXIST
	local Vehicles = GetSandbox("AlarmVehiclesEnabled", true)
		and GetVanillaSandbox("CarAlarm") >= ALARMS_EXIST

	if not Houses and not Vehicles then return end

	Events.LoadGridsquare.Add(function(Square)
		if not Square then return end

		if Houses then DisarmHouse(Square, Rule) end

		if Vehicles then
			local Vehicle = Square:getVehicleContainer()
			if Vehicle then DisarmVehicle(Vehicle) end
		end
	end)
end

Events.OnInitWorld.Add(OnInitWorld)
