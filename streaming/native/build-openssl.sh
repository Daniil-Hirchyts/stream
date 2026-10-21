#!/bin/sh
# Builds static OpenSSL from the pinned source for one target:
#   build-openssl.sh host | arm64-v8a | armeabi-v7a | x86_64
# Output: .deps/build/openssl/<target>/{include,lib}. Android targets need ANDROID_NDK_ROOT (or the pinned NDK in
# the default SDK) and use API 26, the app's minimum.
set -eu
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
deps=${DANIILTV_DEPS_DIR:-$here/.deps}
target=${1:?usage: build-openssl.sh host|arm64-v8a|armeabi-v7a|x86_64}
source="$deps/openssl"
[ -f "$source/.pinned" ] || { echo 'Run fetch-deps.sh first.' >&2; exit 1; }
prefix="$deps/build/openssl/$target"
stamp="$prefix/.built"
want=$(cat "$source/.pinned")
[ -f "$stamp" ] && [ "$(cat "$stamp")" = "$want" ] && exit 0

api_flag=
case "$target" in
  host)
    case "$(uname -s)-$(uname -m)" in
      Darwin-arm64) config=darwin64-arm64-cc ;;
      Darwin-x86_64) config=darwin64-x86_64-cc ;;
      Linux-x86_64) config=linux-x86_64 ;;
      Linux-aarch64) config=linux-aarch64 ;;
      *) echo "Unsupported host $(uname -s)-$(uname -m)" >&2; exit 1 ;;
    esac ;;
  arm64-v8a) config=android-arm64 ;;
  armeabi-v7a) config=android-arm ;;
  x86_64) config=android-x86_64 ;;
  *) echo "Unknown target $target" >&2; exit 1 ;;
esac

if [ "$target" != host ]; then
  api_flag=-D__ANDROID_API__=26
  sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}
  ndk_version=$(awk '$1 == "ndk" { print $2 }' "$here/deps.lock")
  ANDROID_NDK_ROOT=${ANDROID_NDK_ROOT:-$sdk/ndk/$ndk_version}
  [ -d "$ANDROID_NDK_ROOT" ] || { echo "NDK $ndk_version not found at $ANDROID_NDK_ROOT" >&2; exit 1; }
  export ANDROID_NDK_ROOT
  host_tag=$(ls "$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt" | head -n 1)
  PATH="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/$host_tag/bin:$PATH"
  export PATH
fi

work="$deps/build/openssl-work/$target"
rm -rf "$work" "$prefix"
mkdir -p "$work"
jobs=$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)
printf 'Building OpenSSL for %s...\n' "$target"
# Only libssl and libcrypto: no shared libraries, command-line apps, loadable modules, engines or legacy ciphers.
# OPENSSLDIR is never read at runtime: trust roots come from the app (see README).
(
  cd "$work"
  perl "$source/Configure" "$config" $api_flag \
    no-shared no-tests no-docs no-apps no-module no-legacy no-engine no-comp no-ui-console \
    --prefix="$prefix" --libdir=lib --openssldir=/nonexistent/openssl >configure.log
  make -j"$jobs" build_libs >build.log
  make install_dev >install.log
)
printf '%s\n' "$want" >"$stamp"
rm -rf "$work"
printf 'OpenSSL %s ready in %s\n' "$target" "$prefix"
