import sys
SRC, DST = sys.argv[1], sys.argv[2]
t = open(SRC, encoding="utf-8").read()

def rep(old, new, count=1):
    global t
    n = t.count(old)
    assert n == count, f"expected {count} got {n}: {old[:70]!r}"
    t = t.replace(old, new)

def between(start, end, new):
    """replace [start, end) with new; end marker is kept"""
    global t
    assert t.count(start) == 1, f"start {start[:50]!r} x{t.count(start)}"
    a = t.index(start)
    b = t.index(end, a)
    t = t[:a] + new + t[b:]

# ---- header -----------------------------------------------------------
HEADER = '''--[[
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
    if v and v ~= "@VERSION@" then
        print("[VES_Perf] WARNING: Viewpoint Extended Support is " .. tostring(v) .. ", this override was built from @VERSION@; regenerate VES_Perf with tools/build_ves.py")
    end
end

'''
import os
def built_version():
    p = os.path.join(os.path.dirname(SRC), "..", "..", "..", "mod.info")
    for line in open(p, encoding="utf-8"):
        if line.startswith("modversion="):
            return line.split("=", 1)[1].strip()
    raise AssertionError("modversion not found in " + p)
t = HEADER.replace("@VERSION@", built_version()) + t

# ---- optionValue + caches ------------------------------------------------
rep('''local function optionValue(option, fallback)
    if not option or not option.getValue then return fallback end
    local ok, value = pcall(function() return option:getValue() end)
    if not ok or value == nil then return fallback end
    return value
end
''', '''local function optionValue(option, fallback)
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
''')

# ---- isVisible -----------------------------------------------------------
rep('''local function isVisible(ui)
    if not ui or not ui.isVisible then return false end
    local ok, value = pcall(function() return ui:isVisible() end)
    return ok and value == true
end''', '''local function isVisible(ui)
    local fn = ui and ui.isVisible
    if not fn then return false end
    local ok, value = pcall(fn, ui)
    return ok and value == true
end''')

# ---- conversation (V2 gate) ----------------------------------------------
rep('''local function syncConversationCursorMode()
    local talking = isALifeConversationOpen()''', '''local function syncConversationCursorMode()
    local talking = integrations.alife and isALifeConversationOpen() or false''')

# ---- UI state block after the release delay constant -----------------------
rep('''local CURSOR_REQUEST_RELEASE_DELAY = 5
''', '''local CURSOR_REQUEST_RELEASE_DELAY = 5

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
''')

# ---- playerIsInVehicle ----------------------------------------------------
rep('''    local okVehicle, vehicle = pcall(function() return player:getVehicle() end)
    return okVehicle and vehicle ~= nil''', '''    local okVehicle, vehicle = pcall(player.getVehicle, player)
    return okVehicle and vehicle ~= nil''')

# ---- ergonomic option reads ----------------------------------------------
rep('''    if not optionValue(options.ergonomicUi, true) then return end
    if not ErgUIPanel then return end''', '''    if not cfg.ergonomicUi then return end
    if not ErgUIPanel then return end''')
rep('''    if not optionValue(options.ergonomicUi, true) then return false end''',
    '''    if not cfg.ergonomicUi then return false end''')

# ---- suppression + window detection --------------------------------------
between('local function updateInteractionMenuSuppression()', 'local updateCursorRequest\nlocal tickCounter = 0', '''local function pushInteractionMenuState()
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

''')

# ---- cursor window hooks --------------------------------------------------
rep('''        activeCursorWindows[self] = className
        if updateCursorRequest then updateCursorRequest() end''', '''        activeCursorWindows[self] = className
        scanRegistered[self] = nil
        markUiDirty(false)
        if updateCursorRequest then updateCursorRequest() end''')
rep('''        activeCursorWindows[self] = nil
        if updateCursorRequest then updateCursorRequest() end''', '''        activeCursorWindows[self] = nil
        scanRegistered[self] = nil
        markUiDirty(false)
        if updateCursorRequest then updateCursorRequest() end''')

# ---- inventory + ergonomic hooks mark the state dirty ---------------------
rep('''        if isPlayerInventory then
            updateInteractionMenuSuppression()
            updateCursorRequest()
        end''', '''        if isPlayerInventory then
            markUiDirty(false)
            updateInteractionMenuSuppression()
            updateCursorRequest()
        end''')
rep('''            local result = originalOnToggleVisible(self, ...)
            updateInteractionMenuSuppression()''', '''            local result = originalOnToggleVisible(self, ...)
            markUiDirty(false)
            updateInteractionMenuSuppression()''')
rep('''            self._viewpointSupportCursorRequested = visible
            updateInteractionMenuSuppression()''', '''            self._viewpointSupportCursorRequested = visible
            markUiDirty(false)
            updateInteractionMenuSuppression()''')
rep('''            self._viewpointSupportInventoryOpen = true
            self._viewpointSupportCursorRequested = true
            updateInteractionMenuSuppression()''', '''            self._viewpointSupportInventoryOpen = true
            self._viewpointSupportCursorRequested = true
            markUiDirty(false)
            updateInteractionMenuSuppression()''')
rep('''            local result = originalSettings(self, ...)
            self._viewpointSupportCursorRequested = true
            updateCursorRequest()''', '''            local result = originalSettings(self, ...)
            self._viewpointSupportCursorRequested = true
            markUiDirty(false)
            updateCursorRequest()''')

# ---- updateCursorRequest ---------------------------------------------------
between('updateCursorRequest = function()', 'local vehicleCameraForced = false', '''updateCursorRequest = function()
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

''')

# ---- ensureUiState, after the vehicle camera functions -----------------------
rep('''Events.OnEnterVehicle.Add(onEnterVehicle)
Events.OnExitVehicle.Add(onExitVehicle)''', '''-- N1: everything the cursor and menu-suppression updates need, computed once
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
end)''')

# ---- diagnostics reads ---------------------------------------------------
rep('''    if optionValue(options.diagnostics, false) then
        print(''', '''    if cfg.diagnostics then
        print(''')
rep('''    if not optionValue(options.diagnostics, false) then return end
    if interactionMenuDiagnosticStates[key] == message then return end''', '''    if not cfg.diagnostics then return end
    if interactionMenuDiagnosticStates[key] == message then return end''')
rep('''    if not optionValue(options.diagnostics, false) then return end

    local previousKey''', '''    if not cfg.diagnostics then return end

    local previousKey''')

# ---- companion dog tag: no per-frame closure, no closure scan ----------------
rep('''local companionDogTagProjectionLogged = false
''', '''local companionDogTagProjectionLogged = false

local function positionDogTag(self, projected)
    local screenX = projected:get(0) - getPlayerScreenLeft(self.playerNum)
    local zoom = getCore():getZoom(self.playerNum)
    if not zoom or zoom <= 0 then zoom = 1 end
    local screenY = projected:get(1) - getPlayerScreenTop(self.playerNum)
        - COMPANION_DOG_TAG_HEAD_OFFSET / zoom
    self:setX(screenX - COMPANION_DOG_TAG_WIDTH / 2)
    self:setY(screenY - #self.lines * COMPANION_DOG_TAG_LINE_HEIGHT)
end
''')
rep('''                                local positionOk = pcall(function()
                                    local screenX = projected:get(0) - getPlayerScreenLeft(self.playerNum)
                                    local zoom = getCore():getZoom(self.playerNum)
                                    if not zoom or zoom <= 0 then zoom = 1 end
                                    local screenY = projected:get(1) - getPlayerScreenTop(self.playerNum)
                                        - COMPANION_DOG_TAG_HEAD_OFFSET / zoom
                                    self:setX(screenX - COMPANION_DOG_TAG_WIDTH / 2)
                                    self:setY(screenY - #self.lines * COMPANION_DOG_TAG_LINE_HEIGHT)
                                end)''', '''                                local positionOk = pcall(positionDogTag, self, projected)''')
rep('''    local ok, uiList = pcall(function() return UIManager.getUI() end)
    if not ok or not uiList or not uiList.size or not uiList.get then return end
    local okSize, size = pcall(function() return uiList:size() end)
    if not okSize then return end

    for i = 0, size - 1 do
        local okUi, ui = pcall(function() return uiList:get(i) end)
        if okUi and ui and ui.getTable then
            local okTable, uiTable = pcall(function() return ui:getTable() end)''', '''    local ok, uiList = pcall(UIManager.getUI)
    if not ok or not uiList or not uiList.size or not uiList.get then return end
    local okSize, size = pcall(uiList.size, uiList)
    if not okSize then return end

    for i = 0, size - 1 do
        local okUi, ui = pcall(uiList.get, uiList, i)
        if okUi and ui and ui.getTable then
            local okTable, uiTable = pcall(ui.getTable, ui)''')

# ---- OnTick / OnGameStart --------------------------------------------------
between('Events.OnTick.Add(function()', 'syncConfiguration()\nlogInteractionMenuDiagnostic("diagnostics"', '''-- V2: integrations that are absent are skipped outright
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

''')

open(DST, "w", encoding="utf-8").write(t)
print("ok", len(t.splitlines()), "lines")
