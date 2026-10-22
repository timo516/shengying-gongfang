"""ffmpeg / ffprobe 的定位与调用封装。

这是整个包唯一和外部程序打交道的地方。所有命令都走 run() 或 run_with_progress()，
方便统一处理报错和日志。
"""

from __future__ import annotations

import os
import shutil
import subprocess
import threading
from pathlib import Path
from typing import Callable, List, Optional

from .utils import parse_time

# Windows 下不想弹黑框
_NO_WINDOW = 0x08000000 if os.name == "nt" else 0

# 常见安装位置，PATH 里找不到时兜底
_CANDIDATES = [
    "/data/data/com.termux/files/usr/bin/ffmpeg",   # Termux (Android)
    "/usr/bin/ffmpeg",
    "/usr/local/bin/ffmpeg",
    "/opt/homebrew/bin/ffmpeg",
    "/system/bin/ffmpeg",
]


class FFmpegError(RuntimeError):
    """ffmpeg 执行失败。

    message 里只放 stderr 末尾若干行（够人看、不刷屏），**完整输出**放在 .output。
    原因：media_info 的 "ffmpeg -i" 兜底解析要的是开头那几行（Duration / Stream），
    而末尾那几行里根本没有 —— 只留 12 行时，多音轨文件永远解析不出时长。
    """

    def __init__(self, message: str, output: str = ""):
        super().__init__(message)
        self.output = output


_ff: Optional[str] = None
_fp: Optional[str] = None


def ffmpeg() -> str:
    """返回 ffmpeg 可执行文件路径，找不到就抛 FFmpegError。"""
    global _ff
    if _ff:
        return _ff

    found = os.environ.get("FFMPEG_BIN") or shutil.which("ffmpeg")
    if not found:
        # 项目内自带二进制（打包时放 bin/ 下）
        for name in ("ffmpeg", "ffmpeg.exe"):
            local = Path(__file__).resolve().parent.parent / "bin" / name
            if local.exists():
                found = str(local)
                break
    if not found:
        for c in _CANDIDATES:
            if os.path.exists(c) and os.access(c, os.X_OK):
                found = c
                break
    if not found:
        raise FFmpegError(
            "找不到 ffmpeg。\n"
            "  Android/Termux: pkg install ffmpeg\n"
            "  电脑: 装 ffmpeg 并确保在 PATH 里，或用环境变量 FFMPEG_BIN 指定路径"
        )
    _ff = found
    return _ff


def ffprobe() -> Optional[str]:
    """ffprobe 是可选依赖，没有它就用 ffmpeg -i 解析兜底。"""
    global _fp
    if _fp is not None:
        return _fp or None

    found = os.environ.get("FFPROBE_BIN") or shutil.which("ffprobe")
    if not found:
        ff = ffmpeg()
        sibling = Path(ff).with_name("ffprobe.exe" if os.name == "nt" else "ffprobe")
        if sibling.exists():
            found = str(sibling)
    _fp = found or ""
    return _fp or None


def _decode(b: bytes) -> str:
    return b.decode("utf-8", errors="replace")


def run(args, timeout: Optional[float] = None, quiet: bool = True) -> str:
    """同步执行 ffmpeg/ffprobe，返回 stdout+stderr 文本；失败抛 FFmpegError。

    失败时异常对象带 .output（完整输出），需要解析全文的调用方用它。
    """
    exe = args[0]
    cmd = [exe] + list(args[1:])
    try:
        proc = subprocess.run(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout,
            creationflags=_NO_WINDOW,
        )
    except FileNotFoundError as e:
        raise FFmpegError(f"无法启动 {exe}：{e}") from e
    except subprocess.TimeoutExpired as e:
        raise FFmpegError(f"执行超时（{timeout}s）") from e

    out = _decode(proc.stdout) + _decode(proc.stderr)
    if proc.returncode != 0:
        tail = "\n".join(out.strip().splitlines()[-12:])
        raise FFmpegError(f"命令失败(exit {proc.returncode})：\n{tail}", output=out)
    if not quiet:
        print(out)
    return out


def run_with_progress(
    args,
    total_seconds: float,
    on_progress: Optional[Callable[[float, float], None]] = None,
    cwd: Optional[str] = None,
    timeout: Optional[float] = None,
) -> None:
    """执行 ffmpeg 并实时回报进度。

    on_progress(已处理秒数, 总秒数) 会被反复调用，总秒数未知时传 0。
    靠 ffmpeg 的 -progress pipe:1 输出解析，比去正则 stderr 靠谱得多。

    stderr 用独立线程收：stdout/stderr 两条 PIPE 只读一条时，
    另一条写满 64KB 后 ffmpeg 卡在写上、Python 卡在读上 —— 双向死锁。
    """
    exe = args[0]
    cmd = [exe] + list(args[1:]) + ["-progress", "pipe:1", "-nostats", "-loglevel", "error"]

    proc = subprocess.Popen(
        cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        cwd=cwd,
        text=True,
        encoding="utf-8",
        errors="replace",
        bufsize=1,
        creationflags=_NO_WINDOW,
    )

    err_chunks: List[str] = []

    def _drain_stderr() -> None:
        try:
            if proc.stderr is None:
                return
            for line in proc.stderr:
                err_chunks.append(line)
        except Exception:  # noqa: BLE001 —— 收尾线程，任何异常都不该炸出去
            pass

    drainer = threading.Thread(target=_drain_stderr, daemon=True)
    drainer.start()

    try:
        assert proc.stdout is not None
        for line in proc.stdout:
            line = line.strip()
            if "=" not in line:
                continue
            key, _, value = line.partition("=")
            if key == "out_time_us" and value.isdigit():
                done = int(value) / 1_000_000
                if on_progress:
                    on_progress(done, total_seconds)
            elif key == "progress" and value == "end":
                if on_progress:
                    on_progress(total_seconds, total_seconds)
    finally:
        try:
            proc.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait()
            err_chunks.append(f"（超时 {timeout}s，已强制结束）")
        drainer.join(1)

    stderr_text = "".join(err_chunks)
    if proc.returncode != 0:
        tail = "\n".join(stderr_text.strip().splitlines()[-12:])
        raise FFmpegError(f"命令失败(exit {proc.returncode})：\n{tail}", output=stderr_text)


def make_bar(done: float, total: float, width: int = 28) -> str:
    """一行进度条文本。"""
    if total and total > 0:
        ratio = min(max(done / total, 0.0), 1.0)
        filled = int(width * ratio)
        bar = "█" * filled + "─" * (width - filled)
        return f"[{bar}] {ratio * 100:5.1f}%  {parse_time(done)}/{parse_time(total)}"
    return f"已处理 {parse_time(done)}"
