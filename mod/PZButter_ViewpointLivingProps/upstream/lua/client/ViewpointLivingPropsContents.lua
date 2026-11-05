-- Viewpoint: Living Props - what is inside the shop furniture (shelves, racks, gondolas, bookcases, wall displays...).
--
-- The game shows how full a piece of furniture is with a small flat "overlay" picture on top of it (empty = nothing, a few items
-- = "some", many items = "full"). It only has those pictures for the sides and kinds of furniture that you can see in the
-- isometric view. This mod has 3D contents for EVERY side of EVERY one of these pieces, so for the ones the game has no picture
-- for (the back sides, the pieces without any overlay) this script does the same thing the game does and hangs its own overlay
-- (a name like lp_sh_hw_full_a_W, which the model pack binds to the 3D contents) on the furniture:
--   no items = nothing,  fewer than 7 items = "some",  7 or more = "full"   (the very same rule as the game's)
-- Where the game DOES have its own overlay for the piece, the game's picture is only meant for the flat isometric view (the 3D view
-- cannot draw it unless the model pack knows that picture), so while Project Viewpoint's view is showing this script puts its own
-- overlay on top, with the same rule as the game's, and the game's picture is given back when the isometric view is on.
--
-- It is light: furniture is found by name (a table look-up), a small strip of ground around the player is looked at each time
-- (the whole area every few seconds, not every frame), a change of the contents is noticed at once, and the overlay is only
-- changed when the state really changes (each change makes Project Viewpoint redraw that piece of map).
-- In the game's normal isometric view nothing of this exists: the overlay is taken off and the game's own one is given back.
-- Only in-memory state of objects is touched; nothing is sent to the server and no game or mod file is changed.

local P = ViewpointLivingPropsPerf or { tag = function() end, fn = function(_, f) return f end, pcall = function(_, f, ...) return pcall(f, ...) end, count = function() end, gauge = function() end }
P.tag("Contents")

local FEW_LIMIT = 7            -- the game's own limit: fewer items than this = "some", this many or more = "full"
local AREA = 14                -- tiles around the player that are looked at (the area is walked one row at a time)
local ROWS_PER_STEP = 2        -- rows of that area looked at each step
local STEP_TICKS = 20          -- ticks between two steps
local CHECK_TICKS = 30         -- ticks between two checks of the furniture close to the player (contents may have changed)
local NEAR = 10                -- "close" = this many tiles
local FAR_EVERY = 4            -- the furniture farther away (up to CHECK_RANGE tiles) is checked only every this many checks
local CHECK_RANGE = 28         -- beyond this many tiles nothing is checked (it is looked at again when you come near)

-- Extra lines in console.txt. Off normally; switched on by an empty text file called ViewpointLivingProps_debug.txt in Zomboid\Lua.
local function debugFlag()
    local ok, r = pcall(getFileReader, "ViewpointLivingProps_debug.txt", false)
    if ok and r then
        pcall(function() r:close() end)
        return true
    end
    return false
end
local DEBUG = debugFlag()

-- sprite name of the furniture -> { name of the contents family, side }
-- (the 3D contents are bound to <family>_<some|full>_<a|b>_<side>)
local SPR = {
    ["appliances_refrigeration_01_16"] = { "lp_frgB", "E" },
    ["appliances_refrigeration_01_17"] = { "lp_frgA", "E" },
    ["appliances_refrigeration_01_18"] = { "lp_frgA", "S" },
    ["appliances_refrigeration_01_19"] = { "lp_frgB", "S" },
    ["appliances_refrigeration_01_22"] = { "lp_fib", "S" },
    ["appliances_refrigeration_01_23"] = { "lp_fib", "E" },
    ["appliances_refrigeration_01_36"] = { "lp_fib", "N" },
    ["appliances_refrigeration_01_37"] = { "lp_fib", "W" },
    ["appliances_refrigeration_01_40"] = { "lp_fiw", "S" },
    ["appliances_refrigeration_01_41"] = { "lp_fiw", "E" },
    ["appliances_refrigeration_01_42"] = { "lp_fiw", "N" },
    ["appliances_refrigeration_01_43"] = { "lp_fiw", "W" },
    ["appliances_refrigeration_01_44"] = { "lp_frgB", "W" },
    ["appliances_refrigeration_01_45"] = { "lp_frgA", "W" },
    ["appliances_refrigeration_01_46"] = { "lp_frgA", "N" },
    ["appliances_refrigeration_01_47"] = { "lp_frgB", "N" },
    ["carpentry_02_64"] = { "lp_bk", "S" },
    ["carpentry_02_65"] = { "lp_bk", "E" },
    ["carpentry_02_66"] = { "lp_bk", "W" },
    ["carpentry_02_67"] = { "lp_bk", "N" },
    ["furniture_shelving_01_0"] = { "lp_sh_hwc", "S" },
    ["furniture_shelving_01_1"] = { "lp_sh_hw", "S" },
    ["furniture_shelving_01_10"] = { "lp_sh_big", "E" },
    ["furniture_shelving_01_11"] = { "lp_sh_big", "N" },
    ["furniture_shelving_01_12"] = { "lp_sh_big", "W" },
    ["furniture_shelving_01_13"] = { "lp_sh_bigc", "N" },
    ["furniture_shelving_01_14"] = { "lp_sh_bigc", "W" },
    ["furniture_shelving_01_15"] = { "lp_sh_bigc", "E" },
    ["furniture_shelving_01_16"] = { "lp_sh_low_a", "S" },
    ["furniture_shelving_01_17"] = { "lp_sh_low_middle", "S" },
    ["furniture_shelving_01_18"] = { "lp_sh_low_b", "S" },
    ["furniture_shelving_01_19"] = { "lp_sh_low_low", "S" },
    ["furniture_shelving_01_2"] = { "lp_sh_hw", "E" },
    ["furniture_shelving_01_20"] = { "lp_sh_low_a", "E" },
    ["furniture_shelving_01_21"] = { "lp_sh_low_middle", "E" },
    ["furniture_shelving_01_22"] = { "lp_sh_low_b", "E" },
    ["furniture_shelving_01_23"] = { "lp_sh_low_low", "E" },
    ["furniture_shelving_01_24"] = { "lp_sh_rackL", "E" },
    ["furniture_shelving_01_25"] = { "lp_sh_rackR", "E" },
    ["furniture_shelving_01_26"] = { "lp_sh_rackL", "S" },
    ["furniture_shelving_01_27"] = { "lp_sh_rackR", "S" },
    ["furniture_shelving_01_28"] = { "lp_sh_mw", "S" },
    ["furniture_shelving_01_29"] = { "lp_sh_mw", "E" },
    ["furniture_shelving_01_3"] = { "lp_sh_hw", "W" },
    ["furniture_shelving_01_30"] = { "lp_sh_mw", "W" },
    ["furniture_shelving_01_31"] = { "lp_sh_mw", "N" },
    ["furniture_shelving_01_32"] = { "lp_sh_plank_light", "E" },
    ["furniture_shelving_01_33"] = { "lp_sh_plank_light", "S" },
    ["furniture_shelving_01_34"] = { "lp_sh_plank_dark", "E" },
    ["furniture_shelving_01_35"] = { "lp_sh_plank_dark", "S" },
    ["furniture_shelving_01_36"] = { "lp_sh_plank_white", "E" },
    ["furniture_shelving_01_37"] = { "lp_sh_plank_white", "S" },
    ["furniture_shelving_01_4"] = { "lp_sh_hw", "N" },
    ["furniture_shelving_01_40"] = { "lp_bk", "S" },
    ["furniture_shelving_01_41"] = { "lp_bk", "E" },
    ["furniture_shelving_01_42"] = { "lp_bk", "W" },
    ["furniture_shelving_01_43"] = { "lp_bk", "N" },
    ["furniture_shelving_01_44"] = { "lp_bk", "S" },
    ["furniture_shelving_01_45"] = { "lp_bk", "E" },
    ["furniture_shelving_01_46"] = { "lp_bk", "W" },
    ["furniture_shelving_01_47"] = { "lp_bk", "N" },
    ["furniture_shelving_01_48"] = { "lp_sh_low_b", "N" },
    ["furniture_shelving_01_49"] = { "lp_sh_low_middle", "N" },
    ["furniture_shelving_01_5"] = { "lp_sh_hwc", "W" },
    ["furniture_shelving_01_50"] = { "lp_sh_low_a", "N" },
    ["furniture_shelving_01_51"] = { "lp_sh_low_low", "N" },
    ["furniture_shelving_01_52"] = { "lp_sh_low_b", "W" },
    ["furniture_shelving_01_53"] = { "lp_sh_low_middle", "W" },
    ["furniture_shelving_01_54"] = { "lp_sh_low_a", "W" },
    ["furniture_shelving_01_55"] = { "lp_sh_low_low", "W" },
    ["furniture_shelving_01_56"] = { "lp_sh_plank_light", "W" },
    ["furniture_shelving_01_57"] = { "lp_sh_plank_light", "N" },
    ["furniture_shelving_01_58"] = { "lp_sh_plank_dark", "W" },
    ["furniture_shelving_01_59"] = { "lp_sh_plank_dark", "N" },
    ["furniture_shelving_01_6"] = { "lp_sh_hwc", "N" },
    ["furniture_shelving_01_60"] = { "lp_sh_plank_white", "W" },
    ["furniture_shelving_01_61"] = { "lp_sh_plank_white", "N" },
    ["furniture_shelving_01_7"] = { "lp_sh_hwc", "E" },
    ["furniture_shelving_01_8"] = { "lp_sh_bigc", "S" },
    ["furniture_shelving_01_9"] = { "lp_sh_big", "S" },
    ["location_entertainment_theatre_01_124"] = { "lp_rlB", "N" },
    ["location_entertainment_theatre_01_125"] = { "lp_rlA", "N" },
    ["location_entertainment_theatre_01_126"] = { "lp_rlB", "W" },
    ["location_entertainment_theatre_01_127"] = { "lp_rlA", "W" },
    ["location_entertainment_theatre_01_132"] = { "lp_rsB", "N" },
    ["location_entertainment_theatre_01_133"] = { "lp_rsA", "N" },
    ["location_entertainment_theatre_01_134"] = { "lp_rsB", "W" },
    ["location_entertainment_theatre_01_135"] = { "lp_rsA", "W" },
    ["location_restaurant_pie_01_32"] = { "lp_csR", "W" },
    ["location_restaurant_pie_01_33"] = { "lp_csL", "W" },
    ["location_restaurant_pie_01_34"] = { "lp_csL", "N" },
    ["location_restaurant_pie_01_35"] = { "lp_csR", "N" },
    ["location_restaurant_pizzawhirled_01_68"] = { "lp_csR", "W" },
    ["location_restaurant_pizzawhirled_01_69"] = { "lp_csL", "W" },
    ["location_restaurant_pizzawhirled_01_70"] = { "lp_csL", "N" },
    ["location_restaurant_pizzawhirled_01_71"] = { "lp_csR", "N" },
    ["location_restaurant_seahorse_01_60"] = { "lp_csR", "W" },
    ["location_restaurant_seahorse_01_61"] = { "lp_csL", "W" },
    ["location_restaurant_seahorse_01_62"] = { "lp_csL", "N" },
    ["location_restaurant_seahorse_01_63"] = { "lp_csR", "N" },
    ["location_shop_fossoil_01_64"] = { "lp_mgR", "W" },
    ["location_shop_fossoil_01_65"] = { "lp_mgL", "W" },
    ["location_shop_fossoil_01_66"] = { "lp_mgL", "N" },
    ["location_shop_fossoil_01_67"] = { "lp_mgR", "N" },
    ["location_shop_generic_01_0"] = { "lp_sk_comics", "E" },
    ["location_shop_generic_01_1"] = { "lp_sk_comics", "S" },
    ["location_shop_generic_01_10"] = { "lp_sg_gm", "S" },
    ["location_shop_generic_01_104"] = { "lp_mgR", "W" },
    ["location_shop_generic_01_105"] = { "lp_mgL", "W" },
    ["location_shop_generic_01_106"] = { "lp_mgL", "N" },
    ["location_shop_generic_01_107"] = { "lp_mgR", "N" },
    ["location_shop_generic_01_11"] = { "lp_sg_gp", "S" },
    ["location_shop_generic_01_112"] = { "lp_csR", "W" },
    ["location_shop_generic_01_113"] = { "lp_csL", "W" },
    ["location_shop_generic_01_114"] = { "lp_csL", "N" },
    ["location_shop_generic_01_115"] = { "lp_csR", "N" },
    ["location_shop_generic_01_116"] = { "lp_sw_fancy", "N" },
    ["location_shop_generic_01_117"] = { "lp_sw_fancy", "W" },
    ["location_shop_generic_01_118"] = { "lp_sw_wood", "N" },
    ["location_shop_generic_01_119"] = { "lp_sw_wood", "W" },
    ["location_shop_generic_01_12"] = { "lp_sg_gp", "E" },
    ["location_shop_generic_01_13"] = { "lp_sg_gm", "E" },
    ["location_shop_generic_01_14"] = { "lp_sg_gm", "E" },
    ["location_shop_generic_01_15"] = { "lp_sg_ge", "E" },
    ["location_shop_generic_01_2"] = { "lp_sv_bmag", "S" },
    ["location_shop_generic_01_28"] = { "lp_sv_bwall", "W" },
    ["location_shop_generic_01_29"] = { "lp_sv_bwall", "N" },
    ["location_shop_generic_01_3"] = { "lp_sv_bmag", "E" },
    ["location_shop_generic_01_30"] = { "lp_sv_bwall", "S" },
    ["location_shop_generic_01_31"] = { "lp_sv_bwall", "E" },
    ["location_shop_generic_01_62"] = { "lp_sw_wood", "S" },
    ["location_shop_generic_01_63"] = { "lp_sw_wood", "E" },
    ["location_shop_generic_01_72"] = { "lp_sg_ls", "S" },
    ["location_shop_generic_01_73"] = { "lp_sg_ls", "S" },
    ["location_shop_generic_01_74"] = { "lp_sg_ls", "E" },
    ["location_shop_generic_01_75"] = { "lp_sg_ls", "E" },
    ["location_shop_generic_01_76"] = { "lp_sg_ls", "N" },
    ["location_shop_generic_01_77"] = { "lp_sg_ls", "N" },
    ["location_shop_generic_01_78"] = { "lp_sg_ls", "W" },
    ["location_shop_generic_01_79"] = { "lp_sg_ls", "W" },
    ["location_shop_generic_01_8"] = { "lp_sg_ge", "S" },
    ["location_shop_generic_01_86"] = { "lp_sw_fancy", "S" },
    ["location_shop_generic_01_87"] = { "lp_sw_fancy", "E" },
    ["location_shop_generic_01_9"] = { "lp_sg_gm", "S" },
    ["location_shop_greenes_01_56"] = { "lp_sg_ge", "S" },
    ["location_shop_greenes_01_57"] = { "lp_sg_gm", "S" },
    ["location_shop_greenes_01_58"] = { "lp_sg_gm", "S" },
    ["location_shop_greenes_01_59"] = { "lp_sg_gp", "S" },
    ["location_shop_greenes_01_60"] = { "lp_sg_gp", "E" },
    ["location_shop_greenes_01_61"] = { "lp_sg_gm", "E" },
    ["location_shop_greenes_01_62"] = { "lp_sg_gm", "E" },
    ["location_shop_greenes_01_63"] = { "lp_sg_ge", "E" },
    ["location_shop_zippee_01_0"] = { "lp_sv_zwall", "W" },
    ["location_shop_zippee_01_1"] = { "lp_sv_zwall", "N" },
    ["location_shop_zippee_01_10"] = { "lp_sg_gm", "S" },
    ["location_shop_zippee_01_11"] = { "lp_sg_gp", "S" },
    ["location_shop_zippee_01_12"] = { "lp_sg_gp", "E" },
    ["location_shop_zippee_01_13"] = { "lp_sg_gm", "E" },
    ["location_shop_zippee_01_14"] = { "lp_sg_gm", "E" },
    ["location_shop_zippee_01_15"] = { "lp_sg_ge", "E" },
    ["location_shop_zippee_01_2"] = { "lp_sv_zwall", "S" },
    ["location_shop_zippee_01_3"] = { "lp_sv_zwall", "E" },
    ["location_shop_zippee_01_32"] = { "lp_mgR", "W" },
    ["location_shop_zippee_01_33"] = { "lp_mgL", "W" },
    ["location_shop_zippee_01_34"] = { "lp_mgL", "N" },
    ["location_shop_zippee_01_35"] = { "lp_mgR", "N" },
    ["location_shop_zippee_01_4"] = { "lp_sv_zlow", "S" },
    ["location_shop_zippee_01_5"] = { "lp_sv_zlow", "E" },
    ["location_shop_zippee_01_62"] = { "lp_sv_zlow", "N" },
    ["location_shop_zippee_01_63"] = { "lp_sv_zlow", "W" },
    ["location_shop_zippee_01_8"] = { "lp_sg_ge", "S" },
    ["location_shop_zippee_01_9"] = { "lp_sg_gm", "S" },
}

local tracked = {}             -- furniture found -> true
P.gauge("Contents.tracked", function() local n = 0 for _ in pairs(tracked) do n = n + 1 end return n end)
local saved = {}               -- furniture -> the overlay it had before this script put its own (so the isometric view gets it back)
local warned = false
local sets = 0

local function nameOf(spr) return spr and spr:getName() or nil end

local function isOurs(n)
    return n ~= nil and n:sub(1, 3) == "lp_" and n:find("_[sf][ou][ml][el]_[ab]_[SEWN]$") ~= nil
end

-- Is Project Viewpoint's own view showing? true = yes, false = the game's isometric view, nil = cannot tell
local function viewState()
    return ViewpointLivingPropsIsActive()
end

-- does the game have its own contents overlay for this kind of furniture? (it depends only on the sprite: asked once per sprite)
local gameHas = nil
local hasCache = {}
local function gameHasOverlay(o, sprName)
    local c = hasCache[sprName]
    if c ~= nil then return c end
    if gameHas == nil then
        local ok, co = pcall(getContainerOverlays)
        gameHas = (ok and co) or false
    end
    local r = false
    if gameHas then
        local ok, v = pcall(function() return gameHas:hasOverlays(o) end)
        r = ok and v == true
    end
    hasCache[sprName] = r
    return r
end

-- which of the two looks (a / b) a square gets: always the same for the same square
local function variantOf(sq)
    local h = sq:getX() * 7 + sq:getY() * 13 + sq:getZ() * 3
    return (h % 2 == 0) and "a" or "b"
end

local function wanted(o, info)   -- info = { family, side, sprite name }
    local c = o:getContainer()
    if not c then return nil end
    local items = c:getItems()
    local n = items and items:size() or 0
    if n <= 0 then return nil end
    local sq = o:getSquare()
    if not sq then return nil end
    return info[1] .. "_" .. (n < FEW_LIMIT and "some" or "full") .. "_" .. variantOf(sq) .. "_" .. info[2]
end

local function setOverlay(o, name)
    o:setOverlaySprite(name, false)
    sets = sets + 1
    P.count("overlay changes (contents)")
end

-- Our overlay comes off and the game's own picture goes back. The game's own function is asked for it (the very one the game uses when
-- a container changes): it picks the picture from what is inside, the same one every time for the same square. This also fixes furniture
-- that was SAVED with one of our overlay names (the game keeps the overlay in the save): there is no "previous picture" to give back for
-- those, and an empty one would leave the furniture without any picture, in the isometric view too.
local function giveBack(o, sprName)
    local prev = saved[o]
    saved[o] = nil
    local done = false
    if gameHasOverlay(o, sprName) and gameHas then
        -- our name comes off first, in the same call: the game's own update must not find our picture on the piece (the Java hook keeps
        -- our picture when the game tries to redraw over it), so the game's picture always goes back
        pcall(function() o:setOverlaySprite("", false) end)
        done = pcall(function() gameHas:updateContainerOverlaySprite(o) end)
        if done then sets = sets + 1 P.count("overlay changes (contents)") end
    end
    if not done then setOverlay(o, prev or "") end
end

local repaired = {}
local noPic = {}
local seenName = {}
local DIRECT = {   -- families whose contents are bound straight to the game's own picture names (game_overlays.txt, frag 45/46/47/54/55)
    lp_bk = true, lp_sh_big = true, lp_sh_plank_light = true, lp_sh_plank_dark = true, lp_sh_plank_white = true, lp_sk_comics = true,
    lp_sg_ge = true, lp_sg_gm = true, lp_sg_gp = true, lp_sg_ls = true, lp_sh_bigc = true, lp_sh_hw = true, lp_sh_hwc = true,
    lp_sh_low_a = true, lp_sh_low_b = true, lp_sh_low_low = true, lp_sh_low_middle = true, lp_sh_mw = true,
    lp_sh_rackL = true, lp_sh_rackR = true, lp_sv_bwall = true, lp_sw_fancy = true, lp_sw_wood = true,
    lp_frgA = true, lp_frgB = true, lp_fib = true, lp_fiw = true,
}
-- ...except these two pieces: the game's picture of both (books&misc_01_0 / _1) is shared by two pieces with a different shape, so it cannot be
-- bound to one model; they stay with the contents script
local NODIRECT = { furniture_shelving_01_8 = true, furniture_shelving_01_0 = true }
local function isDirect(info) return DIRECT[info[1]] == true and not NODIRECT[info[3]] end

local function sync(o, st)       -- st = Project Viewpoint's view: true = 3D showing, false = isometric, nil = cannot tell
    local info = tracked[o]
    if type(info) ~= "table" then return end
    local cur = nameOf(o:getOverlaySprite())
    local ours = isOurs(cur)
    -- the bookcases and the big wall shelf already have the game's own pictures bound (by name) to their 3D contents: when the game
    -- has a picture for them it is left alone (nothing is redrawn when an item is taken out: the game changes its picture and the
    -- 3D follows it at once, and it always shows what the game's isometric view shows)
    local gameOwns = gameHasOverlay(o, info[3]) and (st ~= true or isDirect(info))
    if DEBUG and gameOwns and isDirect(info) and seenName[o] ~= (cur or "") then   -- log: when does the game change its picture?
        local sq = o:getSquare()
        local c0 = o:getContainer()
        local n0 = (c0 and c0:getItems()) and c0:getItems():size() or -1
        print("[ViewpointLivingProps] game picture " .. tostring(info[3]) .. "@" .. (sq and (sq:getX() .. "," .. sq:getY() .. "," .. sq:getZ()) or "?") .. ": " .. tostring(seenName[o]) .. " -> " .. tostring(cur) .. " items=" .. tostring(n0) .. " explored=" .. tostring(c0 and c0:isExplored()))
        seenName[o] = cur or ""
    end
    if st == false or gameOwns then
        -- the game's own look: take our overlay off and give back what the game had
        if ours then
            giveBack(o, info[3])
        elseif gameOwns and cur == nil and not repaired[o] then
            -- a piece that lost its picture in an earlier session (the old bug above, kept in the save): the game's own picture is
            -- asked for once. Nothing changes when the game itself has no picture for what is inside.
            repaired[o] = true
            local c = o:getContainer()
            if c and c:getItems() and c:getItems():size() > 0 then giveBack(o, info[3]) end
        end
        -- (debug only) a piece that still has no picture although something is inside: what does the game say about it?
        if DEBUG and gameOwns and cur == nil and repaired[o] and not noPic[o] and nameOf(o:getOverlaySprite()) == nil then
            local c = o:getContainer()
            local n = (c and c:getItems()) and c:getItems():size() or 0
            if n > 0 then
                noPic[o] = true
                local sq = o:getSquare()
                local rm = (sq and sq:getRoom()) and sq:getRoom():getName() or "none"
                print("[ViewpointLivingProps] NO PICTURE with items: " .. tostring(info[3]) .. "@" .. (sq and (sq:getX() .. "," .. sq:getY() .. "," .. sq:getZ()) or "?") .. " items=" .. n .. " explored=" .. tostring(c:isExplored()) .. " room=" .. tostring(rm))
            end
        end
        return
    end
    local want = wanted(o, info)
    if want then
        if cur ~= want then
            if not ours then saved[o] = cur end
            setOverlay(o, want)
            if DEBUG then
                local sq = o:getSquare()
                print("[ViewpointLivingProps] contents " .. tostring(info[3]) .. "@" .. sq:getX() .. "," .. sq:getY() .. "," .. sq:getZ() .. " -> " .. want)
            end
        end
    elseif ours then
        giveBack(o, info[3])
    end
end

local function stillThere(o)
    local sq = o:getSquare()
    if not sq then return false end
    local ok, idx = pcall(function() return o:getObjectIndex() end)
    return ok and idx ~= nil and idx >= 0
end

local function adopt(o)
    if tracked[o] then return end
    local spr = o:getSprite()
    local n = spr and spr:getName()
    local info = n and SPR[n]
    if info and o:getContainer() then tracked[o] = { info[1], info[2], n } end
end

local function look(sq)
    local objs = sq:getObjects()
    local st = nil
    for i = 0, objs:size() - 1 do
        local o = objs:get(i)
        if not tracked[o] then
            pcall(adopt, o)
            if tracked[o] then               -- newly found: show its contents right away
                if st == nil then st = viewState() end
                pcall(sync, o, st)
            end
        end
    end
end

-- the strip of ground around the player is walked one row at a time
local rowIdx = 0
local function step()
    local p = getPlayer()
    if not p then return end
    local sq = p:getSquare()
    if not sq then return end
    local cell = getCell()
    local px, py, pz = sq:getX(), sq:getY(), sq:getZ()
    local side = AREA * 2 + 1
    for _ = 1, ROWS_PER_STEP do
        local dy = (rowIdx % side) - AREA
        rowIdx = rowIdx + 1
        for dx = -AREA, AREA do
            local s = cell:getGridSquare(px + dx, py + dy, pz)
            if s then look(s) end
        end
    end
end

local checks, forceAll = 0, false
local function check()
    local p = getPlayer()
    if not p then return end
    local px, py = p:getX(), p:getY()
    local st = viewState()
    checks = checks + 1
    local far = (checks % FAR_EVERY == 0) or forceAll
    forceAll = false
    for o in pairs(tracked) do
        if not stillThere(o) then
            tracked[o] = nil
            saved[o] = nil
            repaired[o] = nil
        else
            local sq = o:getSquare()
            local ddx, ddy = math.abs(sq:getX() - px), math.abs(sq:getY() - py)
            if st == false or (ddx <= NEAR and ddy <= NEAR) or (far and ddx <= CHECK_RANGE and ddy <= CHECK_RANGE) then
                local ok, err = pcall(sync, o, st)
                if not ok and not warned then
                    warned = true
                    print("[ViewpointLivingProps] contents: " .. tostring(err))
                end
            end
        end
    end
end

-- (timed only when ViewpointLivingProps_perf.txt exists)
step = P.fn("Contents.step", step)
check = P.fn("Contents.check", check)
-- the view was switched: everything is looked at again right away
local lastIso = nil
local ticks = 0
Events.OnTick.Add(function()
    ticks = ticks + 1
    if ticks % STEP_TICKS == 0 then pcall(step) end
    if ticks % CHECK_TICKS == 0 then
        pcall(check)
    elseif ticks % 6 == 0 then
        local st = viewState()
        if st ~= nil and st ~= lastIso then
            lastIso = st
            forceAll = true
            pcall(check)
        end
    end
end)

-- a piece of furniture was just found or filled: look at it right away
local function touched(o)
    if not o then return end
    pcall(function()
        adopt(o)
        if tracked[o] then sync(o, viewState()) end
    end)
end

-- a container was filled (shops are filled when the ground around them loads): the furniture that holds it is found at once
pcall(function()
    Events.OnFillContainer.Add(function(roomName, containerType, container)
        if not container then return end
        -- while the ground loads, the game can hand over its own helper object (ItemPickerContainer) that has no getParent:
        -- only a real ItemContainer is asked (asking the helper object logs an error, and the debug window stops on it)
        local real = false
        if type(instanceof) == "function" then
            local okI, isC = pcall(instanceof, container, "ItemContainer")
            real = okI and isC == true
        end
        if not real then return end
        local ok, parent = pcall(function() return container:getParent() end)
        if ok and parent then touched(parent) end
    end)
end)

Events.OnGameStart.Add(function()
    local n = 0
    for _ in pairs(SPR) do n = n + 1 end
    print("[ViewpointLivingProps] contents script running: " .. n .. " kinds of furniture (client=" .. tostring(isClient()) .. ")")
end)
P.tag(nil)
