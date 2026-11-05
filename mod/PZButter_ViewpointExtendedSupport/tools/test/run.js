const fs = require('fs');
const { lua, lauxlib, lualib, to_luastring } = require('fengari');
const L = lauxlib.luaL_newstate();
lualib.luaL_openlibs(L);
const orig = fs.readFileSync(process.argv[2], 'utf8');
const patch = fs.readFileSync(process.argv[3], 'utf8');
const harness = fs.readFileSync(process.argv[4], 'utf8');
for (const [k, v] of [['SRC_ORIG', orig], ['SRC_PATCH', patch]]) {
  lua.lua_pushstring(L, to_luastring(v)); lua.lua_setglobal(L, to_luastring(k));
}
const st = lauxlib.luaL_dostring(L, to_luastring(harness));
if (st !== lua.LUA_OK) console.log('ERR', lua.lua_tojsstring(L, -1));
