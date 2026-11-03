import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))

import check_readme


class CheckReadmeTest(unittest.TestCase):
    SETTINGS = '''private static final String DEFAULTS =
      + "enabled=false\\n"
      + "# comment\\n"
      + "frameLog=false\\n"
      + "devCensus=false\\n"
      + "devScene=off\\n";'''

    def readme(self, body):
        return "## Settings\n\n| Key | What it does |\n|---|---|\n" + body + "\n## Mods\n"

    def test_allCovered(self):
        keys = check_readme.settings_keys(self.SETTINGS)
        readme = self.readme(
            "| `enabled` | a |\n| `frameLog` | b |\n"
            "### Dev keys\n\n| `devCensus` | c |\n| `devScene` | d |\n")
        self.assertEqual(check_readme.missing(keys, readme), (set(), set()))

    def test_missingRow(self):
        keys = check_readme.settings_keys(self.SETTINGS)
        readme = self.readme(
            "| `enabled` | a |\n\n### Dev keys\n\n| `devCensus` | c |\n| `devScene` | d |\n")
        self.assertEqual(check_readme.missing(keys, readme)[0], {"frameLog"})

    def test_staleRow(self):
        keys = check_readme.settings_keys(self.SETTINGS)
        readme = self.readme(
            "| `enabled` | a |\n| `frameLog` | b |\n| `oldKey` | x |\n"
            "### Dev keys\n\n| `devCensus` | c |\n| `devScene` | d |\n")
        self.assertEqual(check_readme.missing(keys, readme)[1], {"oldKey"})

    def test_devKeyOutsideDevSection(self):
        keys = check_readme.settings_keys(self.SETTINGS)
        readme = self.readme(
            "| `enabled` | a |\n| `frameLog` | b |\n| `devCensus` | c |\n"
            "### Dev keys\n\n| `devScene` | d |\n")
        self.assertEqual(check_readme.missing(keys, readme)[0], {"devCensus"})

    def test_installVariablesAreNotSettingsRows(self):
        keys = check_readme.settings_keys(self.SETTINGS)
        readme = ("## Install from source\n\n| Variable | Default |\n|---|---|\n"
                  "| `JDK` | /jdk |\n\n" + self.readme(
                      "| `enabled` | a |\n| `frameLog` | b |\n"
                      "### Dev keys\n\n| `devCensus` | c |\n| `devScene` | d |\n"))
        self.assertEqual(check_readme.missing(keys, readme), (set(), set()))


if __name__ == "__main__":
    unittest.main()
