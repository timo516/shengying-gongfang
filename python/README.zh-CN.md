# 声影工坊 · 命令行版（mediakit）

[English](README.md) | 中文说明

这是 [声影工坊](../README.zh-CN.md) 的 Python 命令行实现，功能与 Android 版一致，适合在电脑或 Termux 上用。

本地跑的小工具：从视频里抽音频、按时间剪片段、整目录批量抽。
**无广告、不联网、不上传、不登录**，所有处理都在你手机上（或电脑上）用 ffmpeg 本地完成。

- 纯 Python 标准库 + 系统里的 ffmpeg，没有任何 pip 依赖
- 默认「能无损就无损」：音轨编码对得上就直接 `-c:a copy`，字节级搬运，音质一点不掉

---

## 一、先装 ffmpeg（唯一的外部依赖）

ffmpeg 不是 Python 包，得单独装。

**Android / Termux**

```bash
pkg update && pkg install ffmpeg
```

**Ubuntu / Debian**

```bash
sudo apt install ffmpeg
```

**macOS**

```bash
brew install ffmpeg
```

装完随便一个终端敲 `ffmpeg -version` 有输出就行。

程序会按这个顺序找 ffmpeg：环境变量 `FFMPEG_BIN` → PATH → 本目录下 `bin/ffmpeg` → Termux 常见路径。
所以就算 ffmpeg 不在 PATH 里，也可以把二进制丢进 `bin/` 目录直接用。

---

## 二、怎么用

### 方式 1：交互菜单（最省事，不用记命令）

```bash
cd /storage/emulated/0/mediakit      # 你放这个工具的目录
python3 main.py
```

出来一个菜单：查信息 / 抽音频 / 剪片段 / 批量抽 / 自检，选数字就行。
路径直接粘贴，带引号也能认。

### 方式 2：命令行

```bash
# 环境自检
python3 main.py --check
python3 -m mediakit.cli check

# 看文件信息（时长、编码、声道、码率、分辨率）
python3 -m mediakit.cli info 视频.mp4

# 抽音频（默认 m4a，能无损就无损）
python3 -m mediakit.cli extract 视频.mp4

# 指定输出目录 / 格式 / 码率
python3 -m mediakit.cli extract 视频.mp4 -o /sdcard/Download -f mp3 -b 320k

# 只取其中一段（0:30 到 1:10）
python3 -m mediakit.cli extract 视频.mp4 --start 0:30 --end 1:10

# 剪片段：默认流复制（快、无损，但切口会落在关键帧上）
python3 -m mediakit.cli clip 视频.mp4 --start 0:10 --end 0:40

# 剪片段：精确模式（重编码，切口一秒不差，慢一点）
python3 -m mediakit.cli clip 视频.mp4 --start 0:10 --end 0:40 --precise

# 只保留音频并裁剪
python3 -m mediakit.cli clip 视频.mp4 --start 0:10 --end 0:40 --audio

# 批量：整个目录的视抽音频
python3 -m mediakit.cli batch /sdcard/Movies -o /sdcard/Download/音频 -f m4a
```

输出默认丢在项目里的 `output/` 目录，同名文件不覆盖，会自动变成 `xxx (1).m4a`。
想改默认输出目录，设环境变量 `MEDIAKIT_OUT` 就行。

---

## 三、时间怎么写

三种都认：

| 写法 | 含义 |
|---|---|
| `90` | 90 秒 |
| `1:30` | 1 分 30 秒 |
| `00:01:30.500` | 1 分 30.5 秒 |

---

## 四、为什么默认是 m4a 而不是 mp3

- 源视频里 95% 的音轨是 AAC，而 m4a 容器正好装 AAC
- 所以「视频 → m4a」可以直接流复制：**几秒完成，音质和源文件完全一样**
- 「视频 → mp3」必须重新编码（AAC 不能塞进 mp3），既慢又掉一点音质

除非播放设备只认 mp3，否则没理由转 mp3。

程序会按源音轨编码自动判断，不会给你造「假 m4a」（把 mp3 音轨硬塞进 .m4a 后缀那种）。

---

## 五、功能一览

| 功能 | 说明 |
|---|---|
| `info` | 时长、容器、视频编码/分辨率、音轨编码/声道/采样率/码率 |
| `extract` | 视频抽音频，支持 m4a / mp3 / aac / flac / wav / opus / ogg |
| `clip` | 按时间区间裁剪，copy / precise 双模式，可只要音频 |
| `batch` | 整目录批量处理，单文件失败不影响整批，最后给成败清单 |
| `check` | 自检：ffmpeg 在哪、版本、输出目录 |

---

## 六、目录结构

```
mediakit/
├── main.py                 交互式入口（菜单）
├── README.md               本文件
├── run.sh                  一键启动脚本
├── output/                 默认输出目录（自动创建）
└── mediakit/
    ├── __init__.py         版本号、对外导出
    ├── utils.py            时间解析、文件大小、文件名清洗、防重名
    ├── config.py           所有可调参数都在这（格式、码率、目录、开关）
    ├── ffmpeg_tool.py      定位 ffmpeg/ffprobe、执行命令、解析进度
    ├── media_info.py       探测媒体信息（ffprobe JSON，无 ffprobe 时兜底）
    ├── extract.py          抽音频（copy 优先，必要时转码）
    ├── clip.py             剪辑（copy / 精确两种模式）
    ├── batch.py            批量处理
    └── cli.py              命令行入口
```

想改默认行为（默认格式、码率、copy 开关、输出目录），改 `mediakit/config.py` 就行，不用动功能代码。

---

## 七、常见情况

**Q：剪出来的起点比我要求的早一点？**
copy 模式只能切在关键帧上，正常的。要严丝合缝加 `--precise`。

**Q：进度条一直 0% 然后突然 100%？**
说明 ffmpeg 拿不到实时进度（文件太大或源时间戳乱）。不影响结果，等它跑完就行。

**Q：报「找不到 ffmpeg」？**
按第一节装一下，或者把 ffmpeg 可执行文件放到本目录的 `bin/` 里。

**Q：报编码器不存在（`Unknown encoder 'libx264'` / `libmp3lame`）？**
你装的是精简版 ffmpeg。换完整版：Termux 的 `pkg install ffmpeg` 是全的，Ubuntu 的 `apt install ffmpeg` 也是全的。

**Q：能处理多大的文件？**
没有额外限制，ffmpeg 是流式处理的，不吃内存。手机上处理一两 G 的视频没问题，只是会慢一点。

---

## 八、已验证

在 ffmpeg 6.1.1 + Python 3.12 上实测通过：

- AAC 音轨抽 m4a：流复制，20 秒视频音频输出 316KB，时长 20.000 秒
- MP3 音轨抽 m4a：自动转 AAC，时长 10.009 秒
- 抽 mp3 320k：正常转码，时长 20.06 秒
- 剪 5s→12s：copy 模式 7.005 秒，precise 模式 7.000 秒
- 只剪音频：AAC 源出 .m4a，MP3 源出 .mp3，切口 3.000 秒准确
- 批量 3 个文件（含 1 个损坏文件）：2 成功 1 失败，损坏文件不影响其他文件
