#!/usr/bin/env bash
# Task 3 checks
set -u
cd "$(dirname "$0")/../.."
ORIG=/home/mitori/Sonic/SteamLibrary/steamapps/workshop/content/108600/3784348098/mods/WhoseKeyIsThis/42/media/lua
FAIL=0
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

lua tools/test/inv_harness.lua || FAIL=1

mkdir -p "$TMP/m/42/media"; cp -r "$ORIG" "$TMP/m/42/media/lua"
if ! python3 -I tools/build_wkt.py "$TMP/m/42/media/lua" "$TMP/dst" >/dev/null 2>&1; then
  echo "FAIL test_build_asserts_on_changed_original (control build failed)"; FAIL=1
fi
sed -i 's/local function colourFor(text)/local function colourForZZ(text)/' "$TMP/m/42/media/lua/client/WKT_InventoryMark.lua"
if python3 -I tools/build_wkt.py "$TMP/m/42/media/lua" "$TMP/dst2" 2>&1 | grep -q AssertionError; then
  echo "PASS test_build_asserts_on_changed_original"
else
  echo "FAIL test_build_asserts_on_changed_original"; FAIL=1
fi

rm -rf "$TMP/pristine"; cp -r "$ORIG" "$TMP/pristine"
for p in upstream/perf-*.patch; do
  if (cd "$TMP/pristine" && patch --dry-run -p1 < "$OLDPWD/$p") >/dev/null 2>&1; then
    echo "PASS test_upstream_patches_apply $(basename "$p")"
  else
    echo "FAIL test_upstream_patches_apply $(basename "$p")"; FAIL=1
  fi
done

# run ground too
bash tools/test/run_ground.sh >/dev/null 2>&1 || FAIL=1

luac -p 42/media/lua/client/*.lua || FAIL=1
if [ "$FAIL" -ne 0 ]; then echo "FAILURES"; exit 1; fi
echo "ALL OK"
