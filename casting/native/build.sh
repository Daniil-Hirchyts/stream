#!/bin/sh
set -eu
cd -- "$(dirname -- "$0")"
if ! command -v cargo >/dev/null 2>&1 && [ -x "$HOME/.cargo/bin/cargo" ]; then
  PATH="$HOME/.cargo/bin:$PATH"
  export PATH
fi
cargo build --release --locked
tools_dir=${DANIILCAST_TOOLS_DIR:-}
if [ -z "$tools_dir" ]; then
  for candidate in /opt/homebrew/opt/ffmpeg@7/bin /usr/local/opt/ffmpeg@7/bin; do
    if [ -x "$candidate/ffmpeg" ] && [ -x "$candidate/ffprobe" ]; then
      tools_dir=$candidate
      break
    fi
  done
fi
if [ -n "$tools_dir" ]; then
  ./target/release/daniil-cast package --output build --tools "$tools_dir"
else
  ./target/release/daniil-cast package --output build
fi
if [ "${1:-}" = "--install" ]; then
  case "$(uname -s)" in
    Darwin) "./build/Daniil Cast.app/Contents/MacOS/daniil-cast-helper" install ;;
    *) ./build/DaniilCast/daniil-cast install ;;
  esac
fi
