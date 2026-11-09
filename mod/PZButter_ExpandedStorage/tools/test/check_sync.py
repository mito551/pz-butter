"""Fails if the Lua fallback table and the Java properties table differ.  Run: python3 tools/test/check_sync.py"""
import re, pathlib
root = pathlib.Path(__file__).resolve().parents[2]
props = dict(re.findall(r'(\w+)=(\d+)', (root / "tools/resources/expandedstorage/capacities.properties").read_text()))
lua = dict(re.findall(r'\["(\w+)"\]\s*=\s*(\d+)', (root / "42/media/lua/shared/ExpandedStorage/ExpandedStorage.lua").read_text()))
assert props == lua, f"out of sync: {sorted(set(props) ^ set(lua))} {[k for k in props if k in lua and props[k] != lua[k]]}"
print(f"sync ok ({len(props)} sprites)")
