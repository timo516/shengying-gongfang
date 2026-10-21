# Changelog

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
- Results are saved through MediaStore to `Download/音视频提取剪辑软件/提取的音频包/` — no "all files access" permission is requested.

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
- Results in `Download/音视频提取剪辑软件/提取的音频包/`, duplicate names get a counter
- Bundled ffmpeg static binary, no `INTERNET` permission
