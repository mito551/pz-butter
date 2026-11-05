-- Fallback-path check for ETW_PerfTick (no Java globals). Run: luajit tools/test/perftick_check.lua 42/media/lua/shared/ETW_PerfTick.lua
local ticks = {}
Events = { OnTick = {
	Add = function(fn) ticks[#ticks + 1] = fn end,
	Remove = function(fn) for i = #ticks, 1, -1 do if ticks[i] == fn then table.remove(ticks, i) end end end,
} }
local localPlayer = { name = "p0" }
getPlayer = function() return localPlayer end
package.loaded.ETW_CommonFunctions = { playersList = function()
	local l = { localPlayer, { name = "p1" } }
	return { size = function() return #l end, get = function(_, i) return l[i + 1] end }
end }
local T = dofile(arg[1])
local seen = {}
T.register("a", function() seen[#seen + 1] = "a" end)
T.register("p", function(p) seen[#seen + 1] = "p:" .. p.name end, "players")
T.register("l", function(p) seen[#seen + 1] = "l:" .. p.name end, "local", {})
T.register("a", function() seen[#seen + 1] = "a2" end) -- replace, not duplicate
assert(#ticks == 3, "expected 3 handlers, got " .. #ticks)
for _, fn in ipairs(ticks) do fn() end
assert(table.concat(seen, ",") == "p:p0,p:p1,l:p0,a2", table.concat(seen, ","))
T.unregister("p"); T.unregister("l"); T.unregister("a"); T.unregister("missing")
assert(#ticks == 0)
-- Java path: globals present -> no Events use.
local calls = {}
etwPerfTickRegister = function(id, fn, gate) calls[#calls + 1] = id .. ":" .. gate end
etwPerfTickUnregister = function(id) calls[#calls + 1] = "-" .. id end
T.register("x", print); T.unregister("x")
assert(table.concat(calls, ",") == "x:always,-x" and #ticks == 0)
print("ok")
