-- Plan 17: handler census and the shared fix helper.
local here = debug.getinfo(1, "S").source:sub(2)
local root = here:gsub("/test/lua/.*$", "")
dofile(root .. "/test/lua/stubs.lua")
dofile(root .. "/mod/PZButter/42/media/lua/shared/PZButter/HandlerCensus.lua")
dofile(root .. "/mod/PZButter/42/media/lua/shared/PZButter/Fix.lua")

local Census, Fix = PZButter.Census, PZButter.Fix
assert(Census and Fix, "census and fix namespaces installed")

-- Installing the file twice must not wrap twice.
local add = Events.OnTick.Add
dofile(root .. "/mod/PZButter/42/media/lua/shared/PZButter/HandlerCensus.lua")
assert(Events.OnTick.Add == add, "installOnce: second load kept the first wrapper")

local fnA = function() end
local fnC = function() end
local fnD = function() end
Stubs.setSource(fnA, "/mods/modA/a.lua", 11)
Stubs.setSource(fnC, "/mods/modB/c.lua", 22)
Stubs.setSource(fnD, "/mods/modA/d.lua", 33)

Events.OnTick.Add(fnA)
Events.OnTick.Add(fnC)
Events.OnTick.Add(fnC)
Events.OnTick.Add(fnD)

-- Source file is recorded before the load phase ends; owners are assigned at finish.
local before = Census.list("OnTick")
assert(#before == 3, "one entry per function before finish")
assert(before[1].file == "/mods/modA/a.lua" and before[1].line == 11, "source recorded")
assert(before[1].owner == nil, "owner waits for finish")

Census.finish()
local rows = Census.list("OnTick")
assert(rows[1].owner == "modA" and rows[2].owner == "modB", "recordsOwner")
assert(rows[2].count == 2, "duplicate registration counted")
assert(Stubs.prints[#Stubs.prints]:find("handlerCensus: 4 handlers", 1, true),
    "finish prints the total: " .. tostring(Stubs.prints[#Stubs.prints]))

-- Same function twice is not a safe takeover, and both stay registered.
assert(Census.route("OnTick", function(e) return e.fn == fnC end) == nil, "routeExactlyOnce")
assert(Stubs.count("OnTick", fnC) == 2, "routeExactlyOnce leaves both")

-- A single load-phase registration with a known owner is removed and returned.
local got = Census.route("OnTick", function(e) return e.fn == fnD end)
assert(got == fnD, "routeRemoves returns the function")
assert(Stubs.count("OnTick", fnD) == 0, "routeRemoves removes it")
assert(Census.route("OnTick", function(e) return e.fn == fnD end) == nil, "route is once")

-- Run-time adds pass through and only bump the counter.
local fnE = function() end
Stubs.setSource(fnE, "/mods/modA/e.lua", 44)
Events.OnTick.Add(fnE)
assert(Stubs.count("OnTick", fnE) == 1, "runtimeAddPassesThrough")
assert(Census.runtime("OnTick") == 1, "runtime add counted")
assert(#Census.list("OnTick") == 2, "runtime add not in the load-phase list")
assert(Census.route("OnTick", function(e) return e.fn == fnE end) == nil, "runtime add not routed")

-- Fix.once is true once, then false.
assert(Fix.once("x") == true, "once first")
assert(Fix.once("x") == false, "once second")

-- A function from another file is left untouched.
local foreign = { foo = function() return "orig" end }
Stubs.setSource(foreign.foo, "/mods/modB/foo.lua", 1)
local after = 0
assert(Fix.wrapAfter(foreign, "foo", "modA/foo.lua", function() after = after + 1 end) == false,
    "wrapAfterForeignFile refuses")
assert(foreign.foo() == "orig" and after == 0, "foreign original untouched")

-- A matching file wraps: original first, then the after hook.
local order = {}
local mine = { foo = function() order[#order + 1] = "orig"; return "orig" end }
Stubs.setSource(mine.foo, "/mods/modA/foo.lua", 1)
assert(Fix.wrapAfter(mine, "foo", "modA/foo.lua", function() order[#order + 1] = "after" end) == true,
    "matching file wraps")
assert(mine.foo() == "orig", "original result passes through")
assert(order[1] == "orig" and order[2] == "after", "original runs first")

Stubs.realPrint("census_test: ok")
