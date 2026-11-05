-- Viewpoint: Living Props - wall light switches (lighting_indoor_01_0 .. 7).
--
-- The switch plate is the sprite's 3D model. The lever and the little red light are drawn by an "overlay" sprite that this
-- script sets on the switch, from the switch's own state:
--   lever UP                 = the light is ON
--   lever DOWN               = the light is OFF
--   lever DOWN + red light   = OFF while the building has power: the little light that helps you find the switch in the dark.
--
-- The little red light GLOWS: in the dark you see only that small red lens, shining on its own (like a firefly / a glow-in-
-- the-dark switch), without lighting the wall, the ceiling or the room. Project Viewpoint cannot make a 3D surface shine
-- (everything is lit by the room's light, and a real lamp always sits near the ceiling), so the glow is drawn on the screen,
-- exactly over the lens, using Viewpoint's own world -> screen position through the Java helper. It only shows when you can
-- see the switch and the place is dark. Nothing is sent to the server and no game or mod file is changed.
-- (A real, weak lamp is also available, off by default: RED_LIGHT = true.)

local P = ViewpointLivingPropsPerf or { tag = function() end, fn = function(_, f) return f end, pcall = function(_, f, ...) return pcall(f, ...) end, count = function() end, gauge = function() end }
P.tag("Switches")

local SW_FIRST, SW_LAST = 0, 7
local PREFIX = "lighting_indoor_01_"
local ON_PREFIX = "lp_swon_"
local OFF_PREFIX = "lp_swoff_"
local LED_PREFIX = "lp_swled_"
local HIDE_2D = true               -- the old flat "light on" drawing is switched off for the switches that have a 3D model

-- The glow drawn on the screen over the red lens.
local GLOW = true                  -- false = no glow (only the red lens of the 3D model remains)
local LENS_TEX = "media/ui/ViewpointLivingProps_lens.png"   -- the bright lens
local HALO_TEX = "media/ui/ViewpointLivingProps_glow.png"   -- the soft red halo around it
local LENS_W, LENS_H = 0.045, 0.061    -- size of the lens in tiles (the model's lens)
local LENS_GROW = 1.25                 -- the bright lens is drawn this much bigger than the model's lens
local HALO_GROW = 3.2                  -- the halo is this many times the lens (smaller = a tighter glow)
local GLOW_STRENGTH = 1.0              -- 0..1, how bright at most (halo is a bit weaker)
local MIN_LENS_PX = 3                  -- from far away it never gets smaller than this many pixels
local RANGE_FULL, RANGE_NONE = 5, 13   -- tiles: full brightness up to RANGE_FULL, fades out and is gone at RANGE_NONE
local MAX_FLOOR_DIFF = 2                 -- a switch up to this many floors above/below you can glow (when you can see it)
-- Room brightness at the switch (0..1) as Project Viewpoint draws it (asked through the Java helper): at or below DARK_FULL = full
-- glow, at or above DARK_NONE = no glow. A room that still has light (lamp on, daylight) shows no glow.
local DARK_FULL, DARK_NONE = 0.05, 0.20
-- Same idea with the game's own, rougher light value; only used when Viewpoint's value is not available.
local GAME_DARK_FULL, GAME_DARK_NONE = 0.12, 0.40

-- An extra real light on the switch (not the glow). It always lights the ceiling and the wall around, so it is off.
-- RED_LEVEL = how strong (0.10 = barely there), RED_RADIUS = how many tiles it reaches (1 = smallest).
local RED_LIGHT = false
local RED_LEVEL = 0.05
local RED_RADIUS = 1

-- Extra lines in console.txt (to see why a glow is or is not drawn). Off normally; it is switched on by an empty text file called
-- ViewpointLivingProps_debug_switch.txt in the Zomboid\Lua folder (NOT the general ViewpointLivingProps_debug.txt, which is for the contents overlays).
local function debugFlag()
    local ok, r = pcall(getFileReader, "ViewpointLivingProps_debug_switch.txt", false)
    if ok and r then
        pcall(function() r:close() end)
        return true
    end
    return false
end
local DEBUG = debugFlag()

-- The little red light can be switched OFF: create an empty text file called ViewpointLivingProps_switch_led_off.txt in the
-- Zomboid\Lua folder and restart the game. The switch then looks the same when the building has power as when it has none
-- (lever down, no red lens, no glow). Delete the file to get the light back. Only affects Project Viewpoint's 3D view.
local function ledOffFlag()
    local ok, r = pcall(getFileReader, "ViewpointLivingProps_switch_led_off.txt", false)
    if ok and r then
        pcall(function() r:close() end)
        return true
    end
    return false
end
local LED_OFF = ledOffFlag()

-- Where the little red light is on each switch sprite: x, y = offset from the tile centre (in tiles), height above the floor
-- (tiles), and the direction the plate faces (nx, ny).
local LED = {
    [0] = { 0.265, -0.4523, 1.327, 0, 1 },
    [1] = { -0.4523, 0.2962, 1.327, 1, 0 },
    [2] = { 0.4523, -0.3625, 1.327, -1, 0 },
    [3] = { -0.39, 0.4523, 1.327, 0, -1 },
    [4] = { -0.3587, -0.4523, 1.327, 0, 1 },
    [5] = { -0.4523, -0.3275, 1.327, 1, 0 },
    [6] = { 0.2312, 0.4523, 1.327, 0, -1 },
    [7] = { 0.4523, 0.2613, 1.327, -1, 0 }
}

local tracked = {}                 -- switches found near the player
P.gauge("Switches.tracked", function() local n = 0 for _ in pairs(tracked) do n = n + 1 end return n end)
local glowing = {}                 -- switches whose little light is on right now: switch -> sprite id
local lamps = {}                   -- switch -> its light source
local ticks = 0
local warned = false

local function nameOf(spr) return spr and spr:getName() or nil end
local function clamp(v, a, b) if v < a then return a elseif v > b then return b end return v end

-- ---------------------------------------------------------------- 2D light off (Viewpoint look) / back on (isometric)
-- The flat "light on" drawing of a switch is taken away only while Project Viewpoint's own view is showing. In the game's
-- normal isometric view the sprites get it back, so that view stays exactly as the game has it.
local had2D = {}                   -- sprite name -> true when the sprite HAD the flag before this mod took it away
local flag2D = nil                 -- true = taken away (Viewpoint look), false = given back (the game's own look)
local viewState = nil              -- function defined further down (next to the Java helper link): true / false / nil

local function hideAll2D()
    if not HIDE_2D or not ViewpointLivingPropsIsActive() then return end
    local n = 0
    for id = SW_FIRST, SW_LAST do
        local name = PREFIX .. id
        local spr = getSprite(name)
        local props = spr and spr:getProperties()
        if props then
            pcall(function()
                if props:has(IsoFlagType.HasLightOnSprite) then had2D[name] = true end
                props:unset(IsoFlagType.HasLightOnSprite)
            end)
            n = n + 1
        end
    end
    flag2D = true
    print("[ViewpointLivingProps] 2D switch lights hidden on " .. n .. " sprites")
end

-- hidden = true: Viewpoint look; false: the game's own look. Returns true when something was switched.
local function set2D(hidden)
    if not HIDE_2D then return false end
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

-- ---------------------------------------------------------------- state -> overlay (+ glow on/off)
local function switchId(o)
    local n = nameOf(o:getSprite())
    if not n or n:sub(1, #PREFIX) ~= PREFIX then return nil end
    local id = tonumber(n:sub(#PREFIX + 1))
    if id and id >= SW_FIRST and id <= SW_LAST then return id end
    return nil
end

local function isPowered(o)
    local sq = o:getSquare()
    if not sq then return false end
    local ok, res = pcall(function() return sq:haveElectricity() or o:hasGridPower() end)
    if ok then return res and true or false end
    return true
end

local function setLamp(o, on)
    local cell = getCell()
    local cur = lamps[o]
    if on and RED_LIGHT then
        if not cur then
            local sq = o:getSquare()
            if sq and cell then
                lamps[o] = cell:addLamppost(sq:getX(), sq:getY(), sq:getZ(), RED_LEVEL, RED_LEVEL * 0.06, RED_LEVEL * 0.03, RED_RADIUS)
            end
        end
    elseif cur then
        if cell then cell:removeLamppost(cur) end
        lamps[o] = nil
    end
end

local function overlayFor(o, id)
    if o:isActivated() then return ON_PREFIX .. id, false end
    if isPowered(o) and not LED_OFF then return LED_PREFIX .. id, true end
    return OFF_PREFIX .. id, false
end

local function syncSwitch(o)
    if not ViewpointLivingPropsIsActive() then
        local cur = nameOf(o:getOverlaySprite())
        if cur and (cur:sub(1, #ON_PREFIX) == ON_PREFIX or cur:sub(1, #OFF_PREFIX) == OFF_PREFIX or cur:sub(1, #LED_PREFIX) == LED_PREFIX) then
            P.count("overlay changes (switches)")
            o:setOverlaySprite(nil, false)
        end
        glowing[o] = nil
        setLamp(o, false)
        return
    end
    local id = switchId(o)
    if not id then return end
    local want, led = overlayFor(o, id)
    if nameOf(o:getOverlaySprite()) ~= want then P.count("overlay changes (switches)") o:setOverlaySprite(want, false) end
    glowing[o] = led and id or nil
    pcall(setLamp, o, led)
end

local scans = 0
local function scanNear()
    local p = getPlayer()
    if not p then return end
    local sq = p:getSquare()
    if not sq then return end
    local cell = getCell()
    local R = 14
    -- your own floor every time; the other floors (up to MAX_FLOOR_DIFF away) take turns, one per scan, to keep this cheap
    scans = scans + 1
    local levels = { 0 }
    if MAX_FLOOR_DIFF > 0 then
        local k = (scans - 1) % (2 * MAX_FLOOR_DIFF) + 1                 -- 1 .. 2*MAX
        local other = math.floor((k + 1) / 2)                                       -- 1,1,2,2,...
        levels[2] = (k % 2 == 1) and other or -other
    end
    for _, dz in ipairs(levels) do
        local z = sq:getZ() + dz
        if z >= -32 and z <= 31 then
            for dx = -R, R do
                for dy = -R, R do
                    local s = cell:getGridSquare(sq:getX() + dx, sq:getY() + dy, z)
                    if s then
                        local objs = s:getObjects()
                        for i = 0, objs:size() - 1 do
                            local o = objs:get(i)
                            if not tracked[o] and instanceof(o, "IsoLightSwitch") and switchId(o) then tracked[o] = true end
                        end
                    end
                end
            end
        end
    end
end

-- the view was switched (Viewpoint <-> isometric): make the switch redraw; when Viewpoint is showing again, drop the game's own
-- flat "light on" picture so nothing is left over
local function viewChanged(o, iso)
    if not iso then pcall(function() o:setOnOverlay(nil) end) end
    pcall(function() o:invalidateRenderChunkLevel(256) end)
end

local lastState = {}
local function update()
    local st = viewState()
    local iso = (st == false)                 -- back to the game's own look only on a sure "isometric"
    local changed = set2D(not iso)
    for o in pairs(tracked) do
        if o:getSquare() == nil then
            tracked[o] = nil
            lastState[o] = nil
            glowing[o] = nil
            pcall(setLamp, o, false)
        else
            if changed then pcall(viewChanged, o, iso) end
            local ok, err = pcall(syncSwitch, o)
            if not ok and not warned then
                warned = true
                print("[ViewpointLivingProps] switch: " .. tostring(err))
            end
            if DEBUG then
                local key = tostring(nameOf(o:getSprite())) .. " on=" .. tostring(o:isActivated()) .. " powered=" .. tostring(isPowered(o)) ..
                    " overlay=" .. tostring(nameOf(o:getOverlaySprite())) .. " light=" .. tostring(lamps[o] ~= nil)
                if lastState[o] ~= key then
                    lastState[o] = key
                    local sq = o:getSquare()
                    print("[ViewpointLivingProps] switch@" .. sq:getX() .. "," .. sq:getY() .. " " .. key)
                end
            end
        end
    end
end

-- (timed only when ViewpointLivingProps_perf.txt exists)
scanNear = P.fn("Switches.scanNear", scanNear)
update = P.fn("Switches.update", update)
Events.OnTick.Add(function()
    ticks = ticks + 1
    if ticks % 10 == 0 then pcall(update) end
    if ticks % 90 == 7 then pcall(scanNear) end
end)

-- ---------------------------------------------------------------- the glow, drawn on the screen
local viewApi = nil
local viewApiTries = 0
local function getViewApi()
    if viewApi then return viewApi end
    if viewApiTries < 50 then
        viewApiTries = viewApiTries + 1
        local ok, api = pcall(function() return ViewpointLivingProps and ViewpointLivingProps.View end)
        if ok and api and api.project then viewApi = api end
    end
    return viewApi
end

-- Is Project Viewpoint's own view showing right now? true = Viewpoint, false = the game's isometric view, nil = cannot tell.
viewState = function()
    return ViewpointLivingPropsIsActive()
end

local texCache = {}
local function getTex(path)
    local t = texCache[path]
    if t == nil then
        local ok, r = pcall(getTexture, path)
        t = (ok and r) or false
        if not t then
            -- On a server that does not list this mod the game does not know the mod's folder, so "media/ui/..." finds
            -- nothing. The Java helper knows where the mod really is: ask it for the full path and load the picture from there.
            local api = getViewApi()
            if api and api.modFile then
                local ok2, full = pcall(api.modFile, path)
                if ok2 and full then
                    local ok3, r3 = pcall(getTexture, full)
                    if ok3 and r3 then
                        t = r3
                        print("[ViewpointLivingProps] glow picture loaded from the mod folder: " .. tostring(full))
                    end
                end
            end
        end
        texCache[path] = t
        if not t then print("[ViewpointLivingProps] glow picture not found (" .. path .. "): using plain squares") end
    end
    return t or nil
end

-- no halo picture available: a few nested, very faint squares instead of one hard square
local function softSquares(el, cx, cy, w, h, a)
    for i = 0, 5 do
        local f = 1 - i / 6
        el:drawRect(cx - w * f / 2, cy - h * f / 2, w * f, h * f, a * 0.07, 1, 0.1, 0.05)
    end
end

-- how bright the place around the switch is, 0 (dark) .. 1 (bright)
-- First choice: the light Project Viewpoint really draws on that tile (through the Java helper). It already includes room lamps,
-- daylight, closed doors and curtains. If that is not available, the game's own value (rougher, it can be empty for a moment).
-- (An error inside pcall is still written to console.txt with a long stack trace, and this runs every frame, so nil is checked.)
local function gameLight(sq, pi)
    local ok, v = pcall(function()
        local c = sq:getLightInfo(pi)
        if c == nil then return nil end
        return math.max(c:getR(), c:getG(), c:getB())
    end)
    if ok and v then return v end
    ok, v = pcall(function() return sq:getLightLevel(pi) end)
    if ok and v then return v end
    return nil
end

-- returns brightness, and how much of it still counts as "dark enough" (0..1)
local function darkness(sq, pi, api)
    if api.lightAt then
        local ok, v = pcall(api.lightAt, sq:getX() + 0.5, sq:getY() + 0.5, sq:getZ())
        if ok and v and v >= 0 then
            return v, clamp((DARK_NONE - v) / (DARK_NONE - DARK_FULL), 0, 1), "vp"
        end
    end
    local v = gameLight(sq, pi)
    if v == nil then return 1, 0, "unknown" end        -- no idea how bright it is: better no glow than a glow in a lit room
    return v, clamp((GAME_DARK_NONE - v) / (GAME_DARK_NONE - GAME_DARK_FULL), 0, 1), "game"
end

-- The player's OWN light (flashlight beam, headlamp, lantern, lighter...). The brightness above is the light of the place and does
-- not include it, so the glow used to stay lit while a torch shone straight on the switch. It uses the same facts Project Viewpoint
-- uses to draw the beam: the active light items, the reach (at least 4 tiles for a cone) and the cone (cosine kept between 0.2 and
-- 0.97) around the direction you face. Read once per frame, only when there is a glow to draw. If anything fails it is reported once
-- and the glow just ignores the torch.
local torchList = nil
local torchBroken = false
local function torchSources(p)
    if torchBroken then return nil end
    local ok, res = pcall(function()
        if not torchList then torchList = ArrayList.new() end
        torchList:clear()
        p:getActiveLightItems(torchList)
        local n = torchList:size()
        if n == 0 then return nil end
        local fx, fy = p:getForwardDirectionX(), p:getForwardDirectionY()
        local fl = math.sqrt(fx * fx + fy * fy)
        local out = { fx = 0, fy = 0 }
        if fl > 0.001 then out.fx, out.fy = fx / fl, fy / fl end
        for i = 0, n - 1 do
            local it = torchList:get(i)
            if it then
                local cone = it:isTorchCone()
                out[#out + 1] = { cone = cone and fl > 0.001, range = math.max(cone and 4 or 2, it:getLightDistance()),
                    cos = clamp(it:getTorchDot(), 0.2, 0.97) }
            end
        end
        if #out == 0 then return nil end
        return out
    end)
    if not ok then
        torchBroken = true
        print("[ViewpointLivingProps] switch glow: could not read the player's torch (" .. tostring(res) .. "); the glow will ignore it")
        return nil
    end
    return res
end

-- 0..1: how much the player's own light shines on the switch lens at cx, cy (the player is at px, py)
local function torchLit(tc, px, py, cx, cy)
    if not tc then return 0 end
    local dx, dy = cx - px, cy - py
    local len = math.sqrt(dx * dx + dy * dy)
    local best = 0
    for i = 1, #tc do
        local t = tc[i]
        local v
        if t.cone then
            if len < 0.3 then
                v = 1
            else
                local cosang = (dx * tc.fx + dy * tc.fy) / len
                v = clamp((cosang - (t.cos - 0.08)) / 0.10, 0, 1) * clamp((t.range - len) / (0.3 * t.range), 0, 1)
            end
        else
            v = clamp((t.range - len) / t.range, 0, 1) * 0.9          -- a lantern / lighter: a ball of light around you
        end
        if v > best then best = v end
    end
    return best
end

local function clearLine(px, py, pz, sq)
    local ok, res = pcall(function()
        return LosUtil.lineClear(getCell(), math.floor(px), math.floor(py), pz, sq:getX(), sq:getY(), sq:getZ(), false)
    end)
    if not ok then return nil end
    return tostring(res):find("Clear") ~= nil
end

local lastLog = 0
local loggedAt = {}
local function drawGlow(el)
    if not GLOW then return end
    local api = getViewApi()
    if not api or not api.canProject() then return end
    if api.active and not api.active() then return end        -- back in the game's normal isometric view: no glow
    local p = getPlayer()
    if not p then return end
    local pi = p:getPlayerNum()
    local px, py, pz = p:getX(), p:getY(), math.floor(p:getZ())
    local core = getCore()
    local sw, sh = core:getScreenWidth(), core:getScreenHeight()
    local lens, halo = getTex(LENS_TEX), getTex(HALO_TEX)
    local now = DEBUG and getTimestampMs() or 0
    local log = DEBUG and now - lastLog > 3000
    local hw, hh = LENS_W / 2, LENS_H / 2
    local tc, tcDone = nil, false
    for o, id in pairs(glowing) do
        local sq = o:getSquare()
        local d = LED[id]
        if sq and d then
            local cx, cy, cz, up = sq:getX() + 0.5 + d[1], sq:getY() + 0.5 + d[2], sq:getZ(), d[3]
            local dx, dy = px - cx, py - cy
            local dist = math.sqrt(dx * dx + dy * dy)
            local a = clamp((RANGE_NONE - dist) / (RANGE_NONE - RANGE_FULL), 0, 1)
            local front = (dx * d[4] + dy * d[5]) > 0           -- you are on the side of the wall the switch faces
            local seen, lum, shownAt, src = false, 0, "", "-"
            local gone = ""
            -- a switch on another floor still glows when you can really see it (through a stairwell, an open ceiling...): that is
            -- decided below by the line of sight (which also counts floors) and by the room test. Only far-away floors are left out.
            -- Never in a room Viewpoint is not drawing (its plate is not drawn either).
            if math.abs(sq:getZ() - pz) > MAX_FLOOR_DIFF then
                if a > 0 and front then gone = " (floor too far)" end
                a = 0
            elseif a > 0 and front and api.hidden and api.hidden(sq:getX() + 0.5, sq:getY() + 0.5, sq:getZ()) then
                a = 0
                gone = " (room hidden by Viewpoint)"
            end
            if a > 0 and front then
                local cs = false
                local okc, r = pcall(function() return sq:isCouldSee(pi) end)
                if okc and r then cs = true end
                seen = cs or (clearLine(px, py, pz, sq) == true)
                if seen then
                    local dk
                    lum, dk, src = darkness(sq, pi, api)
                    if not tcDone then tcDone = true; tc = torchSources(p) end      -- the player's own torch / lantern
                    local tl = torchLit(tc, px, py, cx, cy)
                    if tl > 0 then
                        dk = dk * (1 - tl)
                        src = src .. string.format("+torch%.2f", tl)
                    end
                    a = a * dk * GLOW_STRENGTH
                    if a > 0.02 and api.project(cx, cy, cz, up) then
                        local x, y = api.screenX(), api.screenY()
                        -- the four corners of the lens (along the wall, d[5]/d[4] turned a quarter) -> its box on the screen
                        local tx, ty = -d[5], d[4]
                        local x0, y0, x1, y1 = x, y, x, y
                        for _, c in ipairs({ { 1, 1 }, { 1, -1 }, { -1, 1 }, { -1, -1 } }) do
                            if api.project(cx + tx * hw * c[1], cy + ty * hw * c[1], cz, up + hh * c[2]) then
                                local sx, sy = api.screenX(), api.screenY()
                                if sx < x0 then x0 = sx elseif sx > x1 then x1 = sx end
                                if sy < y0 then y0 = sy elseif sy > y1 then y1 = sy end
                            end
                        end
                        local w, h = x1 - x0, y1 - y0
                        if w < MIN_LENS_PX then w = MIN_LENS_PX end
                        if h < MIN_LENS_PX * 1.3 then h = MIN_LENS_PX * 1.3 end
                        if x > -200 and x < sw + 200 and y > -200 and y < sh + 200 then
                            local hwid, hhei = w * HALO_GROW, h * HALO_GROW
                            local lwid, lhei = w * LENS_GROW, h * LENS_GROW
                            if halo then el:drawTextureScaled(halo, x - hwid / 2, y - hhei / 2, hwid, hhei, a * 0.55, 1, 1, 1)
                            else softSquares(el, x, y, hwid, hhei, a) end
                            if lens then el:drawTextureScaled(lens, x - lwid / 2, y - lhei / 2, lwid, lhei, a, 1, 1, 1)
                            else el:drawRect(x - lwid / 2, y - lhei / 2, lwid, lhei, a, 1, 0.15, 0.08) end
                            shownAt = string.format(" at %d,%d lens %dx%d", math.floor(x), math.floor(y), math.floor(lwid), math.floor(lhei))
                        end
                    end
                end
            end
            if DEBUG and (shownAt ~= "" or gone ~= "") then          -- every glow that is drawn (or held back), once per 3 s each
                local k = sq:getX() .. "," .. sq:getY()
                if now - (loggedAt[k] or 0) > 3000 then
                    loggedAt[k] = now
                    print(string.format("[ViewpointLivingProps] glow DRAWN switch@%s z=%d id=%d player=%d,%d,%d dist=%.1f light=%.2f(%s) a=%.2f%s%s",
                        k, sq:getZ(), math.floor(id), math.floor(px), math.floor(py), pz, dist, lum, src, a, shownAt, gone))
                end
            end
            if log then
                lastLog = now
                log = false
                print(string.format("[ViewpointLivingProps] glow switch@%d,%d dist=%.1f front=%s seen=%s light=%.2f(%s) a=%.2f%s",
                    sq:getX(), sq:getY(), dist, tostring(front), tostring(seen), lum, src, a, shownAt))
            end
        end
    end
end

local layer = nil
local function makeLayer()
    if not GLOW or not ISUIElement then return end
    if layer then pcall(function() layer:removeFromUIManager() end) end
    local el = ISUIElement:new(0, 0, 4, 4)
    el:initialise()
    el:instantiate()
    el.render = function(self) pcall(drawGlow, self) end
    el:addToUIManager()
    el:setVisible(true)
    pcall(function() el.javaObject:setConsumeMouseEvents(false) end)
    layer = el
    local fbo = "?"
    pcall(function() fbo = tostring(UIManager.isFBOActive()) end)
    local api = getViewApi()
    local st = "no Java helper (no glow, only the red lens on the model)"
    if api then
        local ok, res = pcall(function() return api.status() end)
        st = ok and tostring(res) or ("error: " .. tostring(res))
    end
    print("[ViewpointLivingProps] switch glow: " .. st .. "; UI buffer=" .. fbo)
end
Events.OnGameStart.Add(makeLayer)
P.tag(nil)
