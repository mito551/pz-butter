#!/usr/bin/env bash
# PZ Butter: pack build/mod/PZButter into the game's Workshop layout.
# Usage: tools/workshop.sh [--unsigned] OUT_DIR
set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
TOOLS="$SCRIPT_DIR"
# shellcheck source=tools/runlib.sh
source "$TOOLS/runlib.sh"

UNSIGNED=0
if [[ ${1:-} == "--unsigned" ]]; then UNSIGNED=1; shift; fi
OUT_DIR="${1:-}"
[[ -n "$OUT_DIR" ]] || die "usage: workshop.sh [--unsigned] OUT_DIR"

SRC="${PZ_BUILD_MOD:-$REPO_ROOT/build/mod/PZButter}"
[[ -d "$SRC" ]] || die "no build at $SRC (run ./build.sh)"
[[ -f "$SRC/42/mod.info" ]] || die "no mod.info under $SRC"
if (( UNSIGNED == 0 )) && ! find "$SRC" -name '*.zbs' -print -quit | grep -q .; then
    die "unsigned build; pass --unsigned"
fi

PREVIEW="$REPO_ROOT/mod/preview.png"
[[ -f "$PREVIEW" ]] || die "no preview at $PREVIEW"
TEMPLATE="$TOOLS/workshop.txt.in"
[[ -f "$TEMPLATE" ]] || die "no template at $TEMPLATE"

DESC="$(
    python3 - "$REPO_ROOT/README.md" <<'PY'
import re, sys
text = open(sys.argv[1], encoding="utf-8").read().splitlines()
paras, buf = [], []
def flush():
    if buf:
        paras.append(" ".join(x.strip() for x in buf).strip())
        buf.clear()
for line in text:
    if line.startswith("#"):
        flush()
        continue
    if not line.strip() or line.lstrip().startswith(("|", "```", "- ", "* ")):
        flush()
        continue
    buf.append(line)
flush()

out = []
# The README intro is the first paragraph after the title.
intro = [p for p in paras if p.lower().startswith("pz butter is")][:1]
# The optional flags section is prose only; no flags are recommended yet.
flags = [p for p in paras if "jvm flag" in p.lower() or "no flag is recommended" in p.lower()][:1]
for p in intro + flags:
    p = re.sub(r"\s+", " ", p).replace("\r", " ").strip()
    if p:
        out.append("description=" + p)
if not out:
    out.append("description=PZ Butter: smoother frames for Project Zomboid Build 42.")
for i, line in enumerate(out):
    if i and line != "description=":
        print("description=")
    print(line)
PY
)"

DEST="$OUT_DIR/PZButter"
rm -rf -- "$DEST"
mkdir -p "$DEST/Contents/mods" || die "cannot create $DEST"
cp -a "$SRC" "$DEST/Contents/mods/PZButter" || die "copy failed"
# Build bookkeeping and local test leftovers do not belong in a release.
find "$DEST/Contents/mods/PZButter" \( -name '.keep' -o -name '.DS_Store' -o -name '*.class' -o -name '*.tmp' -o -name 'test-*' \) -delete
find "$DEST/Contents/mods/PZButter" -type d -name 'test' -prune -exec rm -rf {} +
# Fix mods (Plan 19): every shipped PZButter_<Target> beside PZButter, without its tools/ and upstream/.
FIX_NOTE=""
while IFS= read -r fm; do
    id="$(basename "$fm")"
    cp -a "$fm" "$DEST/Contents/mods/$id" || die "copy of $id failed"
    rm -rf -- "$DEST/Contents/mods/$id/tools" "$DEST/Contents/mods/$id/upstream"
    find "$DEST/Contents/mods/$id" \( -name '.keep' -o -name '.DS_Store' -o -name '*.tmp' -o -name 'test-*' -o -name '.pzbutter-build' \) -delete
    python3 "$TOOLS/fixmods.py" --packaged "$DEST/Contents/mods/$id" || die "$id fails the fix-mod rules"
    if grep -Eq '^require=.*\\PZButter(,|$)' "$fm/42/mod.info"; then need=" (needs PZ Butter)"; else need=" (standalone)"; fi
    FIX_NOTE="${FIX_NOTE:+$FIX_NOTE, }$id$need"
done < <(python3 "$TOOLS/fixmods.py" --shipped "$(dirname "$SRC")")
if [[ -n "$FIX_NOTE" ]]; then
    DESC="$DESC
description=
description=Fix mods in this item, each switched on by enabling it: $FIX_NOTE."
fi
cp -p "$PREVIEW" "$DEST/preview.png" || die "cannot copy preview.png"

python3 - "$TEMPLATE" "$DEST/workshop.txt" "$DESC" <<'PY'
import sys
template = open(sys.argv[1], encoding="utf-8").read()
desc = sys.argv[3].rstrip("\n")
out = template.replace("@DESCRIPTION@", desc)
open(sys.argv[2], "w", encoding="utf-8").write(out)
PY

echo "workshop: $DEST"
