-- PZ Butter fix: Project A-Life NPCs (modversion 1.5.x). Load-after patches on ProjectALife globals.
-- Every patch is a pcall'd, feature-detected, single-purpose swap with its own tick box; none may throw.
local ID = "PZButter_ProjectALifeNPCs"
PZB_ALIFE = PZB_ALIFE or { applied = {} }
local M = PZB_ALIFE

-- name -> { label, default, apply }. Defaults are on only for patches that keep behaviour identical.
local items = {}
local order = {}
local function item(name, label, default, apply)
    items[name] = { label = label, default = default, apply = apply }
    order[#order + 1] = name
end

local options
local function on(name)
    local o = options and options:getOption(name)
    if o then return o:getValue() == true end
    return items[name].default
end

-- P1. DrivingSeats.guardCollision runs for EVERY zombie EVERY frame; collisionSeat calls getModData(), which
-- creates a table on a vanilla zombie that has none. collisionSeat returns nothing without ProjectALifeOwned in
-- modData, and S.record() needs S.records[npc], so skipping zombies without modData is output-identical.
item("seatGuardSkip", "Skip seat-collision guard for zombies without mod data", true, function()
    local S = ProjectALife.DrivingSeats
    local orig = S and S.guardCollision
    if type(orig) ~= "function" or not (Events and Events.OnZombieUpdate and Events.OnZombieUpdate.Remove) then return false end
    local records = S.records
    local function guard(npc)
        if records[npc] == nil then
            local has = npc.hasModData
            if has ~= nil and not has(npc) then return end
        end
        return orig(npc)
    end
    S.guardCollision = guard
    Events.OnZombieUpdate.Remove(orig)
    Events.OnZombieUpdate.Add(guard)
    return true
end)

-- P2. Handlers whose whole body is "if now < nextAtMs then return": register them with `every` so the
-- dispatcher skips the Lua call, the clock read and the closure on most frames. The module's own timer still
-- decides the real cadence; the added latency is at most (every-1) frames. frames = at 60 fps.
local gates = {
    { "OnTick", "ALifeCombatKnee", 4 },       -- sweepMs 250
    { "OnTick", "ALifeCombatLean", 4 },       -- sweepMs 250
    { "OnTick", "ALifeStanceDriver", 4 },     -- sweepMs 250
    { "OnTick", "ALifeFlankPlan", 4 },        -- sweepMs 250
    { "OnTick", "ALifePushPlan", 8 },         -- sweepMs 1000
    { "OnTick", "ALifeSupport", 8 },          -- nextIntelAtMs +1000
}
item("gateEvery", "Thin out timer-gated per-frame handlers", true, function()
    local H = ProjectALife.Handlers
    if not (H and H.byName and H.add) then return false end
    local n = 0
    for _, g in ipairs(gates) do
        local e = H.byName[g[1] .. ":" .. g[2]]
        if e and e.every == 1 and type(e.fn) == "function" then
            H.add(g[1], g[2], e.fn, { safe = e.safe, live = e.live, label = e.label, every = g[3] })
            n = n + 1
        end
    end
    M.gated = n
    return n > 0
end)

-- P3. CorpseSkin.onTick is registered straight on Events.OnTick (no dispatcher) and its two timers are 2 s / 30 s.
item("corpseSkinEvery", "Run corpse-skin tick through the dispatcher at 1/15 frames", true, function()
    local Skin, H = ProjectALife.CorpseSkin, ProjectALife.Handlers
    if not (Skin and type(Skin.onTick) == "function" and H and H.add and Events and Events.OnTick.Remove) then return false end
    Events.OnTick.Remove(Skin.onTick)
    H.add("OnTick", "PZButterCorpseSkin", Skin.onTick, { every = 15 })
    return true
end)

local function build()
    if not (PZAPI and PZAPI.ModOptions and PZAPI.ModOptions.create) then return end
    local ok, o = pcall(PZAPI.ModOptions.create, PZAPI.ModOptions, ID, "PZ Butter: Project A-Life NPCs")
    if not ok or not o then return end
    options = o
    for _, name in ipairs(order) do
        local it = items[name]
        o:addTickBox(name, it.label, it.default, "Takes effect at the next game start.")
    end
end

local function applyAll()
    if M.done or not ProjectALife then return end
    M.done = true
    for _, name in ipairs(order) do
        if on(name) then
            local ok, res = pcall(items[name].apply)
            M.applied[name] = ok and res == true
            print(string.format("[PZButter_ProjectALifeNPCs] %s: %s", name, ok and (res and "applied" or "target not found") or ("error " .. tostring(res))))
        end
    end
end

pcall(build)
if Events and Events.OnGameStart then Events.OnGameStart.Add(applyAll) end
if Events and Events.OnServerStarted then Events.OnServerStarted.Add(applyAll) end
