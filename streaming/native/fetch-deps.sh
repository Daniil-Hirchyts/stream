#!/bin/sh
# Downloads the pinned native sources listed in deps.lock into .deps/, verifying each checksum or commit.
# Safe to run repeatedly: verified sources are kept, and anything that doesn't match is fetched again.
set -eu
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
deps=${DANIILTV_DEPS_DIR:-$here/.deps}
mkdir -p "$deps/downloads"

sha256() { shasum -a 256 "$1" | cut -d ' ' -f 1; }

fetch_tar() { # name pin url [paths]
  archive="$deps/downloads/$(basename "$3")"
  if [ ! -f "$archive" ] || [ "$(sha256 "$archive")" != "$2" ]; then
    printf 'Downloading %s...\n' "$1"
    curl -fsSL --retry 3 --proto '=https' -o "$archive.part" "$3"
    mv "$archive.part" "$archive"
  fi
  actual=$(sha256 "$archive")
  [ "$actual" = "$2" ] || { printf '%s: SHA-256 %s, expected %s\n' "$1" "$actual" "$2" >&2; return 1; }
  dest="$deps/$1"
  [ -f "$dest/.pinned" ] && [ "$(cat "$dest/.pinned")" = "$2" ] && return 0
  rm -rf "$dest" "$dest.part"
  mkdir -p "$dest.part"
  top=$(tar -tzf "$archive" | head -n 1 | cut -d / -f 1)
  members=
  if [ -n "${4:-}" ]; then
    for path in $(printf '%s' "$4" | tr ',' ' '); do members="$members $top/$path"; done
  fi
  # shellcheck disable=SC2086 # Each member is its own argument.
  tar -xzf "$archive" -C "$dest.part" --strip-components 1 $members
  printf '%s\n' "$2" >"$dest.part/.pinned"
  mv "$dest.part" "$dest"
}

fetch_git() { # name commit url [submodules]
  dest="$deps/$1"
  # Local changes in patches/<name>/ are applied in order; the marker covers them, so editing one re-applies all.
  patches=$(ls "$here/patches/$1/"*.patch 2>/dev/null || true)
  sum=$( (for patch in $patches; do cat "$patch"; done) | shasum -a 256 | cut -d ' ' -f 1)
  want="$2 $sum"
  [ -f "$dest/.pinned" ] && [ "$(cat "$dest/.pinned")" = "$want" ] && return 0
  if [ -d "$dest/.git" ] && [ "$(git -C "$dest" rev-parse HEAD 2>/dev/null)" = "$2" ]; then
    printf 'Resetting %s to %s...\n' "$1" "$2"
    git -C "$dest" reset -q --hard "$2"
    git -C "$dest" clean -qfd
  else
    printf 'Fetching %s at %s...\n' "$1" "$2"
    rm -rf "$dest"
    git init -q "$dest"
    git -C "$dest" fetch -q --depth 1 "$3" "$2"
    git -C "$dest" -c advice.detachedHead=false checkout -q FETCH_HEAD
  fi
  [ "$(git -C "$dest" rev-parse HEAD)" = "$2" ] || { printf '%s: not at %s\n' "$1" "$2" >&2; return 1; }
  if [ -n "${4:-}" ]; then
    # Submodules come from the commits this revision records, which the checkout above pins.
    for path in $(printf '%s' "$4" | tr ',' ' '); do
      git -C "$dest" submodule update -q --init --depth 1 -- "$path"
    done
  fi
  for patch in $patches; do
    printf 'Applying %s\n' "$(basename "$patch")"
    git -C "$dest" apply --whitespace=nowarn "$patch"
  done
  printf '%s\n' "$want" >"$dest/.pinned"
}

grep -v '^#' "$here/deps.lock" | while read -r name version kind pin url extra; do
  [ -n "$name" ] || continue
  case "$kind" in
    tar) fetch_tar "$name" "$pin" "$url" "${extra:-}" ;;
    git) fetch_git "$name" "$pin" "$url" "${extra:-}" ;;
    sdk) ;;
    *) printf 'Unknown kind %s for %s %s\n' "$kind" "$name" "$version" >&2; exit 1 ;;
  esac
done
printf 'Native sources ready in %s.\n' "$deps"
