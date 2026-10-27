import os
import tempfile
import unittest

import manifest


class TestManifest(unittest.TestCase):
    def _make_dir(self, files):
        d = tempfile.mkdtemp()
        for name, content in files.items():
            p = os.path.join(d, name)
            parent = os.path.dirname(p)
            if parent != d:
                os.makedirs(parent, exist_ok=True)
            with open(p, "wb") as f:
                data = content.encode() if isinstance(content, str) else content
                f.write(data)
        return d

    def test_manifest_same(self):
        d = self._make_dir({"a.txt": "hello", "sub/b.txt": "world"})
        m1 = manifest.build_manifest(d)
        m2 = manifest.build_manifest(d)
        self.assertEqual(m1, m2)

    def test_manifest_detects_change(self):
        d = self._make_dir({"a.txt": "hello", "b.txt": "x"})
        before = manifest.build_manifest(d)
        with open(os.path.join(d, "a.txt"), "wb") as f:
            f.write(b"hallo")
        with open(os.path.join(d, "c.txt"), "wb") as f:
            f.write(b"new")
        os.remove(os.path.join(d, "b.txt"))
        after = manifest.build_manifest(d)
        diff = manifest.diff_manifest(before, after)
        changed = diff["changed"] if isinstance(diff, dict) else diff[0]
        added = diff["added"] if isinstance(diff, dict) else diff[1]
        removed = diff["removed"] if isinstance(diff, dict) else diff[2]
        self.assertIn("a.txt", changed)
        self.assertIn("c.txt", added)
        self.assertIn("b.txt", removed)

    def test_manifest_ignores_nothing(self):
        d = self._make_dir({".hidden": "secret", "visible.txt": "v"})
        m = manifest.build_manifest(d)
        self.assertIn(".hidden", m)


if __name__ == "__main__":
    unittest.main()
