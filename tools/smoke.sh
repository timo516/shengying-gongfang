#!/usr/bin/env bash
# 用「App 里完全相同的参数」跑一遍回归。不需要真机、不需要 Android SDK，只要有 ffmpeg。
#
#   ./tools/make-fixtures.sh      # 先造素材
#   ./tools/smoke.sh [ffmpeg]     # 再跑
#
# 为什么值得有这个东西：1.0.3 的「快速剪」在 mkv+字幕 / opus / wav 上 100% 失败，
# 而这种 bug 在命令行里就能复现，完全不用装机手点。
set -uo pipefail

FF="${1:-${FFMPEG_BIN:-ffmpeg}}"
HERE="$(cd "$(dirname "$0")" && pwd)"
FX="$HERE/fixtures"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

if ! command -v "$FF" >/dev/null 2>&1 && [ ! -x "$FF" ]; then
  echo "找不到 ffmpeg：$FF（可用 FFMPEG_BIN 指定）"
  exit 1
fi
[ -d "$FX" ] || { echo "先跑 ./tools/make-fixtures.sh"; exit 1; }

pass=0
fail=0
ok()  { printf '  \033[32m✓\033[0m %s\n' "$1"; pass=$((pass + 1)); }
bad() { printf '  \033[31m✗\033[0m %s\n' "$1"; fail=$((fail + 1)); }
section() { printf '\n%s\n' "$1"; }

run() { "$FF" -hide_banner -nostats -loglevel error "$@" >/dev/null 2>&1; }

# 期望成功
expect_ok() {
  local desc="$1"; shift
  if run "$@"; then ok "$desc"; else bad "$desc"; fi
}

# 期望失败（证明"反例"确实是错的）
expect_fail() {
  local desc="$1"; shift
  if run "$@"; then bad "$desc —— 本该失败却成功了"; else ok "$desc"; fi
}

section "① 快速剪：沿用源容器 + 只映射首条视频/音频（1.1.0 的修法）"
expect_ok "mkv+字幕 -> mkv" -y -ss 0.500 -i "$FX/with-sub.mkv" -t 2 \
  -c copy -map 0:v:0? -map 0:a:0? "$TMP/a.mkv"
expect_ok "mkv+opus -> mkv" -y -i "$FX/opus.mkv" -t 2 \
  -c copy -map 0:v:0? -map 0:a:0? "$TMP/b.mkv"
expect_ok "wav -> wav" -y -i "$FX/plain.wav" -t 2 \
  -c copy -map 0:v:0? -map 0:a:0? "$TMP/c.wav"
expect_ok "mp4 -> mp4" -y -i "$FX/plain.mp4" -t 2 \
  -c copy -map 0:v:0? -map 0:a:0? "$TMP/d.mp4"

section "② 反例：1.0.3 的写法必须失败（否则这条回归没意义）"
expect_fail "mkv+字幕 用 -map 0 塞进 .mp4" -y -i "$FX/with-sub.mkv" -t 2 -c copy -map 0 "$TMP/e.mp4"
expect_fail "wav 用 -map 0 塞进 .mp4" -y -i "$FX/plain.wav" -t 2 -c copy -map 0 "$TMP/f.mp4"
expect_fail "wav 转 mp3 塞进 .m4a（ipod muxer 不收 mp3）" \
  -y -i "$FX/plain.wav" -t 2 -c:a libmp3lame "$TMP/g.m4a"

section "③ 精确剪：重编码进 mp4"
expect_ok "mkv -> mp4" -y -ss 0.500 -i "$FX/with-sub.mkv" -t 2 \
  -c:v libx264 -preset veryfast -crf 20 -pix_fmt yuv420p -c:a aac -b:a 192k \
  -movflags +faststart "$TMP/h.mp4"

section "④ 抽音频"
expect_ok "mp4 -> m4a（copy）" -y -i "$FX/plain.mp4" -vn -c:a copy "$TMP/i.m4a"
expect_ok "wav -> m4a（转码）" -y -i "$FX/plain.wav" -vn -c:a aac -b:a 192k "$TMP/j.m4a"
expect_ok "mp4 -> mp3" -y -i "$FX/plain.mp4" -vn -c:a libmp3lame -b:a 192k "$TMP/k.mp3"

section "⑤ 波形解码（8kHz 单声道 PCM）"
expect_ok "pcm 解码" -y -i "$FX/long-30s.mp3" -vn -ac 1 -ar 8000 -f s16le "$TMP/l.raw"

section "⑥ 拼接导出（atrim + concat）"
FILTER="[0:a]atrim=start=0.000:end=1.000,asetpts=PTS-STARTPTS[a0];[0:a]atrim=start=2.000:end=3.000,asetpts=PTS-STARTPTS[a1];[a0][a1]concat=n=2:v=0:a=1[out]"
expect_ok "两段拼接" -y -i "$FX/plain.mp4" -filter_complex "$FILTER" -map "[out]" -vn \
  -c:a aac -b:a 192k "$TMP/m.m4a"

printf '\n通过 %d 项，失败 %d 项\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
