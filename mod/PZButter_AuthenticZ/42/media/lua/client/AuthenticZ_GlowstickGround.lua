-- FILE: AuthenticZ_GlowstickGround.lua

-- AZ_Perf: glowstick ground lighting is handled by the Java ZombieBuddy
-- module (azperf.AZPerf) to avoid Lua-side cell/object scanning.
-- When the Java module is unavailable (ZombieBuddy missing or jar not loaded),
-- fall back to the original pure-Lua scan so glowsticks still light the ground.

local SCAN_INTERVAL = 30
local tickCounter = 0

-- Pure-Lua fallback (target's original implementation), used only when the jar is absent.
local groundLights = {}

local workingGlowSticksData = {
    ["AuthenticGlowstick_Blue_On"]   = { 0.0,  0.0,  1.0 },
    ["AuthenticGlowstick_Red_On"]    = { 1.0,  0.0,  0.0 },
    ["AuthenticGlowstick_Green_On"]  = { 0.0,  1.0,  0.0 },
    ["AuthenticGlowstick_Purple_On"] = { 1.0,  0.0,  1.0 },
    ["AuthenticGlowstick_Yellow_On"] = { 1.0,  1.0,  0.0 },
    ["AuthenticGlowstick_Orange_On"] = { 1.0,  0.50, 0.0 },
    ["AuthenticGlowstick_Pink_On"]   = { 1.0,  0.0,  0.25 },
    ["AuthenticGlowstick_White_On"]  = { 1.0,  1.0,  1.0 },
}

local LIGHT_RADIUS = 8
local SCAN_RADIUS = 12

local function removeGroundLight(worldObj)
    local light = groundLights[worldObj]
    if not light then return end
    light:setActive(false)
    getCell():removeLamppost(light)
    groundLights[worldObj] = nil
end

local function addGroundLight(worldObj, item)
    local color = workingGlowSticksData[item:getType()]
    if not color then return end
    local square = worldObj:getSquare()
    if not square then return end
    local light = IsoLightSource.new(square:getX(), square:getY(), square:getZ(),
        color[1], color[2], color[3], LIGHT_RADIUS)
    light:setActive(true)
    getCell():addLamppost(light)
    groundLights[worldObj] = light
end

local function scanLua()
    local player = getPlayer()
    if not player then return end
    local cell = getCell()
    if not cell then return end
    local px = math.floor(player:getX())
    local py = math.floor(player:getY())
    local pz = math.floor(player:getZ())
    local seen = {}
    for x = px - SCAN_RADIUS, px + SCAN_RADIUS do
        for y = py - SCAN_RADIUS, py + SCAN_RADIUS do
            local square = cell:getGridSquare(x, y, pz)
            if square then
                local worldObjects = square:getWorldObjects()
                for i = 0, worldObjects:size() - 1 do
                    local worldObj = worldObjects:get(i)
                    local item = worldObj:getItem()
                    if item and workingGlowSticksData[item:getType()] then
                        seen[worldObj] = true
                        local remaining = item:getCurrentUsesFloat()
                        if remaining > 0 then
                            if not groundLights[worldObj] then addGroundLight(worldObj, item) end
                        else
                            removeGroundLight(worldObj)
                        end
                    end
                end
            end
        end
    end
    for worldObj, _ in pairs(groundLights) do
        if not seen[worldObj] then removeGroundLight(worldObj) end
    end
end

local function onTick()
    tickCounter = tickCounter + 1
    if tickCounter < SCAN_INTERVAL then return end
    tickCounter = 0
    if AZ_Perf_scanGroundGlowsticks then
        local player = getPlayer()
        if player then AZ_Perf_scanGroundGlowsticks(player) end
    else
        scanLua()
    end
end

Events.OnTick.Add(onTick)