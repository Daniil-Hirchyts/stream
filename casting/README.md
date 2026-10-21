# Daniil Cast

A Chrome side panel, a small helper on the Mac and a TV player for Fire TV. The
extension finds the video on a page and sends its **original address**, with its
episodes, voice-overs and subtitles, to the TV. The TV fetches and decodes the
video itself. Videos in a format the TV can't decode, such as AV1, are converted
to H.264 on the Mac and streamed to the TV over the home network.

Version 0.3.0 replaces ADB with a paired connection over Wi-Fi. Open Daniil Cast
on the TV, type the code it shows into the side panel, and the Mac and the TV
talk directly. ADB is needed only to install the TV app. There is no screen
capture, cloud service or subscription.

## Why Chrome says “Available for specific video sites”

The stick advertises Amazon's app-specific receiver. This does not make it a
general Google Cast receiver. Changing a Chrome preference or re-enabling a
package cannot add the missing protocol. A Google Cast custom receiver also runs
on a Cast-capable device; it is not an arbitrary Android APK.

This implementation adds a **Daniil Cast extension button**. It does not replace
Chrome's built-in Cast dialog or claim Google Cast device certification.

Primary references:

- [Google Cast architecture](https://developers.google.com/cast/docs/overview)
- [Chromium Media Router](https://www.chromium.org/developers/design-documents/media-router/)
- [Chrome native messaging](https://developer.chrome.com/docs/extensions/develop/concepts/native-messaging)
- [Media3 format support](https://developer.android.com/media/media3/exoplayer/supported-formats)

## Components

- `extension/`: Manifest V3 extension. The toolbar button opens a **side panel**
  with four tabs: **Cast** (what this tab plays), **Remote** (what the TV plays),
  **TV** (paired TVs) and **Developer** (versions, network details, tools and logs).
  `background.js` finds videos, subtitles and player configs on pages.
- `sidepanel/`: the panel's React and TypeScript source. `npm run build` bundles
  it into `extension/sidepanel/`; the built files are committed, so Chrome can
  load the extension folder as it is.
- `receiver/`: the TV app, package `tv.daniil.cast`. A Media3 player with MP4,
  HLS and DASH, a mouse/touch overlay with remote shortcuts and an options menu, plus a background
  service that listens for paired Macs (`CastService`). The service starts with
  the TV, so a cast opens the app by itself. The player, subtitles, menus and
  overlay come from the shared library in
  [`android/playback/`](../android/playback/README.md); the receiver adds
  pairing, Chrome's commands and status, episodes and conversion waits.
- `native/`: one compiled Rust helper for Chrome messages, TV discovery/pairing,
  controls, activity logs, conversion and installation. The same executable
  supplies Terminal commands. See [native/README.md](native/README.md).
  Users install a compiled app; Python, Rust and this checkout are not required.

## How the Mac and the TV connect

The TV app's service answers on port **8765**: HTTP for commands and UDP for
discovery, on the local network only.

1. **Discovery.** The helper broadcasts a short probe; Daniil Cast TVs answer
   with their name and a random identity. Where a router filters broadcasts,
   the helper asks the addresses of its own /24 network directly, and a TV's
   address can also be typed in.
2. **Pairing.** Until a Mac is paired, the TV's idle screen shows a six-digit
   code; afterwards a Mac can ask the TV to show one (**Add a TV**, or **Menu →
   Pair a Mac** on the TV). The code is valid for ten minutes. Five wrong guesses
   retire it and pause pairing for a minute, and each wrong guess is slowed down.
   A matching code returns a random 256-bit token.
3. **Commands.** Every later request carries that token. The TV keeps only a hash
   of it; the Mac keeps it in
   `~/Library/Application Support/DaniilCast/devices.json`, readable by you only.
   **Remove** in the TV tab, or **Menu → Paired Macs** on the TV, revokes it.

Traffic stays on the home network and is not encrypted, as with ADB before. A
TV that changes address is found again by its identity.

## Build and install

Requirements: Java 17–21, Android SDK 36 with platform-tools, Node.js for the
side panel, and Rust 1.85+ for building the desktop helper. End users need only
the compiled helper and extension.

```sh
# TV app
cd casting/receiver
JAVA_HOME=/Users/hirchytsdaniil/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home \
ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew assembleDebug

# Side panel (only after changing sidepanel/src)
cd ../sidepanel && npm install && npm run build

# Mac helper: release build, bundled FFmpeg, installation and Chrome registration
cd ../native && ./build.sh --install
```

The helper is installed in `~/Applications/Daniil Cast.app`, independently of
this repository. Its Rust executable has the app identity `tv.daniil.cast.helper`.
When macOS asks whether **Daniil Cast** may find devices on your local network,
choose **Allow**; you can also enable it in System Settings → Privacy & Security
→ Local Network. Upgrading from the Python helper may require allowing the new
binary. Existing TV pairings remain valid.

To share it, package a build for the recipient’s OS/CPU. On macOS, opening the
packaged app once installs and registers it. Bundled FFmpeg libraries are
relocated so conversion works without Homebrew. This Mac’s FFmpeg requires
macOS 27; broader releases need tools built for their intended minimum OS.
See the native README for packaging, signing and platform validation.

Install `receiver/app/build/outputs/apk/debug/app-debug.apk` on the TV once,
with ADB or any sideloading app:

```sh
$HOME/Library/Android/sdk/platform-tools/adb -s 192.168.1.11:5555 install -r casting/receiver/app/build/outputs/apk/debug/app-debug.apk
```

Open Daniil Cast on the TV once, so Fire OS lets its service start with the TV.
Casting does not require ADB after installation. On this owner’s physical TV,
keep ADB enabled as requested; do not change it during emulator work.

In Chrome, open `chrome://extensions`, turn on **Developer mode**, choose
**Load unpacked** and select `casting/extension`, or press **Reload** on the
existing Daniil Cast card. The stable extension ID is
`mdcjencokcefbcilbgmlpmonlilphdjk`; the helper allows this ID by default. The
installed helper keeps working when this project is moved.

Conversion needs FFmpeg with a DASH demuxer and AV1 decoding. The helper prefers
its bundled FFmpeg and ffprobe. Development builds can fall back to Homebrew's
`ffmpeg@7` at `/opt/homebrew/opt/ffmpeg@7/bin/`; the default FFmpeg 9 build on this
Mac lacks the DASH reader.

## Use

1. Click the Daniil Cast toolbar button. The side panel opens and stays open
   while you browse.
2. In the **TV** tab, choose **Add a TV**, pick the TV and type the code it
   shows. This is needed once.
3. Open a page and start its video. The **Cast** tab shows what it found: the
   episode, quality, voice-over, subtitles and where to start. Press **Cast**.

The panel then shows the **Remote** tab: progress, play and pause, skip 10
seconds, previous and next episode, and pickers for episode, voice-over,
subtitles, quality, speed and subtitle style. The repeated app-name/TV header has
been removed; the four tabs are at the top. The panel follows the active tab,
and looks again when the page's player starts another stream. Technical details,
versions, addresses, conversion information and the activity log live together
in **Developer**. Normal TV setup and playback remain in the other three tabs.

A link to a video or playlist can also be pasted (the link icon in the Cast
tab). For a normal HTML video, its right-click menu offers **Cast this video to
TV**.

### On the TV

Click/tap the video to reveal controls. All playback buttons, the draggable
timeline, settings, menu rows, Back/Close, error retry and up-next controls accept
mouse/touch input. Remote shortcuts are optional. The play icon is centered with
symmetric padding inside its circle.

**OK** plays or pauses, **left/right** skip 10 seconds (hold to scrub),
**up/down** show the overlay, **Back** hides it, then closes the player.

**Menu** (☰) opens **Options**: episode (by season), next episode, audio,
subtitles, quality, speed and subtitle style (size, color, background, position,
with a live sample line). Pages work like Android TV settings: OK or right opens
or chooses, left or Back goes up. On the idle screen, Menu opens **Settings**:
subtitle style, **Pair a Mac** and **Paired Macs**.

When an episode ends, **Up next** counts down ten seconds to the next one; OK
starts it at once and Back cancels. A new episode keeps the voice-over and
subtitles you chose, by name.

## Episodes, versions and voice-overs

VenomPlayer embeds, such as Kinogo's Плеер 1 on `api.nextembed.ws`, list every
season and episode in the page's config, and for each episode its versions (VP9
and AV1 DASH, H.264 HLS), voice-over names and subtitle files. The extension
reads all of it and sends the whole series to the TV, so episodes change on the
TV itself, without the Mac or Chrome. The links are signed for about ten days.
For each episode the TV takes the same kind of version as the one cast, when it
can decode it, and otherwise the best one it can.

For other players the TV gets what the stream itself offers: its qualities,
audio tracks and subtitles. Episodes of players that build each stream on
request (Playerjs-based players such as Kinogo's main player) can only be
changed on the page; cast again afterwards.

Versions the TV decodes itself (H.264, HEVC and VP9 on the Fire TV Stick) rank
first, then by resolution. The TV reports its decoders when paired, so AV1 shows
as **Mac converts** only on TVs that need it. Streams number voice-overs
(`rus0`, `ru2`); the TV maps those numbers to the player's names.

## Conversion

When the chosen version's codec is one the TV doesn't decode, the helper converts
it **before** playback: the TV shows **Converting on your Mac** with the reason,
and the video starts once the first segments are ready. When the codec wasn't
known in advance, the TV notices the unsupported format within a second, waits
(**Preparing**), and the helper converts it the same way. Neither case is an
error. Only when no Mac answers within 20 seconds, for example after an episode
change on the TV, does the TV explain that the video needs the Mac.

The converter makes up to 720p H.264 with stereo AAC from the chosen voice-over,
in 80-second windows of four-second HLS segments; seeking starts another window.
Keep the Mac awake and on the same network; the helper holds an idle-sleep
assertion meanwhile. Stop, a new cast or 30 minutes without requests ends it and
deletes `~/Library/Caches/DaniilCast/`. Live streams aren't converted.

## Errors

Preparing, converting and buffering are shown as normal steps. Only problems
the viewer must act on are errors, and each has a plain title and what to do:

| Situation | TV | Side panel |
| --- | --- | --- |
| Link expired (HTTP 404/410) | “This link has expired”, Close | Same, with Details |
| Site refuses the TV (401/403) | “The website refused the TV” | Same |
| Server or network trouble | Message with **OK · Try again** | Same, with **Try again** |
| Protected (DRM) video | “This video is protected” | Shown before casting |
| TV off or asleep | — | “The TV isn’t answering”, how to fix |
| TV on, app's service not running | — | “Daniil Cast isn’t running on the TV” |
| macOS blocks the helper | — | “macOS is keeping the helper off your network”, where to allow it |
| Mac no longer paired | — | “Pair again” |
| Helper missing | — | Instructions to install the desktop helper |
| FFmpeg missing | — | Developer tab: conversion tools unavailable |

Technical details (error codes, HTTP status, the failing request) are kept out
of both screens. They are in the **Developer** tab, which can copy a diagnostics
report, and in `~/Library/Logs/DaniilCast/host.log`. The log keeps host names
only, never signed stream addresses or tokens.

## Troubleshooting from Terminal

The Rust executable runs the side panel's actions through the same helper code.
From `casting/native`, use the development binary (or the installed executable):

```sh
cd casting/native
./target/release/daniil-cast discover          # TVs on this network
./target/release/daniil-cast pair 192.168.1.11 # shows a code on that TV, then asks for it
./target/release/daniil-cast status --watch    # what the TV is doing, every second
./target/release/daniil-cast load URL --title Test --referer https://example.org/
./target/release/daniil-cast send '{"action": "quality", "id": "720"}'
./target/release/daniil-cast log -n 30         # recent activity
```

`DANIILCAST_HOME` and `DANIILCAST_LOG` point the helper at another pairing store
and log, so tests don't touch the real ones.

### Testing without the TV

An Android 9 emulator (the Fire OS 7 Android version, `system-images;android-28;
google_apis;arm64-v8a`) runs the TV app at 1920 × 1080 and 320 dpi, like the
stick. `adb forward tcp:8765 tcp:8765` makes it reachable; the helper discovers a
TV on 127.0.0.1 as **Emulator on this Mac** and converts for it with x264, as the
emulator's software decoder can't read VideoToolbox output. Like the stick, the
emulator image has no AV1 decoder. Debug builds can also hide decoders: write
`{"blockDecoders": ["video/x-vnd.on2.vp9"]}` to the app's `files/debug.json`.

## Limits

- `blob:` addresses belong to the browser. The underlying HTTP(S) playlist must
  be detectable; arbitrary browser state can't be moved to the TV.
- DRM, browser-bound logins, mandatory cookies and expiring tokens can prevent
  independent playback. Cookies are never read or sent.
- The TV, the Mac and Chrome must be on the same network for casting, control
  and conversion. A stream already playing doesn't depend on the tab, and
  episode changes of a VenomPlayer series don't depend on the Mac.
- A converted video carries one voice-over and can't switch voice-overs on the
  TV; cast again to change it.
- The TV app can't wake the TV from standby.
- Passing public samples and Kinogo doesn't prove compatibility with every site.

## Receiver and extension 0.3.1 — local verification, 21 October 2026

The long-cue subtitle issue was reproduced and fixed on the Android 9 emulator.
Media3 is now 1.11.1, with downloaded files retaining their complete subtitle
timeline and paused seeks resolving the correct cue through Media3's parsers.
Three instrumented tests cover WebVTT, SRT and ASS: forward/backward seeking,
mid-cue resume, track switching/off/on and cue expiry. All passed. See
[receiver/TESTING.md](receiver/TESTING.md) for the emulator-only command, which
refuses physical devices.

Because the Media3 upgrade touches all playback, the emulator was also paired
with a scratch helper store and sent casts through the installed Rust helper
0.4.0: MP4 with two audio tracks and two subtitle files (French audio and
subtitles, 1.5× speed, seek to 1:30), HLS with pause and resume, DASH switching
to 240p and back to Auto, and an AV1 file converted on the Mac. All five
played with rendered video.

The extension header row was removed. Versions, addresses, conversion diagnostics,
manual network setup and activity logs are together in **Developer**. Five
controller/navigation tests, TypeScript checking and production bundling passed.
From the repository root, **`./casting/simulator.sh`** starts the complete
local environment: visible Android TV 16 with sound, latest receiver build,
extension/helper builds, both network bridges and a verified playing local sample.
The real playback test room is at
`http://127.0.0.1:58132/`. Or double-click **`casting/Simulator.command`**.
The emulator is paired on this Mac as **Daniil Cast Local TV**. Open the Chrome
extension and cast a test video or a video from another website. The test room
shows a live view of the actual emulator and supplies MP4, HLS, DASH and AV1.

The default AVD is `DaniilCast_Local_TV` on `emulator-5556`. Its current CA store
can verify the CDN certificate that the old Android 9 image rejected. The
receiver targets API 36, declares its connected-device foreground service, and
the launcher grants its per-app background-launch permission inside the debug
emulator. Android 8 remains the runtime minimum. The older Android 9 AVD is
preserved for compatibility work; no physical TV receives an installation.

`./casting/simulator.sh menu` opens the command menu. `test` runs eight Android
subtitle/certificate/pointer tests; `smoke` tests real playback, audio, subtitles,
episodes, DASH quality, conversion and casting from Home. All ADB operations
check the target emulator identity. For a fresh AVD, enter the displayed code in
the launcher’s Terminal prompt (or pair in Developer at `127.0.0.1`); later launches select it
automatically. Existing other AVDs can be selected with `DANIIL_CAST_AVD` and
`DANIIL_CAST_PORT`. [Full local run instructions](simulator/README.md).
For a local UI preview with stubbed Chrome APIs and sample data, run
`node tests/preview.mjs` from `sidepanel/` and open `http://127.0.0.1:58131`.
The preview never connects to the helper or TV. It is separate from the real
test room on port 58132.

Evidence: `../backups/2026-10-21-simulator/`. Only the emulator received the new
receiver APK. The physical Fire TV and its pairing were not used or changed in
this follow-up. Desktop distribution packaging remains separate release work.

## Desktop helper 0.4.0 — Rust, 21 October 2026

The Python native host, installer, CLI, converter and C launcher were replaced
by one Rust executable. The extension and TV wire protocols and saved pairings
are preserved. Chrome is registered to the independent installed app in
`~/Applications/Daniil Cast.app`, with bundled FFmpeg and its libraries.

- All ten Rust tests passed, including real AV1 conversion to H.264/stereo AAC,
  seeking into another encoding window, authentication, cache cleanup, native
  message framing, pairing/controls, concurrent storage writers and installation
  that survives removal of the source package.
- Formatting, Clippy with warnings denied, side-panel TypeScript checking and
  its production build passed. The packaged tools were verified to load bundled
  and system libraries without loading Homebrew libraries.
- The installed app returned the real paired TV’s online, paused status both
  normally and when launched with Chrome-equivalent macOS process responsibility.
  Its existing pairing remains valid. After the Mac was unlocked, Chrome’s real
  side panel was reloaded and showed **Mac helper Connected · v0.4.0**,
  **Conversion Ready**, and the paired TV **Online**. The TV’s service had stopped
  while the TV was on its Home screen; opening Daniil Cast once restarted it.
  Physical playback was subsequently tested on this Fire TV: H.264 MP4, HLS,
  AV1 conversion, controls, track selection and playback after closing the
  source tab. The owner confirmed sound from the TV speakers. The detailed
  results, remaining subtitle edge case and language review are in
  [SYSTEM_REVIEW.md](SYSTEM_REVIEW.md).
- Installed-helper startup to first native response measured 3.04 ms median
  versus Python’s 77.51 ms over thirty process launches. Warm request overhead
  was comparable. These measurements do not describe video encoding performance.
- This bundled Apple Silicon build requires macOS 27 because of its FFmpeg
  build. Broader macOS compatibility and public signing/notarization remain
  release work. Windows/Linux build-and-test jobs are configured but have not
  been run from this session.

See [native/README.md](native/README.md) for architecture, packaging, commands,
test instructions and the raw benchmark results.

## Verification — version 0.3.0, 21 October 2026

The TV was off, so the receiver ran on the Android 9 emulator described above;
the helper and side panel ran on this Mac. The panel was rendered in a browser
with Chrome's extension APIs stubbed: its native messages went through the real
helper code to the emulator, and the page data came from the extension's real
scan code run on Kinogo's Плеер 1 embed for «Гангстерленд» (2 seasons, 15
episodes, three versions each).

- **Pairing:** the idle TV showed a code; a wrong code was refused; the right
  one paired, and the TV showed “Paired with Chrome on Daniil’s MacBook Pro”.
  Removing the TV in the panel also revoked it on the TV; adding it again from
  the panel (discovery, **Pair**, code over the playing video) worked.
- **Episodes:** a cast of S2 E1 with Eng.Original and English subtitles listed
  all 8 voice-overs by name and 1080p/720p/480p. Choosing S2 E3 on the TV and
  S2 E3 from the panel kept Eng.Original and the subtitles. At the end of S2 E1,
  Up next started S2 E2 by itself with the same choices.
- **Conversion:** casting the AV1 version showed “Converting on your Mac” and
  played H.264 after 13 seconds, also from 12:34. Casting AV1 without a known
  codec was detected on the TV and converted the same way. No error was shown in
  either case.
- **Closed app:** with the TV app closed, a cast opened it and played. The
  service came back by itself after the app was updated.
- **Errors:** a missing manifest showed “This link has expired” with Close only;
  an unreachable server showed “Can’t reach the video server” with Try again.
  The panel showed the same as calm notes with Details in Activity.
- Receiver build, `tsc --noEmit` for the panel and the helper's input validation
  passed. Not verified: the Fire TV Stick itself, Chrome's real side panel and
  native messaging with this version, Playerjs players, and physical sound.
- **On the stick:** receiver 0.3.0 installed on the AFTSS; its service started
  after the update without opening the app, and answered broadcast discovery in
  under two seconds, reporting H.264, HEVC and VP9 decoders.
- **Local network:** the first real pairing from Chrome failed with “no route to
  host”. The system log showed macOS's Local Network check refusing the
  helper's Python process: Chrome starts native hosts as their own responsible
  process. Started the same way (`responsibility_spawnattrs_setdisclaim`), the
  plain helper failed identically, while `Daniil Cast.app` was attributed to
  `tv.daniil.cast.helper`, which can be allowed in Privacy & Security.

## History

Earlier versions were controlled through ADB and a popup.

### Version 0.1 — 20 October 2026

The owner loaded the local extension and authorized direct-casting checks.

- **MP4:** W3C's Sintel trailer was detected in Chrome and played on this AFTSS.
  Chrome's Pause stopped it at 16.594 s. Its seek control moved playback to 26 s,
  and Play resumed it; the stick reported 31.646 s with no player error.
- **Embedded HLS:** Shaka's unprotected Angel One sample uses a `blob:` URL in
  Chrome. After fixing detection, the extension offered `hls.m3u8` as an
  **HLS master**, filtering individual MP4 fragments. The stick played the
  complete stream with video and subtitles, reporting no player error.
- **Browser pause:** after HLS playback began on the TV, Chrome's video element
  reported `paused: true` at 39.152 s, independently of the TV's position.
- **Independent playback:** the Shaka tab was closed. The TV continued playing,
  reporting 39.589/60 s and `playing: true` afterward.
- **Stop:** Chrome's Stop control returned the TV player to idle. Both public
  sample tabs were closed after verification.
- Android receiver build and JavaScript syntax checks passed. Physical speaker
  output and every third-party site were not verified. DASH support is included
  but the initial public-sample session did not verify DASH playback.

The first native helper attempted a raw Python TCP connection from Chrome, which
returned macOS `EHOSTUNREACH` (errno 65), although the same device was reachable
from the development environment and Chrome's Local Network switches were on.
Switching to the official, existing ADB daemon resolved the connection. No
macOS privacy or firewall setting was changed.

Evidence: `../backups/2026-10-20-after/casting-mp4-proof.png`,
`../backups/2026-10-20-after/casting-hls-proof.png` and
`../backups/2026-10-20-after/casting-verification.json`.

#### Kinogo custom player detection fix

The owner reported no video detected on
`https://kinogo.online/serialy/108346-gangsterlend.html`, using **Плеер 1**
(`api.nextembed.ws`). Its video element contains a `blob:` address, while the
underlying DASH manifest URL has no `.mpd` suffix. Live network detection could
see the manifest's response type after playback restarted; the old retrospective
scan matched resource filenames only and missed it. Switching to another player
also left unrelated HLS candidates in the list.

The 0.1.1 changes above address those cases. The extension was reloaded, and the
owner confirmed **“DASH source appears”** after opening it on the original player.
JavaScript syntax checks passed. This confirmation covers detection; playback of
that specific manifest on the stick has not been independently verified. No
signed stream URL or account credentials were saved in this report.

#### AV1 playback fix — version 0.1.2

Two separate failures appeared on the original custom player:

1. The receiver supplied a fixed User-Agent after applying Chrome's headers.
   Media3 overwrote Chrome's value and the CDN returned HTTP 410 for chunks.
   The receiver now preserves the captured User-Agent in the factory's explicit
   User-Agent field. Recasting then fetched the source without the HTTP error.
2. Every DASH video representation was AV1 (864×486, 1280×720 and 1920×1080).
   The stick marked all video tracks unsupported while successfully decoding
   Opus audio. Playing audio alone had incorrectly counted as casting success.
   The owner confirmed sound with a black picture. The receiver now detects this
   condition, pauses audio-only playback, and reports the actual video format.

With automatic Mac conversion enabled, the same source subsequently rendered
1280×720 H.264 Main Level 4.0 video with stereo AAC on the stick. Its status
reported `renderedVideo: true`, `playing: true` and no error. A TV screenshot
confirmed the real video picture. The converter completed the current 80-second
window and began the next requested window without an encoder error. Physical
speaker output after conversion and the website's subtitle/audio selection were
not independently verified. Playback is limited to the verified session; signed
URLs can still expire.

HTTP failures now have specific messages instead of a generic codec suggestion.
The popup waits for the first rendered video frame before pausing Chrome and
clears a previous player error when a subsequent cast succeeds.

Evidence: `../backups/2026-10-20-after/casting-av1-conversion-proof.png` and
`../backups/2026-10-20-after/casting-av1-conversion.json`. These contain no signed
source URL or request headers.

### Version 0.2.0 — design and subtitles

The TV player now uses a shared palette (ink surfaces, one mint accent) with
idle, opening, error and playback screens, replacing Media3's touch controller.
The popup uses the same palette in light and dark mode. Receiver 0.2.0
(versionCode 4) keeps the 0.1.2 behaviour: Chrome's User-Agent, `renderedVideo`,
`unsupportedVideo` and format reporting.

Checked on this AFTSS through the native host's own code path:

- **Embedded HLS subtitles:** Shaka's Angel One listed English, Greek, French
  and Portuguese (Brazil) instead of the packager's `stream_0`… names. English was
  selected automatically. Switching to French and to yellow, outline, raised
  from the host changed the TV at once.
- **Side-loaded SRT:** Blender's Sintel DASH with English and Russian SRT files
  played the requested Russian file with correct Cyrillic. A deliberately broken
  third link was skipped; playback continued.
- **Position:** Media3 anchors WebVTT cues to the bottom edge and ignores its
  bottom-padding setting, so the receiver pads the subtitle view instead.
  Subtitles also move above the overlay's progress bar while it is shown.
- **Errors:** an expired link showed “This link has expired” with
  `ERROR_CODE_IO_BAD_HTTP_STATUS · HTTP 404`.
- **Kinogo, Плеер 1:** the owner saw only “Closed captions” on the TV. That was
  an empty CEA-608 channel Media3 assumes for HLS without declared captions,
  which includes the Mac's converted stream; the receiver now hides it. The
  player itself (VenomPlayer on `api.nextembed.ws`) has three `<track>` WebVTT
  files: Ukrainian, English and Russian. The updated detector finds them with
  those languages and the one the page shows. The files download without
  cookies and are valid UTF-8 WebVTT. After the extension was reloaded, the
  owner's cast of «Восставший» delivered all four subtitle files, with English
  shown.
- **Kinogo voice-overs:** the owner chose Eng.Original but heard HDRezka
  Studio. The three voice-overs are audio tracks in one manifest (`ru0`–`ru2`
  in DASH, `rus0`, `rus1`, `eng2` in HLS), and the TV took the stream's default.
  The player config lists `dash` (VP9 + Opus), `dasha` (AV1 + Opus) and `hls`
  (H.264 + AAC up to 1258 × 680); each manifest downloaded without cookies. The
  updated scan found all three, read the names and the selected Eng.Original
  from the live player, and described the manifests as VP9 1040p, AV1 1040p and
  H.264 680p with three voice-overs each. On the TV, a cast with the owner's
  choice reported the tracks as HDRezka Studio, HDrezka Studio (Дубл.) and
  Eng.Original, with Eng.Original selected. The first build of this change
  crashed on track changes (a recursive call) and was fixed before that check.
  Voice-over selection inside Mac conversion is unit-tested, not heard.
- JavaScript syntax checks, the receiver build and the host's input validation
  passed. The popup was checked with stubbed data, not inside Chrome.

## Remove

Remove **Daniil Cast** in `chrome://extensions`. Remove the helper's manifest
`~/Library/Application Support/Google/Chrome/NativeMessagingHosts/tv.daniil.cast.json`,
its pairings in `~/Library/Application Support/DaniilCast/`, its log in
`~/Library/Logs/DaniilCast/` and `~/Applications/Daniil Cast.app` if no longer wanted.
Uninstall `tv.daniil.cast` on the TV through Settings → Applications → Manage
Installed Applications. No Fire TV packages or Chrome flags were changed.
