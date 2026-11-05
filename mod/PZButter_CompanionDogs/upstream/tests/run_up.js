const fs=require('fs');const {lua,lauxlib,lualib,to_luastring}=require('fengari');
const L=lauxlib.luaL_newstate();lualib.luaL_openlibs(L);
[['SRC_ORIG',2],['SRC_UP',3]].forEach(([n,i])=>{lua.lua_pushstring(L,to_luastring(fs.readFileSync(process.argv[i],'utf8')));lua.lua_setglobal(L,to_luastring(n));});
const st=lauxlib.luaL_dostring(L,to_luastring(fs.readFileSync(process.argv[4],'utf8')));
if(st!==lua.LUA_OK)console.log('ERR',lua.lua_tojsstring(L,-1));
