# Stream documentation

**Using Stream**

- [Using Stream](features.md): screens, add-ons, playback, imports, privacy and settings
- [How Stream works](how-it-works.md): from a catalogue to the screen, torrent streaming, decoding, what stays on the TV

**Inside the app**

- [The Android app](app.md): behaviour in detail, add-ons, local data, device budgets, known gaps
- [Interface design](design.md): layout, focus, motion, typography, sound and player rules
- [Playback preparation and recovery](playback.md): source discovery, startup, retries and the player library
- [Decoders](decoders.md): formats, software decoding, distribution checks
- [Torrent add-on listings](addons.md): the public torrent add-ons the store knows

**The streaming engine**

- [The streaming engine](streaming-engine.md): BitTorrent implementation, compatibility, limitations, port mapping
- [Soak investigation](soak-investigation.md): the historical seek-159 timeout and what a failing soak records

**Building and testing**

- [Building and running](development.md): requirements, emulator, release builds, generated assets
- [Testing](testing.md): suites, focused regressions, device rules, measurements
- [Codec build](codec-build.md): the pinned FFmpeg/NextLib decoder build
- [Sounds](sounds.md) and [fonts and icons](fonts-and-icons.md): generated assets
- [Third-party software](third-party.md): the engine's crates and licenses
