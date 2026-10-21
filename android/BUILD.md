# Android build guide

Project: `android/` (Kotlin, no third-party dependencies, ffmpeg binary bundled).

English | [中文说明](BUILD.zh-CN.md)

---

## 1. Requirements

| Item | Version |
|---|---|
| JDK | 17 |
| Gradle | 8.7 (system `gradle` is fine, no wrapper in the repo) |
| Android SDK | platform-34 + build-tools 34.0.0 |
| AGP / Kotlin | 8.5.2 / 1.9.24 (declared in `build.gradle`) |

No SDK yet:

```bash
yes | sdkmanager --licenses
sdkmanager "platforms;android-34" "build-tools;34.0.0"
```

---

## 2. Build

```bash
cd android
echo "sdk.dir=/path/to/Android/Sdk" > local.properties    # or set ANDROID_HOME

gradle assembleDebug          # output: app/build/outputs/apk/debug/
gradle assembleRelease        # output: app/build/outputs/apk/release/ (needs signing, see §5)
```

You can also open `android/` in Android Studio (**not** the repo root) and use `Build → Build APK(s)`.

**ABI** defaults to `arm64-v8a` only (that's the only `libffmpeg.so` in the repo). For a full build, add the other three binaries per [JNILIBS.md](JNILIBS.md), then:

```bash
gradle assembleRelease -Pabi=arm64-v8a,armeabi-v7a,x86,x86_64
```

---

## 3. Hard constraints (breaking these breaks the app)

1. **Keep `jniLibs/arm64-v8a/libffmpeg.so` exactly as is.**
   It must live under `jniLibs/<abi>/`; it must be named `lib*.so` (Gradle only packages that pattern as a native library); and its content is actually an ffmpeg **executable**, not a real shared object — that's intentional.

2. **`packaging { jniLibs { useLegacyPackaging = true } }` and `android:extractNativeLibs="true"` must stay.**
   Since API 29 Android forbids executing files in the data directory; `nativeLibraryDir` is the only legal location. Turn these off and the binary is never extracted as a real file, so the app reports "no bundled ffmpeg" on startup.

3. **Don't declare ABIs you don't ship.** It only bloats the APK and still won't run.

4. **Don't enable `minifyEnabled true`** (no keep rules configured), and don't add the `INTERNET` permission — "fully offline" depends on that line.

5. **Never commit `keystore.properties` or a `.jks`.**

---

## 4. On-device checklist

| Step | Expected |
|---|---|
| Launch the app | First line reads "已就绪（内置 ffmpeg，完全离线）" |
| Pick a video | Duration, resolution, "video + audio" shown |
| Tap "m4a 无损" | Finishes in seconds; `Download/xxx.m4a` plays |
| Tap "mp3 192k" | Same, a `.mp3` appears |
| Set start `0:05`, end `0:10`, quick cut | ~5 s clip (cut snaps to a keyframe) |
| Same with precise cut | Clip is exactly 5.000 s |
| Tap "编辑音频" | Waveform appears; selection, delete marks, audition and export all work |
| Rerun any of the above in airplane mode | Everything still works → offline verified |

---

## 5. Signing

The repo ships no signing material. To use your own:

```bash
cp keystore.properties.example keystore.properties
# fill in storeFile / storePassword / keyAlias / keyPassword
```

When `keystore.properties` exists, `app/build.gradle` points both release and debug at that key (so debug and release builds share a signature and can overwrite each other on a device). Without the file, signing is skipped and the build produces an unsigned APK.

Sign by hand without the properties file:

```bash
gradle assembleRelease
apksigner sign --ks your.jks --out app-release.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk
```

> Changing keys makes existing users unable to upgrade over the top ("signature mismatch"). Decide before you publish, and keep the keystore and password somewhere safe.

---

## 6. FAQ

**Q: First line says "no bundled ffmpeg".**
A: ① the device ABI isn't in the APK (this repo is arm64 only); ② `libffmpeg.so` was removed or renamed; ③ `extractNativeLibs` was turned off.

**Q: Quick cut starts earlier than requested.**
A: `-c copy` can only cut on keyframes, so the start snaps to the nearest one. Use precise cut when it must be exact.

**Q: How big is the APK?**
A: ffmpeg is 17 MB (arm64), giving ~8.7 MB for the arm64 APK and ~33.7 MB for the 4-ABI APK.

**Q: Can I swap in a newer ffmpeg?**
A: Get a PIE static build for the target ABI, replace `jniLibs/<abi>/libffmpeg.so`, keep the name and location.

---

## 7. Building on an arm64 host (Termux / proot)

Skip this on x86_64 machines. On an arm64 host you'll hit:

```
AAPT2 Daemon startup failed
.../build-tools/34.0.0/aapt2: cannot execute: required file not found
```

because Google only ships an **x86_64** `aapt2`. Work around it with qemu user-mode emulation:

```bash
apt install -y qemu-user-static
dpkg --add-architecture amd64
echo 'deb [arch=amd64] http://archive.ubuntu.com/ubuntu noble main' > /etc/apt/sources.list.d/amd64.list
apt update && apt install -y libc6:amd64 libstdc++6:amd64 libgcc-s1:amd64 zlib1g:amd64

printf '#!/bin/sh\nexec /usr/bin/qemu-x86_64-static /opt/android-sdk/build-tools/34.0.0/aapt2 "$@"\n' > /opt/bin/aapt2
chmod 755 /opt/bin/aapt2
```

Then pass it on the command line (do **not** put it in the project's `gradle.properties`, that would break everyone else's build):

```bash
gradle assembleDebug -Pandroid.aapt2FromMavenOverride=/opt/bin/aapt2 --no-daemon
```

Also:

- Don't run Gradle inside an sdcard directory (FUSE locking and permissions break); `cp -a` to a local disk first.
- `DisconnectableInputStream ... Bad file descriptor` in logs from a background Gradle run is nohup noise without stdin — harmless.
- If you see `mergeReleaseResources ... no data file for changedFile` after changing resources, run `gradle clean` and retry.
