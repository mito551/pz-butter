-- Task 7: CD.data / CD.ownerKey per-frame memo.
local MODULE = "../../42/media/lua/shared/CD_PerfPatch.lua"

local results, failures = {}, 0
local function check(name, ok, detail)
  results[#results + 1] = (ok and "PASS " or "FAIL ") .. name .. ((not ok and detail) and ("  " .. tostring(detail)) or "")
  if not ok then failures = failures + 1 end
end

local function makeEnv(opts)
  opts = opts or {}
  local env = setmetatable({}, { __index = _G })
  env._G = env
  env.print = function() end
  env.clock = 1000
  env.serverMode = opts.serverMode or false
  env.clientMode = opts.clientMode or false
  env.isServer = function() return env.serverMode end
  env.isClient = function() return env.clientMode end
  env.getTimestampMs = function() return env.clock end

  local handlers = {}
  local pausedEv = setmetatable({}, {
    __call = function() for _, f in ipairs(handlers) do f() end end,
    __index = function(_, k)
      if k == "Add" then return function(f) handlers[#handlers + 1] = f end
      elseif k == "Remove" then return function(f)
        for i = #handlers, 1, -1 do if handlers[i] == f then table.remove(handlers, i) end end
      end
      elseif k == "handlers" then return handlers
      end
      return nil
    end,
  })
  -- OnTick is a separate event that never fires here: only OnTickEvenPaused advances frames
  env.Events = { OnTickEvenPaused = pausedEv, OnTick = { Add = function() end, Remove = function() end } }
  env.onTickEvenPaused = function() pausedEv() end

  env.getModInfoByID = function() return nil end

  local modDataCalls = 0
  env.modDataCalls = 0
  local function bump() env.modDataCalls = env.modDataCalls + 1 end

  local CD = {}
  env.CompanionDogs = CD
  env.CD = CD

  CD._perfPatchLoaded = false

  local origDataCalls = 0
  CD.data = function(animal)
    local md = animal:getModData()
    md.CompanionDogs = md.CompanionDogs or {}
    return md.CompanionDogs
  end
  env.origData = CD.data

  CD.ownerKey = function(player)
    if player == nil then return nil end
    return "owner-" .. tostring(player.id)
  end
  env.origOwnerKey = CD.ownerKey

  function env.makeAnimal(id)
    local a = { id = id }
    local md = { CompanionDogs = {} }
    a._md = md
    function a:getModData()
      bump()
      return self._md
    end
    return a
  end

  function env.makePlayer(id)
    local p = { id = id }
    return p
  end

  env.CD_PerfPatchConfig = opts.cfg or {}

  local f = assert(io.open(MODULE, "r"))
  local src = f:read("*a"); f:close()
  assert(load(src, "CD_PerfPatch", "t", env))()

  env.CD = env.CompanionDogs
  pausedEv()  -- the game is running: first frame has ticked
  return env, pausedEv
end

local function test_data_one_getModData_per_frame()
  local env = makeEnv({})
  local CD = env.CompanionDogs
  local a = env.makeAnimal(1)
  env.modDataCalls = 0
  for i = 1, 10 do CD.data(a) end
  check("test_data_one_getModData_per_frame", env.modDataCalls == 1, "calls=" .. env.modDataCalls)
end

local function test_data_sees_replacement_same_frame()
  local env = makeEnv({})
  local CD = env.CompanionDogs
  local a = env.makeAnimal(1)
  local first = CD.data(a)
  local md = a:getModData()
  md.CompanionDogs = {}
  local second = CD.data(a)
  check("test_data_sees_replacement_same_frame", second ~= first and second == md.CompanionDogs, "first=" .. tostring(first) .. " second=" .. tostring(second))
end

local function test_new_frame_refetches()
  local env, ev = makeEnv({})
  local CD = env.CompanionDogs
  local a = env.makeAnimal(1)
  CD.data(a)
  env.modDataCalls = 0
  ev()
  CD.data(a)
  check("test_new_frame_refetches", env.modDataCalls == 1, "calls=" .. env.modDataCalls)
end

local function test_client_disabled()
  local env = makeEnv({ clientMode = true })
  local CD = env.CompanionDogs
  local a = env.makeAnimal(1)
  env.modDataCalls = 0
  for i = 1, 10 do CD.data(a) end
  check("test_client_disabled", env.modDataCalls == 10, "calls=" .. env.modDataCalls)
end

local function test_ownerKey_memo_and_equivalence()
  local env, ev = makeEnv({})
  local CD = env.CompanionDogs
  local p = env.makePlayer(7)
  local k1 = CD.ownerKey(p)
  local k2 = CD.ownerKey(p)
  local ke = env.origOwnerKey(p)
  check("test_ownerKey_memo_and_equivalence", k1 == k2 and k1 == ke, "k1=" .. tostring(k1) .. " k2=" .. tostring(k2) .. " orig=" .. tostring(ke))
  -- new frame refetches (equivalence still holds)
  ev()
  local k3 = CD.ownerKey(p)
  check("test_ownerKey_memo_and_equivalence", k3 == ke, "k3=" .. tostring(k3))
end

local function test_paused_frames_advance()
  local env, ev = makeEnv({})
  local CD = env.CompanionDogs
  local a = env.makeAnimal(1)
  env.modDataCalls = 0
  CD.data(a) -- 1 call
  CD.data(a) -- cached, 0
  local before = env.modDataCalls
  check("test_paused_frames_advance", before == 1, "before=" .. before)
  -- paused frame advance via OnTickEvenPaused
  ev()
  CD.data(a)
  check("test_paused_frames_advance", env.modDataCalls == before + 1, "after=" .. env.modDataCalls)
end

local function test_engine_objects_not_written()
  local env, ev = makeEnv({})
  local CD = env.CompanionDogs
  local a = env.makeAnimal(1)
  setmetatable(a, { __newindex = function() error("cannot set fields on a Java object") end })
  local ok, err = pcall(function() for _ = 1, 3 do CD.data(a); ev() end end)
  check("test_engine_objects_not_written", ok, err)
end

test_engine_objects_not_written()
test_data_one_getModData_per_frame()
test_data_sees_replacement_same_frame()
test_new_frame_refetches()
test_client_disabled()
test_ownerKey_memo_and_equivalence()
test_paused_frames_advance()

print(table.concat(results, "\n"))
print(failures == 0 and "ALL OK" or ("FAILURES: " .. failures))
os.exit(failures == 0 and 0 or 1)