# ShengYing GongFang · Command-line edition (mediakit)

This is the Python CLI counterpart to [ShengYing GongFang](../README.md) — same feature set, aimed at a desktop or Termux.

A small local tool: pull audio out of videos, cut clips by timestamp, batch-extract a whole folder.
**No ads, no network, no upload, no sign-in** — everything runs locally through ffmpeg.

- Python standard library only, plus the ffmpeg already on your system. No pip dependencies.
- Lossless by default: when the track codec matches, it does `-c:a copy` and moves bytes — no quality loss at all.

---

## 1. Install ffmpeg first (the only external dependency)

ffmpeg isn't a Python package; install it separately.

**Android / Termux**

```bash
pkg update && pkg install ffmpeg
```

**Ubuntu / Debian**

```bash
sudo apt install ffmpeg
```

**macOS**

```bash
brew install ffmpeg
```

You're good once `ffmpeg -version` prints something.

Lookup order: `FFMPEG_BIN` env var → `PATH` → `bin/ffmpeg` next to this folder → common Termux paths.
So even if ffmpeg isn't on `PATH`, you can drop the binary into `bin/`.

---

## 2. Usage

### Option 1: interactive menu (no commands to memorise)

```bash
cd /storage/emulated/0/mediakit      # wherever you put this folder
python3 main.py
```

You get a menu — inspect / extract / clip / batch / self-check — pick a number.
Paste paths directly; quoted paths work too.

### Option 2: command line

```bash
# environment check
python3 main.py --check
python3 -m mediakit.cli check

# inspect a file (duration, codecs, channels, bitrate, resolution)
python3 -m mediakit.cli info video.mp4

# extract audio (m4a by default, lossless when possible)
python3 -m mediakit.cli extract video.mp4

# choose output dir / format / bitrate
python3 -m mediakit.cli extract video.mp4 -o /sdcard/Download -f mp3 -b 320k

# only a slice (0:30 to 1:10)
python3 -m mediakit.cli extract video.mp4 --start 0:30 --end 1:10

# cut a clip: stream copy (fast, lossless, cuts land on keyframes)
python3 -m mediakit.cli clip video.mp4 --start 0:10 --end 0:40

# cut a clip: precise mode (re-encode, frame-accurate, slower)
python3 -m mediakit.cli clip video.mp4 --start 0:10 --end 0:40 --precise

# keep only the audio and trim it
python3 -m mediakit.cli clip video.mp4 --start 0:10 --end 0:40 --audio

# batch: extract audio for a whole directory
python3 -m mediakit.cli batch /sdcard/Movies -o /sdcard/Download/audio -f m4a
```

Output goes to `output/` by default; existing names aren't overwritten, they become `xxx (1).m4a`.
Set `MEDIAKIT_OUT` to change the default output directory.

---

## 3. Timestamp formats

All three are accepted:

| Form | Meaning |
|---|---|
| `90` | 90 seconds |
| `1:30` | 1 min 30 s |
| `00:01:30.500` | 1 min 30.5 s |

---

## 4. Why m4a by default instead of mp3

- ~95% of source videos carry an AAC track, and m4a is an AAC container
- So video → m4a can be a straight stream copy: **seconds, identical to the source**
- Video → mp3 must re-encode (AAC can't go into mp3), which is slower and loses a little quality

Unless a player only supports mp3, there's no reason to convert.

The tool decides from the source codec and never produces a "fake m4a" (an mp3 stream renamed to `.m4a`).

---

## 5. Commands

| Command | What it does |
|---|---|
| `info` | duration, container, video codec/resolution, audio codec/channels/sample rate/bitrate |
| `extract` | video → audio; m4a / mp3 / aac / flac / wav / opus / ogg |
| `clip` | trim by time range, copy or precise mode, audio-only option |
| `batch` | process a whole directory; one failing file doesn't stop the rest, summary at the end |
| `check` | self-check: where ffmpeg is, its version, output directory |

---

## 6. Layout

```
mediakit/
├── main.py                 interactive entry point (menu)
├── README.md               this file
├── run.sh                  one-shot launcher
├── output/                 default output directory (created on demand)
└── mediakit/
    ├── __init__.py         version, public exports
    ├── utils.py            timestamp parsing, file sizes, name sanitising, dedupe
    ├── config.py           every tunable (formats, bitrates, directories, flags)
    ├── ffmpeg_tool.py      locate ffmpeg/ffprobe, run commands, parse progress
    ├── media_info.py       probe media info (ffprobe JSON, fallback without ffprobe)
    ├── extract.py          audio extraction (copy first, transcode when needed)
    ├── clip.py             clip (copy / precise)
    ├── batch.py            batch processing
    └── cli.py              command-line entry point
```

To change default behaviour (format, bitrate, copy switch, output directory) edit `mediakit/config.py` — no need to touch the logic.

---

## 7. FAQ

**Q: The clip starts slightly earlier than I asked.**
Copy mode can only cut on keyframes; that's expected. Add `--precise` for exact cuts.

**Q: Progress stays at 0% then jumps to 100%.**
ffmpeg isn't reporting live progress (huge file or messy timestamps). Results are unaffected.

**Q: "ffmpeg not found".**
Install it as in section 1, or drop the binary into `bin/` next to this folder.

**Q: "Unknown encoder 'libx264' / 'libmp3lame'".**
You have a stripped-down ffmpeg. Use a full build: `pkg install ffmpeg` on Termux and `apt install ffmpeg` on Ubuntu both include them.

**Q: How large a file can it handle?**
No extra limit — ffmpeg streams, so memory use stays flat. A 1–2 GB video on a phone works, just slower.

---

## 8. Verified

Tested on ffmpeg 6.1.1 + Python 3.12:

- AAC track → m4a: stream copy; a 20 s video produced 316 KB of audio, duration exactly 20.000 s
- MP3 track → m4a: transcoded to AAC, duration 10.009 s
- mp3 320k extraction: normal transcode, duration 20.06 s
- Cut 5 s → 12 s: copy mode 7.005 s, precise mode 7.000 s
- Audio-only cut: AAC source → `.m4a`, MP3 source → `.mp3`, both exactly 3.000 s
- Batch over 3 files including one corrupt: 2 succeeded, 1 failed, the bad file didn't affect the rest
