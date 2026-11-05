-- PZ Butter: per-tick Lua handler census.
--
-- The census wraps Events.<Name>.Add/.Remove so a slow tick can name the
-- handler that cost the time, and so a fix can take over exactly one handler
-- that a mod registered once. It records source file and first line, assigns
-- each registration to the mod whose folder contains it at the end of the load
-- phase, and then stops growing: run-time registrations only bump a counter.
if not (PZButter and PZButter.handlerCensus) then return end
if _G.PZButterCensusInstalled then return end
_G.PZButterCensusInstalled = true

local Events = Events
local Census = {}
PZButter.Census = Census

-- Per-frame / per-tick events only: the ones whose handler count a tick cares
-- about. Menu and screen events are deliberately absent.
local EVENT_NAMES = {
    "OnTick",
    "OnTickEvenPaused",
    "OnPlayerUpdate",
    "OnRenderTick",
    "OnRenderUpdate",
    "OnFETick",
    "OnPreUIDraw",
    "OnPostUIDraw",
}

local state = {}
local finished = false
local unpack = unpack or table.unpack

local function normalize(path)
    if type(path) ~= "string" then return nil end
    path = path:gsub("\\", "/"):lower():gsub("/+$", "")
    if path == "" then return nil end
    return path
end

local function source(fn)
    local file, line
    local ok, v = pcall(getFilenameOfClosure, fn)
    if ok then file = v end
    ok, v = pcall(getFirstLineOfClosure, fn)
    if ok then line = v end
    return file, line
end

local function record_add(name, fn)
    local st = state[name]
    if not st then
        st = { entries = {}, by_fn = {}, runtime = 0 }
        state[name] = st
    end
    if finished then
        st.runtime = st.runtime + 1
        return
    end
    local entry = st.by_fn[fn]
    if entry then
        entry.count = entry.count + 1
        return
    end
    local file, line = source(fn)
    entry = { fn = fn, file = file, line = line, count = 1, owner = nil }
    st.by_fn[fn] = entry
    st.entries[#st.entries + 1] = entry
end

local function record_remove(name, fn)
    local st = state[name]
    if not st then return end
    local entry = st.by_fn[fn]
    if not entry then return end
    entry.count = entry.count - 1
    if entry.count > 0 then return end
    st.by_fn[fn] = nil
    for i = 1, #st.entries do
        if st.entries[i] == entry then
            table.remove(st.entries, i)
            return
        end
    end
end

local function wrap(name)
    local ev = Events and Events[name]
    if not ev or ev.Add == nil or ev.Remove == nil then return end
    local add, remove = ev.Add, ev.Remove
    ev.Add = function(fn, ...)
        local result = { add(fn, ...) }
        pcall(record_add, name, fn)
        return unpack(result)
    end
    ev.Remove = function(fn, ...)
        local result = { remove(fn, ...) }
        pcall(record_remove, name, fn)
        return unpack(result)
    end
end

for _, name in ipairs(EVENT_NAMES) do wrap(name) end

local function mod_dirs()
    local dirs = {}
    local ok, ids = pcall(getActivatedMods)
    if not ok or ids == nil then return dirs end
    -- the game returns a java.util.List (userdata in Lua): every owner was nil while this required a Lua table
    local okN, n = pcall(function() return ids:size() end)
    local list = {}
    if okN and type(n) == "number" then
        for i = 0, n - 1 do list[#list + 1] = ids:get(i) end
    elseif type(ids) == "table" then
        list = ids
    end
    for _, id in ipairs(list) do
        local got, info = pcall(getModInfoByID, id)
        if got and info then
            -- the root, its 42.x folder and common/: a handler's file lies in one of them
            for _, getter in ipairs({ "getDir", "getVersionDir", "getCommonDir" }) do
                local okDir, dir = pcall(function() return info[getter](info) end)
                dir = okDir and normalize(dir) or nil
                if dir then dirs[#dirs + 1] = { id = id, dir = dir } end
            end
        end
    end
    return dirs
end

local function owner_of(file, dirs)
    file = normalize(file)
    if not file then return nil end
    local best, bestLen
    for _, m in ipairs(dirs) do
        if (file == m.dir or file:sub(1, #m.dir + 1) == m.dir .. "/")
                and (bestLen == nil or #m.dir > bestLen) then
            best, bestLen = m.id, #m.dir
        end
    end
    return best
end

function Census.finish()
    if finished then return end
    finished = true
    local dirs = mod_dirs()
    local total, byOwner = 0, {}
    for _, st in pairs(state) do
        for _, entry in ipairs(st.entries) do
            entry.owner = owner_of(entry.file, dirs)
            total = total + entry.count
            if entry.owner then
                byOwner[entry.owner] = (byOwner[entry.owner] or 0) + entry.count
            end
        end
    end
    local owners = {}
    for id, n in pairs(byOwner) do owners[#owners + 1] = { id = id, n = n } end
    table.sort(owners, function(a, b)
        if a.n ~= b.n then return a.n > b.n end
        return a.id < b.id
    end)
    local parts = {}
    for i = 1, math.min(#owners, 5) do
        parts[#parts + 1] = owners[i].id .. "=" .. owners[i].n
    end
    local runtime = 0
    for _, st in pairs(state) do runtime = runtime + st.runtime end
    print("[PZButter] handlerCensus: " .. total .. " handlers"
        .. (#parts > 0 and (", top owners " .. table.concat(parts, ", ")) or "")
        .. (runtime > 0 and (", runtime=" .. runtime) or ""))
end

function Census.list(event)
    local out = {}
    local st = state[event]
    if not st then return out end
    for _, entry in ipairs(st.entries) do
        out[#out + 1] = {
            fn = entry.fn,
            file = entry.file,
            line = entry.line,
            count = entry.count,
            owner = entry.owner,
        }
    end
    return out
end

function Census.runtime(event)
    local st = state[event]
    return st and st.runtime or 0
end

-- Remove and return the one load-phase handler matchFn selects. A handler
-- registered more than once, added at run time, or with no known owner is not
-- a safe takeover and is left alone.
function Census.route(event, matchFn)
    if not finished then return nil end
    local st = state[event]
    if not st then return nil end
    local found
    for _, entry in ipairs(st.entries) do
        local ok, hit = pcall(matchFn, entry)
        if ok and hit then
            if found then return nil end
            found = entry
        end
    end
    if not found or found.count ~= 1 or not found.owner then return nil end
    local ev = Events and Events[event]
    if not ev or ev.Remove == nil then return nil end
    local ok = pcall(ev.Remove, found.fn)
    if not ok then return nil end
    -- A foreign Remove may not go through our wrapper; keep the registry honest.
    if st.by_fn[found.fn] then record_remove(event, found.fn) end
    return found.fn
end

-- The load phase ends when the game starts: client OnGameStart, dedicated
-- server OnGameBoot. Registered through the original Add, not our wrapper.
local boot = "OnGameStart"
local okServer, server = pcall(isServer)
if okServer and server then boot = "OnGameBoot" end
local bootEvent = Events and Events[boot]
if bootEvent and bootEvent.Add then bootEvent.Add(Census.finish) end
