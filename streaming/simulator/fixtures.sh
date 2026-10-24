#!/bin/sh
# Generates Daniil TV's local test media and torrents in .cache/: no third-party content, nothing downloaded.
# Media come from FFmpeg test sources; torrents from dtv-fixture, built from streaming/native.
set -eu
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cache="$here/.cache"
media="$cache/media"
torrents="$cache/torrents"
mkdir -p "$media" "$torrents"

ffmpeg_bin=${DANIILTV_FFMPEG:-}
if [ -z "$ffmpeg_bin" ]; then
  bundled="$HOME/Applications/Daniil Cast.app/Contents/Resources/bin/ffmpeg"
  if [ -x "$bundled" ]; then ffmpeg_bin=$bundled; else ffmpeg_bin=$(command -v ffmpeg || true); fi
fi
[ -n "$ffmpeg_bin" ] || { echo 'FFmpeg is needed to generate the test media (set DANIILTV_FFMPEG).' >&2; exit 1; }
ff() { "$ffmpeg_bin" -hide_banner -loglevel error -y "$@"; }

if ! "$here/../native/build-host.sh" dev --no-test >"$cache/native-build.log" 2>&1; then
  cat "$cache/native-build.log" >&2
  exit 1
fi
fixture="$here/../native/build/host-dev/dtv-fixture"

# A subtitle file both the MKV and the series embed.
subs="$cache/subtitles.srt"
if [ ! -s "$subs" ]; then
  : >"$subs"
  i=1
  while [ "$i" -le 20 ]; do
    start=$(( (i - 1) * 3 ))
    printf '%d\n00:00:%02d,000 --> 00:00:%02d,500\nSubtitle line %d\n\n' "$i" "$start" "$((start + 2))" "$i" >>"$subs"
    i=$((i + 1))
  done
fi

# The film: 1080p H.264 at a constant 12 Mbit/s with filler, so 45 s are ~67 MB, far beyond a 16 MiB test cache.
film="$media/Daniil Test Film (2026).mp4"
if [ ! -s "$film" ]; then
  printf 'Generating the 1080p test film...\n'
  ff -f lavfi -i 'testsrc2=size=1920x1080:rate=30:duration=45' \
    -f lavfi -i 'sine=frequency=330:sample_rate=48000:duration=45' \
    -c:v libx264 -preset veryfast -pix_fmt yuv420p -g 60 -b:v 12M -minrate 12M -maxrate 12M -bufsize 6M \
    -x264-params nal-hrd=cbr:force-cfr=1 -c:a aac -b:a 128k -movflags +faststart "$film.tmp.mp4"
  mv "$film.tmp.mp4" "$film"
fi

# An MP4 whose index (moov) follows the media data, so the player must read the end of the file first.
late="$media/Late Index (2026).mp4"
if [ ! -s "$late" ]; then
  ff -f lavfi -i 'testsrc2=size=1280x720:rate=30:duration=20' -f lavfi -i 'sine=frequency=440:duration=20' \
    -c:v libx264 -preset veryfast -pix_fmt yuv420p -g 60 -b:v 4M -c:a aac -b:a 96k "$late.tmp.mp4"
  mv "$late.tmp.mp4" "$late"
fi

# Matroska with two audio languages and an embedded subtitle track.
mkv="$media/Matroska Film (2026).mkv"
if [ ! -s "$mkv" ]; then
  ff -f lavfi -i 'testsrc2=size=1280x720:rate=25:duration=30' \
    -f lavfi -i 'sine=frequency=220:duration=30' -f lavfi -i 'sine=frequency=880:duration=30' -i "$subs" \
    -map 0:v -map 1:a -map 2:a -map 3:s -c:v libx264 -preset veryfast -pix_fmt yuv420p -g 50 -b:v 3M \
    -c:a aac -b:a 96k -c:s srt \
    -metadata:s:a:0 language=eng -metadata:s:a:0 title=English -metadata:s:a:1 language=rus \
    -metadata:s:a:1 title=Russian -metadata:s:s:0 language=eng "$mkv.tmp.mkv"
  mv "$mkv.tmp.mkv" "$mkv"
fi

# A season of three short episodes plus files that aren't video.
series="$media/Test Series"
if [ ! -s "$series/Season 1/Test Series S01E03.mkv" ]; then
  mkdir -p "$series/Season 1"
  for e in 1 2 3; do
    ff -f lavfi -i "testsrc2=size=854x480:rate=25:duration=12" -f lavfi -i "sine=frequency=$((300 + e * 100)):duration=12" \
      -i "$subs" -map 0:v -map 1:a -map 2:s -c:v libx264 -preset veryfast -pix_fmt yuv420p -g 50 -b:v 1500k \
      -c:a aac -b:a 64k -c:s srt "$series/Season 1/Test Series S01E0$e.mkv"
  done
  printf 'Generated for Daniil TV tests.\n' >"$series/notes.txt"
  ff -f lavfi -i 'testsrc2=size=600x900:duration=1' -frames:v 1 "$series/poster.jpg"
fi

# A name outside ASCII, emoji included.
unicode_dir="$media/unicode"
unicode="$unicode_dir/Сериал — эпизод 🎬.mp4"
if [ ! -s "$unicode" ]; then
  mkdir -p "$unicode_dir"
  ff -f lavfi -i 'testsrc2=size=640x360:rate=25:duration=6' -c:v libx264 -preset veryfast -pix_fmt yuv420p \
    -movflags +faststart "$unicode.tmp.mp4"
  mv "$unicode.tmp.mp4" "$unicode"
fi

make() { # name source piece-bytes version
  [ -s "$torrents/$1.torrent" ] && return 0
  "$fixture" create "$torrents/$1.torrent" "$2" "$3" "$4" >/dev/null
}
make film-v1 "$film" 1048576 v1
make film-hybrid "$film" 1048576 hybrid
make film-v2 "$film" 524288 v2
make film-huge-pieces "$film" 33554432 v1
make late-index "$late" 262144 v1
make matroska "$mkv" 524288 hybrid
make series "$series" 262144 hybrid
make unicode "$unicode" 65536 v1
DANIILTV_FFMPEG="$ffmpeg_bin" sh "$here/decoder-fixtures.sh" eac3
make codec-eac3 "$media/codecs/eac3.mkv" 65536 v1

# Independent direct HTTP adaptive fixtures, generated from our own local film (no desktop helper required).
mkdir -p "$media/hls" "$media/dash"
if [ ! -s "$media/hls/stream.m3u8" ]; then
  ff -i "$late" -map 0:v -map 0:a -c copy -hls_time 4 -hls_playlist_type vod \
    -hls_segment_filename "$media/hls/segment-%03d.ts" "$media/hls/stream.m3u8"
fi
if [ ! -s "$media/dash/stream.mpd" ]; then
  ff -i "$late" -map 0:v -map 0:v -map 0:a -c:v libx264 -preset veryfast -b:v:0 3M -b:v:1 600k -s:v:1 640x360 -g 60 \
    -c:a copy -seg_duration 4 -use_template 1 -use_timeline 1 -adaptation_sets 'id=0,streams=v id=1,streams=a' -f dash "$media/dash/stream.mpd"
fi
printf 'Fixtures ready in %s.\n' "$cache"
