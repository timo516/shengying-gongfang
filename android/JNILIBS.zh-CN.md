# 内置 ffmpeg 二进制

`libffmpeg.so` 不是真正的共享库，而是**静态链接的 ffmpeg 可执行文件**改名而来。这么命名的唯一目的，是让 Gradle 把它当 native 库打进 APK 的 `lib/<abi>/`；装到手机上之后它落在 `nativeLibraryDir`，那是 Android 唯一允许执行文件的目录。

## 来源

https://github.com/Khang-NT/ffmpeg-binary-android
release：`2018-07-31`（ffmpeg 3.3.2，NDK r15c 构建，API 21+，PIE）

| ABI | 压缩包 | 大小 |
|---|---|---|
| arm64-v8a | `arm64-v8a-full.tar.bz2` | 17.8 MB |
| armeabi-v7a | `armv7-a-full.tar.bz2` | 16.5 MB |
| x86 | `i686-full.tar.bz2` | 19.9 MB |
| x86_64 | `x86_64-full.tar.bz2` | 20.6 MB |

编译时启用：libx264 / libmp3lame / libfdk-aac / libopus / libvorbis / openssl，全静态。

## 为什么仓库里只放 arm64

四份加起来约 75 MB，clone 会非常痛苦。仓库只带 arm64-v8a，近几年手机都够用。要打全量包，把另外三个解压进对应的 `jniLibs/<abi>/`，再执行：

```bash
gradle assembleRelease -Pabi=arm64-v8a,armeabi-v7a,x86,x86_64
```

Release 里的 `full` 包就是这么来的。

## 替换 / 升级

找一个目标 ABI 的 PIE 静态 ffmpeg，改名为 `libffmpeg.so`，覆盖对应目录里的文件，路径和文件名都不要改，然后重新构建。

## 许可说明

ffmpeg 本身是 GPL/LGPL，但这一份构建用了 `--enable-nonfree` 才把 libfdk-aac 链接进来（libfdk-aac 的许可与 GPL 不兼容）。实际后果是：**这个二进制——以及任何包含它的 APK——不可再分发。** 如果你要重新发布，请先把二进制换成只含 GPL 组件的构建（libx264 + 内置 aac）。

本仓库的 Kotlin 代码是 MIT，内置二进制的许可独立存在。
