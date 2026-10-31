#!/bin/sh
# Pulls ReadmeCapture's screenshots and screen recordings from the verified emulator and writes docs/media:
# PNG screenshots scaled to 1280 px and palette-optimised GIFs at 10 fps, kept small enough for a README (the player's
# moving film is narrower than the interface scenes). Needs ffmpeg.
set -eu
SIM_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(dirname "$SIM_DIR")
. "$SIM_DIR/emulator.sh"
command -v ffmpeg >/dev/null || { echo 'ffmpeg is required' >&2; exit 2; }
media="$ROOT/docs/media"
raw="$SIM_DIR/.cache/readme"
selection=${1:-all}
case "$selection" in all|player) ;; *) echo "Usage: $0 [all|player]" >&2; exit 2 ;; esac
mkdir -p "$media" "$raw"
for name in $(device shell run-as tv.stream.app ls files/readme | tr -d '\r'); do
  if [ "$selection" = player ]; then case "$name" in player-*.png) ;; *) continue ;; esac; fi
  device exec-out run-as tv.stream.app cat "files/readme/$name" >"$raw/$name"
  ffmpeg -loglevel error -y -i "$raw/$name" -vf scale=1280:-1:flags=lanczos "$media/$name"
done
scenes="home:720 search:720 player:640"
[ "$selection" != player ] || scenes="player:640"
for scene in $scenes; do
  width=${scene#*:}; scene=${scene%:*}
  device pull "/data/local/tmp/readme-$scene.mp4" "$raw/$scene.mp4" >/dev/null
  device shell rm -f "/data/local/tmp/readme-$scene.mp4"
  ffmpeg -loglevel error -y -i "$raw/$scene.mp4" -vf \
    "fps=10,scale=$width:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=128:stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle" \
    "$media/$scene.gif"
done
ls -l "$media"
