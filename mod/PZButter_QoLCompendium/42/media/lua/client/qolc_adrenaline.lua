--// Adrenaline
--// Adrenaline, Workshop 2807001835 - Original concept and tuning, MIT licensed
--// aspctt - 10.08.2026
--// Panic temporarily takes fatigue off the character, so someone in real trouble sheds
--// the Tired debuff and fights at full strength. The debt is paid back with interest
--// once they calm down, and sleeping settles it in one go.
--//
--// Rewritten against build 42 rather than ported. The original read and wrote fatigue
--// through Stats:getFatigue and Stats:setFatigue, neither of which exists any more.
--// Build 42 replaced every named stat accessor with a keyed pair, Stats:get and
--// Stats:set taking a CharacterStat. It also renamed MoodleType.Panic to PANIC.
--//
--// Client rather than shared. A player's fatigue is simulated on their own machine and
--// synced up from there, so running this on a server as well would apply it twice.
--// OnPlayerUpdate fires for every player a client can see, not only its own, which is
--// what the local player guard is for.
--//
--// Balance lives in sandbox options, never mod options, so the server decides it for
--// everyone. See 42/media/sandbox-options.txt.

--// Tuning
-- Fatigue runs 0 to 1, registered as CharacterStat.FATIGUE. Tired appears around 0.6,
-- so absorbing any earlier would spend the effect on a character with nothing to shed.
local FATIGUE_ABSORB_FLOOR = 0.55
local FATIGUE_MAX = 1

-- Sandbox keeps the two rates as integers so the sliders stay usable. 75 means 0.0075.
local RATE_SCALE = 10000

-- Panic moodle levels run 0 to 4. Each level caps how much fatigue adrenaline may be
-- holding at once, and that cap is what stops sustained panic from replacing sleep.
local ABSORB_CAP_BY_LEVEL = { [0] = 0, [1] = 0.05, [2] = 0.10, [3] = 0.15, [4] = 0.20 }
local PANIC_LEVEL_MAX = 4

-- The original counted sixty player updates between ticks, which is a second only at
-- sixty frames a second. Anyone below that got a slower adrenaline rush than anyone
-- above it, and in multiplayer two players on the same server would not agree. Elapsed
-- time keeps the tuning identical at sixty frames and correct everywhere else.
local TICK_INTERVAL_MS = 1000

-- Used when a save predates this feature and has no sandbox values of its own. These
-- match the defaults declared in 42/media/sandbox-options.txt.
local DEFAULT_CRASH_PENALTY = 1.015
local DEFAULT_BOOST_SPEED = 75
local DEFAULT_CRASH_SPEED = 70

--// Variables
-- Keyed by player number, so split screen players each keep their own clock.
local NextTickAt = {}

--// Functions
-- The sandbox page is the authority, but a character created before this feature
-- existed has nothing stored, so fall back to the declared default.
local function GetSetting(Name, Default)
	local Vars = SandboxVars and SandboxVars.QoLC
	if not Vars then return Default end

	local Value = Vars[Name]
	if Value == nil then return Default end

	return Value
end

-- Clamped rather than indexed directly. A level outside the table would read nil and
-- take the comparisons in OnPlayerUpdate down with it.
local function GetAbsorbCap(PanicLevel)
	if PanicLevel > PANIC_LEVEL_MAX then return ABSORB_CAP_BY_LEVEL[PANIC_LEVEL_MAX] end
	if PanicLevel < 0 then return ABSORB_CAP_BY_LEVEL[0] end

	return ABSORB_CAP_BY_LEVEL[PanicLevel]
end

local function GetStored(ModData)
	local Stored = ModData.QolcAdrenalineFatigue
	if type(Stored) ~= "number" then return 0 end

	return Stored
end

local function ShouldTick(Player)
	local Index = Player:getPlayerNum()
	local Due = NextTickAt[Index]
	local Now = getTimestampMs()

	if Due and Now < Due then return false end

	NextTickAt[Index] = Now + TICK_INTERVAL_MS
	return true
end

-- The crash. Fatigue comes back a little at a time and multiplied, so calming down
-- always costs slightly more than the adrenaline lent out.
local function Release(Stats, ModData, Stored)
	if Stored <= 0 then return end

	local Fatigue = Stats:get(CharacterStat.FATIGUE)
	if Fatigue >= FATIGUE_MAX then return end

	local Penalty = GetSetting("AdrenalineCrashPenalty", DEFAULT_CRASH_PENALTY)
	local Rate = GetSetting("AdrenalineCrashSpeed", DEFAULT_CRASH_SPEED) / RATE_SCALE

	if Stored <= Rate then
		Stats:set(CharacterStat.FATIGUE, Fatigue + (Stored * Penalty))
		ModData.QolcAdrenalineFatigue = 0
	else
		Stats:set(CharacterStat.FATIGUE, Fatigue + (Rate * Penalty))
		ModData.QolcAdrenalineFatigue = Stored - Rate
	end
end

-- The rush. Fatigue moves off the character and into storage, scaled by how panicked
-- they are, until this panic level's cap is reached.
local function Absorb(Stats, ModData, Stored, PanicLevel, Cap)
	if Stored >= Cap then return end

	local Fatigue = Stats:get(CharacterStat.FATIGUE)
	if Fatigue < FATIGUE_ABSORB_FLOOR then return end

	local Amount = (GetSetting("AdrenalineBoostSpeed", DEFAULT_BOOST_SPEED) / RATE_SCALE) * PanicLevel
	if Amount > Cap - Stored then Amount = Cap - Stored end
	if Amount > Fatigue then Amount = Fatigue end
	if Amount <= 0 then return end

	Stats:set(CharacterStat.FATIGUE, Fatigue - Amount)
	ModData.QolcAdrenalineFatigue = Stored + Amount
end

-- Sleeping settles the whole debt at once. The character is about to recover properly,
-- so there is nothing to gain from dripping it back.
local function ReleaseAll(Stats, ModData, Stored)
	if Stored <= 0 then return end

	local Penalty = GetSetting("AdrenalineCrashPenalty", DEFAULT_CRASH_PENALTY)
	local Fatigue = Stats:get(CharacterStat.FATIGUE)

	Stats:set(CharacterStat.FATIGUE, Fatigue + (Stored * Penalty))
	ModData.QolcAdrenalineFatigue = 0
end

function QolcAdrenalineUpdate(Player)
	if not Player or not Player:isLocalPlayer() then return end
	if not GetSetting("AdrenalineEnabled", true) then return end
	if not ShouldTick(Player) then return end

	local ModData = Player:getModData()
	local Moodles = Player:getMoodles()
	local Stats = Player:getStats()
	if not ModData or not Moodles or not Stats then return end

	local PanicLevel = Moodles:getMoodleLevel(MoodleType.PANIC)
	local Cap = GetAbsorbCap(PanicLevel)
	local Stored = GetStored(ModData)

	if Player:isAsleep() then
		ReleaseAll(Stats, ModData, Stored)
	elseif Stored > Cap then
		Release(Stats, ModData, Stored)
	else
		Absorb(Stats, ModData, Stored, PanicLevel, Cap)
	end
end

--// Connections
-- merged registration moved to qolc_merged_player_update.lua
