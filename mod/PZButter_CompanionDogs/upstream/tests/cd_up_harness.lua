local function world()
  local env = setmetatable({}, { __index = _G }); env._G = env
  local clock = 0
  env.getTimestampMs = function() clock = clock + 1; return clock end
  env.isClient = function() return false end; env.isServer = function() return false end
  env.instanceof = function() return false end
  env.Events = {}; env.listeners = {}
  for _, n in ipairs({ "OnTick", "OnPlayerUpdate", "OnServerCommand", "OnAIStateChange" }) do
    local list = {}; env.listeners[n] = list
    env.Events[n] = { Add = function(f) list[#list+1] = f end, Remove = function(f) for i, g in ipairs(list) do if g == f then table.remove(list, i); return end end end }
  end
  env.fire = function(name, ...) for _, f in ipairs({ table.unpack(env.listeners[name]) }) do pcall(f, ...) end end
  env.logs = {}
  return env
end
local function setup(src, preset)
  local env = world()
  env.CompanionDogs = { log = function(s) env.logs[#env.logs+1] = s end }
  if preset then preset(env.CompanionDogs) end
  assert(load(src, "Perf", "t", env))()
  return env, env.CompanionDogs
end
local results, failures = {}, 0
local function expect(n, c, d) results[#results+1] = (c and "PASS " or "FAIL ") .. n .. ((not c and d) and (" " .. d) or ""); if not c then failures = failures + 1 end end

-- profiler level 0
do
  local env, CD = setup(SRC_UP)
  local calls = {}
  for i = 1, 25 do CD.evAdd(env.Events.OnTick, function(a) calls[#calls+1] = i; if i == 3 then error("x") end end, "cli.t" .. i) end
  for i = 1, 6 do CD.evAdd(env.Events.OnPlayerUpdate, function(p) calls[#calls+1] = "p" .. i .. p end, "cli.p" .. i) end
  CD.evAdd(env.Events.OnServerCommand, function(...) calls[#calls+1] = "cmd" end, "cli.cmd")
  expect("level 0: one listener for 25 OnTick handlers", #env.listeners.OnTick == 1)
  expect("level 0: one listener for 6 OnPlayerUpdate handlers", #env.listeners.OnPlayerUpdate == 1)
  expect("level 0: other events direct", #env.listeners.OnServerCommand == 1)
  for _ = 1, 5 do env.fire("OnTick") end
  expect("level 0: all 25 handlers x5 ticks (incl. the one after the failing one)", #calls == 125, "#calls=" .. #calls)
  expect("level 0: failing handler logged 3 times at most", #env.logs == 3, "logs=" .. #env.logs)
  env.fire("OnPlayerUpdate", "P"); expect("level 0: OnPlayerUpdate args", calls[#calls] == "p6P")
end
-- profiler on: per-handler zones survive merging
do
  local env, CD = setup(SRC_UP)
  CD.PERF_DEBUG = true; CD.PERF_LEVEL = 1; CD.perfApplyLevel()
  for i = 1, 4 do CD.evAdd(env.Events.OnTick, function() end, "cli.t" .. i) end
  for _ = 1, 7 do env.fire("OnTick") end
  expect("level 1: merged, zones still counted per handler key", CD._perfCalls["cli.t1"] == 7 and CD._perfCalls["cli.t4"] == 7,
    tostring(CD._perfCalls["cli.t1"]) .. "/" .. tostring(CD._perfCalls["cli.t4"]))
end
-- merging switched off by the author's flag
do
  local env, CD = setup(SRC_UP, function(cd) cd.MERGE_EVENT_HANDLERS = false end)
  for i = 1, 5 do CD.evAdd(env.Events.OnTick, function() end, "k" .. i) end
  expect("flag off: one listener per handler as before", #env.listeners.OnTick == 5)
end
-- baseline unpatched behaviour for comparison
do
  local env, CD = setup(SRC_ORIG)
  for i = 1, 5 do CD.evAdd(env.Events.OnTick, function() end, "k" .. i) end
  expect("baseline original: 5 listeners", #env.listeners.OnTick == 5)
end
print(table.concat(results, "\n")); print(failures == 0 and "ALL OK" or "FAILURES " .. failures)
