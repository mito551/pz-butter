import unittest

import glcalls

DUMP = """\
0 glBindFramebuffer(GL_FRAMEBUFFER, 1)
1 glClear(GL_COLOR_BUFFER_BIT)
2 glUseProgram(3)
3 glUniform4f(1, 0, 0, 0, 1)
4 glActiveTexture(GL_TEXTURE0)
5 glBindTexture(GL_TEXTURE_2D, 7)
6 glBufferData(GL_ARRAY_BUFFER, 64, 0, GL_STATIC_DRAW)
7 glGetIntegerv(GL_VIEWPORT, 0)
8 glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
9 glDrawArrays(GL_TRIANGLES, 0, 3)
10 glMultiDrawElements(GL_TRIANGLES, 0, GL_UNSIGNED_INT, 0, 1)
11 glNamedFrobnicate(1)
12 glBindFramebuffer(GL_FRAMEBUFFER, 0)
13 glBlitFramebuffer(0, 0, 1, 1, 0, 0, 1, 1, GL_COLOR_BUFFER_BIT, GL_NEAREST)
14 glXSwapBuffers(0, 1)
"""


class GlcallsTest(unittest.TestCase):
    def test_categories(self):
        table = glcalls.count(DUMP.splitlines())
        p = table["pass0"]
        self.assertEqual(p["draw"], 2)
        self.assertEqual(p["bind"], 4)
        self.assertEqual(p["uniform"], 1)
        self.assertEqual(p["upload"], 1)
        self.assertEqual(p["get"], 1)
        self.assertEqual(p["sync"], 1)
        self.assertEqual(p["other"], 2)  # glClear, glNamedFrobnicate
        self.assertEqual(sum(p.values()), 12)

    def test_pass_split_at_framebuffer_bind(self):
        table = glcalls.count(DUMP.splitlines())
        self.assertEqual(sorted(table), ["pass0", "pass1"])
        self.assertEqual(table["pass0"]["draw"], 2)
        self.assertEqual(table["pass0"]["bind"], 4)  # the first bind opens pass0
        self.assertEqual(table["pass1"]["other"], 2)  # glBlitFramebuffer, glXSwapBuffers
        self.assertEqual(table["pass1"]["bind"], 1)  # the second bind opens pass1

    def test_unknown_call_is_other(self):
        self.assertEqual(glcalls.category("glNamedFrobnicate"), "other")
        self.assertEqual(glcalls.category("glDrawElements"), "draw")
        self.assertEqual(glcalls.category("glTexSubImage2D"), "upload")

    def test_frames_split_at_swap(self):
        lines = ["0 glClear(GL_COLOR_BUFFER_BIT)", "1 glXSwapBuffers(0, 1)",
                 "2 glDrawArrays(GL_TRIANGLES, 0, 3)", "3 glXSwapBuffers(0, 1)"]
        fs = glcalls.frames(lines)
        self.assertEqual(len(fs), 2)
        self.assertEqual(glcalls.count(fs[1])["pass0"]["draw"], 1)

    def test_empty_dump(self):
        self.assertEqual(glcalls.count([]), {})


if __name__ == "__main__":
    unittest.main()
