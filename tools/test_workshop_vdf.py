import os, subprocess, sys, tempfile, unittest
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import workshop_vdf


class VdfTest(unittest.TestCase):
    def test_escape(self):
        self.assertEqual(workshop_vdf.esc('a "b"\\c\nd'), 'a \\"b\\"\\\\c\\nd')

    def run_cli(self, ident):
        d = tempfile.mkdtemp()
        for n, t in (("t", "[B42] Butter Frames"), ("d", '[b]Hi[/b] "q"\nline2')):
            open(os.path.join(d, n), "w").write(t)
        open(os.path.join(d, "p.png"), "w").write("x")
        out = os.path.join(d, "o.vdf")
        r = subprocess.run([sys.executable, os.path.join(HERE, "workshop_vdf.py"), "--id", ident, "--content", d,
                            "--preview", os.path.join(d, "p.png"), "--title-file", os.path.join(d, "t"),
                            "--desc-file", os.path.join(d, "d"), "--note", "n", "--out", out], capture_output=True, text=True)
        return r, out

    def test_file(self):
        r, out = self.run_cli("123")
        self.assertEqual(r.returncode, 0, r.stderr)
        s = open(out).read()
        self.assertIn('"publishedfileid"\t\t"123"', s)
        self.assertIn('[b]Hi[/b] \\"q\\"\\nline2', s)
        self.assertNotIn('"visibility"', s)

    def test_id_zero_refused(self):
        self.assertNotEqual(self.run_cli("0")[0].returncode, 0)


if __name__ == "__main__":
    unittest.main()
