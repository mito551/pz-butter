#!/usr/bin/env python3
"""
Generates the WKT_Perf overrides from the installed Whose Key Is This? files.

    build_wkt.py <WhoseKeyIsThis/42/media/lua dir> <WKT_Perf/42/media/lua dir>

Every replacement is asserted to match exactly once, so a changed original fails loudly.
Also writes ../upstream/perf-<file>.patch (apply from the mod's 42/media/lua with patch -p1).
"""
import sys, os, difflib

SRC, DST = sys.argv[1], sys.argv[2]
TOOLS = os.path.dirname(os.path.abspath(__file__))
UPSTREAM = os.path.join(TOOLS, "..", "upstream")

# Whose Key Is This? has no modversion, so the guard compares the length of the original file
# (characters without line ends) read at load time through getModFileReader.
GUARD = '''--[[
    WKT_Perf build of @FILE@. Regenerate with WKT_Perf/tools/build_wkt.py after every update
    of Whose Key Is This?.
]]
do
    local ok, r = pcall(getModFileReader, "WhoseKeyIsThis", "media/lua/@REL@", false)
    if ok and r then
        local n, line = 0, r:readLine()
        while line do n = n + #line; line = r:readLine() end
        r:close()
        if n ~= @CHARS@ then
            print("[WKT_Perf] WARNING: @FILE@ of Whose Key Is This? changed since WKT_Perf was built; regenerate WKT_Perf with tools/build_wkt.py")
        end
    end
end

'''


def edit(rel, fn):
    p = os.path.join(SRC, rel)
    raw = open(p, encoding="utf-8", newline="").read()
    t = raw.replace("\r\n", "\n")

    def rep(old, new, count=1):
        nonlocal t
        n = t.count(old)
        assert n == count, f"{rel}: expected {count} got {n}: {old[:70]!r}"
        t = t.replace(old, new)

    def between(start, end, new):
        nonlocal t
        assert t.count(start) == 1, f"{rel}: start {start[:50]!r} x{t.count(start)}"
        assert t.count(end) == 1, f"{rel}: end {end[:50]!r} x{t.count(end)}"
        a = t.index(start)
        b = t.index(end, a)
        t = t[:a] + new + t[b:]

    fn(rep, between)
    chars = sum(len(line) for line in raw.replace("\r\n", "\n").split("\n"))
    name = os.path.basename(rel)
    t = (GUARD.replace("@FILE@", name).replace("@REL@", rel).replace("@CHARS@", str(chars))) + t

    out = os.path.join(DST, rel)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    open(out, "w", encoding="utf-8", newline="").write(t)
    print("wrote", rel, len(t.splitlines()), "lines")

    eol = "\r\n" if "\r\n" in raw else "\n"
    diff = difflib.unified_diff(raw.splitlines(True), t.replace("\n", eol).splitlines(True),
                                "a/" + rel, "b/" + rel)
    os.makedirs(UPSTREAM, exist_ok=True)
    open(os.path.join(UPSTREAM, "perf-" + rel.replace("/", "_") + ".patch"), "w",
         encoding="utf-8", newline="").write("".join(diff))


def ground(rep, between):
    advance = open(os.path.join(TOOLS, "ground_advance.lua"), encoding="utf-8").read()
    between("--- Advances the search. Called every frame; does a slice of a sweep, or",
            "--- Draws the labels. Coordinates are element-relative, so the caller passes",
            advance)


def inventory(rep, between):
    # colourFor memoises into colourCache; reset whenever refresh() rebuilds markedNames
    rep("local markedNames = {}  -- display name -> {r, g, b}\n",
        "local markedNames = {}  -- display name -> {r, g, b}\nlocal colourCache = {}  -- text -> colour|false\n")
    rep("    marked = {}\n    markedNames = {}\n    anyMarked = false\n",
        "    marked = {}\n    markedNames = {}\n    colourCache = {}\n    anyMarked = false\n")

    old_colourFor = """local function colourFor(text)
    if type(text) ~= "string" then return nil end
    local colour = markedNames[text]
    if colour then return colour end
    -- the pane appends " (3)" to stacked rows
    local base = string.match(text, "^(.*) %(%d+%)$")
    if base then return markedNames[base] end
    return nil
end"""
    new_colourFor = """local function colourFor(text)
    if type(text) ~= "string" then return nil end
    if colourCache[text] ~= nil then
        local c = colourCache[text]
        if c == false then return nil end
        return c
    end
    local colour = markedNames[text]
    if colour then colourCache[text] = colour; return colour end
    -- the pane appends " (3)" to stacked rows
    local base = string.match(text, "^(.*) %(%d+%)$")
    if base then
        local bc = markedNames[base]
        colourCache[text] = (bc == nil) and false or bc
        return bc
    end
    colourCache[text] = false
    return nil
end"""
    rep(old_colourFor, new_colourFor)

    old_drawStar = """local function drawStar(button)
    local ok, item = pcall(function()
        return button.inventory and button.inventory:getContainingItem() or nil
    end)
    if not ok or item == nil then return end
    if not WKT.isFavorite(item) then return end"""
    new_drawStar = """local function drawStar(button)
    local now = getTimestampMs()
    local c = button.wktStarCache
    local item = nil
    -- buttons are pooled and handed to other containers, so the cache is per container too
    if not (c and c.inv == button.inventory and (now - c.at) < 500) then
        if button.inventory then
            local ok
            ok, item = pcall(button.inventory.getContainingItem, button.inventory)
            if not ok then item = nil end
        else
            item = nil
        end
        local fav = false
        if item ~= nil then fav = WKT.isFavorite(item) end
        button.wktStarCache = { inv = button.inventory, item = item, fav = fav, at = now }
        c = button.wktStarCache
    else
        item = c.item
    end
    if not c or c.item == nil then return end
    if not c.fav then return end"""
    rep(old_drawStar, new_drawStar)


if __name__ == "__main__":
    edit("client/WKT_GroundKeys.lua", ground)
    edit("client/WKT_InventoryMark.lua", inventory)
