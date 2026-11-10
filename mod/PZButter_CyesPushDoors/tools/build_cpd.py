#!/usr/bin/env python3
"""
Generates the CPD_Perf overrides from the installed Cye's Push Doors files.

    build_cpd.py <CyesPushDoors/42/media/lua dir> <CPD_Perf/42/media/lua dir>

Every replacement is asserted to match exactly once, so a changed original fails loudly.
"""
import sys, os

SRC, DST = sys.argv[1], sys.argv[2]


def edit(rel, fn):
    p = os.path.join(SRC, rel)
    t = open(p, encoding="utf-8").read()

    def rep(old, new, count=1):
        nonlocal t
        n = t.count(old)
        assert n == count, f"{rel}: expected {count} got {n}: {old[:70]!r}"
        t = t.replace(old, new)

    def between(start, end, new):
        nonlocal t
        assert t.count(start) == 1, f"{rel}: start {start[:50]!r} x{t.count(start)}"
        a = t.index(start)
        b = t.index(end, a)
        t = t[:a] + new + t[b:]

    header = fn(rep, between)
    t = header + t
    out = os.path.join(DST, rel)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    open(out, "w", encoding="utf-8", newline="").write(t)
    print("wrote", rel, len(t.splitlines()), "lines")


HOOK_HEADER = '''--[[
    CPD_Perf build of CyesPushDoors/Hook.lua. Same behaviour, much less work per tick.
    It replaces the original at the same path because the scanner is file-local.
    Regenerate with tools/build_cpd.py after every update of Cye's Push Doors.

      N1  the doors around the player are collected when the player changes square or
          every DOOR_CACHE_TICKS, not on every scan; a scan then only looks at those doors
      N2  no pcall(function() ... end) closures in the scan or in isAttachedDoor
      N3  the object walk tests CPD.isSupportedDoor first, so floors, walls and furniture
          cost one check instead of five calls each
      --  captureScannerGeometry is no longer called: it computed an impact context for
          every observed door on every scan, and nothing ever read the result
      V1  with CPDPerf.jar, cpdDoorsNear() does the collection natively in one call
      U1  with CPDPerf.jar, doors toggled through IsoDoor/IsoThumpable are reported by
          cpdDrainDoorToggles() and observed at once; the poll then runs every 4 ticks
          instead of every 2 to pick up changes that arrive another way (network sync,
          double/garage doors, IsoThumpable.ToggleDoorSilent)
]]

do
    local m = getModInfoByID and getModInfoByID("CyesPushDoors")
    local v = m and m:getModVersion()
    if v and v ~= "@VERSION@" then
        print("[CPD_Perf] WARNING: Cye's Push Doors is " .. tostring(v) .. ", this override was built from @VERSION@; regenerate CPD_Perf with tools/build_cpd.py")
    end
end

'''

DOOR_SCAN = '''local DOOR_CACHE_TICKS = 30
local SCAN_PERIOD_POLLING = 2        -- no Java helper: same cadence as the original
local SCAN_PERIOD_EVENTS = 4         -- toggles are reported as they happen; the poll catches the rest
local doorCache = { square = nil, tick = -1000, doors = {} }

--- Same test as the original's per-object check: has a square and is still in its list.
local function isAttachedObject(object)
    local okSquare, objectSquare = pcall(object.getSquare, object)
    if not okSquare or objectSquare == nil then return false end
    local okIndex, objectIndex = pcall(object.getObjectIndex, object)
    return not okIndex or objectIndex == nil or tonumber(objectIndex) == nil or tonumber(objectIndex) >= 0
end

--- N2/N3: every supported door in the 5x5 block of squares around psq, on psq's floor.
local function collectDoorsLua(psq)
    local doors, count = {}, 0
    local cell = getCell()
    local px, py, pz = psq:getX(), psq:getY(), psq:getZ()
    for x = px - 2, px + 2 do
        for y = py - 2, py + 2 do
            local sq = cell:getGridSquare(x, y, pz)
            local objects = sq and sq:getObjects() or nil
            if objects then
                for i = 0, objects:size() - 1 do
                    local object = objects:get(i)
                    if object and CPD.isSupportedDoor(object) then
                        count = count + 1
                        doors[count] = object
                    end
                end
            end
        end
    end
    return doors
end

local function collectNearbyDoors(psq)
    local fast = cpdDoorsNear                    -- V1, from CPDPerf.jar
    if type(fast) == "function" then
        local ok, list = pcall(fast, psq:getX(), psq:getY(), psq:getZ(), 2)
        if ok and type(list) == "table" then return list end
    end
    local ok, doors = pcall(collectDoorsLua, psq)
    if ok then return doors end
    CPD.log("Nearby door collection failed: " .. tostring(doors))
    return nil
end

local function observeList(doors, playerObj, priors)
    local n = #doors
    if n == 0 then return end
    local scanSeen = n > 1 and {} or nil
    for i = 1, n do
        local object = doors[i]
        if isAttachedObject(object) then
            local okObserve, err = pcall(observeDoor, object, playerObj, scanSeen, priors and priors[i])
            if not okObserve then
                CPD.log("Nearby door observer skipped an invalid/stale object: " .. tostring(err))
            end
        end
    end
end

local function scanNearbyDoors()
    local playerObj = getPlayer and getPlayer() or nil
    if not playerObj or not playerObj:isAlive() then return end
    local psq = playerObj:getCurrentSquare()
    if not psq then return end

    if doorCache.square ~= psq or CPD._hookTick - doorCache.tick >= DOOR_CACHE_TICKS then
        local doors = collectNearbyDoors(psq)
        if not doors then return end         -- not cached: retry on the next scan
        doorCache.square, doorCache.tick = psq, CPD._hookTick
        doorCache.doors = doors
    end
    observeList(doorCache.doors, playerObj)
end

--- U1: doors the game toggled since the last tick, if they are inside the scan window.
local function observeToggledDoors(toggled)
    local playerObj = getPlayer and getPlayer() or nil
    if not playerObj or not playerObj:isAlive() then return end
    local psq = playerObj:getCurrentSquare()
    if not psq then return end
    local px, py, pz = psq:getX(), psq:getY(), psq:getZ()

    local priors = toggled.priors            -- state each door had before the toggle
    local inWindow, inWindowPriors = {}, {}
    for i = 1, #toggled do
        local door = toggled[i]
        local okSq, sq = pcall(door.getSquare, door)
        if okSq and sq and sq:getZ() == pz
                and math.abs(sq:getX() - px) <= 2 and math.abs(sq:getY() - py) <= 2 then
            local n = #inWindow + 1
            inWindow[n] = door
            inWindowPriors[n] = priors and priors[i]
        end
    end
    observeList(inWindow, playerObj, inWindowPriors)
end

local function pruneObservedDoors()
    for key, state in pairs(CPD._observedDoors) do
        if not state or (CPD._hookTick - (state.lastSeen or 0)) > 600 then
            CPD._observedDoors[key] = nil
        end
    end
end

'''


def built_version():
    for line in open(os.path.join(SRC, "..", "..", "mod.info"), encoding="utf-8"):
        if line.startswith("modversion="):
            return line.split("=", 1)[1].strip()
    raise AssertionError("modversion not found in the original mod.info")


def hook(rep, between):
    # N2: isAttachedDoor without closures
    rep('''    local okSquare, square = pcall(function() return door:getSquare() end)
    if not okSquare or not square then return false end
    local okIndex, index = pcall(function() return door:getObjectIndex() end)
    if okIndex and index ~= nil and tonumber(index) ~= nil and tonumber(index) < 0 then''',
        '''    local okSquare, square = pcall(door.getSquare, door)
    if not okSquare or not square then return false end
    local okIndex, index = pcall(door.getObjectIndex, door)
    if okIndex and index ~= nil and tonumber(index) ~= nil and tonumber(index) < 0 then''')
    # dead computation: the geometry captured here is never read
    rep('''        CPD._observedDoors[key] = state
        captureScannerGeometry(state, playerObj, reportDoor)
        return''', '''        CPD._observedDoors[key] = state
        return''')
    rep('''    state.open = current
    captureScannerGeometry(state, playerObj, reportDoor)
end''', '''    state.open = current
end''')
    # U1: a door first seen at the moment it is toggled still gets its transition
    rep('local function observeDoor(door, playerObj, scanSeen)', 'local function observeDoor(door, playerObj, scanSeen, hintOpen)')
    rep('''    local state = CPD._observedDoors[key]
    if state == nil then
        state = {
            open = current,
            lastSeen = CPD._hookTick,
            lastTransitionMs = 0,
        }
        CPD._observedDoors[key] = state
        return
    end''', '''    local state = CPD._observedDoors[key]
    if state == nil then
        -- hintOpen is what the game says the door was before it was toggled; it is only
        -- trusted for a single door (a double or garage door has several parts)
        local prior = nil
        if hintOpen ~= nil and idDoor == door and not CPD.isGarageDoor(idDoor) and not CPD.isDoubleDoor(idDoor) then
            prior = hintOpen
        end
        local baseline = current
        if prior ~= nil then baseline = prior end
        state = {
            open = baseline,
            lastSeen = CPD._hookTick,
            lastTransitionMs = 0,
        }
        CPD._observedDoors[key] = state
        if prior == nil then return end
    end''')
    # scanner
    between('local function scanNearbyDoors()', 'local function onTick()', DOOR_SCAN)
    rep('''    if (CPD._hookTick % 2) == 0 then
        scanNearbyDoors()
    end
''', '''    local drain = cpdDrainDoorToggles           -- U1, from CPDPerf.jar
    if type(drain) == "function" then
        local toggled = drain()
        if toggled then
            local ok, err = pcall(observeToggledDoors, toggled)
            if not ok then CPD.log("Toggled door observer failed: " .. tostring(err)) end
        end
    end

    if (CPD._hookTick % (type(drain) == "function" and SCAN_PERIOD_EVENTS or SCAN_PERIOD_POLLING)) == 0 then
        scanNearbyDoors()
    end
    if (CPD._hookTick % 600) == 0 then pruneObservedDoors() end
''')
    return HOOK_HEADER.replace("@VERSION@", built_version())


def options(rep, between):
    header = '''-- CPD_Perf build of CyesPushDoors_ModOptions.lua: refreshFeedbackBinding is throttled in
-- game (every 4 frames); in the menus it still runs every frame. Regenerate with
-- tools/build_cpd.py after every update of Cye's Push Doors.

'''
    rep('''if Events.OnPreUIDraw then
    Events.OnPreUIDraw.Add(refreshFeedbackBinding)
elseif Events.OnTick then''', '''local feedbackFrame = 0
local function throttledFeedbackBinding()
    local inGame = getPlayer and getPlayer() ~= nil
    if inGame then
        feedbackFrame = feedbackFrame + 1
        if feedbackFrame % 4 ~= 0 then return end
    end
    refreshFeedbackBinding()
end

if Events.OnPreUIDraw then
    Events.OnPreUIDraw.Add(throttledFeedbackBinding)
elseif Events.OnTick then''')
    return header


edit("shared/CyesPushDoors/Hook.lua", hook)
edit("client/CyesPushDoors_ModOptions.lua", options)
