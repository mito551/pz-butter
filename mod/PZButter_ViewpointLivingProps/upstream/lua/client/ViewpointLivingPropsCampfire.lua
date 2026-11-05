-- Campfires use the vanilla flame. Only clean up custom attachments left by older versions.
-- Never remove the separate IsoFire object: it supplies the game's fire, heat and light.
local OLD = {
    lp_fire_a_camping_01_5 = true,
    lp_fire_b_camping_01_5 = true,
    lp_fire_c_camping_01_5 = true,
}
local function cleanSquare(sq)
    if not sq then return end
    local objects = sq:getObjects()
    for i = 0, objects:size() - 1 do
        local o = objects:get(i)
        local sprite = o:getSprite()
        local name = sprite and sprite:getName()
        if name == "camping_01_4" or name == "camping_01_5" or name == "camping_01_6" then
            local attached = o:getAttachedAnimSprite()
            if attached then
                for j = attached:size() - 1, 0, -1 do
                    local inst = attached:get(j)
                    if inst and OLD[inst:getName()] then o:RemoveAttachedAnim(j) end
                end
            end
            o:getModData().lpCampAtt = nil
        end
    end
end
local function safeClean(sq) pcall(cleanSquare, sq) end
Events.LoadGridsquare.Add(safeClean)
Events.ReuseGridsquare.Add(safeClean)
Events.OnObjectAdded.Add(function(o) if o then safeClean(o:getSquare()) end end)

-- Covers already-loaded squares when the Java helper starts Lua after joining a server.
-- A row at a time; subsequent loaded squares are handled by the events above.
local ticks, row = 0, -14
Events.OnTick.Add(function()
    if row > 14 then return end
    ticks = ticks + 1
    if ticks % 6 ~= 0 then return end
    local p = getPlayer()
    local sq = p and p:getSquare()
    local cell = getCell()
    if not sq or not cell then return end
    for dx = -14, 14 do
        safeClean(cell:getGridSquare(sq:getX() + dx, sq:getY() + row, sq:getZ()))
    end
    row = row + 1
end)
