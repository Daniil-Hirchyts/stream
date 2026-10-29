# Stream playback

This repository owns this Kotlin/XML Media3 library, included as `:playback` by
`android/settings.gradle`. PlaybackSession and PlayerScreen implement transport,
tracks, subtitle compatibility, overlays, menu, pointer and remote controls.
The app supplies resource overrides and its own codec policy. No external app
checkout is required, and changes here are validated by Stream's tests.

SubtitlePlayback retains complete external subtitle timelines through seeks and
late selections; PausedSubtitleCues keeps active cues visible while paused.
Do not remove these compatibility paths without the real playback regressions.
Keep captions above the entire toolbar, including the 48 dp timeline touch target.

Run focused player cases with `./simulator.sh data-test` and an explicit class or
method; see [the testing guide](../TESTING.md). Keep native decoder and player
failures distinct from source/network failures.
