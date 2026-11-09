--// Immersive Overlays
--// Stephanus van Zyl AKA Viceroy - Original
--// aspctt - 08.08.2026

--// Textures
local OverlayHyperthermia = getTexture("media/textures/GUI/qolc_hyperthermia.png")
local OverlayHypothermia = getTexture("media/textures/GUI/qolc_hypothermia.png")
local OverlayDamage = getTexture("media/textures/GUI/qolc_damage.png")
local OverlayTired = getTexture("media/textures/GUI/qolc_tired.png")
local OverlayPain = getTexture("media/textures/GUI/qolc_pain.png")

--// Tuning
-- Rate is how fast an overlay changes blend.
-- Moodle levels run 0-4, so that is what we normalise the blend against.
-- Strength is a percentage supplied by mod options, 100 being the original mod's opacity.
local MOODLE_LEVEL_MAX = 4

-- Every overlay is drawn at sixty percent of what its strength slider asks for. The
-- original mod's opacities were heavy enough to fight the game underneath them, and
-- pulling them back reads far better without touching the sliders: 100 percent still
-- means "as strong as this goes", it is just a gentler ceiling. Kept as one number so
-- the whole set moves together.
local ALPHA_SCALE = 0.6

local RATE_HYPERTHERMIA = 0.01
local RATE_HYPOTHERMIA = 0.003
local RATE_PAIN_FADE = 0.02
local RATE_HEALTH = 0.005
local RATE_TIRED = 0.01

--// Options
-- One shared category for the whole compendium, see qolc_bigger_avatar.lua.
-- This ID is the save key in Zomboid\Lua\ModOptions.ini. Never rename it or players
-- lose their settings.
local OPTIONS_ID = "QoLC"
local Options = {}

--// Variables
-- Current is merely used to blend smoothly.
local BlendHyperthermiaCurrent = 0
local BlendHypothermiaCurrent = 0
local BlendHealthCurrent = 0
local BlendTiredCurrent = 0
local BlendPainCurrent = 0
local BlendPainIdeal = 0
local BlendPainRate = 0.09
-- PZ Butter QoLC-3: frame counter for idle moodle sampling.
local FrameCounter = 0
local ScreenX
local ScreenY

--// Functions
-- Returns the alpha to draw an overlay at, or nil when it should be skipped.
-- One toggle covers every overlay, each still has its own strength.
local function GetAlpha(BlendCurrent, StrengthOption)
	if BlendCurrent <= 0 then return nil end
	if Options.Enabled and not Options.Enabled:getValue() then return nil end

	local Strength = StrengthOption and StrengthOption:getValue() or 100
	if Strength <= 0 then return nil end

	return (BlendCurrent / MOODLE_LEVEL_MAX) * (Strength / 100) * ALPHA_SCALE
end

local function OnPreUIDraw()
	local Player = getPlayer()
	if not Player then return end

	local Moodles = Player:getMoodles()
	if not Moodles then return end

	-- PZ Butter QoLC-3: early-out while all blends are zero. Moodle levels are only
	-- re-sampled every tenth frame in that idle state, which keeps the JNI getters
	-- (getMoodleLevel) off the hot path; the moment a blend is non-zero the body
	-- runs every frame so fade-out finishes before the bar reaches zero.
	local allBlendsZero = BlendHyperthermiaCurrent <= 0 and BlendHypothermiaCurrent <= 0
		and BlendHealthCurrent <= 0 and BlendTiredCurrent <= 0 and BlendPainCurrent <= 0
	if allBlendsZero then
		FrameCounter = FrameCounter + 1
		if (FrameCounter % 10) ~= 0 then return end
	end
	--// Levels
	-- Build 42 renamed these constants to UPPER_SNAKE_CASE
	local HyperthermiaLevel = Moodles:getMoodleLevel(MoodleType.HYPERTHERMIA)
	local HypothermiaLevel = Moodles:getMoodleLevel(MoodleType.HYPOTHERMIA)
	local HealthLevel = Moodles:getMoodleLevel(MoodleType.INJURED)
	local TiredLevel = Moodles:getMoodleLevel(MoodleType.TIRED)
	local PainLevel = Moodles:getMoodleLevel(MoodleType.PAIN)

	--// Pain Overlay
	-- Rate is set from level, so we get a dull throb on low levels and a sharp ache on high levels.
	-- At zero pain a level driven rate would also be zero, which strands the blend at whatever
	-- value it held, so fall back to a fixed rate and let the overlay fade out.
	local BlendPainUpper = PainLevel
	local BlendPainLower = 0

	if PainLevel > 0 then
		BlendPainRate = (PainLevel * 2) / 100
	else
		BlendPainRate = RATE_PAIN_FADE
	end

	if PainLevel >= 0 then
		-- Set our ideal state
		if BlendPainCurrent > BlendPainUpper then
			BlendPainIdeal = BlendPainLower
		elseif BlendPainCurrent < BlendPainLower then
			BlendPainIdeal = BlendPainUpper
		end
	else
		-- Blend out after the player has died
		if BlendPainCurrent >= 0 then
			BlendPainCurrent = BlendPainCurrent - BlendPainRate
		end
		if BlendPainCurrent < 0 then
			BlendPainCurrent = 0
		end
	end

	-- Weirdness clamp
	if BlendPainCurrent < 0 then
		BlendPainCurrent = 0
	end

	-- Regular pain blend
	if BlendPainCurrent >= BlendPainIdeal then
		BlendPainCurrent = BlendPainCurrent - BlendPainRate
	elseif BlendPainCurrent <= BlendPainIdeal then
		BlendPainCurrent = BlendPainCurrent + BlendPainRate
	end

	--// Health Overlay
	if HealthLevel >= 0 then
		if BlendHealthCurrent >= HealthLevel then
			BlendHealthCurrent = BlendHealthCurrent - RATE_HEALTH
		elseif BlendHealthCurrent < HealthLevel then
			BlendHealthCurrent = BlendHealthCurrent + RATE_HEALTH
		end
	else
		if BlendHealthCurrent > 0 then
			BlendHealthCurrent = BlendHealthCurrent - RATE_HEALTH
		end
		if BlendHealthCurrent <= 0 then
			BlendHealthCurrent = 0
		end
	end

	--// Hyperthermia Overlay
	if HyperthermiaLevel >= 0 then
		if BlendHyperthermiaCurrent >= HyperthermiaLevel then
			BlendHyperthermiaCurrent = BlendHyperthermiaCurrent - RATE_HYPERTHERMIA
		elseif BlendHyperthermiaCurrent < HyperthermiaLevel then
			BlendHyperthermiaCurrent = BlendHyperthermiaCurrent + RATE_HYPERTHERMIA
		end
	else
		if BlendHyperthermiaCurrent > 0 then
			BlendHyperthermiaCurrent = BlendHyperthermiaCurrent - RATE_HYPERTHERMIA
		end
		if BlendHyperthermiaCurrent <= 0 then
			BlendHyperthermiaCurrent = 0
		end
	end

	--// Hypothermia Overlay
	if HypothermiaLevel >= 0 then
		if BlendHypothermiaCurrent >= HypothermiaLevel then
			BlendHypothermiaCurrent = BlendHypothermiaCurrent - RATE_HYPOTHERMIA
		elseif BlendHypothermiaCurrent < HypothermiaLevel then
			BlendHypothermiaCurrent = BlendHypothermiaCurrent + RATE_HYPOTHERMIA
		end
	else
		if BlendHypothermiaCurrent > 0 then
			BlendHypothermiaCurrent = BlendHypothermiaCurrent - RATE_HYPOTHERMIA
		end
		if BlendHypothermiaCurrent <= 0 then
			BlendHypothermiaCurrent = 0
		end
	end

	--// Tired Overlay
	if TiredLevel >= 0 then
		if BlendTiredCurrent >= TiredLevel then
			BlendTiredCurrent = BlendTiredCurrent - RATE_TIRED
		elseif BlendTiredCurrent < TiredLevel then
			BlendTiredCurrent = BlendTiredCurrent + RATE_TIRED
		end
	else
		if BlendTiredCurrent > 0 then
			BlendTiredCurrent = BlendTiredCurrent - RATE_TIRED
		end
		if BlendTiredCurrent <= 0 then
			BlendTiredCurrent = 0
		end
	end

	--// Draw Order
	-- Bottom to top. DO NOT FORGET TO ADD NEW OVERLAYS HERE.
	local Alpha = GetAlpha(BlendTiredCurrent, Options.TiredStrength)
	if Alpha then
		UIManager.DrawTexture(OverlayTired, 0, 0, ScreenX, ScreenY, Alpha)
	end

	Alpha = GetAlpha(BlendHyperthermiaCurrent, Options.HyperthermiaStrength)
	if Alpha then
		UIManager.DrawTexture(OverlayHyperthermia, 0, 0, ScreenX, ScreenY, Alpha)
	end

	Alpha = GetAlpha(BlendHypothermiaCurrent, Options.HypothermiaStrength)
	if Alpha then
		UIManager.DrawTexture(OverlayHypothermia, 0, 0, ScreenX, ScreenY, Alpha)
	end

	Alpha = GetAlpha(BlendHealthCurrent, Options.DamageStrength)
	if Alpha then
		UIManager.DrawTexture(OverlayDamage, 0, 0, ScreenX, ScreenY, Alpha)
	end

	Alpha = GetAlpha(BlendPainCurrent, Options.PainStrength)
	if Alpha then
		UIManager.DrawTexture(OverlayPain, 0, 0, ScreenX, ScreenY, Alpha)
	end
end

local function OnGameBoot()
	ScreenX = getCore():getScreenWidth()
	ScreenY = getCore():getScreenHeight()
end

local function OnResolutionChange(_OldX, _OldY, NewX, NewY)
	ScreenX = NewX
	ScreenY = NewY
end

--// Mod Options
-- One shared category for the whole compendium. Each feature contributes an addTitle
-- block into it, so the options screen shows a single QoL Compendium heading instead of
-- one per absorbed mod.
local function CreateModOptions()
	if not PZAPI or not PZAPI.ModOptions then return end

	-- Get or create, so it does not matter which feature's file loads first
	local ModOptions = PZAPI.ModOptions:getOptions(OPTIONS_ID)
	if not ModOptions then
		ModOptions = PZAPI.ModOptions:create(OPTIONS_ID, "UI_options_QoLC")
	end

	ModOptions:addTitle("UI_options_QoLC_Overlays")
	ModOptions:addDescription("UI_options_QoLC_Overlays_Desc")

	-- One toggle for the whole feature, then a strength per overlay
	Options.Enabled = ModOptions:addTickBox("OverlaysEnabled", "UI_options_QoLC_Overlays_Enabled", true, "UI_options_QoLC_Overlays_Enabled_tooltip")

	Options.HyperthermiaStrength = ModOptions:addSlider("HyperthermiaStrength", "UI_options_QoLC_Overlays_HyperthermiaStrength", 0, 100, 5, 100)
	Options.HypothermiaStrength = ModOptions:addSlider("HypothermiaStrength", "UI_options_QoLC_Overlays_HypothermiaStrength", 0, 100, 5, 100)
	Options.DamageStrength = ModOptions:addSlider("DamageStrength", "UI_options_QoLC_Overlays_DamageStrength", 0, 100, 5, 100)
	Options.PainStrength = ModOptions:addSlider("PainStrength", "UI_options_QoLC_Overlays_PainStrength", 0, 100, 5, 100)

	-- Tired defaults lower, the vignette is heavy at full strength
	Options.TiredStrength = ModOptions:addSlider("TiredStrength", "UI_options_QoLC_Overlays_TiredStrength", 0, 100, 5, 50)
end

CreateModOptions()

--// Connections
Events.OnResolutionChange.Add(OnResolutionChange)
Events.OnPreUIDraw.Add(OnPreUIDraw)
Events.OnGameBoot.Add(OnGameBoot)
