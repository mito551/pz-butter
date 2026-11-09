require "Entity/TimedActions/ISHandcraftAction"

-- AZ_Perf: server-side recovery for ISHandcraftAction:new calls that arrive with no
-- craftRecipe. The recipe is inferred from the input items (rare fallback path).

local AZB42_BAG_UPGRADE_RECIPES = {
    ["AuthenticZClothing.Bag_ALICEpack_Army_Tier_1"] = "Upgrade Camo Alicepack (Tier 2)",
    ["AuthenticZClothing.Bag_ALICEpack_Army_Tier_2"] = "Upgrade Camo Alicepack (Tier 3)",
    ["AuthenticZClothing.Bag_ALICEpack_DesertCamo"] = "Upgrade Desert Camo Alicepack (Tier 1)",
    ["AuthenticZClothing.Bag_ALICEpack_DesertCamo_Tier_1"] = "Upgrade Desert Camo Alicepack (Tier 2)",
    ["AuthenticZClothing.Bag_ALICEpack_DesertCamo_Tier_2"] = "Upgrade Desert Camo Alicepack (Tier 3)",
    ["AuthenticZClothing.Bag_ALICEpack_Festive"] = "Upgrade Festive Alicepack (Tier 1)",
    ["AuthenticZClothing.Bag_ALICEpack_Festive_Tier_1"] = "Upgrade Festive Alicepack (Tier 2)",
    ["AuthenticZClothing.Bag_ALICEpack_Festive_Tier_2"] = "Upgrade Festive Alicepack (Tier 3)",
    ["AuthenticZClothing.Bag_ALICEpack_Tier_1"] = "Upgrade Alicepack (Tier 2)",
    ["AuthenticZClothing.Bag_ALICEpack_Tier_2"] = "Upgrade Alicepack (Tier 3)",
    ["AuthenticZClothing.Bag_ALICEpack_UrbanCamo"] = "Upgrade Urban Camo Alicepack (Tier 1)",
    ["AuthenticZClothing.Bag_ALICEpack_UrbanCamo_Tier_1"] = "Upgrade Urban Camo Alicepack (Tier 2)",
    ["AuthenticZClothing.Bag_ALICEpack_UrbanCamo_Tier_2"] = "Upgrade Urban Camo Alicepack (Tier 3)",
    ["AuthenticZClothing.Bag_ARVN_Rucksack_Tier_1"] = "Upgrade Rucksack (Tier 2)",
    ["AuthenticZClothing.Bag_ARVN_Rucksack_Tier_2"] = "Upgrade Rucksack (Tier 3)",
    ["AuthenticZClothing.Bag_B4BEvangelo"] = "Upgrade Evangelos Backpack (Tier 1)",
    ["AuthenticZClothing.Bag_B4BEvangelo_Tier_1"] = "Upgrade Evangelos Daypack (Tier 2)",
    ["AuthenticZClothing.Bag_B4BEvangelo_Tier_2"] = "Upgrade Evangelos Daypack (Tier 3)",
    ["AuthenticZClothing.Bag_B4BHoffman"] = "Upgrade Hoffmans Backpack (Tier 1)",
    ["AuthenticZClothing.Bag_B4BHoffman_Tier_1"] = "Upgrade Hoffmans Daypack (Tier 2)",
    ["AuthenticZClothing.Bag_B4BHoffman_Tier_2"] = "Upgrade Hoffmans Daypack (Tier 3)",
    ["AuthenticZClothing.Bag_B4BHolly"] = "Upgrade Hollys Backpack (Tier 1)",
    ["AuthenticZClothing.Bag_B4BHolly_Tier_1"] = "Upgrade Hollys Daypack (Tier 2)",
    ["AuthenticZClothing.Bag_B4BHolly_Tier_2"] = "Upgrade Hollys Daypack (Tier 3)",
    ["AuthenticZClothing.Bag_B4BMom"] = "Upgrade Casual Daypack (Tier 1)",
    ["AuthenticZClothing.Bag_B4BMom_Tier_1"] = "Upgrade Casual Daypack (Tier 2)",
    ["AuthenticZClothing.Bag_B4BMom_Tier_2"] = "Upgrade Casual Daypack (Tier 3)",
    ["AuthenticZClothing.Bag_B4BWalker"] = "Upgrade Walkers Backpack (Tier 1)",
    ["AuthenticZClothing.Bag_B4BWalker_Tier_1"] = "Upgrade Walkers Backpack (Tier 2)",
    ["AuthenticZClothing.Bag_B4BWalker_Tier_2"] = "Upgrade Walkers Backpack (Tier 3)",
    ["AuthenticZClothing.Bag_BigHikingBag_Tier_1"] = "Upgrade Big Hiking Bag (Tier 2)",
    ["AuthenticZClothing.Bag_BigHikingBag_Tier_2"] = "Upgrade Big Hiking Bag (Tier 3)",
    ["AuthenticZClothing.Bag_DuffelBagGrey_Tier_1"] = "Upgrade Grey Duffelbag Bag (Tier 2)",
    ["AuthenticZClothing.Bag_DuffelBagGrey_Tier_2"] = "Upgrade Grey Duffelbag Bag (Tier 3)",
    ["AuthenticZClothing.Bag_DuffelBagTINT_Tier_1"] = "Upgrade Colored Duffelbag Bag (Tier 2)",
    ["AuthenticZClothing.Bag_DuffelBagTINT_Tier_2"] = "Upgrade Colored Duffelbag Bag (Tier 3)",
    ["AuthenticZClothing.Bag_DuffelBag_Festive"] = "Upgrade Festive Duffelbag Bag (Tier 1)",
    ["AuthenticZClothing.Bag_DuffelBag_Festive_Tier_1"] = "Upgrade Festive Duffelbag Bag (Tier 2)",
    ["AuthenticZClothing.Bag_DuffelBag_Festive_Tier_2"] = "Upgrade Festive Duffelbag Bag (Tier 3)",
    ["AuthenticZClothing.Bag_DuffelBag_Tier_1"] = "Upgrade Duffelbag Bag (Tier 2)",
    ["AuthenticZClothing.Bag_DuffelBag_Tier_2"] = "Upgrade Duffelbag Bag (Tier 3)",
    ["AuthenticZClothing.Bag_MedicalBag_Tier_1"] = "Upgrade Medical Duffelbag Bag (Tier 2)",
    ["AuthenticZClothing.Bag_MedicalBag_Tier_2"] = "Upgrade Medical Duffelbag Bag (Tier 3)",
    ["AuthenticZClothing.Bag_Military_Tier_1"] = "Upgrade Military Duffelbag Bag (Tier 2)",
    ["AuthenticZClothing.Bag_Military_Tier_2"] = "Upgrade Military Duffelbag Bag (Tier 3)",
    ["AuthenticZClothing.Bag_NormalHikingBag_Tier_1"] = "Upgrade Hiking Bag (Tier 2)",
    ["AuthenticZClothing.Bag_NormalHikingBag_Tier_2"] = "Upgrade Hiking Bag (Tier 3)",
    ["AuthenticZClothing.Bag_Packsport_Plain"] = "Upgrade Packsport Backpack (Tier 1)",
    ["AuthenticZClothing.Bag_Packsport_Plain_Tier_1"] = "Upgrade Packsport Backpack (Tier 2)",
    ["AuthenticZClothing.Bag_Packsport_Plain_Tier_2"] = "Upgrade Packsport Backpack (Tier 3)",
    ["AuthenticZClothing.Bag_Packsport_Rim"] = "Upgrade Rimmed Packsport Backpack (Tier 1)",
    ["AuthenticZClothing.Bag_Packsport_Rim_Tier_1"] = "Upgrade Rimmed Packsport Backpack (Tier 2)",
    ["AuthenticZClothing.Bag_Packsport_Rim_Tier_2"] = "Upgrade Rimmed Packsport Backpack (Tier 3)",
    ["AuthenticZClothing.Bag_RoadsideDuffel"] = "Upgrade Roadside Duffelbag Bag (Tier 1)",
    ["AuthenticZClothing.Bag_RoadsideDuffel_Tier_1"] = "Upgrade Roadside Duffelbag Bag (Tier 2)",
    ["AuthenticZClothing.Bag_RoadsideDuffel_Tier_2"] = "Upgrade Roadside Duffelbag Bag (Tier 3)",
    ["AuthenticZClothing.Bag_SchoolBagCEDA"] = "Upgrade Green CEDA Hazmat Pack (Tier 1)",
    ["AuthenticZClothing.Bag_SchoolBagCEDABlack"] = "Upgrade Black CEDA Hazmat Pack (Tier 1)",
    ["AuthenticZClothing.Bag_SchoolBagCEDABlack_Tier_1"] = "Upgrade Black CEDA Hazmat Pack (Tier 2)",
    ["AuthenticZClothing.Bag_SchoolBagCEDABlack_Tier_2"] = "Upgrade Black CEDA Hazmat Pack (Tier 3)",
    ["AuthenticZClothing.Bag_SchoolBagCEDABlue"] = "Upgrade Blue CEDA Hazmat Pack (Tier 1)",
    ["AuthenticZClothing.Bag_SchoolBagCEDABlue_Tier_1"] = "Upgrade Blue CEDA Hazmat Pack (Tier 2)",
    ["AuthenticZClothing.Bag_SchoolBagCEDABlue_Tier_2"] = "Upgrade Blue CEDA Hazmat Pack (Tier 3)",
    ["AuthenticZClothing.Bag_SchoolBagCEDARed"] = "Upgrade Red CEDA Hazmat Pack (Tier 1)",
    ["AuthenticZClothing.Bag_SchoolBagCEDARed_Tier_1"] = "Upgrade Red CEDA Hazmat Pack (Tier 2)",
    ["AuthenticZClothing.Bag_SchoolBagCEDARed_Tier_2"] = "Upgrade Red CEDA Hazmat Pack (Tier 3)",
    ["AuthenticZClothing.Bag_SchoolBagCEDA_Tier_1"] = "Upgrade Green CEDA Hazmat Pack (Tier 2)",
    ["AuthenticZClothing.Bag_SchoolBagCEDA_Tier_2"] = "Upgrade Green CEDA Hazmat Pack (Tier 3)",
    ["AuthenticZClothing.Bag_SchoolBagNBH"] = "Upgrade NBH Hazmat Pack (Tier 1)",
    ["AuthenticZClothing.Bag_SchoolBagNBH_Tier_1"] = "Upgrade NBH Hazmat Pack (Tier 2)",
    ["AuthenticZClothing.Bag_SchoolBagNBH_Tier_2"] = "Upgrade NBH Hazmat Pack (Tier 3)",
    ["AuthenticZClothing.Bag_Schoolbag_Kids_Tier_1"] = "Upgrade Kids School Bag (Tier 2)",
    ["AuthenticZClothing.Bag_Schoolbag_Kids_Tier_2"] = "Upgrade Kids School Bag (Tier 3)",
    ["AuthenticZClothing.Bag_Schoolbag_Tier_1"] = "Upgrade School Bag (Tier 2)",
    ["AuthenticZClothing.Bag_Schoolbag_Tier_2"] = "Upgrade School Bag (Tier 3)",
    ["AuthenticZClothing.Bag_SpiffoBackpackAZ"] = "Upgrade Spiffo Backpack (Tier 1)",
    ["AuthenticZClothing.Bag_SpiffoBackpackAZ_Tier_1"] = "Upgrade Spiffo Backpack (Tier 2)",
    ["AuthenticZClothing.Bag_SpiffoBackpackAZ_Tier_2"] = "Upgrade Spiffo Backpack (Tier 3)",
    ["Bag_BigHikingBag"] = "Upgrade Big Hiking Bag (Tier 1)",
    ["Bag_BigHikingBag_Travel"] = "Upgrade Big Hiking Bag (Tier 1)",
    ["Base.Bag_ALICEpack"] = "Upgrade Alicepack (Tier 1)",
    ["Base.Bag_ALICEpack_Army"] = "Upgrade Camo Alicepack (Tier 1)",
    ["Base.Bag_ARVN_Rucksack"] = "Upgrade Rucksack (Tier 1)",
    ["Base.Bag_DuffelBag"] = "Upgrade Duffelbag Bag (Tier 1)",
    ["Base.Bag_DuffelBagTINT"] = "Upgrade Colored Duffelbag Bag (Tier 1)",
    ["Base.Bag_FoodCanned"] = "Upgrade Grey Duffelbag Bag (Tier 1)",
    ["Base.Bag_FoodSnacks"] = "Upgrade Grey Duffelbag Bag (Tier 1)",
    ["Base.Bag_HikingBag_Travel"] = "Upgrade Hiking Bag (Tier 1)",
    ["Base.Bag_InmateEscapedBag"] = "Upgrade Colored Duffelbag Bag (Tier 1)",
    ["Base.Bag_MedicalBag"] = "Upgrade Medical Duffelbag Bag (Tier 1)",
    ["Base.Bag_Military"] = "Upgrade Military Duffelbag Bag (Tier 1)",
    ["Base.Bag_MoneyBag"] = "Upgrade Grey Duffelbag Bag (Tier 1)",
    ["Base.Bag_NormalHikingBag"] = "Upgrade Hiking Bag (Tier 1)",
    ["Base.Bag_Schoolbag"] = "Upgrade School Bag (Tier 1)",
    ["Base.Bag_Schoolbag_Kids"] = "Upgrade Kids School Bag (Tier 1)",
    ["Base.Bag_Schoolbag_Travel"] = "Upgrade School Bag (Tier 1)",
    ["Base.Bag_ShotgunBag"] = "Upgrade Grey Duffelbag Bag (Tier 1)",
    ["Base.Bag_ShotgunDblBag"] = "Upgrade Grey Duffelbag Bag (Tier 1)",
    ["Base.Bag_ShotgunDblSawnoffBag"] = "Upgrade Grey Duffelbag Bag (Tier 1)",
    ["Base.Bag_ShotgunSawnoffBag"] = "Upgrade Grey Duffelbag Bag (Tier 1)",
    ["Base.Bag_SurvivorBag"] = "Upgrade Alicepack (Tier 1)",
    ["Base.Bag_ToolBag"] = "Upgrade Grey Duffelbag Bag (Tier 1)",
    ["Base.Bag_WeaponBag"] = "Upgrade Grey Duffelbag Bag (Tier 1)",
    ["Base.Bag_WorkerBag"] = "Upgrade Colored Duffelbag Bag (Tier 1)",
}

local AZB42_MAKE_ITEMS = {
    ["Base.Hat_GasMask"] = true,
    ["Base.Hat_GasMask_nofilter"] = true,
    ["Base.PipeBomb"] = true,
    ["Base.PipeBombSensorV1"] = true,
    ["Base.PipeBombSensorV2"] = true,
    ["Base.PipeBombSensorV3"] = true,
    ["Base.PipeBombRemote"] = true,
    ["Base.Aerosolbomb"] = true,
    ["Base.AerosolbombTriggered"] = true,
    ["Base.AerosolbombSensorV1"] = true,
    ["Base.AerosolbombSensorV2"] = true,
    ["Base.AerosolbombSensorV3"] = true,
    ["Base.AerosolbombRemote"] = true,
    ["Base.FlameTrap"] = true,
    ["Base.FlameTrapTriggered"] = true,
    ["Base.FlameTrapSensorV1"] = true,
    ["Base.FlameTrapSensorV2"] = true,
    ["Base.FlameTrapSensorV3"] = true,
    ["Base.FlameTrapRemote"] = true,
    ["Base.SmokeBomb"] = true,
    ["Base.SmokeBombRemote"] = true,
    ["Base.SmokeBombSensorV1"] = true,
    ["Base.SmokeBombSensorV2"] = true,
    ["Base.SmokeBombSensorV3"] = true,
    ["Base.SmokeBombTriggered"] = true,
    ["Base.Molotov"] = true,
}

local AZB42_REVERT_ITEMS = {
    ["AuthenticZClothing.Hat_GasMask"] = true,
    ["AuthenticZClothing.Hat_GasMask_nofilter"] = true,
    ["AuthenticZClothing.PipeBomb"] = true,
    ["AuthenticZClothing.PipeBombSensorV1"] = true,
    ["AuthenticZClothing.PipeBombSensorV2"] = true,
    ["AuthenticZClothing.PipeBombSensorV3"] = true,
    ["AuthenticZClothing.PipeBombRemote"] = true,
    ["AuthenticZClothing.Aerosolbomb"] = true,
    ["AuthenticZClothing.AerosolbombTriggered"] = true,
    ["AuthenticZClothing.AerosolbombSensorV1"] = true,
    ["AuthenticZClothing.AerosolbombSensorV2"] = true,
    ["AuthenticZClothing.AerosolbombSensorV3"] = true,
    ["AuthenticZClothing.AerosolbombRemote"] = true,
    ["AuthenticZClothing.FlameTrap"] = true,
    ["AuthenticZClothing.FlameTrapTriggered"] = true,
    ["AuthenticZClothing.FlameTrapSensorV1"] = true,
    ["AuthenticZClothing.FlameTrapSensorV2"] = true,
    ["AuthenticZClothing.FlameTrapSensorV3"] = true,
    ["AuthenticZClothing.FlameTrapRemote"] = true,
    ["AuthenticZClothing.SmokeBomb"] = true,
    ["AuthenticZClothing.SmokeBombRemote"] = true,
    ["AuthenticZClothing.SmokeBombSensorV1"] = true,
    ["AuthenticZClothing.SmokeBombSensorV2"] = true,
    ["AuthenticZClothing.SmokeBombSensorV3"] = true,
    ["AuthenticZClothing.SmokeBombTriggered"] = true,
    ["AuthenticZClothing.Molotov"] = true,
}

local AZB42_DIRECT_RECIPES_BY_ITEM = {
    ["Base.Dice"] = "Make Backpack Attachable Item (Dice)",
    ["Base.Rubberducky"] = "Make Backpack Attachable Item (Plushie)",
    ["Base.ToyBear"] = "Make Backpack Attachable Item (Plushie)",
    ["Base.Doll"] = "Make Backpack Attachable Item (Plushie)",
    ["Base.Spiffo"] = "Make Backpack Attachable Item (Plushie)",
    ["Base.FurbertSquirrel"] = "Make Backpack Attachable Item (Plushie)",
    ["Base.FluffyfootBunny"] = "Make Backpack Attachable Item (Plushie)",
    ["Base.MoleyMole"] = "Make Backpack Attachable Item (Plushie)",
    ["Base.PancakeHedgehog"] = "Make Backpack Attachable Item (Plushie)",
    ["Base.FreddyFox"] = "Make Backpack Attachable Item (Plushie)",
    ["Base.JacquesBeaver"] = "Make Backpack Attachable Item (Plushie)",
    ["Base.BorisBadger"] = "Make Backpack Attachable Item (Plushie)",
    ["Base.Pop"] = "Make Backpack Attachable Item (Pop Soda)",
    ["Base.Pop2"] = "Make Backpack Attachable Item (Pop Soda)",
    ["Base.Pop3"] = "Make Backpack Attachable Item (Pop Soda)",
    ["Base.SodaCan"] = "Make Backpack Attachable Item (Pop Soda)",
    ["Base.Lighter"] = "Make Backpack Attachable Item (Lighter)",
    ["Base.Plasticbag"] = "Make Secondary Bag",
    ["Base.GroceryBag1"] = "Make Secondary Bag",
    ["Base.GroceryBag2"] = "Make Secondary Bag",
    ["Base.GroceryBag3"] = "Make Secondary Bag",
    ["Base.GroceryBag4"] = "Make Secondary Bag",
    ["Base.GroceryBag5"] = "Make Secondary Bag",
    ["Base.Plasticbag_Clothing"] = "Make Secondary Bag",
    ["Base.Garbagebag"] = "Make Secondary Bag",
    ["Base.Bag_TrashBag"] = "Make Secondary Bag",
    ["Base.Handbag"] = "Make Secondary Bag",
    ["Base.Purse"] = "Make Secondary Bag",
    ["Base.Tote"] = "Make Secondary Bag",
    ["Base.Tote_Bags"] = "Make Secondary Bag",
    ["Base.Tote_Clothing"] = "Make Secondary Bag",
    ["Base.Bag_Dancer"] = "Make Secondary Bag",
    ["Base.EmptySandbag"] = "Make Secondary Bag",
    ["Base.Bag_DeadRats"] = "Make Secondary Bag",
    ["Base.Bag_DeadMice"] = "Make Secondary Bag",
    ["Base.Bag_DeadRoaches"] = "Make Secondary Bag",
    ["Base.Bag_TreasureBag"] = "Make Secondary Bag",
    ["Base.Toolbox"] = "Make Secondary Bag",
    ["Base.Bag_JanitorToolbox"] = "Make Secondary Bag",
    ["Base.Toolbox_Farming"] = "Make Secondary Bag",
    ["Base.Toolbox_Fishing"] = "Make Secondary Bag",
    ["Base.Toolbox_Gardening"] = "Make Secondary Bag",
    ["Base.Toolbox_Mechanic"] = "Make Secondary Bag",
    ["Base.FirstAidKit"] = "Make Secondary Bag",
    ["Base.FirstAidKit_New"] = "Make Secondary Bag",
    ["Base.FirstAidKit_NewPro"] = "Make Secondary Bag",
    ["Base.Lunchbox"] = "Make Secondary Bag",
    ["Base.Lunchbox2"] = "Make Secondary Bag",
    ["Base.PistolCase1"] = "Make Secondary Bag",
    ["Base.PistolCase2"] = "Make Secondary Bag",
    ["Base.PistolCase3"] = "Make Secondary Bag",
    ["Base.RevolverCase1"] = "Make Secondary Bag",
    ["Base.RevolverCase2"] = "Make Secondary Bag",
    ["Base.RevolverCase3"] = "Make Secondary Bag",
    ["Base.SeedBag"] = "Make Secondary Bag",
    ["Base.SeedBag_Farming"] = "Make Secondary Bag",
}

-- Each checker takes an item and returns recipeName, fullType (or nil).
local function AZB42_CheckAttachable(item)
    local fullType = item.getFullType and item:getFullType()
    if AZB42_MAKE_ITEMS[fullType] then
        return "Make Attachable Item", fullType
    end
    if AZB42_REVERT_ITEMS[fullType] then
        return "Revert Attachable Item", fullType
    end
end

local function AZB42_CheckBagUpgrade(item)
    local fullType = item.getFullType and item:getFullType()
    if not fullType then return end

    -- getFullType() is always "Module.Type"; some entries are keyed by the bare type.
    local recipeName = AZB42_BAG_UPGRADE_RECIPES[fullType]
        or AZB42_BAG_UPGRADE_RECIPES[fullType:match("^[^.]+%.(.+)$")]
    if recipeName then
        return recipeName, fullType
    end
end

local function AZB42_CheckDirect(item)
    local fullType = item.getFullType and item:getFullType()
    local recipeName = AZB42_DIRECT_RECIPES_BY_ITEM[fullType]
    if recipeName then
        return recipeName, fullType
    end
end

local function AZB42_CheckCarabiner(item)
    if item.getFullType and item:getFullType() == "Base.Paperclip" then
        return "Craft Carabiner", "Base.Paperclip"
    end
end

-- Runs check over a Java list or a Lua table of items.
local function AZB42_ScanItemList(itemList, check)
    if not itemList then return end

    if itemList.size and itemList.get then
        for i = 0, itemList:size() - 1 do
            local item = itemList:get(i)
            local recipeName, fullType = item and check(item)
            if recipeName then return recipeName, fullType end
        end
    else
        for _, item in pairs(itemList) do
            local recipeName, fullType = item and check(item)
            if recipeName then return recipeName, fullType end
        end
    end
end

-- Looks in recipeItem (optional), then items, then every list in manualInputs.
local function AZB42_Infer(check, manualInputs, items, recipeItem)
    local recipeName, fullType
    if recipeItem then
        recipeName, fullType = check(recipeItem)
        if recipeName then return recipeName, fullType end
    end

    recipeName, fullType = AZB42_ScanItemList(items, check)
    if recipeName then return recipeName, fullType end

    if manualInputs then
        for _, itemList in pairs(manualInputs) do
            recipeName, fullType = AZB42_ScanItemList(itemList, check)
            if recipeName then return recipeName, fullType end
        end
    end
end

local function AZB42_GetCraftRecipe(recipeName)
    local scriptManager = getScriptManager()
    if not scriptManager or not recipeName then
        return nil
    end

    return scriptManager:getCraftRecipe(recipeName)
        or scriptManager:getCraftRecipe("AuthenticZRecipes." .. recipeName)
        or scriptManager:getCraftRecipe("AuthenticZClothing." .. recipeName)
        or scriptManager:getCraftRecipe("Base." .. recipeName)
end

if ISHandcraftAction and ISHandcraftAction.new and not ISHandcraftAction.AZB42RecipeRecoveryWrapped then
    ISHandcraftAction.AZB42RecipeRecoveryWrapped = true

    local vanillaNew = ISHandcraftAction.new

    -- Only the arguments we read are named; trailing ones pass through untouched.
    function ISHandcraftAction:new(character, craftRecipe, containers, isoObject, craftBench, manualInputs, items, recipeItem, ...)
        if not craftRecipe and isServer() then
            local recipeName = AZB42_Infer(AZB42_CheckDirect, manualInputs, items, recipeItem)
                or AZB42_Infer(AZB42_CheckCarabiner, manualInputs, items, recipeItem)
                or AZB42_Infer(AZB42_CheckAttachable, manualInputs, items)
                or AZB42_Infer(AZB42_CheckBagUpgrade, manualInputs, items)

            if recipeName then
                craftRecipe = AZB42_GetCraftRecipe(recipeName)
            end
        end

        return vanillaNew(self, character, craftRecipe, containers, isoObject, craftBench, manualInputs, items, recipeItem, ...)
    end
end
