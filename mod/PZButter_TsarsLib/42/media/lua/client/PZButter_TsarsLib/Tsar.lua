-- PZButter_TsarsLib (fix mod): enabling the mod is the switch.
-- tsarslib's tsarlibModDataRequest.lua counts every tick and, every 100th, asks the server for
-- "tsaranimations" - but only `if isClient()`. That table is the per-player animation state the
-- other clients need (server/Animations/AMC_ModData.lua), so on a multiplayer client the poll must
-- keep its pace and is left alone. Anywhere else the handler can never send anything: it is taken
-- off OnTick and nothing replaces it.
if not PZButter then return end
if isServer and isServer() then return end
if not PZButter.Census then print("[PZButter_TsarsLib] off (needs handlerCensus=true in PZButter.properties)") return end
if _G.PZButterTsarInstalled then return end
_G.PZButterTsarInstalled = true

local Tsar = {}
PZButter.Tsar = Tsar

local function wanted(entry)
    local file = (entry.file or ""):lower():gsub("\\", "/")
    return file:match("tsarlibmoddatarequest%.lua$") ~= nil
end

-- Census.route answers only after the census has finished (OnGameStart); this file loads before that
function Tsar.install()
    if isClient() then
        print("[PZButter_TsarsLib] off (multiplayer client: the animation poll is needed as it is)")
        return
    end
    PZButter.Census.finish()
    if not PZButter.Census.route("OnTick", wanted) then
        print("[PZButter_TsarsLib] off (no tsarslib OnTick handler with a known owner)")
        return
    end
    print("[PZButter_TsarsLib] on: the client-only animation poll is off OnTick")
end
Events.OnGameStart.Add(Tsar.install)
