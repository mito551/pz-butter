-- Xen's Enhanced GUI : Quick Access bar
-- A small bar of shortcuts on pages: items you carry, recipes and building projects. Drag an item from an
-- inventory list onto a slot, or pick Add to Quick Access in an item's, recipe's or building project's
-- right-click menu. Double-click a slot to use it the way the game does (the game's own double-click action
-- for an item, a craft, a building placement); right-click for the item's own menu or the recipe's tab.
-- Shortcuts are saved per save / server (XEG_qa_<save>.txt) as what they point at (an item type, one item's id, a recipe name),
-- never as the item itself. A slot whose item or recipe is gone stays where it is, marked as missing, and is
-- never pointed at something else on its own.
require "ISUI/ISUIElement"
require "XEG/XEG_Core"
require "XEG/XEG_Widgets"
require "XEG/XEG_Layout"
require "XEG/XEG_Prefs"
require "XEG/XEG_Session"
require "XEG/XEG_Drag"
require "XEG/XEG_Crafting"
require "XEG/XEG_Building"
require "XEG/XEG_Hud"
require "XEG/XEG_Transfers"
require "Hotbar/ISHotbar"

XEG.QuickAccess = XEG.QuickAccess or {}
local QA = XEG.QuickAccess
if QA.loaded then return end
QA.loaded = true

local CFG, P, L, CR, BD, H = XEG.Config, XEG.Prefs, XEG.Layout, XEG.Crafting, XEG.Building, XEG.Hud
local T, TY = XEG.T, XEG.Type
local floor, max, min = math.floor, math.max, math.min
local try = XEG.try

local ID = "quickaccess"
local PARK = 0
QA.ID = ID
QA.MAX_PAGES, QA.MAX_SLOTS = 8, 48
-- A slot's count, condition and availability are read again at most once a second, sooner (but not more than
-- four times a second) after XEG or the game reports an inventory change, and at most PER_FRAME slots are
-- read in one frame. Drawing only uses what was read.
QA.STATE_MS, QA.DIRTY_MS, QA.PER_FRAME = 1000, 250, 6
-- A slot ignores another use this soon after one, and while the actions it queued are still waiting.
QA.LOCK_MS = 600
QA.DOUBLE_MS = 450
QA.DRAG_PX = 8
QA.NOTE_MS, QA.FLASH_MS = 1600, 1300
QA.SIZES = { 30, 38, 46, 56 }
-- Drag-to-resize (Mod Options qaResize): the slot size option times this scale, kept in XEG_prefs.txt.
QA.SCALE_MIN, QA.SCALE_MAX = 0.6, 2
local OFFW = XEG.themed({ r = 0.92, g = 0.90, b = 0.86 })

local function screen() return getCore():getScreenWidth(), getCore():getScreenHeight() end
local ask = H.ask

local warned = {}
local function warn(what, err)
    if warned[what] then return end
    warned[what] = true
    print("[XEG] Quick Access: " .. what .. ": " .. tostring(err))
end

-- Options ------------------------------------------------------------------------------------------------

local function num(id, default, lo, hi)
    local v = tonumber(CFG.get(id))
    if not v then v = default end
    return XEG.clamp(floor(v + 0.5), lo, hi)
end

function QA.enabled() return CFG.get("quickAccess") ~= false end
-- Mod Options qaGear: a gear at the far end of the bar opens Quick Access settings (XEG_QuickAccessSettings).
function QA.gearOn() return CFG.get("qaGear") ~= false end
function QA.perLine() return num("qaPerLine", 8, 1, 12) end
function QA.lines() return num("qaLines", 1, 1, 4) end
function QA.slotCount() return QA.perLine() * QA.lines() end
function QA.pageCount() return num("qaPages", 4, 1, QA.MAX_PAGES) end
function QA.vertical() return CFG.get("qaOrientation") == 2 end
function QA.wheelPages() return (CFG.get("qaPageSwitch") or 1) == 1 end
function QA.pagerShown() return (CFG.get("qaPageSwitch") or 1) ~= 3 and QA.pageCount() > 1 end
function QA.overlays() return CFG.get("qaOverlays") ~= false end
function QA.tipMode() return CFG.get("qaTips") or 1 end

local function joypad() return JoypadState ~= nil and JoypadState.players ~= nil and JoypadState.players[1] ~= nil end

local function setOption(id, v)
    local opt = CFG.options and CFG.options:getOption(id)
    if not opt then return end
    local ok, err = pcall(function()
        opt:setValue(v)
        PZAPI.ModOptions:save()
    end)
    if not ok then warn("could not save option " .. id, err) end
end

-- Saved shortcuts ----------------------------------------------------------------------------------------
-- A shortcut: { k = kind, v = value, id = item id }.
--   k "t": any item of the full type v the player carries.   k "i": the one item with id `id` (type v).
--   k "r": the crafting recipe named v.                       k "b": the building project named v.

local KINDS = { t = true, i = true, r = true, b = true }
function QA.validKind(k) return KINDS[k] == true end

function QA.same(a, b)
    return a ~= nil and b ~= nil and a.k == b.k and a.v == b.v and (a.k ~= "i" or a.id == b.id)
end

QA.states = {}
QA.pend = {}
QA.usedAt = {}

-- Shortcuts belong to one save (single player) or one server (multiplayer): each has its own small file
-- XEG_qa_<save>.txt next to XEG_prefs.txt. The bar's size and its lock stay shared (they are about the
-- screen, not the save). Outside a game (no world yet) the shared lines in XEG_prefs.txt are used as before.
local function djb2(str)
    local h = 5381
    for i = 1, #str do h = (h * 33 + string.byte(str, i)) % 4294967296 end
    return string.format("%08x", h)
end

function QA.saveKey()
    local ok, raw = pcall(function()
        local w = getWorld and getWorld()
        if not w then return nil end
        local name = w.getWorld and w:getWorld()
        if not name or name == "" then return nil end
        local parts = { tostring(w.getGameMode and w:getGameMode() or ""), tostring(name) }
        if isClient and isClient() then
            parts[#parts + 1] = "mp"
            if type(getServerIP) == "function" then parts[#parts + 1] = tostring(getServerIP()) end
            if type(getServerPort) == "function" then parts[#parts + 1] = tostring(getServerPort()) end
        end
        return table.concat(parts, "|")
    end)
    if not ok or not raw then return nil end
    local safe = string.sub(string.gsub(raw, "[^%w_%-]", "_"), 1, 48)
    return safe .. "_" .. djb2(raw)
end

function QA.saveFile(key) return "XEG_qa_" .. key .. ".txt" end

-- The shortcut lines for this save. The first save opened after this change keeps the shortcuts that were
-- shared by every save until now (moved, once); every other save or server starts with an empty bar.
local function saveData(key)
    local file = QA.saveFile(key)
    local sd = P.readQa(file)
    if sd then return sd end
    local g = P.get()
    sd = {}
    if not g.qaMoved and ((g.qa and #g.qa > 0) or (g.qaPages and #g.qaPages > 0)) then
        sd.qa, sd.qaPages = g.qa, g.qaPages
        local st = g.qaState or {}
        sd.qaState = { page = st.page, collapsed = st.collapsed, migrated = st.migrated, hidden = st.hidden }
        P.saveQa(file, sd)
        g.qa, g.qaPages = nil, nil
        g.qaMoved = true
        P.save()
    end
    return sd
end

function QA.load()
    local g = P.get()
    local key = QA.saveKey()
    QA.key = key
    local d = key and saveData(key) or g
    local data = { pages = {}, page = 1 }
    for p = 1, QA.MAX_PAGES do data.pages[p] = { slots = {} } end
    for i, name in ipairs(d.qaPages or {}) do
        if data.pages[i] and type(name) == "string" and name ~= "" then data.pages[i].name = name end
    end
    for _, e in ipairs(d.qa or {}) do
        local pg = data.pages[e.p]
        local okId = e.k ~= "i" or type(e.id) == "number"
        if pg and type(e.s) == "number" and e.s >= 1 and e.s <= QA.MAX_SLOTS and QA.validKind(e.k) and okId
            and type(e.v) == "string" and e.v ~= "" and not pg.slots[e.s] then
            pg.slots[e.s] = { k = e.k, v = e.v, id = e.k == "i" and e.id or nil }
        end
    end
    local st = d.qaState or {}
    data.page = XEG.clamp(tonumber(st.page) or 1, 1, QA.MAX_PAGES)
    data.collapsed, data.locked = st.collapsed == true, (g.qaState or {}).locked == true
    data.migrated, data.hidden = st.migrated == true, st.hidden == true
    local sc = tonumber(g.qaScale)
    data.scale = (sc and sc == sc) and XEG.clamp(sc, QA.SCALE_MIN, QA.SCALE_MAX) or 1
    QA.data = data
    QA.states, QA.pend, QA.usedAt = {}, {}, {}
    return data
end

function QA.get() return QA.data or QA.load() end

function QA.save()
    local data = QA.get()
    local d = P.get()
    local list, names = {}, {}
    for p = 1, QA.MAX_PAGES do
        local pg = data.pages[p]
        names[p] = pg.name or ""
        for s = 1, QA.MAX_SLOTS do
            local sc = pg.slots[s]
            if sc then list[#list + 1] = { p = p, s = s, k = sc.k, v = sc.v, id = sc.id } end
        end
    end
    while #names > 0 and names[#names] == "" do names[#names] = nil end
    local st = { page = data.page, collapsed = data.collapsed, locked = data.locked, migrated = data.migrated, hidden = data.hidden }
    if QA.key then
        P.saveQa(QA.saveFile(QA.key), { qa = list, qaPages = names, qaState = st })
        -- the shared file keeps only the size and the lock
        local g = d.qaState or {}
        d.qaState = { page = g.page or 1, collapsed = g.collapsed, locked = data.locked, migrated = g.migrated, hidden = g.hidden }
    else
        d.qa, d.qaPages = list, names
        d.qaState = st
    end
    d.qaScale = (data.scale and data.scale ~= 1) and data.scale or nil
    QA.ver = (QA.ver or 0) + 1
    P.save()
end

-- Pages and slots ----------------------------------------------------------------------------------------

function QA.page(p)
    local data = QA.get()
    return data.pages[p or data.page] or data.pages[1]
end

-- The page shown, kept inside the page count (fewer pages in Mod Options keep the others' shortcuts).
function QA.current()
    local data = QA.get()
    local n = QA.pageCount()
    -- a page outside 1..n (an older save of page 0) falls back to the first page
    if type(data.page) ~= "number" or data.page ~= data.page or data.page < 1 then data.page = 1 end
    if data.page > n then data.page = n end
    return data.page
end

function QA.setPage(p)
    local data = QA.get()
    local n = QA.pageCount()
    -- the game's % keeps the sign of the left side (-1 % 4 is -1), so add n before wrapping
    p = ((floor(p) - 1) % n + n) % n + 1
    QA.flashAt = XEG.now()
    if p == data.page then return end
    data.page = p
    QA.save()
end

function QA.step(dir) QA.setPage(QA.current() + dir) end

function QA.pageName(p)
    local pg = QA.page(p)
    if pg and pg.name and pg.name ~= "" then return pg.name end
    -- plain numbered names unless you rename a page (Tolga, 29 Oct: no Items / Crafting / Building / Favorites)
    return XEG.tr("Page %s", p)
end

function QA.rename(p, name)
    local pg = QA.page(p)
    if not pg then return end
    name = name and string.gsub(string.gsub(name, "^%s+", ""), "%s+$", "") or ""
    pg.name = name ~= "" and string.sub(name, 1, 24) or nil
    QA.save()
end

function QA.at(p, s)
    local pg = QA.page(p)
    return pg and pg.slots[s]
end

function QA.count(p)
    local n = 0
    for _ in pairs(QA.page(p).slots) do n = n + 1 end
    return n
end

-- Shortcuts on all pages the bar has (the page count in Mod Options).
function QA.total()
    local n = 0
    for p = 1, QA.pageCount() do n = n + QA.count(p) end
    return n
end

function QA.freeSlot(p)
    local pg = QA.page(p)
    for s = 1, QA.slotCount() do if not pg.slots[s] then return s end end
    return nil
end

function QA.freeCount(p)
    local pg, n = QA.page(p), 0
    for s = 1, QA.slotCount() do if not pg.slots[s] then n = n + 1 end end
    return n
end

-- Shortcuts in slots the current size does not show (fewer rows or slots in Mod Options): kept, not shown.
function QA.hiddenCount(p)
    local n, lim = 0, QA.slotCount()
    for s in pairs(QA.page(p).slots) do if s > lim then n = n + 1 end end
    return n
end

function QA.find(sc, p)
    for s, x in pairs(QA.page(p).slots) do
        if QA.same(x, sc) then return s end
    end
    return nil
end

function QA.findAny(sc)
    for p = 1, QA.MAX_PAGES do
        local s = QA.find(sc, p)
        if s then return p, s end
    end
    return nil
end

function QA.forget(sc)
    if not sc then return end
    QA.states[sc], QA.pend[sc], QA.usedAt[sc] = nil, nil, nil
end

-- Puts a shortcut in slot s of page p (the first free slot when s is nil). The same shortcut already on that
-- page moves there instead of being added twice. Returns false, "full" when the page has no free slot.
function QA.assign(p, s, sc)
    if not sc or not QA.validKind(sc.k) then return false end
    p = p or QA.current()
    local pg = QA.page(p)
    if not pg then return false end
    local have = QA.find(sc, p)
    if s == nil then
        if have then return true, "here" end
        s = QA.freeSlot(p)
        if not s then return false, "full" end
    end
    if s < 1 or s > QA.MAX_SLOTS then return false end
    if have == s then return true, "here" end
    if have then
        pg.slots[have], pg.slots[s] = pg.slots[s], pg.slots[have]
    else
        local old = pg.slots[s]
        if old then
            QA.lastRemoved = { p = p, s = s, sc = old }
            QA.forget(old)
        end
        pg.slots[s] = sc
    end
    QA.save()
    if XEG.Hints then XEG.Hints.done("quickaccess") end
    return true
end

function QA.remove(p, s)
    local pg = QA.page(p)
    local old = pg and pg.slots[s]
    if not old then return false end
    pg.slots[s] = nil
    QA.lastRemoved = { p = p, s = s, sc = old }
    QA.forget(old)
    QA.save()
    return true
end

function QA.clearPage(p)
    local pg = QA.page(p)
    if not pg then return false end
    local all, any = {}, false
    for s, sc in pairs(pg.slots) do all[s] = sc QA.forget(sc) any = true end
    if not any then return false end
    pg.slots = {}
    QA.lastRemoved = { p = p, all = all }
    QA.save()
    return true
end

function QA.clearAll()
    local data = QA.get()
    for p = 1, QA.MAX_PAGES do data.pages[p].slots = {} end
    QA.states, QA.pend, QA.usedAt = {}, {}, {}
    QA.lastRemoved = nil
    QA.save()
end

-- Mod Options button: asks before emptying every page.
function QA.askClearAll()
    local n = 0
    for p = 1, QA.MAX_PAGES do n = n + QA.count(p) end
    if n == 0 or not ISModalDialog then return false end
    local sw, sh = screen()
    local w, h = 380, 130
    local modal = ISModalDialog:new(floor(sw / 2 - w / 2), floor(sh / 2 - h / 2), w, h,
        XEG.tr("Remove all %s Quick Access shortcuts from every page?", n), true, nil, function(_, button)
            if button and button.internal == "YES" then QA.clearAll() end
        end, nil)
    modal:initialise()
    modal:addToUIManager()
    modal:setAlwaysOnTop(true)
    modal:bringToTop()
    QA.dialog = modal
    return true
end

-- Puts back what was removed last (one shortcut, or a cleared page), into the same slots while they are free.
function QA.undo()
    local u = QA.lastRemoved
    if not u then return false end
    QA.lastRemoved = nil
    local pg = QA.page(u.p)
    if not pg then return false end
    local back = 0
    local function put(s, sc)
        if pg.slots[s] or QA.find(sc, u.p) then
            if QA.find(sc, u.p) then return end
            s = QA.freeSlot(u.p)
            if not s then return end
        end
        pg.slots[s] = sc
        back = back + 1
    end
    if u.all then
        local keys = {}
        for s in pairs(u.all) do keys[#keys + 1] = s end
        table.sort(keys)
        for _, s in ipairs(keys) do put(s, u.all[s]) end
    else
        put(u.s, u.sc)
    end
    if back > 0 then QA.save() end
    return back > 0
end

-- Swaps two slots, on the same page or on two pages.
function QA.swap(p1, s1, p2, s2)
    local a, b = QA.page(p1), QA.page(p2)
    if not a or not b or (p1 == p2 and s1 == s2) then return false end
    local x, y = a.slots[s1], b.slots[s2]
    if p1 ~= p2 and ((x and QA.find(x, p2)) or (y and QA.find(y, p1))) then return false end
    a.slots[s1], b.slots[s2] = y, x
    QA.save()
    return true
end

function QA.moveTo(p, s, q)
    local sc = QA.at(p, s)
    if not sc or p == q then return false end
    if QA.find(sc, q) then
        QA.remove(p, s)
        QA.lastRemoved = nil
        return true
    end
    local f = QA.freeSlot(q)
    if not f then return false end
    QA.page(q).slots[f] = sc
    QA.page(p).slots[s] = nil
    QA.save()
    return true
end

-- Moves shortcuts from slots the current size does not show into its free slots.
function QA.compact(p)
    local pg, lim, moved = QA.page(p), QA.slotCount(), 0
    local keys = {}
    for s in pairs(pg.slots) do if s > lim then keys[#keys + 1] = s end end
    table.sort(keys)
    for _, s in ipairs(keys) do
        local f = QA.freeSlot(p)
        if not f then break end
        pg.slots[f], pg.slots[s] = pg.slots[s], nil
        moved = moved + 1
    end
    if moved > 0 then QA.save() end
    return moved
end

function QA.toggleHidden()
    local data = QA.get()
    data.hidden = not data.hidden
    QA.save()
end

function QA.setCollapsed(on)
    QA.get().collapsed = on and true or false
    QA.save()
end

function QA.setLocked(on)
    QA.get().locked = on and true or false
    QA.save()
end

function QA.resetLayout()
    QA.moving, QA.drag = nil, nil
    L.reset(ID)
    local data = QA.get()
    data.collapsed, data.hidden = false, false
    QA.save()
end

-- Making shortcuts ---------------------------------------------------------------------------------------

-- A key is only told apart from another key by which door it opens, so a key always gets an exact shortcut.
function QA.itemShortcut(item, exact)
    local ft = item and ask(item, "getFullType")
    if type(ft) ~= "string" or ft == "" then return nil end
    if exact == nil then exact = CFG.get("qaItemMode") == 2 or instanceof(item, "Key") end
    if exact then
        local id = ask(item, "getID")
        if type(id) == "number" then return { k = "i", v = ft, id = id } end
    end
    return { k = "t", v = ft }
end

function QA.recipeShortcut(kind, r)
    local name = r and try(function() return r:getName() end)
    if type(name) ~= "string" or name == "" then return nil end
    return { k = kind, v = name }
end

-- Finding what a shortcut points at ----------------------------------------------------------------------

local function notBroken(item) return not item:isBroken() end

local function wornOfType(pl, ft)
    local worn = ask(pl, "getWornItems")
    if not worn then return nil end
    local n = ask(worn, "size") or 0
    for i = 0, n - 1 do
        local w = try(function() return worn:get(i) end)
        local it = w and ask(w, "getItem")
        if it and ask(it, "getFullType") == ft then return it end
    end
    return nil
end

-- The item a shortcut stands for right now, and how many of it the player carries. An exact shortcut finds
-- only its own item by id (in the inventory or any bag in it) and nothing else. A type shortcut takes the
-- one in your hands first, then the one you wear (so double-click puts it away or takes it off, as the game
-- does), then one that is not broken.
function QA.resolveItem(sc, pl)
    local inv = pl and ask(pl, "getInventory")
    if not inv or not sc then return nil, 0 end
    if sc.k == "i" then
        local it = try(function() return inv:getItemWithIDRecursiv(sc.id) end)
        if it and ask(it, "getFullType") == sc.v then return it, 1 end
        return nil, 0
    end
    if sc.k ~= "t" then return nil, 0 end
    local n = try(function() return inv:getCountTypeRecurse(sc.v) end) or 0
    if n <= 0 then return nil, 0 end
    local h1, h2 = ask(pl, "getPrimaryHandItem"), ask(pl, "getSecondaryHandItem")
    if h1 and ask(h1, "getFullType") == sc.v then return h1, n end
    if h2 and ask(h2, "getFullType") == sc.v then return h2, n end
    local w = wornOfType(pl, sc.v)
    if w then return w, n end
    local it = try(function() return inv:getFirstTypeEvalRecurse(sc.v, notBroken) end)
        or try(function() return inv:getFirstTypeRecurse(sc.v) end)
    return it, n
end

-- Name and icon of an item type from its script, for a slot whose item is not on you. A type whose mod is
-- off has no script: the slot keeps its saved type and shows it as unknown.
QA.scriptInfo = QA.scriptInfo or {}
function QA.script(ft)
    local c = QA.scriptInfo[ft]
    if c then return c end
    c = { name = ft }
    local si = try(function() return getScriptManager():FindItem(ft) end)
    if si then
        c.name = try(function() return si:getDisplayName() end) or ft
        c.tex = try(function() return si:getNormalTexture() end)
        if not c.tex then
            local icon = try(function() return si:getIcon() end)
            if type(icon) == "string" and icon ~= "" then c.tex = getTexture("Item_" .. icon) end
        end
    else
        c.unknown = true
    end
    QA.scriptInfo[ft] = c
    return c
end

-- Recipes by name. Crafting recipes come from the game's script list; building projects from the building
-- list XEG's Building tab uses, built once and again (at most every 30 seconds) when a name is missing,
-- since a mod can add projects after the first look.
QA.buildIdx = nil
QA.buildIdxAt = -1e9
function QA.recipe(kind, name)
    if type(name) ~= "string" then return nil end
    if kind == "r" then
        return try(function() return getScriptManager():getCraftRecipe(name) end)
    end
    if kind ~= "b" then return nil end
    local map = QA.buildIdx
    local now = XEG.now()
    if not map or (map[name] == nil and (now - QA.buildIdxAt > 30000 or now < QA.buildIdxAt)) then
        QA.buildIdxAt = now
        local fresh, got = {}, false
        local ok, err = pcall(function()
            local st = BD.state()
            local list = st and st.logic:getAllBuildableRecipes()
            if not list then return end
            for i = 0, list:size() - 1 do
                local r = list:get(i)
                local n = r:getName()
                if n and fresh[n] == nil then fresh[n] = r got = true end
            end
        end)
        if ok and got then map = fresh QA.buildIdx = map
        elseif not ok then warn("building projects", err) end
    end
    return map and map[name]
end

local function recipeTex(r)
    local t = CR.texOf[r]
    if t == nil then
        t = try(function() return r:getIconTexture() end) or false
        CR.texOf[r] = t
    end
    return t or nil
end

local function ammoOf(item)
    if not instanceof(item, "HandWeapon") or not ask(item, "isRanged") then return nil end
    local n = ask(item, "getCurrentAmmoCount") or 0
    if ask(item, "haveChamber") and ask(item, "isRoundChambered") then n = n + 1 end
    local jam = ask(item, "isJammed") == true
    return tostring(n), (jam or n == 0) and T.critical or OFFW
end

-- Everything a slot draws, read from the game (see STATE_MS above).
function QA.readState(sc, pl, e)
    e = e or {}
    local now = XEG.now()
    e.at, e.tick = now, XEG.dirtyTick
    e.item, e.f, e.col, e.ammo, e.lit, e.equipped, e.broken, e.status, e.r = nil, nil, nil, nil, false, false, false, nil, nil
    if sc.k == "t" or sc.k == "i" then
        local item, n = QA.resolveItem(sc, pl)
        e.item, e.count, e.avail = item, n, item ~= nil
        if item then
            e.name = ask(item, "getName") or sc.v
            e.tex = ask(item, "getTex") or ask(item, "getTexture")
            sc.seenName, sc.seenTex = e.name, e.tex
            e.equipped = try(function() return pl:isHandItem(item) end) == true or try(function() return pl:isEquipped(item) end) == true
            e.broken = ask(item, "isBroken") == true
            if QA.overlays() then
                e.f, e.col = H.itemMeter(item)
                e.ammo, e.ammoCol = ammoOf(item)
                e.lit = ask(item, "canBeActivated") == true and ask(item, "isActivated") == true
            end
        else
            local si = QA.script(sc.v)
            e.name = sc.seenName or si.name
            e.tex = sc.seenTex or si.tex
            e.unknown = si.unknown == true
        end
    else
        local r = QA.recipe(sc.k, sc.v)
        e.r, e.avail, e.count = r, r ~= nil, 0
        if r then
            e.name = CR.recipeName(r)
            e.tex = recipeTex(r)
            local st = sc.k == "r" and CR.state(pl) or BD.state(pl)
            -- before the first recipe check the game has not said yet: drawn as neither ready nor missing
            e.status = (st and st.loaded) and CR.status(st, r) or nil
        else
            e.name, e.tex, e.status, e.unknown = sc.v, nil, 0, true
        end
    end
    return e
end

function QA.stale(e, now)
    if not e or not e.at or now < e.at then return true end
    if now - e.at >= QA.STATE_MS then return true end
    return e.tick ~= XEG.dirtyTick and now - e.at >= QA.DIRTY_MS
end

-- Reads the shown page's slots that are due, a few per frame.
function QA.refreshStates(pl, now, n)
    local pg = QA.page(QA.current())
    local budget = QA.PER_FRAME
    for s = 1, n do
        local sc = pg.slots[s]
        if sc and QA.stale(QA.states[sc], now) then
            if budget <= 0 then return end
            budget = budget - 1
            local ok, e = pcall(QA.readState, sc, pl, QA.states[sc])
            if ok then QA.states[sc] = e else warn("slot state", e) QA.states[sc] = { at = now, tick = XEG.dirtyTick, name = sc.v } end
        end
    end
end

function QA.touch(sc)
    local e = sc and QA.states[sc]
    if e then e.at = -1e9 end
end

-- Using a slot -------------------------------------------------------------------------------------------
-- Every use goes through the game's own actions: the game's double-click action for an item (the same one
-- its inventory and XEG's lists use), XEG's crafting (the game's craft actions) and the game's building
-- placement. The actions a use put in the game's action queue are remembered, and the slot does nothing
-- while any of them still waits or runs, so rapid clicks and a laggy server can't queue the same thing twice.

local function queueList(pl)
    local qs = ISTimedActionQueue and ISTimedActionQueue.queues
    local q = qs and qs[pl]
    return q and q.queue or nil
end

local function snapshot(pl)
    local seen = {}
    local q = queueList(pl)
    if q then for _, a in ipairs(q) do seen[a] = true end end
    return seen
end

local function added(pl, seen)
    local out = {}
    local q = queueList(pl)
    if q then for _, a in ipairs(q) do if not seen[a] then out[#out + 1] = a end end end
    return out
end

function QA.pending(sc, pl)
    local pd = QA.pend[sc]
    if not pd then return false end
    local q = queueList(pl or getSpecificPlayer(0))
    if q then
        for _, a in ipairs(pd) do
            for _, b in ipairs(q) do if a == b then return true end end
        end
    end
    QA.pend[sc] = nil
    QA.touch(sc)
    return false
end

-- The action of this slot that runs right now, for its progress bar.
function QA.running(sc, pl)
    local pd = QA.pend[sc]
    local q = pd and queueList(pl)
    local a = q and q[1]
    if not a then return nil end
    for _, b in ipairs(pd) do
        if a == b then return try(function() return a:getJobDelta() end) end
    end
    return nil
end

function QA.anyPending(kind, pl)
    for sc in pairs(QA.pend) do
        if sc.k == kind and QA.pending(sc, pl) then return true end
    end
    return false
end

-- A short note above the bar ("None on you"); text is already translated.
function QA.note(sc, text)
    QA.notice = { sc = sc, text = text, at = XEG.now() }
end

local function remember(sc, pl, seen)
    local list = added(pl, seen)
    if #list > 0 then QA.pend[sc] = list end
    QA.touch(sc)
end

-- Put back in its bag (Mod Options qaReturn): an item a slot takes out of a bag you carry or wear (a hammer
-- from your backpack) goes back into that bag once it has been in your hands or worn and is put away or taken
-- off. When the bag is no longer on you, is full or won't take it, the item stays in your main inventory as
-- usual; an item that goes onto your belt or back slot stays there. Kept in memory only, never saved.
QA.homes = {}
QA.HOME_WAIT_MS = 5 * 60 * 1000
function QA.returnOn() return CFG.get("qaReturn") ~= false end

local function equipped(item) return try(function() return item:isEquipped() end) == true end
-- (Kahlua has no next())
function QA.anyHome() for _ in pairs(QA.homes) do return true end return false end

function QA.noteHome(item, pl)
    if not QA.returnOn() or equipped(item) then return end
    local src = try(function() return item:getContainer() end)
    if not src or src == pl:getInventory() then return end
    if try(function() return src:isInCharacterInventory(pl) end) ~= true then return end
    QA.homes[item:getID()] = { item = item, home = src, at = XEG.now(), used = false }
end

function QA.putBack(pl, item, home)
    if try(function() return home:isInCharacterInventory(pl) end) ~= true then return false end
    local T = XEG.Transfers
    if T and T.canPut then
        if not T.canPut(item, home, pl) then return false end
    elseif try(function() return home:hasRoomFor(pl, item) end) ~= true then
        return false
    end
    if not (ISTimedActionQueue and ISInventoryTransferAction) then return false end
    ISTimedActionQueue.add(ISInventoryTransferAction:new(pl, item, item:getContainer(), home))
    return true
end

-- Every few ticks: an item seen in use and now loose in the main inventory goes home. One that never got
-- equipped (eaten, read, or the action was cancelled) is forgotten after a while.
function QA.checkHomes(pl)
    if not pl or not QA.anyHome() then return end
    local main = pl:getInventory()
    local now = XEG.now()
    for id, h in pairs(QA.homes) do
        local item = h.item
        if equipped(item) then
            h.used = true
        elseif not h.used then
            if now < h.at or now - h.at > QA.HOME_WAIT_MS then QA.homes[id] = nil end
        else
            QA.homes[id] = nil
            local onBelt = (try(function() return item:getAttachedSlot() end) or -1) >= 0
            if QA.returnOn() and not onBelt and try(function() return item:getContainer() end) == main then
                QA.putBack(pl, item, h.home)
            end
        end
    end
end

function QA.useItem(sc, pl)
    local item = QA.resolveItem(sc, pl)
    if not item then
        QA.note(sc, sc.k == "i" and XEG.tr("That item is not on you") or XEG.tr("None on you"))
        return false
    end
    if try(function() return pl:getInventory():containsRecursive(item) end) ~= true then
        QA.note(sc, XEG.tr("None on you"))
        return false
    end
    if not (ISInventoryPane and ISInventoryPane.doContextualDblClick) then return false end
    local seen = snapshot(pl)
    QA.noteHome(item, pl)
    local ok, err = pcall(ISInventoryPane.doContextualDblClick, { player = pl:getPlayerNum() }, item)
    if not ok then warn("use", err) return false end
    remember(sc, pl, seen)
    XEG.markDirty()
    return true
end

function QA.useRecipe(sc, pl, all)
    local r = QA.recipe("r", sc.v)
    if not r then QA.note(sc, XEG.tr("Recipe not found")) return false end
    local st = CR.state(pl)
    if not st then return false end
    if CR.busy(st) or QA.anyPending("r", pl) then QA.note(sc, XEG.tr("Already crafting")) return false end
    local seen = snapshot(pl)
    if CR.craftRecipe(r, all and "max" or 1) then
        remember(sc, pl, seen)
        return true
    end
    -- something is missing: the Crafting tab shows what
    CR.open(r)
    return false
end

function QA.useBuild(sc, pl)
    local r = QA.recipe("b", sc.v)
    if not r then QA.note(sc, XEG.tr("Building project not found")) return false end
    local st = BD.state(pl)
    if not st then return false end
    if CR.busy(st) then QA.note(sc, XEG.tr("Already building")) return false end
    local pn = pl:getPlayerNum()
    local drag = try(function() return getCell():getDrag(pn) end)
    if st.placing and st.entity and drag == st.entity and st.sel == r then return false end
    CR.select(st, r)
    CR.refresh(st, true)
    if BD.canBuild(st) then
        if BD.start(st) then QA.touch(sc) return true end
        return false
    end
    BD.open(r)
    return false
end

function QA.use(p, s, all)
    local sc = QA.at(p, s)
    local pl = getSpecificPlayer(0)
    if not sc or not pl or pl:isDead() then return false end
    local now = XEG.now()
    local last = QA.usedAt[sc]
    if last and now >= last and now - last < QA.LOCK_MS then return false end
    if QA.pending(sc, pl) then QA.note(sc, XEG.tr("Already doing that")) return false end
    QA.usedAt[sc] = now
    local ok, r = pcall(function()
        if sc.k == "t" or sc.k == "i" then return QA.useItem(sc, pl) end
        if sc.k == "r" then return QA.useRecipe(sc, pl, all) end
        if sc.k == "b" then return QA.useBuild(sc, pl) end
        return false
    end)
    if not ok then warn("use", r) return false end
    return r
end

-- A double-click can reach the bar twice (the release timing and the game's own double-click event): one
-- use per slot per double-click.
function QA.activate(p, s, all)
    local now = XEG.now()
    local a = QA.lastAct
    if a and a.p == p and a.s == s and now >= a.t and now - a.t < QA.DOUBLE_MS then return false end
    QA.lastAct = { p = p, s = s, t = now }
    return QA.use(p, s, all)
end

-- Size ---------------------------------------------------------------------------------------------------

function QA.scale()
    local v = QA.get().scale
    if type(v) ~= "number" or v ~= v then return 1 end
    return XEG.clamp(v, QA.SCALE_MIN, QA.SCALE_MAX)
end

-- The resize handle (a strip at the bar's far end): on in Mod Options, the HUD not locked, the bar expanded.
function QA.resizable(g)
    return CFG.get("qaResize") ~= false and CFG.get("hudLocked") ~= true and not (g and g.collapsed)
end

function QA.setScale(v, keep)
    local data = QA.get()
    v = XEG.clamp(floor(v * 100 + 0.5) / 100, QA.SCALE_MIN, QA.SCALE_MAX)
    if data.scale == v then return end
    data.scale = v
    if not keep then QA.save() end
end

function QA.resetScale()
    QA.sizing = nil
    QA.setScale(1)
end

-- The resize handle is its own strip at the far end of the bar (right side, or the bottom when vertical), so it
-- is easy to see and to grab. Its rectangle in the bar's own pixels, or nil when the bar has none.
-- The gear: the first square of that strip (its top, or its left end when the bar is vertical).
function QA.gearRect(g)
    if not QA.gearOn() then return nil end
    local hx, hy, hw, hh = QA.handleRect(g)
    if not hx then return nil end
    if g.vert then return hx, hy, hh, hh end
    return hx, hy, hw, hw
end

function QA.handleRect(g)
    if not (g.tail and g.tail > 0) or g.collapsed then return nil end
    if g.vert then return g.pad, g.h - g.pad - g.tail, g.w - g.pad * 2, g.tail end
    return g.w - g.pad - g.tail, g.pad, g.tail, g.h - g.pad * 2
end

-- The scale for a resize drag: the corner follows the mouse along the bar's diagonal. Near 100 it snaps to
-- 100 unless Shift is held.
function QA.dragScale(z, dx, dy, free)
    local w0, h0 = z.w0, z.h0
    local t = (dx * w0 + dy * h0) / max(1, w0 * w0 + h0 * h0)
    local v = XEG.clamp(z.s0 * (1 + t), QA.SCALE_MIN, QA.SCALE_MAX)
    if not free and math.abs(v - 1) < 0.05 then v = 1 end
    return floor(v * 100 + 0.5) / 100
end

-- Layout -------------------------------------------------------------------------------------------------

-- Worked out again only when one of its inputs changed.
local geoVal = nil
-- Writes a Mod Option (and saves Mod Options) for the settings panel and the right-click menu.
function QA.setOption(id, v)
    setOption(id, v)
    geoVal = nil
end
function QA.openSettings() if XEG.QASettings then XEG.QASettings.toggle() end end
function QA.geom(s)
    local data = QA.get()
    local size = CFG.get("qaSlotSize") or 2
    local labels = CFG.get("qaLabels") == 2
    local per, lines, vert = QA.perLine(), QA.lines(), QA.vertical()
    local c, font = data.collapsed == true, XEG.FONT_S
    local scale = QA.scale()
    local rz = QA.resizable()
    local gr = QA.gearOn()
    local o = geoVal
    if o and o.s == s and o.size == size and o.labels == labels and o.per == per and o.lines == lines and o.vert == vert
        and o.collapsed == c and o.font == font and o.scale == scale and o.rz == rz and o.gr == gr then
        return o
    end
    local sz = floor((QA.SIZES[size] or 38) * s * scale + 0.5)
    local gap, pad = max(2, floor(3 * s)), max(2, floor(3 * s))
    local rail = max(10, floor(12 * s))
    local lh = labels and (TY.height("meta", s) + floor(4 * s)) or 0
    local tail = (rz or gr) and max(12, floor(14 * s)) or 0
    local g = { s = s, sz = sz, gap = gap, pad = pad, rail = rail, lh = lh, cw = sz, ch = sz + lh, vert = vert,
        collapsed = c, n = per * lines, labels = labels, size = size, per = per, lines = lines, font = font, scale = scale,
        rz = rz, gr = gr, tail = tail }
    local tg = tail > 0 and (tail + gap) or 0
    if vert then
        g.cols, g.rows = lines, per
        g.fw = pad * 2 + g.cols * g.cw + (g.cols - 1) * gap
        g.fh = pad * 2 + rail + gap + g.rows * g.ch + (g.rows - 1) * gap + tg
        g.w, g.h = g.fw, g.collapsed and (pad * 2 + rail) or g.fh
    else
        g.cols, g.rows = per, lines
        g.fw = pad * 2 + rail + gap + g.cols * g.cw + (g.cols - 1) * gap + tg
        g.fh = pad * 2 + g.rows * g.ch + (g.rows - 1) * gap
        g.w, g.h = g.collapsed and (pad * 2 + rail) or g.fw, g.fh
    end
    geoVal = g
    return g
end

-- Mod Options qaHideSlots (off by default): the bar shows only the slots that hold a shortcut, packed together, and shrinks to
-- fit. Every slot comes back while you drag an item or a slot, and while Quick Access settings are open, so
-- there is always somewhere to drop. A page with no shortcuts shows one empty slot.
function QA.hideSlotsOn() return CFG.get("qaHideSlots") == true end

local viewKey, viewVal = nil, nil
function QA.view(g)
    if g.collapsed or not QA.hideSlotsOn() then return g end
    if (XEG.Drag and XEG.Drag.active ~= nil) or (ISMouseDrag ~= nil and ISMouseDrag.dragging ~= nil) or QA.drag
        or (XEG.QASettings and XEG.QASettings.isOpen and XEG.QASettings.isOpen()) then return g end
    local pg = QA.page(QA.current())
    local map, key = {}, ""
    for i = 1, g.n do
        if pg.slots[i] then map[#map + 1] = i key = key .. i .. "," end
    end
    if #map == 0 then map[1] = 1 end
    if #map == g.n then return g end
    key = tostring(g) .. "|" .. key
    if viewKey == key then return viewVal end
    local v = setmetatable({}, { __index = g })
    v.map, v.pos, v.n, v.full = map, {}, #map, g
    for k, i in ipairs(map) do v.pos[i] = k end
    local along, across = math.min(#map, g.per), math.ceil(#map / g.per)
    local gap, pad = g.gap, g.pad
    local tg = g.tail > 0 and (g.tail + gap) or 0
    if g.vert then
        v.cols, v.rows = across, along
        v.fw = pad * 2 + v.cols * g.cw + (v.cols - 1) * gap
        v.fh = pad * 2 + g.rail + gap + v.rows * g.ch + (v.rows - 1) * gap + tg
    else
        v.cols, v.rows = along, across
        v.fw = pad * 2 + g.rail + gap + v.cols * g.cw + (v.cols - 1) * gap + tg
        v.fh = pad * 2 + v.rows * g.ch + (v.rows - 1) * gap
    end
    v.w, v.h = v.fw, v.fh
    viewKey, viewVal = key, v
    return v
end

-- Slot i's square inside the bar. Slots fill the first row (the first column when vertical) first, so slot
-- numbers mean the same in both directions. With hidden empty slots, i is still the real slot number; a
-- hidden slot has no square (nil).
function QA.slotRect(g, i)
    if g.pos then
        i = g.pos[i]
        if not i then return nil end
    end
    local c, r
    if g.vert then c, r = floor((i - 1) / g.rows), (i - 1) % g.rows
    else r, c = floor((i - 1) / g.cols), (i - 1) % g.cols end
    local x0 = g.pad + (g.vert and 0 or (g.rail + g.gap))
    local y0 = g.pad + (g.vert and (g.rail + g.gap) or 0)
    return x0 + c * (g.cw + g.gap), y0 + r * (g.ch + g.gap), g.cw, g.ch
end

function QA.railRect(g)
    if g.vert then return g.pad, g.pad, g.w - g.pad * 2, g.rail end
    return g.pad, g.pad, g.rail, g.h - g.pad * 2
end

-- Page dots inside the rail, after the grip: one or two lines of them, or nil when even two don't fit
-- (then the rail shows the page number).
local NO_DOTS = {}
function QA.dots(g)
    if not QA.pagerShown() then return NO_DOTS end
    local n = QA.pageCount()
    if g.dotsN == n then return g.dotsVal end
    g.dotsN, g.dotsVal = n, QA.dotsNow(g, n)
    return g.dotsVal
end

-- Length of the move grip at the start of the rail (the dots below it switch pages).
function QA.gripLen(s) return max(12, floor(14 * s)) end

function QA.dotsNow(g, n)
    local rx, ry, rw, rh = QA.railRect(g)
    local s = g.s
    local d = max(3, floor(4 * s))
    local step = d + max(2, floor(3 * s))
    local grip = QA.gripLen(s)
    local long = g.vert and rw or rh
    local across = g.vert and rh or rw
    local lines = 1
    local per = n
    if per * step - (step - d) > long - grip then
        lines, per = 2, math.ceil(n / 2)
        if per * step - (step - d) > long - grip or 2 * step - (step - d) > across then return nil end
    end
    local total = per * step - (step - d)
    local start = (g.vert and rx or ry) + grip + floor((long - grip - total) / 2)
    local acrossW = lines * step - (step - d)
    local a0 = (g.vert and ry or rx) + floor((across - acrossW) / 2)
    local out = {}
    for p = 1, n do
        local li, k = floor((p - 1) / per), (p - 1) % per
        local along, side = start + k * step, a0 + li * step
        local x, y = side, along
        if g.vert then x, y = along, side end
        -- the click area covers the gap around the dot, so a dot is easy to hit
        local hx, hy = x - floor((step - d) / 2), y - floor((step - d) / 2)
        out[p] = { x = x, y = y, d = d, p = p, hx = hx, hy = hy, hw = step, hh = step }
    end
    return out
end

local function inRect(x, y, rx, ry, rw, rh) return x >= rx and y >= ry and x < rx + rw and y < ry + rh end

-- What is under (x, y), in the bar's own coordinates.
function QA.hit(g, x, y)
    if not inRect(x, y, 0, 0, g.w, g.h) then return nil end
    local hx, hy = QA.handleRect(g)
    -- the strip plus the margin around it, out to the bar's edge: the gear at its start, the rest resizes
    if hx and ((g.vert and y >= hy - floor(g.gap / 2)) or (not g.vert and x >= hx - floor(g.gap / 2))) then
        local gx, gy, gw, gh = QA.gearRect(g)
        if gx and ((g.vert and x < gx + gw) or (not g.vert and y < gy + gh)) then return { kind = "gear" } end
        if QA.resizable(g) then return { kind = "resize" } end
        return { kind = "plate" }
    end
    local rx, ry, rw, rh = QA.railRect(g)
    if inRect(x, y, rx, ry, rw, rh) then
        local dots = QA.dots(g)
        if dots then
            for _, d in ipairs(dots) do
                if inRect(x, y, d.hx, d.hy, d.hw, d.hh) then return { kind = "dot", p = d.p } end
            end
        elseif QA.pagerShown() then
            local grip = QA.gripLen(g.s)
            local inGrip = g.vert and (x < rx + grip) or (y < ry + grip)
            if not inGrip then return { kind = "num" } end
        end
        return { kind = "rail" }
    end
    if g.collapsed then return { kind = "rail" } end
    for k = 1, g.n do
        local i = g.map and g.map[k] or k
        local sx, sy, sw, sh = QA.slotRect(g, i)
        if inRect(x, y, sx, sy, sw, sh) then return { kind = "slot", i = i } end
    end
    return { kind = "plate" }
end

-- Default spot: centred above the hotbar, following it until you move the bar. It leaves room for what the
-- hotbar shows above itself while you hover it: the slot's name and the HUD positions button.
function QA.defaultPos(g)
    local sw, sh = screen()
    local s = g.s
    local hb = getPlayerHotbar and getPlayerHotbar(0)
    local ok = hb ~= nil and try(function() return hb:getIsVisible() end) == true
    if ok and not (H.vertical and H.vertical(hb)) then
        local x = floor(hb.x + hb.width / 2 - g.fw / 2)
        local hs = H.metrics().s
        local room = max(XEG.fh(XEG.FONT_S) + 8, max(16, floor(17 * hs)) + floor(8 * hs))
        local y = hb.y - g.fh - room
        -- a hotbar moved to the top of the screen gets the bar below it
        if y < 0 then y = hb.y + hb.height + room end
        return x, y
    end
    return floor((sw - g.fw) / 2), sh - g.fh - floor(120 * s)
end

function QA.moved() return L.entry(ID) ~= nil end

function QA.place(g)
    local sw, sh = screen()
    local x, y
    local m = QA.moving
    local z = QA.sizing
    if m and m.active then
        x, y = m.x, m.y
    elseif z and z.active then
        x, y = z.x, z.y
    else
        local e = L.entry(ID)
        if e then
            -- the saved spot is for the full bar, so hiding empty slots keeps a moved bar's start where it was
            -- (the default spot stays centred above the hotbar)
            local f = g.full or g
            x, y = floor(e.fx * max(0, sw - f.fw) + 0.5), floor(e.fy * max(0, sh - f.fh) + 0.5)
        else
            x, y = QA.defaultPos(g)
        end
    end
    x = XEG.clamp(x, 0, max(0, sw - g.w))
    y = XEG.clamp(y, 0, max(0, sh - g.h))
    return x, y
end

local function snap(v, hi, d)
    if v < d then return 0 end
    if hi - v < d then return hi end
    return v
end

-- When to show it --------------------------------------------------------------------------------------

local function dragging() return (XEG.Drag and XEG.Drag.active ~= nil) or (ISMouseDrag ~= nil and ISMouseDrag.dragging ~= nil) end
QA.dragging = dragging

local function inventoryOpen()
    if XEG.isOpen("inventory") then return true end
    local inv = getPlayerInventory and try(function() return getPlayerInventory(0) end)
    return inv ~= nil and try(function() return inv:getIsVisible() and not inv.isCollapsed end) == true
end

function QA.wanted(pl)
    if not QA.enabled() or not pl or pl:isDead() or joypad() then return false end
    local data = QA.get()
    if data.hidden then return false end
    local vis = CFG.get("qaVisibility") or 1
    local open = XEG.isOpen()
    if (vis == 2 and open) or (vis == 3 and not open) then return false end
    -- an empty bar waits until there is something to drop on it
    if CFG.get("qaHideEmpty") ~= false and not QA.drag and QA.total() == 0 then
        return inventoryOpen() or dragging()
    end
    return true
end

-- Clicks go through the bar while you aim, so it never takes a shot meant for the world.
function QA.interactive(pl)
    if QA.drag or (QA.moving and QA.moving.active) then return true end
    return ask(pl, "isAiming") ~= true
end

function QA.covers(mx, my)
    local b = QA.box
    if not QA.shownNow or not b or not QA.live then return false end
    return inRect(mx, my, b.x, b.y, b.w, b.h)
end

-- Top of the bar while it sits in its default spot and has shortcuts, for the XP popups to stack above it.
function QA.topIfDefault()
    if not QA.shownNow or not QA.box or QA.moved() or (QA.moving and QA.moving.active) then return nil end
    if QA.count(QA.current()) == 0 then return nil end
    return QA.box.y
end

-- The bar ------------------------------------------------------------------------------------------------

XEGQuickAccess = XEGBase:derive("XEGQuickAccess")

function XEGQuickAccess:new()
    local o = XEGBase.new(self, PARK, PARK, 1, 1)
    o.anchorLeft, o.anchorTop = true, true
    o.parked = true
    o.ox, o.oy = 0, 0
    return o
end

-- Live: the element covers the bar and takes clicks. Parked: a 1x1 element in the corner that still draws the
-- bar where it belongs (drawing is offset), so clicks reach the world while you aim.
function XEGQuickAccess:park(x, y, w, h, live)
    local ex, ey, ew, eh = PARK, PARK, 1, 1
    if live then ex, ey, ew, eh = x, y, w, h end
    self.parked = not live
    QA.live = live
    local moved = false
    if self.x ~= ex or self.y ~= ey or self.width ~= ew or self.height ~= eh then
        self:setX(ex) self:setY(ey) self:setWidth(ew) self:setHeight(eh)
        moved = true
    end
    local now = XEG.now()
    if not moved and self.absAt and now >= self.absAt and now - self.absAt < 500 then
        self.ox, self.oy = x - self.ax, y - self.ay
        return
    end
    self.absAt = now
    local ax, ay = ex, ey
    local j = self.javaObject
    if j then
        local okx, vx = pcall(j.getAbsoluteX, j)
        local oky, vy = pcall(j.getAbsoluteY, j)
        if okx and type(vx) == "number" then ax = vx end
        if oky and type(vy) == "number" then ay = vy end
    end
    self.ax, self.ay = ax, ay
    self.ox, self.oy = x - ax, y - ay
end

function XEGQuickAccess:mouse()
    local b = QA.box
    if not b then return -1, -1 end
    return getMouseX() - b.x, getMouseY() - b.y
end

local function corners(self, x, y, w, h, len, th, c, a)
    self:rect(x, y, len, th, c, a) self:rect(x, y, th, len, c, a)
    self:rect(x + w - len, y, len, th, c, a) self:rect(x + w - th, y, th, len, c, a)
    self:rect(x, y + h - th, len, th, c, a) self:rect(x, y + h - len, th, len, c, a)
    self:rect(x + w - len, y + h - th, len, th, c, a) self:rect(x + w - th, y + h - len, th, len, c, a)
end

function XEGQuickAccess:prerender()
    local pl = getSpecificPlayer(0)
    local ok, want = pcall(QA.wanted, pl)
    want = ok and want
    QA.shownNow = want
    if not want then
        if QA.drag or QA.moving then QA.drag, QA.moving = nil, nil self:setCapture(false) end
        self:park(0, 0, 0, 0, false)
        QA.hot = false
        return
    end
    local now = XEG.now()
    local s = XEG.scale()
    self.s = s
    local g = QA.view(QA.geom(s))
    local x, y = QA.place(g)
    QA.box = QA.box or {}
    QA.box.x, QA.box.y, QA.box.w, QA.box.h = x, y, g.w, g.h
    self:park(x, y, g.w, g.h, QA.interactive(pl))
    -- a drag that starts while an XEG screen covers the bar brings the bar on top, so it can take the drop
    local dr = dragging()
    if dr and not self.wasDragging then QA.raise(true) end
    self.wasDragging = dr
    if not g.collapsed then QA.refreshStates(pl, now, QA.slotCount()) end
    local ok2, err = pcall(self.drawBar, self, g, pl, now)
    if not ok2 then warn("draw", err) end
end

function XEGQuickAccess:drawBar(g, pl, now)
    local ox, oy = self.ox, self.oy
    local s, w, h = g.s, g.w, g.h
    local mx, my = self:mouse()
    local over = QA.live and inRect(mx, my, 0, 0, w, h)
    QA.hot = over
    local hit = over and QA.hit(g, mx, my) or nil
    local pa = num("qaPanelAlpha", 25, 0, 100) / 100
    local sa = num("qaSlotAlpha", 45, 0, 100) / 100
    local data = QA.get()
    local page = QA.current()
    local pg = data.pages[page]
    local ext = dragging() and not QA.drag

    -- plate
    if pa > 0 then
        self:rect(ox, oy, w, h, T.void, pa)
        self:border(ox, oy, w, h, T.line, 0.12 + 0.3 * pa)
    end
    local cl = max(4, floor(6 * s))
    corners(self, ox, oy, w, h, cl, 1, T.accent, over and 0.8 or 0.35)

    -- rail: grip and page dots (or the page number)
    local rx, ry, rw, rh = QA.railRect(g)
    rx, ry = rx + ox, ry + oy
    local railHot = hit and (hit.kind == "rail" or hit.kind == "num" or hit.kind == "dot")
    local grip = QA.gripLen(s)
    -- move grip: a faint tab with two rows of three dots, lit while hovered or moving
    local gHot = railHot or (QA.moving and QA.moving.active)
    local gw, gh = g.vert and grip or rw, g.vert and rh or grip
    self:rect(rx, ry, gw, gh, gHot and T.accent or OFFW, gHot and 0.18 or 0.08)
    local d = max(2, floor(2 * s))
    local pitch = d + max(2, floor(2 * s))
    local along, across = 3 * pitch - (pitch - d), 2 * pitch - (pitch - d)
    local dw, dh = across, along
    if g.vert then dw, dh = along, across end
    local gx, gy = rx + floor((gw - dw) / 2), ry + floor((gh - dh) / 2)
    for a = 0, 2 do
        for b = 0, 1 do
            local dx, dy = b * pitch, a * pitch
            if g.vert then dx, dy = a * pitch, b * pitch end
            self:rect(gx + dx, gy + dy, d, d, gHot and T.accent or OFFW, gHot and 1 or 0.65)
        end
    end
    if QA.pagerShown() then
        local dots = QA.dots(g)
        if dots then
            for _, d in ipairs(dots) do
                local cur = d.p == page
                local hot = hit and hit.kind == "dot" and hit.p == d.p
                local c = cur and T.accent or OFFW
                local a = cur and 1 or (hot and 0.8 or (QA.count(d.p) > 0 and 0.45 or 0.2))
                self:rect(d.x + ox, d.y + oy, d.d, d.d, c, a)
            end
        else
            local t = tostring(page)
            local fh = XEG.fh(XEG.FONT_S)
            local cx = g.vert and (rx + grip + floor((rw - grip) / 2)) or (rx + floor(rw / 2))
            local cy = g.vert and (ry + floor((rh - fh) / 2)) or (ry + grip + floor((rh - grip - fh) / 2))
            self:textC(t, cx, cy, T.accent, XEG.FONT_S, 1)
        end
    end

    -- slots
    if not g.collapsed then
        local fh = XEG.fh(XEG.FONT_S)
        local len = max(3, floor(g.sz * 0.18))
        local notice = QA.notice
        local altNums = QA.altKeysOn and QA.altKeysOn() and QA.altDown()
        if notice and (now < notice.at or now - notice.at > QA.NOTE_MS) then QA.notice, notice = nil, nil end
        for k = 1, g.n do
            local i = g.map and g.map[k] or k
            local sx, sy = QA.slotRect(g, i)
            sx, sy = sx + ox, sy + oy
            local sz = g.sz
            local sc = pg.slots[i]
            local hot = hit and hit.kind == "slot" and hit.i == i
            local dragFrom = QA.drag and QA.drag.p == page and QA.drag.i == i
            if dragFrom then sc = nil end
            self:rect(sx, sy, sz, sz, T.void, sa)
            if sc then self:drawSlot(g, sc, sx, sy, sz, pl, now, hot, len, fh, notice) else
                local c, a, th = OFFW, 0.16, 1
                if hot and (ext or QA.drag) then c, a, th = T.accent, 0.95, max(1, floor(2 * s)) self:rect(sx, sy, sz, sz, T.accent, 0.1)
                elseif hot then a = 0.4 end
                corners(self, sx, sy, sz, sz, len, th, c, a)
                if hot and not ext and not QA.drag then self:textC("+", sx + floor(sz / 2), sy + floor((sz - fh) / 2), OFFW, XEG.FONT_S, 0.5) end
            end
            if sc and hot and (ext or QA.drag) then
                self:rect(sx, sy, sz, sz, T.accent, 0.12)
                corners(self, sx, sy, sz, sz, len, max(1, floor(2 * s)), T.accent, 0.95)
            end
            -- a slot used with Alt + its number lights up for a moment
            local fl = QA.flashSlot
            if fl and fl.p == page and fl.i == i and now >= fl.at and now - fl.at < 250 then
                self:rect(sx, sy, sz, sz, T.accent, 0.3 * (1 - (now - fl.at) / 250))
            end
            -- while Alt is held, slots 1 to 10 show their number key
            if altNums and i <= 10 then
                local t = tostring(i % 10)
                local tw = XEG.tw(XEG.FONT_S, t) + floor(4 * s)
                self:rect(sx, sy, tw, fh, T.void, 0.8)
                self:textC(t, sx + floor(tw / 2), sy, T.accent, XEG.FONT_S, 1)
            end
        end
    end

    -- resize handle: its own strip at the far end, always shown, with a solid corner grip; lit while hovered or in use
    local hx, hy, hw, hh = QA.handleRect(g)
    local canSize = hx ~= nil and QA.resizable(g)
    if hx then
        local hot = canSize and ((hit and hit.kind == "resize") or (QA.sizing and QA.sizing.active))
        hx, hy = hx + ox, hy + oy
        self:rect(hx, hy, hw, hh, hot and T.accent or T.void, hot and 0.25 or 0.5)
        self:border(hx, hy, hw, hh, hot and T.accent or T.line, hot and 0.9 or 0.45)
        -- the gear at the start of the strip, lit while hovered or while the settings are open
        local gx, gy, gw, gh = QA.gearRect(g)
        if gx then
            local gHot = (hit and hit.kind == "gear") or (XEG.QASettings and XEG.QASettings.isOpen())
            local gs = max(8, min(gw, gh) - max(3, floor(3 * s)))
            local col = gHot and T.accent or OFFW
            local gxx, gyy = gx + ox + floor((gw - gs) / 2), gy + oy + floor((gh - gs) / 2)
            if XEG.tex("gear") then self:glyph("gear", gxx, gyy, gs, col, gHot and 1 or 0.75)
            else self:textC("*", gx + ox + floor(gw / 2), gyy, col, XEG.FONT_S, gHot and 1 or 0.75) end
        end
    end
    if canSize then
        local hot = (hit and hit.kind == "resize") or (QA.sizing and QA.sizing.active)
        -- a stepped triangle in the strip's bottom-right corner, the usual "drag here to resize" sign
        local side = min(hw, hh) - max(4, floor(4 * s))
        local u = max(2, floor(side / 4))
        local rx2, by2 = hx + hw - max(2, floor(2 * s)), hy + hh - max(2, floor(2 * s))
        for k = 1, 4 do
            self:rect(rx2 - k * u, by2 - (5 - k) * u, k * u, u, hot and T.accent or OFFW, hot and 1 or 0.7)
        end
        if QA.sizing and QA.sizing.active then
            local t = tostring(floor(QA.scale() * 100 + 0.5))
            self:textC(t, ox + floor(w / 2), oy - XEG.fh(XEG.FONT_S) - floor(4 * s), T.accent, XEG.FONT_S, 1)
        end
    end

    if hit and not ext and not QA.drag and not (QA.moving and QA.moving.active) and not QA.sizing then self:tip(g, hit, pl) end
    if ext and over and XEG.Drag and XEG.Drag.active then
        XEG.Drag.hover = { ok = true, label = XEG.tr("Add to Quick Access") }
    end
    self:drawAbove(g, ox, oy, now, page)
    if QA.drag then self:drawGhost(g, mx, my, ox, oy) end
end

function XEGQuickAccess:drawSlot(g, sc, sx, sy, sz, pl, now, hot, len, fh, notice)
    local s = g.s
    local e = QA.states[sc]
    local inset = max(2, floor(sz * 0.14))
    local isItem = sc.k == "t" or sc.k == "i"
    local avail = e and e.avail
    local ready = e and (isItem and avail or (not isItem and e.status == 2))
    local c, a, th = OFFW, 0.22, 1
    if e and e.equipped then c, a = T.accent, 0.75 end
    if hot then c, a = T.accent, 0.9 end
    local flash = notice and notice.sc == sc
    if flash then c, a = T.critical, 0.95 end
    corners(self, sx, sy, sz, sz, len, th, c, a)
    local ia = 1
    if not e then ia = 0.5
    elseif isItem then ia = avail and (e.broken and 0.6 or 1) or 0.3
    else ia = (e.status == 2 and 1) or (e.status == nil and 0.7) or (e.status == 1 and 0.45) or 0.3 end
    local tex = e and e.tex
    local is = sz - inset * 2
    if tex then
        if e.item and ia >= 1 then self:itemIcon(e.item, tex, sx + inset, sy + inset, is, is, ia)
        else self:icon(tex, sx + inset, sy + inset, is, is, ia) end
    elseif e then
        self:textC("?", sx + floor(sz / 2), sy + floor((sz - fh) / 2), T.text3, XEG.FONT_S, 0.8)
    end
    if not e then return end
    local bh = max(2, floor(2 * s))
    local by = sy + sz - bh - max(1, floor(2 * s))
    if isItem then
        if not avail then
            self:rect(sx + floor(3 * s), by, sz - floor(6 * s), bh, T.critical, 0.85)
        elseif e.f and QA.overlays() then
            local bw = sz - floor(6 * s)
            self:rect(sx + floor(3 * s), by, bw, bh, T.void, 0.55)
            self:rect(sx + floor(3 * s), by, max(1, floor(bw * XEG.clamp(e.f, 0, 1))), bh, e.col or OFFW, 0.95)
        end
        if sc.k == "t" and (e.count or 0) ~= 1 then
            local t = (e.count or 0) > 1 and (XEG.TIMES .. e.count) or "0"
            self:textR(t, sx + sz - floor(3 * s), sy + sz - fh - floor(3 * s), (e.count or 0) > 0 and OFFW or T.critical, XEG.FONT_S, 0.95)
        end
        local tx = sx + sz - floor(3 * s)
        if e.ammo then
            self:textR(e.ammo, tx, sy + floor(1 * s), e.ammoCol or OFFW, XEG.FONT_S, 0.95)
        elseif e.lit then
            local d = max(3, floor(4 * s))
            self:rect(tx - d, sy + floor(3 * s), d, d, T.accentHi, 0.95)
        end
        if sc.k == "i" then
            local d = max(2, floor(3 * s))
            self:rect(sx + floor(3 * s), sy + floor(3 * s), d, d, T.accentHi, 0.9)
        end
        local f = QA.running(sc, pl)
        if f then self:rect(sx, sy + sz - bh, max(1, floor(sz * XEG.clamp(f, 0, 1))), bh, T.accentHi, 0.95) end
    else
        local st = sc.k == "r" and CR.st or BD.st
        local job = sc.k == "r" and st and st.job
        if job and e.r and job.r == e.r and CR.busy(st) then
            local f = CR.progress(st) or 0
            self:rect(sx, sy + sz - max(3, floor(4 * s)), floor(sz * XEG.clamp(f, 0, 1)), max(3, floor(4 * s)), T.accentHi, 0.95)
            if job.left > 1 then self:textR(tostring(job.left), sx + sz - floor(3 * s), sy + floor(1 * s), OFFW, XEG.FONT_S, 0.95) end
        elseif e.status == 2 then
            self:rect(sx + floor(3 * s), by, sz - floor(6 * s), bh, T.good, 0.75)
        elseif e.status == 0 or e.unknown then
            self:rect(sx + floor(3 * s), by, sz - floor(6 * s), bh, T.critical, 0.85)
        end
        if sc.k == "b" and st and st.placing and st.entity and st.sel == e.r
            and try(function() return getCell():getDrag(pl:getPlayerNum()) end) == st.entity then
            corners(self, sx, sy, sz, sz, len, max(1, floor(2 * s)), T.accentHi, 0.6 + 0.35 * math.abs(math.sin(now / 250)))
        end
    end
    if g.labels and e.name then
        local t = TY.fit("meta", s, e.name, sz + floor(2 * s))
        self:typeC(t, sx + floor(sz / 2), sy + sz + floor(g.lh / 2), "meta", ready and T.text2 or T.text3, 1, "center")
    end
end

-- Page name after switching, and short notes ("None on you"), just above the bar. Drawn outside the
-- element, so they never take clicks.
function XEGQuickAccess:drawAbove(g, ox, oy, now, page)
    local s = g.s
    local text, col
    local n = QA.notice
    if n then text, col = n.text, T.critical
    elseif QA.flashAt and now >= QA.flashAt and now - QA.flashAt < QA.FLASH_MS then
        text, col = QA.pageName(page), T.accent
    end
    if not text then return end
    local age = n and (now - n.at) or (now - QA.flashAt)
    local limit = n and QA.NOTE_MS or QA.FLASH_MS
    local a = min(1, (limit - age) / 300)
    local padX = floor(6 * s)
    local th = TY.height("meta", s)
    local bw = TY.width("meta", s, text) + padX * 2
    local bh = th + floor(6 * s)
    local x = ox
    local y = oy - bh - floor(3 * s)
    if QA.box and QA.box.y - bh - floor(3 * s) < 0 then y = oy + g.h + floor(3 * s) end
    self:rect(x, y, bw, bh, T.void, 0.8 * a)
    self:rect(x, y, max(1, floor(2 * s)), bh, col, 0.95 * a)
    self:typeC(text, x + padX, y + floor(bh / 2), "meta", T.text, a)
end

function XEGQuickAccess:drawGhost(g, mx, my, ox, oy)
    local d = QA.drag
    local e = d and QA.states[d.sc]
    local s = g.s
    local sz = floor(g.sz * 0.85)
    local x, y = mx + ox + floor(10 * s), my + oy + floor(8 * s)
    self:rect(x, y, sz, sz, T.void, 0.7)
    if e and e.tex then self:icon(e.tex, x + floor(3 * s), y + floor(3 * s), sz - floor(6 * s), sz - floor(6 * s), 0.9) end
    local outside = not inRect(mx, my, 0, 0, g.w, g.h)
    if outside then
        local text = QA.get().locked and XEG.tr("Slots are locked") or XEG.tr("Remove")
        local th = TY.height("meta", s)
        local bw = TY.width("meta", s, text) + floor(12 * s)
        local ly = y + sz + floor(3 * s)
        self:rect(x, ly, bw, th + floor(6 * s), T.void, 0.8)
        self:rect(x, ly, max(1, floor(2 * s)), th + floor(6 * s), T.critical, 0.95)
        self:typeC(text, x + floor(6 * s), ly + floor((th + floor(6 * s)) / 2), "meta", T.text, 1)
    end
end

-- Tooltips -----------------------------------------------------------------------------------------------

function XEGQuickAccess:tipLines(g, hit, pl)
    local page = QA.current()
    if hit.kind == "rail" or hit.kind == "num" or hit.kind == "dot" then
        local p = hit.kind == "dot" and hit.p or page
        local lines = { XEG.tr("Quick Access: %s", QA.pageName(p)) }
        if QA.pageCount() > 1 then lines[#lines + 1] = XEG.tr("Page %s of %s", p, QA.pageCount()) end
        if hit.kind == "dot" and p ~= page then lines[#lines + 1] = XEG.tr("Click: show this page") end
        if hit.kind == "num" then lines[#lines + 1] = XEG.tr("Click: next page") end
        if QA.wheelPages() and QA.pageCount() > 1 then lines[#lines + 1] = XEG.tr("Scroll here: change page") end
        if CFG.get("hudLocked") == true then lines[#lines + 1] = XEG.tr("The HUD is locked in Mod Options")
        else lines[#lines + 1] = XEG.tr("Drag: move the bar") end
        lines[#lines + 1] = g.collapsed and XEG.tr("Click: expand") or XEG.tr("Double-click: collapse")
        local hid = QA.hiddenCount(page)
        if hid > 0 then lines[#lines + 1] = XEG.tr("%s more shortcuts are in slots this size doesn't show", hid) end
        lines[#lines + 1] = XEG.tr("Right-click: more")
        return lines
    end
    if hit.kind == "gear" then
        return { XEG.tr("Quick Access settings"), XEG.tr("Click: rows, pages, size and more") }
    end
    if hit.kind == "resize" then
        return { XEG.tr("Drag: resize the bar"), XEG.tr("Size: %s of 100", floor(QA.scale() * 100 + 0.5)),
            XEG.tr("Hold Shift: no snapping to the normal size"), XEG.tr("Double-click: normal size") }
    end
    if hit.kind ~= "slot" then return nil end
    local sc = QA.at(page, hit.i)
    if not sc then
        return { XEG.tr("Empty slot"), XEG.tr("Drag an item here from an inventory list"),
            XEG.tr("Or right-click a recipe or building project and pick Add to Quick Access") }
    end
    local e = QA.states[sc]
    if not e then return { sc.v } end
    local lines = { e.name or sc.v }
    if sc.k == "t" or sc.k == "i" then
        if e.avail then
            if sc.k == "t" then lines[#lines + 1] = XEG.tr("You carry %s", e.count or 1)
            else lines[#lines + 1] = XEG.tr("This exact item") end
            if e.broken then lines[#lines + 1] = XEG.tr("Broken") end
            lines[#lines + 1] = XEG.tr("Double-click: use   Right-click: its menu")
        else
            if e.unknown then lines[#lines + 1] = XEG.tr("Unknown item (its mod may be off)")
            elseif sc.k == "i" then lines[#lines + 1] = XEG.tr("That exact item is not on you")
            else lines[#lines + 1] = XEG.tr("None on you") end
            lines[#lines + 1] = XEG.tr("The slot stays until you remove it")
        end
    elseif sc.k == "r" then
        local st = CR.st
        if not e.r then lines[#lines + 1] = XEG.tr("Recipe not found (its mod may be off)")
        elseif st and st.job and st.job.r == e.r and CR.busy(st) then lines[#lines + 1] = XEG.tr("Crafting %s%%", floor((CR.progress(st) or 0) * 100 + 0.5))
        elseif e.status == 2 then lines[#lines + 1] = XEG.tr("Ready to craft")
        elseif e.status == nil then lines[#lines + 1] = XEG.tr("Checking what you have...")
        else lines[#lines + 1] = XEG.tr("Missing something: double-click to see what") end
        if e.r then
            lines[#lines + 1] = XEG.tr("Double-click: craft 1   Shift + double-click: craft all you can")
            lines[#lines + 1] = XEG.tr("Right-click: open in Crafting")
        end
    elseif sc.k == "b" then
        if not e.r then lines[#lines + 1] = XEG.tr("Building project not found (its mod may be off)")
        elseif e.status == 2 then lines[#lines + 1] = XEG.tr("Ready to build")
        elseif e.status == nil then lines[#lines + 1] = XEG.tr("Checking what you have...")
        else lines[#lines + 1] = XEG.tr("Missing something: double-click to see what") end
        if e.r then
            lines[#lines + 1] = XEG.tr("Double-click: place it   Right-click: open in Building")
        end
    end
    lines[#lines + 1] = XEG.tr("Shift + right-click: shortcut options   Drag: move it")
    return lines
end

function XEGQuickAccess:tip(g, hit, pl)
    local mode = QA.tipMode()
    if mode == 3 then return end
    local s = g.s
    local b = QA.box
    if mode == 2 and hit.kind == "slot" and XEG.ItemTip then
        local sc = QA.at(QA.current(), hit.i)
        local e = sc and QA.states[sc]
        if e and e.item then
            self.tipEntry = self.tipEntry or {}
            self.tipEntry.item = e.item
            XEG.ItemTip.offer(self.tipEntry, self, true)
            return
        end
    end
    local lines = self:tipLines(g, hit, pl)
    if not lines then return end
    local ax = b.x
    if hit.kind == "slot" then ax = b.x + (QA.slotRect(g, hit.i)) end
    XEG.setTip(nil, ax, b.y - floor(6 * s), nil, { lines = lines, v2 = true, align = "above" })
end

-- Mouse --------------------------------------------------------------------------------------------------

local function shiftDown() return isShiftKeyDown ~= nil and isShiftKeyDown() == true end

function XEGQuickAccess:currentHit()
    local g = QA.view(QA.geom(self.s or XEG.scale()))
    local mx, my = self:mouse()
    return g, QA.hit(g, mx, my), mx, my
end

function XEGQuickAccess:onMouseDown(x, y)
    if self.parked then return false end
    if dragging() then return true end
    local g, hit = self:currentHit()
    self.press = nil
    if not hit then return true end
    if hit.kind == "resize" then
        local b = QA.box
        local now = XEG.now()
        local rc = self.resizeClick
        if rc and now >= rc and now - rc < QA.DOUBLE_MS then
            self.resizeClick = nil
            QA.resetScale()
            return true
        end
        self.resizeClick = now
        if b then
            QA.sizing = { mx = getMouseX(), my = getMouseY(), s0 = QA.scale(), w0 = g.fw, h0 = g.fh, x = b.x, y = b.y }
            self:setCapture(true)
        end
    elseif hit.kind == "slot" then
        local sc = QA.at(QA.current(), hit.i)
        self.press = { kind = "slot", i = hit.i, sc = sc, mx = getMouseX(), my = getMouseY() }
    elseif hit.kind == "dot" or hit.kind == "num" then
        self.press = { kind = hit.kind, p = hit.p }
    elseif hit.kind == "gear" then
        self.press = { kind = "gear" }
    elseif hit.kind == "rail" or hit.kind == "plate" then
        local b = QA.box
        self.press = { kind = hit.kind }
        if CFG.get("hudLocked") ~= true and b then
            QA.moving = { dx = getMouseX() - b.x, dy = getMouseY() - b.y, mx = getMouseX(), my = getMouseY(), x = b.x, y = b.y }
            self:setCapture(true)
        end
    end
    return true
end

function XEGQuickAccess:onMove()
    local z = QA.sizing
    if z then
        local dx, dy = getMouseX() - z.mx, getMouseY() - z.my
        if not z.active and dx * dx + dy * dy >= 9 then z.active = true self.resizeClick = nil end
        if z.active then QA.setScale(QA.dragScale(z, dx, dy, shiftDown()), true) end
        return
    end
    local m = QA.moving
    if m then
        local dx, dy = getMouseX() - m.mx, getMouseY() - m.my
        if not m.active and dx * dx + dy * dy >= QA.DRAG_PX * QA.DRAG_PX then m.active = true end
        if m.active then
            local g = QA.view(QA.geom(self.s or XEG.scale()))
            local sw, sh = screen()
            local x = XEG.clamp(getMouseX() - m.dx, 0, max(0, sw - g.w))
            local y = XEG.clamp(getMouseY() - m.dy, 0, max(0, sh - g.h))
            if CFG.get("qaSnap") ~= false then
                local d = max(8, floor(12 * g.s))
                x, y = snap(x, max(0, sw - g.w), d), snap(y, max(0, sh - g.h), d)
            end
            m.x, m.y = x, y
        end
        return
    end
    local p = self.press
    if p and p.kind == "slot" and p.sc and not QA.drag then
        local dx, dy = getMouseX() - p.mx, getMouseY() - p.my
        if dx * dx + dy * dy >= QA.DRAG_PX * QA.DRAG_PX then
            QA.drag = { p = QA.current(), i = p.i, sc = p.sc }
            self.lastClick = nil
            self:setCapture(true)
        end
    end
    local d = QA.drag
    if d then
        -- holding a slot over a page dot for a moment shows that page, to drop it there
        local _, hit = self:currentHit()
        if hit and hit.kind == "dot" and hit.p ~= QA.current() then
            if d.overDot ~= hit.p then d.overDot, d.dotAt = hit.p, XEG.now()
            elseif XEG.now() - d.dotAt > 450 then QA.setPage(hit.p) end
        else
            d.overDot = nil
        end
    end
end
XEGQuickAccess.onMoveOutside = XEGQuickAccess.onMove

-- The items of a drag or a menu, with the game's stacks unpacked.
local function itemsOf(dragged)
    local list = ISInventoryPane and ISInventoryPane.getActualItems and try(function() return ISInventoryPane.getActualItems(dragged) end)
    return list or {}
end

-- Items dropped on the bar: on a slot, the first one goes there; anywhere else on the bar, one shortcut per
-- kind of item into the page's free slots.
function QA.dropItems(items, i)
    if not items or #items == 0 then return false end
    local page = QA.current()
    if i then
        local sc = QA.itemShortcut(items[1])
        return sc ~= nil and QA.assign(page, i, sc) == true
    end
    local any, seen = false, {}
    for _, it in ipairs(items) do
        local sc = QA.itemShortcut(it)
        local key = sc and (sc.v .. "|" .. tostring(sc.id))
        if sc and not seen[key] then
            seen[key] = true
            local ok, why = QA.assign(page, nil, sc)
            if ok then any = true elseif why == "full" then QA.note(nil, XEG.tr("This page is full")) break end
        end
    end
    return any
end

-- A click on the rail expands a collapsed bar; a double-click on it collapses the bar.
function XEGQuickAccess:railClicked(g)
    if g.collapsed then
        self.railClick = nil
        QA.setCollapsed(false)
        return
    end
    local now = XEG.now()
    local lc = self.railClick
    if lc and now >= lc and now - lc < QA.DOUBLE_MS then
        self.railClick = nil
        QA.setCollapsed(true)
    else
        self.railClick = now
    end
end

-- Ends a resize: the new size is saved, and a bar you had moved keeps its top-left corner where it was.
function XEGQuickAccess:finishSize()
    local z = QA.sizing
    QA.sizing = nil
    self:setCapture(false)
    if not (z and z.active) then return false end
    QA.save()
    if QA.moved() then
        local g = QA.geom(self.s or XEG.scale())
        L.commit(ID, z.x, z.y, g.fw, g.fh, g.s)
    end
    return true
end

function XEGQuickAccess:finishMove()
    local m = QA.moving
    QA.moving = nil
    self:setCapture(false)
    if not (m and m.active) then return false end
    local g = QA.geom(self.s or XEG.scale())
    L.commit(ID, m.x, m.y, g.fw, g.fh, g.s)
    return true
end

function XEGQuickAccess:finishDrag(inside, hit)
    local d = QA.drag
    QA.drag = nil
    self:setCapture(false)
    if not d then return false end
    local page = QA.current()
    if inside then
        if hit and hit.kind == "slot" and not (d.p == page and d.i == hit.i) and not QA.get().locked then
            if not QA.swap(d.p, d.i, page, hit.i) then QA.note(d.sc, XEG.tr("Already on this page")) end
        end
        return true
    end
    if QA.get().locked then QA.note(d.sc, XEG.tr("Slots are locked")) return true end
    QA.remove(d.p, d.i)
    QA.note(nil, XEG.tr("Removed. Right-click the bar to undo"))
    return true
end

function XEGQuickAccess:onMouseUp(x, y)
    local g, hit = self:currentHit()
    local D = XEG.Drag
    if D and D.active then
        local items = D.active.items
        D.cancel()
        local ok, err = pcall(QA.dropItems, items, hit and hit.kind == "slot" and hit.i or nil)
        if not ok then warn("drop", err) end
        return true
    end
    if ISMouseDrag and ISMouseDrag.dragging then
        -- the game's own drag: its inventory clears the drag itself, and nothing drops on the floor because
        -- the mouse is over the bar
        local ok, err = pcall(QA.dropItems, itemsOf(ISMouseDrag.dragging), hit and hit.kind == "slot" and hit.i or nil)
        if not ok then warn("drop", err) end
        return true
    end
    if QA.sizing then self:finishSize() return true end
    if QA.moving then
        local moved = self:finishMove()
        local p = self.press
        self.press = nil
        if not moved and p and p.kind == "rail" then self:railClicked(g) end
        return true
    end
    if QA.drag then self.press = nil return self:finishDrag(hit ~= nil, hit) end
    local p = self.press
    self.press = nil
    if not p or not hit then return true end
    if p.kind == "dot" and hit.kind == "dot" and hit.p == p.p then QA.setPage(p.p) return true end
    if p.kind == "num" and hit.kind == "num" then QA.step(1) return true end
    if p.kind == "gear" and hit.kind == "gear" then QA.openSettings() return true end
    if p.kind == "rail" and (hit.kind == "rail" or g.collapsed) then self:railClicked(g) return true end
    if p.kind == "slot" and hit.kind == "slot" and hit.i == p.i then
        local now = XEG.now()
        local lc = self.lastClick
        if lc and lc.i == p.i and now >= lc.t and now - lc.t < QA.DOUBLE_MS then
            self.lastClick = nil
            QA.activate(QA.current(), p.i, shiftDown())
        else
            self.lastClick = { i = p.i, t = now }
        end
    end
    return true
end

function XEGQuickAccess:onMouseUpOutside()
    if QA.sizing then self:finishSize() end
    if QA.moving then self:finishMove() end
    if QA.drag then self:finishDrag(false) end
    self.press = nil
    return true
end

-- The game's own double-click event, as a second route (low frame rates); QA.activate keeps it to one use.
function XEGQuickAccess:onMouseDoubleClick()
    if self.parked or dragging() or QA.drag or QA.moving or QA.sizing then return true end
    local _, hit = self:currentHit()
    if not hit or hit.kind ~= "slot" then return true end
    local lc = self.lastClick
    if not (lc and lc.i == hit.i and XEG.now() - lc.t < QA.DOUBLE_MS) then return true end
    self.lastClick = nil
    self.press = nil
    QA.activate(QA.current(), hit.i, shiftDown())
    return true
end

-- The wheel only changes pages over the dots end of the bar; over the slots it is left to the game (zoom).
function XEGQuickAccess:onMouseWheel(del)
    if self.parked or not QA.wheelPages() or QA.pageCount() < 2 then return false end
    local _, hit = self:currentHit()
    if not hit or (hit.kind ~= "rail" and hit.kind ~= "dot" and hit.kind ~= "num") then return false end
    QA.step(del > 0 and 1 or -1)
    return true
end

function XEGQuickAccess:onRightMouseDown()
    if self.parked then return false end
    return true
end

function XEGQuickAccess:onRightMouseUp()
    if self.parked then return false end
    local _, hit = self:currentHit()
    if not hit then return true end
    local ok, err = pcall(function()
        if hit.kind == "slot" and QA.at(QA.current(), hit.i) then QA.slotClick(QA.current(), hit.i, shiftDown())
        else QA.barMenu() end
    end)
    if not ok then warn("menu", err) end
    return true
end

-- Menus --------------------------------------------------------------------------------------------------

local function menuAt() return ISContextMenu.get(0, getMouseX(), getMouseY()) end

local function addPagesMenu(menu, label, fn, current)
    local n = QA.pageCount()
    if n <= 1 then
        menu:addOption(label, nil, function() fn(1) end)
        return
    end
    local opt = menu:addOption(label)
    local sub = menu:getNew(menu)
    menu:addSubMenu(opt, sub)
    for p = 1, n do
        local name = QA.pageName(p)
        if p == current then name = name .. "  *" end
        local o = sub:addOption(name, nil, function() fn(p) end)
        if QA.freeCount(p) == 0 and o then o.notAvailable = true end
    end
end

-- Right-click on a slot: an item's own game menu, the recipe in Crafting, the project in Building. Shift, a
-- missing item or recipe, or a closed tab gives the shortcut's own menu instead.
function QA.slotClick(p, s, shift)
    local sc = QA.at(p, s)
    if not sc then return end
    local pl = getSpecificPlayer(0)
    if shift or not pl then return QA.slotMenu(p, s) end
    if sc.k == "t" or sc.k == "i" then
        local item = QA.resolveItem(sc, pl)
        if not item then return QA.slotMenu(p, s) end
        QA.inSlotMenu = true
        local ok, menu = pcall(XEG.itemMenu, pl:getPlayerNum(), { item }, true)
        QA.inSlotMenu = false
        if not ok or not menu then return QA.slotMenu(p, s) end
        local opt = menu:addOption(XEG.tr("Quick Access"))
        local sub = menu:getNew(menu)
        menu:addSubMenu(opt, sub)
        QA.slotOptions(sub, p, s, pl)
        return
    end
    local r = QA.recipe(sc.k, sc.v)
    if r and sc.k == "r" and CR.enabled() then CR.open(r) return end
    if r and sc.k == "b" and BD.enabled() then BD.open(r) return end
    QA.slotMenu(p, s)
end

function QA.slotMenu(p, s)
    local menu = menuAt()
    if not menu then return end
    QA.slotOptions(menu, p, s, getSpecificPlayer(0))
end

function QA.slotOptions(menu, p, s, pl)
    local sc = QA.at(p, s)
    if not sc then return end
    local e = QA.states[sc]
    local name = (e and e.name) or sc.v
    if sc.k == "t" or sc.k == "i" then
        local item = pl and QA.resolveItem(sc, pl)
        if item then
            menu:addOption(XEG.tr("Use"), nil, function() QA.use(p, s) end)
        end
        if sc.k == "t" then
            local o = menu:addOption(XEG.tr("Use only this exact one"), nil, function()
                local x = QA.itemShortcut(item, true)
                if x and x.k == "i" then QA.page(p).slots[s] = x QA.forget(sc) QA.save() end
            end)
            if not item and o then o.notAvailable = true end
        else
            menu:addOption(XEG.tr("Use any %s", name), nil, function()
                local x = { k = "t", v = sc.v }
                if QA.find(x, p) then QA.remove(p, s) QA.lastRemoved = nil return end
                QA.page(p).slots[s] = x
                QA.forget(sc)
                QA.save()
            end)
        end
    else
        local r = QA.recipe(sc.k, sc.v)
        local st = r and pl and (sc.k == "r" and CR.state(pl) or BD.state(pl))
        if r and st then
            local ready = e and e.status == 2 and not CR.busy(st)
            if sc.k == "r" then
                if ready then
                    menu:addOption(XEG.tr("Craft 1"), nil, function() QA.use(p, s) end)
                    menu:addOption(XEG.tr("Craft all you can"), nil, function() QA.use(p, s, true) end)
                end
                if CR.busy(st) then menu:addOption(XEG.tr("Cancel crafting"), nil, function() CR.cancel(st) end) end
                if CR.enabled() then menu:addOption(XEG.tr("Open in Crafting"), nil, function() CR.open(r) end) end
            else
                if ready then menu:addOption(XEG.tr("Build"), nil, function() QA.use(p, s) end) end
                if BD.enabled() then menu:addOption(XEG.tr("Open in Building"), nil, function() BD.open(r) end) end
            end
        end
    end
    if QA.pageCount() > 1 then
        local opt = menu:addOption(XEG.tr("Move to page"))
        local sub = menu:getNew(menu)
        menu:addSubMenu(opt, sub)
        for q = 1, QA.pageCount() do
            if q ~= p then
                local o = sub:addOption(QA.pageName(q), nil, function() QA.moveTo(p, s, q) end)
                if o and not QA.freeSlot(q) and not QA.find(sc, q) then o.notAvailable = true end
            end
        end
    end
    menu:addOption(XEG.tr("Remove from Quick Access"), nil, function() QA.remove(p, s) end)
end

function QA.barMenu()
    local menu = menuAt()
    if not menu then return end
    local data = QA.get()
    local page = QA.current()
    if QA.pageCount() > 1 then
        local opt = menu:addOption(XEG.tr("Page"))
        local sub = menu:getNew(menu)
        menu:addSubMenu(opt, sub)
        for p = 1, QA.pageCount() do
            local name = QA.pageName(p) .. "  (" .. QA.count(p) .. ")"
            if p == page then name = name .. "  *" end
            sub:addOption(name, nil, function() QA.setPage(p) end)
        end
    end
    menu:addOption(XEG.tr("Rename this page..."), nil, function()
        if XEG.askName then
            XEG.askName(0, XEG.tr("Rename page"), QA.pageName(page), function(name) QA.rename(page, name) end, nil,
                XEG.tr("Leave it empty for the default name."))
        end
    end)
    if QA.count(page) > 0 then
        local opt = menu:addOption(XEG.tr("Clear this page"))
        local sub = menu:getNew(menu)
        menu:addSubMenu(opt, sub)
        sub:addOption(XEG.tr("Remove its %s shortcuts", QA.count(page)), nil, function() QA.clearPage(page) end)
    end
    if QA.hiddenCount(page) > 0 and QA.freeSlot(page) then
        menu:addOption(XEG.tr("Bring back shortcuts this size doesn't show"), nil, function() QA.compact(page) end)
    end
    if QA.lastRemoved then menu:addOption(XEG.tr("Undo remove"), nil, function() QA.undo() end) end
    menu:addOption(data.locked and XEG.tr("Unlock slots") or XEG.tr("Lock slots"), nil, function() QA.setLocked(not data.locked) end)
    menu:addOption(data.collapsed and XEG.tr("Expand") or XEG.tr("Collapse"), nil, function() QA.setCollapsed(not data.collapsed) end)
    menu:addOption(QA.vertical() and XEG.tr("Turn horizontal") or XEG.tr("Turn vertical"), nil, function()
        setOption("qaOrientation", QA.vertical() and 1 or 2)
        geoVal = nil
    end)
    menu:addOption(XEG.tr("Quick Access settings"), nil, function() QA.openSettings() end)
    menu:addOption(XEG.tr("Reset position"), nil, function() QA.resetLayout() end)
    if QA.scale() ~= 1 then menu:addOption(XEG.tr("Normal size"), nil, function() QA.resetScale() end) end
    local k = CFG.get("qaKey")
    if type(k) == "number" and k > 0 then
        local kn = try(function() return Keyboard.getKeyName(k) end) or tostring(k)
        menu:addOption(XEG.tr("Hide (%s shows it again)", kn), nil, function() QA.toggleHidden() end)
    else
        menu:addOption(XEG.tr("Turn off Quick Access (Mod Options turns it back on)"), nil, function() setOption("quickAccess", false) end)
    end
end

-- Add to Quick Access, in the item menu of every inventory list that uses the game's item menu (XEG's lists
-- and the game's own inventory windows), and in the menus of the Crafting and Building tabs.
function QA.itemMenu(menu, items, pn)
    if QA.inSlotMenu or not menu or not QA.enabled() or pn ~= 0 or joypad() then return end
    local list, seen = {}, {}
    for _, it in ipairs(itemsOf(items or {})) do
        local sc = QA.itemShortcut(it)
        local key = sc and (sc.v .. "|" .. tostring(sc.id))
        if sc and not seen[key] then seen[key] = true list[#list + 1] = sc end
    end
    if #list == 0 then return end
    addPagesMenu(menu, XEG.tr("Add to Quick Access"), function(p)
        for _, sc in ipairs(list) do
            local ok, why = QA.assign(p, nil, sc)
            if not ok and why == "full" then QA.note(nil, XEG.tr("This page is full")) break end
        end
        QA.setPage(p)
    end, QA.current())
end

if Events.OnFillInventoryObjectContextMenu then
    Events.OnFillInventoryObjectContextMenu.Add(function(pn, menu, items)
        local ok, err = pcall(QA.itemMenu, menu, items, pn)
        if not ok then warn("item menu", err) end
    end)
end

function QA.recipeMenu(menu, kind, r)
    if not menu or not QA.enabled() or joypad() then return end
    local sc = QA.recipeShortcut(kind, r)
    if not sc then return end
    local p, s = QA.findAny(sc)
    if p then
        menu:addOption(XEG.tr("Remove from Quick Access"), nil, function() QA.remove(p, s) end)
        return
    end
    addPagesMenu(menu, XEG.tr("Add to Quick Access"), function(q)
        local ok, why = QA.assign(q, nil, sc)
        if not ok and why == "full" then QA.note(nil, XEG.tr("This page is full")) end
        QA.setPage(q)
    end, QA.current())
end

if XEGCraftPanel and XEGCraftPanel.rowMenu then
    local base = XEGCraftPanel.rowMenu
    function XEGCraftPanel:rowMenu(menu, st, row)
        base(self, menu, st, row)
        if self.E == CR and row and row.r then
            local ok, err = pcall(QA.recipeMenu, menu, "r", row.r)
            if not ok then warn("recipe menu", err) end
        end
    end
end
if XEGBuildPanel and rawget(XEGBuildPanel, "rowMenu") then
    local base = XEGBuildPanel.rowMenu
    function XEGBuildPanel:rowMenu(menu, st, row)
        base(self, menu, st, row)
        if row and row.r then
            local ok, err = pcall(QA.recipeMenu, menu, "b", row.r)
            if not ok then warn("building menu", err) end
        end
    end
end

-- Keys ---------------------------------------------------------------------------------------------------
-- None is set by default, so the bar takes none of the game's or the hotbar's keys.

function QA.onKey(key)
    if type(key) ~= "number" or key <= 0 or not QA.enabled() or joypad() then return end
    -- Escape drops a slot or the bar being dragged, where it was
    if key == Keyboard.KEY_ESCAPE and (QA.drag or QA.moving or QA.sizing) then
        if QA.sizing then QA.setScale(QA.sizing.s0) end
        QA.drag, QA.moving, QA.sizing = nil, nil, nil
        if QA.ui then pcall(QA.ui.setCapture, QA.ui, false) QA.ui.press = nil end
        return
    end
    local function is(id)
        local k = CFG.get(id)
        return type(k) == "number" and k > 0 and k == key
    end
    local show, nxt, prv = is("qaKey"), is("qaNextKey"), is("qaPrevKey")
    if not (show or nxt or prv) or XEG.inputBlocked() then return end
    local pl = getSpecificPlayer(0)
    if not pl or pl:isDead() then return end
    if show then QA.toggleHidden() return end
    if QA.get().hidden then return end
    QA.step(nxt and 1 or -1)
end
Events.OnKeyPressed.Add(QA.onKey)

-- Alt + 1 to Alt + 9 use the shown page's slots 1 to 9, Alt + 0 slot 10 (Mod Options qaAltKeys). The game's
-- hotbar would take the same number keys, so while Alt is held they are kept from it, from the press to the
-- release (the release counts even when Alt is let go first).
QA.altHeld = {}

function QA.altDown()
    if isAltKeyDown then
        local ok, v = pcall(isAltKeyDown)
        if ok and v ~= nil then return v == true end
    end
    if not (Keyboard and Keyboard.isKeyDown) then return false end
    return Keyboard.isKeyDown(Keyboard.KEY_LMENU or 56) or Keyboard.isKeyDown(Keyboard.KEY_RMENU or 184)
end

-- The slot an Alt + number key stands for, or nil. Keys 1..9 and 0 are the keyboard's row above the letters.
function QA.altSlot(key)
    if not Keyboard or type(key) ~= "number" then return nil end
    local k1, k0 = Keyboard.KEY_1 or 2, Keyboard.KEY_0 or 11
    if key == k0 then return 10 end
    if key >= k1 and key < k1 + 9 then return key - k1 + 1 end
    return nil
end

function QA.altKeysOn() return CFG.get("qaAltKeys") ~= false and QA.enabled() and not joypad() end

function QA.onAltKey(key)
    local n = QA.altSlot(key)
    if not n or not QA.altKeysOn() or not QA.altDown() then return end
    QA.altHeld[key] = true
    if XEG.inputBlocked() then return end
    local pl = getSpecificPlayer(0)
    if not pl or pl:isDead() then return end
    local data = QA.get()
    if data.hidden or n > QA.slotCount() then return end
    local p = QA.current()
    if not QA.at(p, n) then
        QA.note(nil, XEG.tr("Slot %s is empty", n))
        return
    end
    QA.flashSlot = { p = p, i = n, at = XEG.now() }
    QA.activate(p, n, shiftDown())
end
Events.OnKeyStartPressed.Add(QA.onAltKey)

-- true when the game's hotbar should not see this key event
function QA.altSwallow(key, release)
    if not QA.altSlot(key) then return false end
    local held = QA.altHeld[key]
    if release then QA.altHeld[key] = nil end
    if held then return true end
    return QA.altKeysOn() and QA.altDown()
end

if ISHotbar and not ISHotbar.xegAltWrapped then
    ISHotbar.xegAltWrapped = true
    for _, name in ipairs({ "onKeyStartPressed", "onKeyKeepPressed", "onKeyPressed" }) do
        local base = ISHotbar[name]
        if type(base) == "function" then
            -- PZ Butter: pcall/closure only when the key is actually an alt hotbar key.
            ISHotbar[name] = function(key, ...)
                if QA.altSlot(key) ~= nil then
                    local ok, skip = pcall(QA.altSwallow, key, name == "onKeyPressed")
                    if ok and skip then return end
                end
                return base(key, ...)
            end
        end
    end
end

-- Upkeep -------------------------------------------------------------------------------------------------

-- Once: the favourite recipes the quick-craft bar showed go onto page 2, unless you had
-- hidden that bar.
function QA.migrate(pl)
    local data = QA.get()
    if data.migrated then return end
    data.migrated = true
    local ok, err = pcall(function()
        if CFG.get("quickCraft") == false or not CR.enabled() or not L.visible("quickcraft") then return end
        local st = CR.state(pl)
        local favs = st and CR.favourites(st) or {}
        local pg = data.pages[2]
        local s, n = 1, 0
        for _, r in ipairs(favs) do
            local sc = QA.recipeShortcut("r", r)
            if sc and not QA.find(sc, 2) then
                while s <= QA.MAX_SLOTS and pg.slots[s] do s = s + 1 end
                if s > QA.MAX_SLOTS then break end
                pg.slots[s] = sc
                n = n + 1
            end
        end
        -- the recipes you had on the quick-craft bar stay in view: the bar opens on that page
        if n > 0 and QA.count(1) == 0 and QA.pageCount() >= 2 then data.page = 2 end
    end)
    if not ok then warn("copying favourite recipes", err) end
    QA.save()
end

-- Recipe and project checks for the slots shown, only while you stand still (walking past containers would
-- otherwise re-check every recipe again and again) and not while their own tab is open (it checks itself).
function QA.syncRecipes(pl)
    local pg = QA.page(QA.current())
    local wantR, wantB = false, false
    for s = 1, QA.slotCount() do
        local sc = pg.slots[s]
        if sc then
            if sc.k == "r" then wantR = true elseif sc.k == "b" then wantB = true end
        end
    end
    if not (wantR or wantB) then QA.lastSq = nil return end
    local sq = ask(pl, "getCurrentSquare")
    local still = sq ~= nil and sq == QA.lastSq
    QA.lastSq = sq
    if not still then return end
    if wantR and not XEG.isOpen("crafting") then
        local st = CR.state(pl)
        if st then CR.refresh(st, false, true) end
    end
    if wantB and BD.available() and not XEG.isOpen("building") then
        local st = BD.state(pl)
        if st then CR.refresh(st, false, true) end
    end
end

-- When an XEG screen opens (and again a moment later, for the windows it opens with it) and when a drag starts,
-- the bar is brought to the top, so the screen does not cover it. Never over an open right-click menu or name
-- box, and not while the mouse is on it (its own tooltip sits above it). It is not raised otherwise, so it
-- never covers a window or dialog opened later.
function QA.raise(force)
    local ui = QA.ui
    if not ui or not QA.shownNow then return end
    if XEG.menuOpen() or XEG.nameBox then return end
    if QA.hot and not force then return end
    pcall(ui.bringToTop, ui)
end

local tick = 0
function QA.sync()
    local pl = getSpecificPlayer(0)
    if not QA.enabled() or not pl or pl:isDead() then QA.wasOpen = false return end
    local open = XEG.isOpen()
    if open and not QA.wasOpen then QA.raise(true) QA.raiseAgain = 2 end
    QA.wasOpen = open
    if QA.raiseAgain then
        QA.raiseAgain = QA.raiseAgain - 1
        if QA.raiseAgain <= 0 then
            QA.raiseAgain = nil
            if open then QA.raise(false) end
        end
    end
    tick = tick + 1
    if tick % 3 ~= 0 then return end
    QA.migrate(pl)
    if QA.shownNow then QA.syncRecipes(pl) end
end

function QA.install()
    -- a different save or server than the shortcuts in memory: read that save's own
    if QA.data and QA.key ~= QA.saveKey() then QA.data = nil QA.get() end
    if QA.ui then return end
    local ok, err = pcall(function()
        local ui = XEGQuickAccess:new()
        ui:initialise()
        ui:addToUIManager()
        QA.ui = ui
    end)
    if not ok then warn("setup", err) end
end

Events.OnGameStart.Add(QA.install)
-- a new character starts with fresh slot states (the shortcuts stay)
Events.OnPlayerDeath.Add(function()
    QA.states, QA.pend, QA.usedAt, QA.homes = {}, {}, {}, {}
    QA.drag, QA.moving, QA.notice = nil, nil, nil
end)
local ticks = 0
Events.OnTick.Add(function()
    ticks = ticks + 1
    if ticks % 10 ~= 0 then return end
    local ok, err = pcall(QA.sync)
    if not ok then warn("upkeep", err) end
    if QA.anyHome() then
        local okh, errh = pcall(QA.checkHomes, getSpecificPlayer(0))
        if not okh then warn("put back", errh) end
    end
end)

if XEG.Drag then
    XEG.Drag.zones = XEG.Drag.zones or {}
    XEG.Drag.zones[ID] = QA.covers
end
if XEG.Session then
    XEG.Session.tipNeeds = XEG.Session.tipNeeds or {}
    XEG.Session.tipNeeds[ID] = function() return QA.shownNow == true and QA.tipMode() ~= 3 end
end

L.register(ID, { w = 300, h = 46, minW = 20, minH = 20 })
