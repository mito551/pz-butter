-- KI5 handler bundle: one OnPlayerUpdate dispatcher for recognised vehicle handlers.
-- The table is data from tools/ki5scan.py; this file never parses mod code.
-- PZButter_KI5 (fix mod): enabling the mod is the switch. Every exit says why: the 2026-11-05 A/B ran with no
-- line at all and measured nothing.
if not PZButter then return end
if not PZButter.Census then print("[PZButter_KI5] off (needs handlerCensus=true in PZButter.properties)") return end
if isServer and isServer() then return end
if _G.PZButterKi5Installed then return end
_G.PZButterKi5Installed = true

local Ki5 = {}
PZButter.Ki5 = Ki5
-- read at install: "Ki5.lua" loads before "ki5table.lua" (byte order puts K before k)
local rows = {}
local syncMs = 100   -- shortest gap between two sync calls while the vehicle, type and seat stay the same
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
local anyEvery = false   -- set at install: is any taken-over handler of kind "every"

-- split screen: each local player has its own vehicle snapshot and throttle state
local pstates = {}
local NEVER = -math.huge

-- The handlers that can run for one vehicle type, worked out once per type instead of on every
-- dispatch. Key: false on foot, true in a vehicle without a script name, else the script name.
-- A slot also carries the error pause of its handler for that type.
local lists = {}

local function slots_for(vehicle, vtype)
    local key = vtype or (vehicle ~= nil)
    local list = lists[key]
    if list then return list end
    list = {}
    for _, h in ipairs(handlers) do
        local row = h.row
        local typed = row.types and #row.types > 0
        local fits
        if not vehicle then
            fits = row.onFoot ~= "none" and not typed
        elseif not typed then
            fits = true
        else
            fits = false
            if vtype then
                for _, t in ipairs(row.types) do
                    if string.find(vtype, t, 1, true) then fits = true; break end
                end
            end
        end
        if fits then list[#list + 1] = {h = h, sync = row.kind == "sync"} end
    end
    lists[key] = list
    return list
end

local function failed(slot, now, err)
    local n = (slot.fails or 0) + 1
    slot.fails = n
    slot.paused = now + (n == 1 and 1000 or (n == 2 and 5000 or 30000))
    if n == 1 then
        print("[PZButter_KI5] " .. tostring(slot.h.row.name) .. " error: " .. tostring(err))
    end
end

function Ki5.dispatch(player)
    local now = Ki5.now()
    local vehicle = player and player:getVehicle() or nil
    local vtype, seat
    if vehicle then vtype, seat = vehicle:getScriptName(), vehicle:getSeat(player) end
    -- stub players in the tests (and some mods) carry no getPlayerNum; slot 0 covers them
    local idx = player and player.getPlayerNum and player:getPlayerNum() or 0
    local ps = pstates[idx]
    if not ps then
        ps = {vehicle = vehicle, vtype = vtype, seat = seat, last = {}, list = slots_for(vehicle, vtype)}
        pstates[idx] = ps
    elseif vehicle ~= ps.vehicle or vtype ~= ps.vtype or seat ~= ps.seat then
        -- a new vehicle, type or seat: every sync handler may run at once again
        ps.vehicle, ps.vtype, ps.seat = vehicle, vtype, seat
        ps.last, ps.list = {}, slots_for(vehicle, vtype)
    end
    if anyEvery and PZButter.ki5EveryStart then PZButter.ki5EveryStart() end
    local list, last = ps.list, ps.last
    for i = 1, #list do
        local slot = list[i]
        local paused = slot.paused
        if not (paused and now < paused) then
            local h = slot.h
            -- a sync-only handler: at most every syncMs while vehicle, type and seat stay the same
            if not slot.sync or now - (last[h] or NEVER) >= syncMs then
                local ok, err = pcall(h.fn, player)
                last[h] = now
                if ok then
                    if paused then slot.paused, slot.fails = nil, nil end
                else
                    failed(slot, now, err)
                end
            end
        end
    end
    if anyEvery then
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
        -- an "unchecked" row is no verified vehicle handler; taking it over (the resolve below)
        -- would change other mods' stock behaviour (pausing a foreign handler on its errors)
        if row.kind ~= "unchecked" then
            local fn = resolve(row)
            if fn then
                local h = {row = row, fn = fn}
                handlers[#handlers + 1] = h
                by_name[row.name] = h
                if row.kind == "every" then anyEvery = true end
            end
        end
    end
    if #handlers == 0 then
        local entries = PZButter.Census.list("OnPlayerUpdate")
        print("[PZButter_KI5] off (no recognised handlers; " .. #rows .. " rows, "
            .. #entries .. " OnPlayerUpdate handlers in the census)")
        -- why: the census entries in each row's file (a line, count or owner that differs explains the miss)
        local shown = 0
        for _, row in ipairs(rows) do
            if shown >= 5 then break end
            local _, file = parse_id(row.id)
            for _, e in ipairs(entries) do
                local actual = normalize(e.file)
                if file and actual and (actual == file or actual:sub(-(#file + 1)) == "/" .. file) then
                    print("[PZButter_KI5] row " .. row.id .. " vs census line " .. tostring(e.line)
                        .. ", count " .. tostring(e.count) .. ", owner " .. tostring(e.owner))
                    shown = shown + 1
                end
            end
        end
        if shown == 0 then print("[PZButter_KI5] no census entry is in any row's file") end
        return
    end
    Events.OnPlayerUpdate.Add(Ki5.dispatch)
    print("[PZButter_KI5] " .. #handlers .. " handler(s)")
end
Events.OnGameStart.Add(Ki5.install)
