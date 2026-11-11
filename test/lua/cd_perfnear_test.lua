local here = debug.getinfo(1, "S").source:sub(2)
local root = here:gsub("[/\\]?test/lua/cd_perfnear_test.lua$", "")
if root == "" then root = "." end

-- Kahlua has no global next(); M.step runs on every OnTick and must not call it.
next = nil
getTimestampMs = function() return 0 end
dofile(root .. "/mod/PZButter_CompanionDogs/42/media/lua/shared/CD_PerfNear.lua")
for _ = 1, 3 do CompanionDogs.PerfNear.step() end   -- no dogs: returns quietly
print("cd_perfnear_test ok")
