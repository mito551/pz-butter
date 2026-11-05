-- Marker for the optional Java helper (media/java/client): "the game itself ran this mod's scripts".
-- When this is missing (a server that does not list the mod) the helper runs these same files itself.
ViewpointLivingPropsLoaded = true

-- Keep callbacks installed so the menu can re-enable the mod during the same game.
-- Effects require an explicit yes; cleanup must still run when this returns false.
function ViewpointLivingPropsIsActive()
    local ok, active = pcall(function()
        local api = ViewpointLivingProps and ViewpointLivingProps.View
        return api ~= nil and api.active ~= nil and api.active() == true
    end)
    return ok and active == true
end

-- ---------------------------------------------------------------- optional timing report (OFF normally)
-- An empty text file called ViewpointLivingProps_perf.txt in the Zomboid\Lua folder switches it on. Then, every 10 seconds, console.txt
-- gets a few "[VLP-perf]" lines: the frame rate, the longest pause between two game ticks, and how many milliseconds each script of this
-- mod used (so it can be seen whether this mod's scripts, or something else, make the game slow). Without the file nothing is
-- measured and nothing is printed; the other scripts only call do-nothing functions.
local NOOP = {
    on = false,
    tag = function() end,
    fn = function(_, f) return f end,
    pcall = function(_, f, ...) return pcall(f, ...) end,
    count = function() end,
    gauge = function() end,
}
local P = NOOP

local function perfFlag()
    local ok, r = pcall(getFileReader, "ViewpointLivingProps_perf.txt", false)
    if ok and r then
        pcall(function() r:close() end)
        return true
    end
    return false
end

if perfFlag() then
    P = { on = true }
    local WINDOW_MS = 10000          -- one report every 10 s
    local SPIKE_MS = 8               -- a single call of ours that takes this long is written down at once
    local SLOW_GAP_MS = 50           -- a pause between two ticks longer than this counts as a "slow frame"
    local TOP = 10                   -- lines of the report
    local function now() return getTimestampMs() end

    local t0 = now()
    local stat, isEvent = {}, {}     -- label -> { n, ms, max }
    local counters, gauges = {}, {}
    local spikes = 0
    local cur, curStart = nil, nil
    local hooked = 0

    local function rec(label, dt)
        local s = stat[label]
        if not s then
            s = { n = 0, ms = 0, max = 0 }
            stat[label] = s
        end
        s.n = s.n + 1
        s.ms = s.ms + dt
        if dt > s.max then s.max = dt end
        if dt >= SPIKE_MS and spikes < 40 then
            spikes = spikes + 1
            print("[VLP-perf] spike: " .. label .. " took " .. dt .. " ms (+" .. math.floor((now() - t0) / 1000) .. " s)")
        end
    end

    local function timed(label, f)
        return function(...)
            local a = now()
            local r1, r2, r3 = f(...)
            rec(label, now() - a)
            return r1, r2, r3
        end
    end

    -- P.tag("Name") at the top of a script and P.tag(nil) at its end: the event handlers it adds in between carry that name, and
    -- the time the script took to load is written down too
    function P.tag(name)
        if cur and curStart then rec("load " .. cur, now() - curStart) end
        cur = name
        curStart = name and now() or nil
    end
    function P.fn(label, f) return timed(label, f) end                  -- a function that is timed under that name
    function P.pcall(label, f, ...)
        local a = now()
        local ok, r = pcall(f, ...)
        rec(label, now() - a)
        return ok, r
    end
    function P.count(label) counters[label] = (counters[label] or 0) + 1 end   -- "something happened", counted per report
    function P.gauge(label, f) gauges[label] = f end                    -- a number asked for at every report

    -- the handlers that the scripts of this mod add to the game's events are timed (other mods' handlers are left alone)
    local EVENTS = { "OnTick", "OnPlayerUpdate", "LoadGridsquare", "ReuseGridsquare", "OnObjectAdded", "OnFillContainer",
        "OnGameStart", "OnGameBoot", "OnLoadedTileDefinitions" }
    local rawTickAdd = nil
    for _, name in ipairs(EVENTS) do
        pcall(function()
            local ev = Events[name]
            local add = ev and ev.Add
            if not add then return end
            if name == "OnTick" then rawTickAdd = add end
            ev.Add = function(f)
                if cur and type(f) == "function" then
                    local label = cur .. " " .. name
                    isEvent[label] = true
                    f = timed(label, f)
                end
                return add(f)
            end
            hooked = hooked + 1
        end)
    end

    local ticks, lastT, gapMax, slow = 0, now(), 0, 0
    local winStart = lastT

    local function report(t)
        local win = t - winStart
        local fps = "?"
        pcall(function() fps = tostring(math.floor(getAverageFPS() + 0.5)) end)
        local mem = "?"
        pcall(function() mem = tostring(math.floor(collectgarbage("count"))) end)
        local ours = 0
        local list = {}
        for label, s in pairs(stat) do
            if isEvent[label] then ours = ours + s.ms end
            list[#list + 1] = { label, s }
        end
        table.sort(list, function(a, b)
            if a[2].ms ~= b[2].ms then return a[2].ms > b[2].ms end
            return a[2].n > b[2].n
        end)
        print("[VLP-perf] +" .. math.floor((t - t0) / 1000) .. " s | fps " .. fps .. " | ticks " .. ticks .. " in " .. win .. " ms"
            .. " | longest pause " .. gapMax .. " ms, " .. slow .. " pauses over " .. SLOW_GAP_MS .. " ms"
            .. " | our event handlers " .. ours .. " ms (" .. math.floor(ours * 1000 / win) / 10 .. "%) | Lua memory " .. mem .. " KB")
        for i = 1, math.min(TOP, #list) do
            local s = list[i][2]
            print("[VLP-perf]    " .. list[i][1] .. ": " .. s.ms .. " ms in " .. s.n .. " calls, longest " .. s.max .. " ms")
        end
        local names = {}
        for label in pairs(counters) do names[#names + 1] = label end
        table.sort(names)
        for _, label in ipairs(names) do print("[VLP-perf]    count " .. label .. ": " .. counters[label]) end
        local gn = {}
        for label in pairs(gauges) do gn[#gn + 1] = label end
        table.sort(gn)
        for _, label in ipairs(gn) do
            local ok, v = pcall(gauges[label])
            print("[VLP-perf]    now " .. label .. ": " .. (ok and tostring(v) or "?"))
        end
        stat, counters = {}, {}
        ticks, gapMax, slow, winStart = 0, 0, 0, t
    end

    local function onTick()
        local t = now()
        ticks = ticks + 1
        local gap = t - lastT
        lastT = t
        if gap > gapMax then gapMax = gap end
        if gap >= SLOW_GAP_MS then slow = slow + 1 end
        if t - winStart >= WINDOW_MS then pcall(report, t) end
    end
    if rawTickAdd then rawTickAdd(onTick) end

    ViewpointLivingPropsPerf = P
    print("[VLP-perf] on: " .. hooked .. " of " .. #EVENTS .. " events can be timed; report every " .. (WINDOW_MS / 1000) .. " s")
    P.tag("Main")
else
    ViewpointLivingPropsPerf = NOOP
end

-- Registers the Viewpoint Living Props pack with Project Viewpoint.
-- Same call the other companion packs use: Viewpoint.ModelPacks.register(modId, manifest).
local MOD_ID = "ViewpointLivingProps"
local MANIFEST = "media/modelpacks/livingprops/pack.properties"
local done = false

local function registerPack()
    if done then return end
    if not Viewpoint or not Viewpoint.ModelPacks then return end
    local ok, res = P.pcall("register pack", Viewpoint.ModelPacks.register, MOD_ID, MANIFEST)
    if ok and res then
        done = true
    elseif not ok then
        print("[ViewpointLivingProps] " .. tostring(res))
    end
end

registerPack()
Events.OnGameBoot.Add(registerPack)
Events.OnGameStart.Add(registerPack)
P.tag(nil)
