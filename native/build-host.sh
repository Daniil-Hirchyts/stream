#!/bin/sh
# Builds stream-fixture for this computer, then runs the engine's tests unless --no-test is given.
#   build-host.sh [dev|release] [--no-test] [test name filters...]
# dev: debug build. release: optimized, as the app ships, which also runs the timing-sensitive tests at full speed.
set -eu
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
variant=${1:-dev}
[ $# -gt 0 ] && shift
run_tests=1
if [ "${1:-}" = --no-test ]; then run_tests=0; shift; fi

case "$variant" in
  dev) flags=; out=debug ;;
  release) flags=--release; out=release ;;
  *) echo "Unknown variant $variant" >&2; exit 2 ;;
esac
cargo=${CARGO:-$(command -v cargo || echo "$HOME/.cargo/bin/cargo")}
[ -x "$cargo" ] || { echo "Rust's cargo is required (https://rustup.rs); native/core pins the toolchain." >&2; exit 1; }

cd "$here/core"
# shellcheck disable=SC2086 # flags is empty or one argument.
"$cargo" build --locked $flags --bin stream-fixture
build="$here/build/host-$variant"
mkdir -p "$build"
cp "target/$out/stream-fixture" "$build/stream-fixture"
[ "$run_tests" = 1 ] || exit 0
# shellcheck disable=SC2086
"$cargo" test --locked $flags -- "$@"
