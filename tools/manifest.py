"""manifest: sha256 tree manifest and compare.

Stdlib only. Usage:
    manifest.py write DIR OUT
    manifest.py check DIR MANIFEST
Pure functions: build_manifest(root), diff_manifest(a, b).
"""
import hashlib
import json
import os
import sys


def build_manifest(root):
    out = {}
    for dirpath, dirnames, filenames in os.walk(root):
        for name in filenames:
            full = os.path.join(dirpath, name)
            rel = os.path.relpath(full, root)
            h = hashlib.sha256()
            with open(full, "rb") as f:
                for chunk in iter(lambda: f.read(65536), b""):
                    h.update(chunk)
            out[rel] = h.hexdigest()
    return out


class ManifestDiff(dict):
    def __init__(self, changed, added, removed):
        super().__init__()
        self["changed"] = list(changed)
        self["added"] = list(added)
        self["removed"] = list(removed)

    def _lists(self):
        return self["changed"], self["added"], self["removed"]

    def __getitem__(self, key):
        if isinstance(key, int):
            return self._lists()[key]
        return super().__getitem__(key)

    def __iter__(self):
        yield self["changed"]
        yield self["added"]
        yield self["removed"]

    def __len__(self):
        return 3

    def __contains__(self, key):
        if isinstance(key, str) and key in ("changed", "added", "removed"):
            return super().__contains__(key)
        allpaths = set(self["changed"]) | set(self["added"]) | set(self["removed"])
        return key in allpaths

    def __bool__(self):
        return bool(self["changed"] or self["added"] or self["removed"])


def diff_manifest(a, b):
    changed = sorted(p for p in a if p in b and a[p] != b[p])
    added = sorted(p for p in b if p not in a)
    removed = sorted(p for p in a if p not in b)
    return ManifestDiff(changed, added, removed)


def _write(dir_path, out_path):
    m = build_manifest(dir_path)
    with open(out_path, "w") as f:
        json.dump(m, f, sort_keys=True, indent=2)
        f.write("\n")


def _check(dir_path, manifest_path):
    with open(manifest_path) as f:
        expected = json.load(f)
    actual = build_manifest(dir_path)
    diff = diff_manifest(expected, actual)
    if not diff:
        print("source save unchanged")
        return 0
    for label in ("changed", "added", "removed"):
        for p in diff[label]:
            print("%s: %s" % (label, p))
    return 1


def main(argv=None):
    argv = argv if argv is not None else sys.argv[1:]
    if not argv or argv[0] not in ("write", "check"):
        print("usage: manifest.py write DIR OUT | check DIR MANIFEST", file=sys.stderr)
        return 2
    if argv[0] == "write":
        if len(argv) != 3:
            print("usage: manifest.py write DIR OUT", file=sys.stderr)
            return 2
        _write(argv[1], argv[2])
        return 0
    if len(argv) != 3:
        print("usage: manifest.py check DIR MANIFEST", file=sys.stderr)
        return 2
    return _check(argv[1], argv[2])


if __name__ == "__main__":
    raise SystemExit(main())
