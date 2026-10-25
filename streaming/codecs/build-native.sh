#!/bin/sh
# Build only the pinned decoder module; the TV never downloads executable code.
set -eu
here=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
"$here/fetch-deps.sh"
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}
ndk=$(awk '$1 == "ndk" {print $2}' "$here/../native/deps.lock")
cmake=$(awk '$1 == "cmake" {print $2}' "$here/../native/deps.lock")
export ANDROID_HOME="$sdk" ANDROID_NDK_VERSION="$ndk" ANDROID_CMAKE_VERSION="$cmake"
PATH="$sdk/cmake/$cmake/bin:/opt/homebrew/bin:$PATH"
export PATH
# JNI/rendering patches rebuild through CMake; only FFmpeg inputs invalidate its expensive build.
want=$( (cat "$here/deps.lock" "$here/../native/deps.lock"; git -C "$here/.deps/nextlib" diff HEAD -- ffmpeg/setup.sh) | shasum -a 256 | cut -d ' ' -f 1)
base="$here/.deps/nextlib/ffmpeg"
complete=1
for abi in arm64-v8a armeabi-v7a x86_64; do
  for library in avcodec avutil swresample swscale; do [ -s "$base/output/lib/$abi/lib$library.so" ] || complete=0; done
done
[ "$complete" = 1 ] && [ -f "$here/.deps/native.stamp" ] && [ "$(cat "$here/.deps/native.stamp")" = "$want" ] && exit 0
for name in meson ninja nasm pkg-config; do command -v "$name" >/dev/null || { printf 'Install the codec build tool %s.\n' "$name" >&2; exit 1; }; done
mkdir -p "$base/sources"
for spec in ffmpeg:9.0.1 dav1d:1.5.4 mbedtls:3.6.7; do
  name=${spec%:*}; version=${spec#*:}
  dest="$base/sources/$name-$version"
  if [ ! -e "$dest" ]; then ln -s "$here/.deps/$name" "$dest"; fi
done
printf 'Building pinned FFmpeg decoders for arm64-v8a, armeabi-v7a and x86_64...\n'
if ! bash "$base/setup.sh" >"$here/.deps/native-build.log" 2>&1; then
  tail -n 70 "$here/.deps/native-build.log" >&2
  exit 1
fi
printf '%s\n' "$want" >"$here/.deps/native.stamp"
