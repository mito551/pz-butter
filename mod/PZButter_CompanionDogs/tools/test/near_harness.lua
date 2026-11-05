local MODULE = "../../42/media/lua/shared/CD_PerfNear.lua"

local function loadModule(env)
  local f = assert(io.open(MODULE, "r"))
  local src = f:read("*a"); f:close()
  local fn = assert(load(src, "CD_PerfNear", "t", env))
  fn()
  return env.CompanionDogs.PerfNear
end

local results, failures = {}, 0
local function check(name, ok, detail)
  results[#results+1] = (ok and "PASS " or "FAIL ") .. name .. ((not ok and detail) and ("  " .. tostring(detail)) or "")
  if not ok then failures = failures + 1 end
end

local function makeEnv(opts)
  opts = opts or {}
  local env = {}
  setmetatable(env, { __index = _G })
  env._G = env
  env.print = function() end
  env.clock = 100000
  env.serverMode = opts.serverMode or false
  env.getTimestampMs = function() return env.clock end
  env.isServer = function() return env.serverMode end
  env.getNumActivePlayers = function() return opts.players or 1 end
  env.isClient = function() return false end
  env.instanceof = function(o, cls) return (type(o) == "table" or type(o) == "userdata") and o.__class == cls end
  local squares = {}
  env.world = { squares = squares, calls = { getGridSquare = 0, chunkMap = 0 } }
  env.world.findSquare = function(x, y, z)
    return squares[x .. "," .. y .. "," .. z]
  end
  local function square(x, y, z)
    local k = x .. "," .. y .. "," .. z
    local sq = squares[k]
    if not sq then
      sq = { x = x, y = y, z = z, moving = {}, animals = {}, objects = {}, worldObjects = {} }
      function sq:getX() return self.x end
      function sq:getY() return self.y end
      function sq:getZ() return self.z end
      function sq:getMovingObjects()
        local s = self
        return { size = function() return #s.moving end, get = function(_, i) return s.moving[i + 1] end }
      end
      function sq:getAnimals()
        local s = self
        return { size = function() return #s.animals end, get = function(_, i) return s.animals[i + 1] end }
      end
      function sq:getObjects()
        local s = self
        return { size = function() return #s.objects end, get = function(_, i) return s.objects[i + 1] end }
      end
      function sq:getWorldObjects()
        local s = self
        return { size = function() return #s.worldObjects end, get = function(_, i) return s.worldObjects[i + 1] end }
      end
      squares[k] = sq
    end
    return sq
  end
  env.world.square = square

  -- chunk map getter
  local chunkShouldError = opts.chunkShouldError or false
  env.getCell = function()
    if env.serverMode then
      return { getGridSquare = function(_, x, y, z)
        env.world.calls.getGridSquare = env.world.calls.getGridSquare + 1
        return squares[x .. "," .. y .. "," .. z]
      end }
    end
    return {
      getChunkMap = function(_, idx)
        env.world.calls.chunkMap = env.world.calls.chunkMap + 1
        if chunkShouldError then error("boom") end
        local cm = {
          getGridSquare = function(self, x, y, z)
            env.world.calls.getGridSquare = env.world.calls.getGridSquare + 1
            return squares[x .. "," .. y .. "," .. z]
          end
        }
        return cm
      end,
      getGridSquare = function(_, x, y, z)
        env.world.calls.getGridSquare = env.world.calls.getGridSquare + 1
        return squares[x .. "," .. y .. "," .. z]
      end
    }
  end
  env.CD_PerfPatchConfig = opts.cfg or {}

  local CD = {}
  env.CompanionDogs = CD
  CD.isThreatZombie = function(o)
    if not env.instanceof(o, "IsoZombie") then return false end
    if type(o.isDead) == "function" and o:isDead() then return false end
    return true
  end
  CD.DISH_TYPE = "Dish"
  env.CD = CD  -- allowed in harness only

  -- helper constructors
  env.makeZombie = function(id, x, y, z, dead)
    local o = { __class = "IsoZombie", id = id, x = x, y = y, z = z, dead = dead or false }
    function o:getX() return self.x end
    function o:getY() return self.y end
    function o:getZ() return self.z end
    function o:isDead() return self.dead end
    function o:isExistInTheWorld() return not self.removed end
    function o:getSquare() local sq = env.world.findSquare(math.floor(self.x), math.floor(self.y), math.floor(self.z)); return sq end
    return o
  end
  env.makeAnimal = function(id, x, y, z)
    local o = { __class = "IsoAnimal", id = id, x = x, y = y, z = z }
    function o:getX() return self.x end
    function o:getY() return self.y end
    function o:getZ() return self.z end
    function o:isDead() return false end
    function o:isExistInTheWorld() return not self.removed end
    function o:getSquare() local sq = env.world.findSquare(math.floor(self.x), math.floor(self.y), math.floor(self.z)); return sq end
    function o:getOnlineID() return self.id end
    return o
  end
  env.makeTrough = function(x, y, z)
    local o = { __class = "IsoFeedingTrough", x = x, y = y, z = z }
    function o:getX() return self.x end
    function o:getY() return self.y end
    function o:getZ() return self.z end
    function o:getSquare() return env.world.findSquare(math.floor(self.x), math.floor(self.y), math.floor(self.z)) end
    return o
  end
  env.makeDish = function(x, y, z)
    local o = { __class = "IsoWorldInventoryObject", x = x, y = y, z = z }
    function o:getX() return self.x end
    function o:getY() return self.y end
    function o:getZ() return self.z end
    function o:getSquare() return env.world.findSquare(math.floor(self.x), math.floor(self.y), math.floor(self.z)) end
    function o:getItem() return { getFullType = function() return "Dish" end } end
    return o
  end

  if opts.preload then opts.preload(env) end
  local M = loadModule(env)
  env.M = M
  env.CD = CD
  return env
end

-- helper reference set comparison
local function ids(list)
  local t = {}
  for i = 1, #list do t[i] = (list[i].id or i) end
  return t
end

-- Tests

local function test_zombies_equivalent_to_zsnap_window()
  for trial = 1, 20 do
    local env = makeEnv({ serverMode = true })
    local M = env.M
    -- place zombies
    for i = 1, 15 do
      local z = env.makeZombie(i, (i % 7), (i % 5), 0, (i % 4 == 0))
      local sq = env.world.square(math.floor(z.x), math.floor(z.y), 0)
      sq.moving[#sq.moving + 1] = z
    end
    local cx = trial % 4
    local cy = (trial * 2) % 4
    local R = (trial % 4)
    local animal = env.makeAnimal(1, 0, 0, 0)
    -- reference
    local list = {}
    for dx = -R, R do
      for dy = -R, R do
        local sq = env.world.squares[(cx + dx) .. "," .. (cy + dy) .. ",0"]
        if sq then
          local mo = sq:getMovingObjects()
          for i = 0, mo:size() - 1 do
            local o = mo:get(i)
            if env.CD.isThreatZombie(o) then list[#list + 1] = o end
          end
        end
      end
    end
    local got = M.zombies(animal, cx, cy, 0, R)
    local same = #got == #list
    for i = 1, #list do if got[i] ~= list[i] then same = false end end
    check("test_zombies_equivalent_to_zsnap_window", same, "trial " .. trial .. " got " .. #got .. " exp " .. #list .. " (or order differs)")
  end
end

local function test_animals_and_feed_square_order()
  local env = makeEnv({ serverMode = true })
  local M = env.M
  -- place one animal, trough, dish each on distinct square positions along order
  local positions = { {1,1}, {1,2}, {2,1} }
  for i, p in ipairs(positions) do
    local a = env.makeAnimal(i, p[1], p[2], 0)
    env.world.square(p[1], p[2], 0).animals[#env.world.square(p[1], p[2], 0).animals + 1] = a
    local t = env.makeTrough(p[1] + 0, p[2] + 0, 0)
    -- offset trough to same square? place in same
    env.world.square(p[1], p[2], 0).objects[#env.world.square(p[1], p[2], 0).objects + 1] = t
    local d = env.makeDish(p[1], p[2], 0)
    env.world.square(p[1], p[2], 0).worldObjects[#env.world.square(p[1], p[2], 0).worldObjects + 1] = d
  end
  local animal = env.makeAnimal(99, 1, 1, 0)
  -- reference gather
  local refA, refF = {}, {}
  for dx = -2, 2 do
    for dy = -2, 2 do
      local sq = env.world.squares[(1+dx) .. "," .. (1+dy) .. ",0"]
      if sq then
        local an = sq:getAnimals()
        for i = 0, an:size()-1 do refA[#refA+1] = an:get(i) end
        local ob = sq:getObjects()
        for i = 0, ob:size()-1 do local o = ob:get(i); if env.instanceof(o, "IsoFeedingTrough") then refF[#refF+1]=o end end
        local wo = sq:getWorldObjects()
        for i = 0, wo:size()-1 do local w = wo:get(i); if w:getItem():getFullType()=="Dish" then refF[#refF+1]=w end end
      end
    end
  end
  local gotA = M.animals(animal, 2)
  local gotF = M.feed(animal, 2)
  local same = #gotA == #refA and #gotF == #refF and #refF == 6
  for i = 1, #refA do if gotA[i] ~= refA[i] then same = false end end
  for i = 1, #refF do if gotF[i] ~= refF[i] then same = false end end
  check("test_animals_and_feed_square_order", same, "animals " .. #gotA .. "/" .. #refA .. " feed " .. #gotF .. "/" .. #refF .. " (or order differs)")
end

local function test_perception_used_and_safety_net()
  local env = makeEnv({ serverMode = false })
  local M = env.M
  -- place a zombie
  local z = env.makeZombie(1, 2, 2, 0, false)
  env.world.square(2, 2, 0).moving[1] = z
  local animal = env.makeAnimal(1, 0, 0, 0)
  -- preliminary sync scan to set safetyLast
  env.clock = 1000
  M.zombies(animal, 0, 0, 0, 1)
  -- now perception record
  env._G.cdSpottedZombies = function(a) return { z, ageMs = 100 } end
  env._G.cdWatch = function(a, on) end
  local before = env.world.calls.getGridSquare
  env.clock = 1000 + 50
  local got = M.zombies(animal, 2, 2, 0, 1)
  local after = env.world.calls.getGridSquare
  check("test_perception_used_and_safety_net", (after - before) == 0 and #got >= 1, "sqcalls=" .. (after-before) .. " got=" .. #got)
  -- beyond safety net and snapshot max age -> sync scan
  env.clock = env.clock + 3100
  local before2 = env.world.calls.getGridSquare
  local got2 = M.zombies(animal, 0, 0, 0, 1)
  local after2 = env.world.calls.getGridSquare
  check("test_perception_used_and_safety_net", (after2 - before2) > 0, "no scan after safety net")
end

local function test_perception_stale_falls_back()
  local env = makeEnv({ serverMode = false, cfg = { nearSlice = false } })
  local M = env.M
  local z = env.makeZombie(1, 1, 1, 0, false)
  env.world.square(1, 1, 0).moving[1] = z
  local animal = env.makeAnimal(1, 0, 0, 0)
  env.clock = 1000
  M.zombies(animal, 0, 0, 0, 2)
  env._G.cdSpottedZombies = function(a) return { z, ageMs = 400 } end
  env._G.cdWatch = function(a, on) end
  env.clock = 1000 + 50
  local before = env.world.calls.getGridSquare
  local got = M.zombies(animal, 0, 0, 0, 2)
  local after = env.world.calls.getGridSquare
  check("test_perception_stale_falls_back", (after - before) > 0, "sqcalls=" .. (after-before))
end

local function test_slice_budget()
  local env = makeEnv({ serverMode = true })
  local M = env.M
  -- build an old snapshot (age > threshold but <= 3000) to trigger rebuild
  local animal = env.makeAnimal(1, 0, 0, 0)
  -- place a zombie
  local z = env.makeZombie(1, 1, 1, 0, false)
  env.world.square(1, 1, 0).moving[1] = z
  env.clock = 1000
  M.zombies(animal, 0, 0, 0, 1) -- sync snapshot age 0
  -- age snapshot beyond threshold
  env.clock = 1000 + 600 -- zombie threshold 500, <=3000
  -- query triggers startBuild (older served + rebuild)
  local before = env.world.calls.getGridSquare
  M.zombies(animal, 0, 0, 0, 1)
  -- now run step a few times; ensure any single step <=400
  for _ = 1, 3 do
    env.clock = env.clock + 16
    local b = env.world.calls.getGridSquare
    env.M.step()
    local a = env.world.calls.getGridSquare
    check("test_slice_budget", (a - b) <= 400, "step took " .. (a - b))
  end
end

local function test_first_query_synchronous()
  local env = makeEnv({ serverMode = true })
  local M = env.M
  local animal = env.makeAnimal(1, 0, 0, 0)
  local z = env.makeZombie(1, 1, 1, 0, false)
  env.world.square(1, 1, 0).moving[1] = z
  local before = env.world.calls.getGridSquare
  local got = M.zombies(animal, 0, 0, 0, 1)
  local after = env.world.calls.getGridSquare
  check("test_first_query_synchronous", (after - before) > 0 and #got >= 1, "sqcalls=" .. (after-before) .. " got=" .. #got)
end

local function test_stale_objects_filtered()
  local env = makeEnv({ serverMode = true })
  local M = env.M
  local animal = env.makeAnimal(1, 0, 0, 0)
  local z = env.makeZombie(1, 1, 1, 0, false)
  env.world.square(1, 1, 0).moving[1] = z
  local a = env.makeAnimal(2, 1, 1, 0)
  env.world.square(1, 1, 0).animals[1] = a
  env.clock = 1000
  M.zombies(animal, 0, 0, 0, 2)
  M.animals(animal, 2)
  -- make stale
  z.dead = true
  a.removed = true            -- removal leaves the square field set; isExistInTheWorld says so
  env.clock = 1000 + 10
  local gotZ = M.zombies(animal, 0, 0, 0, 2)
  local gotA = M.animals(animal, 2)
  check("test_stale_objects_filtered", #gotZ == 0 and #gotA == 0, "z=" .. #gotZ .. " a=" .. #gotA)
end

local function test_server_uses_cell()
  local env = makeEnv({ serverMode = true })
  local M = env.M
  -- chunkMap should never be called
  local animal = env.makeAnimal(1, 0, 0, 0)
  local z = env.makeZombie(1, 1, 1, 0, false)
  env.world.square(1, 1, 0).moving[1] = z
  env.clock = 1000
  M.zombies(animal, 0, 0, 0, 1)
  check("test_server_uses_cell", env.world.calls.chunkMap == 0, "chunkMap calls=" .. env.world.calls.chunkMap)
end

local function test_split_player_absent_chunkmap_fallback()
  -- chunk map getGridSquare errors: use chunkShouldError to force error at getChunkMap call?
  -- We set chunkShouldError true so getChunkMap throws, fallback to cell getGridSquare.
  local env = makeEnv({ serverMode = false, chunkShouldError = true })
  local M = env.M
  local animal = env.makeAnimal(1, 0, 0, 0)
  local z = env.makeZombie(1, 1, 1, 0, false)
  env.world.square(1, 1, 0).moving[1] = z
  local callsBefore = env.world.calls.getGridSquare
  env.clock = 1000
  local got = M.zombies(animal, 0, 0, 0, 1)
  check("test_split_player_absent_chunkmap_fallback", env.world.calls.getGridSquare > callsBefore and env.world.calls.chunkMap == 1, "gs=" .. env.world.calls.getGridSquare .. " chunkMap=" .. env.world.calls.chunkMap)
end

local function test_wide_radius_uncapped()
  local env = makeEnv({ serverMode = true })
  local animal = env.makeAnimal(1, 0, 0, 0)
  local far = env.makeAnimal(2, 30, 0, 0)
  env.world.square(30, 0, 0).animals[1] = far
  env.clock = 1000
  local got = env.M.animals(animal, 32)
  check("test_wide_radius_uncapped", #got == 1 and got[1] == far, "got=" .. #got)
end

local function test_only_recent_categories_walked()
  local env = makeEnv({ serverMode = true })
  local animal = env.makeAnimal(1, 0, 0, 0)
  local animalsCalls = 0
  local sq = env.world.square(1, 1, 0)
  local orig = sq.getAnimals
  sq.getAnimals = function(self) animalsCalls = animalsCalls + 1 return orig(self) end
  env.clock = 1000
  env.M.zombies(animal, 0, 0, 0, 2)
  check("test_only_recent_categories_walked", animalsCalls == 0, "animal lists read=" .. animalsCalls)
  env.clock = 1100
  env.M.animals(animal, 2)
  env.clock = 1200
  local before = animalsCalls
  env.clock = 1700   -- zombies stale: the rebuild now fuses animals too
  env.M.zombies(animal, 0, 0, 0, 2)
  for _ = 1, 10 do env.M.step() end
  check("test_only_recent_categories_walked", animalsCalls > before, "fused rebuild skipped animals")
end

local function test_moving_dog_stays_covered()
  local env = makeEnv({ serverMode = true })
  local animal = env.makeAnimal(1, 0, 0, 0)
  env.clock = 1000
  env.M.zombies(animal, 0, 0, 0, 4)
  for _ = 1, 3 do env.M.step() end     -- the margin snapshot is walked over the next ticks
  local before = env.world.calls.getGridSquare
  env.clock = 1100
  env.M.zombies(animal, 2, 1, 0, 4)    -- moved within the 2-tile margin
  check("test_moving_dog_stays_covered", env.world.calls.getGridSquare == before, "rescanned " .. (env.world.calls.getGridSquare - before))
end

local function test_cdNear_path_used()
  local env = makeEnv({ serverMode = false })
  local z = env.makeZombie(1, 2, 2, 0, false)
  local far = env.makeZombie(2, 9, 9, 0, false)
  local a = env.makeAnimal(5, 1, 1, 0)
  local t = env.makeTrough(1, 2, 0)
  env.world.square(2, 2, 0); env.world.square(9, 9, 0); env.world.square(1, 1, 0); env.world.square(1, 2, 0)
  local calls = {}
  env._G.cdNear = function(x, y, zz, r, wz, wa, wt, dish)
    calls[#calls + 1] = { wz, wa, wt, dish }
    return { zombies = { z, far }, animals = { a }, feed = { t } }
  end
  local dog = env.makeAnimal(99, 0, 0, 0)
  local before = env.world.calls.getGridSquare
  local gz = env.M.zombies(dog, 0, 0, 0, 3)
  local ga = env.M.animals(dog, 3)
  local gf = env.M.feed(dog, 3)
  check("test_cdNear_path_used", env.world.calls.getGridSquare == before and #gz == 1 and gz[1] == z
    and #ga == 1 and #gf == 1 and calls[1][1] == true and calls[2][2] == true and calls[3][3] == true
    and calls[3][4] == "Dish", "sq calls=" .. (env.world.calls.getGridSquare - before) .. " z=" .. #gz)
end

local function test_perception_truncated_falls_back()
  local env = makeEnv({ serverMode = false, cfg = { nearSlice = false } })
  local dog = env.makeAnimal(1, 0, 0, 0)
  env.clock = 1000
  env.M.zombies(dog, 0, 0, 0, 1)            -- real scan: arms the safety net
  local rec = { ageMs = 10 }
  for i = 1, 128 do rec[i] = env.makeZombie(100 + i, 50, 50, 0, false) end
  env._G.cdSpottedZombies = function() return rec end
  env._G.cdWatch = function() end
  local before = env.world.calls.getGridSquare
  env.clock = 1100
  env.M.zombies(dog, 0, 0, 0, 1)
  check("test_perception_truncated_falls_back", env.world.calls.getGridSquare > before, "a full (possibly truncated) record was trusted")
end

local function test_removed_not_dead_filtered()
  local env = makeEnv({ serverMode = true })
  local dog = env.makeAnimal(1, 0, 0, 0)
  local z = env.makeZombie(1, 1, 1, 0, false)
  env.world.square(1, 1, 0).moving[1] = z
  local a = env.makeAnimal(2, 1, 1, 0)
  env.world.square(1, 1, 0).animals[1] = a
  env.clock = 1000
  env.M.zombies(dog, 0, 0, 0, 2); env.M.animals(dog, 2)
  z.removed, a.removed = true, true          -- the engine keeps their square field set
  env.clock = 1050
  local gz, ga = env.M.zombies(dog, 0, 0, 0, 2), env.M.animals(dog, 2)
  check("test_removed_not_dead_filtered", #gz == 0 and #ga == 0, "z=" .. #gz .. " a=" .. #ga)
end

local function test_forget_idle_dogs()
  local env = makeEnv({ serverMode = true })
  local unwatched = {}
  env._G.cdWatch = function(d, on) if on == false then unwatched[#unwatched + 1] = d end end
  local dog = env.makeAnimal(1, 0, 0, 0)
  env.clock = 1000
  env.M.animals(dog, 2)
  env.clock = 1000 + 11000
  for _ = 1, 600 do env.M.step() end
  check("test_forget_idle_dogs", unwatched[1] == dog, "dog not forgotten")
end

local function test_split_screen_uses_cell()
  local env = makeEnv({ serverMode = false, players = 2 })
  local dog = env.makeAnimal(1, 0, 0, 0)
  env.clock = 1000
  env.M.zombies(dog, 0, 0, 0, 2)
  check("test_split_screen_uses_cell", env.world.calls.chunkMap == 0, "chunk map used with 2 players")
end

test_zombies_equivalent_to_zsnap_window()
test_animals_and_feed_square_order()
test_perception_used_and_safety_net()
test_perception_stale_falls_back()
test_slice_budget()
test_first_query_synchronous()
test_stale_objects_filtered()
test_server_uses_cell()
test_split_player_absent_chunkmap_fallback()
test_wide_radius_uncapped()
test_only_recent_categories_walked()
test_moving_dog_stays_covered()
test_cdNear_path_used()
test_perception_truncated_falls_back()
test_removed_not_dead_filtered()
test_forget_idle_dogs()
test_split_screen_uses_cell()

print(table.concat(results, "\n"))
print(failures == 0 and "ALL OK" or ("FAILURES: " .. failures))
os.exit(failures == 0 and 0 or 1)