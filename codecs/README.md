# Pinned Android decoder build

`:codecs` builds the pinned NextLib sources plus the reviewed patches in
`patches/nextlib/`. Media3 remains 1.11.1 in the shared player and this module.
The patches add legacy video codecs, container initialization data/dimensions,
PCM stereo recovery, hardware/display format gating, and resampler allocation,
channel-pointer and buffer-size guards. FFmpeg performs decoding;
there is no download of executable code on the TV and no transcoding server.

`deps.lock` pins NextLib's commit and SHA-256 checksums for FFmpeg, dav1d and
mbedTLS. `fetch-deps.sh` verifies downloads and reapplies tracked patches; edit
patches, never the ignored `.deps/` checkout. The NDK and CMake versions come
from `../native/deps.lock`. Gradle fetches sources before compilation and builds
native libraries before CMake configuration. The first build requires network
access and is substantially slower than incremental builds.

Install Java 21, the Android SDK, Git, curl, tar, a C/C++ host toolchain, Python,
Meson, Ninja, NASM and pkg-config (`brew install meson ninja nasm pkgconf` on
macOS). Then build through `android/gradlew :app:assembleDebug`.
`build-native.sh` also works independently with `ANDROID_HOME` set. It builds
arm64-v8a, armeabi-v7a and x86_64. Build logs and checksum-keyed FFmpeg outputs
are retained under `.deps/`; JNI-only changes rebuild through CMake.

The software video renderer outputs SDR. HDR requires a matching hardware
codec and display; software RGBA8 output is not a tone mapper. The app retains
its 1080p device budget. Native decoder discovery alone does not prove that a
container extractor can deliver a particular format or that a TV can decode it
fast enough. See `../android/DECODERS.md` for verified combinations and limits.

## Packaged libraries

The APK packages `libavcodec`, `libavutil`, `libswresample`, `libswscale`, NextLib's
`libmedia3ext` and the NDK's `libc++_shared`. None of them lists `libavformat.so` as
`NEEDED`, and none contains mbedTLS code: `setup.sh` builds libavformat (with its
network protocols and statically linked mbedTLS), but `media3ext`'s CMake imports
only the four libraries above, so libavformat is never packaged. The FFmpeg
libraries embed their configure line (`avutil_configuration()`), which includes
the absolute build paths of the machine that built them.

## Decoder-only evaluation

The reduced configuration keeps the decoder, parser, dav1d and swresample/swscale
options and drops `--enable-avformat`, `--enable-demuxers`, `--enable-protocol=...`
and `--enable-mbedtls` (adding `--disable-network`), so mbedTLS is neither fetched
nor built. Through the normal tooling this means changing the `ffmpeg/setup.sh`
hunk of `patches/nextlib/0001-stream-codecs.patch`, the source links in
`build-native.sh` and the mbedtls line of `deps.lock`; `build-native.sh` must
also remove `ffmpeg/build` and `ffmpeg/output`, because `setup.sh` copies every
library in its install prefix and would otherwise repackage a stale libavformat
into `output/`.

On 30 October 2026 both configurations were built for all three ABIs from a clean
worktree on an M1 Pro (10 build jobs, NDK r29, FFmpeg 9.0.1), followed by
`:app:assembleRelease -PabiSplits=true` and `:app:bundleRelease`. Concurrent
Gradle and emulator work kept the load average between 19 and 44, so the times
are only indicative:

| | Current | Decoder-only |
| --- | --- | --- |
| `build-native.sh` (mbedTLS, dav1d, FFmpeg × 3 ABIs) | 241 s, 265 s | 198 s, 207 s |
| armeabi-v7a APK | 14,651,413 B | 14,634,917 B (−16,496) |
| arm64-v8a APK | 17,206,837 B | 17,173,973 B (−32,864) |
| x86_64 APK | 21,515,653 B | 21,482,757 B (−32,896) |
| Universal APK | 45,503,055 B | 45,421,007 B (−82,048) |
| App Bundle | 31,461,652 B | 31,424,744 B (−36,908) |
| arm64 bundle APK set (bundletool, API 36) | 18,399,213 B | 18,366,445 B (−32,768) |

APKs are unsigned release outputs; bundle sets were signed by bundletool with the
local debug key. Per ABI the four packaged FFmpeg libraries shrink by 22,836
(armeabi-v7a) to 24,840 (arm64-v8a) bytes, almost all in libavcodec (arm64
6,162,232 → 6,137,712 bytes). APK differences vary because each stored library
is padded to a 16 KiB boundary.
Repeated current builds produced byte-identical libraries. The saving is about
0.1–0.2% of each APK, so the shipping configuration is unchanged. Adoption would
be justified by roughly 20% less native build time and one fewer pinned
dependency, not size, and needs the decoder, subtitle, seek and software-profile
device tests first. Results are retained under the ignored
`simulator/.cache/evidence/dist/`.

NextLib is GPL-3.0; FFmpeg uses the LGPL-3.0 configuration, dav1d BSD-2-Clause,
and mbedTLS Apache-2.0. Retain the license texts and corresponding source/build
inputs with distributions. APK notices are in
`../android/app/src/main/assets/licenses/`. This directory, lockfile and patches
are the local modifications needed in addition to the pinned upstream sources.
