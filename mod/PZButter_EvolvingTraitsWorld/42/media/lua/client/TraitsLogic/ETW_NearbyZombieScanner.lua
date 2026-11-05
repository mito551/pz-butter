local ETW_CommonFunctions = require("ETW_CommonFunctions")
local ETWCombinedTraitChecks = require("ETW_CombinedTraitFunctions")
local ETW_PerfTick = require("ETW_PerfTick")
local ETW_Registry = require("ETW_Registry")

local FILENAME = "ETW_NearbyZombieScanner.lua"
if
	not ETW_CommonFunctions.gameModeSafeguard(
		FILENAME,
		{ ETW_CommonFunctions.GameMode.SP, ETW_CommonFunctions.GameMode.MP_CLIENT }
	)
then
	return
end

local ETW_NearbyZombieScanner = {}

---@class ETWNearbyZombieConsumerDefinition
---@field radius number
---@field isEnabled (fun(player: IsoPlayer): boolean)|nil
---@field beforeScan fun(player: IsoPlayer)|nil
---@field onZombie fun(player: IsoPlayer, zombie: IsoZombie, distanceSquared: number)|nil
---@field afterScan fun(player: IsoPlayer)|nil

---@class ETWNearbyZombieConsumer : ETWNearbyZombieConsumerDefinition
---@field radiusSquared number

---@type table<string, ETWNearbyZombieConsumer>
local consumers = {}
---@type table<integer, ETWNearbyZombieConsumer|nil>
local activeConsumers = {}
local activeConsumerCount = 0
---@type IsoPlayer|nil
local scanPlayer

---Dispatches one nearby zombie to every active consumer whose radius contains it.
---@param zombie IsoZombie
---@param distanceSquared number
local function dispatchNearbyZombie(zombie, distanceSquared)
	for i = 1, activeConsumerCount do
		local consumer = activeConsumers[i]
		---@cast consumer ETWNearbyZombieConsumer
		if distanceSquared <= consumer.radiusSquared and consumer.onZombie then
			local player = scanPlayer
			---@cast player IsoPlayer
			consumer.onZombie(player, zombie, distanceSquared)
		end
	end
end

---Runs one bounded traversal using the largest radius requested by an active consumer.
local function scanNearbyZombies()
	local player = getPlayer()
	if not player or player:isDead() then
		return
	end

	local maximumRadius = 0.0
	local count = 0
	for _, consumer in pairs(consumers) do
		if not consumer.isEnabled or consumer.isEnabled(player) then
			count = count + 1
			activeConsumers[count] = consumer
			if consumer.radius > maximumRadius then
				maximumRadius = consumer.radius
			end
		end
	end
	for i = count + 1, activeConsumerCount do
		activeConsumers[i] = nil
	end
	activeConsumerCount = count
	if count == 0 then
		return
	end

	for i = 1, count do
		local consumer = activeConsumers[i]
		---@cast consumer ETWNearbyZombieConsumer
		local beforeScan = consumer.beforeScan
		if beforeScan then
			beforeScan(player)
		end
	end

	scanPlayer = player
	ETWCombinedTraitChecks.forEachNearbyLivingZombieCachedThisFrame(
		player,
		maximumRadius,
		dispatchNearbyZombie
	)
	scanPlayer = nil

	for i = 1, count do
		local consumer = activeConsumers[i]
		---@cast consumer ETWNearbyZombieConsumer
		local afterScan = consumer.afterScan
		if afterScan then
			afterScan(player)
		end
	end
end

local SCAN_TICK_ID = "ETW_NearbyZombieScanner"
-- ponytail: gate traits keyed by the upstream consumer ids; an unknown id turns gating off
-- (scan runs every tick, as upstream), add its trait here when ETW adds a consumer.
local CONSUMER_GATE_TRAITS = {
	Indefatigable = ETW_Registry.traits.INDEFATIGABLE,
	Immunocompromised = ETW_Registry.traits.IMMUNOCOMPROMISED,
}

---(Re)registers the scan with the tick dispatcher, gated on the traits its consumers need.
local function registerScanTick()
	local traits = {}
	for id in pairs(consumers) do
		local trait = CONSUMER_GATE_TRAITS[id]
		if not trait then
			ETW_PerfTick.register(SCAN_TICK_ID, scanNearbyZombies, "always")
			return
		end
		traits[#traits + 1] = trait
	end
	ETW_PerfTick.register(SCAN_TICK_ID, scanNearbyZombies, "local", traits)
end

---Registers or replaces one consumer of the shared nearby-zombie traversal.
---@param id string
---@param definition ETWNearbyZombieConsumerDefinition
function ETW_NearbyZombieScanner.register(id, definition)
	local radius = math.max(0, definition.radius or 0)
	definition.radius = radius
	---@cast definition ETWNearbyZombieConsumer
	definition.radiusSquared = radius * radius
	consumers[id] = definition
	registerScanTick()
end

---Unregisters one consumer and removes the tick hook when no consumers remain.
---@param id string
function ETW_NearbyZombieScanner.unregister(id)
	consumers[id] = nil
	for _ in pairs(consumers) do
		registerScanTick()
		return
	end
	ETW_PerfTick.unregister(SCAN_TICK_ID)
end

return ETW_NearbyZombieScanner
