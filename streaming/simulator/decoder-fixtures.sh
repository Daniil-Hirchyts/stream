#!/bin/sh
# Small, synthetic codec samples only; generated media remain in the ignored simulator cache.
set -eu
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
media="$here/.cache/media/codecs"
mkdir -p "$media"
ffmpeg_bin=${DANIILTV_FFMPEG:-}
if [ -z "$ffmpeg_bin" ]; then
  bundled="$HOME/Applications/Daniil Cast.app/Contents/Resources/bin/ffmpeg"
  if [ -x "$bundled" ]; then ffmpeg_bin=$bundled; else ffmpeg_bin=$(command -v ffmpeg || true); fi
fi
[ -n "$ffmpeg_bin" ] || { echo 'FFmpeg is required for decoder fixtures.' >&2; exit 1; }
ff() { "$ffmpeg_bin" -hide_banner -loglevel error -y "$@"; }
base="$media/base.mkv"
if [ ! -s "$base" ]; then
  ff -f lavfi -i 'testsrc2=size=320x180:rate=24:duration=14' -c:v libx264 -preset ultrafast \
    -pix_fmt yuv420p -g 24 -an "$base.tmp.mkv"
  mv "$base.tmp.mkv" "$base"
fi
audio() { # name encoder channels [encoder options]
  name=$1; encoder=$2; channels=$3; shift 3
  [ ! -s "$media/$name.mkv" ] || return 0
  ff -i "$base" -f lavfi -i 'sine=frequency=440:sample_rate=48000:duration=14' \
    -map 0:v -map 1:a -c:v copy -c:a "$encoder" -ac "$channels" "$@" "$media/$name.tmp.mkv"
  mv "$media/$name.tmp.mkv" "$media/$name.mkv"
}
audio eac3 eac3 6 -b:a 384k
# The standard fixtures need just the regression from the report; the focused decoder command creates the matrix.
[ "${1:-all}" != eac3 ] || exit 0
audio ac3 ac3 6 -b:a 384k
audio dts dca 6 -strict -2 -b:a 768k
audio truehd truehd 6 -channel_layout '5.1(side)' -strict -2
audio aac aac 2 -b:a 128k
audio mp3 libmp3lame 2 -b:a 128k
audio flac flac 8
audio alac alac 2
audio opus libopus 6 -b:a 256k
audio vorbis vorbis 2 -strict -2
video() { # name encoder pixel-format [encoder options]
  name=$1; encoder=$2; pixels=$3; shift 3
  [ ! -s "$media/$name.mkv" ] || return 0
  ff -i "$base" -f lavfi -i 'sine=frequency=660:sample_rate=48000:duration=14' \
    -c:v "$encoder" -pix_fmt "$pixels" "$@" -g 24 -c:a aac -b:a 96k "$media/$name.tmp.mkv"
  mv "$media/$name.tmp.mkv" "$media/$name.mkv"
}
video h264-10 libx264 yuv420p10le -preset ultrafast
video hevc-10 libx265 yuv420p10le -preset ultrafast -x265-params log-level=error
video vp8 libvpx yuv420p -deadline realtime -cpu-used 8 -b:v 300k
video vp9-10 libvpx-vp9 yuv420p10le -deadline realtime -cpu-used 8 -b:v 300k
video av1-10 libsvtav1 yuv420p10le -preset 12 -crf 40 -svtav1-params 'lp=2:log-level=0'
printf 'Decoder samples ready in %s\n' "$media"
