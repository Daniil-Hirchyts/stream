# Daniil Cast

A local Chrome extension and a small Fire TV player. The extension sends the
**original media URL**, and the stick fetches and decodes it itself. The Mac sends
launch and playback commands through its existing, authenticated ADB connection.
There is no screen capture, encoding, cloud service, subscription, or listening
HTTP server.

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

- `extension/`: Manifest V3, direct video detection, optional embedded-player
  request detection, source chooser, resume position, play/pause/seek/stop.
- `receiver/`: Android APK, package `tv.daniil.cast`, Media3 player with MP4,
  HLS and DASH support, remote controls and audio focus. It requests only network
  access. Playback pauses when another app takes the foreground.
- `native/host.py`: narrowly scoped Chrome native-messaging host, using the
  official Android platform-tools ADB daemon and existing trusted key. It accepts player commands;
  it does not expose an arbitrary shell command API.

## Build and install

Requirements: Java 17–21, Android SDK 34/platform-tools, and the existing Python environment at
`../cleanyourtv/tools/venv`. Media3 1.4.1 is pinned for compatibility with the
installed SDK and Fire OS 7. The debug APK is used so the ADB helper can read its
private playback-status file with `run-as`.

```sh
cd $HOME/Developer/Hobby/daniiltv/casting/receiver
JAVA_HOME=$HOME/Library/Java/JavaVirtualMachines/openjdk-21.0.2/Contents/Home \
ANDROID_HOME=$HOME/Library/Android/sdk \
./gradlew --no-daemon --console=plain assembleDebug

cd $HOME/Developer/Hobby/daniiltv
cleanyourtv/tools/venv/bin/python casting/native/install.py
```

Sideload `receiver/app/build/outputs/apk/debug/app-debug.apk` to the stick using
ADB. Keep ADB debugging on, as requested. The native host's `config.json` specifies
the stick's IP, currently `192.0.2.10`. Update this if DHCP changes its address.
The SDK's `adb` is used by default; `adb_path` can override it in this file. Its
daemon owns the device connection so each Chrome popup does not open a separate
raw TCP connection to the stick. No macOS network privacy setting is modified.

In Chrome, open `chrome://extensions`, turn on **Developer mode**, choose
**Load unpacked**, and select:

`$HOME/Developer/Hobby/daniiltv/casting/extension`

The stable extension ID is `mdcjencokcefbcilbgmlpmonlilphdjk`. The native host
allows this ID only. Its public extension key is not a credential. The project
must stay at its current location, or re-run the installer after moving it.

## Use

1. Open a video in Chrome and start it.
2. Open **Daniil Cast** from Chrome's Extensions menu. Direct video sources are
   found in the selected tab. Choose the main playlist or desired quality.
3. Press **Cast video**. After the TV reports successful playback, the extension
   pauses the browser video. Use its controls or the Fire TV remote.

For a player using a `blob:` URL, choose **Find embedded streams**. Chrome asks
for optional site access so the extension can see cross-origin frames and media
requests. Reload the page, start its video, and reopen the extension. Detection
is armed only for tabs where the extension was opened; captured URLs and request
metadata stay in session memory and are removed when those tabs close.
Access is disarmed on a change to another origin. The detector keeps playlists
through same-page URL hash changes, filters common MP4 fragment names, and reads
up to 256 KiB of HLS playlist text without credentials to identify master
playlists. Master playlists are offered before video-only or audio-only children.

Version **0.1.1** also reads resource MIME types, so a DASH manifest does not need
a `.mpd` filename. For a video that was loaded before the extension opened, it
checks existing resource timing entries in each frame. If a type is unavailable,
it checks at most 16 existing resource URLs from frames containing a video using
credential-free HEAD requests, in groups of four. A bounded GET fallback reads
only a manifest prefix when a server rejects HEAD or returns an ambiguous type.
Sources belonging to removed player frames are excluded, and manifests are
retained ahead of individual media fragments in the session cache.

The extension does not read cookies or Authorization headers. It forwards the
media URL, title, playback position, and available Referer/Origin/User-Agent
headers to your own TV. Signed URLs can themselves contain access tokens; do not
share them. No stream URLs are written to logs or native-host configuration.

For a normal HTML video, its right-click menu also offers **Cast this video to
Fire TV**. That shortcut starts at the beginning; the popup supports resuming.

## Limits

- `blob:` addresses belong to the browser. The underlying HTTP(S) playlist must
  be detectable; arbitrary browser state cannot be moved to the TV.
- DRM, browser-bound login sessions, mandatory cookies and expiring tokens can
  prevent independent playback. This implementation does not bypass protection
  or transfer account cookies. Use the service's supported TV app in those cases.
- Actual video/audio codecs must be supported by this Fire TV Stick HD. Media3
  handles containers and streaming protocols but uses the stick's decoders.
- Selecting the wrong playlist can cast an advertisement or a different quality.
  A source chooser is included because arbitrary sites do not identify these
  consistently.
- The Mac is needed for controls and new casts. A stream already playing on the
  TV does not depend on the browser continuing to render the tab. Its source URL
  must remain valid and reachable by the TV.
- Passing public samples does not prove compatibility with every website.

## Verification — 20 October 2026

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
  but has not yet had an end-to-end playback check.

The first native helper attempted a raw Python TCP connection from Chrome, which
returned macOS `EHOSTUNREACH` (errno 65), although the same device was reachable
from the development environment and Chrome's Local Network switches were on.
Switching to the official, existing ADB daemon resolved the connection. No
macOS privacy or firewall setting was changed.

Evidence: `../backups/2026-10-20-after/casting-mp4-proof.png`,
`../backups/2026-10-20-after/casting-hls-proof.png` and
`../backups/2026-10-20-after/casting-verification.json`.

### Kinogo custom player detection fix

The owner reported no video detected on
`https://video.example/series/episode.html`, using **Плеер 1**
(`player.example`). Its video element contains a `blob:` address, while the
underlying DASH manifest URL has no `.mpd` suffix. Live network detection could
see the manifest's response type after playback restarted; the old retrospective
scan matched resource filenames only and missed it. Switching to another player
also left unrelated HLS candidates in the list.

The 0.1.1 changes above address those cases. The extension was reloaded, and the
owner confirmed **“DASH source appears”** after opening it on the original player.
JavaScript syntax checks passed. This confirmation covers detection; playback of
that specific manifest on the stick has not been independently verified. No
signed stream URL or account credentials were saved in this report.

## Remove

Remove **Daniil Cast** in `chrome://extensions`. Remove the native host manifest
`~/Library/Application Support/Google/Chrome/NativeMessagingHosts/tv.daniil.cast.json`
and this `casting/` directory if no longer wanted. Uninstall `tv.daniil.cast` on
the Fire TV through Settings → Applications → Manage Installed Applications.
No original Fire TV packages, Chrome flags, or ADB settings need changing.
