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

local handlers = {}   -- Ki5.install() appends one {row = row, fn = fn} per taken-over handler
local by_name = {}
local anyEvery = false   -- set at install: is any taken-over handler of kind "every"
local errs = {}    -- tkey -> handler -> {n, till}; shared by reference into plan nodes
-- split screen: each local player has its own plan. A plan is the chain of the handlers that fit
-- the player's vehicle type, built when the vehicle, type or seat changes instead of filtering every
-- handler on every dispatch; a fresh chain also lets every sync handler run at once again.
local plans = {}   -- player index -> plan (head object carrying its own key + chain)
local FOOT, NOVT = "\0foot", "\0nil"

local function errRec(tkey, h)
    local t = errs[tkey]
    if not t then t = {} errs[tkey] = t end
    local r = t[h]
    if not r then r = {n = 0, till = -1e300} t[h] = r end
    return r
end

local function build(veh, vtype, seat)
    local plan = {veh = veh, vtype = vtype, seat = seat}
    local tkey = (not veh) and FOOT or vtype or NOVT
    local tail = plan
    for i = 1, #handlers do
        local h = handlers[i]
        local row = h.row
        local types = row.types or {}
        local ok
        if not veh then
            ok = row.onFoot ~= "none" and #types == 0
        elseif #types == 0 then
            ok = true
        else
            ok = false
            if vtype then
                for j = 1, #types do
                    if string.find(vtype, types[j], 1, true) then ok = true break end
                end
            end
        end
        if ok then
            local node = {h = h, sync = row.kind == "sync", e = errRec(tkey, h)}
            tail.nxt = node
            tail = node
        end
    end
    return plan
end

function Ki5.dispatch(player)
    if not player then return end
    local now = Ki5.now()
    local veh = player:getVehicle()
    local vtype, seat
    if veh then
        vtype = veh:getScriptName()
        seat = veh:getSeat(player)
    end
    -- stub players in the tests (and some mods) carry no getPlayerNum; slot 0 covers them
    local pi = player.getPlayerNum and player:getPlayerNum() or 0
    local plan = plans[pi]
    if not plan or plan.veh ~= veh or plan.vtype ~= vtype or plan.seat ~= seat then
        plan = build(veh, vtype, seat)
        plans[pi] = plan
    end
    local P = PZButter
    if anyEvery and P.ki5EveryStart then P.ki5EveryStart() end
    local node = plan.nxt
    while node do
        local e = node.e
        if now >= e.till then
            local last = node.last
            if not (node.sync and last and now - last < syncMs) then
                if node.sync then node.last = now end
                local h = node.h
                local ok, err = pcall(h.fn, player)
                if ok then
                    if e.n > 0 then e.n = 0 e.till = -1e300 end
                else
                    local n = e.n + 1
                    e.n = n
                    e.till = now + (n == 1 and 1000 or n == 2 and 5000 or 30000)
                    if n == 1 then
                        print("[PZButter_KI5] " .. tostring(h.row.name) .. " error: " .. tostring(err))
                    end
                end
            end
        end
        node = node.nxt
    end
    if anyEvery then
        if P.ki5EveryStop then P.ki5EveryStop() end
        if P.ki5Flush then P.ki5Flush() end
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
