if not PZButter then return end   -- this fix mod requires Butter Frames (fixmods.py rule)
require "Items/SuburbsDistributions"
require "Items/ProceduralDistributions"
require "Items/Distributions"

-- PZ Butter fix, LTPE-2: depends on PZ Butter. Uses its inverted distribution index: mirror() touches
-- only the items lists that actually contain the source item, across all three roots at once. The
-- inserted entries and weights are identical to the original. Falls back to a hard error (no writes)
-- when PZ Butter's distribution helpers are unavailable.
local LOG_PREFIX = "[LTP Extended][Seed Loot] "

local OAT_SEED_PACKET = "LTPE.OatBagSeed"
local OAT_SEED = "LTPE.OatSeed"
local OAT_SHEAF = "LTPE.OatSheaf"
local OAT_SHEAF_DRIED = "LTPE.OatSheafDried"

local registrationComplete = false

local function log(message)
    print(LOG_PREFIX .. message)
end

local function registerOatLoot()
    if registrationComplete then
        return
    end

    if not (PZButter and PZButter.Distributions and type(PZButter.Distributions.mirror) == "function") then
        print(LOG_PREFIX .. "ERROR: PZ Butter distribution index not available; oat loot was not registered.")
        return
    end

    local D = PZButter.Distributions

    log("Registering oat loot distributions (index variant)...")

    -- Mirror all source->target pairs wherever they occur, keeping shared targets to the maximum count.
    D.mirrorAll({
        { source = "WheatBagSeed", target = OAT_SEED_PACKET },
        { source = "JalapenoBagSeed", target = OAT_SEED_PACKET },
        { source = "WheatSeed", target = OAT_SEED },
        { source = "WheatSheafDried", target = OAT_SHEAF_DRIED },
    })

    -- Explicit additions.
    D.add("all.Outfit_Farmer", OAT_SEED_PACKET, 1)
    D.add("CrateGardening", OAT_SEED_PACKET, 5)
    D.add("CrateGardening", OAT_SEED, 2)
    D.add("CrateFarming", OAT_SHEAF, 8)

    registrationComplete = true

    log("Oat loot distribution registration complete.")
end

if Events and Events.OnPreDistributionMerge then
    Events.OnPreDistributionMerge.Add(registerOatLoot)
else
    log(
        "ERROR: OnPreDistributionMerge event not found; "
        .. "oat loot was not registered."
    )
end