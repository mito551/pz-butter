---ETW_Perf: routes ETW per-tick handlers through the single Java tick dispatcher (etw.perf.Tick).
---Gates: "always" fn(), "players" fn(player) per online player, "local" / "sleepMoodle" fn(localPlayer).
---Java skips calls when the gate (traits, ETW tick state, moodle data) says there is no work.
---Without the jar, falls back to plain Events.OnTick with no gating, i.e. upstream ETW behavior.
local ETW_PerfTick = {}

---@type table<string, function>
local fallbackHandlers = {}

---@param id string
---@param fn function
---@param gate string|nil
---@param traits CharacterTrait[]|nil
function ETW_PerfTick.register(id, fn, gate, traits)
	if etwPerfTickRegister then
		etwPerfTickRegister(id, fn, gate or "always", traits)
		return
	end
	ETW_PerfTick.unregister(id)
	local handler = fn
	if gate == "players" then
		local ETW_CommonFunctions = require("ETW_CommonFunctions")
		handler = function()
			local players = ETW_CommonFunctions.playersList()
			for i = 0, players:size() - 1 do
				fn(players:get(i))
			end
		end
	elseif gate == "local" or gate == "sleepMoodle" then
		handler = function()
			local player = getPlayer()
			if player then
				fn(player)
			end
		end
	end
	fallbackHandlers[id] = handler
	Events.OnTick.Add(handler)
end

---@param id string
function ETW_PerfTick.unregister(id)
	if etwPerfTickUnregister then
		etwPerfTickUnregister(id)
		return
	end
	local handler = fallbackHandlers[id]
	if handler then
		Events.OnTick.Remove(handler)
		fallbackHandlers[id] = nil
	end
end

return ETW_PerfTick
