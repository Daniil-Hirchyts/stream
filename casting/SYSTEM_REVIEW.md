# System review and physical Fire TV verification — 21 October 2026

The installed Rust helper 0.4.0 successfully cast generated video to the owner's
physical Fire TV Stick AFTSS, running Fire OS 7.7.1.7 / Android 9 with receiver
0.3.0. The owner explicitly confirmed hearing the spoken test message from the
TV speakers. This is physical playback evidence, beyond the earlier emulator
and mock-server tests.

## Physical results

Fixtures were generated locally: a 130-second moving test pattern, H.264 MP4
with two stereo AAC tracks, a corresponding HLS VOD, an AV1 MP4 and English/French
WebVTT subtitles. Chrome's page video was muted to distinguish TV sound. Existing
pairing was reused. No commercial stream or account credentials were needed.

| Check | Observed result | Evidence in `../backups/2026-10-21-rust-firetv/` |
|---|---|---|
| H.264 MP4 through Chrome | Rendered 640×360 H.264, stereo AAC; owner heard speech | `h264-playing.json`, `h264-playing.png` |
| Pause/resume | Chrome remote paused the physical player, then resumed it | `paused.json`, `paused.png`, `controls-playing.json` |
| Timeline / skip | Keyboard timeline seek and Forward 10 seconds changed TV position | Chrome remote observations; paused position 1s → 11s |
| Audio track | English → French changed selected audio from stream 2 to stream 3 | `tracks-french.json`, `controls-playing.json` |
| Speed | Chrome remote selected 1.25×; TV reported 1.25 | `controls-playing.json` |
| Subtitle selection | English and French were selectable; normal five-second cues rendered after seeking | `paused.png`, `converted-french-after-seek.png` |
| AV1 fallback | TV has no AV1 decoder; helper automatically converted to H.264/stereo AAC | `av1-initial.json`, `av1-playing.png` |
| Converted seek | Seeking to 85s crossed the converter's 80s encoding-window boundary and resumed | `av1-seek-resumed.json`, `optimized-seek.json` |
| Updated conversion | 320×180 AV1 played as 320×180 H.264 after removing upscaling | `av1-no-upscale.json`, `av1-no-upscale.png` |
| Automatic browser pause | After AV1 cast succeeded, source video was paused at 20.895s; updated helper retest paused at 14.793s | Read directly from the test page's video element |
| Source tab closed | Converted playback continued to 109s with its converter active | `source-tab-closed.json` |
| Receiver closed / Home | API stayed online with phase `closed`; a new HLS cast reopened the player and rendered video | `background-home.json`, `hls-playing.json` |
| Stop / cleanup | TV idle; conversion inactive with no converter error | `final-stopped.json` |

The speaker confirmation applies to the original spoken audio test. Audio-track
switching was checked through selected stream metadata; independent listening
confirmation of the second track was not requested. Test media and converter
were stopped; English audio/subtitle choices were restored, and ADB stayed enabled.

The TV's service had stopped before the earlier connection check and was
restarted by opening Daniil Cast once. The Home-screen test above passed; it does
not establish long-term service survival or explain that earlier stop. Reboots,
standby, overnight operation, other TV models, every website, DRM and modern
Android background restrictions were not verified here.

## Changes made during this review

1. **Avoid unnecessary upscaling in the converter.** The original filter enlarged
   a 320×180 source to 1280×720, processing sixteen times as many output pixels.
   The filter now caps dimensions without enlarging small sources. FFmpeg's real
   integration test asserts a 160×90 AV1 fixture remains 160×90 after conversion;
   the installed app was rebuilt and verified on the TV at 320×180. This reduces
   unnecessary image processing; it is not a measured claim of sixteen-times
   faster encoding. See `native/src/relay.rs` and `native/tests/conversion.rs`.
2. **Pause the correct source tab after a slow cast.** `state.ts` previously read
   the active page's tab ID after awaiting conversion. Switching tabs while
   waiting could pause the newly selected tab and leave the source playing.
   Capture the source tab before starting. Two controller tests check the race
   and preserve browser playback for pending/manual casts. The extension bundle
   was rebuilt; Chrome loads that change when its panel next opens.

Validation after the changes: Rust formatting and Clippy with warnings denied;
all ten release tests including actual FFmpeg conversion, seek, authentication
and cleanup; two side-panel controller tests; TypeScript checking and production
bundle; Android debug build; installed macOS app signature verification. All
passed. The receiver build was unchanged and was not reinstalled.

## Language choices

| Part | Recommendation | Reason |
|---|---|---|
| Desktop native host, installer, discovery, converter server | Keep Rust | One compiled application, bounded concurrency, predictable deployment. Other users need the packaged application, not Rust or Python. |
| Chrome page detection and extension service worker | Keep JavaScript/TypeScript; gradually type the remaining `background.js` | Browser APIs and page data dominate this work. A Rust/Wasm port adds a bridge without evidence of a CPU bottleneck. Chrome's service worker is a JavaScript entry point. [Chrome documentation](https://developer.chrome.com/docs/extensions/develop/concepts/service-workers/basics) |
| React side panel | Keep TypeScript/React | Small interface, already typed. The tab-switch bug was an asynchronous ownership error; changing languages would not remove the need to capture the correct source. |
| Fire TV receiver | Keep Java/Media3; Kotlin is an optional incremental maintenance choice | Media3 already supplies Android playback, track selection and hardware decoder integration. Its supported usage includes Java and Kotlin. A Rust UI/player rewrite adds Android bindings and does not demonstrate faster hardware decoding. [Media3 guide](https://developer.android.com/media/media3/exoplayer/hello-world) |
| FFmpeg | Keep the established engine | The Rust helper launches FFmpeg; FFmpeg performs decoding/filtering/encoding. Optimize conversion settings and hardware use where measurements justify it. |
| Root `scripts/` and submodule `cleanyourtv/tools/` | Keep Python for developer maintenance | These are ADB inspection/cleanup/recovery tools, outside the distributed casting runtime. Their work waits on device commands; no measured CPU bottleneck warrants a rewrite. Do not ship these as an extension runtime dependency. |

The extension, desktop helper and TV receiver remain separate installations.
An extension alone cannot provide native local-network conversion. End users
also do not need Node, a JDK or a Rust compiler to run packaged releases.

## Remaining findings and priorities

1. **Subtitle issue resolved locally in receiver 0.3.1.** The original 1.4.1
   receiver lost a single 0–130s WebVTT cue after seeking or late selection.
   This was reproduced on the Android 9 emulator, then fixed with Media3 1.11.1,
   full-timeline decoding for downloaded files and cue lookup for paused frames.
   Three real playback tests now pass for WebVTT, SRT and ASS, including seeks,
   mid-cue resume, off/on and track switching, and expiry. Details and the
   compatibility API maintenance limit are in [receiver/TESTING.md](receiver/TESTING.md).
   This follow-up used only the local emulator; the physical TV remains on its
   previous receiver. Evidence is in `../backups/2026-10-21-simulator/`.
2. **Package and test supported desktop platforms.** The current bundled
   Apple Silicon FFmpeg requires macOS 27. Windows/Linux CI jobs exist but were
   not executed here. Define the supported OS/CPU matrix, bundle compatible
   FFmpeg builds, and finish release signing/notarization and installers. This
   is the main distribution work; another language change does not solve it.
3. **Make the protocol a tested contract.** Messages are represented separately
   in TypeScript, Rust and Java. Add shared schemas/fixtures and receiver tests
   for load, track selection, stale responses and errors. Extend the new
   controller tests into CI. Type the service worker incrementally.
4. **Modernize Android compatibility deliberately.** The receiver compiles
   against SDK 36 after the subtitle update, but still targets 28. Test newer TV OS releases and lifecycle
   behavior while planning the target SDK/Media3 upgrade. Apps targeting Android
   14+ must declare the appropriate foreground-service type and permissions;
   raising the target requires this work. This is not a claim that the current
   target-28 app already violates that target-dependent rule.
   [Android service-type documentation](https://developer.android.com/develop/background-work/services/fgs/service-types)
5. **Bound server overload without occupying the accept thread.** Static review
   of `CastServer.java` found eight workers with `CallerRunsPolicy`; the next
   slow client can run on the accept thread and delay new connections up to the
   15s socket timeout. Replace that rejection policy with explicit admission
   handling and socket cleanup, then test slow-client overload. This was not
   stress-tested against the owner's TV. The server also closes every HTTP
   connection, so Rust's client pooling alone cannot eliminate TV handshakes.

Browser automation ended after closing the generated test tab switched Chrome
to a page restricted by the browser tool. No further browser actions were taken;
remaining verification and cleanup used the installed helper and ADB.
