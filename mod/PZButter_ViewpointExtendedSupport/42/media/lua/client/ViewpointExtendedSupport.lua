--[[
    Performance build of ViewpointExtendedSupport.lua (VES_Perf).

    Same behaviour, far less work per tick. This file replaces the original
    at the same path, because everything on the hot path is file-local and
    cannot be patched from outside. Re-diff it against the original after
    every update of Viewpoint Extended Support.

      N1  one evaluation of the UI state, shared by the menu-suppression and
          cursor-request updates (was 4x inventory, 3x conversation, 2x vehicle)
      N2  the full UIManager scan only discovers windows; the hooks keep the
          registry exact and the per-refresh check is a few table reads
      N3  no pcall(function() ... end) closures on the repeating paths
      N4  UI state is refreshed on a 4 tick poll (UI_POLL_TICKS) or at once
          when a hook fires; the release debounce still counts real ticks
      N5  options read on the per-tick path come from a cache refreshed with
          the existing 10 tick beat
      N6  window hooks are re-checked on the 10 tick beat (was every tick)
      U1  event-driven: hooks mark the state dirty, the poll is the safety net
      V1  with VESPerf.jar loaded by ZombieBuddy the scan is one Java call
      V2  integrations that are not installed cost nothing (a few global reads
          on the 10 tick beat), including the Companion Dogs name-tag UI scan
]]

do
    local m = getModInfoByID and getModInfoByID("ViewpointExtendedSupport")
    local v = m and m:getModVersion()
    if v and v ~= "0.4.46" then
        print("[VES_Perf] WARNING: Viewpoint Extended Support is " .. tostring(v) .. ", this override was built from 0.4.46; regenerate VES_Perf with tools/build_ves.py")
    end
end

require "PZAPI/ModOptions"

local Support = ViewpointExtendedSupport
local DEFAULT_PREVIOUS_INTERACTION_KEY = (Keyboard and Keyboard.KEY_LEFT) or 203
local DEFAULT_NEXT_INTERACTION_KEY = (Keyboard and Keyboard.KEY_RIGHT) or 205

local function mouseButtonOrFallback(button, fallback)
    if Mouse and Mouse.BTN_OFFSET then return Mouse.BTN_OFFSET + button end
    return fallback
end

local function addModifierOption(modOptions, id, name, selected)
    local option = modOptions:addComboBox(id, name)
    option:addItem("None", selected == 1)
    option:addItem("Shift", selected == 2)
    option:addItem("Ctrl", selected == 3)
    option:addItem("Alt", selected == 4)
    option:addItem("Shift + Ctrl", selected == 5)
    option:addItem("Shift + Alt", selected == 6)
    option:addItem("Ctrl + Alt", selected == 7)
    option:addItem("Shift + Ctrl + Alt", selected == 8)
    return option
end

local options = {}
local conversationCursorOwned = false
if PZAPI and PZAPI.ModOptions then
    local modOptions = PZAPI.ModOptions:create("ViewpointExtendedSupport", "Viewpoint Extended Support")
    modOptions:addTitle("Cursor and Interface")
    options.holdCursor = modOptions:addTickBox("HoldFreeCursor", "Hold a key to use free mouse", true)
    options.cursorKey = modOptions:addKeyBind("FreeCursorKey", "Free mouse hold key", mouseButtonOrFallback(3, 1003))
    options.cursorModifier = addModifierOption(modOptions, "FreeCursorModifier", "Free mouse modifiers", 1)
    options.autoUi = modOptions:addTickBox("AutoFreeCursorInUI", "Automatically use free mouse in inventories and menus", true)
    options.ergonomicUi = modOptions:addTickBox("ErgonomicUIIntegration", "Integrate with Ergonomic UI", true)
    options.hideInteractionMenuWhenInventoryOpen = modOptions:addTickBox("HideInteractionMenuWhenInventoryOpen", "Hide Viewpoint menu and reserve scroll for inventory", true)

    modOptions:addTitle("Vehicle Camera")
    options.vehicleCameraMode = modOptions:addComboBox("VehicleCameraMode", "Vehicle camera mode")
    options.vehicleCameraMode:addItem("Off", false)
    options.vehicleCameraMode:addItem("Third person in vehicle, first person on exit", true)
    options.vehicleCameraMode:addItem("Keep previous view in vehicle", false)

    modOptions:addTitle("Interaction Menu")
    options.groupContextMenuActions = modOptions:addTickBox("GroupContextMenuActions", "Use cascading Viewpoint interaction menus", true)
    options.cascadeContainerContents = modOptions:addTickBox("CascadeContainerContents", "Show container contents in a submenu", true)
    options.lazyInteractionOptions = modOptions:addTickBox("LazyInteractionOptions", "Show View options before building interaction menus", true)
    options.disableTargetOutline = modOptions:addTickBox("DisableTargetOutline", "Disable Viewpoint target outline", false)
    options.interactionPreviousKey = modOptions:addKeyBind("InteractionPreviousKey", "Previous nearby interaction target", DEFAULT_PREVIOUS_INTERACTION_KEY)
    options.interactionNextKey = modOptions:addKeyBind("InteractionNextKey", "Next nearby interaction target", DEFAULT_NEXT_INTERACTION_KEY)
    options.interactionCycleScrollModifier = addModifierOption(modOptions, "InteractionCycleScrollModifier", "Cycle nearby targets with scroll modifier", 4)

    modOptions:addTitle("Startup")
    options.closeExtractionTrackerOnLoad = modOptions:addTickBox("CloseExtractionTrackerOnLoad", "Start Wolf Extraction Quest tracker closed", true)
    options.skipSetup = modOptions:addTickBox("SkipSetupWizard", "Skip Viewpoint startup setup screen", false)
    options.startViewpoint = modOptions:addTickBox("StartViewpointOnGameStart", "Start Viewpoint automatically when entering a game", false)

    modOptions:addTitle("Diagnostics")
    options.diagnostics = modOptions:addTickBox("CursorDiagnostics", "Enable diagnostic logging", false)
end

local function optionValue(option, fallback)
    local getValue = option and option.getValue
    if not getValue then return fallback end
    local ok, value = pcall(getValue, option)
    if not ok or value == nil then return fallback end
    return value
end

-- N5: the options the repeating paths read, refreshed on the 10 tick beat
local cfg = { autoUi = true, ergonomicUi = true, hideInventoryMenu = true, diagnostics = false }

local function refreshOptionCache()
    cfg.autoUi = optionValue(options.autoUi, true)
    cfg.ergonomicUi = optionValue(options.ergonomicUi, true)
    cfg.hideInventoryMenu = optionValue(options.hideInteractionMenuWhenInventoryOpen, true)
    cfg.diagnostics = optionValue(options.diagnostics, false)
end
refreshOptionCache()

-- V2: which optional integrations are present. Probed at file load, at
-- OnGameStart and on the 10 tick beat (a mod may define its global late).
-- Fields inside an integration (e.g. ProjectALifeJimmy.NpcTags) can still
-- appear later; the install functions keep checking those themselves.
local integrations = { alife = false, alifeTags = false, erg = false, dogs = false, cmi = false }

local function probeIntegrations()
    integrations.alife = ViewpointExtendedSupportALife ~= nil or ProjectALife ~= nil
    integrations.alifeTags = ProjectALifeJimmy ~= nil
    integrations.erg = ErgonomicUI ~= nil or ErgUIPanel ~= nil
    integrations.dogs = CompanionDogs ~= nil
    integrations.cmi = ContextMenuIcons ~= nil
end
probeIntegrations()

local VEHICLE_CAMERA_OFF = 1
local VEHICLE_CAMERA_FORCE_THIRD_PERSON = 2
local VEHICLE_CAMERA_KEEP_PREVIOUS_VIEW = 3

local function vehicleCameraMode()
    local mode = optionValue(options.vehicleCameraMode, VEHICLE_CAMERA_FORCE_THIRD_PERSON)
    if mode == VEHICLE_CAMERA_OFF or mode == VEHICLE_CAMERA_FORCE_THIRD_PERSON
            or mode == VEHICLE_CAMERA_KEEP_PREVIOUS_VIEW then return mode end
    return VEHICLE_CAMERA_FORCE_THIRD_PERSON
end

local function modifierMask(option, fallback)
    local masks = { 0, 1, 2, 4, 3, 5, 6, 7 }
    return masks[optionValue(option, fallback or 1)] or 0
end

local function syncConfiguration()
    if not Support or not Support.configure then return end
    Support.configure(
        optionValue(options.holdCursor, true) and not conversationCursorOwned,
        optionValue(options.cursorKey, 1003),
        optionValue(options.autoUi, true) or conversationCursorOwned,
        optionValue(options.diagnostics, false),
        optionValue(options.skipSetup, false),
        optionValue(options.groupContextMenuActions, true),
        modifierMask(options.cursorModifier, 1)
    )
    if Support.configureInteractionCycling then
        Support.configureInteractionCycling(
            optionValue(options.interactionPreviousKey, DEFAULT_PREVIOUS_INTERACTION_KEY),
            optionValue(options.interactionNextKey, DEFAULT_NEXT_INTERACTION_KEY),
            modifierMask(options.interactionCycleScrollModifier, 4)
        )
    end
    if Support.configureContainerContentsCascade then
        Support.configureContainerContentsCascade(optionValue(options.cascadeContainerContents, true))
    end
    if Support.setTargetOutlineDisabled then
        Support.setTargetOutlineDisabled(optionValue(options.disableTargetOutline, false))
    end
end

local function isVisible(ui)
    local fn = ui and ui.isVisible
    if not fn then return false end
    local ok, value = pcall(fn, ui)
    return ok and value == true
end

local function isALifeConversationOpen()
    local integration = ViewpointExtendedSupportALife
    if integration and type(integration.isConversationOpen) == "function" then
        return integration.isConversationOpen()
    end
    local menu = ProjectALife and ProjectALife.TalkMenu
    local window = menu and menu.instance
    if not window or not Support or type(Support.isViewEnabled) ~= "function" then return false end
    local ok, enabled = pcall(Support.isViewEnabled)
    if not ok or enabled ~= true then return false end
    if window.playerNum ~= nil and window.playerNum ~= 0 then return false end
    return isVisible(window)
end

local function syncConversationCursorMode()
    local talking = integrations.alife and isALifeConversationOpen() or false
    if talking == conversationCursorOwned then return false end

    local closed = conversationCursorOwned and not talking
    if closed and Support and type(Support.setAutoCursorRequested) == "function" then
        Support.setAutoCursorRequested(false)
    end
    conversationCursorOwned = talking
    syncConfiguration()
    return closed
end

local VEHICLE_CURSOR_WINDOWS = {
    ISRadialMenu = true,
    ISWorldMap = true,
    ISMapWrapper = true,
    ISCharacterInfoWindow = true,
}

local ON_FOOT_CURSOR_WINDOWS = {
    ISRadialMenu = true,
    ISWorldMap = true,
    ISMapWrapper = true,
    ISCraftingUI = true,
    ISHandcraftWindow = true,
    ISBuildWindow = true,
    ISCharacterInfoWindow = true,
}

local CURSOR_WINDOW_CLASS_NAMES = {
    "ISRadialMenu",
    "ISWorldMap",
    "ISMapWrapper",
    "ISCraftingUI",
    "ISHandcraftWindow",
    "ISBuildWindow",
    "ISCharacterInfoWindow",
}
local activeCursorWindows = {}
local cursorRequestAbsentTicks = 0
local cursorRequestLastAbsentTick = -1
local CURSOR_REQUEST_RELEASE_DELAY = 5

local CURSOR_WINDOW_TYPES = {}
for _, name in ipairs(CURSOR_WINDOW_CLASS_NAMES) do CURSOR_WINDOW_TYPES[name] = true end

-- windows put in activeCursorWindows by a scan rather than by a hook; only
-- those are pruned when the scan stops seeing them
local scanRegistered = {}

-- U1/N4: the UI state is recomputed when a hook marks it dirty, or on a short
-- poll as the safety net for changes no hook sees.
local uiState = {
    dirty = true, scanDue = true,
    refreshTick = -1000, scanTick = -1000,
    conversationClosed = false,
    inventoryOpen = false, hasOtherUi = false, inVehicle = false,
}
local UI_POLL_TICKS = 4
local UI_SCAN_TICKS = 30

local function markUiDirty(scan)
    uiState.dirty = true
    if scan then uiState.scanDue = true end
end

local ensureUiState

local function visibleInventory(getter)
    if type(getter) ~= "function" then return false end
    local ok, ui = pcall(getter, 0)
    if not ok or not isVisible(ui) then return false end

    local collapsed = ui.isCollapsed
    if type(collapsed) == "function" then
        local collapsedOk, value = pcall(collapsed, ui)
        if collapsedOk then collapsed = value end
    elseif collapsed == nil then
        collapsed = ui.collapsed
    end
    return collapsed ~= true
end

local function playerIsInVehicle()
    if type(getSpecificPlayer) ~= "function" then return false end
    local ok, player = pcall(getSpecificPlayer, 0)
    if not ok or not player or not player.getVehicle then return false end
    local okVehicle, vehicle = pcall(player.getVehicle, player)
    return okVehicle and vehicle ~= nil
end

local function visibleErgonomicInventory()
    local panel = ErgonomicUI and ErgonomicUI.panel
    if not panel then return false end
    if not isVisible(panel) then
        panel._viewpointSupportInventoryOpen = false
        return false
    end
    local bodyVisible
    if type(panel.isBodyVisible) == "function" then
        local ok, value = pcall(function() return panel:isBodyVisible() end)
        if ok then bodyVisible = value == true end
    end
    if bodyVisible == false then
        panel._viewpointSupportInventoryOpen = false
        panel._viewpointSupportCursorRequested = false
        return false
    end
    return bodyVisible == true or panel._viewpointSupportInventoryOpen == true
end

local function pushInteractionMenuState()
    if not Support then return end
    local hideInteractionMenu = cfg.hideInventoryMenu
    local inventoryOpen = uiState.inventoryOpen
    if Support.setInventoryOpen then
        Support.setInventoryOpen(hideInteractionMenu and inventoryOpen)
    end
    if Support.setInteractionMenuSuppressed then
        Support.setInteractionMenuSuppressed(
            conversationCursorOwned
                or (hideInteractionMenu and inventoryOpen)
        )
    end
end

local function updateInteractionMenuSuppression()
    if not Support then return end
    ensureUiState()
end

--- Finds watched windows the hooks never saw (opened before they were
--- installed) and forgets scan-found ones that have left the UI manager.
--- V1: one Java call when VESPerf.jar is loaded, the Lua loop otherwise.
local function scanUIManager()
    local present = {}
    local scanned = false

    local fast = vesPerfWatchedWindows
    if type(fast) == "function" then
        local ok, list = pcall(fast)
        if ok and list then
            for i = 1, #list do
                local window = list[i]
                present[window] = true
                if activeCursorWindows[window] == nil then
                    activeCursorWindows[window] = window.Type
                    scanRegistered[window] = true
                end
            end
            scanned = true
        end
    end

    if not scanned then
        local manager = UIManager
        if not manager or type(manager.getUI) ~= "function" then return end
        local ok, windows = pcall(manager.getUI)
        if not ok or not windows or type(windows.size) ~= "function" or type(windows.get) ~= "function" then
            return
        end
        local sizeOk, size = pcall(windows.size, windows)
        if not sizeOk or type(size) ~= "number" then return end
        for index = 0, size - 1 do
            local windowOk, window = pcall(windows.get, windows, index)
            if windowOk and window then
                if type(window.getTable) == "function" then
                    local tableOk, uiTable = pcall(window.getTable, window)
                    if tableOk and uiTable then window = uiTable end
                end
                local className = window.Type
                if CURSOR_WINDOW_TYPES[className] then
                    present[window] = true
                    if activeCursorWindows[window] == nil then
                        activeCursorWindows[window] = className
                        scanRegistered[window] = true
                    end
                end
            end
        end
    end

    for window in pairs(scanRegistered) do
        if not present[window] then
            scanRegistered[window] = nil
            activeCursorWindows[window] = nil
        end
    end
end

local function visibleGameWindow(vanillaInventory)
    if vanillaInventory then return true end

    local allowedClasses = uiState.inVehicle and VEHICLE_CURSOR_WINDOWS or ON_FOOT_CURSOR_WINDOWS
    for window, className in pairs(activeCursorWindows) do
        if allowedClasses[className] and isVisible(window) then
            return true
        end
    end
    return false
end

local updateCursorRequest
local tickCounter = 0

local function installCursorWindowHook(className)
    local windowClass = _G[className]
    if type(windowClass) ~= "table" or windowClass._viewpointSupportCursorHooks then return end

    local originalAdd = windowClass.addToUIManager
    local originalRemove = windowClass.removeFromUIManager
    if type(originalAdd) ~= "function" or type(originalRemove) ~= "function" then return end

    windowClass.addToUIManager = function(self, ...)
        local result = originalAdd(self, ...)
        activeCursorWindows[self] = className
        scanRegistered[self] = nil
        markUiDirty(false)
        if updateCursorRequest then updateCursorRequest() end
        return result
    end
    windowClass.removeFromUIManager = function(self, ...)
        local result = originalRemove(self, ...)
        activeCursorWindows[self] = nil
        scanRegistered[self] = nil
        markUiDirty(false)
        if updateCursorRequest then updateCursorRequest() end
        return result
    end
    windowClass._viewpointSupportCursorHooks = true
end

local function installCursorWindowHooks()
    for _, className in ipairs(CURSOR_WINDOW_CLASS_NAMES) do
        installCursorWindowHook(className)
    end
end

local function installVanillaInventoryCursorIntegration()
    local inventoryClass = ISInventoryPage
    if type(inventoryClass) ~= "table" or type(inventoryClass.setVisible) ~= "function" then return end
    if inventoryClass._viewpointSupportSetVisible == inventoryClass.setVisible then return end

    local originalSetVisible = inventoryClass.setVisible
    local wrapper = function(self, visible, ...)
        local result = originalSetVisible(self, visible, ...)
        local isPlayerInventory = false
        if type(getPlayerInventory) == "function" then
            local ok, inventory = pcall(getPlayerInventory, 0)
            isPlayerInventory = ok and self == inventory
        end
        if not isPlayerInventory and type(getPlayerLoot) == "function" then
            local ok, loot = pcall(getPlayerLoot, 0)
            isPlayerInventory = ok and self == loot
        end
        if isPlayerInventory then
            markUiDirty(false)
            updateInteractionMenuSuppression()
            updateCursorRequest()
        end
        return result
    end
    inventoryClass.setVisible = wrapper
    inventoryClass._viewpointSupportSetVisible = wrapper

    if type(inventoryClass.onToggleVisible) == "function"
        and inventoryClass._viewpointSupportToggleVisible ~= inventoryClass.onToggleVisible then
        local originalOnToggleVisible = inventoryClass.onToggleVisible
        local toggleWrapper = function(self, ...)
            local result = originalOnToggleVisible(self, ...)
            markUiDirty(false)
            updateInteractionMenuSuppression()
            updateCursorRequest()
            return result
        end
        inventoryClass.onToggleVisible = toggleWrapper
        inventoryClass._viewpointSupportToggleVisible = toggleWrapper
    end
end

local function installErgonomicUIIntegration()
    if not cfg.ergonomicUi then return end
    if not ErgUIPanel then return end

    if ErgUIPanel._viewpointSupportInstalled then return end
    ErgUIPanel._viewpointSupportInstalled = true

    if ErgUIPanel.togglePanelManual then
        local originalToggle = ErgUIPanel.togglePanelManual
        ErgUIPanel.togglePanelManual = function(self, ...)
            local result = originalToggle(self, ...)
            local visible = self:isVisible() and self:isBodyVisible()
            self._viewpointSupportInventoryOpen = visible
            self._viewpointSupportCursorRequested = visible
            markUiDirty(false)
            updateInteractionMenuSuppression()
            updateCursorRequest()
            return result
        end
    end

    if ErgUIPanel.revealFromContainer then
        local originalReveal = ErgUIPanel.revealFromContainer
        ErgUIPanel.revealFromContainer = function(self, ...)
            local result = originalReveal(self, ...)
            self._viewpointSupportInventoryOpen = true
            self._viewpointSupportCursorRequested = true
            markUiDirty(false)
            updateInteractionMenuSuppression()
            updateCursorRequest()
            return result
        end
    end

    if ErgUIPanel.openSettings then
        local originalSettings = ErgUIPanel.openSettings
        ErgUIPanel.openSettings = function(self, ...)
            local result = originalSettings(self, ...)
            self._viewpointSupportCursorRequested = true
            markUiDirty(false)
            updateCursorRequest()
            return result
        end
    end
end

local function ergonomicUiRequestsCursor()
    if not cfg.ergonomicUi then return false end
    local panel = ErgonomicUI and ErgonomicUI.panel
    if not panel then return false end
    if not panel:isVisible() then
        panel._viewpointSupportCursorRequested = false
        return false
    end
    return panel._viewpointSupportCursorRequested == true
end

updateCursorRequest = function()
    if not Support or not Support.setAutoCursorRequested then return end
    ensureUiState()
    local conversationClosed = uiState.conversationClosed
    uiState.conversationClosed = false
    local hasOtherUi = uiState.hasOtherUi
    local requested = conversationCursorOwned or hasOtherUi
    if not conversationCursorOwned and not cfg.autoUi then
        requested = false
    end

    if requested then
        cursorRequestAbsentTicks = 0
        cursorRequestLastAbsentTick = -1
        Support.setAutoCursorRequested(true)
    elseif cursorRequestLastAbsentTick ~= tickCounter then
        cursorRequestAbsentTicks = cursorRequestAbsentTicks + 1
        cursorRequestLastAbsentTick = tickCounter
        if cursorRequestAbsentTicks >= CURSOR_REQUEST_RELEASE_DELAY then
            Support.setAutoCursorRequested(false)
        end
    elseif cursorRequestAbsentTicks >= CURSOR_REQUEST_RELEASE_DELAY then
        Support.setAutoCursorRequested(false)
    end

    if conversationClosed and not hasOtherUi and type(Support.isFreeCursor) == "function"
            and not Support.isFreeCursor() then
        local qol = ViewpointQOL
        if qol and type(qol.lockMouse) == "function" and type(qol.isAvailable) == "function" then
            local ok, available = pcall(qol.isAvailable)
            if ok and available then pcall(qol.lockMouse) end
        end
    end
end

local vehicleCameraForced = false
local vehicleCameraModeOnEntry
local previousThirdPersonView
local lastOnFootThirdPersonView

local function rememberOnFootCamera()
    if playerIsInVehicle()
            or not Support or type(Support.isViewEnabled) ~= "function"
            or type(Support.isThirdPerson) ~= "function" then return end
    local enabledOk, enabled = pcall(Support.isViewEnabled)
    if not enabledOk or enabled ~= true then return end
    local viewOk, thirdPerson = pcall(Support.isThirdPerson)
    if viewOk and type(thirdPerson) == "boolean" then
        lastOnFootThirdPersonView = thirdPerson
    end
end

local function onEnterVehicle(player)
    if player ~= getSpecificPlayer(0) then return end
    local mode = vehicleCameraMode()
    if mode == VEHICLE_CAMERA_OFF then return end
    if not Support or not Support.isViewEnabled or not Support.setThirdPerson then return end
    if not Support.isViewEnabled() then return end
    if vehicleCameraForced then return end
    vehicleCameraModeOnEntry = mode
    previousThirdPersonView = nil
    if mode == VEHICLE_CAMERA_KEEP_PREVIOUS_VIEW then
        previousThirdPersonView = lastOnFootThirdPersonView
        if not playerIsInVehicle() or previousThirdPersonView == nil then
            local ok, thirdPerson = pcall(Support.isThirdPerson)
            if ok and type(thirdPerson) == "boolean" then
                previousThirdPersonView = thirdPerson
            end
        end
    end
    vehicleCameraForced = true
    if mode == VEHICLE_CAMERA_FORCE_THIRD_PERSON then
        Support.setThirdPerson(true)
    elseif previousThirdPersonView ~= nil then
        Support.setThirdPerson(previousThirdPersonView)
    end
end

local function onExitVehicle(player)
    if player and player ~= getSpecificPlayer(0) then return end
    if vehicleCameraForced and Support and Support.setThirdPerson then
        if vehicleCameraModeOnEntry == VEHICLE_CAMERA_FORCE_THIRD_PERSON then
            Support.setThirdPerson(false)
        elseif vehicleCameraModeOnEntry == VEHICLE_CAMERA_KEEP_PREVIOUS_VIEW
                and previousThirdPersonView ~= nil then
            Support.setThirdPerson(previousThirdPersonView)
        end
    end
    vehicleCameraForced = false
    vehicleCameraModeOnEntry = nil
    previousThirdPersonView = nil
end

local function maintainVehicleCamera()
    if not vehicleCameraForced or not playerIsInVehicle()
            or not Support or type(Support.isViewEnabled) ~= "function"
            or type(Support.isThirdPerson) ~= "function"
            or type(Support.setThirdPerson) ~= "function" then return end
    local enabledOk, enabled = pcall(Support.isViewEnabled)
    if not enabledOk or enabled ~= true then return end

    local target
    if vehicleCameraModeOnEntry == VEHICLE_CAMERA_FORCE_THIRD_PERSON then
        target = true
    elseif vehicleCameraModeOnEntry == VEHICLE_CAMERA_KEEP_PREVIOUS_VIEW then
        target = previousThirdPersonView
    end
    if target == nil then return end

    local viewOk, thirdPerson = pcall(Support.isThirdPerson)
    if viewOk and thirdPerson ~= target then
        pcall(Support.setThirdPerson, target)
    end
end

-- N1: everything the cursor and menu-suppression updates need, computed once
ensureUiState = function()
    if not uiState.dirty and tickCounter - uiState.refreshTick < UI_POLL_TICKS then return end
    uiState.dirty = false
    uiState.refreshTick = tickCounter

    if uiState.scanDue or tickCounter - uiState.scanTick >= UI_SCAN_TICKS then
        uiState.scanDue = false
        uiState.scanTick = tickCounter
        scanUIManager()
    end

    if syncConversationCursorMode() then uiState.conversationClosed = true end
    uiState.inVehicle = playerIsInVehicle()

    local vanillaInventory = visibleInventory(getPlayerInventory)
        or visibleInventory(getPlayerLoot)
    uiState.inventoryOpen = vanillaInventory
        or (integrations.erg and visibleErgonomicInventory())
        or false
    uiState.hasOtherUi = visibleGameWindow(vanillaInventory)
        or (integrations.erg and ergonomicUiRequestsCursor())
        or false

    pushInteractionMenuState()
end

-- the allowed window set depends on being in a vehicle
Events.OnEnterVehicle.Add(function(...)
    markUiDirty(false)
    return onEnterVehicle(...)
end)
Events.OnExitVehicle.Add(function(...)
    markUiDirty(false)
    return onExitVehicle(...)
end)

local contextMenuIconsHooked = false
local contextMenuIconsInitialized = false
local activeContextMenuIconPackName
local activeContextMenuIconPack

local function registerContextMenuIcons(optionsTable, packName, pack)
    for localizationKey, details in pairs(optionsTable or {}) do
        local textureName = type(details) == "string" and details or nil
        if type(details) == "table" then
            textureName = details.iconTextureName
        end

        if textureName and type(localizationKey) == "string" then
            local folder = pack.settings and pack.settings.textureFolderName or packName
            local texturePath = "media/ui/cmi/" .. folder .. "/" .. textureName
            local cmi = ContextMenuIcons
            if cmi.IconHandler and cmi.IconHandler.getIconPath then
                local ok, path = pcall(cmi.IconHandler.getIconPath, packName, textureName)
                if ok and path then texturePath = path end
            end

            local ok, texture = pcall(getTexture, texturePath)
            if ok and texture then
                Support.setContextMenuIcon(getText(localizationKey), texture)
                Support.setContextMenuIcon(localizationKey, texture)
            end
        end

        if type(details) == "table" and details.subOptions then
            registerContextMenuIcons(details.subOptions, packName, pack)
        end
    end
end

local function refreshContextMenuIcons()
    if not Support or not Support.clearContextMenuIcons then return end
    Support.clearContextMenuIcons()

    local cmi = ContextMenuIcons
    local preferences = cmi and cmi.preferences
    local packName = preferences and preferences.iconPackName
    local pack = packName and cmi.iconPacks and cmi.iconPacks[packName]
    if pack and not cmi.isNoneIconPackSelected then
        local options = pack.options and pack.options.world
        registerContextMenuIcons(options, packName, pack)
    end

    if Support.refreshContextMenuIcons then
        Support.refreshContextMenuIcons()
    end
    activeContextMenuIconPackName = packName
    activeContextMenuIconPack = pack
    contextMenuIconsInitialized = true
end

local function installContextMenuIconsIntegration()
    local cmi = ContextMenuIcons
    if type(cmi) ~= "table" or not cmi.Events or not cmi.Events.OnPreferencesApplied then return end
    if not Support or not Support.clearContextMenuIcons then return end

    if not contextMenuIconsHooked then
        cmi.Events.OnPreferencesApplied(refreshContextMenuIcons)
        contextMenuIconsHooked = true
    end

    local preferences = cmi.preferences
    local packName = preferences and preferences.iconPackName
    local pack = packName and cmi.iconPacks and cmi.iconPacks[packName]
    if not contextMenuIconsInitialized
            or packName ~= activeContextMenuIconPackName
            or pack ~= activeContextMenuIconPack then
        refreshContextMenuIcons()
    end
end

local function captureVanillaMenuIcons(menu, captured, visited)
    if not menu or type(menu.options) ~= "table" or visited[menu] then return end
    visited[menu] = true

    local optionCount = tonumber(menu.numOptions) or (#menu.options + 1)
    for index = 1, math.min(optionCount - 1, #menu.options) do
        local option = menu.options[index]
        if option then
            local icon = option.iconTexture
            if not icon and option.itemForTexture then
                local ok, texture = pcall(function() return option.itemForTexture:getTex() end)
                if ok then icon = texture end
            end
            if icon and type(option.name) == "string" then
                table.insert(captured, { option = option, icon = icon })
            end
            if option.subOption and menu.getSubMenu then
                local ok, submenu = pcall(function() return menu:getSubMenu(option.subOption) end)
                if ok then captureVanillaMenuIcons(submenu, captured, visited) end
            end
        end
    end
end

local nilActionArgument = {}

local function actionIndexChild(node, key, identityKeys, create)
    local keyType = type(key)
    if keyType ~= "string" and keyType ~= "number" and keyType ~= "boolean" then
        local identity
        for index, candidate in ipairs(identityKeys) do
            if rawequal(candidate, key) then
                identity = index
                break
            end
        end
        if identity == nil then
            if not create then return nil end
            identityKeys[#identityKeys + 1] = key
            identity = #identityKeys
        end
        key = identity
        keyType = "identity:" .. keyType
    end

    local typedNode = node[keyType]
    if not typedNode then
        if not create then return nil end
        typedNode = {}
        node[keyType] = typedNode
    end

    local child = typedNode[key]
    if not child then
        if not create then return nil end
        child = {}
        typedNode[key] = child
    end
    return child
end

local function actionIndexNode(index, fn, args, create, identityKeys)
    if fn == nil or type(args) ~= "table" then return nil end
    local node = actionIndexChild(index, fn, identityKeys, create)
    if not node then return nil end

    for argument = 1, 11 do
        local key = args[argument]
        if type(key) == "number" and key ~= key then return nil end
        if key == nil then key = nilActionArgument end
        node = actionIndexChild(node, key, identityKeys, create)
        if not node then return nil end
    end
    return node
end

local function optionArguments(option)
    return { option.target, option.param1, option.param2, option.param3, option.param4,
        option.param5, option.param6, option.param7, option.param8, option.param9, option.param10 }
end

local function publishVanillaMenuIcons(captured)
    if not Support or not Support.clearVanillaContextMenuIcons
            or not Support.setVanillaContextMenuIcon then return end

    Support.clearVanillaContextMenuIcons()
    for _, entry in ipairs(captured) do
        local option = entry.option
        if option.subOption then
            Support.setVanillaContextMenuIcon(option.name, entry.icon)
        end
    end

    local actions = ViewpointInteract and ViewpointInteract.actions or {}
    local iconIndex = {}
    local identityKeys = {}
    for _, entry in ipairs(captured) do
        local option = entry.option
        if not option.subOption then
            local node = actionIndexNode(iconIndex, option.onSelect, optionArguments(option), true,
                identityKeys)
            if node then
                node.entries = node.entries or {}
                table.insert(node.entries, entry)
            end
        end
    end

    for _, action in ipairs(actions) do
        local node = actionIndexNode(iconIndex, action.fn, action.args, false, identityKeys)
        if node and node.entries then
            local nextEntry = node.nextEntry or 1
            local entry = node.entries[nextEntry]
            if entry then
                node.nextEntry = nextEntry + 1
                Support.setVanillaContextMenuIcon(action.name, entry.icon)
            end
        end
    end
end

local interactionMenuDiagnosticStates = {}
local lastCharacterHarvestTarget
local lastAimedALifeNpc
local alifeNpcTagMenuFallbackLogged = false
local vanillaMenuIconFailureLogged = false
local lastALifeHarvestDiagnostic
local lastALifeHarvestDiagnosticAt = 0

local function diagnosticLog(message)
    if cfg.diagnostics then
        print("[Viewpoint Extended Support] " .. message)
    end
end

local function logInteractionMenuDiagnostic(key, message)
    if not cfg.diagnostics then return end
    if interactionMenuDiagnosticStates[key] == message then return end
    interactionMenuDiagnosticStates[key] = message
    diagnosticLog("interaction menu: " .. message)
end

local function isProjectALifeNpc(target)
    if not target then return false end

    local speech = ProjectALife and ProjectALife.Speech
    if type(speech) == "table" and type(speech.variable) == "function" then
        local ok, uid = pcall(speech.variable, target, "ALifeUID")
        if ok and uid ~= nil and uid ~= "" then return true end
    end

    if type(target.getModData) == "function" then
        local ok, data = pcall(target.getModData, target)
        return ok and type(data) == "table" and type(data.ProjectALifeUID) == "string"
            and data.ProjectALifeUID ~= ""
    end
    return false
end

local function collectContextOptions(menu, options, visited)
    if not menu or visited[menu] or type(menu.options) ~= "table" then return end
    visited[menu] = true

    for _, option in ipairs(menu.options) do
        if type(option) == "table" then
            options[option] = true
            if option.subOption and type(menu.getSubMenu) == "function" then
                local ok, submenu = pcall(function() return menu:getSubMenu(option.subOption) end)
                if ok then collectContextOptions(submenu, options, visited) end
            end
        end
    end
end

local function linkNewContextActionsToTarget(menu, target, existingOptions, visited)
    if not menu or visited[menu] or type(menu.options) ~= "table" then return 0 end
    visited[menu] = true

    local linked = 0
    for _, option in ipairs(menu.options) do
        if type(option) == "table" then
            if not existingOptions[option] then
                local lastParameter = 0
                local alreadyTargetsNpc = option.target == target
                for index = 1, 10 do
                    local parameter = option["param" .. index]
                    if parameter ~= nil then lastParameter = index end
                    if parameter == target then alreadyTargetsNpc = true end
                end
                if option.onSelect and not option.subOption and not alreadyTargetsNpc
                        and lastParameter < 10 then
                    option["param" .. (lastParameter + 1)] = target
                    linked = linked + 1
                end
            end
            if option.subOption and type(menu.getSubMenu) == "function" then
                local ok, submenu = pcall(function() return menu:getSubMenu(option.subOption) end)
                if ok then
                    linked = linked + linkNewContextActionsToTarget(submenu, target,
                        existingOptions, visited)
                end
            end
        end
    end
    return linked
end

local function diagnoseCharacterHarvestTarget(target)
    local isCharacter = false
    if target and type(instanceof) == "function" then
        local ok, result = pcall(instanceof, target, "IsoGameCharacter")
        isCharacter = ok and result == true
    end

    if not isCharacter then
        lastCharacterHarvestTarget = nil
        lastAimedALifeNpc = nil
        return
    end

    local isALifeNpc = isProjectALifeNpc(target)
    lastAimedALifeNpc = isALifeNpc and target or nil
    if target == lastCharacterHarvestTarget then return end

    lastCharacterHarvestTarget = target
    logInteractionMenuDiagnostic("target", "Viewpoint harvested a character target; Project A-Life NPC="
        .. tostring(isALifeNpc))
end

local function logALifeHarvestResult(message)
    local now = type(getTimestampMs) == "function" and getTimestampMs() or 0
    if message == lastALifeHarvestDiagnostic and now - lastALifeHarvestDiagnosticAt < 3000 then return end
    lastALifeHarvestDiagnostic = message
    lastALifeHarvestDiagnosticAt = now
    diagnosticLog("A-Life interaction harvest: " .. message)
end

local function installViewpointVanillaMenuIconsIntegration()
    local interactions = ViewpointInteract
    if type(interactions) ~= "table" then
        logInteractionMenuDiagnostic("harvest-hook", "waiting for ViewpointInteract")
        return
    end
    if type(interactions.harvest) ~= "function" then
        logInteractionMenuDiagnostic("harvest-hook", "ViewpointInteract.harvest is unavailable")
        return
    end
    if type(ISWorldObjectContextMenu) ~= "table"
            or type(ISWorldObjectContextMenu.createMenu) ~= "function" then
        logInteractionMenuDiagnostic("harvest-hook", "vanilla world context-menu builder is unavailable")
        return
    end

    if interactions._extendedSupportVanillaIconHarvest == interactions.harvest
            or interactions._extendedSupportLazyHarvest == interactions.harvest then return end
    local originalHarvest = interactions.harvest
    local worldMenu = ISWorldObjectContextMenu
    local wrapper = function(...)
        local player, target = ...
        local isALifeNpc = isProjectALifeNpc(target)
        local contextMenuClass = ISContextMenu
        local originalContextMenuGet = isALifeNpc and type(contextMenuClass) == "table"
            and contextMenuClass.get or nil
        local capturedContext
        local interceptContextMenuGet
        if type(originalContextMenuGet) == "function" then
            interceptContextMenuGet = function(...)
                local menu = originalContextMenuGet(...)
                if capturedContext == nil then capturedContext = menu end
                return menu
            end
            contextMenuClass.get = interceptContextMenuGet
        end

        local originalTriggerEvent = isALifeNpc and type(triggerEvent) == "function" and triggerEvent or nil
        local fillEventTriggered = false
        local linkedContextActions = 0
        local interceptTriggerEvent
        if originalTriggerEvent then
            interceptTriggerEvent = function(eventName, ...)
                local args = { ... }
                local eventContext = eventName == "OnFillWorldObjectContextMenu" and args[2] or nil
                local isTargetFill = eventContext ~= nil and eventContext == capturedContext
                local existingOptions
                if isTargetFill then
                    existingOptions = {}
                    collectContextOptions(eventContext, existingOptions, {})
                end
                local result = originalTriggerEvent(eventName, ...)
                if isTargetFill then
                    fillEventTriggered = true
                    linkedContextActions = linkedContextActions + linkNewContextActionsToTarget(
                        eventContext, target, existingOptions, {})
                end
                return result
            end
            triggerEvent = interceptTriggerEvent
        end

        local captured = {}
        local originalCreateMenu = worldMenu.createMenu
        local menuBuilder = worldMenu._NB_old_createMenu
        if type(menuBuilder) ~= "function" then
            menuBuilder = originalCreateMenu
        end
        local createMenuCalled = false
        local fetchCount
        local recoveredContext = false
        local filledRecoveredContext = false
        local interceptCreateMenu = function(...)
            createMenuCalled = true
            local menu = menuBuilder(...)
            if isALifeNpc and capturedContext ~= nil then
                local fetch = worldMenu.fetchVars
                fetchCount = type(fetch) == "table" and tonumber(fetch.c) or nil
                if not fillEventTriggered and originalTriggerEvent and player
                        and type(player.getPlayerNum) == "function" then
                    local ok, playerNum = pcall(player.getPlayerNum, player)
                    if ok then
                        local existingOptions = {}
                        collectContextOptions(capturedContext, existingOptions, {})
                        local fillOk = pcall(originalTriggerEvent, "OnFillWorldObjectContextMenu", playerNum,
                            capturedContext, { target }, false)
                        filledRecoveredContext = fillOk
                        fillEventTriggered = fillOk
                        if fillOk then
                            linkedContextActions = linkedContextActions + linkNewContextActionsToTarget(
                                capturedContext, target, existingOptions, {})
                        end
                    end
                end
                if type(menu) ~= "table" then
                    menu = capturedContext
                    recoveredContext = true
                end
            end
            captureVanillaMenuIcons(menu, captured, {})
            return menu
        end

        worldMenu.createMenu = interceptCreateMenu
        local ok, result = pcall(originalHarvest, ...)
        if worldMenu.createMenu == interceptCreateMenu then
            worldMenu.createMenu = originalCreateMenu
        end
        if interceptTriggerEvent and triggerEvent == interceptTriggerEvent then
            triggerEvent = originalTriggerEvent
        end
        if interceptContextMenuGet and contextMenuClass.get == interceptContextMenuGet then
            contextMenuClass.get = originalContextMenuGet
        end

        if isALifeNpc then
            local resultSummary
            if not ok then
                resultSummary = "harvest failed: " .. tostring(result)
            elseif type(result) == "table" and type(result.labels) == "table" then
                local labels = {}
                for index = 1, math.min(#result.labels, 8) do
                    labels[index] = tostring(result.labels[index])
                end
                resultSummary = "menu labels=" .. tostring(#result.labels)
                    .. ", seen=" .. tostring(result.seen)
                    .. ", entries=" .. table.concat(labels, " | ")
            else
                resultSummary = "no labels; reason=" .. tostring(type(result) == "table" and result.why or result)
            end
            logALifeHarvestResult(resultSummary .. "; createMenu=" .. tostring(createMenuCalled)
                .. ", fetchCount=" .. tostring(fetchCount)
                .. ", fillEvent=" .. tostring(fillEventTriggered)
                .. ", npcActionsLinked=" .. tostring(linkedContextActions)
                .. ", contextRecovered=" .. tostring(recoveredContext)
                .. ", recoveredOnFill=" .. tostring(filledRecoveredContext))
        end

        if ok and result and type(result.labels) == "table" then
            local iconsOk, iconError = pcall(publishVanillaMenuIcons, captured)
            if not iconsOk then
                if Support and Support.clearVanillaContextMenuIcons then
                    pcall(Support.clearVanillaContextMenuIcons)
                end
                if not vanillaMenuIconFailureLogged then
                    diagnosticLog("vanilla interaction icon mapping failed: " .. tostring(iconError))
                    vanillaMenuIconFailureLogged = true
                end
            end
        elseif Support and Support.clearVanillaContextMenuIcons then
            pcall(Support.clearVanillaContextMenuIcons)
        end
        if not ok then error(result, 0) end
        return result
    end
    interactions.harvest = wrapper
    interactions._extendedSupportVanillaIconHarvest = wrapper
    logInteractionMenuDiagnostic("harvest-hook", "Viewpoint interaction harvest hook installed")

    if type(interactions.harvestVehicle) == "function"
            and interactions._extendedSupportVanillaIconVehicle ~= interactions.harvestVehicle
            and interactions._extendedSupportLazyVehicle ~= interactions.harvestVehicle then
        local originalHarvestVehicle = interactions.harvestVehicle
        local vehicleWrapper = function(...)
            if Support and Support.clearVanillaContextMenuIcons then
                Support.clearVanillaContextMenuIcons()
            end
            return originalHarvestVehicle(...)
        end
        interactions.harvestVehicle = vehicleWrapper
        interactions._extendedSupportVanillaIconVehicle = vehicleWrapper
    end
end

local pendingInteractionTarget
local pendingInteractionTicks = 0
local lazyInteractionWarningLogged = false

local function interactionTargetHeading(target)
    if not target then return nil end

    local ok, isGround = pcall(function()
        if target:isFloor() then return true end
        local sprite = target:getSprite()
        local properties = sprite and sprite:getProperties()
        return properties and IsoFlagType and properties:has(IsoFlagType.solidfloor) or false
    end)
    local title
    if ok and isGround then
        title = "Interact with Ground"
    else
        local okName, name = pcall(function() return target:getName() end)
        if okName and type(name) == "string" and name ~= "" then
            title = "Interact with " .. name
        else
            local okObjectName, objectName = pcall(function() return target:getObjectName() end)
            if okObjectName and type(objectName) == "string" and objectName ~= ""
                    and objectName ~= "IsoObject" then
                objectName = objectName:gsub("^Iso", ""):gsub("(%l)(%u)", "%1 %2")
                title = "Interact with " .. objectName
            else
                title = "Interact with Object"
            end
        end
    end

    if Support and type(Support.getInteractionCyclePosition) == "function"
            and type(Support.getInteractionCycleCount) == "function" then
        local okPosition, position = pcall(Support.getInteractionCyclePosition)
        local okCount, count = pcall(Support.getInteractionCycleCount)
        if okPosition and okCount and position > 0 and count > 1 then
            title = title .. " (" .. tostring(position) .. "/" .. tostring(count) .. ")"
        end
    end
    return title
end

local function applyInteractionTargetHeading(result, target)
    if type(result) ~= "table" then return result end
    local title = interactionTargetHeading(target)
    if title then result.title = title end
    return result
end

local function lazyInteractionHarvest(originalHarvest, player, target)
    diagnoseCharacterHarvestTarget(target)
    local alife = ViewpointExtendedSupportALife
    local function harvest()
        if alife and type(alife.harvest) == "function" then
            return alife.harvest(originalHarvest, player, target)
        end
        return originalHarvest(player, target)
    end
    if not optionValue(options.lazyInteractionOptions, true)
            or not Support or type(Support.requestInteractionOptions) ~= "function" then
        pendingInteractionTarget = nil
        pendingInteractionTicks = 0
        return applyInteractionTargetHeading(harvest(), target)
    end

    if pendingInteractionTarget == target then
        pendingInteractionTarget = nil
        pendingInteractionTicks = 0
        return applyInteractionTargetHeading(harvest(), target)
    end

    pendingInteractionTarget = nil
    pendingInteractionTicks = 0
    if Support.clearVanillaContextMenuIcons then
        Support.clearVanillaContextMenuIcons()
    end

    local title = interactionTargetHeading(target)

    ViewpointInteract.actions = {{
        name = "View options",
        fn = function()
            pendingInteractionTarget = target
            pendingInteractionTicks = 0
            local ok, requested = pcall(Support.requestInteractionOptions)
            if not ok or requested ~= true then
                pendingInteractionTarget = nil
                pendingInteractionTicks = 0
                if not lazyInteractionWarningLogged then
                    diagnosticLog("could not request deferred interaction options")
                    lazyInteractionWarningLogged = true
                end
            end
        end,
        args = {},
        n = 0,
        enabled = true,
    }}

    return { title = title, labels = { "View options" }, enabled = { true }, seen = 0 }
end

local function installLazyInteractionOptions()
    local interactions = ViewpointInteract
    if type(interactions) ~= "table" then return end

    if type(interactions.harvest) == "function"
            and interactions._extendedSupportVanillaIconHarvest == interactions.harvest
            and interactions._extendedSupportLazyHarvest ~= interactions.harvest then
        local originalHarvest = interactions.harvest
        local wrapper = function(player, object)
            return lazyInteractionHarvest(originalHarvest, player, object)
        end
        interactions.harvest = wrapper
        interactions._extendedSupportLazyHarvest = wrapper
        logInteractionMenuDiagnostic("lazy-hook", "deferred interaction harvest hook installed")
    elseif type(interactions.harvest) == "function" then
        logInteractionMenuDiagnostic("lazy-hook", "waiting for the vanilla interaction harvest hook")
    end

    if type(interactions.harvestVehicle) == "function"
            and interactions._extendedSupportVanillaIconVehicle == interactions.harvestVehicle
            and interactions._extendedSupportLazyVehicle ~= interactions.harvestVehicle then
        local originalHarvestVehicle = interactions.harvestVehicle
        local wrapper = function(player, vehicle)
            return lazyInteractionHarvest(originalHarvestVehicle, player, vehicle)
        end
        interactions.harvestVehicle = wrapper
        interactions._extendedSupportLazyVehicle = wrapper
    end
end

local function viewpointAimHudActive()
    if not Support or type(Support.isViewEnabled) ~= "function" then return false end
    local ok, enabled = pcall(Support.isViewEnabled)
    if not ok or enabled ~= true then return false end
    if type(Support.shouldSkipInteractionMenu) == "function" then
        local skipOk, skip = pcall(Support.shouldSkipInteractionMenu)
        if skipOk and skip == true then return false end
    end
    return true
end

local function aimedALifeNpc()
    if Support and type(Support.getAimedInteractionObject) == "function" then
        local ok, target = pcall(Support.getAimedInteractionObject)
        if ok then
            if target and isProjectALifeNpc(target) then
                lastAimedALifeNpc = target
                return target
            end
            lastAimedALifeNpc = nil
            return nil
        end
    end

    if Support and type(Support.getAimedALifeNpc) == "function" then
        local ok, target = pcall(Support.getAimedALifeNpc)
        if ok and target then
            lastAimedALifeNpc = target
            return target
        end
    end

    if lastAimedALifeNpc then
        if not alifeNpcTagMenuFallbackLogged then
            diagnosticLog("A-Life NPC tag using the confirmed interaction-menu target")
            alifeNpcTagMenuFallbackLogged = true
        end
        return lastAimedALifeNpc
    end
    return nil
end

local function alifeNpcUid(target)
    local speech = ProjectALife and ProjectALife.Speech
    if type(speech) == "table" and type(speech.variable) == "function" then
        local ok, uid = pcall(speech.variable, target, "ALifeUID")
        if ok and uid ~= nil and uid ~= "" then return tostring(uid) end
    end
    if target and type(target.getModData) == "function" then
        local ok, data = pcall(target.getModData, target)
        if ok and type(data) == "table" and data.ProjectALifeUID ~= nil
                and data.ProjectALifeUID ~= "" then
            return tostring(data.ProjectALifeUID)
        end
    end
    return nil
end

local function alifeRecordForCompanionDogs(uid)
    local alife = ProjectALife
    if type(alife) ~= "table" then return nil end

    local executor = alife.Executor
    local records = executor and executor.mirror and executor.mirror.records
    local record = type(records) == "table" and records[uid] or nil
    if type(record) == "table" then return record end

    local registry = alife.ActorRegistry
    local lookup = type(registry) == "table" and (registry.peek or registry.read) or nil
    if type(lookup) ~= "function" then return nil end
    local ok, registered = pcall(lookup, uid)
    return ok and type(registered) == "table" and registered or nil
end

local function isFriendlyOrAlliedALifeNpcForCompanionDogs(zombie)
    local uid = alifeNpcUid(zombie)
    if not uid or type(getSpecificPlayer) ~= "function" then return false end

    local okPlayer, player = pcall(getSpecificPlayer, 0)
    if not okPlayer or not player then return false end

    local record = alifeRecordForCompanionDogs(uid)
    local relations = ProjectALife and ProjectALife.Relations
    if not record or type(relations) ~= "table" or type(relations.playerStance) ~= "function" then
        return false
    end

    if type(relations.hostileToPlayer) == "function" then
        local okHostile, hostile = pcall(relations.hostileToPlayer, record, player)
        if okHostile and hostile == true then return false end
    end

    local okStance, stance = pcall(relations.playerStance, record, player)
    if not okStance then return false end
    stance = string.lower(tostring(stance or ""))
    return stance == "friendly" or stance == "allied"
end

local function installCompanionDogsFriendlyNpcFilter()
    local dogs = CompanionDogs
    if type(dogs) ~= "table" or type(dogs.isFriendlyNPC) ~= "function" then return end
    if dogs._viewpointSupportALifeFriendlyNPC == dogs.isFriendlyNPC then return end

    local original = dogs.isFriendlyNPC
    local wrapper = function(zombie, ...)
        if isFriendlyOrAlliedALifeNpcForCompanionDogs(zombie) then return true end
        return original(zombie, ...)
    end
    dogs.isFriendlyNPC = wrapper
    dogs._viewpointSupportALifeFriendlyNPC = wrapper
    diagnosticLog("Companion Dogs ignores Friendly/Allied A-Life NPCs in client threat checks")
end

local function placeNpcTagAboveAimedNpc(label, target)
    if not target or not Support or type(Support.projectCompanionDogTag) ~= "function" then
        return false
    end

    local layout = ProjectALife and ProjectALife.SpeechBubbleLayout
    local rise = layout and layout.settings and layout.settings.headRise or 0.66
    if type(rise) ~= "number" then rise = 0.66 end
    local ok, projected = pcall(function()
        return Support.projectCompanionDogTag(0, target:getX(), target:getY(),
            target:getZ() + rise)
    end)
    if not ok or not projected or not projected.size or projected:size() < 2 then return false end

    local okPosition, screenX, screenY = pcall(function()
        return projected:get(0) - getPlayerScreenLeft(0),
            projected:get(1) - getPlayerScreenTop(0)
    end)
    if not okPosition then return false end

    label:setX(screenX - label.width / 2)
    label:setY(screenY - label.height - 4)
    return true
end

local alifeNpcTagIntegrationLogged = false
local alifeNpcTagWaitingLogged = false
local alifeNpcTagLabelLogged = false
local alifeNpcTagTargetLogged = false
local alifeNpcTagNoAimLogged = false
local alifeNpcTagUidFailureLogged = false
local alifeNpcTagDataFailureLogged = false
local alifeNpcTagProjectionLogged = false

local function installALifeNpcTagIntegration()
    local npcTags = ProjectALifeJimmy and ProjectALifeJimmy.NpcTags
    if type(npcTags) ~= "table" or type(npcTags.hovered) ~= "function"
            or type(npcTags.extraLines) ~= "function" then
        if not alifeNpcTagWaitingLogged then
            diagnosticLog("A-Life NPC tag integration is waiting for ProjectALifeJimmy.NpcTags")
            alifeNpcTagWaitingLogged = true
        end
        return
    end

    if npcTags._viewpointSupportHovered ~= npcTags.hovered then
        local originalHovered = npcTags.hovered
        local wrapper = function(playerNum, player)
            npcTags._viewpointSupportAimedTarget = nil
            if not viewpointAimHudActive() then return originalHovered(playerNum, player) end
            local target = aimedALifeNpc()
            if not target and not alifeNpcTagNoAimLogged then
                diagnosticLog("A-Life NPC tag has no Viewpoint aimed NPC target")
                alifeNpcTagNoAimLogged = true
            end
            local uid = target and alifeNpcUid(target) or nil
            if not uid then
                if target and not alifeNpcTagUidFailureLogged then
                    diagnosticLog("A-Life NPC tag target has no A-Life UID")
                    alifeNpcTagUidFailureLogged = true
                end
                return nil
            end
            if not alifeNpcTagTargetLogged then
                diagnosticLog("A-Life NPC tag resolved an aimed NPC")
                alifeNpcTagTargetLogged = true
            end
            npcTags._viewpointSupportAimedTarget = target
            return { uid = uid, shell = target }, 0, 0, 1
        end
        npcTags.hovered = wrapper
        npcTags._viewpointSupportHovered = wrapper
    end

    if npcTags._viewpointSupportExtraLines ~= npcTags.extraLines then
        local originalExtraLines = npcTags.extraLines
        local wrapper = function(...)
            local lines = originalExtraLines(...)
            if not viewpointAimHudActive() then return lines end

            local withoutSpots
            for index, line in ipairs(lines) do
                if type(line) == "table" and line.spot ~= nil then
                    if not withoutSpots then
                        withoutSpots = {}
                        for previous = 1, index - 1 do
                            withoutSpots[previous] = lines[previous]
                        end
                    end
                    local copy = {}
                    for key, value in pairs(line) do
                        if key ~= "spot" then copy[key] = value end
                    end
                    withoutSpots[index] = copy
                elseif withoutSpots then
                    withoutSpots[index] = line
                end
            end
            return withoutSpots or lines
        end
        npcTags.extraLines = wrapper
        npcTags._viewpointSupportExtraLines = wrapper
    end
    if not alifeNpcTagIntegrationLogged then
        diagnosticLog("A-Life NPC tag hover integration installed")
        alifeNpcTagIntegrationLogged = true
    end

    local label = npcTags.label
    if label and type(label.prerender) == "function"
            and label._viewpointSupportPrerender ~= label.prerender then
        local originalPrerender = label.prerender
        local wrapper = function(self, ...)
            local result = originalPrerender(self, ...)
            if viewpointAimHudActive() then
                local target = npcTags._viewpointSupportAimedTarget
                if target and not self.show then
                    if not alifeNpcTagDataFailureLogged then
                        diagnosticLog("A-Life NPC tag target found, but its tag data produced no label")
                        alifeNpcTagDataFailureLogged = true
                    end
                elseif self.show then
                    local projected = placeNpcTagAboveAimedNpc(self, target)
                    if not projected then self.show = nil end
                    if not alifeNpcTagProjectionLogged then
                        diagnosticLog("A-Life NPC tag head projection "
                            .. (projected and "succeeded" or "failed"))
                        alifeNpcTagProjectionLogged = true
                    end
                end
            end
            return result
        end
        label.prerender = wrapper
        label._viewpointSupportPrerender = wrapper
    end
    if label and type(label.prerender) == "function" and not alifeNpcTagLabelLogged then
        diagnosticLog("A-Life NPC tag label hook installed")
        alifeNpcTagLabelLogged = true
    end
end

local COMPANION_DOG_TAG_HEAD_OFFSET = 55
local COMPANION_DOG_TAG_WIDTH = 260
local COMPANION_DOG_TAG_LINE_HEIGHT = 16
local companionDogTagHookLogged = false
local companionDogTagProjectionLogged = false

local function positionDogTag(self, projected)
    local screenX = projected:get(0) - getPlayerScreenLeft(self.playerNum)
    local zoom = getCore():getZoom(self.playerNum)
    if not zoom or zoom <= 0 then zoom = 1 end
    local screenY = projected:get(1) - getPlayerScreenTop(self.playerNum)
        - COMPANION_DOG_TAG_HEAD_OFFSET / zoom
    self:setX(screenX - COMPANION_DOG_TAG_WIDTH / 2)
    self:setY(screenY - #self.lines * COMPANION_DOG_TAG_LINE_HEIGHT)
end

local function installCompanionDogTagProjection()
    if not Support or type(Support.projectCompanionDogTag) ~= "function"
            or not UIManager or not UIManager.getUI then return end
    local ok, uiList = pcall(UIManager.getUI)
    if not ok or not uiList or not uiList.size or not uiList.get then return end
    local okSize, size = pcall(uiList.size, uiList)
    if not okSize then return end

    for i = 0, size - 1 do
        local okUi, ui = pcall(uiList.get, uiList, i)
        if okUi and ui and ui.getTable then
            local okTable, uiTable = pcall(ui.getTable, ui)
            if okTable and uiTable and uiTable.Type == "ISCDNameTag"
                    and not uiTable._viewpointSupportDogTagPrerender then
                local originalPrerender = uiTable.prerender
                if type(originalPrerender) == "function" then
                    local wrapper = function(self, ...)
                        local result = originalPrerender(self, ...)
                        if self.draw and self.lines and self.dog and Support.isViewEnabled
                                and Support.isViewEnabled() then
                            local dog = self.dog
                            local projectedOk, projected = pcall(Support.projectCompanionDogTag,
                                self.playerNum, dog:getX(), dog:getY(), dog:getZ())
                            if projectedOk and projected and projected.size
                                    and projected:size() >= 2 then
                                local positionOk = pcall(positionDogTag, self, projected)
                                if not positionOk then
                                    self.draw = false
                                elseif not companionDogTagProjectionLogged then
                                    diagnosticLog("Companion Dogs name-tag camera projection succeeded")
                                    companionDogTagProjectionLogged = true
                                end
                            else
                                self.draw = false
                            end
                        end
                        return result
                    end
                    uiTable.prerender = wrapper
                    uiTable._viewpointSupportDogTagPrerender = wrapper
                    if not companionDogTagHookLogged then
                        diagnosticLog("Companion Dogs name-tag camera hook installed")
                        companionDogTagHookLogged = true
                    end
                end
            end
        end
    end
end

local extractionTrackerClosePending = false

local function logInteractionCycleKeyEvent(key)
    if not cfg.diagnostics then return end

    local previousKey = optionValue(options.interactionPreviousKey, DEFAULT_PREVIOUS_INTERACTION_KEY)
    local nextKey = optionValue(options.interactionNextKey, DEFAULT_NEXT_INTERACTION_KEY)
    if key ~= previousKey and key ~= nextKey
            and key ~= 200 and key ~= 203 and key ~= 205 and key ~= 208 then return end

    print("[Viewpoint Extended Support] interaction cycling key event: key=" .. tostring(key)
        .. ", previousKey=" .. tostring(previousKey)
        .. ", nextKey=" .. tostring(nextKey))
end

if Events and Events.OnKeyPressed then
    Events.OnKeyPressed.Add(logInteractionCycleKeyEvent)
end

local function closeExtractionTrackerOnLoad()
    if not extractionTrackerClosePending then return end
    local trackerClass = WQS_GPSWindow
    if type(trackerClass) ~= "table" then return end
    local tracker = trackerClass.instance
    if type(tracker) ~= "table" or type(tracker.isVisible) ~= "function" then return end

    local ok, visible = pcall(function() return tracker:isVisible() end)
    if not ok then return end

    if visible then
        local closeOk = pcall(function()
            tracker:setVisible(false)
            tracker:removeFromUIManager()
        end)
        if not closeOk then return end
    end

    WQSCheckFlip = true
    extractionTrackerClosePending = false
end

-- V2: integrations that are absent are skipped outright
local function installIntegrations()
    installCursorWindowHooks()
    if integrations.dogs then installCompanionDogsFriendlyNpcFilter() end
    installVanillaInventoryCursorIntegration()
    if integrations.erg then installErgonomicUIIntegration() end
    if integrations.cmi then installContextMenuIconsIntegration() end
    installViewpointVanillaMenuIconsIntegration()
    installLazyInteractionOptions()
    -- the "waiting for" diagnostic still prints when diagnostics are on
    if integrations.alifeTags or cfg.diagnostics then installALifeNpcTagIntegration() end
    if integrations.dogs then installCompanionDogTagProjection() end
end

Events.OnTick.Add(function()
    tickCounter = tickCounter + 1
    rememberOnFootCamera()
    maintainVehicleCamera()
    closeExtractionTrackerOnLoad()
    if pendingInteractionTarget then
        pendingInteractionTicks = pendingInteractionTicks + 1
        if pendingInteractionTicks > 180 then
            pendingInteractionTarget = nil
            pendingInteractionTicks = 0
        end
    end
    if Support and Support.diagnosticTick then
        Support.diagnosticTick()
    end
    if tickCounter % 10 == 0 then
        refreshOptionCache()
        probeIntegrations()
        syncConfiguration()
        installIntegrations()
    end
    updateInteractionMenuSuppression()
    updateCursorRequest()
end)

Events.OnGameStart.Add(function()
    extractionTrackerClosePending = optionValue(options.closeExtractionTrackerOnLoad, true)
    closeExtractionTrackerOnLoad()
    refreshOptionCache()
    probeIntegrations()
    syncConfiguration()
    rememberOnFootCamera()
    installIntegrations()
    markUiDirty(true)
    if optionValue(options.startViewpoint, false) and Support and Support.enableViewpoint then
        Support.enableViewpoint()
    end
end)

syncConfiguration()
logInteractionMenuDiagnostic("diagnostics", "interaction diagnostics active (0.4.35)")
