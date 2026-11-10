--[[
    Performance patch for that DAMN Library forced vehicle spawns.
    Overrides DAMN.Spawns with a cheap per-square target test, lazily cached
    eligibility, cached mod/map sets and ModData, and debug-gated logging.

    LoadGridsquare fires for every streamed square, so the hot path is one test:
      - ZombieBuddy + DSPerf.jar present: dsSpawnsIsTargetSquare(square), one Java call.
      - otherwise: a numeric-key Lua set lookup (no string building).
    Only target squares reach checkSquare.
]]--

DAMN = DAMN or {};
DAMN.Spawns = DAMN.Spawns or {};
DAMN.SpawnsPerfConfig = DAMN.SpawnsPerfConfig or {
    eligibilityCache = true,
    modMapCache = true,
    nativeTargets = true,
    blockedRetry = false, -- original only retried blocked squares when debug logging was on
};

local cfg = DAMN.SpawnsPerfConfig;

function DAMN.Spawns:add(fullScriptName, x, y, options)
    if DAMN["spawnerDebug"] then
        DAMN:log("DAMN.Spawns:add(...)");
    end

    if not isClient() then
        return DAMN.Spawns:addInternal(fullScriptName, x, y, options);
    end

    if DAMN["spawnerDebug"] then
        DAMN:log(" -> skipping because it was called on the client side.");
    end

    return false;
end

if isClient() then
    return;
end

DAMN.Spawns["byLocation"] = DAMN.Spawns["byLocation"] or {};
DAMN.Spawns["modDataKey"] = DAMN.Spawns["modDataKey"] or "forced_vehicle_spawns";

-- ---------------------------------------------------------------------------
-- Cached environment (sets, so lookups are O(1))
-- ---------------------------------------------------------------------------

local _modSet = nil;
local function getModSet()
    if cfg.modMapCache and _modSet then return _modSet; end

    local set = {};
    local mods = getActivatedMods();
    for i = 0, mods:size() - 1 do
        set[mods:get(i)] = true;
    end
    _modSet = set;
    return set;
end

-- Returns nil while server options are unavailable, so callers can retry later
-- instead of caching an empty list.
local _mapSet = nil;
local function getMapSet()
    if cfg.modMapCache and _mapSet then return _mapSet; end

    local options = getServerOptions();
    local option = options and options:getOptionByName("Map");
    if not option then return nil; end

    local set = {};
    for _, map in ipairs(string.split(option:getValue() or "", ";") or {}) do
        set[string.trim(map)] = true;
    end
    _mapSet = set;
    return set;
end

local _spawnModData = nil;
local function spawnModData()
    if not _spawnModData then
        _spawnModData = ModData.getOrCreate(DAMN.Spawns["modDataKey"]);
    end
    return _spawnModData;
end

-- ---------------------------------------------------------------------------
-- Eligibility: true / false, or nil when it cannot be decided yet
-- ---------------------------------------------------------------------------

local function computeAllowed(sc)
    if sc.sandboxVar then
        local sv = SandboxVars and SandboxVars["DAMN"];
        if not sv then return nil; end
        if not sv[sc.sandboxVar] then return false; end
    end

    local mods = getModSet();
    for _, modId in ipairs(sc.modBlacklist) do
        if mods[modId] then return false; end
    end
    for _, modId in ipairs(sc.modWhitelist) do
        if not mods[modId] then return false; end
    end

    if #sc.mapBlacklist > 0 or #sc.mapWhitelist > 0 then
        local maps = getMapSet();
        if not maps then return nil; end

        for _, map in ipairs(sc.mapBlacklist) do
            if maps[string.trim(tostring(map))] then return false; end
        end
        for _, map in ipairs(sc.mapWhitelist) do
            if not maps[string.trim(tostring(map))] then return false; end
        end
    end

    return true;
end

local function isAllowed(sc)
    local allowed = sc.allowed;
    if allowed ~= nil then return allowed; end

    allowed = computeAllowed(sc);
    if allowed ~= nil and cfg.eligibilityCache then
        sc.allowed = allowed;
    end
    return allowed;
end

-- ---------------------------------------------------------------------------
-- Target set: every registered x/y, as a numeric key
-- ---------------------------------------------------------------------------

local TARGET_STRIDE = 131072; -- y must be below this for the key to be unique
local targetSet = {};
local targetCount = 0;

local function registerTarget(x, y)
    local key = x * TARGET_STRIDE + y;
    if not targetSet[key] then
        targetSet[key] = true;
        targetCount = targetCount + 1;
    end

    if cfg.nativeTargets and dsSpawnsRegister then
        pcall(dsSpawnsRegister, x, y);
    end
end

-- ---------------------------------------------------------------------------
-- Public API (same method names as the original)
-- ---------------------------------------------------------------------------

function DAMN.Spawns:addInternal(fullScriptName, x, y, options)
    if getScriptManager():getVehicle(fullScriptName) then
        if x and y and x > 0 and y > 0 and y < TARGET_STRIDE then
            local xy = tostring(x) .. "_" .. tostring(y);

            options = options or {};

            -- Copy the lists: needsVanillaMap appends below and must not touch the caller's tables.
            local mapWhitelist = {};
            for i, map in ipairs(options["mapWhitelist"] or {}) do
                mapWhitelist[i] = map;
            end
            if options["needsVanillaMap"] ~= false then
                mapWhitelist[#mapWhitelist + 1] = "Muldraugh, KY";
            end

            DAMN.Spawns["byLocation"][xy] = {
                mdKey = xy,
                script = fullScriptName,
                direction = options["direction"] or IsoDirections.S,
                chance = options["chance"] or 100,
                skinIndex = options["skinIndex"],
                sandboxVar = options["sandboxVar"],
                modBlacklist = options["modBlacklist"] or {},
                modWhitelist = options["modWhitelist"] or {},
                mapBlacklist = options["mapBlacklist"] or {},
                mapWhitelist = mapWhitelist,
                x = x,
                y = y,
            };

            registerTarget(x, y);

            if DAMN["spawnerDebug"] then
                DAMN:log(" -> adding [" .. tostring(fullScriptName) .. "] at [" .. tostring(x) .. "," .. tostring(y) .. "]");
            end
        elseif DAMN["spawnerDebug"] then
            DAMN:log(" - invalid location [" .. tostring(x) .. "," .. tostring(y) .. "]: skipping");
        end
    elseif DAMN["spawnerDebug"] then
        DAMN:log(" - vehicle script [" .. tostring(fullScriptName) .. "] unavailable: skipping");
    end
end

function DAMN.Spawns:checkSquare(square)
    local squareConfig = DAMN.Spawns["byLocation"][tostring(square:getX()) .. "_" .. tostring(square:getY())];

    if not squareConfig then
        return true;
    end

    if self:checkIfDone(squareConfig) then
        if DAMN["spawnerDebug"] then
            DAMN:log("DAMN.Spawns:checkIfDone(...) -> vehicle spawn was already processed");
        end
        return false;
    end

    -- Not allowed (or not decidable yet): do not remember, so enabling it later still works.
    if isAllowed(squareConfig) ~= true then
        if DAMN["spawnerDebug"] then
            DAMN:log(" -> vehicle is not allowed to spawn");
        end
        return true;
    end

    local roll = ZombRandBetween(1, 100);

    if DAMN["spawnerDebug"] then
        DAMN:log("DAMN.Spawns:checkIfAllowed(...) -> rolling for force spawn: [" .. tostring(roll) .. "] vs. [" .. tostring(squareConfig["chance"]) .. "] on square [" .. tostring(squareConfig["mdKey"]) .. "]");
    end

    if roll <= squareConfig["chance"] then
        if not self:checkIfBlocked(square) then
            if DAMN["spawnerDebug"] then
                DAMN:log(" -> force spawned vehicle [" .. squareConfig["script"] .. "] at [" .. squareConfig["mdKey"] .. "]");
            end

            addVehicleDebug(squareConfig["script"], squareConfig["direction"], squareConfig["skinIndex"], square);
        else
            if DAMN["spawnerDebug"] then
                DAMN:log(" -> square at [" .. squareConfig["mdKey"] .. "] is blocked. will retry later");
            end

            if cfg.blockedRetry or DAMN["spawnerDebug"] then
                return false;
            end
        end
    elseif DAMN["spawnerDebug"] then
        DAMN:log(" -> not force spawning vehicle [" .. squareConfig["script"] .. "] at [" .. squareConfig["mdKey"] .. "] because of bad rng");
    end

    self:remember(squareConfig);
    return true;
end

function DAMN.Spawns:remember(squareConfig)
    spawnModData()[squareConfig["mdKey"]] = Calendar.getInstance():getTimeInMillis();
    ModData.transmit(DAMN.Spawns["modDataKey"]);
end

function DAMN.Spawns:checkIfDone(squareConfig)
    return spawnModData()[squareConfig["mdKey"]];
end

-- Compatibility shim for mods that called the original check.
function DAMN.Spawns:checkIfAllowed(squareConfig)
    return isAllowed(squareConfig) == true;
end

function DAMN.Spawns:checkIfBlocked(square)
    if square:isVehicleIntersecting() then
        if DAMN["spawnerDebug"] then
            DAMN:log("DAMN.Spawns:checkIfBlocked(...) -> vehicle intersecting");
        end
        return true;
    end

    return false;
end

-- ---------------------------------------------------------------------------
-- Event wiring
-- ---------------------------------------------------------------------------

local handlerInstalled = false;

local function installHandler()
    if handlerInstalled then return; end
    handlerInstalled = true;

    -- Use the Java test only if the jar has it and agrees with Lua on the target count;
    -- anything else falls back to the pure-Lua set.
    local isTargetSquare = dsSpawnsIsTargetSquare;
    local native = cfg.nativeTargets and isTargetSquare and dsSpawnsCount and dsSpawnsCount() == targetCount;

    if native then
        Events.LoadGridsquare.Add(function(square)
            if isTargetSquare(square) then
                DAMN.Spawns:checkSquare(square);
            end
        end);
    else
        Events.LoadGridsquare.Add(function(square)
            if targetSet[square:getX() * TARGET_STRIDE + square:getY()] then
                DAMN.Spawns:checkSquare(square);
            end
        end);
    end
end

Events.OnInitGlobalModData.Add(function()
    _spawnModData = ModData.getOrCreate(DAMN.Spawns["modDataKey"]);

    -- Drop anything computed at file load: sandbox, mods and server options may not have been final then.
    _modSet = nil;
    _mapSet = nil;

    -- Rebuild both target sets from the authoritative table.
    targetSet = {};
    targetCount = 0;
    if dsSpawnsClear then pcall(dsSpawnsClear); end
    for _, sc in pairs(DAMN.Spawns["byLocation"]) do
        sc.allowed = nil;
        if sc.x and sc.y then registerTarget(sc.x, sc.y); end
    end

    installHandler();
end);
