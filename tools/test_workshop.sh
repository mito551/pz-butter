#!/usr/bin/env bash
# Tests for tools/workshop.sh. Uses a temporary build tree; no game or network.
set -u

TOOLS="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$TOOLS/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
FAIL=0
ok() { echo "ok $1"; }
bad() { echo "not ok $1"; FAIL=1; }

make_build() {
    local d="$1" signed="$2"
    mkdir -p "$d/42/media/java/common" "$d/42/media/lua/client" "$d/common"
    echo "id=PZButter" > "$d/42/mod.info"
    echo "jar" > "$d/42/media/java/common/PZButter.jar"
    echo "keep" > "$d/common/.keep"
    echo "notes" > "$d/42/test-notes.txt"
    echo "return {}" > "$d/42/media/lua/client/Ok.lua"
    if [[ "$signed" == yes ]]; then echo "sig" > "$d/42/media/java/common/PZButter.jar.zbs"; fi
}

BUILD="$TMP/build/mod/PZButter"
make_build "$BUILD" yes
PZ_BUILD_MOD="$BUILD" "$TOOLS/workshop.sh" "$TMP/out" >"$TMP/signed.log" 2>&1
if [[ $? -eq 0 && -f "$TMP/out/PZButter/Contents/mods/PZButter/42/mod.info" ]]; then
    ok signedLayout
else
    bad signedLayout
    cat "$TMP/signed.log"
fi
if [[ -f "$TMP/out/PZButter/preview.png" && -f "$TMP/out/PZButter/workshop.txt" ]]; then ok previewAndText; else bad previewAndText; fi
grep -q '^title=\[B42\] Butter Frames (Comprehensive' "$TMP/out/PZButter/workshop.txt" && ok title || bad title
grep -q '^tags=Build 42;Misc$' "$TMP/out/PZButter/workshop.txt" && ok tags || bad tags
grep -q '^description=Butter Frames is' "$TMP/out/PZButter/workshop.txt" && ok description || bad description
[[ ! -e "$TMP/out/PZButter/Contents/mods/PZButter/common/.keep" ]] && ok noKeep || bad noKeep
[[ ! -e "$TMP/out/PZButter/Contents/mods/PZButter/42/test-notes.txt" ]] && ok noTestFiles || bad noTestFiles
[[ -f "$TMP/out/PZButter/Contents/mods/PZButter/42/media/java/common/PZButter.jar.zbs" ]] && ok signatureKept || bad signatureKept

# fix mods: a shipped one is packed without upstream/, an UNSHIPPED one is left out, the text names the shipped one
FM="$TMP/build/mod/PZButter_CompanionDogs"
mkdir -p "$FM/42/media/lua/client" "$FM/upstream"
printf 'id=PZButter_CompanionDogs\nrequire=\\CompanionDogs\nloadModAfter=CompanionDogs\n' > "$FM/42/mod.info"
mkdir -p "$TMP/build/mod/PZButter_VES/42"
printf 'id=PZButter_VES\nrequire=\\VES\nloadModAfter=VES\n' > "$TMP/build/mod/PZButter_VES/42/mod.info"
echo "PZButter_VES" > "$TMP/build/mod/UNSHIPPED"
PZ_BUILD_MOD="$BUILD" "$TOOLS/workshop.sh" "$TMP/out" >"$TMP/fix.log" 2>&1
if [[ $? -eq 0 && -f "$TMP/out/PZButter/Contents/mods/PZButter_CompanionDogs/42/mod.info" ]]; then ok fixModPacked; else bad fixModPacked; cat "$TMP/fix.log"; fi
[[ ! -e "$TMP/out/PZButter/Contents/mods/PZButter_CompanionDogs/upstream" ]] && ok fixModNoUpstream || bad fixModNoUpstream
[[ ! -e "$TMP/out/PZButter/Contents/mods/PZButter_VES" ]] && ok unshippedLeftOut || bad unshippedLeftOut
grep -q 'PZButter_CompanionDogs (standalone)' "$TMP/out/PZButter/workshop.txt" && ok fixModListed || bad fixModListed

BUILD2="$TMP/build2/mod/PZButter"
make_build "$BUILD2" no
PZ_BUILD_MOD="$BUILD2" "$TOOLS/workshop.sh" "$TMP/out2" >"$TMP/unsigned.log" 2>&1
rc=$?
if [[ $rc -ne 0 ]] && grep -q 'unsigned build; pass --unsigned' "$TMP/unsigned.log"; then ok refusesUnsigned; else bad refusesUnsigned; cat "$TMP/unsigned.log"; fi
PZ_BUILD_MOD="$BUILD2" "$TOOLS/workshop.sh" --unsigned "$TMP/out2" >"$TMP/unsigned2.log" 2>&1
if [[ $? -eq 0 && -f "$TMP/out2/PZButter/Contents/mods/PZButter/42/mod.info" ]]; then ok unsignedAllowed; else bad unsignedAllowed; cat "$TMP/unsigned2.log"; fi

exit "$FAIL"
