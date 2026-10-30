# Playback decoders

Stream builds the pinned NextLib **1.11.1-0.16.0** sources in `:codecs`,
matching the shared player's Media3 **1.11.1**. `../codecs/deps.lock` pins the
upstream revision and checksums; `../codecs/patches/nextlib/` contains the local
changes. See [build instructions](../codecs/README.md). ARMv7, ARM64 and x86_64
libraries are packaged in the APK; playback needs no helper or codec download.

| Media | Bundled software fallback |
| --- | --- |
| Dolby audio | AC-3, E-AC-3 (including the E-AC-3 base audio in JOC/Atmos streams), TrueHD |
| DTS audio | DTS, DTS-HD base decoding; output is PCM, not a guarantee of object-based surround |
| Other common audio | AAC, MP1/MP2/MP3, FLAC, ALAC, Opus, Vorbis, AMR, A-law/μ-law |
| Video | H.264 (including Hi10P), HEVC/Main10, VP8, VP9, AV1 via dav1d, MPEG-4 Part 2/Xvid/DivX, MSMPEG4v2/v3, MJPEG, VC-1 |

`StreamDecoders` uses Android decoders first and enables the bundled renderers
when platform capabilities cannot handle the actual track. Media3 still
validates the container, encryption, audio output and track format. Source
ranking considers the bundled codecs instead of hiding every release without
a platform decoder. Once Media3 selects a renderer, the app checks its
separate 1080p budget rather than rejecting the track using MediaCodec alone.

A runtime platform decoder failure can switch the affected audio or video
renderer to FFmpeg once per load. It keeps the same player, source, position,
speed, track selections and pause state. Audio output initialization/write
failures may also recover from encoded output to software-decoded PCM. FFmpeg
failures and exhausted retries reach the existing source/error handling.
A new media identity resets decoder modes to automatic. Late subtitle attachment
and same-source payload recovery preserve the successful mode. A change of
compressed codec resets the affected track type. A second audio-output failure
can downmix FFmpeg PCM to stereo once; exhausted retries reach source recovery.

This does not promise arbitrary files: corrupted media, unsupported containers,
DRM, Dolby Vision display support and the app's resolution limit remain
separate concerns. Software decoding throughput depends on the TV CPU. No
certificate or torrent integrity checks are relaxed.

The custom factory retains `SubtitlePlayback.textRenderer`, including the
external subtitle seek and late-selection compatibility path.

The native audio patch validates resampler allocation and output buffer sizes,
owns the resampler even if initialization fails, and uses FFmpeg's extended
channel pointers for planar audio with more than eight channels. Both tracked
patches are reapplied by the dependency fetcher; JNI-only changes rebuild without
re-encoding or rebuilding the unchanged FFmpeg libraries.

## Focused checks

```sh
./simulator.sh decoder-test
(cd android && ./gradlew :app:testDebugUnitTest --tests tv.stream.app.TorrentSourcesTest)
```

`decoder-test` generates small synthetic media, then runs only
`DecoderPlaybackTest` on the verified local emulator. It covers E-AC-3 5.1
through the real BitTorrent engine, native decoder discovery, ten audio
encodings (including surround), software video cases (including 10-bit
H.264/HEVC/VP9/AV1), Xvid/MP42/MP43/MJPEG AVI, 1080p software rendering,
forward/backward seeks requiring fresh frames, PCM output, late captions and
bounded runtime recovery. VC-1 currently has native discovery coverage only;
no generated VC-1 decoding fixture is available. It records the initialized decoder names in private local evidence.
No public providers or swarms are needed. Hardware and format limits are
listed under Known gaps in the [app guide](README.md#known-gaps).

## Distribution and page alignment

`-PabiSplits=true` adds armeabi-v7a, arm64-v8a and x86_64 APKs beside the
universal APK; `:app:bundleRelease` builds the App Bundle. On 30 October 2026
release outputs and bundletool 1.18.0 API 36 APK sets for each ABI were checked
with NDK r29 `llvm-readelf -lW`, build-tools 35 `zipalign -c -P 16 -v 4` and
`aapt2 dump xmltree`:

| Output | Size (bytes) | `zipalign -P 16` | Native libraries |
| --- | --- | --- | --- |
| armeabi-v7a APK | 14,651,413 | OK | stored; LOAD 16 KiB except `libc++_shared.so` (4 KiB) |
| arm64-v8a APK | 17,206,837 | OK | stored; every LOAD 16 KiB |
| x86_64 APK | 21,515,653 | OK | stored; every LOAD 16 KiB |
| Universal APK | 45,503,055 | OK | as per ABI above |
| App Bundle | 31,461,652 | — | per-ABI splits as above, all OK |
| Bundle sets: v7a / arm64 / x86_64 | 15,843,325 / 18,399,213 / 22,708,181 | OK | master, ABI, density and language splits |

Every APK and split has `extractNativeLibs=false`. Bundle sets are larger than
the ABI APKs mainly because bundletool stores `classes.dex` uncompressed for
recent Android versions. Each ABI packages `libavcodec`, `libavutil`,
`libswresample`, `libswscale`, `libmedia3ext`, `libc++_shared`, `libstream` and
`libandroidx.graphics.path`.

The armeabi-v7a `libc++_shared.so` is the NDK r29 (29.0.14206865) prebuilt from
`sysroot/usr/lib/arm-linux-androideabi`; AGP only strips it, and its GNU build ID
matches the prebuilt. (Its `.note.android.ident` says r28/13004108, so that note is
not a reliable NDK version.) The NDK ships 4 KiB segments for both 32-bit ABIs and
16 KiB segments for arm64-v8a and x86_64; its CMake toolchain only ever adds a
page-size flag for the 64-bit ABIs. Android's 16 KiB page sizes exist only on
64-bit devices, and Google Play's 16 KiB requirement covers 64-bit libraries, so no
action is needed. Stream's own 32-bit libraries use 16 KiB segments, which 4 KiB
devices also load.

## Software video conversion

In surface mode the decoder keeps the decoded `AVFrame` and converts it once, on
the playback thread, directly into the locked `RGBA_8888` window buffer with a
cached `SwsContext`. Nothing is copied in between, and the scaler is recreated only
when size, format, matrix or range changes. For 8-bit 4:2:0 input whose width is a
multiple of 16, FFmpeg 9.0.1 uses its unscaled NEON YUV-to-RGBA path on both ARM
ABIs (SSSE3 on x86_64). Other widths use its C converter; 10-bit, 4:4:4 and
full-range `yuvj` input use the general scaler. Rotated video adds a per-pixel
copy.

A host benchmark of the same libswscale configuration (FFmpeg 9.0.1 on an M1 Pro
under load, best of 120 1080p frames) measured 0.39 ms for 8-bit 4:2:0, 1.7 ms
for a width of 1916, and 4.0–4.2 ms for 10-bit 4:2:0. Two possible 10-bit changes
are not applied. Converting to 8-bit 4:2:0 first and then using the NEON path
took 1.4 ms, but adds dithering and loses precision, which changes the picture.
Four swscale slice threads took 1.2–1.4 ms, but would compete with FFmpeg's
decoder threads. TV CPUs are slower; neither result is a device measurement.
`SoftwarePlaybackPerformanceTest` (`./simulator.sh software-profile`) plays a
generated 1080p H.264 (8-bit) and a 1080p HEVC Main 10 file through FFmpeg for a
minute each. On the arm64 emulator (30 October 2026) the whole process used about
28% and 39% of one core, with no dropped frames or rebuffering and 201–214 vs
228–246 MiB PSS. That includes decoding, so the 10-bit conversion is not shown to
limit playback there, and the conversion stays as it is; a slower TV CPU may
differ and has not been measured.

## Third-party notices

NextLib is GPL-3.0. Its native build includes FFmpeg (LGPL-3.0), dav1d
(BSD-2-Clause) and mbedTLS (Apache-2.0). License texts and source links are
included under the APK's `assets/licenses/`; retain them when packaging.
The complete pinned inputs, checksums, local patches and build scripts are in
`../codecs/`; retain these corresponding sources with distributions.
