import os, subprocess, sys, tempfile, textwrap, unittest
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import workshop_vdf


class VdfTest(unittest.TestCase):
    def test_escape(self):
        self.assertEqual(workshop_vdf.esc('a "b"\\c\nd'), 'a \\"b\\"\\\\c\\nd')

    def run_cli(self, ident):
        d = tempfile.mkdtemp()
        for n, t in (("t", "[B42] Butter Frames"), ("d", '[b]Hi[/b] "q"\nline2')):
            with open(os.path.join(d, n), "w") as f:
                f.write(t)
        with open(os.path.join(d, "p.png"), "w") as f:
            f.write("x")
        out = os.path.join(d, "o.vdf")
        r = subprocess.run([sys.executable, os.path.join(HERE, "workshop_vdf.py"), "--id", ident, "--content", d,
                            "--preview", os.path.join(d, "p.png"), "--title-file", os.path.join(d, "t"),
                            "--desc-file", os.path.join(d, "d"), "--note", "n", "--out", out], capture_output=True, text=True)
        return r, out

    def test_file(self):
        r, out = self.run_cli("123")
        self.assertEqual(r.returncode, 0, r.stderr)
        with open(out) as f:
            s = f.read()
        self.assertIn('"publishedfileid"\t\t"123"', s)
        self.assertIn('[b]Hi[/b] \\"q\\"\\nline2', s)
        self.assertNotIn('"visibility"', s)

    def test_id_zero_refused(self):
        self.assertNotEqual(self.run_cli("0")[0].returncode, 0)


class WorkflowTest(unittest.TestCase):
    def test_corrupt_zombiebuddy_download_is_rejected(self):
        workflow_path = os.path.join(HERE, "..", ".github", "workflows", "workshop-publish.yml")
        with open(workflow_path) as f:
            workflow = f.read()
        block = workflow.split("      - name: Fetch ZombieBuddy and collect the game jars\n", 1)[1]
        block = block.split("\n      - name:", 1)[0]
        script = textwrap.dedent(block.split("        run: |\n", 1)[1])

        root = tempfile.mkdtemp()
        game = os.path.join(root, "home", "pzserver", "java")
        os.makedirs(game)
        with open(os.path.join(game, "projectzomboid.jar"), "w") as f:
            f.write("fixture")
        bin_dir = os.path.join(root, "bin")
        os.makedirs(bin_dir)
        curl = os.path.join(bin_dir, "curl")
        with open(curl, "w") as f:
            f.write("#!/bin/sh\nwhile [ \"$1\" != \"-o\" ]; do shift; done\nprintf corrupt > \"$2\"\n")
        os.chmod(curl, 0o755)
        env = dict(os.environ, RUNNER_TEMP=root, HOME=os.path.join(root, "home"),
                   ZB_VERSION="test", GITHUB_ENV=os.path.join(root, "github-env"),
                   PATH=bin_dir + os.pathsep + os.environ["PATH"])
        result = subprocess.run(["bash", "-euo", "pipefail", "-c", script],
                                env=env, capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0,
                            "a corrupt ZombieBuddy download was accepted:\n" + result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
