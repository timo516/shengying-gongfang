"""批量处理：扫目录、过滤、逐个跑、汇总成败。

这是防「抽一半崩了还得从头来」的地方 —— 单个文件失败不影响其他的。
"""

from __future__ import annotations

from pathlib import Path
from typing import Callable, Iterable, List, Optional, Sequence

from . import config


def iter_media(root, recursive: bool = False, kinds: str = "all") -> List[Path]:
    """列出目录下的媒体文件。

    kinds: all / video / audio
    结果按路径排序，保证每次跑的顺序一致。
    """
    root = Path(root)
    if not root.exists():
        raise FileNotFoundError(f"目录不存在：{root}")

    if kinds == "video":
        exts = config.VIDEO_EXTS
    elif kinds == "audio":
        exts = config.AUDIO_EXTS
    else:
        exts = config.ALL_EXTS

    pattern = "**/*" if recursive else "*"
    files = []
    for p in root.glob(pattern):
        if p.is_file() and p.suffix.lower() in exts:
            # 跳过自己产出的 output 目录
            if any(part == "output" for part in p.parts):
                continue
            files.append(p)

    return sorted(files)


def run_batch(
    files: Sequence[Path],
    worker: Callable[[Path], Path],
    on_start: Optional[Callable[[int, int, Path], None]] = None,
    on_done: Optional[Callable[[int, int, Path, Optional[Path], Optional[BaseException]], None]] = None,
) -> dict:
    """对每个文件调 worker，收集结果。

    worker 返回输出路径；抛异常则记为失败。
    返回 {"ok": [...], "fail": [(file, 错误文本), ...]}
    """
    files = list(files)
    total = len(files)
    ok: List[Path] = []
    fail: List[tuple] = []

    for i, f in enumerate(files, 1):
        if on_start:
            on_start(i, total, f)
        try:
            out = worker(f)
            ok.append(out)
            if on_done:
                on_done(i, total, f, out, None)
        except Exception as e:                       # noqa: BLE001 —— 故意的，一个失败不能带走整批
            fail.append((f, f"{type(e).__name__}: {e}"))
            if on_done:
                on_done(i, total, f, None, e)

    return {"ok": ok, "fail": fail, "total": total}