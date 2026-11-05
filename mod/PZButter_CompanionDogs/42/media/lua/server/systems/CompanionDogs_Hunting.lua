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

local huntPreyRef = {}
local huntWoundedRef = {}

local HUNT_TINY_PREFIX = { "mouse", "rat", "squirrel" }
local HUNT_SMALL_PREFIX = { "rab", "raccoon" }
local HUNT_LARGE_TYPES = { fawn = true, doe = true, buck = true, deer = true }
local function huntPreyClass(o)
    local t = o.getAnimalType and o:getAnimalType()
    if not t then return nil end
    if HUNT_LARGE_TYPES[t] then return "large" end
    for _, p in ipairs(HUNT_TINY_PREFIX) do
        if string.sub(t, 1, string.len(p)) == p then return "tiny" end
    end
    for _, p in ipairs(HUNT_SMALL_PREFIX) do
        if string.sub(t, 1, string.len(p)) == p then return "small" end
    end
    return nil
end

local function isHuntablePrey(animal, o, classes)
    if o == animal or not instanceof(o, "IsoAnimal") then return false end
    if not o:isWild() then return false end
    if o:isDead() then return false end
    local cls = huntPreyClass(o)
    if cls == nil then return false end
    if classes and not classes[cls] then return false end
    return CD.breedCanHuntClass(animal, cls)
end

local function scanWildAnimals(animal, radius, classes)
    local cell = getCell()
    if not cell then return nil, 0 end
    local ax, ay, az = math.floor(animal:getX()), math.floor(animal:getY()), math.floor(animal:getZ())
    local R = math.ceil(radius)
    local best, bestDist, count = nil, radius, 0
    local animals = CD.PerfNear.animals(animal, R)
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
    return best, count
end

local function setHuntTag(animal, d, state)
    if d.hunting == state then return end
    d.hunting = state
    CD.transmit(animal)
end

local function addHuntXP(animal, amount)
    CD.addSkillXP(animal, "hunt", amount * CD.huntXPRate())
end

local function huntBark(animal)
    if not CD.bark(animal) then return end
    if not CD.huntBarkAttracts() then return end
    if CD.cooldown(animal, "huntNoise", CD.SENTINEL_NOISE_COOLDOWN_MS) then return end
    addSound(animal, math.floor(animal:getX()), math.floor(animal:getY()), math.floor(animal:getZ()),
        CD.HUNT_BARK_RADIUS, CD.HUNT_BARK_VOLUME)
end

---Barks loud enough to pull the zombies within TRICK_DISTRACT_RADIUS toward the dog.
---@param animal IsoAnimal
CD.trickDistract = function(animal)
    CD.stamp(animal, "bark")
    CD.emitSound(animal, CD.breedVoice(animal, "bark"))
    addSound(animal, math.floor(animal:getX()), math.floor(animal:getY()), math.floor(animal:getZ()),
        CD.TRICK_DISTRACT_RADIUS, CD.TRICK_DISTRACT_VOLUME)
end

local function bumpHuntHunger(animal)
    local h = math.min(1, CD.getHunger(animal) + CD.huntHungerPerHunt())
    CD.setNeed(animal, "hunger", h)
end

local function dropHuntTarget(animal, d)
    local id = animal:getOnlineID()
    local held = huntWoundedRef[id]
    if held then
        local b = held:getBehavior()
        if b then
            b:setBlockMovement(false)
        end
    end
    huntPreyRef[id] = nil
    huntWoundedRef[id] = nil
    d.huntTargetId = nil
    d.huntGoingSinceMin = nil
    d.huntPointSinceMin = nil
    d.huntPointed = nil
    d.huntAlerted = nil
    d.huntGoalTx = nil
    d.huntGoalTy = nil
    d.huntProbeMs = nil
    d.huntSniffXp = nil
    setHuntTag(animal, d, nil)
end

-- addCorpse nao move o corpo: sem setX/setY/setZ e setSquare ele fica listado no destino com a
-- coordenada do abate e nao desenha em canto nenhum.
local function relocateCorpseToOwner(kx, ky, kz, owner)
    local cell = getCell()
    if not cell or not owner then return end
    local ksq = cell:getGridSquare(kx, ky, kz)
    if not ksq then return end
    local osq = owner:getCurrentSquare()
        or cell:getGridSquare(math.floor(owner:getX()), math.floor(owner:getY()), math.floor(owner:getZ()))
    if not osq or osq == ksq then return end
    local bodies = ksq:getDeadBodys()

    local function placeOn(body, sq)
        if not body or not sq then return end
        -- So removeCorpse emite RemoveCorpseFromMap: sem ele o client fica com duas carcacas no mesmo ObjectID.
        local old = body:getSquare()
        if old then
            old:removeCorpse(body, false)
        else
            body:removeFromWorld()
            body:removeFromSquare()
        end
        body:setX(sq:getX() + 0.5)
        body:setY(sq:getY() + 0.5)
        body:setZ(sq:getZ())
        body:setSquare(sq)
        sq:addCorpse(body, false)
        body:invalidateCorpse()
        body:setInvalidateNextRender(true)
        if isServer() then
            sendCorpse(body)
        end
    end

    local function isOn(body, sq)
        local list = sq and sq:getDeadBodys()
        if not list then return false end
        for i = 0, list:size() - 1 do
            if list:get(i) == body then return true end
        end
        return false
    end

    for i = bodies:size() - 1, 0, -1 do
        local body = bodies:get(i)
        local isAnimalCorpse = body:getModData() and body:getModData()["AnimalType"] ~= nil
        if isAnimalCorpse then
            placeOn(body, osq)
            if not isOn(body, osq) then
                CD.log("entrega de caca: addCorpse no square do dono nao pegou; carcaca devolvida ao local do abate")
                placeOn(body, ksq)
            end
            return
        end
    end
end

local function huntStrike(animal, owner, prey, d, cls, huntLevel)
    local now = CD.worldMinutes()
    if not CD.cooledDown(animal, "huntHit", CD.HUNT_HIT_COOLDOWN_MS) then return end

    if cls == "large" then
        local h = prey:getHealth()
        if h <= CD.HUNT_DEER_WOUND_FLOOR + 0.001 then
            d.huntDeerHeldSinceMin = d.huntDeerHeldSinceMin or now
            setHuntTag(animal, d, "holding")
            animal:faceThisObject(prey)
            if not d.huntDeerHeldNotified then
                d.huntDeerHeldNotified = true
                CD.notifyOwner(owner, "huntwoundeddeer", { id = animal:getOnlineID(), breed = CD.getBreed(animal) })
            end
            if (now - d.huntDeerHeldSinceMin) > CD.HUNT_DEER_HOLD_MIN then
                prey:setHealth(0)
                prey:killed(owner)
                d.preyKills = (d.preyKills or 0) + 1
                addHuntXP(animal, CD.HUNT_XP_PER_SMALL_KILL)
                bumpHuntHunger(animal)
                CD.notifyOwner(owner, "huntkill", { id = animal:getOnlineID(), breed = CD.getBreed(animal) })
                d.woundedPrey = nil
                d.huntDeerHeldSinceMin = nil
                d.huntDeerHeldNotified = nil
                dropHuntTarget(animal, d)
            end
            return
        end
        d.huntDeerHeldSinceMin = nil
        CD.stamp(animal, "huntHit")
        CD.emitSound(animal, CD.BITE_SOUND)
        CD.emitAnim(animal)
        local nh = h - CD.HUNT_DEER_WOUND
        if nh < CD.HUNT_DEER_WOUND_FLOOR then
            nh = CD.HUNT_DEER_WOUND_FLOOR
        end
        prey:setHealth(nh)
        addHuntXP(animal, CD.HUNT_XP_PER_LARGE_HIT)
        bumpHuntHunger(animal)
        d.woundedPrey = { id = prey:getOnlineID(), untilMin = now + CD.HUNT_DEER_HOLD_MIN + CD.HUNT_GOING_TIMEOUT_MIN }
        huntWoundedRef[animal:getOnlineID()] = prey
        if CD.huntDeerCanHurtDog() and ZombRand(0, 1000) < CD.HUNT_DEER_DOG_HURT_CHANCE * 1000 then
            CD.addCombatStress(animal, CD.huntDeerDogStress())
            animal:setIsInvincible(false)
            animal:setHealth(math.max(0.05, animal:getHealth() - CD.HUNT_DEER_DOG_DAMAGE))
            animal:setIsInvincible(true)
        end
        CD.transmit(animal)
        return
    end

    CD.stamp(animal, "huntHit")
    CD.emitSound(animal, CD.BITE_SOUND)
    CD.emitAnim(animal)
    local kx, ky, kz = math.floor(prey:getX()), math.floor(prey:getY()), math.floor(prey:getZ())
    local hungry = CD.getHunger(animal) >= CD.troughFeedTrigger(owner)
    if hungry then
        prey:delete()
        local nh = math.max(0, CD.getHunger(animal) - CD.troughHungerRestore())
        CD.setNeed(animal, "hunger", nh)
        d.preyKills = (d.preyKills or 0) + 1
        addHuntXP(animal, CD.HUNT_XP_PER_SMALL_KILL)
        CD.markProteinMeal(d)
        CD.vetRollFoodAllergy(animal, "Game")
        CD.addCombatStress(animal, -0.05)
        dropHuntTarget(animal, d)
        CD.transmit(animal)
        return
    end

    prey:setHealth(0)
    prey:killed(owner)
    d.preyKills = (d.preyKills or 0) + 1
    addHuntXP(animal, CD.HUNT_XP_PER_SMALL_KILL)
    bumpHuntHunger(animal)
    d.huntTargetId = nil
    d.huntAlerted = nil
    if CD.huntFetchEnabled() and huntLevel >= CD.huntFetchLevel(animal) then
        -- viaDistract: a janela de distracao fecha no MESMO tick do abate (o drive devolve false porque
        -- preyKills subiu), e o portao de "modo Caca desligado" do tryHunt apagaria a entrega no tick
        -- seguinte. Marcar aqui e o que deixa o portao poupa-la e a entrega chegar ao fim.
        d.huntRetrieve = { x = kx, y = ky, z = kz, untilMin = now + CD.huntDeliverTimeout(animal), picked = false,
                           viaDistract = (d.distractKind == "prey" and CD.distractActive(animal)) or nil }
        setHuntTag(animal, d, "retrieving")
    else
        setHuntTag(animal, d, nil)
        CD.notifyOwner(owner, "huntkill", { id = animal:getOnlineID(), breed = CD.getBreed(animal) })
    end
    CD.transmit(animal)
end

local function tryForagePoint(animal, owner, d)
    if not CD.huntForagePointEnabled() then
        if d.hunting == "foraging" then
            setHuntTag(animal, d, nil)
        end
        d.forageSpot = nil
        return false
    end
    local now = CD.worldMinutes()
    if d.forageRetryMin and now < d.forageRetryMin then
        if d.hunting == "foraging" then
            setHuntTag(animal, d, nil)
        end
        d.forageSpot = nil
        return false
    end

    local tgt = d.forageScoutTarget
    if tgt and now > (tgt.untilMin or 0) then
        d.forageScoutTarget = nil
        tgt = nil
    end
    if not tgt then
        if d.hunting == "foraging" then
            setHuntTag(animal, d, nil)
        end
        d.forageSpot = nil
        return false
    end
    local spot = d.forageSpot
    if not spot or spot.x ~= tgt.x or spot.y ~= tgt.y then
        spot = { x = tgt.x, y = tgt.y, z = tgt.z }
        d.forageSpot = spot
        d.foragePointSinceMin = nil
        d.forageGoingSinceMin = nil
        d.forageAlerted = nil
    end

    setHuntTag(animal, d, "foraging")
    local gx = animal:getX() - (spot.x + 0.5)
    local gy = animal:getY() - (spot.y + 0.5)
    if math.sqrt(gx * gx + gy * gy) > CD.FORAGE_ARRIVE_DIST then
        d.foragePointSinceMin = nil
        d.forageGoingSinceMin = d.forageGoingSinceMin or now
        if (now - d.forageGoingSinceMin) > CD.HUNT_GOING_TIMEOUT_MIN then
            d.forageRetryMin = now + CD.HUNT_FORAGE_RETRY_COOLDOWN_MIN
            d.forageSpot = nil
            d.forageGoingSinceMin = nil
            setHuntTag(animal, d, nil)
            return false
        end
        CD.moveTo(animal, false, CD.walkAnimSpeed(), spot.x, spot.y, spot.z, "forrageio", nil, "forrageio")
        return true
    end
    d.forageGoingSinceMin = nil

    d.foragePointSinceMin = d.foragePointSinceMin or now
    if not d.forageAlerted then
        d.forageAlerted = true
        huntBark(animal)
        CD.pulseSniffAnim(animal, "forrageio")
        CD.notifyOwner(owner, "foragefound", { id = animal:getOnlineID(), breed = CD.getBreed(animal) })
        if not d.lastHuntXpMin or (now - d.lastHuntXpMin) >= CD.HUNT_XP_COOLDOWN_MIN then
            d.lastHuntXpMin = now
            addHuntXP(animal, CD.HUNT_XP_PER_FORAGE_POINT)
        end
    end
    if (now - d.foragePointSinceMin) > CD.HUNT_FORAGE_POINT_DWELL_MIN then
        d.forageRetryMin = now + CD.HUNT_FORAGE_RETRY_COOLDOWN_MIN
        d.forageSpot = nil
        d.foragePointSinceMin = nil
        d.forageAlerted = nil
        setHuntTag(animal, d, nil)
        return false
    end
    animal:setVariable("animalRunning", false)
    animal:setVariable("animalSpeed", 0)
    animal:stopAllMovementNow()
    local fx = (spot.x + 0.5) - animal:getX()
    local fy = (spot.y + 0.5) - animal:getY()
    if (fx * fx + fy * fy) > 0.0025 then
        animal:faceLocation(spot.x, spot.y)
    end
    CD.holdStill(animal)
    return true
end

---Debug log of the go-to trick drive: progress since the previous line, path target and movement state.
---@param animal IsoAnimal
---@param d table companion ModData
---@param spot table { x, y } target tile
---@param tag string
---@param stalled boolean
---@param sinceIssue number milliseconds since the path was issued
CD.debugGoto = function(animal, d, spot, tag, stalled, sinceIssue)
    local ax, ay = animal:getX(), animal:getY()
    local px, py = d.dbgGotoX or ax, d.dbgGotoY or ay
    local step = math.abs(ax - px) + math.abs(ay - py)
    d.dbgGotoX, d.dbgGotoY = ax, ay
    local ptx, pty = "-", "-"
    local b = animal:getPathFindBehavior2()
    if b then
        ptx, pty = math.floor(b:getTargetX()), math.floor(b:getTargetY())
    end
    local st = tostring(animal:getCurrentState())
    local bp = tostring(animal:getVariableBoolean("bPathfind"))
    local def = tostring(animal:isDeferredMovementEnabled())
    local spd = tostring(animal:getVariableString("animalSpeed"))
    CD.log(string.format(
        "[CD-GOTO] %s pos(%.2f,%.2f) andou=%.3f alvo(%d,%d) dist=%.2f pathAlvo(%s,%s) parado=%s desdeEmite=%d estado=%s bPathfind=%s defer=%s vel=%s trava=%s",
        tag, ax, ay, step, spot.x, spot.y,
        math.sqrt((ax - (spot.x + 0.5)) * (ax - (spot.x + 0.5)) + (ay - (spot.y + 0.5)) * (ay - (spot.y + 0.5))),
        tostring(ptx), tostring(pty), stalled and "SIM" or "-", math.min(sinceIssue, 99999),
        st, bp, def, spd,
        d.lockX and string.format("(%.2f,%.2f)", d.lockX, d.lockY) or "-"))
end

---Drives the go-to trick until the dog reaches the tile or gives up after TRICK_GOTO_TIMEOUT_MIN.
---@param animal IsoAnimal
---@param owner IsoPlayer|nil gets the done or failed notice
---@param d table companion ModData
---@return boolean true while the trick owns the dog this tick, arrival included
CD.tryTrickGoto = function(animal, owner, d)
    local spot = d.gotoSpot
    if not spot then return false end
    local now = CD.worldMinutes()
    local id = animal:getOnlineID()
    CD.followMaintainDogs[id] = nil
    CD.combatMaintainDogs[id] = nil
    CD.combatTarget[id] = nil
    if d.gotoPathMs == nil then
        if d.inCombat or d.retreating then
            CD.setInCombat(animal, d, false)
            d.retreating = false
        end
    end

    local nowMs = getTimestampMs()
    if d.gotoPathMs then
        local age = nowMs - d.gotoPathMs
        if age < 0 or age > 60000 then
            d.gotoPathMs, d.gotoProbeMs = nil, nil
        end
    end
    local ax, ay = animal:getX(), animal:getY()
    local stalled = false
    if not d.gotoProbeMs or (math.abs(ax - (d.gotoProbeX or 0)) + math.abs(ay - (d.gotoProbeY or 0))) >= CD.FOLLOW_STALL_EPS then
        d.gotoProbeMs, d.gotoProbeX, d.gotoProbeY = nowMs, ax, ay
    else
        stalled = (nowMs - d.gotoProbeMs) >= CD.TRICK_GOTO_STALL_MS
    end

    local gx = ax - (spot.x + 0.5)
    local gy = ay - (spot.y + 0.5)
    local dist = math.sqrt(gx * gx + gy * gy)
    local onTile = math.floor(ax) == spot.x and math.floor(ay) == spot.y
    if dist > CD.TRICK_GOTO_ARRIVE_DIST and not (onTile and stalled and d.gotoPathMs) then
        spot.sinceMin = spot.sinceMin or now
        if (now - spot.sinceMin) > CD.TRICK_GOTO_TIMEOUT_MIN then
            if CD.DEBUG_TRICK_GOTO then
                CD.debugGoto(animal, d, spot, "DESISTE", false, 0)
            end
            d.gotoSpot = nil
            d.gotoPathMs, d.gotoProbeMs, d.dbgGotoX, d.dbgGotoY = nil, nil, nil, nil
            CD.notifyOwner(owner, "trickgotofail", { id = animal:getOnlineID(), breed = CD.getBreed(animal) })
            return false
        end
        animal:setVariable("animalRunning", false)
        animal:setVariable("animalSpeed", CD.walkAnimSpeed())
        local sinceIssue = d.gotoPathMs and (nowMs - d.gotoPathMs) or 999999
        if d.gotoPathMs and not (stalled and sinceIssue >= CD.FOLLOW_PATH_RESOLVE_MS) then
            CD.forcePathfind(animal)
            if CD.DEBUG_TRICK_GOTO then
                CD.debugGoto(animal, d, spot, "segura", stalled, sinceIssue)
            end
            return true
        end
        d.gotoPathMs = nowMs
        d.gotoProbeMs, d.gotoProbeX, d.gotoProbeY = nowMs, ax, ay
        CD.moveTo(animal, false, CD.walkAnimSpeed(), spot.x, spot.y, spot.z, "goto-truque", nil, "truque")
        if CD.DEBUG_TRICK_GOTO then
            CD.debugGoto(animal, d, spot, "EMITE", stalled, sinceIssue)
        end
        return true
    end
    if dist > 0.02 then
        animal:stopAllMovementNow()
        local cx, cy = spot.x + 0.5, spot.y + 0.5
        animal:setX(cx)
        animal:setY(cy)
        animal:setNextX(cx)
        animal:setNextY(cy)
    end
    if CD.DEBUG_TRICK_GOTO then
        CD.debugGoto(animal, d, spot, "CHEGOU", stalled, 0)
    end
    d.gotoSpot = nil
    d.gotoPathMs, d.gotoProbeMs, d.dbgGotoX, d.dbgGotoY = nil, nil, nil, nil
    CD.notifyOwner(owner, "trickgotodone", { id = animal:getOnlineID(), breed = CD.getBreed(animal) })
    CD.holdStill(animal)
    return true
end

CD.driveMemos = CD.driveMemos or {}
---Path bookkeeping kept per dog and per drive kind, created on first use.
---@param animal IsoAnimal
---@param kind string drive kind, such as "sniff" or "fetch"
---@param reset boolean|nil forgets the issued path and the stall probe
---@return table memo { pathMs, probeMs, probeX, probeY }
CD.driveMemo = function(animal, kind, reset)
    local id = animal:getOnlineID()
    local key = tostring(id) .. ":" .. tostring(kind)
    local m = CD.driveMemos[key]
    if not m then
        m = {}
        CD.driveMemos[key] = m
    end
    if reset then
        m.pathMs, m.probeMs, m.probeX, m.probeY = nil, nil, nil, nil
    end
    return m
end

-- Sequestro detectado pelo objetivo, nunca por coordenada: no callback do A* o targetX passa a valer
-- o ultimo no alcancado. isGoalSound() tambem nao serve, so pathToSound o escreve.
---@param animal IsoAnimal
---@return boolean
---@nodiscard
CD.pathHijacked = function(animal)
    return animal:getLastSoundRespondedTo() ~= nil
end

---One step of a walk to a tile: keeps a live path, issues a new one when stalled or hijacked, stops on arrival.
---@param animal IsoAnimal
---@param memo table from CD.driveMemo
---@param x integer target tile
---@param y integer
---@param z integer
---@param arriveDist number|nil tiles from the tile center that count as arrived; 1.2 when nil
---@param running boolean|nil
---@param why string|nil label for the path debug log
---@param system string|nil path counter bucket; "farejar" when nil
---@return boolean going true while the dog is still on its way
---@return string|nil outcome when not going: "chegou" arrived, "desistiu" stalled too long, "inalcancavel" no route
---@return number|nil gap tiles left to the tile center, when not going
CD.walkToTile = function(animal, memo, x, y, z, arriveDist, running, why, system)
    system = system or "farejar"
    if memo.pathMs then
        local age = getTimestampMs() - memo.pathMs
        if age < 0 or age > 60000 then
            memo.pathMs, memo.probeMs = nil, nil
        end
    end
    local ax, ay = animal:getX(), animal:getY()
    local gx, gy = ax - (x + 0.5), ay - (y + 0.5)
    local gap = math.sqrt(gx * gx + gy * gy)
    if gap <= (arriveDist or 1.2) then
        memo.pathMs, memo.probeMs = nil, nil
        if CD.DEBUG_PATH then CD.logPathQuiet(animal, why or "walkToTile", x, y,
            string.format("CHEGOU gap=%.1f", gap)) end
        return false, "chegou", gap
    end
    local nowMs = getTimestampMs()
    local stalled, stillMs = false, 0
    if not memo.probeMs or (math.abs(ax - (memo.probeX or 0)) + math.abs(ay - (memo.probeY or 0))) >= CD.FOLLOW_STALL_EPS then
        memo.probeMs, memo.probeX, memo.probeY = nowMs, ax, ay
    else
        stillMs = nowMs - memo.probeMs
        stalled = stillMs >= CD.TRICK_GOTO_STALL_MS
    end
    if stillMs >= CD.WALK_GIVEUP_MS then
        memo.pathMs, memo.probeMs = nil, nil
        if CD.DEBUG_PATH then CD.logPathQuiet(animal, why or "walkToTile", x, y,
            string.format("DESISTIU travado gap=%.1f", gap)) end
        return false, "desistiu", gap
    end
    running = running == true and CD.mayRun(animal)
    local speed = running and CD.runAnimSpeed() or CD.walkAnimSpeed()
    animal:setVariable("animalRunning", running)
    animal:setVariable("animalSpeed", speed)
    local sinceIssue = memo.pathMs and (nowMs - memo.pathMs) or 999999
    local hijacked = false
    if memo.pathMs and sinceIssue >= CD.WALK_HIJACK_GRACE_MS then
        hijacked = CD.pathHijacked(animal)
        if hijacked then
            CD.perfCount("path.hijack." .. system)
        end
    end
    local noRoute = false
    if memo.pathMs and not hijacked and sinceIssue >= CD.WALK_HIJACK_GRACE_MS then
        local b = animal:getPathFindBehavior2()
        if b and b:isGoalLocation() then
            local ex, ey = math.floor(b:getTargetX()), math.floor(b:getTargetY())
            local ddx, ddy = ex - x, ey - y
            local tol = CD.WALK_UNREACHABLE_TOL
            noRoute = (ddx * ddx + ddy * ddy) > (tol * tol)
        end
    end
    if noRoute then
        memo.pathMs, memo.probeMs = nil, nil
        if CD.DEBUG_PATH then CD.logPathQuiet(animal, why or "walkToTile", x, y,
            string.format("SEM ROTA (o A* so chega a %.1f do alvo)", gap)) end
        return false, "inalcancavel", gap
    end
    local reissue = (memo.pathMs == nil)
        or hijacked
        or (stalled and sinceIssue >= CD.FOLLOW_PATH_RESOLVE_MS)
    if not reissue then
        CD.forcePathfind(animal)
        if CD.DEBUG_PATH then CD.logPathQuiet(animal, why or "walkToTile", x, y,
            string.format("path vivo, travado=%s desdeEmite=%dms gap=%.1f",
                stalled and "SIM" or "-", math.min(sinceIssue, 99999), gap)) end
        return true
    end
    local reason
    if CD.DEBUG_PATH then
        if memo.pathMs == nil then reason = "1a emissao"
        elseif hijacked then reason = "SEQUESTRO (objetivo virou Som)"
        else reason = string.format("travado ha %dms", math.min(stillMs, 99999)) end
    end
    memo.pathMs = nowMs
    CD.moveTo(animal, running == true, speed, x, y, z, why or "walkToTile", reason, system)
    return true
end

---Game minutes between sniff scans, spread per dog by its animal ID so the dogs do not scan together.
---@param animal IsoAnimal
---@return number
---@nodiscard
CD.sniffScanPeriod = function(animal)
    local base = CD.TRICK_SNIFF_SCAN_MIN
    local j = CD.TRICK_SNIFF_SCAN_JITTER
    if j <= 0 then return base end
    local id = animal:getAnimalID()
    return base * (1 - j * 0.5 + j * ((id % 97) / 97))
end

CD.sniffCursor = CD.sniffCursor or {}
CD._sniffTickSquares = 0
CD.sniffScanRan = false

---Offset of the i-th tile on the square ring at distance r; the ring has 8r tiles, and 1 when r is 0.
---@param r integer
---@param i integer index on the ring, from 0
---@return integer dx
---@return integer dy
---@nodiscard
CD.ringOffset = function(r, i)
    if r == 0 then return 0, 0 end
    local a = 4 * r + 2
    if i < a then
        local k = math.floor(i / 2)
        return -r + k, (i % 2 == 0) and -r or r
    end
    local j = i - a
    local k = math.floor(j / 2)
    return (j % 2 == 0) and -r or r, -r + 1 + k
end

---Resumable ring scan for an item of that full type on the floor or in containers; each call scans a slice within the tick quota.
---@param animal IsoAnimal
---@param ftype string|nil item full type
---@param radius number|nil tiles; TRICK_SNIFF_RADIUS when nil
---@return integer|nil x nil until the item is found
---@return integer|nil y
---@return integer|nil z
---@return string|nil name display name of the item found
CD.scanSniffTarget = function(animal, ftype, radius)
    CD.sniffScanRan = false
    local cell = getCell()
    if not cell or not ftype then return nil end
    local perfT = CD.perfIn2()
    local ax, ay, az = math.floor(animal:getX()), math.floor(animal:getY()), math.floor(animal:getZ())
    local R = math.ceil(radius or CD.TRICK_SNIFF_RADIUS)
    local budget = CD.TRICK_SNIFF_CONTAINER_BUDGET
    local id = animal:getOnlineID()

    local slack = (CD._sniffTickCap or CD.TRICK_SNIFF_TICK_QUOTA) - (CD._sniffTickSquares or 0)
    if slack <= 0 then
        if CD.DEBUG_TRICK_SNIFF then
            CD.log("[CD-SNIFF] cota do tick cheia, adiando (cao " .. tostring(id) .. ")")
        end
        return nil
    end
    local scanBudget = CD.TRICK_SNIFF_SCAN_BUDGET
    if scanBudget > slack then
        scanBudget = slack
    end

    local cur = CD.sniffCursor[id]
    if cur then
        local ddx, ddy = ax - cur.ox, ay - cur.oy
        local lim = CD.TRICK_SNIFF_SCAN_RESET_DIST
        if az ~= cur.oz or cur.R ~= R or (ddx * ddx + ddy * ddy) > (lim * lim) then
            cur = nil
        end
    end
    if not cur then
        cur = { ox = ax, oy = ay, oz = az, R = R, r = 0, i = 0 }
        CD.sniffCursor[id] = cur
    end

    local bx, by, bz, bname
    local squares = 0
    local closed = false
    while squares < scanBudget do
        if cur.r > cur.R then
            closed = true
            break
        end
        local n = (cur.r == 0) and 1 or (8 * cur.r)
        if cur.i >= n then
            cur.r, cur.i = cur.r + 1, 0
        else
            local dx, dy = CD.ringOffset(cur.r, cur.i)
            cur.i = cur.i + 1
            if dx * dx + dy * dy <= cur.R * cur.R then
                local sq = cell:getGridSquare(cur.ox + dx, cur.oy + dy, cur.oz)
                if sq then
                    squares = squares + 1
                    local hit, name
                    local wobs = sq:getWorldObjects()
                    if wobs then
                        for i = 0, wobs:size() - 1 do
                            local wo = wobs:get(i)
                            local it = wo and wo:getItem()
                            if it and it:getFullType() == ftype then
                                hit, name = true, it:getName()
                                break
                            end
                        end
                    end
                    if not hit and budget > 0 then
                        local objs = sq:getObjects()
                        if objs then
                            for i = 0, objs:size() - 1 do
                                local o = objs:get(i)
                                local cont = o and o:getContainer()
                                if cont then
                                    budget = budget - 1
                                    if budget < 0 then break end
                                    local items = cont:getItems()
                                    for j = 0, items:size() - 1 do
                                        local it = items:get(j)
                                        if it and it:getFullType() == ftype then
                                            hit, name = true, it:getName()
                                            break
                                        end
                                    end
                                    if hit then break end
                                end
                            end
                        end
                    end
                    if hit then
                        bx, by, bz, bname = sq:getX(), sq:getY(), sq:getZ(), name
                        break
                    end
                end
            end
        end
    end

    CD.sniffScanRan = true
    CD._sniffTickSquares = (CD._sniffTickSquares or 0) + squares
    CD.perfOut2("dog.sniffscan", perfT)
    CD.perfCount("sniff.sq", squares)

    if bx or closed then
        CD.sniffCursor[id] = nil
    end

    if CD.DEBUG_TRICK_SNIFF then
        CD.log(string.format("[CD-SNIFF] fatia raio=%d anel=%d/%d squares=%d/%d conts=%d/%d %s-> %s",
            R, cur.r, cur.R, squares, scanBudget,
            CD.TRICK_SNIFF_CONTAINER_BUDGET - budget, CD.TRICK_SNIFF_CONTAINER_BUDGET,
            closed and "DISCO FECHADO " or "",
            bx and string.format("(%d,%d) %s", bx, by, tostring(bname)) or "nada"))
    end
    if not bx then return nil end
    return bx, by, bz, bname
end

---The tile itself when it can be stood on, else its walkable neighbor with no wall between, nearest to fromX, fromY.
---@param x integer
---@param y integer
---@param z integer
---@param fromX number|nil
---@param fromY number|nil
---@return integer x
---@return integer y
---@nodiscard
CD.approachTile = function(x, y, z, fromX, fromY)
    local cell = getCell()
    if not cell then return x, y end
    local target = cell:getGridSquare(x, y, z)
    local function walkable(s)
        return s ~= nil and not s:isSolid() and not s:isSolidTrans() and CD.squareHasFloor(s)
    end
    if walkable(target) then return x, y end
    if not target then return x, y end
    local bx, by, bestD = x, y, nil
    for _, off in ipairs({ { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } }) do
        local nx, ny = x + off[1], y + off[2]
        local nb = cell:getGridSquare(nx, ny, z)
        if walkable(nb) then
            local wall = target:isWallTo(nb)
            if not wall then
                local dx, dy = (fromX or x) - (nx + 0.5), (fromY or y) - (ny + 0.5)
                local dd = dx * dx + dy * dy
                if not bestD or dd < bestD then
                    bx, by, bestD = nx, ny, dd
                end
            end
        end
    end
    return bx, by
end

---The sniffing tag the clients read expires on its own; on renews it at most once per TRICK_SNIFF_TAG_BEAT_MIN, off clears it.
---@param animal IsoAnimal
---@param d table companion ModData
---@param on boolean
CD.setSniffTag = function(animal, d, on)
    local now = CD.worldMinutes()
    if on then
        local ttl = CD.TRICK_SNIFF_TAG_TTL_MIN
        local beat = CD.TRICK_SNIFF_TAG_BEAT_MIN
        if d.sniffingUntilMin and (d.sniffingUntilMin - now) > (ttl - beat)
            and (d.sniffingUntilMin - now) <= ttl then return end
        d.sniffingUntilMin = now + ttl
        d.sniffing = nil
        CD.transmit(animal)
        return
    end
    if d.sniffingUntilMin or d.sniffing then
        d.sniffingUntilMin, d.sniffing = nil, nil
        CD.transmit(animal)
    end
end

---Debug log of the sniff trick: scent, radius, position and current target.
---@param animal IsoAnimal
---@param d table companion ModData
---@param tag string
---@param extra string|nil
CD.debugSniff = function(animal, d, tag, extra)
    local s = d.sniffed
    CD.log(string.format("[CD-SNIFF] %s cheiro=%s raio=%d pos(%.1f,%.1f,%d) alvo=%s %s",
        tag, s and tostring(s.t) or "-", CD.effectiveSniffRadius(animal),
        animal:getX(), animal:getY(), math.floor(animal:getZ()),
        d.sniffSpot and string.format("(%d,%d,%d)", d.sniffSpot.x, d.sniffSpot.y, d.sniffSpot.z) or "-",
        extra or ""))
end

local function sniffAcquireSpot(animal, owner, d, now, scent, spot)
    if not spot then
        if d.sniffRetryMin and now < d.sniffRetryMin then
            if CD.DEBUG_TRICK_SNIFF and (not d.sniffDbgMin or (now - d.sniffDbgMin) >= CD.TRICK_SNIFF_SCAN_MIN) then
                d.sniffDbgMin = now
                CD.debugSniff(animal, d, "em espera", string.format("volta a varrer em %.0f min", d.sniffRetryMin - now))
            end
            return false
        end
        if d.lastSniffScanMin and (now - d.lastSniffScanMin) < CD.sniffScanPeriod(animal) then return false end
        local mute = d.sniffMute
        if mute then
            local mdx, mdy = animal:getX() - (mute.x + 0.5), animal:getY() - (mute.y + 0.5)
            local R = CD.effectiveSniffRadius(animal) + 2
            if (mdx * mdx + mdy * mdy) > (R * R) or math.floor(animal:getZ()) ~= mute.z then
                if CD.DEBUG_TRICK_SNIFF then
                    CD.debugSniff(animal, d, "saiu da area: pode avisar de novo")
                end
                d.sniffMute = nil
                mute = nil
            end
        end
        local x, y, z, name = CD.scanSniffTarget(animal, scent.t, CD.effectiveSniffRadius(animal))
        if CD.sniffScanRan then
            d.lastSniffScanMin = now
        end
        if not x then return false end
        if mute and mute.x == x and mute.y == y and mute.z == z then
            if CD.DEBUG_TRICK_SNIFF then
                CD.debugSniff(animal, d, "achou mas ja mostrou este", string.format("(%d,%d)", x, y))
            end
            return false
        end
        local gx, gy = CD.approachTile(x, y, z, animal:getX(), animal:getY())
        d.sniffSpot = { x = x, y = y, z = z, gx = gx, gy = gy, sinceMin = now, name = name }
        d.sniffMark = { x = x, y = y, z = z }
        CD.driveMemo(animal, "sniff", true)
        CD.bark(animal)
        CD.setSniffTag(animal, d, true)
        CD.notifyOwner(owner, "sniffnear", {
            id = animal:getOnlineID(), breed = CD.getBreed(animal), item = name or scent.name,
        })
        CD.transmit(animal)
        spot = d.sniffSpot
        if CD.DEBUG_TRICK_SNIFF then
            CD.debugSniff(animal, d, "ACHOU", tostring(name))
        end
    end
    return nil, spot
end

local function sniffApproach(animal, owner, d, now, scent, spot)

    if not spot.checkMin or (now - spot.checkMin) >= CD.TRICK_SNIFF_RECHECK_MIN
        or (now - spot.checkMin) < 0 then
        spot.checkMin = now
        local cell = getCell()
        local still = false
        for dx = -2, 2 do
            for dy = -2, 2 do
                if not still and cell then
                    local sq = cell:getGridSquare(spot.x + dx, spot.y + dy, spot.z)
                    if sq and CD.sniffItemAtSquare(sq, scent.t) then
                        still = true
                    end
                end
            end
        end
        if not still then
            if CD.DEBUG_TRICK_SNIFF then
                CD.debugSniff(animal, d, "SOLTOU: item sumiu")
            end
            d.sniffSpot = nil
            d.sniffMark = nil
            d.sniffMute = nil
            d.sniffRetryMin = now + CD.TRICK_SNIFF_RETRY_MIN
            CD.setSniffTag(animal, d, false)
            CD.transmit(animal)
            return false
        end
    end

    if spot.arrived then
        local rx = animal:getX() - ((spot.gx or spot.x) + 0.5)
        local ry = animal:getY() - ((spot.gy or spot.y) + 0.5)
        if math.sqrt(rx * rx + ry * ry) > CD.TRICK_SNIFF_HOLD_SLACK then
            if CD.DEBUG_TRICK_SNIFF then
                CD.debugSniff(animal, d, "saiu do ponto: volta a andar")
            end
            spot.arrived = nil
            CD.driveMemo(animal, "sniff", true)
        end
    end

    if not spot.arrived then
        local going, desfecho, gap = CD.walkToTile(animal, CD.driveMemo(animal, "sniff"),
            spot.gx or spot.x, spot.gy or spot.y, spot.z, CD.FORAGE_ARRIVE_DIST, false, "farejar")
        if going then return true end
        if desfecho == "inalcancavel" then
            if CD.DEBUG_TRICK_SNIFF then
                CD.debugSniff(animal, d, "SOLTOU: sem rota", string.format("gap=%.1f", gap or -1))
            end
            d.sniffSpot = nil
            d.sniffRetryMin = now + CD.TRICK_SNIFF_RETRY_MIN
            CD.setSniffTag(animal, d, false)
            return false
        end
        if desfecho == "desistiu" and (gap or 0) > CD.TRICK_SNIFF_NEAR_ENOUGH then
            if CD.DEBUG_TRICK_SNIFF then
                CD.debugSniff(animal, d, "SOLTOU: nao alcanca", string.format("gap=%.1f", gap or -1))
            end
            d.sniffSpot = nil
            d.sniffRetryMin = now + CD.TRICK_SNIFF_RETRY_MIN
            CD.setSniffTag(animal, d, false)
            return false
        end
        spot.arrived = true
        spot.dwellMin = now
        d.sniffMute = { x = spot.x, y = spot.y, z = spot.z }
        CD.notifyOwner(owner, "sniffat", {
            id = animal:getOnlineID(), breed = CD.getBreed(animal), item = spot.name or scent.name,
        })
        if CD.DEBUG_TRICK_SNIFF then
            CD.debugSniff(animal, d, "CHEGOU no ponto", string.format("%s gap=%.1f", tostring(desfecho), gap or -1))
        end
    end
end

---Drives the sniff trick: finds the remembered scent nearby, walks there and points at it for a while.
---@param animal IsoAnimal
---@param owner IsoPlayer
---@param d table companion ModData
---@return boolean true while the trick owns the dog this tick
CD.trySniffPoint = function(animal, owner, d)
    if not CD.tricksEnabled() then
        if d.sniffSpot then
            d.sniffSpot = nil
            CD.setSniffTag(animal, d, false)
        end
        return false
    end
    local scent = CD.sniffedScent(animal)
    if not scent or not CD.trickUnlocked(animal, "sniff") then
        if d.sniffSpot then
            d.sniffSpot = nil
            CD.setSniffTag(animal, d, false)
        end
        if CD.DEBUG_TRICK_SNIFF and d.sniffed then
            local nowDbg = CD.worldMinutes()
            if not d.sniffDbgMin or (nowDbg - d.sniffDbgMin) >= CD.TRICK_SNIFF_SCAN_MIN then
                d.sniffDbgMin = nowDbg
                CD.debugSniff(animal, d, "BLOQUEADO", scent and "nivel < 5" or "cheiro invalido")
            end
        end
        return false
    end
    local now = CD.worldMinutes()

    local spot = d.sniffSpot
    local r
    r, spot = sniffAcquireSpot(animal, owner, d, now, scent, spot)
    if r ~= nil then return r end

    local ownerGap = CD.dist2D(animal, owner)
    local giveUp = nil
    if ownerGap > CD.TRICK_SNIFF_HOLD_DIST then giveUp = "dono longe"
    elseif not spot.arrived and (now - (spot.sinceMin or now)) > CD.TRICK_SNIFF_TIMEOUT_MIN then
        giveUp = "nao alcancou"
    end
    if giveUp then
        if CD.DEBUG_TRICK_SNIFF then
            CD.debugSniff(animal, d, "SOLTOU: " .. giveUp)
        end
        d.sniffSpot = nil
        d.sniffRetryMin = now + CD.TRICK_SNIFF_RETRY_MIN
        CD.setSniffTag(animal, d, false)
        return false
    end

    CD.setSniffTag(animal, d, true)
    r = sniffApproach(animal, owner, d, now, scent, spot)
    if r ~= nil then return r end

    if (now - (spot.dwellMin or now)) >= CD.TRICK_SNIFF_DWELL_MIN then
        if CD.DEBUG_TRICK_SNIFF then
            CD.debugSniff(animal, d, "apontou e volta (o movel fica marcado)")
        end
        d.sniffSpot = nil
        d.sniffRetryMin = now + CD.TRICK_SNIFF_RETRY_MIN
        CD.setSniffTag(animal, d, false)
        return false
    end

    local poseMs = getTimestampMs()
    if not d.sniffPoseBeatMs or (poseMs - d.sniffPoseBeatMs) >= CD.TRICK_SNIFF_POSE_MS
        or (poseMs - d.sniffPoseBeatMs) < 0 then
        d.sniffPoseBeatMs = poseMs
        CD.pulseSniffAnim(animal, "sniff-hold")
    end
    animal:setVariable("animalRunning", false)
    animal:setVariable("animalSpeed", 0)
    animal:stopAllMovementNow()
    local fx, fy = (spot.x + 0.5) - animal:getX(), (spot.y + 0.5) - animal:getY()
    if (fx * fx + fy * fy) > 0.0025 then
        animal:faceLocation(spot.x, spot.y)
    end
    CD.holdStill(animal)
    return true
end

local function huntRetrieve(animal, owner, d, now)
    if d.huntRetrieve then
        local r = d.huntRetrieve
        if now > (r.untilMin or 0) then
            CD.notifyOwner(owner, "huntkill", { id = animal:getOnlineID(), breed = CD.getBreed(animal) })
            d.huntRetrieve = nil
            setHuntTag(animal, d, nil)
            return false
        end
        setHuntTag(animal, d, "retrieving")
        if not r.picked then
            local gx = animal:getX() - (r.x + 0.5)
            local gy = animal:getY() - (r.y + 0.5)
            if math.sqrt(gx * gx + gy * gy) > CD.HUNT_STRIKE_DIST then
                CD.letMove(animal)
                animal:setVariable("animalRunning", false)
                animal:setVariable("animalSpeed", CD.walkAnimSpeed())
                CD.pathStatEmit(animal, "caca-buscar")
                animal:pathToLocation(r.x, r.y, r.z)
                CD.forcePathfind(animal)
                return true
            end
            r.picked = true
        end
        local gap = CD.dist2D(animal, owner)
        if gap > CD.FOLLOW_START_DIST then
            CD.letMove(animal)
            animal:setVariable("animalRunning", gap > CD.FOLLOW_RUN_DIST and CD.mayRunFrom(d))
            animal:setVariable("animalSpeed", CD.walkAnimSpeed())
            CD.pathStatEmit(animal, "caca-buscar")
            animal:pathToCharacter(owner)
            CD.forcePathfind(animal)
            return true
        end
        relocateCorpseToOwner(r.x, r.y, r.z, owner)
        CD.notifyOwner(owner, "huntdelivered", { id = animal:getOnlineID(), breed = CD.getBreed(animal) })
        for i = 1, #CD.onHuntDelivered do
            local fn = CD.onHuntDelivered[i]
            if type(fn) == "function" then
                fn(animal, owner)
            end
        end
        d.huntRetrieve = nil
        setHuntTag(animal, d, nil)
        return true
    end
end

local function huntPointOnly(animal, d, prey, gap, canEngage, now)
    if not canEngage then
        local reapproach = d.huntPointed and gap > CD.HUNT_POINT_REAPPROACH_DIST
        if (not d.huntPointed and gap > CD.HUNT_POINT_DIST) or reapproach then
            setHuntTag(animal, d, "tracking")
            if reapproach then
                d.huntPointSinceMin = nil
            end
            d.huntPointed = nil
            d.huntPoseBeatMs = nil
            local nowMs = getTimestampMs()
            local ax, ay = animal:getX(), animal:getY()
            local stalled = false
            if not d.huntProbeMs or (nowMs - d.huntProbeMs) >= 1200 then
                stalled = d.huntProbeMs ~= nil
                    and math.abs(ax - d.huntProbeX) < 0.2 and math.abs(ay - d.huntProbeY) < 0.2
                d.huntProbeMs, d.huntProbeX, d.huntProbeY = nowMs, ax, ay
            end
            local tx, ty = math.floor(prey:getX()), math.floor(prey:getY())
            if stalled or d.huntGoalTx ~= tx or d.huntGoalTy ~= ty then
                d.huntGoalTx, d.huntGoalTy = tx, ty
                CD.moveTo(animal, true, CD.runAnimSpeed(), tx, ty, math.floor(prey:getZ()), "caca-rastro", nil, "caca")
            end
        else
            setHuntTag(animal, d, "sniffing")
            if not d.huntSniffXp then
                d.huntSniffXp = true
                addHuntXP(animal, CD.HUNT_XP_PER_SNIFF)
            end
            d.huntPointed = true
            d.huntGoalTx, d.huntGoalTy, d.huntProbeMs = nil, nil, nil
            d.huntPointSinceMin = d.huntPointSinceMin or now
            if (now - d.huntPointSinceMin) > CD.HUNT_POINT_DWELL_MIN then
                d.huntPointSinceMin = nil
                d.huntPointed = nil
                d.huntPoseBeatMs = nil
                CD.emitEatVar(animal, false, CD.SNIFF_ANIM_VAR)
                d.huntRetryMin = now + CD.HUNT_RETRY_COOLDOWN_MIN
                dropHuntTarget(animal, d)
                return false
            end
            animal:setVariable("animalRunning", false)
            animal:setVariable("animalSpeed", 0)
            animal:faceThisObject(prey)
            animal:stopAllMovementNow()
            CD.holdStill(animal)
            local nowMs = getTimestampMs()
            if not d.huntPoseBeatMs or (nowMs - d.huntPoseBeatMs) >= 2500 then
                d.huntPoseBeatMs = nowMs
                CD.pulseSniffAnim(animal, "caca-aponto")
            end
        end
        return true
    end
end

local function huntEngage(animal, owner, d, prey, preyCls, gap, huntLevel, now)
    if gap > CD.HUNT_STRIKE_DIST then
        setHuntTag(animal, d, "chasing")
        d.huntGoingSinceMin = d.huntGoingSinceMin or now
        if (now - d.huntGoingSinceMin) > CD.HUNT_GOING_TIMEOUT_MIN then
            d.woundedPrey = nil
            d.huntRetryMin = now + CD.HUNT_RETRY_COOLDOWN_MIN
            dropHuntTarget(animal, d)
            return false
        end
        CD.pathStatHijack(animal, "caca-perseguir")
        CD.letMove(animal)
        local run = CD.mayRunFrom(d)
        animal:setVariable("animalSpeed", run and CD.runAnimSpeed() or CD.walkAnimSpeed())
        animal:setVariable("animalRunning", run)
        animal:faceThisObject(prey)
        CD.pathStatEmit(animal, "caca-perseguir")
        animal:pathToCharacter(prey)
        CD.forcePathfind(animal)
        return true
    end
    setHuntTag(animal, d, preyCls == "large" and "holding" or "chasing")
    d.huntGoingSinceMin = nil
    if preyCls == "large" then
        prey:setAttackedBy(nil)
        prey:stopAllMovementNow()
        prey:setVariable("animalRunning", false)
        prey:setVariable("animalSpeed", 0)
        local pb = prey:getBehavior()
        if pb then
            pb:setBlockMovement(true)
        end
        prey:faceThisObject(animal)
    end
    animal:setVariable("animalRunning", false)
    animal:setVariable("animalSpeed", 0)
    animal:faceThisObject(prey)
    animal:stopAllMovementNow()
    CD.holdStill(animal)
    huntStrike(animal, owner, prey, d, preyCls, huntLevel)
    return true
end

-- Diagnostico so com CD.DEBUG_DISTRACT: quando o scan volta vazio, dizer o que havia no raio e em
-- qual porta cada bicho caiu. Em CD.* de proposito, para nao gastar local num arquivo ja perto do teto.
---@param animal IsoAnimal
---@param radius number tiles
---@param classes table set of the prey classes accepted, such as { tiny = true }
CD.debugDumpNearby = function(animal, radius, classes)
    local cell = getCell()
    if not cell then return end
    local ax, ay, az = math.floor(animal:getX()), math.floor(animal:getY()), math.floor(animal:getZ())
    local R = math.ceil(radius)
    local n = 0
    for dx = -R, R do
        for dy = -R, R do
            local sq = cell:getGridSquare(ax + dx, ay + dy, az)
            local list = sq and sq:getAnimals()
            if list then
                for i = 0, list:size() - 1 do
                    local o = list:get(i)
                    if o ~= animal then
                        n = n + 1
                        local cls = huntPreyClass(o)
                        CD.log("[CD-DISTRACT] vizinho: tipo=" .. tostring(o.getAnimalType and o:getAnimalType()) ..
                              " wild=" .. tostring(o:isWild()) .. " morto=" .. tostring(o:isDead()) ..
                              " classe=" .. tostring(cls) ..
                              " classeAceita=" .. tostring(cls ~= nil and classes[cls] == true) ..
                              " racaPodeCacar=" .. tostring(cls ~= nil and CD.breedCanHuntClass(animal, cls)) ..
                              " dist=" .. string.format("%.1f", CD.dist2D(animal, o)))
                    end
                end
            end
        end
    end
    if n == 0 then
        CD.log("[CD-DISTRACT] nenhum animal em " .. tostring(R) .. " tiles no z=" .. tostring(az) ..
              " (bicho em " .. tostring(ax) .. "," .. tostring(ay) .. ")")
    end
end

local function preyClasses(cfg)
    return (cfg and cfg.classes) or CD.DISTRACT_PREY_CLASSES or { tiny = true }
end

CD.registerDistraction("prey", {
    -- Sem gate pelo indice de rato do sandbox: ele governa o SPAWN por chunk, nao a existencia.
    -- Rato de armadilha e rato posto por debug existem com o indice zerado.
    gate = function(animal, cfg)
        return CD.breedCanHunt(animal)
    end,
    find = function(animal, cfg)
        local radius = cfg.radius or CD.DISTRACT_RADIUS
        local classes = preyClasses(cfg)
        local target = scanWildAnimals(animal, radius, classes)
        if target == nil and CD.DEBUG_DISTRACT and not CD.cooldown(animal, "distractDbgScan", 10000) then
            CD.debugDumpNearby(animal, radius, classes)
        end
        return target
    end,
    drive = function(animal, owner, d, cfg)
        local kills = d.preyKills or 0
        local driven = CD.tryHunt(animal, owner, d, true)
        if (d.preyKills or 0) > kills then return false end
        return driven
    end,
    stop = function(animal, d)
        dropHuntTarget(animal, d)
        d.huntRetryMin = nil
    end,
})

---Hunt mode: tracks, points at or catches wild prey, or brings the kill back to the owner.
---@param animal IsoAnimal
---@param owner IsoPlayer
---@param d table companion ModData
---@param viaDistract boolean|nil true when the prey distraction drives the hunt
---@return boolean true while the hunt owns the dog this tick
function CD.tryHunt(animal, owner, d, viaDistract)
    local distracting = d.distractKind == "prey" and CD.distractActive(animal)
    if distracting and not viaDistract then return false end
    local dcfg = distracting and CD.breedDistract(animal, "prey") or nil
    local dclasses = distracting and ((dcfg and dcfg.classes) or CD.DISTRACT_PREY_CLASSES) or nil
    if not distracting and (not CD.huntEnabled() or not CD.getHuntMode(animal)) then
        if d.hunting then
            dropHuntTarget(animal, d)
        end
        if d.huntCooling then
            d.huntCooling = nil
            CD.transmit(animal)
        end
        d.woundedPrey = nil
        -- Entrega nascida de distracao atravessa: a presa ja morreu, o recado tem prazo proprio, e o
        -- modo Caca desligado nao e razao pra largar o corpo no chao. Entrega NORMAL continua sendo
        -- cancelada aqui, porque ali desligar o modo e ordem do jogador.
        if d.huntRetrieve and d.huntRetrieve.viaDistract then
            local rd = huntRetrieve(animal, owner, d, CD.worldMinutes())
            if rd ~= nil then return rd end
        end
        d.huntRetrieve = nil
        return false
    end
    local spd = getGameSpeed()
    if spd > 1 and not (distracting and CD.DISTRACT_IN_FAST_FORWARD) then
        if d.hunting then
            setHuntTag(animal, d, nil)
        end
        return false
    end
    if owner:isAsleep() then
        if d.hunting then
            setHuntTag(animal, d, nil)
        end
        return false
    end
    if animal:getVehicle() ~= nil then
        if d.hunting then
            dropHuntTarget(animal, d)
        end
        return false
    end

    local now = CD.worldMinutes()
    if (d.feedYieldUntilMin and now < d.feedYieldUntilMin) or (d.attackUntilMin and now < d.attackUntilMin) then
        if d.hunting then
            dropHuntTarget(animal, d)
        end
        d.huntRetrieve = nil
        return false
    end

    local huntLevel = CD.getSkillLevel(animal, "hunt")
    local leash = distracting and CD.DISTRACT_LEASH or CD.huntMaxLeash()

    local r = huntRetrieve(animal, owner, d, now)
    if r ~= nil then return r end

    if d.huntRetryMin and now < d.huntRetryMin then
        if d.hunting then
            dropHuntTarget(animal, d)
        end
        if not d.huntCooling then
            d.huntCooling = true
            CD.transmit(animal)
        end
        return false
    end
    if d.huntCooling then
        d.huntCooling = nil
        CD.transmit(animal)
    end

    local prey, preyCls
    if d.woundedPrey then
        if now < (d.woundedPrey.untilMin or 0) then
            local wp = huntWoundedRef[animal:getOnlineID()]
            local alive = wp ~= nil and not wp:isDead()
            if alive then
                prey, preyCls = wp, "large"
            else
                d.woundedPrey = nil
                d.huntDeerHeldNotified = nil
            end
        else
            d.woundedPrey = nil
            d.huntDeerHeldNotified = nil
        end
    end
    local dogId = animal:getOnlineID()
    if not prey and huntPreyRef[dogId] then
        local t = huntPreyRef[dogId]
        local ok = isHuntablePrey(animal, t, dclasses) and CD.dist2D(animal, t) <= leash
        if ok then
            prey, preyCls = t, huntPreyClass(t)
        else
            huntPreyRef[dogId] = nil
            d.huntTargetId = nil
            d.huntAlerted = nil
        end
    end
    if not prey then
        if owner and CD.dist2D(animal, owner) > leash then
            if d.hunting then
                dropHuntTarget(animal, d)
            end
            d.forageSpot = nil
            return false
        end
        if not CD.cooldown(animal, "huntScan", CD.HUNT_SCAN_THROTTLE_MS) then
            local radius = distracting and ((dcfg and dcfg.radius) or CD.DISTRACT_RADIUS)
                or CD.effectiveHuntRadius(animal)
            local nearest = scanWildAnimals(animal, radius, dclasses)
            if nearest then
                prey, preyCls = nearest, huntPreyClass(nearest)
                huntPreyRef[dogId] = nearest
                d.huntTargetId = prey:getOnlineID()
                d.huntGoingSinceMin = nil
                d.huntAlerted = nil
            end
        end
        if not prey then
            if distracting then
                if d.hunting then
                    dropHuntTarget(animal, d)
                end
                return false
            end
            if d.lastPreySeenMin and (now - d.lastPreySeenMin) < CD.HUNT_PREY_FORGET_MIN then
                if d.hunting then
                    dropHuntTarget(animal, d)
                end
                d.forageSpot = nil
                return false
            end
            if d.hunting and d.hunting ~= "foraging" then
                dropHuntTarget(animal, d)
            end
            if tryForagePoint(animal, owner, d) then return true end
            if d.hunting then
                dropHuntTarget(animal, d)
            end
            return false
        end
    end
    d.lastPreySeenMin = now

    if owner and CD.dist2D(animal, owner) > leash then
        d.huntTargetId = nil
        d.woundedPrey = nil
        if not distracting then
            d.huntRetryMin = now + CD.HUNT_RETRY_COOLDOWN_MIN
        end
        dropHuntTarget(animal, d)
        return false
    end

    if d.inCombat or d.retreating then
        CD.setInCombat(animal, d, false)
        d.retreating = false
    end
    CD.combatTarget[animal:getOnlineID()] = nil

    if not d.huntAlerted then
        d.huntAlerted = true
        if not distracting then
            huntBark(animal)
            CD.notifyOwner(owner, "huntpoint", { id = animal:getOnlineID(), breed = CD.getBreed(animal) })
        end
        if not d.lastHuntXpMin or (now - d.lastHuntXpMin) >= CD.HUNT_XP_COOLDOWN_MIN then
            d.lastHuntXpMin = now
            addHuntXP(animal, CD.HUNT_XP_PER_DETECT)
        end
    end

    local canEngage = ((preyCls == "tiny" or preyCls == "small") and huntLevel >= CD.huntSmallLevel())
        or (preyCls == "large" and huntLevel >= CD.huntLargeLevel())
    if distracting then
        canEngage = canEngage or preyCls == "tiny"
        canEngage = canEngage and CD.huntEnabled()
    end
    local gap = CD.dist2D(animal, prey)

    r = huntPointOnly(animal, d, prey, gap, canEngage, now)
    if r ~= nil then return r end

    return huntEngage(animal, owner, d, prey, preyCls, gap, huntLevel, now)
end
