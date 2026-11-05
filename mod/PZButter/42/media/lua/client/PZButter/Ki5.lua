-- KI5 handler bundle: one OnPlayerUpdate dispatcher for recognised vehicle handlers.
-- The table is data from tools/ki5scan.py; this file never parses mod code.
if not (PZButter and PZButter.ki5Bundle) then return end
-- with the key on, every exit says why: the 2026-11-05 A/B ran with no ki5Bundle line and measured nothing
if not PZButter.Census then print("[PZButter] ki5Bundle: off (needs handlerCensus=true)") return end
if isServer and isServer() then return end
if _G.PZButterKi5Installed then return end
_G.PZButterKi5Installed = true

local Ki5 = {}
PZButter.Ki5 = Ki5
-- read at install: "Ki5.lua" loads before "ki5table.lua" (byte order puts K before k)
local rows = {}
local syncMs = tonumber(PZButter.ki5SyncMs) or 100
Ki5.now = getTimestampMs

local function normalize(path)
    if type(path) ~= "string" then return nil end
    path = path:gsub("\\", "/"):lower()
    path = path:gsub("/+$", "")
    if path == "" then return nil end
    return path
end

local function parse_id(id)
    if type(id) ~= "string" then return nil end
    local mod, file, line = id:match("^(.-):(.*):(%d+)$")
    if not mod then return nil end
    return mod, normalize(file), tonumber(line)
end

-- entry.line is the closure's first instruction (inside the function); the row spans line .. last
local function match_row(entry, mod, file, line, last)
    if entry.count ~= 1 or type(entry.line) ~= "number" or entry.line < line or entry.line > (last or line) then return false end
    if normalize(entry.file) == nil then return false end
    local actual = normalize(entry.file)
    return actual == file or actual:sub(-(#file + 1)) == "/" .. file
end

local function resolve(row)
    local mod, file, line = parse_id(row.id)
    if not mod then return nil end
    return PZButter.Census.route("OnPlayerUpdate", function(e)
        return match_row(e, mod, file, line, tonumber(row.last))
    end)
end

local handlers = {}
local by_name = {}

local paused = {}
local fails = {}
local lastVehicle, lastType, lastSeat

local function error_key(row, vtype)
    return row.id .. "|" .. tostring(vtype or "")
end

local function should_run(h, now, vehicle, vtype, seat)
    local row = h.row
    if not vehicle and row.onFoot == "none" then return false end
    if row.types and #row.types > 0 then
        if not vtype then return false end
        local hit = false
        for _, t in ipairs(row.types) do
            if string.find(vtype, t, 1, true) then hit = true; break end
        end
        if not hit then return false end
    end
    local p = paused[error_key(row, vtype)]
    if p and now < p then return false end
    if row.kind == "sync" then
        local changed = h.vehicle ~= vehicle or h.vtype ~= vtype or h.seat ~= seat
        if not changed and now - h.last < syncMs then return false end
    end
    return true
end

local function run(h, player, now, vehicle, vtype, seat)
    local row = h.row
    local ok, err = pcall(h.fn, player)
    h.vehicle, h.vtype, h.seat, h.last = vehicle, vtype, seat, now
    local k = error_key(row, vtype)
    if ok then
        paused[k] = nil
        fails[k] = nil
    else
        local n = (fails[k] or 0) + 1
        fails[k] = n
        local wait = n == 1 and 1000 or (n == 2 and 5000 or 30000)
        paused[k] = now + wait
        if n == 1 then
            print("[PZButter] ki5Bundle: " .. tostring(row.name) .. " error: " .. tostring(err))
        end
    end
end

function Ki5.dispatch(player)
    local now = Ki5.now()
    local vehicle = player and player:getVehicle() or nil
    local vtype = vehicle and vehicle:getScriptName() or nil
    local seat = vehicle and vehicle:getSeat(player) or nil
    if vehicle ~= lastVehicle or vtype ~= lastType or seat ~= lastSeat then
        lastVehicle, lastType, lastSeat = vehicle, vtype, seat
        for _, h in ipairs(handlers) do h.last = -math.huge end
    end
    local every = false
    for _, h in ipairs(handlers) do
        if h.row.kind == "every" then every = true; break end
    end
    if every and PZButter.ki5EveryStart then PZButter.ki5EveryStart() end
    for _, h in ipairs(handlers) do
        if should_run(h, now, vehicle, vtype, seat) then
            run(h, player, now, vehicle, vtype, seat)
        end
    end
    if every then
        if PZButter.ki5EveryStop then PZButter.ki5EveryStop() end
        if PZButter.ki5Flush then PZButter.ki5Flush() end
    end
end

-- Census.route answers only after the census has finished, at OnGameStart; this file loads before that, so the
-- handlers are taken over then (finish is idempotent, so the order of the two OnGameStart handlers does not matter)
function Ki5.install()
    PZButter.Census.finish()
    rows = PZButter.Ki5Table or {}
    for _, row in ipairs(rows) do
        local fn = resolve(row)
        if fn then
            local h = {row = row, fn = fn, last = -math.huge}
            handlers[#handlers + 1] = h
            by_name[row.name] = h
        end
    end
    if #handlers == 0 then
        local entries = PZButter.Census.list("OnPlayerUpdate")
        print("[PZButter] ki5Bundle: off (no recognised handlers; " .. #rows .. " rows, "
            .. #entries .. " OnPlayerUpdate handlers in the census)")
        -- why: the census entries in each row's file (a line, count or owner that differs explains the miss)
        local shown = 0
        for _, row in ipairs(rows) do
            if shown >= 5 then break end
            local _, file = parse_id(row.id)
            for _, e in ipairs(entries) do
                local actual = normalize(e.file)
                if file and actual and (actual == file or actual:sub(-(#file + 1)) == "/" .. file) then
                    print("[PZButter] ki5Bundle: row " .. row.id .. " vs census line " .. tostring(e.line)
                        .. ", count " .. tostring(e.count) .. ", owner " .. tostring(e.owner))
                    shown = shown + 1
                end
            end
        end
        if shown == 0 then print("[PZButter] ki5Bundle: no census entry is in any row's file") end
        return
    end
    Events.OnPlayerUpdate.Add(Ki5.dispatch)
    print("[PZButter] ki5Bundle: " .. #handlers .. " handler(s)")
end
Events.OnGameStart.Add(Ki5.install)
