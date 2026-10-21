# Shared playback library

`tv.daniil.playback` is the Android player shared by Daniil Cast
(`casting/receiver`) and the planned standalone Daniil TV app. It holds Media3
playback, subtitle handling, track selection, the playback overlay, the options
menu, remote and pointer controls, and their resources. Casting transport,
pairing, conversion and catalogue sources stay in the apps.

There is no separate Gradle build. Each app includes this directory as its
`:playback` project and writes the library's build output under its own
`build/playback`, so two apps never share outputs.

## Host interface

- `PlaybackRequest`: one media item: title, address or a source-specific
  `DataSource.Factory`, request headers, subtitle files, track preferences and
  resume position.
- `PlaybackSession`: owns the `ExoPlayer` and the system media session; loads
  requests, plays, pauses, seeks, changes tracks and speed, stops and releases.
  Remote seek presses are gathered and committed once they pause, and a timeline
  drag seeks on release, so a stream fetches only the final position.
- `PlaybackSnapshot`: an immutable read of readiness, first frame, position,
  duration, buffering, tracks, qualities and errors.
- `PlaybackHost`: what the app decides: exit, next and previous episode,
  retry, source choice, progress, formats without a decoder, and extra menu rows.
- `PlayerScreen`: binds the shared layouts to a session.

An activity lays out its player with the shared layers in this order, adding
its own layers between them:

```xml
<FrameLayout ...>
    <include layout="@layout/playback_video" />
    <!-- App layers under the player UI, such as an idle screen. -->
    <include layout="@layout/playback_layers" />
    <!-- App layers over the player UI, such as a pairing code. -->
    <include layout="@layout/playback_menu" />
</FrameLayout>
```

Apps may override the library's string resources; Daniil Cast does so for
error messages that mention Chrome.

## Regressions

The subtitle compatibility path (`SubtitlePlayback`, `PausedSubtitleCues`) must
keep passing Daniil Cast's instrumentation suite before it changes: long VTT,
SRT and ASS cues after forward and backward seeks, a resumed load and a late
selection. Run it with `./casting/simulator.sh test`.
