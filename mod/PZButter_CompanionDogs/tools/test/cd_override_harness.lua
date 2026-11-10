-- Task 6: each CD_Perf override is loaded next to its original on the same stub world and
-- must give the same answers. Run from CD_Perf/: lua tools/test/cd_override_harness.lua
local ORIG = "/home/mitori/Sonic/SteamLibrary/steamapps/workshop/content/108600/3740052292/mods/CompanionDogs/42/media/lua/"
local OVR = "42/media/lua/"
local NEAR = OVR .. "shared/CD_PerfNear.lua"

local results, failures = {}, 0
local function check(name, ok, detail)
  results[#results + 1] = (ok and "PASS " or "FAIL ") .. name .. ((not ok and detail) and ("  " .. tostring(detail)) or "")
  if not ok then failures = failures + 1 end
end

local function list(t) return { size = function() return #t end, get = function(_, i) return t[i + 1] end } end

-- a world shared by both loads: squares with moving objects, animals, objects, world objects
local function makeWorld()
  local W = { squares = {}, clock = 100000 }
  function W.sq(x, y, z)
    local k = x .. "," .. y .. "," .. z
    local s = W.squares[k]
    if not s then
      s = { x = x, y = y, z = z, moving = {}, animals = {}, objects = {}, world = {} }
      function s:getX() return self.x end
      function s:getY() return self.y end
      function s:getZ() return self.z end
      function s:getMovingObjects() return list(self.moving) end
      function s:getAnimals() return list(self.animals) end
      function s:getObjects() return list(self.objects) end
      function s:getWorldObjects() return list(self.world) end
      W.squares[k] = s
    end
    return s
  end
  local function base(o, x, y, z)
    o.x, o.y, o.z = x, y, z
    function o:getX() return self.x end
    function o:getY() return self.y end
    function o:getZ() return self.z end
    function o:getSquare() return W.squares[math.floor(self.x) .. "," .. math.floor(self.y) .. "," .. math.floor(self.z)] end
    return o
  end
  function W.zombie(id, x, y, z, dead)
    local o = base({ __class = "IsoZombie", id = id, dead = dead }, x, y, z)
    function o:isDead() return self.dead end
    function o:isExistInTheWorld() return true end
    function o:isReanimatedForGrappleOnly() return false end
    function o:isFakeDead() return false end
    function o:getVariableBoolean() return false end
    table.insert(W.sq(math.floor(x), math.floor(y), z).moving, o)
    return o
  end
  function W.animal(id, x, y, z, atype, wild)
    local o = base({ __class = "IsoAnimal", id = id, atype = atype, wild = wild }, x, y, z)
    function o:isDead() return false end
    function o:isExistInTheWorld() return true end
    function o:isWild() return self.wild end
    function o:getAnimalType() return self.atype end
    function o:getOnlineID() return self.id end
    table.insert(W.sq(math.floor(x), math.floor(y), z).animals, o)
    return o
  end
  function W.trough(x, y, z, water)
    local o = base({ __class = "IsoFeedingTrough", water = water }, x, y, z)
    function o:getWater() return self.water end
    function o:getContainer() return nil end
    table.insert(W.sq(x, y, z).objects, o)
    return o
  end
  function W.dish(x, y, z, water)
    local item = { md = { cdWater = water } }
    function item:getFullType() return "Base.CompanionDogsBowl" end
    function item:getModData() return self.md end
    local o = base({ __class = "IsoWorldInventoryObject" }, x + 0.5, y + 0.5, z)
    function o:getItem() return item end
    table.insert(W.sq(x, y, z).world, o)
    return o
  end
  return W
end

-- one load of a file (original or override) with CD_PerfNear, on world W
local function makeEnv(W, opts)
  opts = opts or {}
  local env = setmetatable({}, { __index = _G })
  env._G = env
  env.print = function() end
  env.isServer = function() return true end
  env.isClient = function() return false end
  env.getNumActivePlayers = function() return 1 end
  env.getTimestampMs = function() return W.clock end
  env.instanceof = function(o, cls) return type(o) == "table" and o.__class == cls end
  env.getCell = function() return { getGridSquare = function(_, x, y, z) return W.squares[x .. "," .. y .. "," .. z] end } end
  env.getModInfoByID = function() return nil end
  env.getGameTime = function() return { getWorldAgeHours = function() return 1 end } end
  env.CD_PerfPatchConfig = opts.cfg or {}
  env.Events = setmetatable({}, { __index = function(t, k)
    local e = { Add = function() end, Remove = function() end }; rawset(t, k, e); return e end })
  local noop = function() end
  local CD = setmetatable({}, { __index = function() return noop end })
  env.CompanionDogs = CD
  CD.zbox, CD.zseq, CD.BOOT_TICKS, CD._regCounter = {}, 1, 0, 0
  CD.DISH_TYPE = "Base.CompanionDogsBowl"
  CD.isDishType = function(t) return t == CD.DISH_TYPE end
  CD.dist2D = function(a, b) local dx, dy = a:getX() - b:getX(), a:getY() - b:getY(); return math.sqrt(dx * dx + dy * dy) end
  CD.breedCanHuntClass = function() return true end
  CD.getBreed = function() return "collie" end
  CD.isFriendlyNPC = function() return false end
  CD.dogReg = { dogs = opts.dogs or {} }
  CD.registerDistraction = function(name, h) env.distractions = env.distractions or {}; env.distractions[name] = h end
  CD.evAdd = function(ev, fn, key) if key == "srv.tick" then env.companionTick = fn end end
  env.pzCalls = {}
  CD.pz = function(key, fn, ...) env.pzCalls[#env.pzCalls + 1] = key end
  for _, k in ipairs({ "DOG_REG_TICKS", "FOLLOW_TICK_INTERVAL", "COMPANION_TICK_INTERVAL" }) do CD[k] = 1 end
  local function loadFile(path)
    local f = assert(io.open(path, "r")); local src = f:read("*a"); f:close()
    assert(load(src, path, "t", env))()
  end
  env.loadFile = loadFile
  loadFile(NEAR)
  return env
end

local function ids(t)
  local out = {}
  for i = 1, #t do out[i] = tostring(t[i].id or t[i]) end
  return table.concat(out, ",")
end

---------------------------------------------------------------------------

local function test_zsnap_equivalent()
  local W = makeWorld()
  math.randomseed(7)
  for i = 1, 60 do W.zombie(i, math.random(-12, 12) + 0.3, math.random(-12, 12) + 0.7, 0, i % 9 == 0) end
  local o, v = makeEnv(W), makeEnv(W)
  o.loadFile(ORIG .. "server/CompanionDogs/combat/Threat.lua")
  v.loadFile(OVR .. "server/CompanionDogs/combat/Threat.lua")
  local bad = nil
  for n = 1, 20 do
    local cx, cy, R = math.random(-6, 6), math.random(-6, 6), math.random(1, 10)
    o.CompanionDogs.zseq, v.CompanionDogs.zseq = n + 1, n + 1
    local dog = { getOnlineID = function() return 1 end }
    local a = ids(o.CompanionDogs.zsnap(dog, cx, cy, 0, R))
    W.clock = W.clock + 1000      -- every query sees a fresh scan
    local b = ids(v.CompanionDogs.zsnap(dog, cx, cy, 0, R))
    if a ~= b then bad = cx .. "," .. cy .. " R" .. R .. " orig=" .. a .. " ovr=" .. b break end
  end
  check("test_zsnap_equivalent", bad == nil, bad)
end

local function test_scanWildAnimals_equivalent_including_ties()
  local W = makeWorld()
  W.animal(1, 3.5, 0.5, 0, "rabbit", true)   -- distance 3
  W.animal(2, -2.5, 0.5, 0, "rat", true)     -- distance 3 too: the tie goes to the last visited
  W.animal(3, 1.5, 1.5, 0, "cow", false)     -- not wild
  W.animal(4, 6.5, 6.5, 0, "mouse", true)    -- outside radius 6
  W.animal(5, 0.5, 5.5, 1, "rat", true)      -- other floor
  local dog = W.animal(99, 0.5, 0.5, 0, "dog", false)
  local o, v = makeEnv(W), makeEnv(W)
  o.loadFile(ORIG .. "server/systems/CompanionDogs_Hunting.lua")
  v.loadFile(OVR .. "server/systems/CompanionDogs_Hunting.lua")
  local cfg = { radius = 6, classes = { tiny = true, small = true } }
  local a = o.distractions.prey.find(dog, cfg)
  local b = v.distractions.prey.find(dog, cfg)
  check("test_scanWildAnimals_equivalent_including_ties", a == b and a ~= nil and a.id == 1, "orig=" .. tostring(a and a.id) .. " ovr=" .. tostring(b and b.id))
end

local function feedPair(W, opts)
  local o, v = makeEnv(W, opts), makeEnv(W, opts)
  o.loadFile(ORIG .. "server/systems/CompanionDogs_Feed.lua")
  v.loadFile(OVR .. "server/systems/CompanionDogs_Feed.lua")
  return o.CompanionDogs.findTroughNear, v.CompanionDogs.findTroughNear
end

local function test_findTroughNear_equivalent_no_zone()
  local W = makeWorld()
  -- dog at (0.5, 0.5): a trough at x=3.5 and a dish at y=3.5 are both exactly 3 away; the
  -- dish's square (0,3) is visited before the trough's (3,0), and strict "<" keeps the first
  local tie = W.trough(3, 0, 0, 5); tie.x, tie.y = 3.5, 0.5
  W.trough(-5, 0, 0, 5)       -- farther
  W.trough(1, 1, 0, 0)        -- empty
  W.dish(0, 3, 0, 2)
  local dog = W.animal(99, 0.5, 0.5, 0, "dog", false)
  function dog:getConnectedDZone() return list({}) end
  local fo, fv = feedPair(W)
  local a = { fo(dog, 16, false, true) }
  local b = { fv(dog, 16, false, true) }
  local same = #a == #b
  for i = 1, 7 do if a[i] ~= b[i] then same = false end end
  check("test_findTroughNear_equivalent_no_zone", same and a[1] ~= nil and a[6] == true, "orig=" .. tostring(a[2]) .. "@" .. tostring(a[3]) .. " ovr=" .. tostring(b[2]) .. "@" .. tostring(b[3]))
end

local function test_trough_zone_fast_path_and_every_third_full()
  local W = makeWorld()
  local zoneTrough = W.trough(5, 0, 0, 5)
  W.dish(0, 1, 0, 2)                   -- closer, but only the full search sees it
  local dog = W.animal(99, 0.5, 0.5, 0, "dog", false)
  function dog:getConnectedDZone() return list({ "zone" }) end
  function dog:getBehavior() return { getRandomTroughList = function() return list({ zoneTrough }) end } end
  local _, fv = feedPair(W, { cfg = { troughZone = true } })
  local got = {}
  for i = 1, 3 do got[i] = fv(dog, 16, false, true) end
  -- default (no opt-in): always the full search, so the closer dish wins as in the original
  local fo, fd = feedPair(W)
  local same = true
  for _ = 1, 3 do if fd(dog, 16, false, true) ~= fo(dog, 16, false, true) then same = false end end
  check("test_trough_zone_off_by_default", same and fd(dog, 16, false, true) ~= zoneTrough, "default differs from the original")
  check("test_trough_zone_fast_path_and_every_third_full",
    got[1] == zoneTrough and got[2] == zoneTrough and got[3] ~= zoneTrough and got[3] ~= nil,
    "picks=" .. tostring(got[1] == zoneTrough) .. "," .. tostring(got[2] == zoneTrough) .. "," .. tostring(got[3] == zoneTrough))
end

local SKIPPED = { ["srv.animclears"] = true, ["srv.fencehop"] = true, ["srv.fencesteer"] = true,
  ["srv.followsteer"] = true, ["srv.fencelandpush"] = true, ["srv.landglide"] = true,
  ["srv.carewatch"] = true, ["srv.fallguard"] = true,
  ["srv.gunguard"] = true, ["srv.fire"] = true, ["srv.densesync"] = true }

local function tickKeys(path, dogs, cfg)
  local env = makeEnv(makeWorld(), { dogs = dogs, cfg = cfg })
  env.loadFile(path)
  env.companionTick()
  return env.pzCalls
end

local function test_idle_gate_skips_exactly_documented_set()
  local orig = tickKeys(ORIG .. "server/CompanionDogs_Companion.lua", {})
  local ovr = tickKeys(OVR .. "server/CompanionDogs_Companion.lua", {})
  local expect = {}
  for _, k in ipairs(orig) do if not SKIPPED[k] then expect[#expect + 1] = k end end
  check("test_idle_gate_skips_exactly_documented_set", table.concat(expect, " ") == table.concat(ovr, " "),
    "\n  expected " .. table.concat(expect, " ") .. "\n  got      " .. table.concat(ovr, " "))
  -- the comment's SKIP list is the same set
  local f = assert(io.open(OVR .. "server/CompanionDogs_Companion.lua")); local src = f:read("*a"); f:close()
  local line = src:match("%-%- SKIP: ([^\n]*)")
  local n = 0
  for _ in (line or ""):gmatch("[%w]+") do n = n + 1 end
  check("test_idle_gate_skips_exactly_documented_set", n == 11, "comment lists " .. n .. " systems")
end

local function test_idle_gate_runs_everything_with_one_dog()
  local orig = tickKeys(ORIG .. "server/CompanionDogs_Companion.lua", { "dog" })
  local ovr = tickKeys(OVR .. "server/CompanionDogs_Companion.lua", { "dog" })
  local off = tickKeys(OVR .. "server/CompanionDogs_Companion.lua", {}, { idleGate = false })
  check("test_idle_gate_runs_everything_with_one_dog", table.concat(orig, " ") == table.concat(ovr, " "), "differs with a dog loaded")
  check("test_idle_gate_runs_everything_with_one_dog", table.concat(orig, " ") == table.concat(off, " "), "idleGate=false still skips")
end

test_zsnap_equivalent()
test_scanWildAnimals_equivalent_including_ties()
test_findTroughNear_equivalent_no_zone()
test_trough_zone_fast_path_and_every_third_full()
test_idle_gate_skips_exactly_documented_set()
test_idle_gate_runs_everything_with_one_dog()

print(table.concat(results, "\n"))
print(failures == 0 and "ALL OK" or ("FAILURES: " .. failures))
os.exit(failures == 0 and 0 or 1)
