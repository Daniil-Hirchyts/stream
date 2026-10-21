#!/bin/sh
# Generated media only: no third-party video or runtime downloads.
set -eu
dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)/.cache
mkdir -p "$dir/hls" "$dir/dash"
ffmpeg_bin=${DANIILCAST_FFMPEG:-ffmpeg}
if [ -x "$HOME/Applications/Daniil Cast.app/Contents/Resources/bin/ffmpeg" ] && [ -z "${DANIILCAST_FFMPEG:-}" ]; then
  ffmpeg_bin="$HOME/Applications/Daniil Cast.app/Contents/Resources/bin/ffmpeg"
fi
command -v "$ffmpeg_bin" >/dev/null 2>&1 || { echo 'FFmpeg is needed to generate the local playback fixtures.' >&2; exit 1; }
if [ ! -s "$dir/sample.mp4" ]; then
  printf 'Generating a 150-second local video with two audio tracks...\n'
  "$ffmpeg_bin" -hide_banner -loglevel error -y \
    -f lavfi -i 'testsrc2=size=640x360:rate=10:duration=150' \
    -f lavfi -i 'sine=frequency=440:sample_rate=48000:duration=150' \
    -f lavfi -i 'sine=frequency=880:sample_rate=48000:duration=150' \
    -map 0:v -map 1:a -map 2:a -c:v libx264 -preset veryfast -crf 29 -g 20 \
    -c:a aac -b:a 64k -filter:a volume=0.1 \
    -metadata:s:a:0 language=eng -metadata:s:a:0 title='English · low tone' \
    -metadata:s:a:1 language=fra -metadata:s:a:1 title='French · high tone' \
    -movflags +faststart "$dir/sample.tmp.mp4"
  mv "$dir/sample.tmp.mp4" "$dir/sample.mp4"
fi
if [ ! -s "$dir/hls/stream.m3u8" ]; then
  "$ffmpeg_bin" -hide_banner -loglevel error -y -i "$dir/sample.mp4" -map 0:v -map 0:a:0 \
    -c copy -hls_time 4 -hls_playlist_type vod -hls_segment_filename "$dir/hls/segment-%03d.ts" "$dir/hls/stream.m3u8"
fi
if [ ! -s "$dir/dash/stream.mpd" ]; then
  "$ffmpeg_bin" -hide_banner -loglevel error -y -i "$dir/sample.mp4" \
    -map 0:v -map 0:v -map 0:a:0 -map 0:a:1 \
    -c:v libx264 -preset veryfast -b:v:0 650k -b:v:1 180k -s:v:1 320x180 -g 20 \
    -c:a copy -seg_duration 4 -use_template 1 -use_timeline 1 \
    -adaptation_sets 'id=0,streams=v id=1,streams=2 id=2,streams=3' -f dash "$dir/dash/stream.mpd"
fi
if [ ! -s "$dir/av1.webm" ]; then
  "$ffmpeg_bin" -hide_banner -loglevel error -y -i "$dir/sample.mp4" -t 45 -map 0:v -map 0:a:0 \
    -vf scale=320:180 -c:v libsvtav1 -preset 12 -crf 45 -c:a libopus "$dir/av1.tmp.webm"
  mv "$dir/av1.tmp.webm" "$dir/av1.webm"
fi
printf 'Local media ready.\n'
