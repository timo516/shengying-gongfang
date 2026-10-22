"""参数拼装单测：只拼字符串，不真跑 ffmpeg（所以能在任何机器上跑）。

回归的是"能 copy 就 copy"这条主线和 1.0.3 里那几个 ffmpeg 坑
（-avoid_negative_ts、音频 copy 的 -ss 位置）。
"""

import os
import sys
import unittest
from pathlib import Path

# build_args 里会拿 ft.ffmpeg() 当 args[0]，这里指到一个一定存在的可执行文件上，
# 免得测试依赖"机器上装了 ffmpeg"。
os.environ.setdefault("FFMPEG_BIN", sys.executable)

from mediakit.clip import build_args as clip_args                     # noqa: E402
from mediakit.extract import build_args as extract_args              # noqa: E402


class ExtractArgsTest(unittest.TestCase):
    def test_lossless_copy_when_codec_matches(self):
        args, lossless = extract_args(Path("a.mp4"), Path("o.m4a"), "m4a", src_codec="aac")
        self.assertTrue(lossless)
        self.assertIn("copy", args)

    def test_transcode_when_codec_mismatch(self):
        args, lossless = extract_args(Path("a.mp4"), Path("o.mp3"), "mp3", src_codec="aac")
        self.assertFalse(lossless)
        self.assertIn("libmp3lame", args)
        self.assertIn("192k", args)

    def test_video_track_is_always_dropped(self):
        args, _ = extract_args(Path("a.mp4"), Path("o.m4a"), "m4a", src_codec="aac")
        self.assertIn("-vn", args)

    def test_time_window(self):
        args, _ = extract_args(
            Path("a.mp4"), Path("o.m4a"), "m4a", start=10.0, end=20.0, src_codec="aac"
        )
        self.assertEqual(args[args.index("-ss") + 1], "10.000")
        self.assertEqual(args[args.index("-t") + 1], "10.000")

    def test_unknown_format_falls_back_to_copy(self):
        args, lossless = extract_args(Path("a.mp4"), Path("o.xyz"), "xyz", src_codec="aac")
        self.assertFalse(lossless)
        self.assertIn("copy", args)


class ClipArgsTest(unittest.TestCase):
    def test_copy_mode_maps_all_tracks(self):
        args = clip_args(Path("a.mkv"), Path("o.mkv"), 1.0, 2.0, copy_mode=True, audio_only=False)
        self.assertIn("copy", args)
        self.assertIn("-map", args)

    def test_no_avoid_negative_ts(self):
        # 加了它会让 mp4 的 edit list 保留原始偏移，剪出来时长报错，这是实测过的坑
        args = clip_args(Path("a.mp4"), Path("o.mp4"), 1.0, 2.0, copy_mode=True, audio_only=False)
        self.assertNotIn("-avoid_negative_ts", args)

    def test_audio_copy_seeks_after_input(self):
        # 音频 copy + 输入定位会让开头多出编码器延迟，必须把 -ss 放到 -i 后面
        args = clip_args(Path("a.mp4"), Path("o.m4a"), 1.0, 2.0, copy_mode=True, audio_only=True)
        self.assertLess(args.index("-i"), args.index("-ss"))

    def test_precise_mode_re_encodes(self):
        args = clip_args(Path("a.mp4"), Path("o.mp4"), 0.0, 3.0, copy_mode=False, audio_only=False)
        self.assertIn("libx264", args)
        self.assertIn("+faststart", args)
        self.assertNotIn("copy", args)


if __name__ == "__main__":
    unittest.main()
