import unittest

import sysmon

STAT1 = """cpu  100 0 100 800 0 0 0 0 0 0
cpu0 50 0 50 400 0 0 0 0 0 0
cpu1 50 0 50 400 0 0 0 0 0 0
"""

STAT2 = """cpu  200 0 200 1600 0 0 0 0 0 0
cpu0 150 0 150 700 0 0 0 0 0 0
cpu1 50 0 50 900 0 0 0 0 0 0
"""

MEMINFO = """MemTotal:       16000000 kB
MemFree:         4000000 kB
MemAvailable:    8000000 kB
SwapTotal:       2000000 kB
SwapFree:        1500000 kB
"""

VMSTAT = """pswpin 10
pswpout 20
pgmajfault 5
nr_free_pages 100
"""


class TestSysmon(unittest.TestCase):
    def test_cpu_busy(self):
        busy = sysmon.cpu_busy(sysmon.parse_stat(STAT1), sysmon.parse_stat(STAT2))
        self.assertAlmostEqual(busy["cpu0"], 40.0, places=1)
        self.assertAlmostEqual(busy["cpu1"], 0.0, places=1)

    def test_parse_meminfo(self):
        m = sysmon.parse_meminfo(MEMINFO)
        self.assertEqual(m["MemAvailable"], 8000000)
        self.assertEqual(m["SwapTotal"], 2000000)

    def test_vmstat_deltas(self):
        v = sysmon.parse_vmstat(VMSTAT)
        self.assertEqual(v["pswpin"], 10)
        self.assertEqual(v["pswpout"], 20)
        self.assertEqual(v["pgmajfault"], 5)


if __name__ == "__main__":
    unittest.main()
