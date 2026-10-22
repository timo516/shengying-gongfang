#!/usr/bin/env bash
# 生成「最容易让容器/编码组合翻车」的测试素材。
# 不需要真机、不需要真实素材，全部用 lavfi 现场合成（每个文件 3 秒、几十 KB）。
#
#   ./tools/make-fixtures.sh [输出目录]      默认 tools/fixtures
#
# 生成的东西正好覆盖 1.0.3 踩过的坑，配套回归见 tools/smoke.sh。
set -euo pipefail

FF="${FFMPEG_BIN:-ffmpeg}"
OUT="${1:-$(cd "$(dirname "$0")" && pwd)/fixtures}"
mkdir -p "$OUT"

command -v "$FF" >/dev/null 2>&1 || { echo "找不到 ffmpeg（可用 FFMPEG_BIN 指定）"; exit 1; }

echo "→ $OUT"

# 1) 带字幕的 mkv：把 -map 0 的流复制塞进 mp4 会因为 subrip 失败
printf '1\n00:00:00,500 --> 00:00:01,500\nhello\n' > "$OUT/sub.srt"
"$FF" -hide_banner -loglevel error -y \
  -f lavfi -i "testsrc=d=3:s=320x240:r=10" -f lavfi -i "sine=d=3" -i "$OUT/sub.srt" \
  -c:v libx264 -preset ultrafast -pix_fmt yuv420p -c:a aac -c:s srt -shortest \
  "$OUT/with-sub.mkv"

# 2) opus 音轨的 mkv：opus 塞不进 mp4（网上下载的视频非常常见）
"$FF" -hide_banner -loglevel error -y \
  -f lavfi -i "testsrc=d=3:s=320x240:r=10" -f lavfi -i "sine=d=3" \
  -c:v libx264 -preset ultrafast -pix_fmt yuv420p -c:a libopus -shortest \
  "$OUT/opus.mkv"

# 3) 纯 wav：pcm 塞不进 mp4，对应「纯音频文件点快速剪」
"$FF" -hide_banner -loglevel error -y -f lavfi -i "sine=d=3" -c:a pcm_s16le "$OUT/plain.wav"

# 4) 多音轨：ffmpeg -i 的输出会超过 12 行，专治 probe 兜底只留末 12 行的问题
"$FF" -hide_banner -loglevel error -y \
  -f lavfi -i "sine=d=3" -f lavfi -i "sine=frequency=800:d=3" -f lavfi -i "sine=frequency=1000:d=3" \
  -map 0 -map 1 -map 2 -c:a aac "$OUT/multi-audio.mkv"

# 5) 普通 mp4：h264 + aac，对照组
"$FF" -hide_banner -loglevel error -y \
  -f lavfi -i "testsrc=d=3:s=320x240:r=10" -f lavfi -i "sine=d=3" \
  -c:v libx264 -preset ultrafast -pix_fmt yuv420p -c:a aac -shortest "$OUT/plain.mp4"

# 6) 长一点的高采样率 mp3：给「波形分析」和进度用
"$FF" -hide_banner -loglevel error -y -f lavfi -i "sine=d=30:sample_rate=48000" \
  -c:a libmp3lame -b:a 128k "$OUT/long-30s.mp3"

ls -lh "$OUT" | tail -n +2
