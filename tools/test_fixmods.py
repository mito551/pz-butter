import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(__file__))
import fixmods


def make_mod(root, mod_id, info, lua=None, extra_dirs=()):
    d = os.path.join(root, mod_id)
    os.makedirs(os.path.join(d, "42"))
    with open(os.path.join(d, "42", "mod.info"), "w") as f:
        f.write("id=%s\nname=%s\n%s" % (mod_id, mod_id, info))
    for rel, text in (lua or {}).items():
        p = os.path.join(d, "42", "media", "lua", rel)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "w") as f:
            f.write(text)
    for sub in extra_dirs:
        os.makedirs(os.path.join(d, sub))
    return d


class PackageRulesTest(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp()

    def test_targetRequiredAndLoadedAfterPasses(self):
        d = make_mod(self.root, "PZButter_CompanionDogs",
                     "require=\\CompanionDogs\nloadModAfter=CompanionDogs\n",
                     {"client/X.lua": "print(1)\n"})
        self.assertEqual(fixmods.check(d), [])

    def test_requireWithoutTarget(self):
        d = make_mod(self.root, "PZButter_CompanionDogs",
                     "require=\\ZombieBuddy\nloadModAfter=CompanionDogs\n")
        self.assertIn("require= lacks target CompanionDogs", fixmods.check(d))

    def test_pzButterWithoutGuard(self):
        d = make_mod(self.root, "PZButter_KI5",
                     "require=\\PZButter,\\KI5Lib\nloadModAfter=PZButter,KI5Lib\n",
                     {"client/PZButter_KI5/Ki5.lua": "local x = PZButter.Census\n",
                      "client/PZButter_KI5/ok.lua": "if not (PZButter and PZButter.Census) then return end\n"})
        problems = fixmods.check(d)
        self.assertEqual(len([p for p in problems if "guard" in p]), 1, problems)
        self.assertTrue(any("Ki5.lua" in p for p in problems), problems)

    def test_shippedCopyWithUpstream(self):
        d = make_mod(self.root, "PZButter_CompanionDogs",
                     "require=\\CompanionDogs\nloadModAfter=CompanionDogs\n", extra_dirs=("upstream", "tools"))
        self.assertEqual(fixmods.check(d), [])  # the source tree may keep them
        shipped = fixmods.check(d, packaged=True)
        self.assertIn("packaged copy has upstream/", shipped)
        self.assertIn("packaged copy has tools/", shipped)

    def test_idAndJarRules(self):
        d = make_mod(self.root, "CompanionDogsFix",
                     "require=\\CompanionDogs\nloadModAfter=CompanionDogs\njavaJarFile=media/java/client/X.jar\n")
        problems = fixmods.check(d)
        self.assertIn("id CompanionDogsFix does not start with PZButter_", problems)
        self.assertIn("javaJarFile= without require=\\ZombieBuddy", problems)

    def test_multiTargetUsesLoadModAfterOnly(self):
        d = make_mod(self.root, "PZButter_VehicleRadio", "loadModAfter=RadioA,RadioB\n")
        self.assertEqual(fixmods.check(d), [])

    def test_unshippedList(self):
        p = os.path.join(self.root, "UNSHIPPED")
        with open(p, "w") as f:
            f.write("# one id per line\nPZButter_VES\n\n  PZButter_VLP  \n")
        self.assertEqual(fixmods.unshipped(p), {"PZButter_VES", "PZButter_VLP"})
        self.assertEqual(fixmods.unshipped(os.path.join(self.root, "missing")), set())
        for mid in ("PZButter_VES", "PZButter_KI5"):
            make_mod(self.root, mid, "loadModAfter=X\n")
        os.makedirs(os.path.join(self.root, "PZButter"))
        self.assertEqual([os.path.basename(d) for d in fixmods.shipped(self.root)], ["PZButter_KI5"])


class OverrideFreshnessTest(unittest.TestCase):
    """Plan 18 B-D8-9: full-file overrides drift when the target mod updates its file."""

    def setUp(self):
        self.root = tempfile.mkdtemp()
        self.install = tempfile.mkdtemp()

    def target(self, text):
        d = os.path.join(self.install, "123/mods/XensEnhancedGUI", "42", "media", "lua", "client", "XEG")
        os.makedirs(d)
        with open(os.path.join(d, "XEG_QuickAccess.lua"), "w") as f:
            f.write(text)
        return d

    def test_staleOverrideFlagged(self):
        self.target("lineA\nlineB\nnew upstream line\n")
        d = make_mod(self.root, "PZButter_XensEnhancedGUI",
                     "require=\\ZombieBuddy,\\XensEnhancedGUI\nloadModAfter=XensEnhancedGUI\n",
                     {"client/XEG/XEG_QuickAccess.lua": "lineA\nlineB\n-- PZ Butter: edit\n"})
        problems = [p for p in fixmods.check(d, install_root=self.install) if "stale" in p]
        self.assertEqual(len(problems), 1)
        self.assertIn("1 line(s) absent", problems[0])

    def test_freshOverridePasses(self):
        self.target("lineA\nlineB\n")
        d = make_mod(self.root, "PZButter_XensEnhancedGUI",
                     "require=\\ZombieBuddy,\\XensEnhancedGUI\nloadModAfter=XensEnhancedGUI\n",
                     {"client/XEG/XEG_QuickAccess.lua": "lineA\nlineB\n-- PZ Butter: edit\n"})
        self.assertEqual(fixmods.check(d, install_root=self.install), [])

    def test_targetNotInstalledSkipped(self):
        d = make_mod(self.root, "PZButter_XensEnhancedGUI",
                     "require=\\ZombieBuddy,\\XensEnhancedGUI\nloadModAfter=XensEnhancedGUI\n",
                     {"client/XEG/XEG_QuickAccess.lua": "lineA\n"})
        self.assertEqual(fixmods.check(d, install_root=self.install), [])
        self.assertEqual(fixmods.check(d, install_root=None), [])  # no --install: unchecked


class AddModTest(unittest.TestCase):
    """runlib.sh add_mod: run.sh --add-mod edits default.txt and the bench save's mods.txt with it."""

    def add(self, text, mid):
        import subprocess
        p = os.path.join(tempfile.mkdtemp(), "list.txt")
        with open(p, "w") as f:
            f.write(text)
        lib = os.path.join(os.path.dirname(os.path.abspath(__file__)), "runlib.sh")
        subprocess.run(["bash", "-c", 'source "$0"; add_mod "$1" "$2"', lib, p, mid], check=True)
        with open(p) as f:
            return f.read()

    def test_addModWritesBoth(self):
        block = "VERSION = 1,\n\nmods\n{\n    mod = ZombieBuddy,\n    mod = PZButter,\n}\n\nmaps\n{\n}\n"
        out = self.add(block, "PZButter_KI5")
        self.assertIn("    mod = PZButter,\n    mod = PZButter_KI5,\n}\n\nmaps", out)
        self.assertEqual(self.add(out, "PZButter_KI5"), out)  # already listed: unchanged
        self.assertEqual(self.add("mod = ZombieBuddy,\n", "PZButter_KI5"), "mod = ZombieBuddy,\n    mod = PZButter_KI5,\n")

    def run_lib(self, fn, text, mid):
        import subprocess
        p = os.path.join(tempfile.mkdtemp(), "list.txt")
        with open(p, "w") as f:
            f.write(text)
        lib = os.path.join(os.path.dirname(os.path.abspath(__file__)), "runlib.sh")
        subprocess.run(["bash", "-c", 'source "$0"; ' + fn + ' "$1" "$2"', lib, p, mid], check=True)
        with open(p) as f:
            return f.read()

    def test_removeModWritesBoth(self):
        block = "mods\n{\n    mod = CD_Perf,\n    mod = CD_PerfX,\n    mod = PZButter,\n}\n"
        out = self.run_lib("remove_mod", block, "CD_Perf")
        self.assertEqual(out, "mods\n{\n    mod = CD_PerfX,\n    mod = PZButter,\n}\n")  # only the exact id
        self.assertEqual(self.run_lib("remove_mod", out, "CD_Perf"), out)  # not listed: unchanged


if __name__ == "__main__":
    unittest.main()
