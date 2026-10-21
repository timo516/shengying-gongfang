# 更新日志

## v1.0.3（2026-10-20）

**正式签名 + 深色模式 + 长任务提示 + 音频剪辑器**

| 文件 | ABI | 大小 |
|---|---|---|
| `shengying-gongfang-1.0.3-arm64.apk` | arm64-v8a | 8.7 MB |
| `shengying-gongfang-1.0.3-full.apk` | arm64-v8a / armeabi-v7a / x86 / x86_64 | 33.7 MB |

versionCode 5，minSdk 24 / targetSdk 34。

### 新增

- **音频剪辑器**：波形时间轴、双滑块选区、多段标记删除、只保留选区、选区试听、拼接导出。
  可直接导入音频，也可以导入视频（先自动抽音轨）。
- **深色模式**：颜色走 `values/colors.xml` + `values-night/colors.xml`，跟随系统切换；顺手去掉了重复的系统 ActionBar。
- **自适应图标**：前景/背景分层 + Android 13 主题化单色图标，另附五档 PNG 兜底。
- **长任务提示**：素材 ≥60 秒或 ≥150MB 时，任务开始前提示「保持 App 在前台」，同时 `FLAG_KEEP_SCREEN_ON` 保持亮屏，结束自动撤销。覆盖提取音频 / 快速剪 / 精确剪 / 剪辑器导入 / 剪辑器导出五个入口。
- **预测式返回**：`enableOnBackInvokedCallback="true"`，Android 13+ 手势返回动画。
- **ABI 可配置**：`-Pabi=...`，默认 arm64-v8a。

### 变更

- **换正式签名**：RSA 4096，有效期至 2056 年，V2 + V3 签名方案。
  与 1.0.0～1.0.2 的 debug 签名**不兼容**，老用户需先卸载再装。
- 输出目录策略：有「所有文件访问」权限时写入 `手机存储/音视频提取剪辑软件/提取的音频包/`，
  没有则降级到 `Download/音视频提取剪辑软件/提取的音频包/`。

---

## v1.0.1

**「查看」按钮修复 + 跳厂商文件管理器**

- 修 `Output.openFolder()`：`setData()` 与 `setType()` 互相清空，导致 Intent 无目标、按钮点了没反应。统一改 `setDataAndType(uri, type)`。
- 「查看」优先跳厂商文件管理器（小米 / OPPO / 一加 / vivo / 华为 / 荣耀 / 三星 / 魅族 / 联想），失败退回 DocumentsUI，再失败复制路径到剪贴板并提示。
- Manifest 补 `<queries>`，否则 Android 11+ 上包可见性限制会让厂商管理器探测全部落空。

---

## v1.0.0

首个可用版本：

- 提取音频：m4a 无损（`-c:a copy`）/ mp3 192k / flac / wav / aac / opus / ogg
- 剪辑片段：快速剪（`-c copy`，关键帧对齐）/ 精确剪（libx264 重编码）
- 系统分享入口：其他 App 直接分享视频/音频进来
- 结果落 `Download/音视频提取剪辑软件/提取的音频包/`，重名自动加序号
- 内置 ffmpeg 静态二进制，无 `INTERNET` 权限
