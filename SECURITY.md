# Security Policy

## Supported versions

| Version | Supported |
|---|---|
| 1.0.3 and later | ✅ |
| 1.0.2 and earlier | ❌ (please upgrade; note the signing-key change means an uninstall is required once) |

## Scope

In scope:

- The Android app: file handling, path traversal, unsafe `ffmpeg` argument construction, permission handling, intent handling (the share-sheet entry point), and the audio editor's temp-file handling.
- The Python CLI in `python/`: same categories.
- Release integrity: a way to produce a build that passes signature verification but behaves differently, or checksums / fingerprints that don't match what is published.
- The bundled `libffmpeg.so` (a 2018 static build of ffmpeg 3.3.2). If you find a vulnerability in the binary itself, please report it to the ffmpeg project as well — but tell us too, since we ship it.

Out of scope:

- The GitHub platform, or the security of my GitHub account itself.
- Devices that are already rooted or running malicious apps.
- Damage a malicious *media file* can do outside the app sandbox — the app only gets the file you pick plus its own storage scope.
- Social engineering, and repackaged APKs from unofficial channels. Report those to us anyway — we'll add warnings — but they are not a vulnerability in this codebase.
- Anything that requires physical access to an unlocked device.

## Reporting a vulnerability

**Please do not open a public issue for a security problem.**

Preferred: use GitHub's private advisory form —

<https://github.com/timo516/shengying-gongfang/security/advisories/new>

If that doesn't work, open a normal issue that says only "security issue, please contact me" and wait for a reply — don't include details.

Please include:

- App version (and APK variant: `arm64` or `full`), Android version and device model
- Steps to reproduce, and the impact you believe it has
- A proof-of-concept file if relevant. **Do not attach personal media** — reproduce with a generated sample (e.g. `ffmpeg -f lavfi -i testsrc ...`) instead.

## What to expect

- Acknowledgement: best effort, usually within 7 days.
- Fixes ship in the next release; the changelog will describe the fix without giving a ready-made exploit.
- Credit in the release notes if you want it — tell me the name/handle to use, or say you'd rather stay anonymous.

## Verifying that a build is official

Only APKs published on the [Releases page](https://github.com/timo516/shengying-gongfang/releases) are the original build. The signing certificate fingerprint is published in the [README](README.md#verify-the-download); anything that doesn't match is not mine.

This matters more than usual here: the "no `INTERNET` permission" guarantee is a property of *this* build. A repackaged APK can add permissions, and the app would look identical.

## License note

The code is MIT, the bundled ffmpeg binary is GPL. A license dispute is not a security issue — open a normal issue for that.
