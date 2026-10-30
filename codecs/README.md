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

## Decoder-only evaluation

An isolated arm64 FFmpeg 9.0.1 build on the development Mac compared the current
configuration with the same decoder and parser list but FFmpeg's avformat and
networking disabled. Using four build jobs and the same prebuilt dav1d inputs,
configure plus build took 77 seconds for the current configuration and 67 seconds
for the reduced one. These are single runs, not controlled benchmarks. The four
stripped libraries used by the app totalled 8,241,272 bytes and 8,216,480 bytes
respectively, a 24,792-byte difference. The current APK does not package the
separately built libavformat, so its much larger scratch-build size is not an APK
saving. This experiment did not build an APK with the reduced libraries or run
playback, TLS or other ABI checks. The shipping configuration is unchanged;
the reduced build requires those checks before adoption. Scratch build logs and
outputs are retained locally via `simulator/.cache/evidence/ffmpeg-eval-path.txt`.

NextLib is GPL-3.0; FFmpeg uses the LGPL-3.0 configuration, dav1d BSD-2-Clause,
and mbedTLS Apache-2.0. Retain the license texts and corresponding source/build
inputs with distributions. APK notices are in
`../android/app/src/main/assets/licenses/`. This directory, lockfile and patches
are the local modifications needed in addition to the pinned upstream sources.
