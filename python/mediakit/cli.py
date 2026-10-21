"""命令行入口：mediakit <子命令>

python -m mediakit.cli info  视频.mp4
python -m mediakit.cli extract 视频.mp4 -f mp3
python -m mediakit.cli clip 视频.mp4 --start 0:10 --end 0:40
python -m mediakit.cli batch ~/Movies --mode extract -f m4a
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from . import __version__
from . import batch as bt
from . import config
from . import ffmpeg_tool as ft
from .clip import clip
from .extract import extract_audio
from .media_info import probe
from .utils import human_size

# 终端里画进度条：同一行刷新，不刷屏
_LAST_LEN = {"n": 0}


def _progress_cb(label: str = ""):
    def cb(done: float, total: float):
        text = ("  " + label + " " if label else "  ") + ft.make_bar(done, total)
        pad = max(0, _LAST_LEN["n"] - len(text))
        sys.stdout.write("\r" + text + " " * pad)
        sys.stdout.flush()
        _LAST_LEN["n"] = len(text)
    return cb


def _end_line():
    sys.stdout.write("\n")
    sys.stdout.flush()
    _LAST_LEN["n"] = 0


def cmd_info(args) -> int:
    rc = 0
    for src in args.files:
        try:
            info = probe(src)
            print(info.summary())
            print(f"大小    {human_size(info.size)}（{info.size} 字节）")
        except Exception as e:
            print(f"⚠️  {src}: {type(e).__name__}: {e}")
            rc = 1
        if len(args.files) > 1:
            print("-" * 40)
    return rc


def cmd_extract(args) -> int:
    rc = 0
    for src in args.files:
        try:
            print(f"→ {Path(src).name}")
            out = extract_audio(
                src,
                out_dir=args.out,
                fmt=args.format,
                bitrate=args.bitrate,
                start=args.start,
                end=args.end,
                overwrite=args.force,
                on_progress=_progress_cb(),
            )
            _end_line()
            print(f"  ✅ {out}  ({human_size(out.stat().st_size)})")
        except Exception as e:
            _end_line()
            print(f"  ⚠️ {type(e).__name__}: {e}")
            rc = 1
    return rc


def cmd_clip(args) -> int:
    try:
        print(f"→ {Path(args.file).name}")
        out = clip(
            args.file,
            out_dir=args.out,
            start=args.start,
            end=args.end,
            copy_mode=not args.precise,
            audio_only=args.audio_only,
            crf=args.crf,
            overwrite=args.force,
            on_progress=_progress_cb(),
        )
        _end_line()
        print(f"  ✅ {out}  ({human_size(out.stat().st_size)})")
        return 0
    except Exception as e:
        _end_line()
        print(f"  ⚠️ {type(e).__name__}: {e}")
        return 1


def cmd_batch(args) -> int:
    files = bt.iter_media(args.dir, recursive=args.recursive, kinds=args.kinds)
    if not files:
        print(f"目录里没找到可处理的文件：{args.dir}")
        return 1

    print(f"共 {len(files)} 个文件，输出到 {Path(args.out or config.DEFAULT_OUTPUT_DIR)}")
    print("-" * 40)

    def worker(f: Path) -> Path:
        print(f"[{f.name}]")
        if args.mode == "extract":
            return extract_audio(
                f, out_dir=args.out, fmt=args.format, bitrate=args.bitrate,
                overwrite=args.force, on_progress=_progress_cb(),
            )
        return clip(
            f, out_dir=args.out, start=args.start, end=args.end,
            copy_mode=not args.precise, audio_only=args.audio_only,
            overwrite=args.force, on_progress=_progress_cb(),
        )

    def on_done(i, total, f, out, err):
        _end_line()
        if out:
            print(f"  ✅ ({i}/{total}) {out.name}  {human_size(out.stat().st_size)}")
        else:
            print(f"  ⚠️ ({i}/{total}) 失败：{type(err).__name__}: {err}")

    result = bt.run_batch(files, worker, on_done=on_done)

    print("-" * 40)
    print(f"成功 {len(result['ok'])} / {result['total']}，失败 {len(result['fail'])}")
    if result["fail"]:
        print("失败清单：")
        for f, msg in result["fail"]:
            print(f"  - {f.name}: {msg}")
        return 1
    return 0


def cmd_check(_args) -> int:
    print(f"mediakit {__version__}")
    print(f"Python   {sys.version.split()[0]}")
    try:
        exe = ft.ffmpeg()
        ver = ft.run([exe, "-version"]).splitlines()[0]
        print(f"ffmpeg   {exe}")
        print(f"         {ver}")
    except Exception as e:
        print(f"ffmpeg   ❌ {e}")
        return 1
    p = ft.ffprobe()
    print(f"ffprobe  {p or '（没有，会用 ffmpeg 兜底解析）'}")
    print(f"输出去向 {config.DEFAULT_OUTPUT_DIR}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="mediakit",
        description="本地音视频提取与剪辑 · 无广告 · 不联网 · 不上传",
    )
    p.add_argument("-V", "--version", action="version", version=f"mediakit {__version__}")
    sub = p.add_subparsers(dest="cmd", required=True)

    # info
    sp = sub.add_parser("info", help="查看媒体信息")
    sp.add_argument("files", nargs="+")
    sp.set_defaults(func=cmd_info)

    # extract
    sp = sub.add_parser("extract", help="提取音频")
    sp.add_argument("files", nargs="+")
    sp.add_argument("-o", "--out", default=None, help="输出目录")
    sp.add_argument("-f", "--format", default=config.DEFAULT_AUDIO_FORMAT,
                    choices=["m4a", "mp3", "aac", "flac", "wav", "opus", "ogg"],
                    help="目标格式，默认 m4a（能无损 copy 时无损）")
    sp.add_argument("-b", "--bitrate", default=config.DEFAULT_MP3_BITRATE, help="转码码率，默认 192k")
    sp.add_argument("--start", default=None, help="只导出这一段，起点（0:30 或 90）")
    sp.add_argument("--end", default=None, help="只导出这一段，终点")
    sp.add_argument("--force", action="store_true", help="允许覆盖同名文件")
    sp.set_defaults(func=cmd_extract)

    # clip
    sp = sub.add_parser("clip", help="剪辑（裁剪时间区间）")
    sp.add_argument("file")
    sp.add_argument("-o", "--out", default=None, help="输出目录")
    sp.add_argument("--start", default="0", help="起点，默认 0")
    sp.add_argument("--end", default=None, help="终点，默认到结尾")
    sp.add_argument("--precise", action="store_true",
                    help="精确模式（重编码，切口严格对齐；慢）")
    sp.add_argument("--audio", dest="audio_only", action="store_true", help="只要音频")
    sp.add_argument("--crf", type=int, default=20, help="精确模式画质，越小越清晰，默认 20")
    sp.add_argument("--force", action="store_true", help="允许覆盖同名文件")
    sp.set_defaults(func=cmd_clip)

    # batch
    sp = sub.add_parser("batch", help="批量处理整个目录")
    sp.add_argument("dir")
    sp.add_argument("--mode", choices=["extract", "clip"], default="extract")
    sp.add_argument("-o", "--out", default=None, help="输出目录")
    sp.add_argument("-f", "--format", default=config.DEFAULT_AUDIO_FORMAT,
                    choices=["m4a", "mp3", "aac", "flac", "wav", "opus", "ogg"])
    sp.add_argument("-b", "--bitrate", default=config.DEFAULT_MP3_BITRATE)
    sp.add_argument("--start", default="0")
    sp.add_argument("--end", default=None)
    sp.add_argument("--precise", action="store_true")
    sp.add_argument("--audio", dest="audio_only", action="store_true")
    sp.add_argument("--kinds", choices=["all", "video", "audio"], default="video")
    sp.add_argument("-r", "--recursive", action="store_true", help="含子目录")
    sp.add_argument("--force", action="store_true")
    sp.set_defaults(func=cmd_batch)

    # check
    sp = sub.add_parser("check", help="环境自检")
    sp.set_defaults(func=cmd_check)

    return p


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    try:
        return args.func(args)
    except KeyboardInterrupt:
        _end_line()
        print("已取消")
        return 130
    except ft.FFmpegError as e:
        _end_line()
        print(f"❌ {e}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())