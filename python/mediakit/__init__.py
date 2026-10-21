"""mediakit —— 本地音视频提取与剪辑工具包。

设计原则：
1. 只用 Python 标准库，不引入任何第三方依赖（打包/分发不会卡在网络和版本上）。
2. 所有音视频能力都交给系统的 ffmpeg 可执行文件，本包只负责调度、参数拼装、进度解析。
3. 默认不做二次压缩：能 copy 就 copy，音质画质零损失、速度快。
"""

__version__ = "0.1.0"

from . import config, ffmpeg_tool, media_info, extract, clip, batch  # noqa: F401
