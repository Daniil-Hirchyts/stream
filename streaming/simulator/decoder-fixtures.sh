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
if [ ! -s "$media/eac3.ec3" ]; then
  ff -i "$media/eac3.mkv" -map 0:a:0 -c:a copy -f eac3 "$media/eac3.tmp.ec3"
  mv "$media/eac3.tmp.ec3" "$media/eac3.ec3"
fi
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
legacy() { # name encoder AVI FourCC
  name=$1; encoder=$2; tag=$3
  [ ! -s "$media/$name.avi" ] || return 0
  ff -i "$base" -f lavfi -i 'sine=frequency=550:sample_rate=48000:duration=14' \
    -c:v "$encoder" -q:v 4 -vtag "$tag" -c:a libmp3lame -b:a 128k "$media/$name.tmp.avi"
  mv "$media/$name.tmp.avi" "$media/$name.avi"
}
legacy xvid mpeg4 XVID
legacy mp42 msmpeg4v2 MP42
legacy mp43 msmpeg4 MP43
legacy mjpeg mjpeg MJPG
if [ ! -s "$media/sdr-1080.mkv" ]; then
  ff -f lavfi -i 'testsrc2=size=1920x1080:rate=24:duration=14' -f lavfi -i 'sine=frequency=660:sample_rate=48000:duration=14' \
    -c:v libx264 -preset veryfast -crf 24 -g 24 -bf 3 -pix_fmt yuv420p -c:a aac -b:a 128k "$media/sdr-1080.tmp.mkv"
  mv "$media/sdr-1080.tmp.mkv" "$media/sdr-1080.mkv"
fi
printf 'Decoder samples ready in %s\n' "$media"
