-- CPD_Perf build of CyesPushDoors_ModOptions.lua: refreshFeedbackBinding is throttled in
-- game (every 4 frames); in the menus it still runs every frame. Regenerate with
-- tools/build_cpd.py after every update of Cye's Push Doors.

require "CyesPushDoors/Settings"
require "CyesPushDoors/ForcedOpening"

local CPD = CyesPushDoors
local C = CPD.Config
local S = CPD.Settings
local MOD_ID = "CyesPushDoors"
local MOD_NAME = "Cye's Push Doors!"

local options = nil
local registered = false
local lastFeedbackElement = nil
local FEEDBACK_CHILD_IDS = {
    "DynamicMessages",
    "CustomAnimations",
    "SoundEffects",
}
local COLOR_DRAFT_GUARDED_OPTION_IDS = {
    "DynamicMessages",
    "CustomAnimations",
    "SoundEffects",
    "ImpactTargetOutline",
}
local FEEDBACK_OPTION_IDS = {
    "ImpactFeedback",
    "DynamicMessages",
    "CustomAnimations",
    "SoundEffects",
    "IncrementMessageColor",
    "LimitationMessageColor",
    "StandardMessageColor",
}

local COLOR_OPTION_SETTERS = {
    ImpactTargetOutlineColor = S.setClientImpactTargetOutlineColor,
    IncrementMessageColor = S.setClientIncrementMessageColor,
    LimitationMessageColor = S.setClientLimitationMessageColor,
    StandardMessageColor = S.setClientStandardMessageColor,
}

local COLOR_OPTION_IDS = {
    "ImpactTargetOutlineColor",
    "IncrementMessageColor",
    "LimitationMessageColor",
    "StandardMessageColor",
}
local pendingColors = {}
local colorElements = {}
local colorCaptureSuspendDepth = 0
local colorCaptureSuspendReason = nil
local forcedOpeningCaptureGuard = false

local function debugPrint(message)
    if C and C.DEBUG == true then
        print(message)
    end
end

debugPrint("[CyesPushDoors] ModOptions compatibility fix v8 loaded")

local function optionText(key, fallback)
    if getText then
        local ok, value = pcall(getText, key)
        if ok and value and tostring(value) ~= key then return tostring(value) end
    end
    return fallback
end


local function hasEntry()
    if not PZAPI or not PZAPI.ModOptions or not PZAPI.ModOptions.Data then return false end
    for i = 1, #PZAPI.ModOptions.Data do
        if PZAPI.ModOptions.Data[i] == options then return true end
    end
    return false
end

local function registerOptions()
    if not options or not PZAPI or not PZAPI.ModOptions then return end
    PZAPI.ModOptions.Dict = PZAPI.ModOptions.Dict or {}
    PZAPI.ModOptions.Data = PZAPI.ModOptions.Data or {}

    local found = false
    for i = #PZAPI.ModOptions.Data, 1, -1 do
        local entry = PZAPI.ModOptions.Data[i]
        if entry == options then
            if found then
                table.remove(PZAPI.ModOptions.Data, i)
            else
                found = true
            end
        elseif entry and entry.modOptionsID == MOD_ID then
            table.remove(PZAPI.ModOptions.Data, i)
        end
    end

    PZAPI.ModOptions.Dict[MOD_ID] = options
    if not found then
        table.insert(PZAPI.ModOptions.Data, options)
    end
    registered = true
end

local function unregisterOptions()
    if not options or not PZAPI or not PZAPI.ModOptions then return end
    if PZAPI.ModOptions.Dict then
        PZAPI.ModOptions.Dict[MOD_ID] = nil
    end
    if PZAPI.ModOptions.Data then
        for i = #PZAPI.ModOptions.Data, 1, -1 do
            if PZAPI.ModOptions.Data[i] == options then
                table.remove(PZAPI.ModOptions.Data, i)
            end
        end
    end
    registered = false
end

local function getOptionValue(option, default)
    if not option then return default end
    local ok, value = pcall(function() return option:getValue() end)
    if ok and value ~= nil then return value end
    return default
end

local function colorComponent(value, field, getters, default)
    if value == nil then return default end

    local ok, component = pcall(function() return value[field] end)
    component = ok and tonumber(component) or nil
    if component ~= nil then return component end

    for i = 1, #getters do
        ok, component = pcall(function()
            local getter = value[getters[i]]
            if getter == nil then return nil end
            return getter(value)
        end)
        component = ok and tonumber(component) or nil
        if component ~= nil then return component end
    end
    return default
end

local function copyColor(value)
    if value == nil then return nil end
    local r = colorComponent(value, "r", { "getR", "getRedFloat", "getRed" }, nil)
    local g = colorComponent(value, "g", { "getG", "getGreenFloat", "getGreen" }, nil)
    local b = colorComponent(value, "b", { "getB", "getBlueFloat", "getBlue" }, nil)
    local a = colorComponent(value, "a", { "getA", "getAlphaFloat", "getAlpha" }, 1.0)
    if r == nil or g == nil or b == nil or a == nil then return nil end
    if r > 1 then r = r / 255 end
    if g > 1 then g = g / 255 end
    if b > 1 then b = b / 255 end
    if a > 1 then a = a / 255 end
    return { r = r, g = g, b = b, a = a }
end

local function updateColorOption(targetOptions, id, color)
    if not targetOptions then return end
    local target = nil
    if type(targetOptions.getOption) == "function" then
        local ok, result = pcall(targetOptions.getOption, targetOptions, id)
        if ok then target = result end
    elseif targetOptions.dict then
        target = targetOptions.dict[id]
    end
    if target then
        target.color = copyColor(color)
        return target
    end
end

local function updateColorElement(option, color)
    if not option or not option.element then return end
    local elementColor = copyColor(color)
    if not elementColor then return end
    pcall(function() option.element.backgroundColor = elementColor end)
end

local function updateColorEverywhere(id, color, updateElements)
    local seen = {}
    local function updateEntry(entry)
        if not entry or seen[entry] then return end
        seen[entry] = true
        local option = updateColorOption(entry, id, color)
        if updateElements then updateColorElement(option, color) end
        if entry == options and option and option.element then
            colorElements[id] = option.element
        end
    end

    updateEntry(options)

    if PZAPI and PZAPI.ModOptions then
        local current = PZAPI.ModOptions.Dict and PZAPI.ModOptions.Dict[MOD_ID]
        updateEntry(current)
        if PZAPI.ModOptions.Data then
            for i = 1, #PZAPI.ModOptions.Data do
                local entry = PZAPI.ModOptions.Data[i]
                if entry and entry.modOptionsID == MOD_ID then updateEntry(entry) end
            end
        end
    end

    local setter = COLOR_OPTION_SETTERS[id]
    if setter then setter(copyColor(color)) end
end

local function restorePendingColor(id, reason, updateElements)
    local color = pendingColors[id]
    if not color then return false end
    updateColorEverywhere(id, color, updateElements == true)
    if reason then
        debugPrint("[CyesPushDoors] Preserved " .. id .. " during " .. tostring(reason) .. " = " ..
            tostring(color.r) .. " " .. tostring(color.g) .. " " ..
            tostring(color.b) .. " " .. tostring(color.a))
    end
    return true
end

local function captureColor(id, value)
    local option = options and options:getOption(id) or nil
    local element = option and option.element or nil
    local elementWasRecreated = element and colorElements[id] ~= element
    if elementWasRecreated then colorElements[id] = element end
    if pendingColors[id] and (colorCaptureSuspendDepth > 0 or elementWasRecreated) then
        local reason = colorCaptureSuspendReason or "options-list rebuild"
        restorePendingColor(id, reason, true)
        return
    end

    local color = copyColor(value)
    if not color then return end
    pendingColors[id] = copyColor(color)
    updateColorEverywhere(id, color, true)

    debugPrint("[CyesPushDoors] Selected " .. id .. " = " ..
        tostring(color.r) .. " " .. tostring(color.g) .. " " ..
        tostring(color.b) .. " " .. tostring(color.a))
end

local function restorePendingColors(reason, updateElements)
    for i = 1, #COLOR_OPTION_IDS do
        local id = COLOR_OPTION_IDS[i]
        restorePendingColor(id, reason, updateElements)
    end
end

local function colorsMatch(first, second)
    local a = copyColor(first)
    local b = copyColor(second)
    if not a or not b then return false end
    local epsilon = 0.00001
    return math.abs(a.r - b.r) <= epsilon and
        math.abs(a.g - b.g) <= epsilon and
        math.abs(a.b - b.b) <= epsilon and
        math.abs(a.a - b.a) <= epsilon
end

local function syncPendingColorElements(reason)
    if not options then return end
    for i = 1, #COLOR_OPTION_IDS do
        local id = COLOR_OPTION_IDS[i]
        local option = options:getOption(id)
        local element = option and option.element or nil
        local color = pendingColors[id]
        if element and color then
            local recreated = colorElements[id] ~= element
            local changed = not colorsMatch(element.backgroundColor, color)
            colorElements[id] = element
            if recreated or changed then
                restorePendingColor(id, reason or "live UI refresh", true)
            end
        end
    end
end

local function beginColorDraftGuard(reason)
    restorePendingColors(nil, true)
    if colorCaptureSuspendDepth == 0 then colorCaptureSuspendReason = reason end
    colorCaptureSuspendDepth = colorCaptureSuspendDepth + 1
end

local function endColorDraftGuard(reason)
    if colorCaptureSuspendDepth > 0 then
        colorCaptureSuspendDepth = colorCaptureSuspendDepth - 1
    end
    if colorCaptureSuspendDepth == 0 then colorCaptureSuspendReason = nil end
    restorePendingColors(reason, true)
    syncPendingColorElements(reason)
end

local function seedPendingColors()
    if not options then return end
    for i = 1, #COLOR_OPTION_IDS do
        local id = COLOR_OPTION_IDS[i]
        if pendingColors[id] == nil then
            local color = copyColor(getOptionValue(options:getOption(id), nil))
            if color then pendingColors[id] = color end
        end
    end
end

local function bindColorOption(id)
    if not options then return end
    local option = options:getOption(id)
    if not option then return end

    local function onColorChanged(self, color)
        captureColor(id, color)
    end

    option.onChange = onColorChanged
    option.onChangeApply = onColorChanged
end

local function installModOptionsSaveHook()
    if not PZAPI or not PZAPI.ModOptions or type(PZAPI.ModOptions.save) ~= "function" then return end
    local restoreCallback = function()
        restorePendingColors("save", true)
    end
    PZAPI.ModOptions._cpdRestorePendingColors = restoreCallback
    PZAPI.ModOptions._cpdCaptureColorOptions = restoreCallback
    if PZAPI.ModOptions._cpdColorSaveHooked then return end

    local originalSave = PZAPI.ModOptions.save
    PZAPI.ModOptions.save = function(self, ...)
        local restore = PZAPI.ModOptions._cpdRestorePendingColors
        if type(restore) == "function" then
            local ok, err = pcall(restore)
            if not ok then
                debugPrint("[CyesPushDoors] Pending-color restore failed: " .. tostring(err))
            end
        end
        return originalSave(self, ...)
    end
    PZAPI.ModOptions._cpdColorSaveHooked = true
end

local function setOptionValue(option, value)
    if not option then return false end
    local ok = pcall(function()
        if option.setValue then
            option:setValue(value)
        else
            option.value = value
        end
    end)
    return ok
end

local function setControlChecked(control, checked)
    if not control then return end
    local selected = pcall(function()
        if control.setSelected then control:setSelected(1, checked) end
    end)
    if not selected then
        pcall(function() if control.setSelected then control:setSelected(checked) end end)
    end
    pcall(function() if control.setChecked then control:setChecked(checked) end end)
    pcall(function()
        if type(control.selected) == "table" then control.selected[1] = checked end
    end)
    pcall(function() control.checked = checked end)
end

local function setControlEnabled(control, enabled)
    if not control then return end
    pcall(function()
        if control.setEnable then
            control:setEnable(enabled)
        elseif control.setEnabled then
            control:setEnabled(enabled)
        end
    end)
    pcall(function() if control.setDisabled then control:setDisabled(not enabled) end end)
    pcall(function() if control.setLocked then control:setLocked(not enabled) end end)
    pcall(function() if control.setEditable then control:setEditable(enabled) end end)
    pcall(function() control.enable = enabled end)
    pcall(function() control.enabled = enabled end)
    pcall(function() control.disabled = not enabled end)
    pcall(function() control.locked = not enabled end)
    pcall(function() control.editable = enabled end)
end

local function setOptionEnabled(option, enabled)
    if not option then return end
    setControlEnabled(option, enabled)
    pcall(function() setControlEnabled(option.element, enabled) end)
    pcall(function() setControlEnabled(option.control, enabled) end)
    pcall(function() setControlEnabled(option.tickBox, enabled) end)
    pcall(function() setControlEnabled(option.element and option.element.tickBox, enabled) end)
end

local function setOptionChecked(option, checked)
    if not option then return end
    if getOptionValue(option, not checked) ~= checked then setOptionValue(option, checked) end
    setControlChecked(option, checked)
    pcall(function() setControlChecked(option.element, checked) end)
    pcall(function() setControlChecked(option.control, checked) end)
    pcall(function() setControlChecked(option.tickBox, checked) end)
    pcall(function() setControlChecked(option.element and option.element.tickBox, checked) end)
end

local function refreshFeedbackHierarchy(generalValue)
    if not options then return end
    beginColorDraftGuard("feedback hierarchy refresh")
    local serverLocked = S.hideIndividualOptions()
    local general = generalValue
    if general == nil then
        general = getOptionValue(options:getOption("ImpactFeedback"), true) == true
    end
    for i = 1, #FEEDBACK_CHILD_IDS do
        local child = options:getOption(FEEDBACK_CHILD_IDS[i])
        if general then setOptionChecked(child, true) end
        setOptionEnabled(child, not serverLocked and not general)
    end
    if serverLocked then
        for i = 1, #FEEDBACK_OPTION_IDS do
            setOptionEnabled(options:getOption(FEEDBACK_OPTION_IDS[i]), false)
        end
    else
        setOptionEnabled(options:getOption("ImpactFeedback"), true)
        setOptionEnabled(options:getOption("IncrementMessageColor"), true)
        setOptionEnabled(options:getOption("LimitationMessageColor"), true)
        setOptionEnabled(options:getOption("StandardMessageColor"), true)
    end
    endColorDraftGuard("feedback hierarchy refresh")
end

local function bindFeedbackControl(control)
    if not control or control._cpdFeedbackChangeHooked then return false end
    if type(control.changeOptionMethod) ~= "function" then return false end

    local originalChangeOptionMethod = control.changeOptionMethod
    control.changeOptionMethod = function(target, index, selected, ...)
        beginColorDraftGuard("feedback live change")
        local ok, result = pcall(originalChangeOptionMethod, target, index, selected, ...)
        refreshFeedbackHierarchy(selected == true)
        endColorDraftGuard("feedback live change")
        if not ok then error(result) end
        return result
    end
    control._cpdFeedbackChangeHooked = true
    return true
end

local function bindColorDraftControl(control, reason)
    if not control or control._cpdColorDraftChangeHooked then return false end
    if type(control.changeOptionMethod) ~= "function" then return false end

    local originalChangeOptionMethod = control.changeOptionMethod
    control.changeOptionMethod = function(target, index, selected, ...)
        beginColorDraftGuard(reason)
        local ok, result = pcall(originalChangeOptionMethod, target, index, selected, ...)
        endColorDraftGuard(reason)
        if not ok then error(result) end
        return result
    end
    control._cpdColorDraftChangeHooked = true
    return true
end

local function bindOtherFeedbackControls()
    if not options then return end
    for i = 1, #COLOR_DRAFT_GUARDED_OPTION_IDS do
        local id = COLOR_DRAFT_GUARDED_OPTION_IDS[i]
        local option = options:getOption(id)
        if option then
            local reason = id .. " live change"
            if not option._cpdColorDraftValueHooked and type(option.setValue) == "function" then
                local originalSetValue = option.setValue
                option.setValue = function(self, value)
                    beginColorDraftGuard(reason)
                    local ok, result = pcall(originalSetValue, self, value)
                    endColorDraftGuard(reason)
                    if not ok then error(result) end
                    return result
                end
                option._cpdColorDraftValueHooked = true
            end

            bindColorDraftControl(option, reason)
            pcall(function() bindColorDraftControl(option.element, reason) end)
            pcall(function() bindColorDraftControl(option.control, reason) end)
            pcall(function() bindColorDraftControl(option.tickBox, reason) end)
            pcall(function() bindColorDraftControl(option.element and option.element.tickBox, reason) end)
        end
    end
end

local function bindFeedbackMaster()
    if not options then return end
    local master = options:getOption("ImpactFeedback")
    if not master then return end

    if not master._cpdFeedbackValueHooked and type(master.setValue) == "function" then
        local originalSetValue = master.setValue
        master.setValue = function(self, value)
            beginColorDraftGuard("feedback value change")
            local ok, result = pcall(originalSetValue, self, value)
            refreshFeedbackHierarchy(value == true)
            endColorDraftGuard("feedback value change")
            if not ok then error(result) end
            return result
        end
        master._cpdFeedbackValueHooked = true
    end

    bindFeedbackControl(master)
    pcall(function() bindFeedbackControl(master.element) end)
    pcall(function() bindFeedbackControl(master.control) end)
    pcall(function() bindFeedbackControl(master.tickBox) end)
    pcall(function() bindFeedbackControl(master.element and master.element.tickBox) end)
    bindOtherFeedbackControls()
end

local function refreshFeedbackBinding()
    if not options then return end
    syncPendingColorElements()

    if forcedOpeningCaptureGuard and CPD.isForcedOpeningChordCaptureActive then
        local ok, active = pcall(CPD.isForcedOpeningChordCaptureActive)
        if ok and not active then
            forcedOpeningCaptureGuard = false
            endColorDraftGuard("shortcut capture")
        end
    end

    local master = options:getOption("ImpactFeedback")
    local element = master and master.element or nil
    if not element or element == lastFeedbackElement then return end

    lastFeedbackElement = element
    local ok, err = pcall(function()
        bindFeedbackMaster()
        refreshFeedbackHierarchy()
    end)
    if not ok then
        debugPrint("[CyesPushDoors] Feedback-control binding failed: " .. tostring(err))
    end
end

local function applyOptions()
    if not options then return end
    refreshFeedbackHierarchy()
    local feedback = options:getOption("ImpactFeedback")
    if feedback then
        S.setClientImpactFeedback(getOptionValue(feedback, true) == true)
    end
    local dynamicMessages = options:getOption("DynamicMessages")
    if dynamicMessages then
        S.setClientDynamicMessages(getOptionValue(dynamicMessages, true) == true)
    end
    local customAnimations = options:getOption("CustomAnimations")
    if customAnimations then
        S.setClientCustomAnimations(getOptionValue(customAnimations, true) == true)
    end
    local soundEffects = options:getOption("SoundEffects")
    if soundEffects then
        S.setClientSoundEffects(getOptionValue(soundEffects, true) == true)
    end
    local impactTargetOutline = options:getOption("ImpactTargetOutline")
    if impactTargetOutline then
        S.setClientImpactTargetOutline(
            getOptionValue(impactTargetOutline, false) == true
        )
    end
    local impactTargetOutlineColor = options:getOption("ImpactTargetOutlineColor")
    if impactTargetOutlineColor then
        S.setClientImpactTargetOutlineColor(
            getOptionValue(impactTargetOutlineColor, nil)
        )
    end
    local incrementColor = options:getOption("IncrementMessageColor")
    if incrementColor then
        S.setClientIncrementMessageColor(getOptionValue(incrementColor, nil))
    end
    local limitationColor = options:getOption("LimitationMessageColor")
    if limitationColor then
        S.setClientLimitationMessageColor(getOptionValue(limitationColor, nil))
    end
    local standardColor = options:getOption("StandardMessageColor")
    if standardColor then
        S.setClientStandardMessageColor(getOptionValue(standardColor, nil))
    end
end

local function createOptions()
    if options then return end
    if not PZAPI or not PZAPI.ModOptions then return end

    options = PZAPI.ModOptions:create(MOD_ID, MOD_NAME)
    options:addTickBox(
        "ImpactFeedback",
        optionText("IGUI_CyesPushDoors_ImpactFeedback", "Enable All Feedback"),
        true,
        optionText(
            "IGUI_CyesPushDoors_ImpactFeedback_Tooltip",
            "Enable and lock all messages, custom animations, and sound effects."
        )
    )
    options:addTickBox(
        "DynamicMessages",
        optionText("IGUI_CyesPushDoors_DynamicMessages", "Dynamic Messages"),
        true,
        optionText(
            "IGUI_CyesPushDoors_DynamicMessages_Tooltip",
            "Show fatigue and exhaustion messages generated by this mod."
        )
    )
    options:addTickBox(
        "CustomAnimations",
        optionText("IGUI_CyesPushDoors_CustomAnimations", "Custom Animations"),
        true,
        optionText(
            "IGUI_CyesPushDoors_CustomAnimations_Tooltip",
            "Enable custom player and zombie animations. Disabling this may improve performance."
        )
    )
    options:addTickBox(
        "SoundEffects",
        optionText("IGUI_CyesPushDoors_SoundEffects", "Sound Effects"),
        true,
        optionText(
            "IGUI_CyesPushDoors_SoundEffects_Tooltip",
            "Enable effort voices, door impact sounds, and other sounds played by this mod."
        )
    )
    options:addTickBox(
        "ImpactTargetOutline",
        optionText(
            "IGUI_CyesPushDoors_ImpactTargetOutline",
            "Impact Target Outline"
        ),
        false,
        optionText(
            "IGUI_CyesPushDoors_ImpactTargetOutline_Tooltip",
            "Highlight zombies in the predicted impact area with the vanilla outline. This option is independent of Melee Target Outline."
        )
    )
    options:addColorPicker(
        "ImpactTargetOutlineColor",
        optionText(
            "IGUI_CyesPushDoors_ImpactTargetOutlineColor",
            "Impact Outline Color"
        ),
        0.45,
        1.0,
        0.45,
        1.0,
        optionText(
            "IGUI_CyesPushDoors_ImpactTargetOutlineColor_Tooltip",
            "Choose the outline color used for zombies in the predicted door-impact area."
        )
    )
    options:addColorPicker(
        "IncrementMessageColor",
        optionText("IGUI_CyesPushDoors_IncrementMessageColor", "Increase Message Color"),
        1.0,
        1.0,
        0.0,
        1.0,
        optionText(
            "IGUI_CyesPushDoors_IncrementMessageColor_Tooltip",
            "Choose the color used by stat-increase messages marked with an arrow."
        )
    )
    options:addColorPicker(
        "LimitationMessageColor",
        optionText("IGUI_CyesPushDoors_LimitationMessageColor", "Limitation Message Color"),
        1.0,
        0.0,
        0.0,
        1.0,
        optionText(
            "IGUI_CyesPushDoors_LimitationMessageColor_Tooltip",
            "Choose the color used when an action is blocked or limited."
        )
    )
    options:addColorPicker(
        "StandardMessageColor",
        optionText("IGUI_CyesPushDoors_StandardMessageColor", "Standard Message Color"),
        1.0,
        1.0,
        1.0,
        1.0,
        optionText(
            "IGUI_CyesPushDoors_StandardMessageColor_Tooltip",
            "Choose the color used by standard contextual dialogue."
        )
    )

    bindColorOption("ImpactTargetOutlineColor")
    bindColorOption("IncrementMessageColor")
    bindColorOption("LimitationMessageColor")
    bindColorOption("StandardMessageColor")

    refreshFeedbackHierarchy()

    if options.addDescription then
        options:addDescription(optionText(
            "IGUI_CyesPushDoors_ForcedOpeningKey",
            "Forced Opening Shortcut"
        ))
    end
    local forcedOpeningButton = nil
    forcedOpeningButton = options:addButton(
        "ForcedOpeningKey",
        CPD.getForcedOpeningChordLabel(),
        optionText(
            "IGUI_CyesPushDoors_ForcedOpeningKey_Tooltip",
            "Click, then press exactly two keyboard keys or mouse buttons. Default: Alt + E."
        ),
        function()
            if not forcedOpeningCaptureGuard then
                beginColorDraftGuard("shortcut capture")
                forcedOpeningCaptureGuard = true
            end
            CPD.beginForcedOpeningChordCapture(forcedOpeningButton)
        end,
        nil
    )
    CPD.registerForcedOpeningChordButton(forcedOpeningButton)

    options.apply = function(self)
        beginColorDraftGuard("apply")
        local ok, err = pcall(applyOptions)
        endColorDraftGuard("apply")
        if not ok then error(err) end
    end

    bindFeedbackMaster()
    registerOptions()
    installModOptionsSaveHook()
end

local function refreshVisibility()
    createOptions()
    if not options then return end
    if not registered or not hasEntry() then registerOptions() end
    applyOptions()
    refreshFeedbackHierarchy()
end

local function onGameBoot()
    createOptions()
    installModOptionsSaveHook()
    bindFeedbackMaster()
    refreshFeedbackBinding()
end

local function onMainMenuEnter()
    createOptions()
    registerOptions()
    installModOptionsSaveHook()
    seedPendingColors()
    syncPendingColorElements()
    bindFeedbackMaster()
    refreshFeedbackBinding()
    applyOptions()
end

local function onGameStart()
    if CPD.cancelForcedOpeningChordCapture then
        CPD.cancelForcedOpeningChordCapture()
    end
    if forcedOpeningCaptureGuard then
        forcedOpeningCaptureGuard = false
        endColorDraftGuard("shortcut capture cancelled")
    end
    seedPendingColors()
    refreshVisibility()
end

if Events.OnGameBoot then Events.OnGameBoot.Add(onGameBoot) end
if Events.OnMainMenuEnter then Events.OnMainMenuEnter.Add(onMainMenuEnter) end
if Events.OnGameStart then Events.OnGameStart.Add(onGameStart) end
local feedbackFrame = 0
local function throttledFeedbackBinding()
    local inGame = getPlayer and getPlayer() ~= nil
    if inGame then
        feedbackFrame = feedbackFrame + 1
        if feedbackFrame % 4 ~= 0 then return end
    end
    refreshFeedbackBinding()
end

if Events.OnPreUIDraw then
    Events.OnPreUIDraw.Add(throttledFeedbackBinding)
elseif Events.OnTick then
    Events.OnTick.Add(refreshFeedbackBinding)
end
