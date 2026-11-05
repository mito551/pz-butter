-- Viewpoint: Living Props - one-time diagnostic (does nothing unless Zomboid\Lua\ViewpointLivingProps_dump.txt starts with the word "run").
-- It asks the game which flat "contents" pictures (overlays) it uses for each piece of furniture and writes the answer to
-- Zomboid\Lua\ViewpointLivingProps_overlays.txt, so the 3D contents can be matched to the very same pictures. It changes nothing in the game:
-- the test furniture it makes is never put in the world. When it ends it writes "done" in the flag file so it does not run again.

local FLAG = "ViewpointLivingProps_dump.txt"
local OUT = "ViewpointLivingProps_overlays.txt"

local PREFIX = {
    ["Fire_01"]=29,
    ["Fire_02"]=29,
    ["Fire_03"]=29,
    ["Giblet_00"]=2,
    ["Giblet_01"]=2,
    ["RainSplash_00"]=3,
    ["Rain_00"]=0,
    ["Smoke_01"]=29,
    ["Smoke_02"]=29,
    ["Smoke_03"]=29,
    ["TileThuztorSymbolDisease"]=0,
    ["TileThuztorSymbolPIXEL"]=0,
    ["TileThuztorSymbolPests"]=0,
    ["TileThuztorSymbolWaterDrop"]=0,
    ["advertising_01"]=117,
    ["advertising_01_on"]=23,
    ["advertising_02"]=1,
    ["advertising_02_on"]=1,
    ["alokin_test_sheet"]=171,
    ["animaltracks_large_01"]=63,
    ["appliances_01"]=75,
    ["appliances_com_01"]=167,
    ["appliances_com_01_on"]=157,
    ["appliances_cooking_01"]=79,
    ["appliances_cooking_01_on"]=43,
    ["appliances_cooking_ON_01"]=43,
    ["appliances_laundry_01"]=31,
    ["appliances_laundry_01_on"]=13,
    ["appliances_misc_01"]=15,
    ["appliances_misc_01_on"]=11,
    ["appliances_radio_01"]=19,
    ["appliances_refrigeration_01"]=51,
    ["appliances_refrigeration_01_on"]=41,
    ["appliances_television_01"]=73,
    ["bed_overlays_large"]=7,
    ["blood_floor_large"]=5,
    ["blood_floor_med"]=8,
    ["blood_floor_small"]=8,
    ["books&misc_01"]=127,
    ["books&misc_02"]=127,
    ["boulders"]=55,
    ["brokenglass_1"]=3,
    ["camping_01"]=69,
    ["camping_02"]=87,
    ["camping_03"]=47,
    ["camping_03 (ayrton orio's conflicted copy 2025-09-10)"]=47,
    ["camping_04"]=127,
    ["camping_04_on"]=126,
    ["clothing_01"]=63,
    ["clothing_02"]=52,
    ["constructedobjects_01"]=104,
    ["constructedobjects_01_on"]=0,
    ["constructedobjects_signs_01"]=101,
    ["construction_01"]=119,
    ["crafting_ore"]=127,
    ["damaged_objects_01"]=71,
    ["desks_01"]=95,
    ["electricity_pylon"]=181,
    ["farm_01"]=101,
    ["fencing_01"]=123,
    ["fencing_burnt_01"]=75,
    ["fencing_damaged_01"]=180,
    ["fencing_damaged_01b"]=172,
    ["fencing_damaged_02"]=234,
    ["fencing_damaged_03"]=172,
    ["fencing_damaged_04"]=229,
    ["fencing_damaged_05"]=229,
    ["fencing_damaged_06"]=229,
    ["fixtures_01"]=39,
    ["fixtures_bathroom_01"]=67,
    ["fixtures_bathroom_02"]=35,
    ["fixtures_counters_01"]=207,
    ["fixtures_escalators_01"]=57,
    ["fixtures_fireplaces_01"]=73,
    ["fixtures_overlay_counters_01"]=63,
    ["fixtures_railings_01"]=141,
    ["fixtures_sinks_01"]=43,
    ["fixtures_stairs_01"]=90,
    ["food_01"]=115,
    ["food_02"]=103,
    ["foraging_01"]=17,
    ["furniture_bedding_01"]=95,
    ["furniture_seating_indoor_01"]=63,
    ["furniture_seating_indoor_02"]=63,
    ["furniture_seating_indoor_03"]=151,
    ["furniture_seating_outdoor_01"]=35,
    ["furniture_shelving_01"]=61,
    ["furniture_storage_01"]=71,
    ["furniture_storage_01_on"]=67,
    ["furniture_storage_02"]=47,
    ["furniture_tables_high_01"]=63,
    ["furniture_tables_high_02"]=40,
    ["furniture_tables_low_01"]=21,
    ["industry_01"]=57,
    ["industry_02"]=340,
    ["industry_03"]=127,
    ["industry_04"]=23,
    ["industry_bunker_01"]=27,
    ["industry_railroad_01"]=63,
    ["industry_railroad_02"]=54,
    ["industry_railroad_03"]=63,
    ["industry_railroad_04"]=54,
    ["industry_railroad_05"]=60,
    ["industry_trucks_01"]=71,
    ["industry_trucks_02"]=15,
    ["location_barn_01"]=55,
    ["location_business_bank_01"]=78,
    ["location_business_distillery_01"]=34,
    ["location_business_machinery_01"]=52,
    ["location_business_office_generic_01"]=230,
    ["location_business_office_generic_02"]=23,
    ["location_community_cemetary_01"]=43,
    ["location_community_church_small_01"]=271,
    ["location_community_church_small_01_on"]=63,
    ["location_community_medical_01"]=247,
    ["location_community_medical_01_on"]=83,
    ["location_community_park_01"]=52,
    ["location_community_police_01"]=117,
    ["location_community_police_01_on"]=79,
    ["location_community_school_01"]=79,
    ["location_community_school_02"]=47,
    ["location_community_school_overlays"]=47,
    ["location_community_school_overlays_black"]=47,
    ["location_entertainment_gallery_01"]=124,
    ["location_entertainment_gallery_02"]=95,
    ["location_entertainment_theatre_01"]=279,
    ["location_entertainment_theatre_01_on"]=279,
    ["location_farm_accesories_01"]=135,
    ["location_hospitality_sunstarmotel_01"]=67,
    ["location_hospitality_sunstarmotel_02"]=55,
    ["location_hospitality_sunstarmotel_02_on"]=7,
    ["location_military_generic_01"]=71,
    ["location_military_knox_01"]=31,
    ["location_military_tent_01"]=127,
    ["location_orphanage"]=9,
    ["location_restaurant_bar_01"]=125,
    ["location_restaurant_bar_01_on"]=69,
    ["location_restaurant_bar_smutz"]=38,
    ["location_restaurant_diner_01"]=61,
    ["location_restaurant_diner_01_on"]=62,
    ["location_restaurant_generic_01"]=57,
    ["location_restaurant_pie_01"]=63,
    ["location_restaurant_pileocrepe_01"]=59,
    ["location_restaurant_pileocrepe_01_on"]=23,
    ["location_restaurant_pizzawhirled_01"]=78,
    ["location_restaurant_pizzawhirled_01_on"]=78,
    ["location_restaurant_seahorse_01"]=79,
    ["location_restaurant_spiffos_01"]=79,
    ["location_restaurant_spiffos_02"]=63,
    ["location_restaurant_spiffos_02_on"]=59,
    ["location_restaurant_spiffos_03"]=45,
    ["location_restaurant_spiffos_03_on"]=43,
    ["location_services_beauty_01"]=11,
    ["location_sewer_01"]=50,
    ["location_shop_accessories_01"]=37,
    ["location_shop_accessories_01_on"]=19,
    ["location_shop_accessories_genericsigns_01"]=92,
    ["location_shop_accessories_genericsigns_01_on"]=92,
    ["location_shop_bargNclothes_01"]=71,
    ["location_shop_bargNclothes_01_on"]=39,
    ["location_shop_fossoil_01"]=122,
    ["location_shop_fossoil_01_on"]=57,
    ["location_shop_gas2go_01"]=95,
    ["location_shop_gas2go_01_on"]=15,
    ["location_shop_generic_01"]=123,
    ["location_shop_greenes_01"]=79,
    ["location_shop_greenes_01_on"]=52,
    ["location_shop_mall_01"]=109,
    ["location_shop_mall_signs_01"]=143,
    ["location_shop_mall_signs_01_on"]=111,
    ["location_shop_mall_signs_02"]=143,
    ["location_shop_mall_signs_02_on"]=70,
    ["location_shop_zippee_01"]=63,
    ["location_shop_zippee_01_on"]=57,
    ["location_trailer_01"]=63,
    ["location_trailer_02"]=71,
    ["misc_01"]=3,
    ["office_01"]=31,
    ["papernotices_01"]=9,
    ["preset_depthmaps_01"]=7,
    ["radio_tower"]=119,
    ["recreational_01"]=109,
    ["recreational_01_on"]=94,
    ["recreational_sports_01"]=163,
    ["rooftop_furniture"]=55,
    ["rugs_animals"]=103,
    ["seating_01"]=63,
    ["security_01"]=5,
    ["signs_billboards"]=43,
    ["signs_church"]=31,
    ["signs_church_on"]=31,
    ["signs_miscbrands_01"]=118,
    ["signs_miscbrands_01_on"]=118,
    ["signs_one-off_01"]=127,
    ["signs_one-off_01_on"]=114,
    ["signs_one-off_02"]=509,
    ["signs_one-off_02_on"]=495,
    ["signs_one-off_03"]=510,
    ["signs_one-off_03_on"]=494,
    ["signs_one-off_04"]=511,
    ["signs_one-off_04_on"]=483,
    ["signs_one-off_05"]=510,
    ["signs_one-off_05_on"]=434,
    ["signs_one-off_06"]=319,
    ["signs_one-off_06_on"]=308,
    ["signs_one-off_07"]=286,
    ["signs_one-off_07_on"]=159,
    ["signs_one-off_08"]=358,
    ["signs_one-off_08_on"]=325,
    ["signs_one-off_09"]=337,
    ["signs_one-off_09_on"]=159,
    ["signs_one-off_lights_01"]=31,
    ["signs_one-off_lights_01_on"]=31,
    ["stashes_01"]=27,
    ["steamboat"]=37,
    ["storage_01"]=71,
    ["street_curbs_01_diag"]=6,
    ["street_curbs_01_diag_2"]=55,
    ["street_decoration_01"]=95,
    ["street_roadsigns_01"]=51,
    ["trash&junk_01"]=9,
    ["trash_01"]=53,
    ["trash_walls_01"]=9,
    ["trashcontainers_01"]=34,
    ["underground_01"]=11,
    ["water_tank"]=45,
    ["weapons_01"]=31,
    ["z_templates_wallcutaways"]=9
}

local function flagOn()
    local ok, r = pcall(getFileReader, FLAG, false)
    if not (ok and r) then return false end
    local l = nil
    pcall(function() l = r:readLine() end)
    pcall(function() r:close() end)
    return l ~= nil and l:sub(1, 3) == "run"
end

local function put(w, s) w:write(s .. "\n") end

local function run()
    local co = getContainerOverlays()
    local w = getFileWriter(OUT, true, false)
    if not (co and w) then return end
    put(w, "# U <overlay sprite> <furniture sprites that use it>   S <furniture sprite> <square> <room> <items> <overlay the game picks>")
    local bases, nU = {}, 0
    for pre, mx in pairs(PREFIX) do
        for i = 0, mx do
            local name = pre .. "_" .. i
            local ok, list = pcall(function() return co:getUnderlyingSpriteNames(name) end)
            if ok and list and list:size() > 0 then
                local t = {}
                for k = 0, list:size() - 1 do
                    local b = list:get(k)
                    t[#t + 1] = b
                    bases[b] = true
                end
                put(w, "U\t" .. name .. "\t" .. table.concat(t, ","))
                nU = nU + 1
            end
        end
    end
    local nb = 0
    for _ in pairs(bases) do nb = nb + 1 end
    put(w, "# overlays=" .. nU .. " furniture sprites=" .. nb)
    local p = getPlayer()
    local sq0 = p and p:getSquare()
    if sq0 then
        local cell = getCell()
        local rm = sq0:getRoom()
        local room = rm and rm:getName() or "-"
        local errs = {}
        for b, _ in pairs(bases) do
            for s = 0, 3 do
                local sq = cell:getGridSquare(sq0:getX() + s, sq0:getY(), sq0:getZ()) or sq0
                for _, n in ipairs({ 1, 8 }) do
                    local ok, res = pcall(function()
                        local o = IsoObject.new(cell, sq, b)
                        local c = ItemContainer.new("shelves", sq, o)
                        o:setContainer(c)
                        for _ = 1, n do c:AddItem("Base.Pen") end
                        co:updateContainerOverlaySprite(o)
                        local ov = o:getOverlaySprite()
                        return ov and ov:getName() or "-"
                    end)
                    if ok then
                        put(w, "S\t" .. b .. "\t" .. sq:getX() .. "," .. sq:getY() .. "\t" .. room .. "\t" .. n .. "\t" .. tostring(res))
                    else
                        local e = tostring(res)
                        if not errs[e] then
                            errs[e] = true
                            put(w, "# ERR " .. e)
                        end
                    end
                end
            end
        end
    end
    put(w, "# end")
    w:close()
    local f = getFileWriter(FLAG, true, false)
    if f then f:write("done\n"); f:close() end
    print("[ViewpointLivingProps] overlay dump written to Zomboid\\Lua\\" .. OUT)
end

local ticks, ran = 0, false
Events.OnTick.Add(function()
    if ran or not ViewpointLivingPropsIsActive() then return end
    ticks = ticks + 1
    if ticks == 600 then            -- about ten seconds after the game is running
        ran = true
        if flagOn() then
            local ok, err = pcall(run)
            if not ok then print("[ViewpointLivingProps] overlay dump failed: " .. tostring(err)) end
        end
    end
end)
