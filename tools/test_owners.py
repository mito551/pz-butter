import contextlib
import io
import os
import subprocess
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import owners


def _mod(root, folder, mod_id, pkg, jar_name, classes):
    """A fake mod folder: root/folder/42/mod.info + its jar holding the given classes."""
    vdir = os.path.join(root, folder, "42")
    os.makedirs(os.path.join(vdir, "media", "java"), exist_ok=True)
    lines = ["name = %s" % folder]
    if mod_id is not None:
        lines.append("id = %s" % mod_id)
    if pkg is not None:
        lines.append("javaPkgName = %s" % pkg)
    if jar_name is not None:
        lines.append("javaJarFile = media/java/%s" % jar_name)
        with zipfile.ZipFile(os.path.join(vdir, "media", "java", jar_name), "w") as zf:
            for c in classes:
                zf.writestr(c, b"\xca\xfe\xba\xbe")
    with open(os.path.join(vdir, "mod.info"), "w") as f:
        f.write("\n".join(lines) + "\n")
    return vdir


def _modlist(path, ids):
    with open(path, "w") as f:
        f.write("VERSION = 1,\n\nmods\n{\n")
        for i in ids:
            f.write("    mod = %s,\n" % i)
        f.write("}\n")


class ModTableTest(unittest.TestCase):
    def test_modTableFromModInfo(self):
        with tempfile.TemporaryDirectory() as root:
            _mod(root, "modA", "modA", "a.b", "x.jar", ["a/b/X.class"])
            _mod(root, "modA-extra", "modA", "a.b", "y.jar", ["c/Y.class"])
            ml = os.path.join(root, "modlist.used.txt")
            _modlist(ml, ["modA"])
            self.assertEqual(owners.mod_table(ml, [root]), {"a.b": "modA", "c": "modA"})

    def test_duplicateModIdUsesListOrder(self):
        with tempfile.TemporaryDirectory() as td:
            local, ws = os.path.join(td, "local"), os.path.join(td, "ws")
            os.makedirs(local)
            os.makedirs(ws)
            _mod(local, "modA", None, "a.b", "x.jar", ["a/b/X.class"])
            _mod(ws, "modA", None, "x.y", "q.jar", ["x/y/Q.class"])
            ml = os.path.join(td, "modlist.used.txt")
            _modlist(ml, ["modA"])
            err = io.StringIO()
            with contextlib.redirect_stderr(err):
                table = owners.mod_table(ml, [local, ws])
            self.assertEqual(table, {"a.b": "modA"})
            self.assertIn("duplicate", err.getvalue())


class OwnerTest(unittest.TestCase):
    def test_leafWalkSkipsJdk(self):
        s = [{"frames": ["java.util.HashMap.get", "a.b.X.f", "zombie.X.g"]}]
        self.assertEqual(owners.fold(s, {"a.b": "modA"}, None), {"modA": 1})

    def test_kahluaSplitByLuaOut(self):
        s = [{"frames": ["se.krka.kahlua.vm.LuaClosure.call"]}] * 10
        self.assertEqual(owners.fold(s, {}, {"modA": 30, "modB": 10}),
                         {"lua:modA": 7.5, "lua:modB": 2.5})

    def test_kahluaWithoutLuaOut(self):
        s = [{"frames": ["se.krka.kahlua.vm.LuaClosure.call"]}] * 10
        self.assertEqual(owners.fold(s, {}, None), {"lua (no lua.out)": 10})

    def test_jniLeafIsNative(self):
        s = [{"frames": ["zombie.iso.LightingJNI.update", "zombie.GameWindow.mainThreadStep"]}]
        self.assertEqual(owners.fold(s, {}, None), {"native:libLighting64": 1})

    def test_patchedGameMethodLabelled(self):
        s = [{"frames": ["zombie.X.g"]}]
        self.assertEqual(owners.fold(s, {}, None, patched={"zombie.X.g": ["modA"]}),
                         {"game (patched by: modA)": 1})

    def test_allocSamplesUseEventThread(self):
        values = {"startTime": "2026-11-03T13:12:43.577544382+01:00",
                  "eventThread": {"osName": "MainThread", "javaName": "MainThread"},
                  "stackTrace": {"frames": [{"method": {"type": {"package": {"name": "zombie/iso"},
                                                                 "name": "IsoMovingObject"},
                                                         "name": "update"}}]}}
        when, thread, frames_list = owners._sample_of(values)
        self.assertEqual(thread, "MainThread")
        self.assertEqual(frames_list, ["zombie.iso.IsoMovingObject.update"])


class RefusalTest(unittest.TestCase):
    def test_noWindowRefuses(self):
        with tempfile.TemporaryDirectory() as run:
            r = subprocess.run(
                [sys.executable, os.path.join(os.path.dirname(os.path.abspath(__file__)), "owners.py"), run],
                capture_output=True, text=True)
            self.assertEqual(r.returncode, 1)
            self.assertIn("no window", r.stdout + r.stderr)

    def test_jfrNotCoveringRefuses(self):
        with tempfile.TemporaryDirectory() as run:
            with open(os.path.join(run, "marks.out"), "w") as f:
                f.write("mark scene.start 1000\nmark scene.end 10000\n")
            orig = owners._jfr_stamps
            owners._jfr_stamps = lambda rd: (4000, 12000)  # 3 s late: past coverage's 2 s tolerance
            try:
                err = io.StringIO()
                with contextlib.redirect_stderr(err), contextlib.redirect_stdout(err):
                    with self.assertRaises(SystemExit) as cm:
                        owners.main([run])
                self.assertEqual(cm.exception.code, 1)
                self.assertRegex(err.getvalue(), r"game\.jfr .*starts late")
            finally:
                owners._jfr_stamps = orig


if __name__ == "__main__":
    unittest.main()
