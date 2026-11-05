if not (PZButter and PZButter.tsarPoll and PZButter.Census) then return end
if _G.PZButterTsarInstalled then return end
_G.PZButterTsarInstalled = true
if isServer and isServer() then return end

-- tsarslib asks the server for its animation data every 100 ticks, forever.
-- One request on the first tick and then at most every tsarPollMin minutes
-- is enough: the answer only changes when a mod is added or updated.
local Tsar = {}
PZButter.Tsar = Tsar
local minutes = tonumber(PZButter.tsarPollMin) or 5
if minutes < 0 then minutes = 0 end
Tsar.now = getTimestampMs
local last = nil

local function wanted(entry)
    local file = (entry.file or ""):lower():gsub("\\", "/")
    return file:match("tsarlibmoddatarequest%.lua$") ~= nil
end

local fn = PZButter.Census.route("OnTick", wanted)
if not fn then
    print("[PZButter] tsarPoll: off (no tsarlib handler)")
    return
end

local function poll()
    if minutes == 0 then return end
    local now = Tsar.now()
    if last ~= nil and now - last < minutes * 60000 then return end
    last = now
    if ModData and ModData.request then
        pcall(ModData.request, "tsaranimations")
    end
end

Events.OnTick.Add(poll)
print("[PZButter] tsarPoll: on, every " .. minutes .. " min")
