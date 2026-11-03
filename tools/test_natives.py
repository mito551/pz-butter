"""Tests for natives.py (D6 native profile).

Fixture provenance: the first rows are verbatim from a real
`perf report --stdio --no-children --sort comm,dso,sym` capture
(perf 7.2.11, 2026-11-03, HotLoop.java recorded with
-XX:+DumpPerfMapAtExit, /tmp/opencode/perffix/report-resolved.txt).
The libLighting64.so and [JIT] tid 335952 rows are crafted in the
same format from game shapes seen in the S3 runs' perf maps.
"""
import contextlib
import io
import os
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import coverage  # noqa: E402
import natives  # noqa: E402
import owners  # noqa: E402


FIXTURE = """\
# Samples: 41K of event 'cpu-clock:u'
# Overhead  Command          Shared Object         Symbol
# ........  ...........  ...  ....................  .....
    44.75%  main             [JIT] tid 1447194     [.] void HotLoop.churn(long)
    43.21%  MainThread       [JIT] tid 1447194     [.] void HotLoop.burn(long)
     9.06%  MainThread       [JIT] tid 1447194     [.] void HotLoop.spin(long)
     0.18%  main             [JIT] tid 1447194     [.] Interpreter
     0.11%  MainThread       [JIT] tid 1447194     [.] Interpreter
     0.08%  main             libjvm.so             [.] ObjArrayAllocator::initialize(HeapWordImpl**) const
     0.06%  main             libc.so.6             [.] __memset_avx2_unaligned_erms
     0.05%  main             libjvm.so             [.] TypeArrayKlass::allocate_common(int, bool, JavaThread*)
     0.04%  main             libjvm.so             [.] G1Allocator::unsafe_max_tlab_alloc()
     0.03%  main             libc.so.6             [.] clock_gettime@@GLIBC_2.17
     2.00%  MainThread       libLighting64.so      [.] Java_zombie_iso_LightingJNI_update
     1.80%  MainThread       libLighting64.so      [.] Java_zombie_iso_LightingJNI_WaitingForMain
     1.50%  MainThread       libLighting64.so      [.] Lighting::update()
     0.40%  MainThread       libLighting64.so      [.] Lighting::recompute()
     0.35%  MainThread       libLighting64.so      [.] Lighting::sweep()
     0.30%  MainThread       libLighting64.so      [.] Lighting::build()
     0.25%  main             libLighting64.so      [.] Lighting::clear()
     0.20%  main             libLighting64.so      [.] Lighting::queue()
     0.15%  main             libLighting64.so      [.] Lighting::sync()
     0.10%  main             libLighting64.so      [.] Lighting::idle()
     0.05%  main             libLighting64.so      [.] Lighting::init()
     0.02%  main             libLighting64.so      [.] Lighting::free()
     0.30%  MainThread       [JIT] tid 335952      [.] Lzombie/iso/IsoCell;::render
     0.20%  MainThread       [JIT] tid 335952      [.] void zombie.iso.IsoGridSquare.<init>(zombie.iso.IsoCell, zombie.iso.SliceY, int, int, int)
     0.50%  main             libfmod.so            [.] FMOD::Studio::System::update()
"""


class ParseReportTest(unittest.TestCase):
    def test_parsesCommDsoSym(self):
        rows = natives.parse_report(FIXTURE)
        by = {(c, d, s): p for c, d, s, p in rows}
        self.assertEqual(by[("main", "[JIT] tid 1447194", "void HotLoop.churn(long)")], 44.75)
        self.assertEqual(by[("MainThread", "[JIT] tid 1447194", "void HotLoop.burn(long)")], 43.21)
        # symbol keeps single spaces, parens and commas
        self.assertEqual(
            by[("main", "libjvm.so", "ObjArrayAllocator::initialize(HeapWordImpl**) const")], 0.08
        )
        self.assertEqual(by[("main", "libc.so.6", "clock_gettime@@GLIBC_2.17")], 0.03)
        # header and separator lines never leak through
        self.assertTrue(all(not s.startswith("#") for _, _, s, _ in rows))
        self.assertTrue(all(p >= 0 for *_, p in rows))

    def test_libraryAbove5PercentGetsTopSymbols(self):
        sh = natives.shares(natives.parse_report(FIXTURE))
        libs = sh["libraries"]
        self.assertIn("libLighting64.so", libs)
        self.assertNotIn("libfmod.so", libs)  # 0.5% of samples
        self.assertNotIn("libjvm.so", libs)
        syms = libs["libLighting64.so"]["symbols"]
        self.assertLessEqual(len(syms), 10)
        self.assertEqual(syms[0], ("Java_zombie_iso_LightingJNI_update", 2.0))
        self.assertTrue(all(syms[i][1] >= syms[i + 1][1] for i in range(len(syms) - 1)))
        # 12 lighting symbols in, the two smallest are cut
        self.assertNotIn("Lighting::init()", [s for s, _ in syms])
        self.assertNotIn("Lighting::free()", [s for s, _ in syms])
        self.assertTrue(libs["libLighting64.so"]["pct"] > 5.0)
        # per-thread dso shares sum to 100 within a thread
        thr = sh["threads"]
        self.assertEqual(set(thr), {"main", "MainThread"})
        self.assertAlmostEqual(sum(thr["MainThread"].values()), 100.0, places=0)
        self.assertAlmostEqual(sum(thr["main"].values()), 100.0, places=0)

    def test_zeroPercentRowsTolerated(self):
        rows = [
            ("main", "libjvm.so", "Interpreter", 0.0),
            ("main", "libjvm.so", "Interpreter", 1.0),
            ("GC Thread#1", "libjvm.so", "SpinPause", 0.0),
        ]
        sh = natives.shares(rows)
        self.assertEqual(sh["threads"]["main"]["libjvm.so"], 100.0)
        self.assertEqual(sh["threads"]["GC Thread#1"]["libjvm.so"], 0.0)

    def test_jitFramesKeepJavaNames(self):
        rows = natives.parse_report(FIXTURE)
        syms = {s for _, _, s, _ in rows}
        self.assertIn("Lzombie/iso/IsoCell;::render", syms)
        self.assertIn(
            "void zombie.iso.IsoGridSquare.<init>(zombie.iso.IsoCell, zombie.iso.SliceY, int, int, int)",
            syms,
        )
        self.assertIn("void HotLoop.churn(long)", syms)
        # the [.] sample-type prefix is stripped from the symbol
        self.assertNotIn("[.] void HotLoop.churn(long)", syms)


class MapLifecycleTest(unittest.TestCase):
    def test_mapRestoredAndRemoved(self):
        with tempfile.TemporaryDirectory() as run:
            content = "0x1 0x2 void Foo.bar()\n"
            with open(os.path.join(run, "perf-424242424.map"), "w") as f:
                f.write(content)
            with natives.restore_map(run):
                placed = "/tmp/perf-424242424.map"
                self.assertTrue(os.path.isfile(placed))
                with open(placed) as f:
                    self.assertEqual(f.read(), content)
            self.assertFalse(os.path.exists("/tmp/perf-424242424.map"))

    def test_missingMapProceeds(self):
        with tempfile.TemporaryDirectory() as run:
            with natives.restore_map(run):
                self.assertFalse(os.path.exists("/tmp/perf-424242424.map"))


class JniCallersTest(unittest.TestCase):
    def test_jniCallersCountLibs(self):
        stacks = [
            ["org.lwjgl.opengl.GL11C.nglTexImage2D", "zombie.core.textures.Texture.bind", "zombie.core.SpriteRenderer.render"],
            ["org.lwjgl.opengl.GL11C.nglTexImage2D", "zombie.core.textures.Texture.bind"],
            ["zombie.iso.LightingJNI.update", "zombie.iso.IsoCell.doLighting"],
            ["zombie.iso.LightingJNI.update", "zombie.iso.IsoGridSquare.doLightingLocal"],
            ["java.lang.Thread.sleep", "zombie.game.GameTime.Actualize"],
        ]
        got = natives.jni_callers(stacks)
        self.assertEqual(got["liblwjgl"], [("zombie.core.textures.Texture.bind", 2)])
        self.assertEqual(
            got["libLighting64"],
            [("zombie.iso.IsoCell.doLighting", 1), ("zombie.iso.IsoGridSquare.doLightingLocal", 1)],
        )
        self.assertNotIn("libjvm", got)


class RefusalTest(unittest.TestCase):
    def _run(self, argv):
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            try:
                natives.main(argv)
            except SystemExit as e:
                return e.code, err.getvalue()
        return 0, err.getvalue()

    def test_noWindowRefuses(self):
        with tempfile.TemporaryDirectory() as run:
            code, err = self._run([run])
        self.assertEqual(code, 1)
        self.assertIn("no window", err)

    def test_jfrNotCoveringRefuses(self):
        with tempfile.TemporaryDirectory() as run:
            with open(os.path.join(run, "marks.out"), "w") as f:
                f.write("mark scene.start 1000\nmark scene.end 5000\n")
            with mock.patch.object(owners, "_jfr_stamps", lambda rd: (1000.0, 5000.0)), \
                    mock.patch.object(coverage, "_status", lambda *a: "does not cover the window"):
                code, err = self._run([run])
        self.assertEqual(code, 1)
        self.assertIn("game.jfr", err)

    def test_noPerfDataRefuses(self):
        with tempfile.TemporaryDirectory() as run:
            with open(os.path.join(run, "marks.out"), "w") as f:
                f.write("mark scene.start 1000\nmark scene.end 5000\n")
            with mock.patch.object(owners, "_jfr_stamps", lambda rd: (1000.0, 5000.0)), \
                    mock.patch.object(coverage, "_status", lambda *a: "ok"):
                code, err = self._run([run])
        self.assertEqual(code, 1)
        self.assertIn("no perf.data", err)


if __name__ == "__main__":
    unittest.main()
