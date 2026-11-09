-- Run: luajit tools/test/harness.lua 42/media/lua/server/DAMN_Spawns.lua native|lua
-- (SetCheck.java: javac/java it with DSPerf.jar and projectzomboid.jar on the classpath.)
-- usage: luajit harness.lua <path-to-DAMN_Spawns.lua> <native|lua>
local file, mode = arg[1], arg[2]
local handlers, init = {}, nil
local spawned, transmits, rolls = {}, 0, 0
local md = {}
local sandbox = { DAMN = { AllowA = true, AllowB = false } }
SandboxVars = sandbox
IsoDirections = { S = "S" }
function isClient() return false end
function getScriptManager() return { getVehicle = function() return true end } end
function getActivatedMods() local l = {"damnlib"}; return { size = function() return #l end, get = function(_, i) return l[i + 1] end } end
function getServerOptions() return { getOptionByName = function() return { getValue = function() return "Muldraugh, KY" end } end } end
function string.split(s, sep) local t = {}; for p in (s .. sep):gmatch("(.-)" .. sep) do t[#t + 1] = p end; return t end
function string.trim(s) return (s:gsub("^%s+", ""):gsub("%s+$", "")) end
ModData = { getOrCreate = function() return md end, transmit = function() transmits = transmits + 1 end }
Calendar = { getInstance = function() return { getTimeInMillis = function() return 123 end } end }
function ZombRandBetween() return 1 end
function addVehicleDebug(script, _, _, sq) spawned[#spawned + 1] = script .. "@" .. sq:getX() end
Events = { LoadGridsquare = { Add = function(f) handlers[#handlers + 1] = f end }, OnInitGlobalModData = { Add = function(f) init = f end } }
DAMN = { log = function() end }
local nativeSet, nativeCalls = {}, 0
if mode == "native" then
    function dsSpawnsRegister(x, y) nativeSet[x * 131072 + y] = true end
    function dsSpawnsClear() nativeSet = {} end
    function dsSpawnsCount() local n = 0; for _ in pairs(nativeSet) do n = n + 1 end; return n end
    function dsSpawnsIsTargetSquare(sq) nativeCalls = nativeCalls + 1; return nativeSet[sq:getX() * 131072 + sq:getY()] == true end
end
dofile(file)

local shared = { "Muldraugh, KY" }
local opts = { mapWhitelist = shared }
DAMN.Spawns:add("Base.A", 8145, 11644, { sandboxVar = "AllowA", mapWhitelist = shared })
DAMN.Spawns:add("Base.B", 9000, 9000, { sandboxVar = "AllowB" })
DAMN.Spawns:add("Base.C", 7000, 7000, { modBlacklist = { "damnlib" } })
assert(#shared == 1, "caller mapWhitelist was mutated")
init(); init() -- twice: handler must register once
assert(#handlers == 1, "handler registered " .. #handlers .. " times")
local function sq(x, y) return { getX = function() return x end, getY = function() return y end, isVehicleIntersecting = function() return false end } end

for i = 1, 1000 do handlers[1](sq(100 + i, 200)) end -- ordinary squares
assert(#spawned == 0 and transmits == 0, "non-target squares did work")
if mode == "native" then assert(nativeCalls == 1000, "native path unused") end

handlers[1](sq(8145, 11644)); handlers[1](sq(8145, 11644))
assert(#spawned == 1 and spawned[1] == "Base.A@8145", "allowed spawn should happen exactly once")
local t0 = transmits
handlers[1](sq(9000, 9000)); handlers[1](sq(7000, 7000)); handlers[1](sq(9000, 9000))
assert(#spawned == 1, "disallowed spawned")
assert(transmits == t0, "disallowed spawn was remembered / broadcast")
assert(md["9000_9000"] == nil and md["7000_7000"] == nil, "disallowed spawn marked done")

-- blocked square retries, then spawns once cleared
local blocked = true
local s2 = sq(8145, 11644)
md["8145_11644"] = nil; spawned = {}
s2.isVehicleIntersecting = function() return blocked end
handlers[1](s2); assert(#spawned == 0 and md["8145_11644"] == nil, "blocked should not remember")
blocked = false; handlers[1](s2); assert(#spawned == 1 and md["8145_11644"], "retry should spawn")
print("lua ok (" .. mode .. ")")
