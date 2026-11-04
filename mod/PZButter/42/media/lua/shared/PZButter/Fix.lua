-- PZ Butter: shared helper for post-load fixes.
--
-- A fix wraps a function only when the loaded function still comes from the
-- file the fix expects. Another mod's patch means the fix stays off and says
-- why, instead of wrapping a wrapper and doubling its work.
if not PZButter then return end
PZButter.Fix = PZButter.Fix or {}
local Fix = PZButter.Fix

local unpack = unpack or table.unpack

local function normalize(path)
    if type(path) ~= "string" then return nil end
    path = path:gsub("\\", "/"):lower():gsub("/+$", "")
    if path == "" then return nil end
    return path
end

local function same_file(actual, expected)
    actual, expected = normalize(actual), normalize(expected)
    if not actual or not expected then return false end
    return actual == expected or actual:sub(-(#expected + 1)) == "/" .. expected
end

function Fix.once(id)
    local seen = _G.PZButterFixOnce
    if not seen then
        seen = {}
        _G.PZButterFixOnce = seen
    end
    if seen[id] then return false end
    seen[id] = true
    return true
end

-- Wrap tbl[name]: original first, then afterFn under pcall. The original's
-- return values pass through unchanged. ponytail: {orig(...)} drops trailing
-- nil returns; no current fix needs them.
function Fix.wrapAfter(tbl, name, expectFile, afterFn)
    if type(tbl) ~= "table" or type(tbl[name]) ~= "function" then
        print("[PZButter] " .. tostring(name) .. ": off (missing function)")
        return false
    end
    local ok, file = pcall(getFilenameOfClosure, tbl[name])
    if not ok or not same_file(file, expectFile) then
        print("[PZButter] " .. tostring(name) .. ": off (patched by "
            .. tostring(ok and file or "unknown file") .. ")")
        return false
    end
    local original = tbl[name]
    tbl[name] = function(...)
        local result = { original(...) }
        pcall(afterFn, ...)
        return unpack(result)
    end
    return true
end
