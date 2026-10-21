"""媒体信息探测：时长、编码、声道、分辨率。

优先用 ffprobe 的 JSON 输出（结构稳定），没有 ffprobe 时用 ffmpeg -i 的文本兜底。
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import List, Optional

from . import ffmpeg_tool as ft
from .utils import parse_time


@dataclass
class AudioStream:
    index: int
    codec: str
    channels: int = 0
    sample_rate: int = 0
    bit_rate: int = 0

    @property
    def label(self) -> str:
        ch = f"{self.channels}声道" if self.channels else "声道未知"
        sr = f"{self.sample_rate / 1000:.1f}kHz" if self.sample_rate else "采样率未知"
        br = f"{self.bit_rate // 1000}kbps" if self.bit_rate else "码率未知"
        return f"#{self.index} {self.codec} · {ch} · {sr} · {br}"


@dataclass
class MediaInfo:
    path: Path
    duration: float = 0.0
    size: int = 0
    container: str = ""
    audio: List[AudioStream] = field(default_factory=list)
    video_codec: str = ""
    width: int = 0
    height: int = 0

    @property
    def has_audio(self) -> bool:
        return bool(self.audio)

    @property
    def has_video(self) -> bool:
        return bool(self.video_codec)

    @property
    def kind(self) -> str:
        if self.has_video and self.has_audio:
            return "视频(带音轨)"
        if self.has_video:
            return "视频(无音轨)"
        if self.has_audio:
            return "音频"
        return "未知"

    def summary(self) -> str:
        lines = [
            f"文件    {self.path.name}",
            f"类型    {self.kind}",
            f"容器    {self.container or '未知'}",
            f"时长    {parse_time(self.duration):.1f} 秒"
            f"（{int(self.duration // 60)}分{self.duration % 60:04.1f}秒）",
            f"大小    {self.size} 字节",
        ]
        if self.has_video:
            lines.append(f"画面    {self.video_codec} {self.width}x{self.height}")
        if self.has_audio:
            for a in self.audio:
                lines.append(f"音轨    {a.label}")
        else:
            lines.append("音轨    无（这个文件抽不出音频）")
        return "\n".join(lines)


def probe(path) -> MediaInfo:
    """读取媒体信息。文件不存在或不可解析时抛异常。"""
    p = Path(path)
    if not p.exists():
        raise FileNotFoundError(f"文件不存在：{p}")

    info = MediaInfo(path=p, size=p.stat().st_size)
    probe_exe = ft.ffprobe()

    if probe_exe:
        try:
            return _probe_with_ffprobe(probe_exe, p, info)
        except Exception:
            pass  # 落到下面用 ffmpeg -i 兜底
    return _probe_with_ffmpeg(p, info)


def _probe_with_ffprobe(exe: str, p: Path, info: MediaInfo) -> MediaInfo:
    raw = ft.run([
        exe, "-v", "quiet", "-print_format", "json",
        "-show_format", "-show_streams", str(p),
    ])
    data = json.loads(raw)

    fmt = data.get("format", {})
    info.container = fmt.get("format_name", "") or ""
    info.duration = float(fmt.get("duration") or 0)
    info.size = int(fmt.get("size") or info.size)

    for st in data.get("streams", []):
        codec_type = st.get("codec_type")
        if codec_type == "audio":
            info.audio.append(AudioStream(
                index=int(st.get("index", 0)),
                codec=st.get("codec_name", "unknown"),
                channels=int(st.get("channels") or 0),
                sample_rate=int(st.get("sample_rate") or 0),
                bit_rate=int(st.get("bit_rate") or 0),
            ))
            if not info.duration:
                info.duration = float(st.get("duration") or 0)
        elif codec_type == "video":
            # 封面图会被识别成 video，排除掉
            if st.get("disposition", {}).get("attached_pic") == 1:
                continue
            info.video_codec = st.get("codec_name", "") or ""
            info.width = int(st.get("width") or 0)
            info.height = int(st.get("height") or 0)
    return info


_DUR_RE = re.compile(r"Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)")
_AUD_RE = re.compile(r"Stream #\d+:\d+.*?:\s*Audio:\s*([A-Za-z0-9_]+).*?(?:,\s*(\d+)\s*Hz)?.*?(?:,\s*(\d+)\s*kb/s)?")
_VID_RE = re.compile(r"Stream #\d+:\d+.*?:\s*Video:\s*([A-Za-z0-9_]+).*?(\d{2,5})x(\d{2,5})")


def _probe_with_ffmpeg(p: Path, info: MediaInfo) -> MediaInfo:
    """没有 ffprobe 时的兜底：解析 ffmpeg -i 的 stderr。"""
    text = ""
    try:
        ft.run([ft.ffmpeg(), "-hide_banner", "-i", str(p)])
    except ft.FFmpegError as e:
        text = str(e)

    m = _DUR_RE.search(text)
    if m:
        h, mi, s = m.groups()
        info.duration = int(h) * 3600 + int(mi) * 60 + float(s)

    for i, m in enumerate(_AUD_RE.finditer(text)):
        codec, hz, kb = m.groups()
        info.audio.append(AudioStream(
            index=i,
            codec=codec,
            sample_rate=int(hz) if hz else 0,
            bit_rate=int(kb) * 1000 if kb else 0,
        ))

    mv = _VID_RE.search(text)
    if mv:
        info.video_codec = mv.group(1)
        info.width = int(mv.group(2))
        info.height = int(mv.group(3))

    return info


def first_audio_codec(info: MediaInfo) -> Optional[str]:
    return info.audio[0].codec if info.audio else None