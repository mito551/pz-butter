--[[
    CD_Perf build of Companion Dogs. Same behaviour, much less work per tick.
    Regenerate with tools/build_cd.py after every update of Companion Dogs.
]]
do
    local m = getModInfoByID and getModInfoByID("CompanionDogs")
    local v = m and m:getModVersion()
    if v and v ~= "0.7.4" then
        print("[CD_Perf] WARNING: Companion Dogs is " .. tostring(v) .. ", this override was built from 0.7.4; regenerate CD_Perf with tools/build_cd.py")
    end
end

CompanionDogs = CompanionDogs or {}
local CD = CompanionDogs
local function cfgOn(key)
    local c = rawget(_G, "CD_PerfPatchConfig")
    return not (type(c) == "table" and c[key] == false)
end

local function worldMinutes()
    return getGameTime():getWorldAgeHours() * 60
end

local function setNeed(animal, kind, value)
    local stats = animal:getStats()
    if kind == "thirst" then
        stats:set(CharacterStat.THIRST, value)
    else
        local d = CD.data(animal)
        if d.gunHungerSave ~= nil then
            d.gunHungerSave = value
        end
        stats:set(CharacterStat.HUNGER, value)
    end
end

-- Sem destinatario todo client ouve todo cao do mapa, e cao que o client nao atualiza toca em 2D (emissor em 0,0).
local function emitSound(animal, sound)
    local args = { id = animal:getOnlineID(), sound = sound }
    if not isServer() then
        CD.emit("sound", args, animal)
        return
    end
    local range = CD.soundRange(sound) + CD.SOUND_RANGE_MARGIN
    local ax, ay = animal:getX(), animal:getY()
    local players = getOnlinePlayers()
    local np = players:size()
    local sent = 0
    for i = 0, np - 1 do
        local p = players:get(i)
        if math.abs(p:getX() - ax) <= range and math.abs(p:getY() - ay) <= range then
            sendServerCommand(p, CD.MODULE, "sound", args)
            sent = sent + 1
        end
    end
    CD.perfCount("net.sound", sent)
    if CD.SOUND_DEBUG then
        CD.log(string.format("[CD-SND] %s cao=%s em=(%.0f,%.0f) alcance=%d destinos=%d/%d",
            tostring(sound), tostring(args.id), ax, ay, range, sent, np))
    end
end
CD.emitSound = emitSound

local function emitStopSound(animal, sound)
    CD.emit("stopsound", { id = animal:getOnlineID(), sound = sound }, animal)
end
CD.emitStopSound = emitStopSound

local function eachOnlinePlayer(fn)
    if isServer() then
        local players = getOnlinePlayers()
        local np = players:size()
        CD.perfCount("iter.eachplayer", np)
        for i = 0, np - 1 do fn(players:get(i)) end
    else
        CD.eachLocalPlayer(fn)
    end
end
CD.eachOnlinePlayer = eachOnlinePlayer

local function applyOfflineDebt(owner, elapsed)
    if not owner or elapsed <= 0 then return elapsed end
    local pd = CD.playerData(owner)
    local debt = pd.offlineDebtMin or 0
    if debt <= 0 then return elapsed end
    local use = debt < elapsed and debt or elapsed
    pd.offlineDebtMin = debt - use
    return elapsed - use
end
CD.applyOfflineDebt = applyOfflineDebt

-- Reafirmar todo tick: a flag de blockMovement se limpa sozinha depois de ~8000 game-units.
local function holdStill(animal)
    local b = animal:getBehavior()
    if b then
        b:setBlockMovement(true)
    end
end

local function letMove(animal)
    CD.clearIdleVariation(animal)
    local b = animal:getBehavior()
    if b then
        b:setBlockMovement(false)
    end
    animal:setDeferredMovementEnabled(true)
end

local function forcePathfind(animal)
    if animal then
        animal:setVariable("bPathfind", true)
    end
end

-- Alias em tempo de carga: quem define e quem publica, senao esta linha le um global nil.
CD.letMove, CD.forcePathfind = letMove, forcePathfind

CD.branchLast = CD.branchLast or {}
---Debug log of the decision branch taken this tick; the same branch repeats at most every DEBUG_BRANCH_BEAT_MS.
---@param animal IsoAnimal
---@param d table companion ModData, unused
---@param name string
CD.branch = function(animal, d, name)
    if not CD.DEBUG_BRANCH then return end
    local id = animal:getOnlineID()
    local now = getTimestampMs()
    local last = CD.branchLast[id]
    if last and last.name == name then
        local age = now - last.ms
        if age >= 0 and age < CD.DEBUG_BRANCH_BEAT_MS then return end
    end
    local repeated = last and last.name == name
    CD.branchLast[id] = { name = name, ms = now }
    CD.log(string.format("[CD-BRANCH] %s%s  cao=%s estado=%s pos(%.1f,%.1f)",
        tostring(name), repeated and " (segue)" or "", tostring(id), tostring(CD.getState(animal)),
        animal:getX(), animal:getY()))
end

local dbgPathLast = {}
local dbgQuietLast = {}
---Debug log of a walk step that issued no new path; at most one line per DEBUG_PATH_QUIET_MS per dog.
---@param animal IsoAnimal
---@param tag string
---@param x number target tile
---@param y number
---@param reason string|nil
CD.logPathQuiet = function(animal, tag, x, y, reason)
    if not CD.DEBUG_PATH then return end
    local id = animal:getOnlineID()
    local now = getTimestampMs()
    if dbgQuietLast[id] and (now - dbgQuietLast[id]) < CD.DEBUG_PATH_QUIET_MS then return end
    dbgQuietLast[id] = now
    CD.log(string.format("[CD-PATH] %-14s NAO emitiu (%s) alvo(%d,%d) pos(%.1f,%.1f) cao=%s",
        tostring(tag), tostring(reason), x, y, animal:getX(), animal:getY(), tostring(id)))
end

---Debug log of an issued path: target, time since the dog's previous one and the end of the engine path.
---@param animal IsoAnimal
---@param tag string
---@param x number target tile
---@param y number
---@param reason string|nil
CD.logPath = function(animal, tag, x, y, reason)
    if not CD.DEBUG_PATH then return end
    local id = animal:getOnlineID()
    local now = getTimestampMs()
    local dt = dbgPathLast[id] and (now - dbgPathLast[id]) or -1
    dbgPathLast[id] = now
    local ex, ey = "-", "-"
    local b = animal:getPathFindBehavior2()
    if b then
        ex, ey = math.floor(b:getTargetX()), math.floor(b:getTargetY())
    end
    CD.log(string.format("[CD-PATH] %-14s alvo(%d,%d) dt=%dms eng(%s,%s) pos(%.1f,%.1f) cao=%s%s",
        tostring(tag), x, y, dt, tostring(ex), tostring(ey), animal:getX(), animal:getY(), tostring(id),
        reason and ("  <- " .. reason) or ""))
end

local function moveTo(animal, running, speed, x, y, z, why, reason, system)
    letMove(animal)
    if running and not CD.mayRun(animal) then
        running = false
        speed = CD.walkAnimSpeed()
    end
    if animal then
        animal:setVariable("animalRunning", running)
        animal:setVariable("animalSpeed", speed)
        CD.applyGaitScale(animal)
        CD.pathStatEmit(animal, system or "moveTo")
        animal:pathToLocation(x, y, z)
    end
    forcePathfind(animal)
    if CD.DEBUG_PATH then
        CD.logPath(animal, why or "moveTo", x, y, reason)
    end
end

local fenceHopping = {}
local landStreaming = {}
CD.landStreaming = landStreaming
local landGlide = {}
CD.landGlide = landGlide
CD.fenceHopping, CD.landGlide = fenceHopping, landGlide

-- Tabelas compartilhadas com systems/ por alias: nunca rebindar (`t = {}`), so esvaziar no lugar.
local combatTarget = {}
CD.combatTarget = combatTarget

local mountedDogs = {}
CD.mountedDogs = mountedDogs

local followMaintainDogs = {}
CD.followMaintainDogs = followMaintainDogs

local combatMaintainDogs = {}
CD.combatMaintainDogs = combatMaintainDogs

local trailerRecords = {}
CD.trailerRecords = trailerRecords

---@param player IsoPlayer|nil
---@param tok number|nil companion token
---@return boolean true when a trailer record holds the player's dog with this token
---@nodiscard
CD.tokenTrailered = function(player, tok)
    if tok == nil or player == nil then return false end
    local key = CD.ownerKey(player)
    for _, rec in pairs(trailerRecords) do
        if rec.data and rec.data.companionToken == tok then
            local mine = false
            if rec.ownerKey ~= nil and rec.ownerKey ~= "" then
                mine = rec.ownerKey == key
            elseif rec.ownerName ~= nil and rec.ownerName ~= "" then
                mine = rec.ownerName == player:getUsername()
            end
            if mine then return true end
        end
    end
    return false
end

do
    local nextRoll = {}
    ---Past STRAY_SCAVENGE_TRIGGER a stray finds food or water on an hourly roll; past STRAY_SCAVENGE_FORCE it always does.
    ---@param a IsoAnimal
    function CD.strayScavenge(a)
        local h = a:getHunger()
        local t = a:getThirst()
        local trig = CD.STRAY_SCAVENGE_TRIGGER
        if h < trig and t < trig then return end
        local force = CD.STRAY_SCAVENGE_FORCE
        local ate = h >= force
        local drank = t >= force
        if not (ate and drank) then
            local id = a:getOnlineID()
            if id ~= -1 then
                local now = getGameTime():getWorldAgeHours()
                local nxt = nextRoll[id]
                if nxt == nil or now >= nxt then
                    nextRoll[id] = now + 1
                    if nxt ~= nil and ZombRand(100) < CD.STRAY_SCAVENGE_CHANCE then
                        ate = ate or h >= trig
                        drank = drank or t >= trig
                    end
                end
            end
        end
        local lvl = CD.STRAY_SCAVENGE_LEVEL
        if ate then
            setNeed(a, "hunger", lvl)
        end
        if drank then
            setNeed(a, "thirst", lvl)
        end
        if ate then CD.pulseEatAnim(a, nil, "auto-feed")
        elseif drank then CD.pulseDrinkAnim(a, nil, "auto-feed") end
    end
    ---@param present table set of the online IDs still loaded; the others lose their roll timer
    function CD.strayScavengePrune(present)
        for id in pairs(nextRoll) do
            if not present[id] then
                nextRoll[id] = nil
            end
        end
    end
end

local activeDogSnapshot = {}
CD.activeDogSnapshot = activeDogSnapshot
local phantomLostSince = {}
CD.phantomLostSince = phantomLostSince

local function cdDeepCopy(t)
    if type(t) ~= "table" then return t end
    local r = {}
    for k, v in pairs(t) do r[k] = cdDeepCopy(v) end
    return r
end
-- CD.cdDeepCopy, nunca CD.deepCopy: esse nome ja existe no shared e seria clobberado.
CD.cdDeepCopy = cdDeepCopy

local function bumpCanonical(owner, dog)
    if not owner or not dog then return end
    local u = CD.ensureUid(dog)
    if not u then return end
    local pd = CD.playerData(owner)
    pd.uidGen = pd.uidGen or {}
    pd.uidGen[u] = (pd.uidGen[u] or 0) + 1
    CD.data(dog).gen = pd.uidGen[u]
    owner:transmitModData()
    CD.transmit(dog)
end
CD.bumpCanonical = bumpCanonical

---Keeps the state saved in the player's active dog snapshot in step with the live dog.
---@param player IsoPlayer|nil
---@param animal IsoAnimal|nil
---@param state string CD.STATE_FOLLOW, CD.STATE_STAY or CD.STATE_GUARD
function CD.stampActiveSnapshotState(player, animal, state)
    if not player or not animal then return end
    if CD.data(animal).companionToken ~= CD.playerData(player).token then return end
    local snap = activeDogSnapshot[player:getOnlineID()]
    if snap and snap.data then
        snap.data.state = state
    end
end

local function reapDogState(id)
    if id == nil then return end
    landGlide[id] = nil
    local e = CD.pendingClears.eat[id]
    if e and e.sound then
        emitStopSound(e.animal, e.sound)
    end
    CD.pendingClears.eat[id] = nil
    CD.pendingClears.idle[id] = nil
    CD.lastAttackVar[id] = nil
    CD.idleVarNext[id] = nil
    CD.lastIdleVar[id] = nil
    CD.restState[id] = nil
    CD.restPose[id] = nil
    CD.calmSince[id] = nil
    CD.restBeat[id] = nil
    combatTarget[id] = nil
    CD.fireBaseline[id] = nil
    CD.fireSafeTile[id] = nil
    CD.gunGuarded[id] = nil
    CD.gunGuardUntil[id] = nil
    mountedDogs[id] = nil
    followMaintainDogs[id] = nil
    combatMaintainDogs[id] = nil
    if CD.netAuthHeldMap[id] then
        CD.netAuthRevoke(id, "reap")
    end
    fenceHopping[id] = nil
    CD.actMs[id] = nil
    CD.zbox[id] = nil
    CD.combatUnreach[id] = nil
    CD.sniffCursor[id] = nil
    CD.sentinelWasAlarm[id] = nil
end
CD.reapDogState = reapDogState

CD.setNeed, CD.emitSound, CD.emitAnim, CD.emitStopSound = setNeed, emitSound, CD.emitAnim, emitStopSound
CD.holdStill, CD.moveTo, CD.combatTarget = holdStill, moveTo, combatTarget

local function returnToAnchor(animal, d, ax, ay, az)
    ax = ax or d.guardX
    ay = ay or d.guardY
    az = az or d.guardZ
    if not ax then return end
    local dx = animal:getX() - ax
    local dy = animal:getY() - ay
    if (dx * dx + dy * dy) > (CD.GUARD_RETURN_DIST * CD.GUARD_RETURN_DIST) then
        CD.standUp(animal)
        animal:setVariable("animalSpeed", 1.0)
        animal:setVariable("animalRunning", false)
        letMove(animal)
        CD.pathStatEmit(animal, "guarda")
        animal:pathToLocation(math.floor(ax), math.floor(ay), math.floor(az or animal:getZ()))
        forcePathfind(animal)
    else
        holdStill(animal)
        CD.updateRest(animal, d)
    end
end

local function companionGuards(animal, owner)
    local d = CD.data(animal)

    if d.fenceHop then
        local hid = animal:getOnlineID()
        if combatMaintainDogs[hid] then combatMaintainDogs[hid] = animal
        else followMaintainDogs[hid] = animal end
        animal:setIsInvincible(true)
        CD.branch(animal, d, "pulo de cerca")
        return true
    end

    followMaintainDogs[animal:getOnlineID()] = nil
    combatMaintainDogs[animal:getOnlineID()] = nil

    if d.careMode then
        CD.branch(animal, d, "cuidador de fazenda (outro loop)")
        return true
    end

    animal:setShootable(false)

    if not d.skills then
        CD.restoreFromKennel(owner, animal)
        CD.initSkills(animal)
    end

    if CD.upkeepEnabled() then
        CD.pzd("dog.upkeep", CD.updateUpkeep, animal, owner, d)
    end

    CD.pzd("dog.colliecalm", CD.collieCalmPass, animal, d)

    if not d.lastKennelMin or (worldMinutes() - d.lastKennelMin) >= CD.KENNEL_MIRROR_MIN then
        d.lastKennelMin = worldMinutes()
        CD.kennelSnapshot(owner, animal)
    end
    return false, d
end

local function companionMountedStash(animal, owner, d, mounted)
    if mounted and CD.getState(animal) == CD.STATE_FOLLOW then
        if d.autoFeeding then
            CD.setAutoFeedTag(animal, d, nil)
        end
        CD.setInCombat(animal, d, false)
        d.retreating = false
        d.alertTier = nil
        if not d.mounted then
            d.mounted = true
            CD.transmit(animal)
        end
        mountedDogs[animal:getOnlineID()] = animal
        if owner and not CD.playerData(owner).stash then
            CD.writeStashRecord(animal, owner)
        end
        CD.branch(animal, d, "dono em veiculo (guardado)")
        return true
    end
    return false
end

local function companionStayPass(animal, owner, d, id, state, protecting)
    if state == CD.STATE_STAY and not protecting then
        if CD.huntEnabled() and CD.huntAutonomyEnabled() and CD.getHuntMode(animal)
            and CD.getSkillLevel(animal, "hunt") >= CD.huntAutonomyLevel()
            and CD.pzd("dog.hunt", CD.tryHunt, animal, owner, d) then
            CD.branch(animal, d, "caca autonoma (em Ficar)")
            CD.standUp(animal)
            return true
        end
        if CD.pzd("dog.distract", CD.tryDistract, animal, owner, d) then
            CD.branch(animal, d, "distraido (em Ficar)")
            CD.standUp(animal)
            return true
        end
        CD.branch(animal, d, "parado (Ficar)")
        if d.inCombat or d.retreating or d.combatHold or combatTarget[id] then
            CD.stopCombat(animal, d)
        end
        if animal:isAnimalMoving() then
            animal:setVariable("animalRunning", false)
            animal:setVariable("animalSpeed", 0)
            animal:stopAllMovementNow()
        end
        holdStill(animal)
        CD.updateRest(animal, d)
        return true
    end
    return false
end

local function combatEngage(animal, owner, d, id, engageZ, commanded, guarding)
    if engageZ then
        CD.standUp(animal)
        d.combatFollowVault = not guarding
        if not d.inCombat then
            CD.setInCombat(animal, d, true)
            CD.bark(animal)
        end

        local base = d.maxHealth or animal:getHealth()
        local lowHealth = animal:getHealth() <= CD.retreatHealthFrac() * base
        local dwellOver = CD.cooledDown(animal, "retreat", CD.RETREAT_MIN_DURATION_MS)
        local retreatNow = lowHealth or (d.retreating and not dwellOver)
        if retreatNow then
            if commanded then
                CD.clearAttack(animal, d)
            end
            combatTarget[id] = nil
            d.cstall = nil
            if not d.retreating then
                d.retreating = true
                CD.bark(animal)
                CD.stamp(animal, "retreat")
            end
            d.combatMode = "retreat"
            combatMaintainDogs[id] = animal
            CD.retreat(animal, owner, engageZ)
            return true
        end
        d.retreating = false

        local gap = CD.dist2D(animal, engageZ)
        local blocked = CD.pathBlocked(animal, engageZ)
        animal:setAttackedBy(nil)
        animal:setDebugStress(0)
        animal:faceThisObject(engageZ)
        if not guarding and CD.tryCombatVault(animal, engageZ, d, getTimestampMs()) then
            d.combatMode = "approach"
            combatMaintainDogs[id] = animal
            CD.branch(animal, d, "combate-pulo-cerca")
            return true
        end
        if gap > CD.STRIKE_DIST or blocked then
            local nowMs = getTimestampMs()
            local st = d.cstall
            if not st then
                st = { bg = gap, ax = animal:getX(), ay = animal:getY(), sfw = 0 }
                d.cstall = st
            end
            local net = math.abs(animal:getX() - st.ax) + math.abs(animal:getY() - st.ay)
            local gained = gap + 0.2 < st.bg
            if gained then
                st.bg = gap
            end
            if gained or net > CD.COMBAT_STALL_RADIUS then
                st.ax, st.ay = animal:getX(), animal:getY()
                st.ms = nil
                st.sfw = 0
            else
                st.ms = st.ms or nowMs
                local sfw = animal:shouldFollowWall()
                st.sfw = sfw and (st.sfw + 1) or 0
            end
            if st.sfw >= 2 or (st.ms and (nowMs - st.ms) > CD.COMBAT_STALL_MS) then
                local unr = CD.combatUnreach[id]
                if not unr then
                    unr = {}
                    CD.combatUnreach[id] = unr
                end
                unr[engageZ] = nowMs + CD.COMBAT_UNREACH_MS
                combatTarget[id] = nil
                d.cstall = nil
                d.combatMode = nil
                d.combatHold = nil
                combatMaintainDogs[id] = nil
                CD.setInCombat(animal, d, false)
                if commanded then
                    CD.clearAttack(animal, d)
                    CD.notifyOwner(owner, "notarget", { name = d.name, breed = CD.getBreed(animal) })
                end
                engageZ = nil
            end
        else
            d.cstall = nil
        end
        if CD.DMG_DEBUG and engageZ then
            if animal then
                local strike = not (gap > CD.STRIKE_DIST or blocked)
                local nowMs = getTimestampMs()
                if d.dmgBeatStrike ~= strike or not d.dmgBeatMs or (nowMs - d.dmgBeatMs) >= CD.DMG_BEAT_MS then
                    d.dmgBeatMs, d.dmgBeatStrike = nowMs, strike
                    local ex, ey = -1, -1
                    local b = animal:getPathFindBehavior2()
                    if b then
                        ex, ey = b:getTargetX(), b:getTargetY()
                    end
                    CD.log(string.format("[CD-DMG] pass cao=%s gap=%.2f golpe=%s bloq=%s modo=%s seq=%d eng=(%.1f,%.1f) pos=(%.1f,%.1f) z=(%.1f,%.1f)",
                        tostring(id), gap, strike and "SIM" or "NAO", blocked and "S" or "N",
                        tostring(d.combatMode or "-"), d.combatHijacks or 0, ex, ey,
                        animal:getX(), animal:getY(), engageZ:getX(), engageZ:getY()))
                end
            end
        end
        if engageZ then
            if gap > CD.STRIKE_DIST or blocked then
                d.combatHold = nil
                if animal then
                    local run = CD.mayRunFrom(d)
                    animal:setVariable("animalSpeed", run and CD.runAnimSpeed() or CD.walkAnimSpeed())
                    animal:setVariable("animalRunning", run)
                    -- Primeira entrada no node de corrida da perseguicao: e aqui que a escala e
                    -- lida. Sem carimbar antes, o cao larga na escala cheia que o upkeep deixou e
                    -- o maintainer so consegue corrigir na proxima parada.
                    CD.applyGaitScale(animal, CD.combatRunScale(), nil, d)
                    letMove(animal)
                    CD.pathStatEmit(animal, "combate")
                    animal:pathToCharacter(engageZ)
                    forcePathfind(animal)
                end
            else
                animal:setVariable("animalRunning", false)
                animal:setVariable("animalSpeed", 0)
                animal:stopAllMovementNow()
                d.combatHold = true
                holdStill(animal)
                CD.strikeExchange(animal, engageZ)
            end
            d.combatMode = "approach"
            combatMaintainDogs[id] = animal
            CD.branch(animal, d, "combate")
            return true
        end
    end
    return false
end

local function combatPass(animal, owner, d, id, canFight, guarding, realGuard)
    if canFight then
        local engageRadius = guarding and CD.GUARD_RADIUS or CD.ZOMBIE_ATTACK_RADIUS
        local engageRef = realGuard and animal or owner
        if d.inCombat or d.retreating or guarding or combatTarget[id]
            or (d.attackUntilMin and worldMinutes() < d.attackUntilMin)
            or CD.playerFighting(owner) then
            CD.scanAlert(d)
        end
        local nearest, count, threatensUs = nil, 0, false
        if not CD.scanQuietSkip(d) then
            nearest, count, threatensUs = CD.scanZombies(animal, engageRadius, owner, engageRef, not guarding)
            CD.scanQuietResult(d, nearest ~= nil or count > 0)
        end
        local commanded = d.attackUntilMin and worldMinutes() < d.attackUntilMin
        local autoProtectOn = CD.autoProtectEnabled() and CD.getAutoProtect(animal)
        local ownerFighting = CD.playerFighting(owner)
        local defend = autoProtectOn and (threatensUs or ownerFighting)

        local engageZ = combatTarget[id]
        if engageZ and d.standDown and not guarding and not defend then
            combatTarget[id] = nil
            engageZ = nil
        end
        if engageZ then
            local invalid = true
            if CD.isThreatZombie(engageZ) then
                local gone = engageZ:isDead() or not CD.inWorldNow(engageZ)
                if realGuard and not commanded then
                    local gx, gy = d.guardX or animal:getX(), d.guardY or animal:getY()
                    local zx, zy = engageZ:getX(), engageZ:getY()
                    invalid = gone
                        or ((zx - gx) * (zx - gx) + (zy - gy) * (zy - gy)) > (CD.GUARD_RADIUS * CD.GUARD_RADIUS)
                elseif commanded then
                    invalid = gone or CD.dist2D(animal, engageZ) > CD.ATTACK_COMMAND_RADIUS
                elseif guarding then
                    invalid = gone or CD.dist2D(animal, engageZ) > engageRadius
                else
                    invalid = gone or CD.dist2D(owner, engageZ) > CD.FOLLOW_DEFEND_DROP
                end
            end
            if invalid then
                combatTarget[id] = nil
                engageZ = nil
                d.cstall = nil
                if commanded then
                    CD.clearAttack(animal, d)
                end
            end
        end

        if not engageZ then
            if commanded and d.attackArmed then
                d.attackArmed = nil
                engageZ = CD.scanZombies(animal, CD.ATTACK_COMMAND_RADIUS, owner, animal, false)
                if not engageZ then
                    CD.clearAttack(animal, d)
                    CD.notifyOwner(owner, "notarget", { name = d.name, breed = CD.getBreed(animal) })
                end
            elseif not commanded and nearest and not CD.vetLame(d)
                and (guarding or defend or (not d.standDown and (CD.combatInitiative() or ownerFighting))) then
                if realGuard then
                    local inPost = false
                    if animal and nearest then
                        local gx, gy = d.guardX or animal:getX(), d.guardY or animal:getY()
                        local zx, zy = nearest:getX(), nearest:getY()
                        inPost = ((zx - gx) * (zx - gx) + (zy - gy) * (zy - gy)) <= (CD.GUARD_RADIUS * CD.GUARD_RADIUS)
                    end
                    if inPost then
                        engageZ = nearest
                    end
                elseif guarding then
                    engageZ = nearest
                else
                    local near = true
                    if owner then
                        near = CD.dist2D(owner, nearest) <= CD.FOLLOW_DEFEND_RADIUS
                    end
                    if near then
                        engageZ = nearest
                    end
                end
            end
            if engageZ then
                combatTarget[id] = engageZ
                d.cstall = nil
            end
        end

        if combatEngage(animal, owner, d, id, engageZ, commanded, guarding) then return true end
        combatTarget[id] = nil
        CD.setInCombat(animal, d, false)
        d.combatHold = nil
        d.retreating = false
        d.cstall = nil
    elseif combatTarget[id] or d.inCombat or d.retreating then
        combatTarget[id] = nil
        CD.setInCombat(animal, d, false)
        d.combatHold = nil
        d.retreating = false
        d.cstall = nil
    end
    return false
end

local function companionGuardPost(animal, owner, d, state, realGuard, protecting, ownerStill)
    if realGuard then
        CD.branch(animal, d, "guarda-posto")
        returnToAnchor(animal, d)
    elseif protecting then
        CD.branch(animal, d, "auto-protecao")
        if ownerStill then
            returnToAnchor(animal, d, d.protectX, d.protectY, d.protectZ)
        elseif state == CD.STATE_STAY then
            holdStill(animal)
        else
            CD.followOwner(animal, owner)
        end
    else
        CD.branch(animal, d, "seguir")
        CD.followOwner(animal, owner)
    end
end

local function refuseCombat(animal, owner, d, why)
    CD.branch(animal, d, why)
    d.alertTier = nil
    CD.setInCombat(animal, d, false)
    d.retreating = false
    CD.warnRefusalInCombat(animal, owner, d)
    CD.standUp(animal)
    if CD.getState(animal) ~= CD.STATE_STAY then
        CD.followOwner(animal, owner)
    else
        holdStill(animal)
    end
end

local function updateCompanion(animal, owner)
    local handled, d = companionGuards(animal, owner)
    if handled then return end

    local mounted = CD.isMounted(owner)
    local id = animal:getOnlineID()
    animal:setIsInvincible(true)

    if d.breedTarget then
        CD.branch(animal, d, "indo cruzar")
        return
    end

    if CD.hasBag(animal) and CD.ensureAttachSlots(animal) then
        if animal:getAttachedItem("saddlebags_l") == nil then
            CD.applyBagVisual(animal)
        end
    end

    if not mounted then
        CD.ensureUidData(d)
        activeDogSnapshot[owner:getOnlineID()] = {
            type = animal:getAnimalType(),
            female = animal:isFemale(),
            age = animal:getAge(),
            hp = animal:getHealth(),
            hunger = CD.getHunger(animal),
            thirst = animal:getThirst(),
            data = cdDeepCopy(d),
        }
    end

    animal:setAttackedBy(nil)

    if companionMountedStash(animal, owner, d, mounted) then return end

    CD.updatePanicNotify(animal, owner, d)

    if CD.pzd("dog.autofeed", CD.tryAutoFeed, animal, owner, d) then
        CD.branch(animal, d, "auto-comer")
        CD.standUp(animal)
        return
    end

    if CD.isDisloyal(animal) then
        CD.branch(animal, d, "desleal")
        d.alertTier = nil
        CD.setInCombat(animal, d, false)
        CD.standUp(animal)
        CD.warnRefusalInCombat(animal, owner, d)
        return
    end

    if CD.getStress(animal) >= CD.breedPanicThreshold(animal) then
        d.standDown = true
        refuseCombat(animal, owner, d, "panico")
        return
    end

    if CD.isWoundedCritical(animal) then
        refuseCombat(animal, owner, d, "ferido-critico")
        return
    end

    local state = CD.getState(animal)
    local realGuard = state == CD.STATE_GUARD

    local protecting = CD.autoProtectEnabled() and CD.getAutoProtect(animal)
        and d.protectUntilMin and worldMinutes() < d.protectUntilMin
    local ownerStill = false
    if protecting then
        local oid = owner:getOnlineID()
        local ox, oy = owner:getX(), owner:getY()
        local nowMs = getTimestampMs()
        local oms = CD.ownerMoveState[oid]
        if oms and (math.abs(ox - oms.x) + math.abs(oy - oms.y)) < CD.OWNER_STILL_EPS then
            ownerStill = (nowMs - oms.sinceMs) >= CD.OWNER_STILL_MS
        else
            CD.ownerMoveState[oid] = { x = ox, y = oy, sinceMs = nowMs }
        end
    end
    if protecting and not realGuard then
        if not d.protectX or not ownerStill then
            d.protectX, d.protectY, d.protectZ = owner:getX(), owner:getY(), owner:getZ()
        end
    elseif d.protectX then
        d.protectX, d.protectY, d.protectZ = nil, nil, nil
    end

    local inRecall = d.recallUntilMin and worldMinutes() < d.recallUntilMin
    if CD.sentinelEnabled() and CD.breedCanSense(animal) and not inRecall then
        CD.pzd("dog.sentinel", CD.updateSentinel, animal, owner, d)
    else
        if d.alertTier ~= nil then
            d.alertTier = nil
            CD.transmit(animal)
        end
    end

    if CD.pzd("dog.trickgoto", CD.tryTrickGoto, animal, owner, d) then
        CD.branch(animal, d, "truque-ir-ate")
        CD.standUp(animal)
        return
    end

    if companionStayPass(animal, owner, d, id, state, protecting) then return end
    local guarding = realGuard or protecting

    if not guarding and d.recallUntilMin and worldMinutes() < d.recallUntilMin then
        CD.branch(animal, d, "recall")
        CD.setInCombat(animal, d, false)
        d.retreating = false
        CD.followOwner(animal, owner)
        return
    end

    if not guarding and state == CD.STATE_FOLLOW and CD.dist2D(animal, owner) > CD.TELEPORT_DIST then
        combatTarget[id] = nil
        combatMaintainDogs[id] = nil
        d.cstall = nil
        d.combatMode = nil
        if d.attackUntilMin then
            CD.clearAttack(animal, d)
        end
        if d.inCombat or d.retreating then
            CD.setInCombat(animal, d, false)
            d.retreating = false
        end
        CD.standUp(animal)
        CD.branch(animal, d, "coleira (longe demais)")
        CD.followOwner(animal, owner)
        return
    end

    local canFight = CD.combatEnabled() and CD.breedCanFight(animal)
    if combatPass(animal, owner, d, id, canFight, guarding, realGuard) then return end

    if not guarding and state == CD.STATE_FOLLOW then
        local hunted = CD.pzd("dog.hunt", CD.tryHunt, animal, owner, d)
        if hunted then
            CD.branch(animal, d, "caca")
            CD.standUp(animal)
            return
        end
        if CD.pzd("dog.distract", CD.tryDistract, animal, owner, d) then
            CD.branch(animal, d, "distraido")
            CD.standUp(animal)
            return
        end
        if CD.pzd("dog.sniffpoint", CD.trySniffPoint, animal, owner, d) then
            CD.branch(animal, d, "truque-farejar")
            CD.standUp(animal)
            return
        end
    end

    companionGuardPost(animal, owner, d, state, realGuard, protecting, ownerStill)
end
CD.updateCompanion = updateCompanion

---Restores missing kennel records and companion slots from the world store copy under the player's key.
---@param player IsoPlayer|nil
CD.reclaimKennelFromGlobal = function(player)
    if not player then return end
    local recs = CD.kennelGlobalGet(CD.ownerKey(player))
    if type(recs) ~= "table" then return end
    local pd = CD.playerData(player)
    pd.kennel = pd.kennel or {}
    pd.companions = pd.companions or {}
    local changed, n = false, 0
    for tok, snap in pairs(recs) do
        if pd.kennel[tok] == nil and type(snap) == "table" then
            pd.kennel[tok] = CD.deepCopy(snap)
            changed = true
            n = n + 1
        end
        if not pd.companions[tok] then
            pd.companions[tok] = true
            changed = true
        end
    end
    if changed then
        player:transmitModData()
    end
end

CD._sliceK = 0
CD._sliceCursor = 0
---Round robin: each online player comes up once every COMPANION_TICK_INTERVAL calls; off the server, all local players on one call in that many.
---@param fn fun(player: IsoPlayer)|nil
---@param fn2 fun(player: IsoPlayer)|nil
---@return integer visited players visited by this call
function CD.eachPlayerSlice(fn, fn2)
    local N = CD.COMPANION_TICK_INTERVAL
    if N < 1 then
        N = 1
    end
    local k = (CD._sliceK or 0) % N
    CD._sliceK = (k + 1) % N
    if not isServer() then
        if k ~= 0 then return 0 end
        CD._sniffTickCap = CD.TRICK_SNIFF_TICK_QUOTA
        CD._sniffTickSquares = 0
        local nlocal = 0
        CD.eachLocalPlayer(function(p)
            if p then
                nlocal = nlocal + 1
                if fn then
                    fn(p)
                end
                if fn2 then
                    fn2(p)
                end
            end
        end)
        CD.perfCount("iter.playerslice", nlocal)
        return nlocal
    end
    local players = getOnlinePlayers()
    local P = players and players:size() or 0
    if P <= 0 then
        CD._sliceCursor = 0
        return 0
    end
    local n = math.floor((k + 1) * P / N) - math.floor(k * P / N)
    if n <= 0 then return 0 end
    CD._sniffTickCap = CD.TRICK_SNIFF_TICK_QUOTA * n / P
    CD._sniffTickSquares = 0
    local c = CD._sliceCursor or 0
    for _ = 1, n do
        local p = players:get(c % P)
        c = c + 1
        if p then
            if fn then
                fn(p)
            end
            if fn2 then
                fn2(p)
            end
        end
    end
    CD._sliceCursor = c % P
    CD.perfCount("iter.playerslice", n)
    return n
end

-- Idle gate: with no dog loaded (CD.dogReg.dogs empty) these only walk their own per-dog
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

if not isClient() then
    CD.evAdd(Events.OnTick, companionTick, "srv.tick")
    CD.evAdd(Events.EveryOneMinute, function() CD.reconcileOwnerPresence() end, "srv.ownerpresence")
    Events.OnTick.Add(CD.perfTickEnd)
    CD.BOOT_TICKS = (CD.BOOT_TICKS or 0) + 1
    if CD.DEBUG_BOOT then
        CD.log(string.format("[CD-BOOT] server: companionTick registrado (carga #%d)", CD.BOOT_TICKS))
        if CD.BOOT_TICKS > 1 then
            CD.log("[CD-BOOT] *** ATENCAO: o Lua do Companion Dogs foi carregado " .. CD.BOOT_TICKS ..
                  " VEZES. Ha uma segunda copia do mod ativa. Desative-a antes de julgar qualquer bug. ***")
        end
    end
end
