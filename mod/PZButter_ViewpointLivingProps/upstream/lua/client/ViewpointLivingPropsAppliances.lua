-- Viewpoint: Living Props - appliances (stoves, ovens, microwaves, wood stove, TVs).
--
-- 1) Stoves: in this game build the "flames on" drawing of a stove is a separate 2D "light on" sprite that Project
--    Viewpoint does not draw. So this script mirrors the stove's on/off state onto the object's overlay sprite
--    (appliances_cooking_ON_01_<id>), which Viewpoint does draw and which this mod binds to the 3D "on" model.
--    It only touches in-memory state while the game runs; no game or mod file is changed. Turning the stove off clears it.
-- 2) The old 2D "light on" drawing is switched off for the stoves that have a 3D model (flag below), but ONLY while
--    Project Viewpoint's own view is showing: back in the game's normal isometric view everything is put back as the game has it.
-- 2b) Barbecues, the stone kiln and the stone ring forge (IsoBarbecue) never change picture when lit: the same script hangs their "lit" overlay
--    on them while they burn (LIT_NAMES), again only while Project Viewpoint's view is showing.
-- 3) Gas stove flames flicker and microwave plates turn (see ANIM_MS), antique wood stove lights its vent when lit.
-- 4) A stove or oven that is ON gives off a little warm light (the game gives them none, so the kitchen stayed pitch black).
--    Only while Project Viewpoint's own view is showing: in the game's normal isometric view this light does not exist.

local P = ViewpointLivingPropsPerf or { tag = function() end, fn = function(_, f) return f end, pcall = function(_, f, ...) return pcall(f, ...) end, count = function() end, gauge = function() end }
P.tag("Appliances")

local HIDE_2D_STOVE_LIGHTS = true
-- Animations (gas flames flicker, microwave plate turns) are light but not free: every picture change makes Project Viewpoint
-- rebuild the piece of map around the object. So they only run while the object is close to the player (ANIM_RANGE tiles)
-- AND inside what Viewpoint's camera shows (ONSCREEN_ONLY, asked from the small Java helper: it is the same test Viewpoint uses to
-- flatten corpses you are not looking at). Otherwise the object just shows its normal "on" picture, which costs nothing.
local ANIM_RANGE = 7
local ONSCREEN_ONLY = true
-- ANIM_MS = time of each picture (0 = no animation at all, lightest). Flames and plate share it, so they change together
-- and Viewpoint rebuilds the map piece once instead of twice.
local function animOffFlag()
    local ok, r = pcall(getFileReader, "ViewpointLivingProps_no_animation.txt", false)
    if ok and r then
        pcall(function() r:close() end)
        return true
    end
    return false
end
-- An empty text file called ViewpointLivingProps_no_animation.txt in the Zomboid\Lua folder (restart the game) turns the flame
-- flicker and the microwave plate off: the stove / microwave then simply shows its normal "on" picture.
local ANIM_MS = animOffFlag() and 0 or 360   -- (0 = no animation at all). The animation only starts once ALL its pictures are loaded (see framesReady), so it does not blink
local SEQUENCE = { 1, 2, 3, 2 }            -- gas stoves: the blue flames alternate between 3 slightly different shapes
local SPIN_FRAMES = 6                      -- microwaves: 6 pictures = one turn of the plate (about 2 seconds)
-- Extra lines in console.txt (a stove/TV near the player changes state, lights added/removed). Off normally; it is switched on
-- by an empty text file called ViewpointLivingProps_debug.txt in the Zomboid\Lua folder.
local function debugFlag()
    local ok, r = pcall(getFileReader, "ViewpointLivingProps_debug.txt", false)
    if ok and r then
        pcall(function() r:close() end)
        return true
    end
    return false
end
local DEBUG = debugFlag()

-- The little light of a stove / oven that is on (not microwaves). false = no light at all.
local STOVE_LIGHT = true
local LIGHT_R, LIGHT_G, LIGHT_B = 0.55, 0.32, 0.12   -- warm orange, 0..1 (smaller numbers = weaker light)
local LIGHT_RADIUS = 3                                -- how many tiles it reaches (smaller = a smaller pool of light)
-- The microwave: the tiniest glow, the colour of its own lit picture (yellow 225,206,43 of 255) at a third of its strength.
-- Only while Project Viewpoint's view is showing (in the isometric view nothing is added). false = no light.
local MICRO_LIGHT = true
local MICRO_STRENGTH = 0.30                           -- 0..1 (smaller = weaker)
local MICRO_R, MICRO_G, MICRO_B = 0.88 * MICRO_STRENGTH, 0.81 * MICRO_STRENGTH, 0.17 * MICRO_STRENGTH
local MICRO_RADIUS = 2                                -- tiles

local STOVE_PREFIX = "appliances_cooking_01_"
local STOVE_ON_PREFIX = "appliances_cooking_ON_01_"
local STOVE_IDS = {}               -- ids that have a 3D "on" model
for id = 0, 15 do STOVE_IDS[id] = true end       -- the four small stoves
for id = 20, 23 do STOVE_IDS[id] = true end      -- industrial oven
for id = 40, 43 do STOVE_IDS[id] = true end      -- large two-tile oven (E, S)
for id = 48, 51 do STOVE_IDS[id] = true end      -- large two-tile oven (W, N)
for id = 24, 31 do STOVE_IDS[id] = true end      -- microwaves, all four sides (white 24-27, chrome 28-31)

local FLAME_IDS = {}               -- stoves with gas flames (green 0-3, modern 12-15)
for id = 0, 3 do FLAME_IDS[id] = true end
for id = 12, 15 do FLAME_IDS[id] = true end
local FLAME_PREFIX = { false, "lp_flame_b_", "lp_flame_c_" }   -- shape 1 is the normal "on" sprite
local SPIN_IDS = {}                -- microwaves: the plate turns while it is on
for id = 24, 31 do SPIN_IDS[id] = true end

-- Objects that are not IsoStove but also show a lit picture while burning: sprite name -> overlay sprite name (IsoFireplace / IsoBarbecue).
-- The game itself already sets some of these overlays (GAME_LIT below); setting the same name again is harmless.
local LIT_NAMES = {}
for id = 16, 19 do LIT_NAMES[STOVE_PREFIX .. id] = STOVE_PREFIX .. "on_" .. id end        -- antique wood stove (IsoFireplace)
LIT_NAMES[STOVE_PREFIX .. 35] = STOVE_PREFIX .. "on_35"                                    -- charcoal kettle barbecue
for id = 36, 39 do                                                                         -- gas barbecue with tank (S, E, N, W)
    LIT_NAMES[STOVE_PREFIX .. id] = STOVE_PREFIX .. "on_" .. id
    LIT_NAMES[STOVE_PREFIX .. (id + 8)] = STOVE_PREFIX .. "on_" .. id                      -- the same without the tank (44..47) has no glow picture of its own
end
for _, id in ipairs({ 16, 33, 45, 46 }) do LIT_NAMES["crafted_01_" .. id] = "crafted_01_on_" .. id end   -- stone kiln (16, 33) and stone ring forge (45, 46)
-- the lit pictures the game puts on by itself (so they must stay in the game's isometric view)
local GAME_LIT = {}
for id = 16, 17 do GAME_LIT[STOVE_PREFIX .. id] = true end

-- The large oven is two tiles (two objects). Each half also lights up when the other half is on, so the pair looks consistent.
-- sprite id -> { sibling sprite id, dx, dy of the sibling's square }
local PAIRS = {
    [40] = { 41, 0, -1 }, [41] = { 40, 0, 1 },
    [42] = { 43, 1, 0 },  [43] = { 42, -1, 0 },
    [48] = { 49, 0, -1 }, [49] = { 48, 0, 1 },
    [50] = { 51, 1, 0 },  [51] = { 50, -1, 0 },
}

local tracked = {}                 -- objects found near the player (stoves)
P.gauge("Appliances.tracked", function() local n = 0 for _ in pairs(tracked) do n = n + 1 end return n end)
local warned = false
local ticks = 0

local function nameOf(spr) return spr and spr:getName() or nil end

-- ---------------------------------------------------------------- 2D lights off (Viewpoint look) / back on (isometric)
local had2D = {}                   -- sprite name -> true when the sprite HAD the 2D "light on" flag before this mod took it away
local flag2D = nil                 -- true = flags taken away (Viewpoint look), false = given back (the game's own look)

local function hide2D(name)
    local spr = getSprite(name)
    if not spr then return false end
    local props = spr:getProperties()
    if not props then return false end
    if props:has(IsoFlagType.HasLightOnSprite) then had2D[name] = true end
    props:unset(IsoFlagType.HasLightOnSprite)
    return true
end

local function hideAll2D()
    if not HIDE_2D_STOVE_LIGHTS or not ViewpointLivingPropsIsActive() then return end
    local n = 0
    local all = {}
    for id in pairs(STOVE_IDS) do all[id] = true end
    for id in pairs(all) do all[id] = STOVE_PREFIX .. id end
    for name in pairs(LIT_NAMES) do all[name] = name end
    for _, name in pairs(all) do
        local ok, res = pcall(hide2D, name)
        if ok then
            if res then n = n + 1 end
        elseif not warned then
            warned = true
            print("[ViewpointLivingProps] could not hide the 2D stove lights: " .. tostring(res))
        end
    end
    flag2D = true
    print("[ViewpointLivingProps] 2D stove lights hidden on " .. n .. " sprites")
end

-- hidden = true: Viewpoint look (flags taken away); false: the game's own look (flags given back to the sprites that had them)
local function set2D(hidden)
    if not HIDE_2D_STOVE_LIGHTS then return false end
    if flag2D == nil then
        if hidden then hideAll2D() return true end
        return false
    end
    if flag2D == hidden then return false end
    flag2D = hidden
    for name in pairs(had2D) do
        pcall(function()
            local spr = getSprite(name)
            local props = spr and spr:getProperties()
            if not props then return end
            if hidden then props:unset(IsoFlagType.HasLightOnSprite) else props:set(IsoFlagType.HasLightOnSprite) end
        end)
    end
    return true
end

Events.OnLoadedTileDefinitions.Add(hideAll2D)
Events.OnGameStart.Add(hideAll2D)

-- ---------------------------------------------------------------- stove on/off -> overlay sprite
local function stoveId(o)
    local n = nameOf(o:getSprite())
    if not n or n:sub(1, #STOVE_PREFIX) ~= STOVE_PREFIX then return nil end
    local id = tonumber(n:sub(#STOVE_PREFIX + 1))
    if id and STOVE_IDS[id] then return id end
    return nil
end

-- overlay sprite name that shows this (non-stove) object lit, or nil when it has none
local function litName(o)
    return LIT_NAMES[nameOf(o:getSprite()) or ""]
end

local function siblingActive(o, id)
    local p = PAIRS[id]
    if not p then return false end
    local sq = o:getSquare()
    if not sq then return false end
    local s = getCell():getGridSquare(sq:getX() + p[2], sq:getY() + p[3], sq:getZ())
    if not s then return false end
    local want = STOVE_PREFIX .. p[1]
    local objs = s:getObjects()
    for i = 0, objs:size() - 1 do
        local so = objs:get(i)
        if instanceof(so, "IsoStove") and nameOf(so:getSprite()) == want and so:Activated() then return true end
    end
    return false
end

-- wood stove / barbecue: lit state -> overlay sprite
local function syncLit(o, want)
    local cur = nameOf(o:getOverlaySprite())
    if o:isLit() then
        if cur ~= want then P.count("overlay changes (appliances)") o:setOverlaySprite(want, false) end
    elseif cur == want then
        P.count("overlay changes (appliances)") o:setOverlaySprite("", false)
    end
end

local function isOurOverlay(cur)
    return cur and (cur:sub(1, #STOVE_ON_PREFIX) == STOVE_ON_PREFIX or cur:sub(1, 9) == "lp_flame_" or cur:sub(1, 8) == "lp_spin_")
end

local function nearPlayer(o)
    local p = getPlayer()
    local sq = o:getSquare()
    if not p or not sq then return false end
    return math.abs(p:getX() - sq:getX()) <= ANIM_RANGE and math.abs(p:getY() - sq:getY()) <= ANIM_RANGE
end

-- is the object inside what Viewpoint's camera shows? (asks the Java helper; if it is not there, only the distance rule is used)
local viewApi = nil
local viewApiTries = 0
local function getViewApi()
    if viewApi then return viewApi end
    if viewApiTries < 50 then             -- not there yet (helper still loading)? ask again later, but not forever
        viewApiTries = viewApiTries + 1
        local ok, api = pcall(function() return ViewpointLivingProps and ViewpointLivingProps.View end)
        if ok and api and api.sees then viewApi = api end
    end
    return viewApi
end

local function onScreen(o)
    if not ONSCREEN_ONLY then return true end
    local api = getViewApi()
    if not api then return true end
    local ok, res = pcall(function()
        local sq = o:getSquare()
        if not sq then return false end
        return api.sees(sq:getX() + 0.5, sq:getY() + 0.5, sq:getZ() + 0.4)
    end)
    if ok then return res end
    return true
end

-- ---------------------------------------------------------------- a stove that is on lights its surroundings a little
-- The game gives stoves no light of their own. A light source is added to the world while the stove is on and taken away when
-- it is off, picked up or no longer loaded. Project Viewpoint reads the game's list of light sources, so it shows up there too.
local lamps = {}                   -- stove object -> the light source added for it

local function isStillThere(o)
    local sq = o:getSquare()
    if not sq then return false end
    local cur = getCell():getGridSquare(sq:getX(), sq:getY(), sq:getZ())
    if not cur then return false end
    local objs = cur:getObjects()
    for i = 0, objs:size() - 1 do
        if objs:get(i) == o then return true end
    end
    return false
end

local function dropLamp(o)
    local ls = lamps[o]
    if not ls then return end
    lamps[o] = nil
    pcall(function() getCell():removeLamppost(ls) end)
    if DEBUG then pcall(function() print("[ViewpointLivingProps] stove light OFF @" .. ls:getX() .. "," .. ls:getY() .. "," .. ls:getZ()) end) end
end

-- every stove / oven of the game gives light, but not the microwaves and toasters (ids 24..33 of the cooking sheet)
local function givesLight(o)
    local n = nameOf(o:getSprite())
    if not n or n:sub(1, #STOVE_PREFIX) ~= STOVE_PREFIX then return false end
    local id = tonumber(n:sub(#STOVE_PREFIX + 1))
    -- only the stoves and ovens that show glowing burners (the ones with a 3D "on" model); not microwaves, toasters,
    -- coffee machines or the commercial fryers, which have no lit picture and must not light the room
    return id ~= nil and STOVE_IDS[id] == true and not SPIN_IDS[id]
end

-- Is Project Viewpoint's own view showing right now? (asked from the Java helper)
-- true = Viewpoint's view, false = the game's normal isometric view, nil = cannot tell.
local function viewState()
    return ViewpointLivingPropsIsActive()
end

local function isMicrowave(o)
    local n = nameOf(o:getSprite())
    if not n or n:sub(1, #STOVE_PREFIX) ~= STOVE_PREFIX then return false end
    local id = tonumber(n:sub(#STOVE_PREFIX + 1))
    return id ~= nil and SPIN_IDS[id] == true
end

local function syncLight(o, vpOn)
    if not instanceof(o, "IsoStove") then return end
    local micro = MICRO_LIGHT and isMicrowave(o)
    if not micro and not STOVE_LIGHT then return end
    local want = vpOn and (micro or givesLight(o)) and o:Activated() and isStillThere(o)
    if want then
        if not lamps[o] then
            local sq = o:getSquare()
            if micro then
                lamps[o] = getCell():addLamppost(sq:getX(), sq:getY(), sq:getZ(), MICRO_R, MICRO_G, MICRO_B, MICRO_RADIUS)
            else
                lamps[o] = getCell():addLamppost(sq:getX(), sq:getY(), sq:getZ(), LIGHT_R, LIGHT_G, LIGHT_B, LIGHT_RADIUS)
            end
            if DEBUG then print("[ViewpointLivingProps] " .. (micro and "microwave" or "stove") .. " light ON @" .. sq:getX() .. "," .. sq:getY() .. "," .. sq:getZ()) end
        end
    else
        dropLamp(o)
    end
end

local swaps = 0                     -- picture changes since the last report (debug)

-- Animation pictures are loaded only when a stove / microwave is switched ON (never at start-up, so the mod's loading time
-- does not grow): the Java helper asks Project Viewpoint to load every picture of the animation, and until all of them are
-- ready the plain "on" picture stays. Only then does the animation start, so no picture is missing for a moment (= no blinking).
local framesState = {}                -- id -> { ok = all loaded?, t = time of the last check (ms) }
local function framesReady(id, now)
    local st = framesState[id]
    if st then
        local age = now - st.t
        if st.ok and age < 1000 then return true end        -- everything was ready a moment ago
        if not st.ok and age < 200 then return false end    -- still loading: do not ask every poll
    end
    local ok = false           -- no way to check that the pictures are loaded (Java helper missing / not approved) = no animation, so it cannot blink
    local api = getViewApi()
    if api and api.preload then
        local names = STOVE_ON_PREFIX .. id
        if FLAME_IDS[id] then
            names = names .. "," .. FLAME_PREFIX[2] .. id .. "," .. FLAME_PREFIX[3] .. id
        elseif SPIN_IDS[id] then
            for k = 2, SPIN_FRAMES do names = names .. ",lp_spin_" .. k .. "_" .. id end
        end
        local okc, res = P.pcall("preload", api.preload, names)
        ok = (not okc) or res == true
    end
    framesState[id] = { ok = ok, t = now }
    return ok
end

local function wantedOn(o, id, now)
    local flame = FLAME_IDS[id]
    local spin = SPIN_IDS[id]
    if now and ANIM_MS > 0 and (flame or spin) and nearPlayer(o) and onScreen(o) and framesReady(id, now) then
        local n = math.floor(now / ANIM_MS)
        if flame then
            local pre = FLAME_PREFIX[SEQUENCE[(n % #SEQUENCE) + 1]]
            if pre then return pre .. id end
        elseif spin then
            local k = n % SPIN_FRAMES + 1
            if k > 1 then return "lp_spin_" .. k .. "_" .. id end
        end
    end
    return STOVE_ON_PREFIX .. id
end

local function syncStove(o, now, iso)
    if not instanceof(o, "IsoStove") then
        local want = litName(o)
        if not want then return end
        if iso then
            -- the game's own look: the lit pictures the game puts on itself (wood stove) are left alone; the ones only this mod put on are taken off
            if not GAME_LIT[nameOf(o:getSprite()) or ""] and nameOf(o:getOverlaySprite()) == want then
                P.count("overlay changes (appliances)") o:setOverlaySprite("", false)
            end
            return
        end
        syncLit(o, want)
        return
    end
    local id = stoveId(o)
    if not id then return end
    local cur = nameOf(o:getOverlaySprite())
    if iso then
        -- isometric view: the game draws the lit stove by itself, so the picture this mod hung on it is taken off again
        if isOurOverlay(cur) then P.count("overlay changes (appliances)") o:setOverlaySprite("", false) end
        return
    end
    if o:Activated() or siblingActive(o, id) then
        local want = wantedOn(o, id, now)
        if cur ~= want then
            P.count("overlay changes (appliances)") o:setOverlaySprite(want, false)
            swaps = swaps + 1
        end
    elseif isOurOverlay(cur) then
        P.count("overlay changes (appliances)") o:setOverlaySprite("", false)
    end
end

-- the view was switched (Viewpoint <-> isometric): make the object redraw, and drop the game's own flat "lit" picture when
-- Viewpoint is showing again (it would not be drawn there anyway, but it must not be left over)
local function viewChanged(o, iso)
    if not instanceof(o, "IsoStove") then
        pcall(function() o:invalidateRenderChunkLevel(256) end)
        return
    end
    if not iso then pcall(function() o:setOnOverlay(nil) end) end
    pcall(function() o:invalidateRenderChunkLevel(256) end)
end

local function scanNear()
    local p = getPlayer()
    if not p then return end
    local sq = p:getSquare()
    if not sq then return end
    local cell = getCell()
    local R = 12
    for dx = -R, R do
        for dy = -R, R do
            local s = cell:getGridSquare(sq:getX() + dx, sq:getY() + dy, sq:getZ())
            if s then
                local objs = s:getObjects()
                for i = 0, objs:size() - 1 do
                    local o = objs:get(i)
                    if not tracked[o] and (instanceof(o, "IsoStove") or ((instanceof(o, "IsoFireplace") or instanceof(o, "IsoBarbecue")) and litName(o))) then tracked[o] = true end
                end
            end
        end
    end
end

local lastState = {}
local function update()
    local now = nil
    pcall(function() now = getTimestampMs() end)
    local st = viewState()
    local vpOn = (st == true)               -- the stove light needs a sure "yes"
    local iso = (st == false)               -- back to the game's own look only on a sure "isometric"
    local changed = set2D(not iso)
    for o in pairs(tracked) do
        if o:getSquare() == nil or not isStillThere(o) then
            tracked[o] = nil
            lastState[o] = nil
            dropLamp(o)
        else
            if changed then pcall(viewChanged, o, iso) end
            pcall(syncLight, o, vpOn)
            local ok, err = pcall(syncStove, o, now, iso)
            if not ok and not warned then
                warned = true
                print("[ViewpointLivingProps] stove overlay: " .. tostring(err))
            end
            if DEBUG then
                local isOn = instanceof(o, "IsoStove") and o:Activated() or (not instanceof(o, "IsoStove") and o:isLit())
                local ov = nameOf(o:getOverlaySprite())
                if isOurOverlay(ov) then ov = "ON(model)" end      -- the flame shapes change all the time: do not log each one
                local key = tostring(nameOf(o:getSprite())) .. " on=" .. tostring(isOn) .. " overlay=" .. tostring(ov)
                if lastState[o] ~= key then
                    lastState[o] = key
                    local sq = o:getSquare()
                    print("[ViewpointLivingProps] stove@" .. sq:getX() .. "," .. sq:getY() .. " " .. key)
                end
            end
        end
    end
end

-- (timed only when ViewpointLivingProps_perf.txt exists)
scanNear = P.fn("Appliances.scanNear", scanNear)
update = P.fn("Appliances.update", update)
Events.OnTick.Add(function()
    ticks = ticks + 1
    if ticks % 6 == 0 then pcall(update) end
    if ticks % 90 == 1 then pcall(scanNear) end
    if DEBUG and ticks % 600 == 0 and swaps > 0 then       -- how much animating is going on (a lot = more frames lost)
        print("[ViewpointLivingProps] animation: " .. swaps .. " picture changes in the last ~10 s")
        swaps = 0
    end
end)

-- which "is it on screen" test is in use (shown once in the log)
Events.OnGameStart.Add(function()
    local api = getViewApi()
    local st = "no Java helper (distance rule only)"
    if api then
        local ok, res = pcall(function() return api.status() end)
        st = ok and tostring(res) or ("error: " .. tostring(res))
    end
    print("[ViewpointLivingProps] animations: range " .. ANIM_RANGE .. " tiles, on-screen test: " .. st)
end)

-- ---------------------------------------------------------------- diagnostic for TVs (console only)
if DEBUG then
    local lastTv = {}
    local function scanTv()
        local p = getPlayer()
        if not p then return end
        local sq = p:getSquare()
        if not sq then return end
        local cell = getCell()
        for dx = -6, 6 do
            for dy = -6, 6 do
                local s = cell:getGridSquare(sq:getX() + dx, sq:getY() + dy, sq:getZ())
                if s then
                    local objs = s:getObjects()
                    for i = 0, objs:size() - 1 do
                        local o = objs:get(i)
                        if instanceof(o, "IsoTelevision") then
                            local dd = o:getDeviceData()
                            local on = dd and dd:getIsTurnedOn() or false
                            local key = tostring(nameOf(o:getSprite())) .. " on=" .. tostring(on) .. " overlay=" .. tostring(nameOf(o:getOverlaySprite()))
                            local id = "tv@" .. s:getX() .. "," .. s:getY()
                            if lastTv[id] ~= key then
                                lastTv[id] = key
                                print("[ViewpointLivingProps] " .. id .. " " .. key)
                            end
                        end
                    end
                end
            end
        end
    end
    local t = 0
    Events.OnTick.Add(function()
        t = t + 1
        if t % 45 == 0 then pcall(scanTv) end
    end)
end

-- ---------------------------------------------------------------- start-up note
-- If the game did not run this script (a server that does not list the mod), the small Java helper that comes with
-- the mod runs it instead, after the tile definitions were already loaded: so switch the 2D lights off right away.
if ViewpointLivingPropsJava then pcall(hideAll2D) end
print("[ViewpointLivingProps] appliances script running (started by "
    .. (ViewpointLivingPropsJava and "the Java helper" or "the game") .. ", client=" .. tostring(isClient()) .. ")")
P.tag(nil)
