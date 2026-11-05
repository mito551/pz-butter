local ORIG = "/home/mitori/Sonic/SteamLibrary/steamapps/workshop/content/108600/3784348098/mods/WhoseKeyIsThis/42/media/lua/client/WKT_InventoryMark.lua"
local OVR = "42/media/lua/client/WKT_InventoryMark.lua"
local PERF = "/home/mitori/Zomboid/mods/WKT_Perf/42/media/lua/client/WKT_PerfPatch.lua"

local results, failures = {}, 0
local function check(name, ok, detail)
  results[#results+1] = (ok and "PASS " or "FAIL ") .. name .. (not ok and detail and ("  " .. tostring(detail)) or "")
  if not ok then failures = failures + 1 end
end

local function makeItems()
  return {
    { name = "AlphaKey", r = 0.2, g = 0.3, b = 0.4, fav = true },
    { name = "BetaKey", r = 0.7, g = 0.1, b = 0.9, fav = false },
  }
end

local function makeEnv(path)
  local W = { clock = 1000 }
  function W.now() return W.clock end
  local items = makeItems()
  for _, it in ipairs(items) do function it:getName() return it.name end end

  local env = setmetatable({}, { __index = _G })
  env._G = env
  env.print = function() end
  env.getTimestampMs = function() return W.clock end
  env.getModFileReader = function() return nil end
  env.require = function() end
  env.getSpecificPlayer = function() return { isDead = function() return false end } end
  env.getTexture = function() return true end
  env.circleTex = nil

  env.WKT = {
    isEnabled = function() return true end,
    getPlayerKeys = function(playerObj) return items end,
    isPointing = function(item) return true end,
    getData = function(item) return item end,
    canReadLocation = function() return true end,
    markerColor = function(p, item, data) return item.r, item.g, item.b end,
    isFavorite = function(item) return item.fav end,
    claimPatch = function() return true end,
    isKeyItem = function(item) return item.keyId ~= nil end,
    getKeyId = function(item) return 1 end,
    isCarriedBy = function() return true end,
  }

  env.ISInventoryItem = { renderItemIcon = function(self, ...) return end }
  env.ISButton = { render = function(self) end }
  env.ISInventoryPage = {
    addContainerButton = function(self, container, texture, name, tooltip)
      local b = { inventory = container and container.inv or nil, render = nil, wktStarred = false, height = 20 }
      function b:drawTextureScaledAspect(...) end
      return b
    end
  }

  -- original pane render stub: draws a couple of rows
  env.ISInventoryPane = {}
  env.ISInventoryPane.render = function(self)
    if self and self.drawText then
      self:drawText("AlphaKey", 0, 0, 1, 1, 1, 1, nil)
      self:drawText("AlphaKey (3)", 0, 10, 1, 1, 1, 1, nil)
      self:drawText("Nope", 0, 20, 1, 1, 1, 1, nil)
    end
  end

  local f = assert(io.open(path)); local src = f:read("*a"); f:close()
  assert(load(src, path, "t", env))()
  env.W = W
  env.items = items
  return env
end

local function runRender(env)
  local captured = {}
  local pane = {
    drawText = function(self, text, x, y, r, g, b, a, font)
      captured[text] = { r = r, g = g, b = b }
    end
  }
  local ok, err = pcall(function() env.ISInventoryPane.render(pane) end)
  return ok, err, captured
end

local function test_colour_equivalent()
  local o = makeEnv(ORIG); local v = makeEnv(OVR)
  local ok1, err1, co = runRender(o)
  local ok2, err2, cv = runRender(v)
  local same = ok1 and ok2 and
    (tostring(co["AlphaKey"].r) == tostring(cv["AlphaKey"].r)) and
    (tostring(co["AlphaKey (3)"].r) == tostring(cv["AlphaKey (3)"].r))
  check("test_colour_equivalent", same, (err1 or "") .. " " .. (err2 or ""))
end

local function test_colour_cache_reset_on_refresh()
  -- ensure changing marker color after a refresh shows: clock advance 1000+ then render
  local v = makeEnv(OVR)
  local items = v.items
  items[1].r = 0.9
  v.W.clock = v.W.clock + 1100
  local ok, err, c = runRender(v)
  check("test_colour_cache_reset_on_refresh", ok and c["AlphaKey"] and c["AlphaKey"].r == 0.9, tostring(err))
end

local function starDraws(env, btn, n)
  local draws = 0
  btn.drawTextureScaledAspect = function() draws = draws + 1 end
  for _ = 1, n do btn:render() end
  return draws
end

local function container(item)
  return { item = item, getContainingItem = function(self) return self.item end }
end

local function test_star_equivalent_and_cached()
  local o, v = makeEnv(ORIG), makeEnv(OVR)
  local bo = o.ISInventoryPage.addContainerButton(nil, { inv = container(o.items[1]) })
  local bv = v.ISInventoryPage.addContainerButton(nil, { inv = container(v.items[1]) })
  check("test_star_equivalent_and_cached", starDraws(o, bo, 1) == 1 and starDraws(v, bv, 1) == 1, "favourite star not drawn alike")
  local calls = 0
  local fav = v.WKT.isFavorite
  v.WKT.isFavorite = function(item) calls = calls + 1 return fav(item) end
  v.W.clock = v.W.clock + 1000
  starDraws(v, bv, 10)
  check("test_star_equivalent_and_cached", calls == 1, "isFavorite called " .. calls .. " times in 500 ms")
  -- a pooled button handed to another container must not keep the old answer
  bv.inventory = container(v.items[2])     -- not a favourite
  local d = starDraws(v, bv, 1)
  check("test_star_equivalent_and_cached", d == 0, "reused button kept the previous container's star")
end

-- original functions, read from the installed WKT_Shared.lua (not copied)
local SHARED = "/home/mitori/Sonic/SteamLibrary/steamapps/workshop/content/108600/3784348098/mods/WhoseKeyIsThis/42/media/lua/shared/WKT_Shared.lua"
local function sharedChunk(from, to)
  local f = assert(io.open(SHARED)); local src = f:read("*a"):gsub("\r\n", "\n"); f:close()
  local a = assert(src:find(from, 1, true), from)
  local b = assert(src:find(to, a, true), to)
  return src:sub(a, b - 1)
end

-- the WKT_PerfPatch additions, loaded on their own (the rest of the file is the arrow UI)
local function perfChunk(env)
  local f = assert(io.open(PERF)); local src = f:read("*a"); f:close()
  local a = assert(src:find("-- Item 7 / item 2 helpers", 1, true))
  local line = src:sub(1, a):match(".*\n()") or a
  assert(load(src:sub(line), "WKT_PerfPatch tail", "t", env))()
end

local function keyWorld()
  local items = {}
  for i = 1, 6 do
    local it = { id = i, keyId = (i ~= 3) and (100 + i) or -1, isKey = (i ~= 5) }
    items[i] = it
  end
  local inv = {}
  function inv:getAllEvalRecurse(fn, list)
    local out = { n = 0 }
    for _, it in ipairs(items) do if fn(it) then out.n = out.n + 1; out[out.n] = it end end
    return { size = function() return out.n end, get = function(_, i) return out[i + 1] end }
  end
  local player = { invCalls = 0 }
  function player:getInventory() self.invCalls = self.invCalls + 1 return inv end
  return items, inv, player
end

local function keysEnv(items, jar)
  local env = setmetatable({}, { __index = _G })
  env._G = env
  env.clock = 5000
  env.getTimestampMs = function() return env.clock end
  env.clearMopt = function() end
  env.ArrayList = { new = function() return {} end }
  env.WKT = {
    isKeyItem = function(it) return it.isKey end,
    getKeyId = function(it) return it.keyId end,
    isCarriedBy = function(p, it) return it.id ~= 6 end,
    refreshPointers = function() end,
  }
  assert(load(sharedChunk("function WKT.getPlayerKeys(playerObj)", "\n---------------------------------------------------------------------------\n-- What a key opens"), "orig getPlayerKeys", "t", env))()
  env.origKeys = env.WKT.getPlayerKeys
  if jar then
    env.wktKeysIn = function(inv)
      local out = {}
      for _, it in ipairs(items) do if it.isKey then out[#out + 1] = it end end
      return out
    end
  end
  perfChunk(env)
  return env
end

local function ids(list) local s = {} for i, it in ipairs(list) do s[i] = it.id end return table.concat(s, ",") end

local function test_get_player_keys_equivalent_lua_and_jar()
  local items, _, player = keyWorld()
  local lua, jar = keysEnv(items, false), keysEnv(items, true)
  local want = ids(lua.origKeys(player))
  check("test_get_player_keys_equivalent_lua_and_jar", want == "1,2,4" and ids(lua.WKT.getPlayerKeys(player)) == want
    and ids(jar.WKT.getPlayerKeys(player)) == want, "orig=" .. want)
end

local function test_get_player_keys_copy()
  local items, _, player = keyWorld()
  local env = keysEnv(items, false)
  local first = env.WKT.getPlayerKeys(player)
  local calls = player.invCalls
  table.remove(first, 1)
  first[#first + 1] = "junk"
  local second = env.WKT.getPlayerKeys(player)
  check("test_get_player_keys_copy", ids(second) == "1,2,4" and player.invCalls == calls, "second=" .. ids(second) .. " inventory reads " .. (player.invCalls - calls))
  env.clock = env.clock + 1001
  env.WKT.getPlayerKeys(player)
  check("test_get_player_keys_copy", player.invCalls > calls, "memo never expires")
end

local function favItem(engineFav, hasMd, mdFav)
  local it = { fav = engineFav, md = hasMd and { WKT_fav = mdFav } or nil, sets = 0 }
  function it:isFavorite() return self.fav end
  function it:hasModData() return self.md ~= nil end
  function it:getModData() self.md = self.md or {} return self.md end
  function it:setFavorite(on) self.fav = on; self.sets = self.sets + 1 end
  return it
end

local function test_is_favorite_equivalent()
  local cases = { { false, false, nil }, { true, false, nil }, { false, true, true }, { true, true, true }, { false, true, nil }, { true, true, nil } }
  local bad
  for i, c in ipairs(cases) do
    local o = setmetatable({ WKT = {} }, { __index = _G })
    assert(load(sharedChunk("local reconciled = {}", "function WKT.setFavorite(item, on)"), "orig isFavorite", "t", o))()
    local v = setmetatable({ WKT = { isFavorite = function() end, refreshPointers = function() end, getPlayerKeys = function() end },
      clearMopt = function() end, getTimestampMs = function() return 0 end }, { __index = _G })
    perfChunk(v)
    local a, b = favItem(c[1], c[2], c[3]), favItem(c[1], c[2], c[3])
    local ra1, rb1 = o.WKT.isFavorite(a), v.WKT.isFavorite(b)
    local ra2, rb2 = o.WKT.isFavorite(a), v.WKT.isFavorite(b)
    local sa = tostring(ra1) .. tostring(ra2) .. a.sets .. tostring(a.md and a.md.WKT_fav)
    local sb = tostring(rb1) .. tostring(rb2) .. b.sets .. tostring(b.md and b.md.WKT_fav)
    if sa ~= sb then bad = "case " .. i .. ": orig " .. sa .. " ovr " .. sb break end
  end
  check("test_is_favorite_equivalent", bad == nil, bad)
end

test_colour_equivalent()
test_colour_cache_reset_on_refresh()
test_star_equivalent_and_cached()
test_get_player_keys_equivalent_lua_and_jar()
test_get_player_keys_copy()
test_is_favorite_equivalent()

print(table.concat(results, "\n"))
print(failures == 0 and "ALL OK" or ("FAILURES: " .. failures))
os.exit(failures == 0 and 0 or 1)