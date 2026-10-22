"""从视频/音频里提取音频轨。

核心思路：能 copy 就 copy。
-c:a copy 是字节级搬运，不解码不编码 —— 秒出、音质和源文件一模一样。
只有目标格式和源编码不兼容时（比如源是 AAC 你要 MP3），才真正转码。
"""

from __future__ import annotations

from pathlib import Path
from typing import Callable, List, Optional, Tuple

from . import config
from . import ffmpeg_tool as ft
from .media_info import probe
from .utils import format_time, parse_time, safe_name, unique_path

ProgressFn = Optional[Callable[[float, float], None]]

# 目标格式 -> (是否可直接 copy, 需要的源编码, ffmpeg 编码器参数)
_COPY_OK = {
    "m4a": {"aac", "alac"},
    "mp3": {"mp3"},
    "opus": {"opus"},
    "ogg": {"vorbis"},
    "flac": {"flac"},
    "wav": {"pcm_s16le", "pcm_s24le", "pcm_s32le", "pcm_f32le"},
}

_ENCODERS = {
    "mp3": ["-c:a", "libmp3lame"],
    "aac": ["-c:a", "aac"],
    "m4a": ["-c:a", "aac"],          # 源不是 AAC 时，转成 AAC 塞进 m4a
    "flac": ["-c:a", "flac"],
    "opus": ["-c:a", "libopus"],
    "wav": ["-c:a", "pcm_s16le"],
    "ogg": ["-c:a", "libvorbis"],
}


def default_ext_for(codec: str) -> str:
    return config.COPY_CONTAINER.get(codec, ".mka")


def build_args(
    src: Path,
    dst: Path,
    fmt: str,
    bitrate: str = config.DEFAULT_MP3_BITRATE,
    start: float = 0.0,
    end: Optional[float] = None,
    src_codec: str = "",
) -> Tuple[List[str], bool]:
    """拼出 ffmpeg 参数，并返回是否走了无损 copy。

    src_codec 由调用方传入（它已经 probe 过了）。以前这里自己又 probe 一次，
    同一文件跑两遍 ffprobe，批量处理时纯属浪费。
    """
    lossless = fmt in _COPY_OK and src_codec in _COPY_OK.get(fmt, set())

    args = [ft.ffmpeg(), "-hide_banner", "-y"]
    if start and start > 0:
        args += ["-ss", f"{start:.3f}"]
    args += ["-i", str(src), "-vn"]                      # -vn 扔掉所有画面
    if end is not None and end > start:
        args += ["-t", f"{end - start:.3f}"]

    if lossless:
        args += ["-c:a", "copy"]
    else:
        args += _ENCODERS.get(fmt, ["-c:a", "copy"])
        if fmt in ("mp3", "aac", "m4a", "opus", "ogg"):
            args += ["-b:a", str(bitrate)]

    args += ["-map_metadata", "0", str(dst)]
    return args, lossless


def extract_audio(
    src,
    out_dir=None,
    fmt: str = config.DEFAULT_AUDIO_FORMAT,
    bitrate: str = config.DEFAULT_MP3_BITRATE,
    filename: Optional[str] = None,
    start=None,
    end=None,
    on_progress: ProgressFn = None,
    overwrite: bool = False,
) -> Path:
    """抽音频，返回输出文件路径。

    fmt: m4a / mp3 / aac / flac / wav / opus / ogg
    start/end: 只导出这一段（可选）
    """
    src = Path(src)
    out_dir = Path(out_dir or config.DEFAULT_OUTPUT_DIR)
    out_dir.mkdir(parents=True, exist_ok=True)

    info = probe(src)
    if not info.has_audio:
        raise ValueError(f"{src.name} 里没有音轨，抽不了")

    s = parse_time(start) if start is not None else 0.0
    e = parse_time(end) if end is not None else None
    if e is not None and e <= s:
        raise ValueError("结束时间必须大于开始时间")

    # 决定后缀：无损 copy 时用源编码的真实容器，避免出现「假 m4a」
    src_codec = info.audio[0].codec
    if fmt in _COPY_OK and src_codec in _COPY_OK.get(fmt, set()):
        suffix = default_ext_for(src_codec)
    else:
        suffix = "." + fmt

    base = safe_name(filename) if filename else safe_name(src.stem)
    if s > 0 or e is not None:
        base += f"_{format_time(s).replace(':', '-')}"
    dst = out_dir / (base + suffix)
    if not overwrite:
        dst = unique_path(dst)

    args, lossless = build_args(src, dst, fmt, bitrate, s, e, src_codec)

    total = (e - s) if e is not None else ((info.duration - s) if info.duration else 0)
    if on_progress:
        on_progress(0, total)
    ft.run_with_progress(args, total, on_progress)

    if not dst.exists() or dst.stat().st_size == 0:
        raise ft.FFmpegError("输出文件为空，可能源音轨有问题")
    return dst
