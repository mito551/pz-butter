--// Merged OnPlayerUpdate (QoLC-5): one getSpecificPlayer-less pass calls both bodies with the same player.
local function QolcMergedPlayerUpdate(Player)
	if Player and type(QolcAdrenalineUpdate) == "function" then
		QolcAdrenalineUpdate(Player)
	end
	if Player and type(QolcHotbarSyncUpdate) == "function" then
		QolcHotbarSyncUpdate(Player)
	end
end

Events.OnPlayerUpdate.Add(QolcMergedPlayerUpdate)
