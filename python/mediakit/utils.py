"""通用小工具：时间格式互转、文件大小、文件名清洗。"""

from pathlib import Path
import re

_INVALID = re.compile(r'[\\/:*?"<>|\x00-\x1f]')


def parse_time(text) -> float:
    """把 '90' / '1:30' / '00:01:30.500' 统一解析成秒。

    也接受纯数字（秒，可带小数）。
    """
    if text is None:
        return 0.0
    if isinstance(text, (int, float)):
        return float(text)

    s = str(text).strip()
    if not s:
        return 0.0

    # 允许 90s / 90.5 这种写法
    if s.lower().endswith("s") and ":" not in s:
        s = s[:-1]

    parts = s.split(":")
    if len(parts) > 3:
        raise ValueError(f"时间格式看不懂：{text}")

    total = 0.0
    for p in parts:
        total = total * 60 + float(p or 0)
    return total


def format_time(seconds: float) -> str:
    """秒 -> '01:23:45.6' 或 '03:45.6'"""
    if seconds is None or seconds < 0:
        return "00:00.0"
    seconds = float(seconds)
    h = int(seconds // 3600)
    m = int((seconds % 3600) // 60)
    s = seconds % 60
    if h:
        return f"{h:02d}:{m:02d}:{s:04.1f}"
    return f"{m:02d}:{s:04.1f}"


def human_size(num_bytes) -> str:
    n = float(num_bytes or 0)
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if n < 1024 or unit == "TB":
            return f"{n:.1f}{unit}" if unit != "B" else f"{int(n)}B"
        n /= 1024
    return f"{n:.1f}TB"


def safe_name(name: str, max_len: int = 120) -> str:
    """清洗成合法的文件名（去掉路径分隔符和系统保留字符）。"""
    name = _INVALID.sub("_", str(name)).strip().strip(".")
    return (name or "output")[:max_len]


def unique_path(path: Path) -> Path:
    """若文件已存在，自动加 (1) (2) 后缀，绝不覆盖。"""
    path = Path(path)
    if not path.exists():
        return path
    stem, suffix, parent = path.stem, path.suffix, path.parent
    i = 1
    while True:
        candidate = parent / f"{stem} ({i}){suffix}"
        if not candidate.exists():
            return candidate
        i += 1
