import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(__file__))
import ki5scan


SYNC = '''local M = {}
function M.SyncRearWindows(player)
    local vehicle = player:getVehicle()
    if not (vehicle and string.find(vehicle:getScriptName(), "dodgeA")) then return end
    vehicle:doThing()
end
Events.OnPlayerUpdate.Add(M.SyncRearWindows)
'''

EVERY = '''local M = {}
function M.Turbo(player)
    local vehicle = player:getVehicle()
    if vehicle and string.find(vehicle:getScriptName(), "nissanB") then
        vehicle:turbo()
    end
end
Events.OnPlayerUpdate.Add(M.Turbo)
'''

FOREIGN = '''function Other.Thing(player)
    print("not a vehicle handler")
end
Events.OnPlayerUpdate.Add(Other.Thing)
'''


class Ki5ScanTest(unittest.TestCase):
    def tree(self):
        root = tempfile.mkdtemp()
        for mid, files in {
            "ModA": {"client/sync.lua": SYNC},
            "ModB": {"client/every.lua": EVERY},
            "ModC": {"client/other.lua": FOREIGN},
        }.items():
            d = os.path.join(root, mid, "mods", mid)
            os.makedirs(os.path.join(d, "media/lua"))
            with open(os.path.join(d, "mod.info"), "w") as f:
                f.write("id=%s\n" % mid)
            for rel, body in files.items():
                p = os.path.join(d, "media/lua", rel)
                os.makedirs(os.path.dirname(p), exist_ok=True)
                with open(p, "w") as f:
                    f.write(body)
        return root

    def test_kindsAndFilters(self):
        root = self.tree()
        listfile = os.path.join(root, "default.txt")
        with open(listfile, "w") as f:
            f.write("mod=ModA\nmod=ModB\nmod=ModC\n")
        rows = ki5scan.scan(root, ki5scan.active_ids(listfile))
        by_name = {r["name"]: r for r in rows}
        self.assertEqual(len(rows), 3)
        self.assertEqual(by_name["M.SyncRearWindows"]["kind"], "sync")
        self.assertEqual(by_name["M.SyncRearWindows"]["types"], ["dodgeA"])
        self.assertEqual(by_name["M.SyncRearWindows"]["onFoot"], "none")
        self.assertEqual(by_name["M.Turbo"]["kind"], "every")
        self.assertEqual(by_name["M.Turbo"]["types"], ["nissanB"])
        self.assertEqual(by_name["Other.Thing"]["kind"], "unchecked")
        self.assertEqual(by_name["Other.Thing"]["onFoot"], "unchecked")

    def test_activeListFiltersMods(self):
        root = self.tree()
        listfile = os.path.join(root, "default.txt")
        with open(listfile, "w") as f:
            f.write("mod=ModA\n")
        rows = ki5scan.scan(root, ki5scan.active_ids(listfile))
        self.assertEqual([r["name"] for r in rows], ["M.SyncRearWindows"])

    def test_sameModTwiceGivesOneRow(self):
        root = self.tree()
        copy = os.path.join(root, "ModA2", "mods", "ModA")
        import shutil
        shutil.copytree(os.path.join(root, "ModA", "mods", "ModA"), copy)
        listfile = os.path.join(root, "default.txt")
        with open(listfile, "w") as f:
            f.write("mod=ModA\n")
        rows = ki5scan.scan(root, ki5scan.active_ids(listfile))
        self.assertEqual([r["name"] for r in rows], ["M.SyncRearWindows"])

    def test_tableIsData(self):
        rows = [{"id": "M:a.lua:2", "name": "M.F", "kind": "sync",
                 "types": ["x", "y"], "onFoot": "none"}]
        text = ki5scan.table_text(rows)
        self.assertIn('id="M:a.lua:2"', text)
        self.assertIn('types={"x", "y"}', text)
        self.assertIn("PZButter.Ki5Table", text)
        self.assertNotIn("function", text)


if __name__ == "__main__":
    unittest.main()
