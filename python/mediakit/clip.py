"""剪辑：按时间区间裁剪视频或音频。

两种模式：
- copy 模式（默认）：-c copy 流复制，快、无损，但切口只能落在关键帧上，
  实际起点可能比你要的时间早零点几秒到一两秒。
- 精确模式：重新编码，切口严丝合缝，代价是慢一点、画面掉一点点质量。

要「一秒都不能差」就用精确模式；只是大概掐个头尾就用 copy。
"""

from __future__ import annotations

from pathlib import Path
from typing import Callable, Optional

from . import config
from . import ffmpeg_tool as ft
from .media_info import probe
from .utils import format_time, parse_time, safe_name, unique_path

ProgressFn = Optional[Callable[[float, float], None]]


def _stamp(seconds: float) -> str:
    """00:01:05.3 -> 00-01-05.3，用于文件名。"""
    return format_time(seconds).replace(":", "-")


def build_args(
    src: Path,
    dst: Path,
    start: float,
    duration: float,
    copy_mode: bool,
    audio_only: bool,
    crf: int = 20,
) -> list:
    args = [ft.ffmpeg(), "-hide_banner", "-y"]

    # -ss 放在 -i 前面是「输入定位」，比输出定位快得多。
    # 但「只要音频 + copy」这种情况必须放在 -i 后面：
    #   输入定位 + 音频流复制时，mp3/aac 的编码器延迟会让开头多出整段前奏，
    #   实测 1s→4s 的片段剪出来是 4.03 秒。放到 -i 后面就正好 3.000 秒，
    #   而且因为不解码，速度一样快。
    seek_after_input = copy_mode and audio_only
    if start > 0 and not seek_after_input:
        args += ["-ss", f"{start:.3f}"]
    args += ["-i", str(src)]
    if start > 0 and seek_after_input:
        args += ["-ss", f"{start:.3f}"]
    args += ["-t", f"{duration:.3f}"]

    if audio_only:
        args += ["-vn"]
        args += ["-c:a", "copy"] if copy_mode else ["-c:a", "aac", "-b:a", "192k"]
    elif copy_mode:
        args += ["-c", "copy"]
        # 注意：这里别加 -avoid_negative_ts make_zero。
        # 实测（ffmpeg 6.1）加上它会让 mp4 的 edit list 保留原始的 5 秒偏移，
        # 结果 5s→12s 的片段被报成 12 秒长，播放器容易错位。
        # 默认的 auto 行为已经会把负时间戳处理掉。
        args += ["-map", "0"]                      # 保留全部轨（含字幕）
    else:
        args += [
            "-c:v", "libx264", "-preset", "veryfast", "-crf", str(crf),
            "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-b:a", "192k",
            "-movflags", "+faststart",
        ]

    args += [str(dst)]
    return args


def clip(
    src,
    out_dir=None,
    start=0,
    end=None,
    copy_mode: Optional[bool] = None,
    audio_only: bool = False,
    crf: int = 20,
    filename: Optional[str] = None,
    on_progress: ProgressFn = None,
    overwrite: bool = False,
) -> Path:
    """裁剪 [start, end) 区间，返回输出路径。

    end 传 None 表示剪到结尾。
    """
    src = Path(src)
    out_dir = Path(out_dir or config.DEFAULT_OUTPUT_DIR)
    out_dir.mkdir(parents=True, exist_ok=True)

    info = probe(src)
    s = parse_time(start)
    e = parse_time(end) if end is not None else info.duration

    if info.duration and s >= info.duration:
        raise ValueError(f"开始时间 {format_time(s)} 超出了文件总长 {format_time(info.duration)}")
    if e is not None and e <= s:
        raise ValueError("结束时间必须大于开始时间")
    if info.duration and e and e > info.duration + 0.5:
        e = info.duration   # 宽容处理：超出就掐到结尾

    duration = e - s
    if duration <= 0:
        raise ValueError("裁剪区间为空")

    if copy_mode is None:
        copy_mode = config.CLIP_COPY_MODE
    if audio_only and not info.has_audio:
        raise ValueError("这个文件没有音轨")

    # 后缀：copy 模式沿用原容器最省事；重编码统一 mp4 / m4a
    if copy_mode:
        if audio_only:
            # 只要音频时别输出成 .mp4，按真实音轨编码选容器
            codec = info.audio[0].codec if info.audio else ""
            suffix = config.COPY_CONTAINER.get(codec, ".m4a")
        else:
            suffix = src.suffix.lower() or ".mp4"
    else:
        suffix = ".m4a" if audio_only else ".mp4"

    base = safe_name(filename) if filename else safe_name(src.stem)
    base += f"_{_stamp(s)}-{_stamp(e)}"
    dst = out_dir / (base + suffix)
    if not overwrite:
        dst = unique_path(dst)

    args = build_args(src, dst, s, duration, copy_mode, audio_only, crf)
    if on_progress:
        on_progress(0, duration)
    ft.run_with_progress(args, duration, on_progress)

    if not dst.exists() or dst.stat().st_size == 0:
        raise ft.FFmpegError("输出文件为空，剪失败了")
    return dst


def split(src, out_dir=None, segments=None, copy_mode: Optional[bool] = None,
          on_progress: ProgressFn = None) -> list:
    """一次切成多段。segments = [("开头名", 0, 30), ("中段", 60, 90), ...]

    返回输出路径列表。
    """
    results = []
    for name, a, b in (segments or []):
        results.append(clip(
            src, out_dir=out_dir, start=a, end=b, copy_mode=copy_mode,
            filename=name, on_progress=on_progress,
        ))
    return results