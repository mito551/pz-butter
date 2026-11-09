--[[
    PZ Butter fix: Wind Sway WS-2 (depends on PZ Butter). Auto-tunes treeDetail from the GPU timer.
    Uses PZButter.gpu.overTarget (read live) exposed by PZ Butter core. RESTORE restores to the user's setting.
]]
local function ready()
    return WindSway and type(WindSway.syncToJava) == "function"
       and WindSway.treeDetailOpt and WindSway.treeDetailOpt.getValue
       and ModJava and ModJava.setTreeDetail
       and PZButter and PZButter.gpu and PZButter.gpu.overTarget ~= nil
end

local treeDetailLevels = { 2, 1, 0 }  -- Java levels High/Medium/Low (matches WindSway_Options)
local userLevelIndex = 3  -- combo index; Java level 2 initially (High)
local appliedJava = 2
local lastCheck = 0
local overStart = nil
local underStart = nil
local userJava = 2
local OVER_MS = 5000
local RESTORE_MS = 10000

local function comboForJava(javaLevel)
    for i, jl in ipairs(treeDetailLevels) do
        if jl == javaLevel then return i end
    end
    return 1
end

local function toast(text)
    local p = getSpecificPlayer and getSpecificPlayer(0)
    if p then pcall(function() p:Say(text) end) end
    print("[PZButter_WindSway] " .. text)
end

local function stepTo(javaLevel)
    if javaLevel == appliedJava then return end
    appliedJava = javaLevel
    ModJava.setTreeDetail(javaLevel)
    WindSway.treeDetailOpt:setValue(comboForJava(javaLevel))
end

local function tick()
    if not ready() then return end
    if not PZButter.windSwayAutoTreeDetail then return end
    local now = getTimestampMs()
    if now - lastCheck < 1000 then return end
    lastCheck = now

    -- track the user's chosen level when they are on the options screen is not wired here; keep base.
    local over = (PZButter.gpu.overTarget == true)

    if over then
        underStart = nil
        if not overStart then overStart = now end
        if (now - overStart >= OVER_MS) and appliedJava > 0 then
            stepTo(appliedJava - 1)
            toast("Tree detail lowered under sustained GPU load")
            overStart = now  -- require another full window for further steps
        end
    else
        overStart = nil
        if not underStart then underStart = now end
        if (now - underStart >= RESTORE_MS) and appliedJava < userJava then
            stepTo(appliedJava + 1)
            toast("Tree detail restored")
            underStart = now
        end
    end
end

Events.OnTick.Add(tick)
Events.OnGameStart.Add(function()
    if ready() then userJava = WindSway.treeDetailOpt and (treeDetailLevels[WindSway.treeDetailOpt:getValue()] or 2) or 2
        appliedJava = userJava
    end
end)