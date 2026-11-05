-- Task 2: the WKT_Perf ground-key override next to the original, on the same stub world.
-- Run from WKT_Perf/: lua tools/test/ground_harness.lua
local ORIG = "/home/mitori/Sonic/SteamLibrary/steamapps/workshop/content/108600/3784348098/mods/WhoseKeyIsThis/42/media/lua/client/WKT_GroundKeys.lua"
local OVR = "42/media/lua/client/WKT_GroundKeys.lua"

local results, failures = {}, 0
local function check(name, ok, detail)
  results[#results + 1] = (ok and "PASS " or "FAIL ") .. name .. ((not ok and detail) and ("  " .. tostring(detail)) or "")
  if not ok then failures = failures + 1 end
end

local function list(t) return { size = function() return #t end, get = function(_, i) return t[i + 1] end } end

-- world: squares carry world objects and a visibility flag; walls hide squares
local function makeWorld()
  local W = { squares = {}, clock = 100000, calls = 0, chunkCalls = {}, hidden = {} }
  function W.sq(x, y, z)
    local k = x .. "," .. y .. "," .. z
    local s = W.squares[k]
    if not s then
      s = { x = x, y = y, z = z, world = {} }
      function s:getX() return self.x end
      function s:getY() return self.y end
      function s:getZ() return self.z end
      function s:isCouldSee(pn) W.calls = W.calls + 1; return not W.hidden[self.x .. "," .. self.y] end
      function s:getWorldObjects() return list(self.world) end
      W.squares[k] = s
    end
    return s
  end
  function W.get(x, y, z) W.calls = W.calls + 1; return W.squares[x .. "," .. y .. "," .. z] end
  local nextId = 0
  function W.key(x, y, z)
    nextId = nextId + 1
    local item = { id = nextId, keyId = 100 + nextId }
    function item:getName() return "Key" .. self.id end
    local s = W.sq(x, y, z)
    local wo = { item = item, sq = s }
    function wo:getItem() return self.item end
    function wo:getSquare() return self.sq end
    function wo:getX() return self.sq.x + 0.5 end
    function wo:getY() return self.sq.y + 0.5 end
    function wo:getZ() return self.sq.z end
    table.insert(s.world, wo)
    return wo
  end
  function W.junk(x, y, z)
    local item = { junk = true }
    local s = W.sq(x, y, z)
    local wo = { item = item, sq = s }
    function wo:getItem() return self.item end
    function wo:getSquare() return self.sq end
    table.insert(s.world, wo)
  end
  function W.remove(wo)
    local s = wo.sq
    for i = #s.world, 1, -1 do if s.world[i] == wo then table.remove(s.world, i) end end
    wo.sq = nil
  end
  -- a ground plane: squares exist for a 90 x 90 area around the origin
  for x = -45, 45 do for y = -45, 45 do W.sq(x, y, 0) end end
  return W
end

-- one load (original or override) on world W. Screen: 1600 x 900 per player, iso projection
-- centred on the player, 64 x 32 px tiles.
local function makeEnv(W, path, opts)
  opts = opts or {}
  local env = setmetatable({}, { __index = _G })
  env._G = env
  env.print = function() end
  env.getTimestampMs = function() return W.clock end
  env.getModFileReader = function() return nil end
  env.require = function() end
  local player = { x = 0.5, y = 0.5, z = 0, dead = false }
  function player:getX() return self.x end
  function player:getY() return self.y end
  function player:getZ() return self.z end
  function player:isDead() return self.dead end
  env.player = player
  local cell = { getGridSquare = function(_, x, y, z) return W.get(x, y, z) end }
  if not opts.noChunkMap then
    cell.getChunkMap = function(_, pn)
      return { getGridSquare = function(_, x, y, z) W.chunkCalls[pn] = (W.chunkCalls[pn] or 0) + 1; return W.get(x, y, z) end }
    end
  end
  env.getCell = function() return cell end
  env.getCore = function() return { getZoom = function() return opts.zoom or 1 end } end
  local zoom = opts.zoom or 1
  env.isoToScreenX = function(pn, x, y, z) return 800 + ((x - player.x) - (y - player.y)) * 32 / zoom end
  env.isoToScreenY = function(pn, x, y, z) return 450 + ((x - player.x) + (y - player.y)) * 16 / zoom end
  env.getPlayerScreenLeft = function() return 0 end
  env.getPlayerScreenTop = function() return 0 end
  env.getPlayerScreenWidth = function() return 1600 end
  env.getPlayerScreenHeight = function() return 900 end
  env.WKT_PerfPatchConfig = opts.cfg or {}
  if opts.jar then
    env.wktDrainGroundKeys = function() local d = W.drainQueue; W.drainQueue = nil; return d end
    env.wktGroundKeysNear = function(x, y, z, r, pn)
      W.nearCalls = (W.nearCalls or 0) + 1
      local out = {}
      for _, s in pairs(W.squares) do
        local dx, dy = s.x + 0.5 - x, s.y + 0.5 - y
        if s.z == math.floor(z) and dx * dx + dy * dy <= r * r and not W.hidden[s.x .. "," .. s.y] then
          for _, wo in ipairs(s.world) do if wo.item.keyId then out[#out + 1] = wo end end
        end
      end
      return out
    end
  end
  local found = {}
  env.WKT = {
    Ground = {}, SHOW_GROUND = "g",
    showing = function() return true end,
    mopt = function(_, d) return opts.radius or d end,
    isKeyItem = function(item) return item ~= nil and item.keyId ~= nil end,
    getKeyId = function(item) return item.keyId end,
    isFound = function(item) return found[item] == true end,
    kindColor = function() return 1, 1, 1 end,
  }
  env.found = found
  local f = assert(io.open(path)); local src = f:read("*a"); f:close()
  assert(load(src, path, "t", env))()
  env.G = env.WKT.Ground
  return env
end

local function frames(env, W, n, pn)
  for _ = 1, n do
    env.G.advance(pn or 0, env.player)
    W.clock = W.clock + 16
  end
end

local function labelSet(env, pn)
  local names = {}
  for _, e in ipairs(env.G.get(pn or 0)) do names[#names + 1] = e.name end
  table.sort(names)
  return table.concat(names, ",")
end

-- keys spread on and off screen; the screen spans about +-12 tiles along the iso diagonals
local function seedKeys(W)
  local onScreen = {}
  for _, p in ipairs({ { 2, 1 }, { -3, 4 }, { 5, -2 }, { 0, -6 }, { 7, 7 }, { -8, -1 } }) do
    onScreen[#onScreen + 1] = W.key(p[1], p[2], 0)
  end
  local offScreen = W.key(20, -20, 0)       -- inside radius 30, far off the screen's corner
  W.junk(1, 1, 0); W.junk(-4, 2, 0)
  return onScreen, offScreen
end

local function test_equivalent_stationary()
  local W = makeWorld()
  seedKeys(W)
  local o, v = makeEnv(W, ORIG), makeEnv(W, OVR, { cfg = { groundClip = false } })
  frames(o, W, 150); frames(v, W, 150)
  check("test_equivalent_stationary", labelSet(o) == labelSet(v), labelSet(o) .. " vs " .. labelSet(v))
end

local function test_equivalent_walking()
  local W = makeWorld()
  seedKeys(W)
  local o, v = makeEnv(W, ORIG), makeEnv(W, OVR, { cfg = { groundClip = false } })
  local bad
  for step = 1, 20 do
    o.player.x, o.player.y = 0.5 + step, 0.5
    v.player.x, v.player.y = 0.5 + step, 0.5
    frames(o, W, 40); frames(v, W, 40)
    if labelSet(o) ~= labelSet(v) then bad = "step " .. step .. ": " .. labelSet(o) .. " vs " .. labelSet(v) break end
  end
  check("test_equivalent_walking", bad == nil, bad)
end

local function test_new_key_on_empty_square_within_2s()
  local W = makeWorld()
  local v = makeEnv(W, OVR)
  frames(v, W, 30)
  W.key(3, 3, 0)
  local seenAt
  for i = 1, 200 do
    frames(v, W, 1)
    if labelSet(v):find("Key") then seenAt = i * 16 break end
  end
  check("test_new_key_on_empty_square_within_2s", seenAt ~= nil and seenAt <= 2000 + 8 * 16, "seen after " .. tostring(seenAt) .. " ms")
end

local function test_new_key_on_item_square_within_recheck()
  local W = makeWorld()
  W.junk(2, 2, 0)
  local v = makeEnv(W, OVR)
  frames(v, W, 30)
  W.key(2, 2, 0)
  local seenAt
  for i = 1, 100 do
    frames(v, W, 1)
    if labelSet(v):find("Key") then seenAt = i * 16 break end
  end
  check("test_new_key_on_item_square_within_recheck", seenAt ~= nil and seenAt <= 500 + 16, "seen after " .. tostring(seenAt) .. " ms")
end

local function test_key_behind_wall_hidden()
  local W = makeWorld()
  W.key(4, 0, 0)
  W.hidden["4,0"] = true
  local v = makeEnv(W, OVR)
  frames(v, W, 200)
  check("test_key_behind_wall_hidden", labelSet(v) == "", labelSet(v))
end

local function test_offscreen_key_not_listed_onscreen_listed()
  local W = makeWorld()
  local on, off = seedKeys(W)
  local v = makeEnv(W, OVR)
  frames(v, W, 200)
  local s = labelSet(v)
  check("test_offscreen_key_not_listed_onscreen_listed",
    not s:find(off.item:getName(), 1, true) and s:find(on[1].item:getName(), 1, true) ~= nil, s)
end

local function test_split_screen_player1()
  local W = makeWorld()
  W.key(2, 1, 0)
  local v = makeEnv(W, OVR)
  frames(v, W, 40, 1)
  check("test_split_screen_player1", (W.chunkCalls[1] or 0) > 0 and (W.chunkCalls[0] or 0) == 0 and labelSet(v, 1) ~= "",
    "chunk map 1 calls=" .. tostring(W.chunkCalls[1]) .. " map 0 calls=" .. tostring(W.chunkCalls[0]))
end

local function test_jar_path_drain_and_safety_net()
  local W = makeWorld()
  local v = makeEnv(W, OVR, { jar = true })
  frames(v, W, 5)
  local k = W.key(1, 2, 0)
  W.drainQueue = { k }
  frames(v, W, 1)
  check("test_jar_path_drain_and_safety_net", labelSet(v) ~= "", "drained key not shown the same frame")
  W.remove(k)
  frames(v, W, 2000 / 16 + 2)
  check("test_jar_path_drain_and_safety_net", labelSet(v) == "", "picked-up key still listed: " .. labelSet(v))
  check("test_jar_path_drain_and_safety_net", W.calls < 2000, "jar path read squares from Lua: " .. W.calls)
end

local function test_stale_worldobject_filtered()
  local W = makeWorld()
  local v = makeEnv(W, OVR, { jar = true })
  frames(v, W, 5)
  local k = W.key(1, 2, 0)
  W.remove(k)                 -- picked up between the render and the drain
  W.drainQueue = { k }
  local ok, err = pcall(frames, v, W, 1)
  check("test_stale_worldobject_filtered", ok and labelSet(v) == "", tostring(err) .. " " .. labelSet(v))
end

local function test_engine_calls_reduced()
  local W = makeWorld()
  seedKeys(W)
  local o, v = makeEnv(W, ORIG), makeEnv(W, OVR)
  W.calls = 0; frames(o, W, 625)        -- 10 s
  local orig = W.calls
  W.calls = 0; frames(v, W, 625)
  local ovr = W.calls
  check("test_engine_calls_reduced", ovr <= orig * 0.25, "orig=" .. orig .. " ovr=" .. ovr)
end

local function test_drained_while_off()
  local W = makeWorld()
  local v = makeEnv(W, OVR, { jar = true })
  v.WKT.showing = function() return false end
  W.drainQueue = { W.key(1, 1, 0) }
  frames(v, W, 1)
  check("test_drained_while_off", W.drainQueue == nil, "buffer not drained while labels are off")
end

test_equivalent_stationary()
test_equivalent_walking()
test_new_key_on_empty_square_within_2s()
test_new_key_on_item_square_within_recheck()
test_key_behind_wall_hidden()
test_offscreen_key_not_listed_onscreen_listed()
test_split_screen_player1()
test_jar_path_drain_and_safety_net()
test_stale_worldobject_filtered()
test_engine_calls_reduced()
test_drained_while_off()

print(table.concat(results, "\n"))
print(failures == 0 and "ALL OK" or ("FAILURES: " .. failures))
os.exit(failures == 0 and 0 or 1)
