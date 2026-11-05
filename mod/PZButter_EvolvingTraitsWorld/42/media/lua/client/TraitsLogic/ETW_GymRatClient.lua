local ETW_CommonFunctions = require("ETW_CommonFunctions")
local ETWCombinedTraitFunctions = require("ETW_CombinedTraitFunctions")
local ETW_Registry = require("ETW_Registry")
local ETW_PerfTick = require("ETW_PerfTick")

local FILENAME = "ETW_GymRatClient.lua"
if
	not ETW_CommonFunctions.gameModeSafeguard(
		FILENAME,
		{ ETW_CommonFunctions.GameMode.SP, ETW_CommonFunctions.GameMode.MP_CLIENT }
	)
then
	return
end

---@type EvolvingTraitsWorldTraitsRegistries
local ETWTraitsRegistry = ETW_Registry.traits
---@type EvolvingTraitsWorldSandboxVars
local SBvars = SandboxVars.EvolvingTraitsWorld
local gameMode = ETW_CommonFunctions.gameMode()
local gymRatStiffnessState = {}
local gymRatSuppressionActive = false
local pendingServerDecay = {}
local DECAY_TICK_ID = "ETW_GymRatClient.stiffnessDecay"
local gymRatStiffnessDecay

local function clearGymRatStiffnessState()
	gymRatStiffnessState = {}
	gymRatSuppressionActive = false
	ETW_PerfTick.unregister(DECAY_TICK_ID)
	pendingServerDecay = {}
end

---@param player IsoPlayer
local function flushPendingServerDecay(player)
	if gameMode ~= ETW_CommonFunctions.GameMode.MP_CLIENT then
		return
	end
	for groupName, amountPerPart in pairs(pendingServerDecay) do
		if amountPerPart > 0 then
			sendClientCommand(
				player,
				"ETW",
				"applyGymRatStiffnessDecay",
				{ group = groupName, amountPerPart = amountPerPart }
			)
		end
	end
	pendingServerDecay = {}
end

---Suppresses new local stiffness after the configured share of queued fatigue has applied.
local function gymRatExerciseFatigue()
	local player = getPlayer()
	if player then
		flushPendingServerDecay(player)
	end
	if not player or not player:hasTrait(ETWTraitsRegistry.GYM_RAT) then
		clearGymRatStiffnessState()
		return
	end
	local reduction = PZMath.clamp(SBvars.GymRatExerciseFatigueReductionPercent or 50, 0, 100) / 100
	if reduction <= 0 then
		clearGymRatStiffnessState()
		return
	end

	gymRatSuppressionActive = ETWCombinedTraitFunctions.processGymRatExerciseFatigue(
		player,
		gymRatStiffnessState,
		reduction
	)
	if gymRatSuppressionActive then
		ETW_PerfTick.register(DECAY_TICK_ID, gymRatStiffnessDecay)
	else
		ETW_PerfTick.unregister(DECAY_TICK_ID)
	end
end

---Mirrors vanilla stiffness decay while an active Fitness queue prevents it.
gymRatStiffnessDecay = function()
	if not gymRatSuppressionActive then
		return
	end
	local player = getPlayer()
	if
		not player
		or not player:hasTrait(ETWTraitsRegistry.GYM_RAT)
		or not player:getFitness():onGoingStiffness()
	then
		return
	end
	local serverDecay = gameMode == ETW_CommonFunctions.GameMode.MP_CLIENT and pendingServerDecay or nil
	ETWCombinedTraitFunctions.decayGymRatSuppressedStiffness(
		player,
		gymRatStiffnessState,
		0.002 * getGameTime():getMultiplier(),
		serverDecay
	)
end

Events.OnPlayerDeath.Remove(clearGymRatStiffnessState)
Events.OnPlayerDeath.Add(clearGymRatStiffnessState)
Events.EveryOneMinute.Remove(gymRatExerciseFatigue)
Events.EveryOneMinute.Add(gymRatExerciseFatigue)
