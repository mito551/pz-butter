-- R18S-B4: InvTooltipCache keys carry a 250 ms time bucket; entries from old buckets can
-- never hit again, so the cache must sweep them out — one item stays one entry however
-- long the session runs. Stubs enough of the game for ISToolTipInv.render to run.
local root = arg[0]:match("^(.*)/test/lua/[^/]+$") or "."

local NOW = 100000
Calendar = { getInstance = function() return { getTimeInMillis = function() return NOW end } end }
getMouseX = function() return 0 end
getMouseY = function() return 0 end
getCore = function() return { getScreenWidth = function() return 1920 end, getScreenHeight = function() return 1080 end } end
ISContextMenu = {}
ISToolTipInv = { render = function() end } -- the guarded stock the file captures at load
PZButter = { setting = function() return true end, invTooltipCache = nil }

local measures = 0
local function item()
    return {
        getCondition = function() return 75.5 end,
        getWeight = function() return 1.25 end,
        getFavorite = function() return false end,
        -- the game's own tooltip build: fills the tooltip with the real size
        DoTooltip = function(self, tip) measures = measures + 1 end,
    }
end
local function tip()
    return {
        width = 0, height = 0,
        setX = function() end, setY = function() end,
        setWidth = function(self, w) self.width = w end,
        setHeight = function(self, h) self.height = h end,
        setMeasureOnly = function() end,
        getWidth = function(self) return self.width end,
        getHeight = function(self) return self.height end,
        getX = function(self) return self.x end, getY = function(self) return self.y end,
        setX = function(self, x) self.x = x end, setY = function(self, y) self.y = y end,
    }
end
local function ui(t, it)
    return {
        item = it or item(), tooltip = tip(), followMouse = true, contextMenu = nil,
        backgroundColor = { a = 0, r = 0, g = 0, b = 0 }, borderColor = { a = 0, r = 0, g = 0, b = 0 },
        drawRect = function() end, drawRectBorder = function() end,
        adjustPositionToAvoidOverlap = function() end,
        setX = function() end, setY = function() end,
        setWidth = function() end, setHeight = function() end,
    }
end

dofile(root .. "/mod/PZButter/42/media/lua/client/PZButter/InvTooltipCache.lua")

-- one item, steady size: first render measures (DoTooltip via the measure pass + the draw pass...
-- the stub item counts tooltip builds), the next render in the same bucket hits.
local it = item()
local u = ui(nil, it)
ISToolTipInv.render(u)
local first = measures
ISToolTipInv.render(u)
assert(measures == first + 1, "a cache hit skips the measure pass (measure and draw on miss, draw only on hit)")

-- the cache never grows across buckets: every entry older than the current one sweeps out.
local cache = PZButter.invTooltipCache
for i = 1, 10 do
    NOW = NOW + 300
    ISToolTipInv.render(ui(nil, it))
end
local n = 0
for _ in pairs(cache) do n = n + 1 end
assert(n == 1, "stale buckets evicted, cache stays at one entry per item, got " .. n)

-- a second item in the same bucket is its own entry, and also sweeps with the bucket.
NOW = NOW + 300
ISToolTipInv.render(ui())
ISToolTipInv.render(u)
n = 0
for _ in pairs(cache) do n = n + 1 end
assert(n == 2, "two items two entries in one bucket, got " .. n)
NOW = NOW + 300
ISToolTipInv.render(u)
n = 0
for _ in pairs(cache) do n = n + 1 end
assert(n == 1, "the other item's entry swept with its bucket, got " .. n)

print("inv_tooltip_cache_test: ok")
