"""纯函数单测：不碰 ffmpeg、不碰网络。

    cd python && python3 -m unittest discover -s tests -v
"""

import tempfile
import unittest
from pathlib import Path

from mediakit.utils import format_time, human_size, parse_time, safe_name, unique_path


class ParseTimeTest(unittest.TestCase):
    def test_plain_seconds(self):
        self.assertEqual(parse_time("90"), 90.0)
        self.assertEqual(parse_time(90), 90.0)
        self.assertEqual(parse_time("90.5"), 90.5)

    def test_mm_ss(self):
        self.assertEqual(parse_time("1:30"), 90.0)
        self.assertEqual(parse_time("00:01:30.500"), 90.5)

    def test_seconds_suffix(self):
        self.assertEqual(parse_time("90s"), 90.0)

    def test_trailing_colon_is_zero(self):
        self.assertEqual(parse_time("1:"), 60.0)

    def test_none_and_empty(self):
        self.assertEqual(parse_time(None), 0.0)
        self.assertEqual(parse_time(""), 0.0)

    def test_garbage_raises(self):
        with self.assertRaises(ValueError):
            parse_time("abc")
        with self.assertRaises(ValueError):
            parse_time("1:2:3:4")


class FormatTest(unittest.TestCase):
    def test_format_time(self):
        self.assertEqual(format_time(90.5), "01:30.5")
        self.assertEqual(format_time(3661.0), "01:01:01.0")
        self.assertEqual(format_time(-1), "00:00.0")

    def test_human_size(self):
        self.assertEqual(human_size(0), "0B")
        self.assertEqual(human_size(1024), "1.0KB")
        self.assertEqual(human_size(1536), "1.5KB")


class NameTest(unittest.TestCase):
    def test_safe_name_strips_path_chars(self):
        self.assertEqual(safe_name("a/b:c?.mp4"), "a_b_c_.mp4")

    def test_safe_name_never_empty(self):
        self.assertEqual(safe_name("..."), "output")

    def test_safe_name_truncates(self):
        self.assertEqual(len(safe_name("a" * 300, max_len=20)), 20)


class UniquePathTest(unittest.TestCase):
    def test_adds_counter(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "a.mp4"
            self.assertEqual(unique_path(p), p)
            p.write_text("x")
            self.assertEqual(unique_path(p).name, "a (1).mp4")

    def test_keeps_extension(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "a.b.mkv"
            p.write_text("x")
            n = unique_path(p)
            self.assertEqual(n.suffix, ".mkv")
            self.assertEqual(n.name, "a.b (1).mkv")


if __name__ == "__main__":
    unittest.main()
