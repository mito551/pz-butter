import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(__file__))

import report


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(text)


def make_inputs(root, vehicle="none"):
    run = os.path.join(root, "2026-11-03_131237-S3-bare")
    write(os.path.join(run, "run.opts"), "scene=S3\nlist=bare\nsave=Sandbox/x\n")
    write(os.path.join(run, "marks.out"),
          "mark jvm.start 1000\nmark menu.ready 39000\nmark world.ready 60000\n"
          "mark scene.start 80000\nmark scene.end 140000\n")
    frames = "\n".join("%d %d %.3f %.3f %.3f" % (80000 + i * 16, i + 1, 16.0 + (10.0 if i % 10 == 0 else 0.0), 4.0, 3.0)
                       for i in range(60))
    write(os.path.join(run, "frames.out"), frames + "\n")
    present = "\n".join("%d %d %.3f %.3f %.3f %.3f" % (80000 + i * 16, i + 1, 16.0, 12.0, 2.0, 1.0)
                        for i in range(60))
    write(os.path.join(run, "present.out"), present + "\n")
    write(os.path.join(run, "sysmon.csv"),
          "epoch_ms,cpu0,mem_avail_mb,swap_used_mb,pswpin,pswpout,pgmajfault,gpu_util,gpu_mem_mb,gpu_clock_mhz,rss_mb,threads\n"
          "80000,10,8000,0,0,0,0,40.0,2000,900,4000,40\n"
          "80016,10,8000,0,0,0,0,42.0,2000,900,4000,40\n")
    write(os.path.join(run, "gc.log"),
          "[79.500s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 12M->2M(256M) 3.456ms\n"
          "[80.100s][info][gc] GC(1) Pause Young (Normal) (G1 Evacuation Pause) 12M->2M(256M) 1.000ms\n")
    write(os.path.join(run, "owners.json"), '{"window": [80000, 140000], "owners": {"game": 60, "Viewpoint": 40},'
          ' "slow_owners": {"game": 50, "Viewpoint": 50}, "alloc_owners": {"game": 100},'
          ' "totals": {"samples": 100, "slow_samples": 100, "alloc_samples": 100}}')
    write(os.path.join(run, "natives.json"), '{"libraries": {"libLighting64.so": {"pct": 7.5,'
          ' "symbols": [["Java_zombie_iso_LightingJNI_update", 7.5]], "jni_callers": [["zombie.iso.IsoCell.doLighting", 3]]}},'
          ' "threads": {}}')
    write(os.path.join(run, "spikes.out"), "80010 1 60 driving\n80020 2 30 moving\n")
    write(os.path.join(run, "hitches.out"), "80010 1 60ms lua=-\n")
    write(os.path.join(run, "stalls.out"), "80010 320 ms late\n")

    notes = os.path.join(root, "notes")
    write(os.path.join(notes, "a-lighting.md"),
          "scene: S3\nwindow: scene\ncost_p50_ms: 25\ncost_p99_ms: 60\nfrequency: 3/min\n"
          "owner: Viewpoint\ncause: lighting probes\ngain: 5\nrisk: low\nvehicle: setting\n\n"
          "Turn the light probes down.\n")
    write(os.path.join(notes, "b-heavy.md"),
          "scene: S3\nwindow: scene\ncost_p50_ms: 40\ncost_p99_ms: 90\nfrequency: 1/min\n"
          "owner: game\ncause: heavy pass\ngain: 8\nrisk: high\nvehicle: patch mod\n\nBigger, riskier.\n")
    write(os.path.join(notes, "c-quiet.md"),
          "scene: S3\nwindow: scene\ncost_p50_ms: 1\nfrequency: 60/min\nowner: game\n"
          "cause: quiet residue\ngain: 0\nrisk: low\nvehicle: none\n\nMeasured; not worth it.\n")
    return run, notes


class ReportTest(unittest.TestCase):
    def test_gcTimeDecorator(self):
        d = tempfile.mkdtemp()
        write(os.path.join(d, "marks.out"), "mark jvm.start 1000\n")
        write(os.path.join(d, "gc.log"),
              "[2026-11-05T15:36:40.123+0100][79.500s][info][gc] GC(0) Pause Young (Normal) 12M->2M(256M) 3.456ms\n")
        self.assertEqual(report._gc(d, 0, 10**9, [])["count"], 1)

    def test_reportReproduces(self):
        root = tempfile.mkdtemp(prefix="ptrep-")
        run, notes = make_inputs(root)
        out1, out2 = os.path.join(root, "a.md"), os.path.join(root, "b.md")
        report.main([run, "--notes", notes, "--out", out1])
        report.main([run, "--notes", notes, "--out", out2])
        with open(out1, "rb") as f1, open(out2, "rb") as f2:
            self.assertEqual(f1.read(), f2.read())

    def test_rankByGainPerRisk(self):
        def note(name, gain, risk):
            return {"file": name, "keys": {"gain": gain, "risk": risk}, "body": ""}
        ranked = report.rank_notes([note("high.md", "8", "high"), note("low.md", "5", "low")])
        self.assertEqual(ranked[0]["file"], "low.md")
        self.assertAlmostEqual(report._gain({"gain": "5 ms/frame"}), 5.0)

    def test_noneVehicleListedSeparately(self):
        root = tempfile.mkdtemp(prefix="ptnone-")
        run, notes = make_inputs(root)
        text = report.report_text([run], notes)
        findings, measured = text.split("## Measured, does not matter")
        self.assertIn("quiet residue", measured)
        self.assertNotIn("quiet residue", findings)

    def test_findingsRankedAndTablesPresent(self):
        root = tempfile.mkdtemp(prefix="ptbody-")
        run, notes = make_inputs(root)
        text = report.report_text([run], notes)
        self.assertLess(text.index("lighting probes"), text.index("heavy pass"))
        self.assertIn("| Viewpoint | 40.00 | 50.00 | 0.00 |", text)
        self.assertIn("libLighting64.so", text)
        self.assertIn("GC pauses: 2", text)
        self.assertIn("GPU busy 41.0 %", text)


if __name__ == "__main__":
    unittest.main()
