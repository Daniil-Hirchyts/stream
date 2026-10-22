# Daniil TV Android

Java/XML, minimum API 26, compile/target API 36. Three modules:

- `:playback`: shared Media3 UI/session library from `android/playback`.
- `:torrent`: JNI engine and positional Media3 data source, built for
  armeabi-v7a, arm64-v8a and x86_64.
- `:app`: `tv.daniil.stream`, the standalone launcher, RecyclerView navigation,
  playback host, add-ons and local data. Installs separately alongside Daniil Cast.

## Run the app

From the repository root: `./streaming/simulator.sh up`. The launcher starts or
verifies `DaniilCast_Local_TV` / `emulator-5556`, builds the standalone app and
installs/opens it. No helper, extension, pairing, debrid account or home server
is needed. Development requires Java 21/17, Node, FFmpeg, a C++ toolchain and
Android SDK/NDK/CMake versions pinned in `streaming/native/deps.lock`.

Welcome offers Cinemeta for explicit review/acceptance, a configured source
add-on, manual imports and language preferences. Skipping providers leaves
imports available. Home, Search, Library, History, Imports and Settings are in
the left navigation. Details provide Play/Resume, Sources, watched/favourite
controls and seasons/episodes. Settings manage add-ons, catalogue visibility,
languages, subtitle style, cache size (128/256/512 MiB), autoplay and app-cache
cleanup. Versions, statistics and license texts live in Developer views.

`WatchActivity` re-resolves sources and uses the shared player. `SourcePolicy`
checks advertised hints and actual Media3 formats against the 1080p profile.
Automatic failover is limited to three candidates before first frame; explicit
choices are preserved. Ambiguous multi-file torrents require a file choice.
Leaving/backgrounding playback saves progress and releases Media3, native
sessions and payload files. Restart reconstructs playback from descriptors;
startup recovers disposable payload/subtitle files left by a killed process.

File imports accept Android `content://` grants through Open/Share as well as
`ACTION_OPEN_DOCUMENT`. This TV image has no system document picker; in that
case the app explains how to open/share a `.torrent` from a TV file manager.
It never requests broad storage access. Instrumentation supplies actual
generated metainfo through a separate test-APK content provider, then exercises
the production private-copy and native playback paths.

## Add-ons

`AddonRepository` is the entry point for manifest preview/accepted installation,
catalogue rows, filtered/paginated catalogue queries, search, metadata, streams
and subtitles. It implements the supported HTTP resources in the
[Stremio protocol](https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/protocol.md).
No add-on JavaScript runs in the application. Cinemeta's address is an onboarding
offer constant; constructing the store/repository installs nothing.

Call blocking APIs from an application worker, never the UI or the repository's
internal request pool. Create a `Cancellation` with `newRequest()` for each
navigation/search generation and close it when obsolete. Configuration mutations
cancel existing scopes. The UI checks the scope/generation again before showing
worker results. Close the repository when its owning application scope ends.

The repository uses four request workers and a bounded queue; transport also
limits sockets to four. Requests get ten seconds including redirects, and
discovery gets fifteen seconds including queued providers. Results retain
provider errors alongside valid responses in installation order. Catalogue/meta
requests always try the network, then may return cached metadata marked stale.
Stream/subtitle addresses are resolved afresh and never stored as resumable
playable URLs.

Configured URL paths/escapes and query parameters remain intact. Resource IDs
and extras are encoded individually. Titles use provider-scoped identities;
only an explicit common IMDb identifier permits search deduplication. Episodes
retain their exact video IDs and need no pre-existing playable URL.

`MediaSource` separates HTTP, magnet, info-hash and unsupported candidates.
Invalid explicit indexes remain unsupported; file zero stays zero. Source
headers belong only to that HTTP candidate, and are never applied to add-on or
subtitle discovery. `notWebReady` does not make an Android source unsupported.
`torrentSource()` maps an explicitly chosen torrent and its supplied trackers
to the JNI input without starting a session. Discovery DHT hints remain
available on the model; native DHT is a session setting. `SourcePolicy` and
`WatchActivity` handle ranking, file selection and native session ownership.

## Local data

`TvStore` uses SQLiteOpenHelper migrations (currently version 2) for configured
add-ons, catalogue visibility, library/favourites, per-video progress/history,
preferences, imported descriptors and metadata cache. Call it from workers.
Continue Watching starts at 30 seconds and excludes watched/completed videos;
natural completion and explicit watched/unwatched actions are distinct. The
playback host saves progress periodically and on lifecycle, seek and episode
transitions.

SQLite URL/manifest, metadata, import and preference values are AES-GCM encrypted
with an Android Keystore key. Row identities are authenticated as additional
data. No plaintext fallback exists on key loss. Diagnostic URL representations
omit configured paths, queries, fragments and userinfo. Android backup and
device transfer are disabled. The metadata cache holds at most 256 entries and
8 MiB of encrypted content; clearing it preserves library/history.
The shared player's app-private compatibility preferences hold presentation and
language choices only; credential-bearing addresses never enter that store.

`ImportRepository` stores magnets/URLs as encrypted descriptors. A selected
`.torrent` is copied to app-private no-backup storage, capped at 32 MiB and
encrypted, so reopening does not depend on an expiring file permission. Private
UUID filenames prevent traversal. Each database has its own directory so test
cleanup cannot remove the user's descriptors. Crash recovery removes only
interrupted/orphaned descriptors in that directory. Native payload remains
separate and is never an import descriptor or library asset.

## Verification

```sh
./streaming/simulator.sh engine-test   # 12 JNI/Media3 tests, real BitTorrent
./streaming/simulator.sh data-test     # add-ons, encryption, persistence, imports
./streaming/simulator.sh test          # engine, data and app suites on the verified AVD
./streaming/simulator.sh smoke         # visible real-app catalogue → torrent playback
./streaming/simulator.sh soak          # 30 real minutes of playback/seek cycles
./streaming/simulator.sh site          # real screen and bounded input, port 58133
./streaming/simulator.sh profile       # dumpsys memory/graphics and screenshot
./streaming/simulator.sh acceptance    # extended UI, HTTP formats, tracks, failover and measurements
./streaming/simulator.sh process-death # real process termination and resumed playback
./streaming/simulator.sh release-test  # shrunk release JNI probe; restores debug app/test APKs
cd streaming/android
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:lintDebug :app:lintRelease
```

Tests use generated loopback fixtures on 58133, the real seeder on 58134, and a
deliberately untrusted TLS fixture on 58135. The TLS test must fail certificate
verification; no trust-store change or certificate bypass is used. Fixture code
lives outside the app. The launcher verifies serial, emulator flag and AVD name
before every device operation and preserves runner output in
`streaming/simulator/.cache/evidence/`.

The default class list excludes the long soak and two-phase process-death test.
Debug app tests use isolated databases/player preferences and local-only peers.
After installing the current debug app/test APKs and starting services,
`streaming/simulator/process-death.sh` terminates actual playback after durable
progress, then checks orphan recovery and resumed playback in a fresh process.

The shrunk release JNI probe builds with `-PreleaseValidation=true
:app:assembleRelease :app:assembleReleaseAndroidTest`, using the local debug
signing key explicitly for validation; ordinary release output is unsigned.
The launcher invokes `tv.daniil.stream.ReleaseProbeInstrumentation`, a small
Android platform runner that does not retain AndroidX/Kotlin test dependencies
in the shrunk application. It opens no owner database and uses explicit
local-only native peers. Never use
`connectedAndroidTest` to select a device. Both app and library pin the NDK so
final release symbol stripping uses the intended toolchain.

See the [validation record](../../docs/streaming-validation.md) and the
[complete application specification](../../docs/STREAMING_APP_PLAN.md).
