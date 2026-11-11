--[[
    PZ Butter fix: Wind Sway WS-2 (needs PZ Butter; does nothing without it). Auto-tunes treeDetail
    from the GPU timer: PZButter.gpu.overTarget (read live) steps the detail down one level after
    5 s over target and back up, to the user's own setting at most, after 10 s under it.
    The user's saved option is never written.
]]
local function ready()
    return WindSway and type(WindSway.syncToJava) == "function"
       and WindSway.treeDetailOpt and WindSway.treeDetailOpt.getValue
       and ModJava and ModJava.setTreeDetail
       and PZButter and PZButter.gpu and PZButter.gpu.overTarget ~= nil
end

local treeDetailLevels = { 2, 1, 0 }  -- Java levels High/Medium/Low (matches WindSway_Options)
local OVER_MS = 5000
local RESTORE_MS = 10000

local userJava = 2      -- the level the user's option asks for
local appliedJava = 2   -- the level Java has now
local lastCheck = 0
local overStart = nil
local underStart = nil

local function userLevel()
    return treeDetailLevels[WindSway.treeDetailOpt:getValue()] or 2
end

local function stepTo(javaLevel, why)
    appliedJava = javaLevel
    ModJava.setTreeDetail(javaLevel)
    print("[PZButter_WindSway] " .. why)
end

local function tick()
    local now = getTimestampMs()
    if now - lastCheck < 1000 then return end
    lastCheck = now

    -- The user changed the option: Wind Sway applied it already (onChangeApply), so that is the
    -- level in force and the new ceiling. Without this, a detail the user lowered was "restored".
    local user = userLevel()
    if user ~= userJava then
        userJava, appliedJava = user, user
        overStart, underStart = nil, nil
    end

    if PZButter.gpu.overTarget == true then
        underStart = nil
        if not overStart then overStart = now end
        if now - overStart >= OVER_MS and appliedJava > 0 then
            stepTo(appliedJava - 1, "Tree detail lowered under sustained GPU load")
            overStart = now  -- require another full window for further steps
        end
    else
        overStart = nil
        if not underStart then underStart = now end
        if now - underStart >= RESTORE_MS and appliedJava < userJava then
            stepTo(appliedJava + 1, "Tree detail restored")
            underStart = now
        end
    end
end

-- The switch comes from PZButter.properties and is fixed for the session, so the per-tick
-- handler is only registered when the feature can do something.
local registered = false
Events.OnGameStart.Add(function()
    if not ready() or not PZButter.windSwayAutoTreeDetail then return end
    userJava = userLevel()
    appliedJava = userJava
    overStart, underStart = nil, nil
    if not registered then
        registered = true
        Events.OnTick.Add(tick)
    end
end)
