-- KI5 handler bundle: one OnPlayerUpdate dispatcher for recognised vehicle handlers.
-- The table is data from tools/ki5scan.py; this file never parses mod code.
if not (PZButter and PZButter.ki5Bundle and PZButter.Census) then return end
if isServer and isServer() then return end
if _G.PZButterKi5Installed then return end
_G.PZButterKi5Installed = true

local Ki5 = {}
PZButter.Ki5 = Ki5
local rows = PZButter.Ki5Table or {}
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

local function match_row(entry, mod, file, line)
    if entry.count ~= 1 or entry.line ~= line then return false end
    if normalize(entry.file) == nil then return false end
    local actual = normalize(entry.file)
    return actual == file or actual:sub(-(#file + 1)) == "/" .. file
end

local function resolve(row)
    local mod, file, line = parse_id(row.id)
    if not mod then return nil end
    return PZButter.Census.route("OnPlayerUpdate", function(e)
        return match_row(e, mod, file, line)
    end)
end

local handlers = {}
local by_name = {}
for _, row in ipairs(rows) do
    local fn = resolve(row)
    if fn then
        local h = {row = row, fn = fn, last = -math.huge}
        handlers[#handlers + 1] = h
        by_name[row.name] = h
    end
end
if #handlers == 0 then
    print("[PZButter] ki5Bundle: off (no recognised handlers)")
    return
end

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

Events.OnPlayerUpdate.Add(Ki5.dispatch)
print("[PZButter] ki5Bundle: " .. #handlers .. " handler(s)")
