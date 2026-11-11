local here = debug.getinfo(1, "S").source:sub(2)
local root = here:gsub("[/\\]?test/lua/windsway_ws2_test.lua$", "")
if root == "" then root = "." end

-- Wind Sway WS-2: steps tree detail down under GPU load and back up, never above the user's
-- own setting, including one changed in the middle of a session.
local ticks, starts, now, sets = {}, {}, 0, {}
Events = { OnTick = { Add = function(f) ticks[#ticks + 1] = f end }, OnGameStart = { Add = function(f) starts[#starts + 1] = f end } }
function getTimestampMs() return now end
print = function() end
local combo = 1   -- High
WindSway = { syncToJava = function() end, treeDetailOpt = { getValue = function() return combo end } }
ModJava = { setTreeDetail = function(level) sets[#sets + 1] = level end }
local file = root .. "/mod/PZButter_WindSway/42/media/lua/client/PZButter_WindSway_WS2.lua"

-- no PZ Butter: no per-tick handler at all
dofile(file); for _, f in ipairs(starts) do f() end
assert(#ticks == 0, "without PZ Butter nothing may run per tick")

PZButter = { gpu = { overTarget = false }, windSwayAutoTreeDetail = true }
starts = {}; dofile(file); for _, f in ipairs(starts) do f() end
assert(#ticks == 1, "handler not registered")
local function run(ms) for _ = 1, ms / 1000 do now = now + 1000; ticks[1]() end end

PZButter.gpu.overTarget = true; run(6000)
assert(sets[#sets] == 1, "5 s over target: one step down from High")
PZButter.gpu.overTarget = false; run(11000)
assert(sets[#sets] == 2, "10 s under target: back to the user's High")

-- the user picks Low mid-game (Wind Sway applies it itself): never raise it again
local n = #sets
combo = 3; run(30000)
assert(#sets == n, "detail was raised above the level the user chose")
io.write("windsway_ws2_test: ok\n")
