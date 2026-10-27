#!/bin/sh
# Builds the engine, dtv-fixture and dtv-tests for this computer, then runs the tests unless --no-test is given.
#   build-host.sh [dev|asan|tsan|checks] [--no-test] [test name filters...]
# dev: debug build. asan: AddressSanitizer and UndefinedBehaviorSanitizer. tsan: ThreadSanitizer.
# checks: libtorrent's own asserts and full invariant checks, which validate the eviction patch's state changes.
# Its default run is the cache and eviction regressions: engine tests crawl under expensive checks, and a magnet
# torrent trips an upstream invariant (see README, "Known upstream issues").
set -eu
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
variant=${1:-dev}
[ $# -gt 0 ] && shift
run_tests=1
if [ "${1:-}" = --no-test ]; then run_tests=0; shift; fi

sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}
cmake_version=$(awk '$1 == "cmake" { print $2 }' "$here/deps.lock")
if [ -x "$sdk/cmake/$cmake_version/bin/cmake" ]; then
  cmake_bin="$sdk/cmake/$cmake_version/bin/cmake"
  ninja_bin="$sdk/cmake/$cmake_version/bin/ninja"
else
  cmake_bin=$(command -v cmake) || { echo "CMake $cmake_version is required." >&2; exit 1; }
  ninja_bin=$(command -v ninja) || { echo "Ninja is required." >&2; exit 1; }
fi

case "$variant" in
  dev) flags="-DCMAKE_BUILD_TYPE=Debug" ;;
  asan) flags="-DCMAKE_BUILD_TYPE=Debug -DDTV_SANITIZE=address,undefined" ;;
  tsan) flags="-DCMAKE_BUILD_TYPE=Debug -DDTV_SANITIZE=thread" ;;
  checks) flags="-DCMAKE_BUILD_TYPE=Debug -DDTV_LIBTORRENT_CHECKS=ON" ;;
  *) echo "Unknown variant $variant" >&2; exit 2 ;;
esac

"$here/fetch-deps.sh"
"$here/build-openssl.sh" host
build="$here/build/host-$variant"
# shellcheck disable=SC2086 # flags holds several arguments.
"$cmake_bin" -S "$here" -B "$build" -G Ninja -DCMAKE_MAKE_PROGRAM="$ninja_bin" -DDTV_HOST=ON \
  -DDTV_OPENSSL_ROOT="$here/.deps/build/openssl/host" $flags >/dev/null
"$cmake_bin" --build "$build" --target dtv-tests dtv-fixture
[ "$run_tests" = 1 ] || exit 0
if [ "$variant" = checks ] && [ $# -eq 0 ]; then set -- piece_cache_ eviction_; fi
# Sanitizer reports fail the run rather than scroll past.
ASAN_OPTIONS=${ASAN_OPTIONS:-detect_leaks=0:abort_on_error=1} \
UBSAN_OPTIONS=${UBSAN_OPTIONS:-print_stacktrace=1:halt_on_error=1} \
TSAN_OPTIONS=${TSAN_OPTIONS:-halt_on_error=1:second_deadlock_stack=1} \
  "$build/dtv-tests" "$@"
