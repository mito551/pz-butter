-- PZ Butter dev: devNewSave. SceneDriver.autoContinueTick calls PZButterDevNewSave(name, "x,y,z") on the built main
-- menu instead of pressing Continue. It starts a new Sandbox world on the active mod list with vanilla's own new-game
-- calls (NewGameScreen, CharacterCreationProfession/Main, DebugScenarios): default sandbox settings, an unemployed
-- character with no traits, the player at x,y,z. tools/newsave.sh sets the keys and quits after the scene.
if not PZButter then return end

local function dress()
    pcall(function()
        CharacterCreationMain.instance:randomGenericOutfit()
        getPlayer():setDescriptor(MainScreen.instance.desc)
        getPlayer():Dressup(MainScreen.instance.desc)
    end)
end

function PZButterDevNewSave(name, at)
    local x, y, z = string.match(tostring(at or ""), "^%s*(%-?%d+)%s*,%s*(%-?%d+)%s*,%s*(%-?%d+)%s*$")
    if not (name and name ~= "" and x) then
        print("[PZButter] devNewSave: off (name " .. tostring(name) .. ", at " .. tostring(at) .. ")")
        return
    end
    getWorld():setGameMode("Sandbox")
    MainScreen.instance:setDefaultSandboxVars()
    getWorld():setMap("DEFAULT")   -- what NewGameScreen sets for a Sandbox game
    getWorld():setWorld(name)
    local desc = SurvivorFactory.CreateSurvivor()
    desc:setCharacterProfession(CharacterProfession.UNEMPLOYED)
    MainScreen.instance.desc = desc
    getWorld():setLuaPlayerDesc(desc)
    getWorld():getLuaTraits():clear()
    getWorld():setLuaPosX(tonumber(x))
    getWorld():setLuaPosY(tonumber(y))
    getWorld():setLuaPosZ(tonumber(z))
    Events.OnNewGame.Add(dress)
    print("[PZButter] devNewSave: creating Sandbox/" .. name .. " at " .. x .. "," .. y .. "," .. z)
    createWorld(name)
    GameWindow.doRenderEvent(false)
    forceChangeState(LoadingQueueState.new())
end
