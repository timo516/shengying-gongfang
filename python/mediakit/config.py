"""全局配置：路径、默认输出参数、可改的开关。

想改行为优先改这里，别去动功能模块。
"""

import os
from pathlib import Path

# 项目根目录（本文件在 <root>/mediakit/config.py）
ROOT = Path(__file__).resolve().parent.parent

# 默认输出目录：软件目录下的 output
DEFAULT_OUTPUT_DIR = Path(os.environ.get("MEDIAKIT_OUT", ROOT / "output"))

# 支持的输入扩展名（小写，含点）
VIDEO_EXTS = {
    ".mp4", ".mov", ".m4v", ".mkv", ".avi", ".flv", ".wmv",
    ".webm", ".ts", ".m2ts", ".3gp", ".mpg", ".mpeg", ".rmvb", ".vob",
}
AUDIO_EXTS = {
    ".mp3", ".m4a", ".aac", ".wav", ".flac", ".ogg", ".opus",
    ".wma", ".amr", ".aiff", ".ape", ".mka",
}
ALL_EXTS = VIDEO_EXTS | AUDIO_EXTS

# 抽音频默认参数
DEFAULT_AUDIO_FORMAT = "m4a"   # m4a(无损copy) / mp3 / aac / wav / flac / opus
DEFAULT_MP3_BITRATE = "192k"

# 剪辑默认参数
# True  = 流复制（飞快、无损，但只能切在关键帧上，可能多几帧）
# False = 精确重编码（慢、掉一点点画质，但切口严丝合缝）
CLIP_COPY_MODE = True

# 各编码对应的默认输出后缀（用 copy 时按源编码选容器，避免出现假 m4a）
COPY_CONTAINER = {
    "aac": ".m4a",
    "mp3": ".mp3",
    "opus": ".opus",
    "vorbis": ".ogg",
    "flac": ".flac",
    "pcm_s16le": ".wav",
    "pcm_s24le": ".wav",
    "ac3": ".mka",
    "eac3": ".mka",
    "alac": ".m4a",
    "amr_nb": ".amr",
}
