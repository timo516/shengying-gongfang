# ShengYing GongFang (声影工坊)

**An offline audio & video toolkit for Android. ffmpeg is bundled inside the APK, so it works the moment you install it: fully local, zero third-party dependencies, no ads, no sign-in.**

![Platform](https://img.shields.io/badge/Android-7.0%2B%20(API%2024)-3DDC84)
![No Internet](https://img.shields.io/badge/INTERNET-permission%20not%20declared-critical)
![License](https://img.shields.io/badge/license-MIT-blue)
![Size](https://img.shields.io/badge/APK-8.7%20MB%20(arm64)-lightgrey)

English | [中文说明](README.zh-CN.md)

---

## What it is

A small app you install on a phone: pull the audio track out of a video, cut a clip by timestamp, or load a file into a waveform editor and delete the parts you don't want before stitching the rest back together.

Everything runs on the device, using an **ffmpeg static binary shipped inside the APK**. The app manifest does **not declare the `INTERNET` permission** — this isn't a promise to stay offline, it's that the app is physically unable to reach the network.

| | |
|---|---|
| App name | 声影工坊 |
| Package | `com.mediakit.app` |
| Version | 1.0.3 (versionCode 5) |
| Min / target SDK | 24 (Android 7.0) / 34 (Android 14) |
| Language & deps | Kotlin, stock Android widgets only — `dependencies {}` is empty, not even androidx |
| Engine | ffmpeg 3.3.2 static build (libx264 / libmp3lame / libfdk-aac / libopus / libvorbis) |
| Network permission | **none** |
| APK size | 8.7 MB (arm64) / 33.7 MB (4 ABIs) |

---

## Screenshots

**Main screen** (light / dark)

<p align="center">
  <img src="docs/screenshots/light-main.jpg" width="300" alt="Main screen, light theme">
  <img src="docs/screenshots/dark-main.jpg" width="300" alt="Main screen, dark theme">
</p>

**Audio editor** (light / dark)

<p align="center">
  <img src="docs/screenshots/light-editor.jpg" width="300" alt="Audio editor, light theme">
  <img src="docs/screenshots/dark-editor.jpg" width="300" alt="Audio editor, dark theme">
</p>

---

## Features

### 1. Extract audio

| Output | How |
|---|---|
| **m4a (lossless)** | If the source track is AAC it's remuxed with `-c:a copy` — byte-for-byte, a few seconds, zero quality loss |
| mp3 192k / flac / wav / aac / opus / ogg | Transcoded when needed |

No fake m4a: if the source isn't AAC and you picked m4a, it re-encodes to AAC instead of stuffing an mp3 stream into an `.m4a` container.

### 2. Cut clips

- **Quick cut** — `-c copy`, stream copy, no re-encode, finishes in seconds; cuts snap to the nearest keyframe.
- **Precise cut** — libx264 re-encode, frame-accurate, slower.
- Audio-only mode: drop the video track.

### 3. Audio editor

"Edit audio" opens a waveform timeline with a two-handle selection, multi-range deletion and stitch-on-export.

- Waveform drawn into 1600 sample buckets, smooth to drag
- Mark a range for deletion, mark several, undo or clear them
- "Keep only this range" as the inverse operation
- Audition the selection with MediaPlayer instead of waiting through the whole file
- Export trims the kept ranges with `atrim` and joins them with `concat`; audio re-encoding is fast

You can import a video too — the audio track is extracted first.

### 4. Share-sheet entry

From the gallery, a file manager or WeChat, "Share → 声影工坊" sends the media straight in, no need to browse folders inside the app.

### 5. Where results go, and "Open folder"

Default output directory:

```
Internal storage/音视频提取剪辑软件/提取的音频包/
```

Without the "All files access" permission it falls back to `Download/音视频提取剪辑软件/提取的音频包/`, so results always land somewhere. Existing names get ` (1)`, ` (2)` — nothing is overwritten.

The "Open folder" button probes the vendor file manager first (Xiaomi / OPPO / OnePlus / vivo / Huawei / Honor / Samsung / Meizu / Lenovo), then DocumentsUI, and if everything fails it copies the path to the clipboard and says so — never a dead button.

### 6. Command-line version (Python)

`python/` holds the same feature set for a desktop or Termux, standard library only plus the system ffmpeg — no pip dependencies:

```bash
python3 main.py                      # interactive menu
python3 -m mediakit.cli info video.mp4
python3 -m mediakit.cli extract video.mp4 -f mp3 -b 320k
python3 -m mediakit.cli clip video.mp4 --start 0:10 --end 0:40 --precise
python3 -m mediakit.cli batch /sdcard/Movies -f m4a
```

See [python/README.md](python/README.md).

---

## Download & install

Grab the APK from [Releases](https://github.com/timo516/shengying-gongfang/releases):

| File | ABI | Size | Who it's for |
|---|---|---|---|
| `shengying-gongfang-1.0.3-arm64.apk` | arm64-v8a | 8.7 MB | Almost every phone from the last few years — **pick this one** |
| `shengying-gongfang-1.0.3-full.apk` | arm64-v8a / armeabi-v7a / x86 / x86_64 | 33.7 MB | 32-bit devices, emulators, or if you're unsure |

Android will ask you to allow "install from unknown sources"; this app is not on any store.

> **Signing:** since 1.0.3 the app uses a real release key (RSA 4096, valid until 2056). It is **not compatible** with the debug signature used in 1.0.0–1.0.2: if you have an older build installed, uninstall it first, otherwise the install fails with "App not installed / signature mismatch". Every version after this one installs over the top.

### Verify the download

**The only official release channel is this repository's Releases page:**
<https://github.com/timo516/shengying-gongfang/releases>

Only the APKs published there are the original, unmodified build. Packages from cloud drives, forums, third-party app sites, chat groups or "a friend sent me this" cannot be trusted: a repackaged APK can add permissions, so the "no network permission" guarantee only holds for the original.

**Signing certificate SHA-256** — the v1, v2 and v3 signature blocks of 1.0.3 all use this one certificate, and every future version will too (a different key would break in-place upgrades):

```
EF:4C:A4:77:06:01:7A:BE:4A:19:2C:11:52:7B:0C:7D:6C:B9:A2:B8:D6:70:A1:87:66:67:D2:64:13:ED:39:9E
```

Verify it yourself, with the Android SDK:

```bash
apksigner verify --print-certs shengying-gongfang-1.0.3-arm64.apk
```

or with nothing but a JDK:

```bash
keytool -printcert -jarfile shengying-gongfang-1.0.3-arm64.apk
```

The output must contain the fingerprint above. If it doesn't, the file is not my build — do not install it.

**File checksums (SHA-256):**

| File | SHA-256 |
|---|---|
| `shengying-gongfang-1.0.3-arm64.apk` | `b52a5c17aff07b7477b048f1920cba52d406c0cc63b0c94acb2c3dd1ff4263e7` |
| `shengying-gongfang-1.0.3-full.apk` | `5ff77c46a445d21bd1491c956c94f5b4380e97a7947d32a51fcae7f682357e9a` |

Two checks you can do on the phone itself:

1. Android Settings → Apps → 声影工坊 → Permissions — there must be **no network-related permission**.
2. Any "APK signature checker" app will show the same certificate fingerprint.

---

## Project layout

```
.
├── docs/screenshots/           # UI screenshots (light / dark)
├── android/                    # Android app (Kotlin, no dependencies)
│   ├── BUILD.md                # build, packaging and device checklist
│   ├── build.gradle            # AGP 8.5.2 / Kotlin 1.9.24
│   ├── settings.gradle
│   ├── gradle.properties
│   ├── JNILIBS.md              # where the bundled ffmpeg comes from, how to swap it
│   └── app/
│       ├── build.gradle
│       └── src/main/
│           ├── AndroidManifest.xml
│           ├── java/com/mediakit/app/
│           │   ├── MainActivity.kt        # pick file → show info → extract / cut
│           │   ├── AudioEditorActivity.kt # waveform editor: selection, multi-delete, export
│           │   ├── Ffmpeg.kt              # locate and run libffmpeg.so, parse -progress
│           │   ├── MediaInfo.kt           # duration / resolution / has-audio
│           │   ├── Output.kt              # save, dedupe names, open vendor file manager
│           │   ├── Perm.kt                # storage permissions
│           │   ├── LongTask.kt            # long-job warning + keep screen on
│           │   └── WaveView.kt            # waveform rendering
│           ├── res/                       # themes (incl. night), adaptive icon
│           └── jniLibs/arm64-v8a/libffmpeg.so   # bundled ffmpeg (17 MB, don't rename)
└── python/                     # CLI / desktop version (standard library only)
    ├── main.py
    ├── mediakit/               # info / extract / clip / batch / check
    └── README.md
```

---

## Build from source

Full steps in [android/BUILD.md](android/BUILD.md). Short version:

```bash
cd android
echo "sdk.dir=/path/to/Android/Sdk" > local.properties   # or set ANDROID_HOME
gradle assembleRelease            # needs JDK 17 + Gradle 8.7 + SDK 34
```

> The repo ships only the **arm64-v8a** `libffmpeg.so` (17 MB, to keep clones bearable). For a 4-ABI build, add the other three binaries as described in [android/JNILIBS.md](android/JNILIBS.md) and run `gradle assembleRelease -Pabi=arm64-v8a,armeabi-v7a,x86,x86_64`.

Signing passwords are **not** in the repo. Copy `android/keystore.properties.example` to `android/keystore.properties` and fill it in; without that file the build still succeeds and produces an unsigned APK.

---

## Gotchas (things that break if you change them)

1. **`libffmpeg.so` is an executable, not a real shared library.** The `.so` name only exists so Gradle packages it into `lib/<abi>/`.
2. **`useLegacyPackaging = true` plus `android:extractNativeLibs="true"` must stay.** Since API 29, Android forbids executing files in the data directory; `nativeLibraryDir` is the only legal place. Turn these off and the binary is never extracted as a real file, so the app reports "no bundled ffmpeg" on launch.
3. **Don't add ABIs you don't ship binaries for** — it only makes the APK bigger and still won't run.
4. **Don't add the `INTERNET` permission.** "Fully offline" lives on that line.
5. **Never commit `keystore.properties` or a `.jks`.**

Bugs fixed along the way (all in the current code):

| Symptom | Cause | Fix |
|---|---|---|
| "Open folder" did nothing | `setData()` and `setType()` clear each other | use `setDataAndType(uri, type)` |
| ffmpeg hung → every button disabled forever | no timeout | watchdog: duration × 4 + 120 s, clamped to 180 s–30 min, then `destroyForcibly()` |
| Multi-GB video filled up internal storage | whole file copied into `cacheDir` | check size vs. free space first, keep 50 MB headroom, show a readable message |
| Crash on very long file names | length was never limited | truncate to 80 chars, keep the extension |
| Hard failure when the target folder became unwritable | no fallback | catch and fall back to `Download/` |
| Audio written into an `.mp4` extension | extension followed the source container | pick the container from the actual codec |
| Audio stream `-ss` landed 30 ms early | argument order | move `-ss`, cuts are now exact |

---

## Known limitations

- This repo builds **arm64-v8a** by default; 32-bit devices need the full APK from Releases.
- The bundled ffmpeg is 3.3.2 (built 2018) — that's the trade-off for a sane APK size. AV1/VVC are out of scope; H.264, H.265, AAC, MP3, FLAC, Vorbis and Opus are covered.
- Quick cut follows keyframes, so the cut point snaps; use precise cut when it must be exact.
- No foreground service: for very long files keep the app in the foreground (it warns you and keeps the screen on); backgrounding it for a long time may let the system kill the process.

---

## Privacy

- No `INTERNET` permission — nothing can be uploaded.
- No accounts, no analytics, no device identifiers.
- Media is read and written locally only; temp files are cleaned up when a job ends.

---

## Credits & license

- Code in this repo: [MIT](LICENSE) © 2026 timo516
- The bundled ffmpeg is a **GPL** static build taken from [Khang-NT/ffmpeg-binary-android](https://github.com/Khang-NT/ffmpeg-binary-android) (release `2018-07-31`, ffmpeg 3.3.2, NDK r15c, API 21+). If you redistribute an APK containing it, comply with the GPL on your own.
- Icons are original vector artwork made for this project.
