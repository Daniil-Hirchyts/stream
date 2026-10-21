# Daniil TV Android

Java/XML, minimum API 26, compile/target API 36. Three modules:

- `:playback`: shared Media3 UI/session library from `android/playback`.
- `:torrent`: JNI engine and positional Media3 data source, built for
  armeabi-v7a, arm64-v8a and x86_64.
- `:app`: `tv.daniil.stream`, currently the add-on and persistence layers and
  their device tests. The navigation/player activities are **not implemented**
  yet; the debug APK has no launcher screen.

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
cancel existing scopes. The future UI must check the scope again before showing
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
available on the model; native DHT is a session setting. Decoder/ranking and
ambiguous episode-file selection belong to the unfinished playback controller.

## Local data

`TvStore` uses SQLiteOpenHelper migrations (currently version 2) for configured
add-ons, catalogue visibility, library/favourites, per-video progress/history,
preferences, imported descriptors and metadata cache. Call it from workers.
Continue Watching starts at 30 seconds and excludes watched/completed videos;
natural completion and explicit watched/unwatched actions are distinct. The
future playback host must call progress updates periodically and on lifecycle,
seek and episode transitions.

Stored URL/manifest, metadata, import and preference values are AES-GCM encrypted
with an Android Keystore key. Row identities are authenticated as additional
data. No plaintext fallback exists on key loss. Diagnostic URL representations
omit configured paths, queries, fragments and userinfo. Android backup and
device transfer are disabled. The metadata cache holds at most 256 entries and
8 MiB of encrypted content; clearing it preserves library/history.

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
./streaming/simulator.sh test          # both suites on the verified AVD
cd streaming/android
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

Tests use generated loopback fixtures on 58133, the real seeder on 58134, and a
deliberately untrusted TLS fixture on 58135. The TLS test must fail certificate
verification; no trust-store change or certificate bypass is used. Fixture code
lives outside the app. The launcher verifies serial, emulator flag and AVD name
before every device operation and preserves runner output in
`streaming/simulator/.cache/evidence/`.

See the [validation record](../../docs/streaming-validation.md) and the remaining
[complete application specification](../../docs/STREAMING_APP_PLAN.md).
