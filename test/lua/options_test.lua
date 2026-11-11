-- R18S-B5: every key row in the client Options.lua must appear once — a duplicate list
-- entry makes two tickboxes for one setting. Checks the raw table rows (tab + "key",).
local root = arg[0]:match("^(.*)/test/lua/[^/]+$") or "."
local path = root .. "/mod/PZButter/42/media/lua/client/PZButter/Options.lua"
local seen, dup = {}, {}
for row in io.lines(path) do
    local key = row:match('^\t"([^"]+)",%s*$')
    if key then
        if seen[key] then dup[#dup + 1] = key end
        seen[key] = true
    end
end
assert(#dup == 0, "duplicate keys in Options.lua: " .. table.concat(dup, ", "))
print("options_test: ok")
