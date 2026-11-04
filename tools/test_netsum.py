import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import netsum


class NetSumTest(unittest.TestCase):
    def test_totalsAndRatio(self):
        net = """
1000 client ZombieList 10 0 1000 0
1000 client ZombieList 5 2 500 200
1000 server PlayerUpdate 0 7 0 700
2000 client ZombieList 3 1 300 100
"""
        frames = """
1000 1 16.0 4.0 2.0
1100 2 70.0 60.0 10.0
1200 3 20.0 5.0 3.0
"""
        snmp_start = "Udp: InDatagrams NoPorts InErrors OutDatagrams\nUdp: 100 0 0 40\n"
        snmp_end = "Udp: InDatagrams NoPorts InErrors OutDatagrams\nUdp: 118 0 0 47\n"
        s = netsum.summarize(net, frames, snmp_start, snmp_end)
        self.assertEqual(s["totals"]["client"], [18, 3, 1800, 300])
        self.assertEqual(s["totals"]["server"], [0, 7, 0, 700])
        self.assertEqual(s["snmp"], (18, 7))
        self.assertAlmostEqual(s["ratio"][0], 1.0)
        self.assertAlmostEqual(s["ratio"][1], 10.0 / 7.0)
        self.assertEqual(len(s["rows"]), 1)
        self.assertEqual(s["rows"][0][1], 1)
        self.assertAlmostEqual(s["rows"][0][2], 60.0)

    def test_compactSnmp(self):
        self.assertEqual(netsum.parse_snmp("123 50 60\n"), (50, 60))
        self.assertIsNone(netsum.parse_snmp(""))

    def test_malformedSkipped(self):
        recs = netsum.parse_net("bad line\n1 2 3\n1000 client X 1 2 3 4\n")
        self.assertEqual(len(recs), 1)
        self.assertEqual(recs[0][2], "X")


if __name__ == "__main__":
    unittest.main()
