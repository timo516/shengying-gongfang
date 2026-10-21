"""交互式入口：不记命令也能用。

python main.py            直接跑这个，按菜单选
python main.py --check    自检环境
"""

from __future__ import annotations

import sys
from pathlib import Path

# 允许直接 `python main.py` 运行（把项目根加进 sys.path）
sys.path.insert(0, str(Path(__file__).resolve().parent))

from mediakit import config                                  # noqa: E402
from mediakit import ffmpeg_tool as ft                       # noqa: E402
from mediakit.batch import iter_media, run_batch             # noqa: E402
from mediakit.clip import clip                               # noqa: E402
from mediakit.extract import extract_audio                   # noqa: E402
from mediakit.media_info import probe                        # noqa: E402
from mediakit.utils import human_size, parse_time            # noqa: E402

BANNER = """
╭──────────────────────────────────────╮
│  音视频提取剪辑  ·  本地 ·  无广告    │
╰──────────────────────────────────────╯"""


def _ask(prompt: str, default: str = "") -> str:
    try:
        s = input(prompt).strip()
    except EOFError:
        return default
    return s if s else default


def _clean_path(raw: str) -> Path:
    """容忍用户把路径连引号一起粘进来。"""
    raw = raw.strip().strip("'\"").strip()
    p = Path(raw).expanduser()
    # Android 上 /sdcard 和 /storage/emulated/0 是同一条
    return p


def _bar(done: float, total: float):
    sys.stdout.write("\r  " + ft.make_bar(done, total) + "   ")
    sys.stdout.flush()


def _end():
    sys.stdout.write("\n")
    sys.stdout.flush()


def menu_info():
    raw = _ask("文件路径（可拖拽/粘贴）：")
    if not raw:
        return
    try:
        info = probe(_clean_path(raw))
        print()
        print(info.summary())
        print(f"大小    {human_size(info.size)}")
    except Exception as e:
        print(f"⚠️ {type(e).__name__}: {e}")


def menu_extract():
    raw = _ask("文件路径：")
    if not raw:
        return
    src = _clean_path(raw)
    try:
        info = probe(src)
        print(f"时长 {info.duration:.1f}s，音轨：{info.audio[0].codec if info.audio else '无'}")
    except Exception as e:
        print(f"⚠️ {type(e).__name__}: {e}")
        return
    if not info.has_audio:
        print("这个文件没有音轨")
        return

    print("格式：1) m4a 无损(推荐)   2) mp3   3) flac   4) wav   5) aac")
    fmt = {"1": "m4a", "2": "mp3", "3": "flac", "4": "wav", "5": "aac"}.get(
        _ask("选一个（默认 1）：", "1"), "m4a"
    )
    bitrate = config.DEFAULT_MP3_BITRATE
    if fmt in ("mp3", "aac"):
        bitrate = _ask("码率（默认 192k，可选 128k/320k）：", "192k")

    start = _ask("只导出一段？起点（直接回车=从头）：") or None
    end = _ask("终点（直接回车=到结尾）：") or None

    try:
        out = extract_audio(src, fmt=fmt, bitrate=bitrate, start=start, end=end,
                            on_progress=_bar)
        _end()
        print(f"✅ 完成：{out}")
        print(f"   {human_size(out.stat().st_size)}")
    except Exception as e:
        _end()
        print(f"⚠️ {type(e).__name__}: {e}")


def menu_clip():
    raw = _ask("文件路径：")
    if not raw:
        return
    src = _clean_path(raw)
    try:
        info = probe(src)
        print(f"时长 {info.duration:.1f}s（{info.duration / 60:.1f} 分钟）")
    except Exception as e:
        print(f"⚠️ {type(e).__name__}: {e}")
        return

    start = _ask("起点（0:30 或 90，默认 0）：", "0")
    end = _ask("终点（直接回车=到结尾）：") or None
    precise = _ask("要切口严格精确吗？精确会慢一些 (y/N)：", "n").lower() == "y"
    audio_only = _ask("只要音频 (y/N)：", "n").lower() == "y"

    try:
        out = clip(src, start=start, end=end, copy_mode=not precise,
                   audio_only=audio_only, on_progress=_bar)
        _end()
        print(f"✅ 完成：{out}")
        print(f"   {human_size(out.stat().st_size)}")
    except Exception as e:
        _end()
        print(f"⚠️ {type(e).__name__}: {e}")


def menu_batch():
    raw = _ask("目录路径：")
    if not raw:
        return
    d = _clean_path(raw)
    try:
        files = iter_media(d, recursive=False, kinds="video")
    except Exception as e:
        print(f"⚠️ {type(e).__name__}: {e}")
        return

    if not files:
        print("这个目录下没有视频文件")
        return

    print(f"找到 {len(files)} 个视频：")
    for i, f in enumerate(files[:20], 1):
        print(f"  {i}. {f.name}")
    if len(files) > 20:
        print(f"  …还有 {len(files) - 20} 个")
    if _ask("开始批量抽音频？(Y/n)：", "y").lower() == "n":
        return

    fmt = "m4a" if _ask("用 m4a 无损？(Y/n)：", "y").lower() != "n" else "mp3"

    def worker(f: Path) -> Path:
        print(f"[{f.name}]")
        return extract_audio(f, fmt=fmt, on_progress=_bar)

    def on_done(i, total, f, out, err):
        _end()
        if out:
            print(f"  ✅ {i}/{total} {out.name}")
        else:
            print(f"  ⚠️ {i}/{total} 失败：{err}")

    r = run_batch(files, worker, on_done=on_done)
    print("-" * 40)
    print(f"成功 {len(r['ok'])}，失败 {len(r['fail'])}")


def menu_check():
    print(f"输出去向 {config.DEFAULT_OUTPUT_DIR}")
    try:
        exe = ft.ffmpeg()
        print(f"ffmpeg   {exe}")
        print("         " + ft.run([exe, "-version"]).splitlines()[0])
    except Exception as e:
        print(f"ffmpeg   ❌ {e}")
    print(f"ffprobe  {ft.ffprobe() or '（没有，用 ffmpeg 兜底）'}")


def main():
    if "--check" in sys.argv:
        menu_check()
        return

    print(BANNER)
    if not config.ROOT.exists():
        print(f"⚠️ 项目目录不存在：{config.ROOT}")

    while True:
        print("""
 1. 查看文件信息
 2. 提取音频
 3. 剪辑（裁剪时间区间）
 4. 批量抽音频（整个目录）
 5. 环境自检
 0. 退出""")
        choice = _ask("\n选哪个：", "0")

        if choice == "0":
            print("再见")
            return
        elif choice == "1":
            menu_info()
        elif choice == "2":
            menu_extract()
        elif choice == "3":
            menu_clip()
        elif choice == "4":
            menu_batch()
        elif choice == "5":
            menu_check()
        else:
            print("没这个选项")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n已退出")