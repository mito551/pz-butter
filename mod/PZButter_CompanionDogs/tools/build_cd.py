#!/usr/bin/env python3
"""
Generates the CD_Perf overrides from the installed Companion Dogs files.

    build_cd.py <CompanionDogs/42/media/lua dir> <CD_Perf/42/media/lua dir>

Every replacement is asserted to match exactly once, so a changed original fails loudly.
"""
import sys, os, difflib

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
    write_upstream_patch(p, rel, t)


UPSTREAM = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "upstream")


def write_upstream_patch(orig_path, rel, new_text):
    """Unified diff original -> override, in the original's line endings.
    Apply from the Companion Dogs 42/media/lua folder with: patch -p1 < <file>"""
    raw = open(orig_path, encoding="utf-8", newline="").read()
    eol = "\r\n" if "\r\n" in raw else "\n"
    new_raw = new_text.replace("\r\n", "\n").replace("\n", eol)
    diff = difflib.unified_diff(raw.splitlines(True), new_raw.splitlines(True),
                                "a/" + rel, "b/" + rel)
    name = "perf-" + rel.replace("/", "_") + ".patch"
    os.makedirs(UPSTREAM, exist_ok=True)
    open(os.path.join(UPSTREAM, name), "w", encoding="utf-8", newline="").write("".join(diff))


def built_version():
    for line in open(os.path.join(SRC, "..", "..", "mod.info"), encoding="utf-8"):
        if line.startswith("modversion="):
            return line.split("=", 1)[1].strip()
    raise AssertionError("modversion not found in the original mod.info")


HOOK_HEADER = '''--[[
    CD_Perf build of Companion Dogs. Same behaviour, much less work per tick.
    Regenerate with tools/build_cd.py after every update of Companion Dogs.
]]
do
    local m = getModInfoByID and getModInfoByID("CompanionDogs")
    local v = m and m:getModVersion()
    if v and v ~= "@VERSION@" then
        print("[CD_Perf] WARNING: Companion Dogs is " .. tostring(v) .. ", this override was built from @VERSION@; regenerate CD_Perf with tools/build_cd.py")
    end
end

'''

def threat(rep, between):
    between('    local cell = getCell()\n    if not cell then return {} end\n    local list = {}\n    CD.perfCount(perfKey or "sq.zombies", (2 * R + 1) * (2 * R + 1))\n    for dx = -R, R do',
            '    CD.zbox[id] = { seq = CD.zseq, cx = cx, cy = cy, z = z, R = R, list = list }',
            '    local list = CD.PerfNear.zombies(animal, cx, cy, z, R)\n')
    return HOOK_HEADER.replace("@VERSION@", built_version())


CFG = '''local function cfgOn(key)
    local c = rawget(_G, "CD_PerfPatchConfig")
    return not (type(c) == "table" and c[key] == false)
end
'''


def hunting(rep, between):
    between('    CD.perfCount("sq.wildanimals", (2 * R + 1) * (2 * R + 1))\n    for dx = -R, R do',
            '    return best, count\nend',
            '''    local animals = CD.PerfNear.animals(animal, R)
    for ai = 1, #animals do
        local o = animals[ai]
        if isHuntablePrey(animal, o, classes) then
            local dd = CD.dist2D(animal, o)
            if dd <= radius then
                count = count + 1
                if dd <= bestDist then
                    best, bestDist = o, dd
                end
            end
        end
    end
''')
    return HOOK_HEADER.replace("@VERSION@", built_version())


FINDTROUGH = '''local troughZoneCalls = {}

''' + CFG + '''
local function findTroughNear(animal, radius, wantFood, wantWater)
    local cell = getCell()
    if not cell then return nil end
    local breedKey = CD.getBreed(animal)
    local ax = math.floor(animal:getX())
    local ay = math.floor(animal:getY())
    local az = math.floor(animal:getZ())
    local R = math.ceil(radius)
    local id = animal:getOnlineID()
    local calls = (troughZoneCalls[id] or 0) + 1
    troughZoneCalls[id] = calls
    -- ranch fast path: the engine's own list of the troughs in the dog's animal zones.
    -- Every 3rd search per dog still does the full one, so a closer dish is not missed for long.
    local dzones = cfgOn("troughZone") and calls % 3 ~= 0 and animal:getConnectedDZone()
    if dzones and dzones:size() > 0 then
        local list = animal:getBehavior():getRandomTroughList()
        local best, bestKind, bestDist = nil, nil, radius + 1
        local bx, by, bz
        for i = 0, list:size() - 1 do
            local o = list:get(i)
            if math.floor(o:getZ()) == az then
                local kind
                if wantFood and troughHasFood(o, breedKey) then kind = "food"
                elseif wantWater and troughHasWater(o) then kind = "water" end
                if kind then
                    local dist = CD.dist2D(animal, o)
                    if dist <= radius and dist < bestDist then
                        best, bestKind, bestDist = o, kind, dist
                        bx, by, bz = o:getX(), o:getY(), o:getZ()
                    end
                end
            end
        end
        if best then return best, bestKind, bx, by, bz, false, nil end
    end

    -- troughs and dishes in the original's visiting order (square by square, troughs first),
    -- so equal distances resolve the same way
    local items = CD.PerfNear.feed(animal, R)
    local best, bestKind, bestDist, bestDish, bestItem = nil, nil, radius + 1, false, nil
    local bx, by, bz
    for i = 1, #items do
        local o = items[i]
        if instanceof(o, "IsoFeedingTrough") then
            local kind
            if wantFood and troughHasFood(o, breedKey) then kind = "food"
            elseif wantWater and troughHasWater(o) then kind = "water" end
            if kind then
                local dist = CD.dist2D(animal, o)
                if dist <= radius and dist < bestDist then
                    best, bestKind, bestDist, bestDish, bestItem = o, kind, dist, false, nil
                    bx, by, bz = o:getX(), o:getY(), o:getZ()
                end
            end
        else
            local item = o:getItem()
            if item and CD.isDishType(item:getFullType()) then
                local kind
                if wantFood and dishStock(item, "food") > 0 then kind = "food"
                elseif wantWater and dishStock(item, "water") > 0 then kind = "water" end
                if kind then
                    local dist = CD.dist2D(animal, o)
                    if dist <= radius and dist < bestDist then
                        local sq = o:getSquare()
                        best, bestKind, bestDist, bestDish, bestItem = o, kind, dist, true, item
                        bx, by, bz = sq:getX(), sq:getY(), sq:getZ()
                    end
                end
            end
        end
    end
    if best then return best, bestKind, bx, by, bz, bestDish, bestItem end
    return nil
end

'''


def feed(rep, between):
    between('local function findTroughNear(animal, radius, wantFood, wantWater)',
            'local function walkToFeedTile',
            FINDTROUGH + '\n')
    return HOOK_HEADER.replace("@VERSION@", built_version())


COMPANION_TICK = '''-- Idle gate: with no dog loaded (CD.dogReg.dogs empty) these only walk their own per-dog
-- tables, which then hold nothing live; stale entries are cleaned when a dog loads again.
-- SKIP: processAnimClears, tickFenceHops, tickFenceSteer, tickFollowSteer, tickFenceLandPush, tickLandGlide, tickCareWatch, tickFallGuard, tickGunGuard, tickCompanionFire, tickDenseSync
-- NEVER: tickMountedDogs (mounted dogs may be outside the animal list), tickCarriedGuard (carried dogs are found by id), tickNetAuthority (holds must expire), maintainFollowers + maintainCombat + tickFollowStream + tickLandStream (together they drop dogs that left and tell clients to stop streaming them), teleportbring, eachPlayerSlice (its ghost scan finds unregistered dogs), everything on the COMPANION_TICK_INTERVAL beat

local counter = 0
local followCounter = 0
local function companionTick()
    local t0 = CD.PERF_DEBUG and getTimestampMs() or nil
    CD.zseq = (CD.zseq or 0) + 1
    CD._regCounter = (CD._regCounter or 0) + 1
    if CD._regCounter >= CD.DOG_REG_TICKS then
        CD._regCounter = 0
        CD._regTickDriven = true
        CD.pz("srv.dogreg", CD.rebuildDogRegIfChanged)
    end
    local busy = #CD.dogReg.dogs > 0 or not cfgOn("idleGate")
    if busy then CD.pz("srv.animclears", CD.processAnimClears) end
    CD.pz("srv.mounted", CD.tickMountedDogs)
    if busy then
        CD.pz("srv.fencehop", CD.tickFenceHops)
        CD.pz("srv.fencesteer", CD.tickFenceSteer)
        CD.pz("srv.followsteer", CD.tickFollowSteer)
        CD.pz("srv.fencelandpush", CD.tickFenceLandPush)
        CD.pz("srv.landglide", CD.tickLandGlide)
    end
    CD.pz("srv.landstream", CD.tickLandStream)
    if busy then
        CD.pz("srv.carewatch", CD.tickCareWatch)
        CD.pz("srv.fallguard", CD.tickFallGuard)
        CD.pz("srv.gunguard", CD.tickGunGuard)
        CD.pz("srv.fire", CD.tickCompanionFire)
    end
    CD.pz("srv.teleportbring", eachOnlinePlayer, CD.bringActiveOnTeleport)
    followCounter = followCounter + 1
    if followCounter >= CD.FOLLOW_TICK_INTERVAL then
        followCounter = 0
        CD.pz("srv.followmaint", CD.maintainFollowers)
        CD.pz("srv.combatmaint", CD.maintainCombat)
        CD.pz("srv.carriedguard", CD.tickCarriedGuard)
    end
    CD.pz("srv.netauth", CD.tickNetAuthority)
    if busy then CD.pz("srv.densesync", CD.tickDenseSync) end
    CD.pz("srv.followstream", CD.tickFollowStream)
    CD.pz("srv.companion", CD.eachPlayerSlice, CD.processNearbyDogs, CD.reapPhantomCompanions)
    counter = counter + 1
    if counter < CD.COMPANION_TICK_INTERVAL then
        CD.perfSample(t0)
        return
    end
    counter = 0
    CD.pz("srv.zdmgsweep", CD.zdmgSweep)
    CD.pz("srv.invincible", CD.tickCompanionInvincible)
    CD.pz("srv.vet", CD.tickVet)
    CD.pz("srv.breedapproach", CD.tickBreedingApproach)
    CD.pz("srv.gestation", CD.tickGestation)
    CD.pz("srv.passivebreed", CD.tickPassiveBreeding)
    CD.pz("srv.maturation", CD.tickMaturation)
    CD.pz("srv.prunefire", CD.pruneFireState)
    CD.pz("srv.adoptorphans", CD.tickAdoptOrphans)
    CD.pz("srv.repairowner", CD.repairOwnership)
    CD.pz("srv.splitidentity", CD.repairSplitIdentity)
    CD.pz("srv.reclaimkennel", eachOnlinePlayer, CD.reclaimKennelFromGlobal)
    CD.pz("srv.recoverstash", CD.recoverStashedDogs)
    CD.pz("srv.recoverofflinestash", CD.recoverOfflineStashes)
    CD.pz("srv.recovercarried", CD.recoverCarriedDogs)
    CD.pz("srv.recovertrailer", CD.recoverTraileredDogs)
    CD.pz("srv.reapclones", CD.reapClonesCellWide)
    CD.pz("srv.caretaker", CD.tickCareDogs, worldMinutes())
    CD.perfSample(t0)
end
'''


def companion(rep, between):
    between('local counter = 0\nlocal followCounter = 0\nlocal function companionTick()',
            'if not isClient() then',
            COMPANION_TICK + '\n')
    # inject cfgOn helper
    rep('CompanionDogs = CompanionDogs or {}\nlocal CD = CompanionDogs\n',
        'CompanionDogs = CompanionDogs or {}\nlocal CD = CompanionDogs\n' + CFG, 1)
    return HOOK_HEADER.replace("@VERSION@", built_version())


edit("server/CompanionDogs/combat/Threat.lua", threat)
edit("server/systems/CompanionDogs_Hunting.lua", hunting)
edit("server/systems/CompanionDogs_Feed.lua", feed)
edit("server/CompanionDogs_Companion.lua", companion)
