# Playback decoders

Daniil TV bundles `io.github.anilbeesetti:nextlib-media3ext:1.11.1-0.16.0`
from Maven Central, matching the shared player's Media3 **1.11.1**. Update
these together. [NextLib's tagged sources](https://github.com/anilbeesetti/nextlib/tree/v1.11.1-0.16.0)
contain its native build and decoder implementation. The dependency includes
ARMv7, ARM64 and x86_64 native libraries; no desktop helper or codec download
is needed during playback.

| Media | Bundled software fallback |
| --- | --- |
| Dolby audio | AC-3, E-AC-3 (including the E-AC-3 base audio in JOC/Atmos streams), TrueHD |
| DTS audio | DTS, DTS-HD base decoding; output is PCM, not a guarantee of object-based surround |
| Other common audio | AAC, MP1/MP2/MP3, FLAC, ALAC, Opus, Vorbis, AMR, A-law/μ-law |
| Video | H.264 (including Hi10P), HEVC/Main10, VP8, VP9, AV1 via dav1d |

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
Loading another media request resets both decoder modes to automatic.

This does not promise arbitrary files: corrupted media, unsupported containers,
DRM, Dolby Vision display support and the app's resolution limit remain
separate concerns. Software decoding throughput depends on the TV CPU. No
certificate or torrent integrity checks are relaxed. Daniil Cast retains its
platform-only decoders and desktop conversion behavior.

The custom factory retains `SubtitlePlayback.textRenderer`, including the
external subtitle seek and late-selection compatibility path.

## Focused checks

```sh
./streaming/simulator.sh decoder-test
(cd streaming/android && ./gradlew :app:testDebugUnitTest --tests tv.daniil.stream.TorrentSourcesTest)
```

`decoder-test` generates small synthetic media, then runs only
`DecoderPlaybackTest` on the verified local emulator. It covers E-AC-3 5.1
through the real BitTorrent engine, native decoder discovery, ten audio
encodings (including surround), five software video cases (including 10-bit
H.264/HEVC/VP9/AV1), forward/backward seeks, PCM output, and bounded runtime
recovery. It records the initialized decoder names in private local evidence.
No public providers or swarms are needed.

## Third-party notices

NextLib is GPL-3.0. Its native build includes FFmpeg (LGPL-3.0), dav1d
(BSD-2-Clause) and mbedTLS (Apache-2.0). License texts and source links are
included under the APK's `assets/licenses/`; retain them when packaging.
See the pinned upstream source for the complete corresponding build inputs.
