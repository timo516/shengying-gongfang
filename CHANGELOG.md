# Changelog

## v1.1.0 (2026-10-22)

**可靠性版本：快速剪不再挑容器、长任务不再被系统回收、补上自动化回归**

versionCode 6, minSdk 24 / targetSdk 34。

### Fixed

- **快速剪在三种常见素材上 100% 失败**：copy 模式用 `-map 0` 把字幕/数据轨也搬进写死的 `.mp4`，`mkv+字幕`、`mkv+opus 音轨`、`wav` 全部报 `Could not find tag for codec ...`。现在 copy 模式**沿用源容器**，并只映射首条视频/音频（`-map 0:v:0? -map 0:a:0?`）。
- **任务跑完时页面已关闭会崩溃**：`AlertDialog` 在已 destroy 的 Activity 上抛 `BadTokenException`。现在所有收尾都先看 `isFinishing/isDestroyed`。
- **逗号小数点地区剪片段必失败**：`"%.3f".format()` 用默认 Locale，德语/法语等地区把 `-ss 30.000` 写成 `30,000`。所有 ffmpeg 时间参数改用 `Locale.US`。
- **波形分析进度假死、长素材必超时**：解码时总时长传了 0，进度永不回调，看门狗固定 5 分钟。现在把已知时长传进去，进度和超时都按素材长度走。
- **保存后的提示路径可能不存在**：MediaStore 遇重名会自动改名（`xxx (1).m4a`），现在回读 `DISPLAY_NAME`。
- **时间输入越界被静默接受**：`1:70` 会被算成 130 秒，用户以为输错了地方却没提示。分段现在严格校验（除第一段外都必须 < 60），越界直接判非法。
- **任务失败后按钮仍要空等冷却**：冷却计时不看结果，失败一次也得等。现在只有成功才进冷却。
- **Python**：`run()` 的异常只带 stderr 末 12 行，`ffprobe` 兜底解析因此拿不到 `Duration`（多音轨文件必现）；`run_with_progress()` 只读 stdout，另一条 PIPE 写满会死锁，且没有超时。

### Added

- **前台服务**（`WorkService`，`dataSync` 类型）：长任务期间切后台/锁屏不再被当缓存进程回收 —— 以前四分钟的精确剪可能只剩半截文件。
- **可单测的核心逻辑**：`FfmpegArgs`（参数拼装）、`Fmt`（时间/尺寸）、`Names`（文件名）、`Timeline`（区间运算）全部与 Android API 解耦，配 31 条 JVM 单测。测试期依赖只有 JUnit，**不进 APK**。
- `tools/make-fixtures.sh` + `tools/smoke.sh`：用 lavfi 合成"最容易翻车"的素材（带字幕 mkv / opus mkv / wav / 多音轨），把 App 里的参数原样跑一遍，**不需要真机**就能回归容器与编码组合。
- `.github/workflows/ci.yml`：Android 编译 + 单测、Python 单测 + ffmpeg 参数回归。
- 剪辑器导入前预检可用空间（和主页共用 `Storage.requireFree`）。

### Changed

- 剪辑器临时文件从 `filesDir` 挪到 `cacheDir`：前者系统永不回收，导入一个 4 GB 视频就能把内部存储塞满，且只能卸载 App 才清掉。
- `MainActivity` 加 `launchMode="singleTop"`：二次分享复用同一实例（`onNewIntent()` 以前基本不会触发）。
- 权限收窄：删掉没用到的 `READ_EXTERNAL_STORAGE`，删掉在 targetSdk 34 下已失效的 `requestLegacyExternalStorage`。
- `lint.abortOnError` 改为 `true` —— `String.format` 不带 Locale 这类问题正是它抓的。
- 去掉两处冗余的 `-strict -2`（这个构建的 native AAC 编码器不需要，实测无 `-strict -2` 也能编码）。
- Python 输出目录默认从 `<安装目录>/output` 改为 `~/mediakit-output`（`pip install` 后安装目录是只读的），新增 `pyproject.toml` 与 `mediakit` 命令。
- 批量扫描不再把路径里任何叫 `output` 的目录一律跳过（以前 `/home/me/output/movies` 会被误杀）。

---

## v1.0.3 (2026-10-20)

**Release signing, dark mode, long-job warning, and the audio editor**

| File | ABI | Size |
|---|---|---|
| `shengying-gongfang-1.0.3-arm64.apk` | arm64-v8a | 8.7 MB |
| `shengying-gongfang-1.0.3-full.apk` | arm64-v8a / armeabi-v7a / x86 / x86_64 | 33.7 MB |

versionCode 5, minSdk 24 / targetSdk 34.

### Added

- **Audio editor** — waveform timeline, two-handle selection, multi-range deletion, keep-only-selection, audition, stitch-on-export. Import audio directly, or import a video and the audio track is extracted first.
- **Dark mode** — colours moved to `values/colors.xml` + `values-night/colors.xml`, follows the system; the duplicated system ActionBar is gone.
- **Adaptive icon** — layered foreground/background plus a themed monochrome icon for Android 13, with five PNG fallbacks.
- **Long-job warning** — for media ≥ 60 s or ≥ 150 MB the app warns you to keep it in the foreground and holds `FLAG_KEEP_SCREEN_ON` until the job ends. Wired into five entry points: extract audio, quick cut, precise cut, editor import, editor export.
- **Predictive back** — `enableOnBackInvokedCallback="true"`, gesture-back animation on Android 13+.
- **Configurable ABIs** — `-Pabi=...`, defaults to arm64-v8a.

### Changed

- **Switched to a release key** — RSA 4096, valid until 2056, V2 + V3 signature schemes. **Incompatible** with the debug signature used by 1.0.0–1.0.2, so older installs must be uninstalled first.
- Results are saved through MediaStore straight to `Download/` — no "all files access" permission is requested.

---

## v1.0.1

**"Open folder" fix and vendor file-manager support**

- Fixed `Output.openFolder()`: `setData()` and `setType()` cleared each other, leaving an Intent with no targets, so the button silently did nothing. Now uses `setDataAndType(uri, type)`.
- "Open folder" tries the vendor file manager first (Xiaomi / OPPO / OnePlus / vivo / Huawei / Honor / Samsung / Meizu / Lenovo), then DocumentsUI, then copies the path to the clipboard with a toast.
- Added `<queries>` to the manifest — without it, Android 11+ package visibility makes every vendor probe return null.

---

## v1.0.0

First usable release:

- Extract audio: m4a lossless (`-c:a copy`) / mp3 192k / flac / wav / aac / opus / ogg
- Cut clips: quick cut (`-c copy`, keyframe-aligned) / precise cut (libx264 re-encode)
- Share-sheet entry: send video/audio in from other apps
- Results in `Download/`, duplicate names get a counter
- Bundled ffmpeg static binary, no `INTERNET` permission
