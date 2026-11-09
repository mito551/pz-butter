#!/usr/bin/env python3
"""Write the steamcmd workshop_build_item file for Butter Frames.

  workshop_vdf.py --id ID --content DIR --preview FILE --title-file F --desc-file F --note TEXT --out FILE [--appid N]

--id is the published file id of the existing Workshop item (never 0: the first upload is done in the game).
steamcmd reads backslash escapes, so backslash, quote and newline are escaped.
"""
import argparse
import os
import sys


def esc(s):
    return s.replace("\\", "\\\\").replace('"', '\\"').replace("\r", "").replace("\n", "\\n")


def vdf(fields):
    return 'workshopitem\n{\n' + ''.join('\t"%s"\t\t"%s"\n' % (k, esc(v)) for k, v in fields) + '}\n'


def main(argv):
    p = argparse.ArgumentParser()
    p.add_argument("--id", required=True)
    p.add_argument("--content", required=True)
    p.add_argument("--preview", required=True)
    p.add_argument("--title-file", required=True)
    p.add_argument("--desc-file", required=True)
    p.add_argument("--note", default="")
    p.add_argument("--out", required=True)
    p.add_argument("--appid", default="108600")
    a = p.parse_args(argv)
    if not a.id.isdigit() or int(a.id) == 0:
        sys.exit("workshop_vdf: --id must be the existing Workshop item's id (upload once in the game first)")
    for path in (a.content, a.preview, a.title_file, a.desc_file):
        if not os.path.exists(path):
            sys.exit("workshop_vdf: missing " + path)
    title = open(a.title_file, encoding="utf-8").read().strip()
    desc = open(a.desc_file, encoding="utf-8").read().strip()
    if not title or not desc:
        sys.exit("workshop_vdf: empty title or description")
    fields = [("appid", a.appid), ("publishedfileid", a.id),
              ("contentfolder", os.path.abspath(a.content)), ("previewfile", os.path.abspath(a.preview)),
              ("visibility", "0"), ("title", title), ("description", desc), ("changenote", a.note)]
    open(a.out, "w", encoding="utf-8").write(vdf(fields))


if __name__ == "__main__":
    main(sys.argv[1:])
