#!/usr/bin/env python3
"""Tests for tools/mp.sh. No game, no network beyond a local UDP bind."""

import os
import shutil
import socket
import subprocess
import tempfile
import time
import unittest
from pathlib import Path

MP = Path(__file__).resolve().parent / "mp.sh"


def free_port():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


class MpTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="pz-mp-test-"))
        self.home = self.tmp / "home"
        self.z = self.home / "Zomboid"
        self.pz = self.tmp / "pz"
        self.save = self.z / "Saves" / "Multiplayer" / "demo"
        self.save.mkdir(parents=True)
        (self.save / "map.bin").write_bytes(b"save data\n")
        (self.z / "mods").mkdir(parents=True)
        (self.z / "mods" / "default.txt").write_text(
            "mod = ZombieBuddy,\nmod = PZButter,\n")
        (self.z / "Server").mkdir()
        (self.pz / "projectzomboid").mkdir(parents=True)
        (self.pz / "projectzomboid" / "start-server.sh").write_text("#!/bin/sh\n")
        (self.pz / "projectzomboid" / "projectzomboid.jar").write_bytes(b"jar")
        (self.pz / "projectzomboid" / "ZombieBuddy.jar").write_bytes(b"zb")
        (self.pz / "projectzomboid" / "jre64" / "bin").mkdir(parents=True)
        (self.pz / "projectzomboid.sh").write_text("#!/bin/sh\n")

        self.server_ok = self.tmp / "server-ok.sh"
        self.server_ok.write_text(
            "#!/bin/sh\n"
            "echo '*** SERVER STARTED ****'\n"
            "while read line; do\n"
            "  case \"$line\" in quit) exit 0;; save) echo saved;; esac\n"
            "done\n")
        self.server_slow = self.tmp / "server-slow.sh"
        self.server_slow.write_text(
            "#!/bin/sh\n"
            "echo booting\n"
            "trap 'exit 0' TERM INT\n"
            "while :; do sleep 1; done\n")
        self.client_ok = self.tmp / "client-ok.sh"
        self.client_ok.write_text("#!/bin/sh\nsleep 0.1\nexit 0\n")
        for p in (self.server_ok, self.server_slow, self.client_ok):
            p.chmod(0o755)

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def env(self, **kw):
        e = os.environ.copy()
        e.update({
            "HOME": str(self.home),
            "Z": str(self.z),
            "PZ_DIR": str(self.pz),
            "MP_SKIP_PRECHECK": "1",
        })
        e.update(kw)
        return e

    def run_mp(self, args, env=None, timeout=30):
        return subprocess.run(
            ["bash", str(MP)] + args,
            env=env or self.env(),
            capture_output=True, text=True, timeout=timeout)

    def base(self, port):
        return ["--scene", "M1", "--list", "bare", "--save", "Multiplayer/demo",
                "--port", str(port), "--out", str(self.tmp / "runs")]

    def test_refuses_port_in_use(self):
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
        try:
            r = self.run_mp(self.base(port) + ["--dry-run"])
        finally:
            s.close()
        self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
        self.assertIn(f"port {port} is in use", r.stderr)
        self.assertIn("pid", r.stderr)
        self.assertIn("refusing to stop", r.stderr)

    def test_refuses_list_mismatch(self):
        r = self.run_mp(self.base(free_port()) + ["--dry-run"],
                        env=self.env(MP_SERVER_MODS_OVERRIDE="OtherMod"))
        self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
        self.assertIn("chosen:", r.stderr)
        self.assertIn("server:", r.stderr)
        self.assertIn("mod lists differ", r.stderr)

    def test_server_timeout_names_step(self):
        r = self.run_mp(self.base(free_port()) + ["--timeout", "1"],
                        env=self.env(MP_SERVER_CMD=str(self.server_slow),
                                     MP_CLIENT_CMD=str(self.client_ok)),
                        timeout=20)
        self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
        self.assertIn("waiting for server ready", r.stderr)
        self.assertIn("timeout 1s", r.stderr)

    def test_admin_password_passed(self):
        r = self.run_mp(self.base(free_port()) + ["--dry-run"])
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn("-adminpassword", r.stdout)
        self.assertIn("-servername demo-pzbutter-bench-", r.stdout)

    def test_source_save_unchanged(self):
        r = self.run_mp(self.base(free_port()), env=self.env(
            MP_SERVER_CMD=str(self.server_ok),
            MP_CLIENT_CMD=str(self.client_ok)), timeout=40)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertEqual((self.save / "map.bin").read_bytes(), b"save data\n")
        self.assertIn("done:", r.stdout)
        benches = list((self.z / "Saves" / "Multiplayer").glob("*pzbutter-bench*"))
        self.assertEqual(benches, [])

    def test_stops_only_its_own_pids(self):
        sleeper = subprocess.Popen(["sleep", "30"])
        try:
            r = self.run_mp(self.base(free_port()), env=self.env(
                MP_SERVER_CMD=str(self.server_ok),
                MP_CLIENT_CMD=str(self.client_ok)), timeout=40)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            time.sleep(0.2)
            self.assertIsNone(sleeper.poll(), "mp.sh killed a process it did not start")
        finally:
            sleeper.kill()
            sleeper.wait()


if __name__ == "__main__":
    unittest.main()
