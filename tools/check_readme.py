#!/usr/bin/env python3
"""Check that every Settings key has a row in SETTINGS.md (the README's settings reference), and every row a key."""

import argparse
import os
import re
import sys

KEY = re.compile(r'^\s*\+\s*"([A-Za-z0-9_]+)=')
ROW = re.compile(r"^\|\s*`([A-Za-z0-9_]+)`\s*\|")
HEADING = re.compile(r"^(#{1,3})\s+(.*)$")


def settings_keys(source):
    return {m.group(1) for m in (KEY.match(line) for line in source.splitlines()) if m}


def readme_rows(readme):
    """key -> is the row under a Dev heading, within the Settings section."""
    rows = {}
    in_settings = False
    dev = False
    for line in readme.splitlines():
        h = HEADING.match(line)
        if h:
            level, title = h.group(1), h.group(2).strip()
            if level in ("#", "##"):
                in_settings = "settings" in title.lower()
                dev = False
            elif level == "###" and in_settings:
                dev = "dev" in title.lower()
            continue
        if not in_settings:
            continue
        m = ROW.match(line)
        if m:
            rows[m.group(1)] = dev
    return rows


def missing(keys, readme):
    rows = readme_rows(readme)
    without_row = set()
    for key in keys:
        if key not in rows:
            without_row.add(key)
        elif key.startswith("dev") and not rows[key]:
            without_row.add(key)
    stale = set(rows) - set(keys)
    return without_row, stale


def main(argv=None):
    here = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    ap = argparse.ArgumentParser()
    ap.add_argument("--settings", default=here + "/src/pzbutter/Settings.java")
    ap.add_argument("--readme", default=here + "/SETTINGS.md")
    args = ap.parse_args(argv)
    with open(args.settings) as f:
        keys = settings_keys(f.read())
    with open(args.readme) as f:
        readme = f.read()
    no_row, stale = missing(keys, readme)
    ok = True
    if no_row:
        ok = False
        print("settings keys without a README row: " + ", ".join(sorted(no_row)))
    if stale:
        ok = False
        print("README rows without a settings key: " + ", ".join(sorted(stale)))
    if ok:
        print("readme: %d settings keys, all covered" % len(keys))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
