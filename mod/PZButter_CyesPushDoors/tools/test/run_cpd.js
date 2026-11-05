const fs=require('fs');const {lua,lauxlib,lualib,to_luastring}=require('fengari');
const L=lauxlib.luaL_newstate();lualib.luaL_openlibs(L);
const m=process.argv[2];
const set=(n,p)=>{lua.lua_pushstring(L,to_luastring(fs.readFileSync(p,'utf8')));lua.lua_setglobal(L,to_luastring(n));};
set('SRC_CONFIG',m+'/Config.lua');set('SRC_SETTINGS',m+'/Settings.lua');set('SRC_FEEDBACK',m+'/Feedback.lua');set('SRC_CORE',m+'/Core.lua');
set('SRC_HOOK_ORIG',m+'/Hook.lua');set('SRC_HOOK_NEW',process.argv[3]);
const st=lauxlib.luaL_dostring(L,to_luastring(fs.readFileSync(process.argv[4],'utf8')));
if(st!==lua.LUA_OK)console.log('ERR',lua.lua_tojsstring(L,-1));
