# Bundled ffmpeg binaries

`libffmpeg.so` is not a real shared library — it is a **statically linked ffmpeg executable** that has been renamed. The name exists only so Gradle packages it into the APK's `lib/<abi>/` directory; after install it lands in `nativeLibraryDir`, the only location Android allows executing files from.

## Source

https://github.com/Khang-NT/ffmpeg-binary-android
release tag: `2018-07-31` (ffmpeg 3.3.2, built with NDK r15c, API 21+, PIE)

| ABI | Archive | Size |
|---|---|---|
| arm64-v8a | `arm64-v8a-full.tar.bz2` | 17.8 MB |
| armeabi-v7a | `armv7-a-full.tar.bz2` | 16.5 MB |
| x86 | `i686-full.tar.bz2` | 19.9 MB |
| x86_64 | `x86_64-full.tar.bz2` | 20.6 MB |

Configured with libx264 / libmp3lame / libfdk-aac / libopus / libvorbis / openssl, fully static.

## Why only arm64 is in the repo

All four add up to roughly 75 MB, which makes cloning painful. The repo ships arm64-v8a only — enough for any recent phone. For a full build, extract the other three archives into the matching `jniLibs/<abi>/` directories and run:

```bash
gradle assembleRelease -Pabi=arm64-v8a,armeabi-v7a,x86,x86_64
```

That is exactly how the `full` APK in Releases is produced.

## Replacing / upgrading

Find a PIE static ffmpeg build for the target ABI, rename it to `libffmpeg.so`, overwrite the file in the matching directory, keep the path and name unchanged, then rebuild.

## License note

ffmpeg itself is GPL/LGPL, but this particular build was produced with `--enable-nonfree` so that libfdk-aac could be linked in (libfdk-aac's license is not GPL-compatible). The practical consequence: **the resulting binary — and therefore an APK containing it — is not redistributable.** Keep that in mind if you republish the app; replace the binary with a GPL-only build (libx264 + native aac) first.

The Kotlin code in this repo is MIT; the bundled binary keeps its own license.
