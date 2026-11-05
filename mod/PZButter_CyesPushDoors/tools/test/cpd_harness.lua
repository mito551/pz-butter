local FILES = { Config = SRC_CONFIG, Settings = SRC_SETTINGS, Feedback = SRC_FEEDBACK, Core = SRC_CORE }

local function newWorld(hookSrc, opts)
  local env = setmetatable({}, { __index = _G }); env._G = env
  env.print = function() end
  env.clock = 100000
  env.getTimestampMs = function() return env.clock end
  env.isServer = function() return false end
  env.isClient = function() return false end
  local lists = {}
  env.lists = lists
  env.Events = setmetatable({}, { __index = function(t, k)
    local l = {}; lists[k] = l
    local e = { Add = function(f) l[#l+1] = f end, Remove = function() end }
    rawset(t, k, e); return e end })
  env.fire = function(name, ...) for _, f in ipairs({ table.unpack(lists[name] or {}) }) do f(...) end end

  local loaded = {}
  env.require = function(name)
    local short = name:match("([^/]+)$")
    if loaded[short] then return true end
    loaded[short] = true
    local src = FILES[short]
    if short == "Hook" then src = hookSrc end
    if src then assert(load(src, short, "t", env))() end
    return true
  end

  -- world
  local doors, squares = {}, {}
  env.world = { squares = squares, calls = { getGridSquare = 0, getObjects = 0 } }
  local function square(x, y, z)
    local k = x .. "," .. y .. "," .. z
    local sq = squares[k]
    if not sq then
      sq = { x = x, y = y, z = z, objs = {} }
      function sq:getX() return self.x end
      function sq:getY() return self.y end
      function sq:getZ() return self.z end
      function sq:getObjects()
        env.world.calls.getObjects = env.world.calls.getObjects + 1
        local s = self
        return { size = function() return #s.objs end, get = function(_, i) return s.objs[i + 1] end }
      end
      squares[k] = sq
    end
    return sq
  end
  env.world.square = square
  env.getCell = function() return { getGridSquare = function(_, x, y, z)
    env.world.calls.getGridSquare = env.world.calls.getGridSquare + 1
    local sq = squares[x .. "," .. y .. "," .. z]; return sq end } end
  local function addObject(x, y, z, o)
    local sq = square(x, y, z); sq.objs[#sq.objs + 1] = o
    o.sq, o.idx = sq, #sq.objs - 1
    function o:getSquare() return self.sq end
    function o:getObjectIndex() return self.idx end
    return o
  end
  env.world.addClutter = function(x, y, z, n) for i = 1, n do addObject(x, y, z, { kind = "wall" }) end end
  env.world.addDoor = function(x, y, z, open)
    local d = addObject(x, y, z, { kind = "door", open = open or false })
    function d:IsOpen() return self.open end
    function d:isDoor() return true end
    return d
  end
  env.IsoDoor = { getGarageDoorIndex = function() return -1 end, getDoubleDoorIndex = function() return -1 end }
  env.instanceof = function(o, cls) return (cls == "IsoDoor" and o.kind == "door") or (cls == "IsoThumpable" and o.kind == "thumpable") end

  local player = { alive = true }
  function player:isAlive() return self.alive end
  function player:getCurrentSquare() return self.sq end
  function player:getX() return self.sq and self.sq.x + .5 or 0 end
  function player:getY() return self.sq and self.sq.y + .5 or 0 end
  env.player = player
  env.getPlayer = function() return player end
  env.moveTo = function(x, y, z) player.sq = env.world.square(x, y, z) end

  env.pending = {}
  env.toggle = function(door)
    local was = door.open
    door.open = not door.open
    if env.cpdDrainDoorToggles then
      for _, e in ipairs(env.pending) do if e.door == door then return end end   -- first prior of the tick wins
      env.pending[#env.pending + 1] = { door = door, was = was }
    end
  end
  env.CyesPushDoors = nil
  env.required = function() end
  if opts and opts.preload then opts.preload(env) end
  for _, n in ipairs({ "Config", "Settings", "Core", "Hook" }) do env.require("CyesPushDoors/" .. n) end
  local CPD = env.CyesPushDoors
  env.debugLog = {}
  CPD.mpDebug = function(tag, msg) env.debugLog[#env.debugLog + 1] = tag .. " " .. tostring(msg) end
  CPD.logDoorIdentity = function() end
  CPD.log = function(m) env.debugLog[#env.debugLog + 1] = "LOG " .. tostring(m) end
  CPD.supportsClosingControlImpact = function() return true end
  env.tick = function(n)
    for _ = 1, (n or 1) do env.clock = env.clock + 16; env.fire("OnTick") end
  end
  return env
end

local function transitions(log)
  local out = {}
  for _, line in ipairs(log) do
    local tag, key, tr = line:match("^(SCANNER_[%w_]+) key=(%S+) transition=(%S+)")
    if tag then out[#out + 1] = tag .. ":" .. key .. ":" .. tr end
  end
  return out
end

local results, failures = {}, 0
local function check(name, ok, detail)
  results[#results+1] = (ok and "PASS " or "FAIL ") .. name .. ((not ok and detail) and ("  " .. tostring(detail)) or "")
  if not ok then failures = failures + 1 end
end

local function scenario(name, setup, run, variants)
  local base
  for vi, v in ipairs(variants) do
    local env = newWorld(v.src, v.opts)
    setup(env)
    local log = run(env)
    local t = transitions(env.debugLog)
    if vi == 1 then base = { t = t, env = env }
    else
      local same = #t == #base.t
      if same then for i = 1, #t do if t[i] ~= base.t[i] then same = false end end end
      check(name .. " [" .. v.label .. "]: same transitions as original (" .. #base.t .. ")", same,
        "orig=" .. table.concat(base.t, "|") .. " got=" .. table.concat(t, "|"))
      results[#results+1] = string.format("     cost: getGridSquare %d -> %d, getObjects %d -> %d",
        base.env.world.calls.getGridSquare, env.world.calls.getGridSquare, base.env.world.calls.getObjects, env.world.calls.getObjects)
    end
  end
end

local variants = function()
  local javaStub = function(env)
    env.cpdDoorsNear = function(x, y, z, r)
      local out = {}
      for sx = x - r, x + r do for sy = y - r, y + r do
        local sq = env.world.squares[sx .. "," .. sy .. "," .. z]
        if sq then for _, o in ipairs(sq.objs) do if o.kind == "door" and o.sq and o.idx >= 0 then out[#out+1] = o end end end
      end end
      return out
    end
  end
  return {
    { label = "original", src = SRC_HOOK_ORIG },
    { label = "override, Lua walk", src = SRC_HOOK_NEW },
    { label = "override + Java door query", src = SRC_HOOK_NEW, opts = { preload = javaStub } },
    { label = "override + Java query + toggle events", src = SRC_HOOK_NEW, opts = { preload = function(env)
        javaStub(env)
        env.cpdDrainDoorToggles = function()
          if #env.pending == 0 then return nil end
          local out, priors = {}, {}
          for i, e in ipairs(env.pending) do out[i] = e.door; priors[i] = e.was end
          out.priors = priors
          env.pending = {}
          return out
        end
      end } },
  }
end

local function populate(env)
  env.world.addClutter(10, 10, 0, 6); env.world.addClutter(11, 10, 0, 6); env.world.addClutter(9, 9, 0, 4)
  env.A = env.world.addDoor(11, 10, 0, false)
  env.B = env.world.addDoor(12, 12, 0, true)
  env.C = env.world.addDoor(30, 30, 0, false)       -- out of range
  env.world.addClutter(13, 13, 0, 8)
  env.moveTo(10, 10, 0)
end

scenario("doors toggled while standing", populate, function(env)
  env.tick(4)
  env.toggle(env.A); env.tick(4)
  env.toggle(env.B); env.tick(4)
  env.toggle(env.A); env.tick(4)
  env.toggle(env.C); env.tick(4)                    -- out of range: ignored
  env.toggle(env.A); env.toggle(env.A); env.tick(4) -- flipped twice inside one tick: nothing to see
  return env.debugLog
end, variants())

scenario("walking to another door", populate, function(env)
  env.tick(4)
  for step = 1, 18 do env.moveTo(10 + step, 10 + step, 0); env.tick(3) end   -- ends at (28,28)
  env.world.addClutter(29, 29, 0, 5)
  env.toggle(env.C); env.tick(4)                     -- C is inside the window now
  env.toggle(env.C); env.tick(4)
  env.moveTo(10, 10, 0); env.tick(4)                 -- back: A and B again
  env.toggle(env.A); env.tick(4)
  return env.debugLog
end, variants())

scenario("door built next to the player after the cache was filled", populate, function(env)
  env.tick(4)
  env.D = env.world.addDoor(9, 10, 0, false)
  env.tick(40)
  env.toggle(env.D); env.tick(4)
  return env.debugLog
end, variants())

scenario("door removed from its square", populate, function(env)
  env.tick(4)
  env.A.idx = -1; env.toggle(env.A); env.tick(4)     -- detached: must not be observed as a transition
  return env.debugLog
end, variants())

print(table.concat(results, "\n")); print(failures == 0 and "ALL OK" or ("FAILURES: " .. failures))
