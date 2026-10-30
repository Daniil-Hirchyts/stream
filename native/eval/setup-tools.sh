#!/bin/sh
# Downloads the optional independent clients interop.sh can use into a new directory, outside the repository:
#   native/eval/setup-tools.sh <directory>
#   STREAM_INTEROP_TOOLS=<directory> native/eval/interop.sh
# libtorrent (a pip wheel in a virtual environment), aria2 (conda-forge), WebTorrent and http-server (npm, without
# running install scripts), Transmission (Homebrew's bottles, unpacked; nothing is installed in Homebrew). Each is
# optional: what fails to download is skipped by interop.sh. These are test peers only and never part of the app.
set -u
tools=${1:?usage: setup-tools.sh <directory>}
mkdir -p "$tools"
tools=$(CDPATH= cd -- "$tools" && pwd)

python3 -m venv "$tools/lt-venv" && "$tools/lt-venv/bin/pip" install --quiet --only-binary=:all: libtorrent ||
  echo "libtorrent: not available" >&2
if command -v conda >/dev/null 2>&1; then
  CONDA_PKGS_DIRS="$tools/conda-pkgs" conda create -y -q -p "$tools/conda-env" -c conda-forge --override-channels aria2 >/dev/null ||
    echo "aria2: not available" >&2
else
  echo "aria2: needs conda" >&2
fi
mkdir -p "$tools/npm"
# WebTorrent loads a native WebRTC module even with WebRTC unused; only that module's script runs.
(cd "$tools/npm" && npm install --silent --no-audit --no-fund --ignore-scripts webtorrent@3.0.21 http-server@14.1.1 &&
  npm rebuild --silent node-datachannel) ||
  echo "webtorrent, http-server: not available" >&2
if command -v brew >/dev/null 2>&1; then
  mkdir -p "$tools/tr/x"
  for formula in transmission-cli libevent libpsl miniupnpc; do
    HOMEBREW_NO_AUTO_UPDATE=1 HOMEBREW_NO_ENV_HINTS=1 HOMEBREW_CACHE="$tools/tr/cache" brew fetch --force-bottle --quiet "$formula" >/dev/null 2>&1 ||
      echo "$formula: not available" >&2
  done
  for bottle in "$tools/tr/cache/downloads/"*.tar.gz; do
    tar xzf "$bottle" -C "$tools/tr/x"
  done
else
  echo "Transmission: needs Homebrew's bottles" >&2
fi
