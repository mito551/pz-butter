#!/usr/bin/env bash
# Task 6 checks: override equivalence, build asserts, upstream patches apply. Run from anywhere.
set -u
cd "$(dirname "$0")/../.."
ORIG=/home/mitori/Sonic/SteamLibrary/steamapps/workshop/content/108600/3740052292/mods/CompanionDogs/42/media/lua
FAIL=0
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

lua tools/test/cd_override_harness.lua || FAIL=1

# a changed original must stop the build (control: the unchanged copy builds)
mkdir -p "$TMP/m/42/media"
cp -r "$ORIG" "$TMP/m/42/media/lua"
cp "$ORIG/../../mod.info" "$TMP/m/42/mod.info"
if ! python3 -I tools/build_cd.py "$TMP/m/42/media/lua" "$TMP/dst" >/dev/null 2>&1; then
  echo "FAIL test_build_asserts_on_changed_original (control build failed)"; FAIL=1
fi
sed -i 's/CD.perfCount("sq.wildanimals"/CD.perfCount("sq.wildanimalsX"/' "$TMP/m/42/media/lua/server/systems/CompanionDogs_Hunting.lua"
if python3 -I tools/build_cd.py "$TMP/m/42/media/lua" "$TMP/dst2" 2>&1 | grep -q AssertionError; then
  echo "PASS test_build_asserts_on_changed_original"
else
  echo "FAIL test_build_asserts_on_changed_original"; FAIL=1
fi

# each generated upstream patch applies to a pristine copy of the original lua folder
rm -rf "$TMP/pristine"; cp -r "$ORIG" "$TMP/pristine"
for p in upstream/perf-*.patch; do
  if (cd "$TMP/pristine" && patch --dry-run -p1 < "$OLDPWD/$p") >/dev/null 2>&1; then
    echo "PASS test_upstream_patches_apply $(basename "$p")"
  else
    echo "FAIL test_upstream_patches_apply $(basename "$p")"; FAIL=1
  fi
done

luac -p 42/media/lua/server/CompanionDogs/combat/Threat.lua 42/media/lua/server/systems/CompanionDogs_Hunting.lua \
  42/media/lua/server/systems/CompanionDogs_Feed.lua 42/media/lua/server/CompanionDogs_Companion.lua \
  42/media/lua/shared/CD_PerfNear.lua || FAIL=1

if [ "$FAIL" -ne 0 ]; then echo "FAILURES"; exit 1; fi
echo "ALL OK"
