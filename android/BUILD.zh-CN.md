# Android 构建说明

[English](BUILD.md) | 中文说明

工程：`android/`（Kotlin，零第三方依赖，内置 ffmpeg 二进制）。

---

## 1. 环境要求

| 项 | 版本 |
|---|---|
| JDK | 17 |
| Gradle | 8.7（系统 `gradle` 即可，工程没带 wrapper） |
| Android SDK | platform-34 + build-tools 34.0.0 |
| AGP / Kotlin | 8.5.2 / 1.9.24（`build.gradle` 里声明） |

没有安装 SDK 的机器：

```bash
yes | sdkmanager --licenses
sdkmanager "platforms;android-34" "build-tools;34.0.0"
```

---

## 2. 构建

```bash
cd android
echo "sdk.dir=/你的/Android/Sdk" > local.properties    # 或改用 ANDROID_HOME 环境变量

gradle assembleDebug          # 产物 app/build/outputs/apk/debug/
gradle assembleRelease        # 产物 app/build/outputs/apk/release/（需配签名，见第 5 节）
```

也可以直接用 Android Studio 打开 `android/` 目录（**不是仓库根目录**），`Build → Build APK(s)`。

**ABI** 默认只有 `arm64-v8a`（仓库只带了这一份 `libffmpeg.so`）。想打全量包，先按 [JNILIBS.zh-CN.txt](JNILIBS.zh-CN.txt) 补齐其余三个 ABI 的二进制，再：

```bash
gradle assembleRelease -Pabi=arm64-v8a,armeabi-v7a,x86,x86_64
```

---

## 3. 硬性约束（改了就坏）

1. **`jniLibs/arm64-v8a/libffmpeg.so` 必须原样保留。**
   ① 必须在 `jniLibs/<abi>/` 下；② 必须是 `lib*.so` 命名（Gradle 只把这个模式当 native 库打包）；③ 内容其实是 ffmpeg **可执行文件**，不是真 `.so`——这是故意的。

2. **`packaging { jniLibs { useLegacyPackaging = true } }` 和 `android:extractNativeLibs="true"` 不能关。**
   Android 从 API 29 起禁止执行 data 目录里的文件，`nativeLibraryDir` 是唯一合法执行位置。关了这两项，二进制不会被解压成真实文件，App 启动就报「找不到内置 ffmpeg」。

3. **`abiFilters` 别乱加。** 没放对应二进制却声明了 ABI，只会让包变大、装上还跑不起来。

4. **不要开 `minifyEnabled true`**（没配 keep 规则），也不要加 `INTERNET` 权限——「完全离线」写在这一行。

5. **不要把 `keystore.properties` / `.jks` 提交进仓库。**

---

## 4. 真机验收清单

| 步骤 | 期望 |
|---|---|
| 打开 App | 第一行显示「已就绪（内置 ffmpeg，完全离线）」 |
| 选一段手机拍的视频 | 显示时长、分辨率、「视频 音频」 |
| 点「m4a 无损」 | 几秒完成，`Download/音视频提取剪辑软件/提取的音频包/xxx.m4a` 能播 |
| 点「mp3 192k」 | 同上，出 `.mp3` |
| 起点 `0:05`、终点 `0:10`，点快速剪 | 出一个 5 秒左右的片段（切口吸附关键帧） |
| 同上点精确剪 | 片段时长为 5.000 秒 |
| 点「编辑音频」 | 波形出现，能拖动选区、标记删除、试听、导出 |
| 飞行模式下重跑上面任意一步 | 全部正常 → 离线验证通过 |

---

## 5. 签名

仓库不带任何签名材料。要用自己的签名：

```bash
cp keystore.properties.example keystore.properties
# 填 storeFile / storePassword / keyAlias / keyPassword
```

`app/build.gradle` 会在 `keystore.properties` 存在时把 release 和 debug 都指到这份签名上（避免真机上 debug/release 签名不一致导致无法覆盖安装）；文件不存在则跳过签名，产出 unsigned 包。

没有 `keystore.properties` 时手工签：

```bash
gradle assembleRelease
apksigner sign --ks your.jks --out app-release.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk
```

> 换签名会让老用户无法覆盖升级（报「签名不一致」），发布前想清楚。建议长期保存 keystore 与密码。

---

## 6. 常见问题

**Q：第一行显示「没找到内置 ffmpeg」？**
A：① 设备 CPU 架构不在包内（本仓库只有 arm64）；② `libffmpeg.so` 被删/改名；③ `extractNativeLibs` 被关掉。

**Q：快速剪出来的起点比要求的早？**
A：`-c copy` 只能切在关键帧上，起点会吸附到最近关键帧。要严格就用「精确剪」。

**Q：包有多大？**
A：ffmpeg 二进制 17 MB（arm64），APK 约 8.7 MB（arm64）/ 33.7 MB（四 ABI）。

**Q：想换更新的 ffmpeg？**
A：找 arm64 的 PIE 静态构建，替换 `jniLibs/arm64-v8a/libffmpeg.so`，文件名和位置不变即可。

---

## 7. 在 arm64 主机（Termux / proot）上构建的坑

x86_64 电脑不用看这节。宿主机是 arm64 时会遇到：

```
AAPT2 Daemon startup failed
.../build-tools/34.0.0/aapt2: cannot execute: required file not found
```

因为 Google 只发 **x86_64** 版 `aapt2`。解决办法（qemu 用户态模拟）：

```bash
apt install -y qemu-user-static
dpkg --add-architecture amd64
echo 'deb [arch=amd64] http://archive.ubuntu.com/ubuntu noble main' > /etc/apt/sources.list.d/amd64.list
apt update && apt install -y libc6:amd64 libstdc++6:amd64 libgcc-s1:amd64 zlib1g:amd64

printf '#!/bin/sh\nexec /usr/bin/qemu-x86_64-static /opt/android-sdk/build-tools/34.0.0/aapt2 "$@"\n' > /opt/bin/aapt2
chmod 755 /opt/bin/aapt2
```

构建时通过命令行参数指过去（**不要**写进工程的 `gradle.properties`，否则会污染别人的构建）：

```bash
gradle assembleDebug -Pandroid.aapt2FromMavenOverride=/opt/bin/aapt2 --no-daemon
```

另外：

- 别在 sdcard 目录里直接跑 Gradle（FUSE 的锁文件和权限会出问题），先 `cp -a` 到本地盘。
- 后台跑 Gradle 时日志里的 `DisconnectableInputStream ... Bad file descriptor` 是 nohup 无 stdin 的噪音，不影响结果。
- 换过资源文件后若报 `mergeReleaseResources ... no data file for changedFile`，`gradle clean` 重来。
