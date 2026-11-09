-- FILE: AuthenticZ_GlowstickGround.lua

-- AZ_Perf: glowstick ground lighting is handled by the Java ZombieBuddy
-- module (azperf.AZPerf) to avoid Lua-side cell/object scanning.

local SCAN_INTERVAL = 30
local tickCounter = 0

local function onTick()
    tickCounter = tickCounter + 1

    if tickCounter < SCAN_INTERVAL then
        return
    end

    tickCounter = 0

    -- Java module missing (ZombieBuddy/jar failed to load): do nothing.
    if not AZ_Perf_scanGroundGlowsticks then
        return
    end

    local player = getPlayer()
    if player then
        AZ_Perf_scanGroundGlowsticks(player)
    end
end

Events.OnTick.Add(onTick)
