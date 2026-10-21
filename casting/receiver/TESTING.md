# Emulator-only receiver regression tests

Build requirements: Java 17–21, Android SDK 36 and platform-tools. Gradle 8.11.1
and Android Gradle Plugin 8.10.1 are pinned; SDK 36 is needed by Media3 1.11.1.
The receiver still has Android 8 / API 26 as its minimum and targets API 36.

Use `./casting/simulator.sh up` from the repository root for the complete local
environment, then `./casting/simulator.sh test`. The default is the current
`DaniilCast_Local_TV` Android TV 16 AVD on `emulator-5556`. The older
`DaniilCast_TV` Android 9 AVD remains available for Fire OS compatibility checks.
Cold boot avoids stale quick-boot state. No physical TV, Mac helper or LAN
discovery is used by the instrumentation tests. The test HTTP fixture binds
to 127.0.0.1 **inside the emulator**.

```sh
"$HOME/Library/Android/sdk/emulator/emulator" -avd DaniilCast_Local_TV -port 5556 \
  -no-snapshot -gpu swiftshader -feature -Vulkan
```

In another terminal, once the emulator has booted:

```sh
cd casting/receiver
./test-emulator.sh emulator-5556
```

The script refuses serials without the `emulator-` prefix, confirms
`ro.kernel.qemu=1`, explicitly targets that serial for every ADB operation,
builds/installs both debug APKs and fails if instrumentation does not report
success. It never runs `connectedAndroidTest`, which could select other attached
devices. `ADB` can override the executable path.

Three instrumentation tests each exercise WebVTT, SRT and ASS:

- A cue spanning 0–130 seconds survives paused and playing forward/backward seeks and is
  absent at 131 seconds.
- Loading at 85 seconds displays the already active cue.
- Starting with subtitles off, enabling a track, switching languages, switching
  off and selecting the first track again displays the right text.

Two more tests distinguish a missing certificate trust anchor from a genuine
network/DNS failure. Three pointer tests inject actual mouse and touchscreen MotionEvents into the
app: settings/submenus/choices/back/close, playback/skip/timeline drag, speed and
subtitle selection, up-next play/cancel, error retry/close, and remote shortcuts
after pointer use. They also check that captions stay above the timeline’s touch
target and that the center button has symmetric padding. They do not call `performClick()` as a substitute for input.
The complete run must report `OK (8 tests)`.

`./casting/simulator.sh smoke` exercises the real installed Rust helper and the
real receiver using generated MP4/HLS/DASH/AV1 media. It checks audio switching,
four subtitle tracks, pause/resume, seeking, speed, subtitle style, DASH quality,
episode selection, AV1 conversion and bringing the receiver forward from Home.
Its helper store contains only the emulator's pairing, with separate conversion
cache and logs. Evidence is written to `casting/simulator/.cache/smoke/evidence/`.

Assertions inspect the actual `SubtitleView` cue list on the UI thread, rather
than treating a selected track as proof of visible captions. Screenshots are
saved under the receiver's external files directory in `subtitle-regression/`.
The generated 150-second H.264 test pattern in `androidTest/assets/` contains
no third-party content and is only 82 KiB. To regenerate it:

```sh
ffmpeg -f lavfi -i 'testsrc2=size=160x90:rate=1:duration=150' \
  -c:v libx264 -preset veryslow -crf 38 -pix_fmt yuv420p \
  -movflags +faststart -an app/src/androidTest/assets/subtitle-video.mp4
```

Downloaded subtitle files use Media3's full-timeline compatibility path through
`SubtitlePlayback` in the shared library (`android/playback/`), so this suite
also guards the standalone app's subtitles; embedded subtitles retain the
current extraction pipeline.
`PausedSubtitleCues` uses Media3's parsers off the UI thread to resolve the exact
cue for a paused frame, since the legacy renderer waits for playback to resume.
Some compatibility APIs are deprecated by Media3. Keep this test suite mandatory
for dependency upgrades and removal of the compatibility path.

The issue was reproduced with Media3 1.4.1 and fixed locally with 1.11.1 plus
these receiver changes. Evidence is in `backups/2026-10-21-simulator/` at the
repository root. No new receiver was installed on the physical Fire TV.
