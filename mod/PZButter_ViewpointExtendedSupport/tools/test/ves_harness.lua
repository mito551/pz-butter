local WATCHED = { "ISRadialMenu","ISWorldMap","ISMapWrapper","ISCraftingUI","ISHandcraftWindow","ISBuildWindow","ISCharacterInfoWindow" }

local function newWorld(opts)
  local env = setmetatable({}, { __index = _G })
  env._G = env
  env.require = function() end
  env.print = function() end
  local handlers = { OnTick = {}, OnGameStart = {}, OnEnterVehicle = {}, OnExitVehicle = {}, OnKeyPressed = {} }
  env.Events = {}
  for name, list in pairs(handlers) do env.Events[name] = { Add = function(f) list[#list+1] = f end } end
  env.handlers = handlers
  env.getTimestampMs = function() return 0 end

  -- ModOptions
  local function opt(default)
    return { value = default, getValue = function(s) return s.value end,
             addItem = function(s, _, sel) s.n = (s.n or 0) + 1; if sel then s.value = s.n end end }
  end
  env.PZAPI = { ModOptions = { create = function()
    return { addTitle = function() end,
             addTickBox = function(_, _, _, d) return opt(d) end,
             addKeyBind = function(_, _, _, d) return opt(d) end,
             addComboBox = function() local o = opt(1); o.value = 1; return o end } end } }

  -- Support (Java bridge stand-in); records effective values
  local log = {}
  local S = { autoCursor = false, invOpen = false, suppressed = false, viewEnabled = true }
  S.configure = function() end
  S.configureInteractionCycling = function() end
  S.configureContainerContentsCascade = function() end
  S.setTargetOutlineDisabled = function() end
  S.diagnosticTick = function() end
  S.setAutoCursorRequested = function(v) S.autoCursor = v end
  S.setInventoryOpen = function(v) S.invOpen = v and true or false end
  S.setInteractionMenuSuppressed = function(v) S.suppressed = v and true or false end
  S.isViewEnabled = function() return S.viewEnabled end
  S.isThirdPerson = function() return S.third or false end
  S.setThirdPerson = function(v) S.third = v; log[#log+1] = "third=" .. tostring(v) end
  S.isFreeCursor = function() return false end
  env.ViewpointExtendedSupport = S
  env.ViewpointQOL = { lockMouse = function() log[#log+1] = "lockMouse" end, isAvailable = function() return true end }

  -- player
  local player = { vehicle = nil }
  function player:getVehicle() return self.vehicle end
  env.player = player
  env.getSpecificPlayer = function() return player end

  -- inventory pages
  local ISInventoryPage = {}
  ISInventoryPage.setVisible = function(self, v) self.vis = v end
  ISInventoryPage.onToggleVisible = function(self) self.vis = not self.vis end
  ISInventoryPage.isVisible = function(self) return self.vis end
  ISInventoryPage.isCollapsed = function(self) return self.collapsed end
  ISInventoryPage.__index = ISInventoryPage
  env.ISInventoryPage = ISInventoryPage
  local inv = setmetatable({ vis = false, collapsed = false }, ISInventoryPage)
  local loot = setmetatable({ vis = false, collapsed = false }, ISInventoryPage)
  env.inv, env.loot = inv, loot
  env.getPlayerInventory = function() return inv end
  env.getPlayerLoot = function() return loot end

  -- UI manager and window classes
  local items = {}
  env.items = items
  local listObj = { size = function() return #items end, get = function(_, i) return items[i + 1] end }
  env.UIManager = { getUI = function() return listObj end }
  env.windowClasses = {}
  for _, name in ipairs(WATCHED) do
    local c = { Type = name }
    c.__index = c
    c.addToUIManager = function(self) items[#items+1] = self end
    c.removeFromUIManager = function(self) for i, w in ipairs(items) do if w == self then table.remove(items, i); break end end end
    c.isVisible = function(self) return self.vis end
    env[name] = c
    env.windowClasses[name] = c
  end
  env.newWindow = function(name, vis) return setmetatable({ vis = vis ~= false }, env.windowClasses[name]) end

  if opts.vesPerf then  -- V1 stand-in for the Java helper
    local set = {}; for _, n in ipairs(WATCHED) do set[n] = true end
    env.vesPerfWatchedWindows = function()
      local out = {}
      for _, w in ipairs(items) do if set[w.Type] then out[#out+1] = w end end
      return out
    end
  end
  env.S, env.log = S, log
  return env
end

local function loadInto(env, src, name)
  assert(load(src, name, "t", env))()
end

local function run(src, scenario, opts)
  local env = newWorld(opts)
  if scenario.setup then scenario.setup(env) end
  loadInto(env, src, "ves")
  for _, f in ipairs(env.handlers.OnGameStart) do f() end
  local trace = {}
  for t = 1, scenario.ticks do
    if scenario.act then scenario.act(t, env) end
    for _, f in ipairs(env.handlers.OnTick) do f() end
    trace[t] = string.format("%s|%s|%s", tostring(env.S.autoCursor), tostring(env.S.invOpen), tostring(env.S.suppressed))
  end
  return trace, env.log
end

local scenarios = {}
local function add(name, lag, s) s.name, s.lag = name, lag; scenarios[#scenarios+1] = s end

add("hooked windows", 0, { ticks = 120,
  setup = function(env) end,
  act = function(t, env)
    if t == 1 then env.w = env.newWindow("ISRadialMenu", true) end
    if t == 20 then env.w:addToUIManager() end
    if t == 60 then env.w:removeFromUIManager() end
    if t == 80 then env.c = env.newWindow("ISCharacterInfoWindow", true); env.c:addToUIManager() end
    if t == 90 then env.c.vis = false end   -- silent hide of a hooked window
  end })
scenarios[#scenarios].lag = 5

add("inventory hook + silent collapse", 5, { ticks = 120,
  act = function(t, env)
    if t == 30 then env.inv:setVisible(true) end
    if t == 50 then env.inv.collapsed = true end
    if t == 70 then env.inv.collapsed = false; env.inv:setVisible(false) end
    if t == 90 then env.loot:onToggleVisible() end
  end })

add("window opened outside hooks", 32, { ticks = 160,
  act = function(t, env)
    if t == 40 then env.s = env.newWindow("ISBuildWindow", true); env.items[#env.items+1] = env.s end
    if t == 100 then env.s.vis = false end
    if t == 130 then env.items[#env.items] = nil end
  end })

add("A-Life conversation", 5, { ticks = 120,
  setup = function(env)
    env.talk = { playerNum = 0, vis = false, isVisible = function(s) return s.vis end }
    env.ProjectALife = { TalkMenu = { instance = env.talk } }
  end,
  act = function(t, env)
    if t == 25 then env.talk.vis = true end
    if t == 70 then env.talk.vis = false end
  end })

add("vehicle", 5, { ticks = 140,
  act = function(t, env)
    if t == 10 then env.player.vehicle = {}; for _, f in ipairs(env.handlers.OnEnterVehicle) do f(env.player) end end
    if t == 30 then env.a = env.newWindow("ISCraftingUI", true); env.a:addToUIManager() end
    if t == 50 then env.b = env.newWindow("ISRadialMenu", true); env.b:addToUIManager() end
    if t == 70 then env.b:removeFromUIManager() end
    if t == 90 then env.player.vehicle = nil; for _, f in ipairs(env.handlers.OnExitVehicle) do f(env.player) end end
  end })

add("ergonomic ui", 5, { ticks = 120,
  setup = function(env)
    env.panel = { vis = true, body = false }
    function env.panel:isVisible() return self.vis end
    function env.panel:isBodyVisible() return self.body end
    env.ErgonomicUI = { panel = env.panel }
    env.ErgUIPanel = {
      togglePanelManual = function(self) self.body = not self.body end,
      revealFromContainer = function(self) self.body = true end,
      openSettings = function() end }
  end,
  act = function(t, env)
    if t == 20 then env.ErgUIPanel.togglePanelManual(env.panel) end
    if t == 60 then env.ErgUIPanel.togglePanelManual(env.panel) end
    if t == 80 then env.ErgUIPanel.revealFromContainer(env.panel) end
    if t == 100 then env.panel.vis = false end
  end })

local failures = 0
local function check(sc, label, a, b, la, lb)
  local bad
  for i = 1, #a do
    local ok = false
    for j = math.max(1, i - sc.lag), i do if b[i] == a[j] then ok = true; break end end
    if not ok then bad = bad or string.format("tick %d: patched %s, original %s", i, b[i], a[i]) end
  end
  if a[#a] ~= b[#b] then bad = bad or "final state differs" end
  if table.concat(la, ",") ~= table.concat(lb, ",") then bad = bad or ("events " .. table.concat(la, ",") .. " vs " .. table.concat(lb, ",")) end
  local changes = 0; for i = 2, #a do if a[i] ~= a[i-1] then changes = changes + 1 end end
  print(string.format("%s %-34s %-22s state changes=%d events=%d%s", bad and "FAIL" or "PASS", sc.name, label, changes, #la, bad and ("  " .. bad) or ""))
  if bad then failures = failures + 1 end
end

for _, sc in ipairs(scenarios) do
  local a, la = run(SRC_ORIG, sc, {})
  local b1, lb1 = run(SRC_PATCH, sc, {})
  local b2, lb2 = run(SRC_PATCH, sc, { vesPerf = true })
  check(sc, "lua scan", a, b1, la, lb1)
  check(sc, "java scan stub", a, b2, la, lb2)
end
print(failures == 0 and "ALL OK" or ("FAILURES: " .. failures))
