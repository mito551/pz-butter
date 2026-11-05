-- PZButter_TsarsLib (fix mod): enabling the mod is the switch.
-- tsarslib asks the server for its animation data every 100 ticks, forever. One request on the first tick and then
-- at most every 5 minutes is enough: the answer only changes when a mod is added or updated.
if not PZButter then return end
if isServer and isServer() then return end
if not PZButter.Census then print("[PZButter_TsarsLib] off (needs handlerCensus=true in PZButter.properties)") return end
if _G.PZButterTsarInstalled then return end
_G.PZButterTsarInstalled = true

local Tsar = {}
PZButter.Tsar = Tsar
Tsar.minutes = 5
Tsar.now = getTimestampMs
local last = nil

local function wanted(entry)
    local file = (entry.file or ""):lower():gsub("\\", "/")
    return file:match("tsarlibmoddatarequest%.lua$") ~= nil
end

function Tsar.poll()
    local now = Tsar.now()
    if last ~= nil and now - last < Tsar.minutes * 60000 then return end
    last = now
    if ModData and ModData.request then
        pcall(ModData.request, "tsaranimations")
    end
end

-- Census.route answers only after the census has finished (OnGameStart); this file loads before that
function Tsar.install()
    PZButter.Census.finish()
    local fn = PZButter.Census.route("OnTick", wanted)
    if not fn then
        print("[PZButter_TsarsLib] off (no tsarslib OnTick handler with a known owner)")
        return
    end
    Events.OnTick.Add(Tsar.poll)
    print("[PZButter_TsarsLib] on: one animation-data request every " .. Tsar.minutes .. " min")
end
Events.OnGameStart.Add(Tsar.install)
