# Daniil TV — Complete Standalone Streaming App

**Status:** all five implementation and verification phases are complete locally,
including the full thirty-minute playback/seek soak. See
[streaming-validation.md](streaming-validation.md) for executed checks.
Remote CI and physical Fire OS performance remain separately unverified.
Repository facts in section 2 describe the original planning baseline, and the
native dependency pins in section 3 were checked against the sources, the
local AVD configuration and upstream tags on 2026-10-21.

## 1. Objective, scope, and constraints

Build a separate, complete personal TV streaming application in this repository. Users browse films and series, discover sources through compatible add-ons, or import torrents, then watch directly on the TV.

The application must work without Nuvio, TorBox, the Chrome extension, the desktop helper, or a home server. Remote add-on services supply configured catalogues and sources; the TV performs torrent downloading and playback itself.

All capabilities below are required for completion. Implementation dependencies do not represent separate releases, an MVP, or optional later iterations.

### Confirmed product decisions

- Separate installation alongside Daniil Cast.
- Application name: **Daniil TV**; application ID: **`tv.daniil.stream`**.
- One local user, with persistent library, favourites, watch history, progress, and preferences.
- Compatible HTTP add-ons for catalogues, metadata, streams, and subtitles.
- Manual magnet, torrent URL, and `.torrent` file imports.
- Offer Cinemeta during onboarding; install it only after the user accepts.
- C++ libtorrent core with a maintained integration for cache eviction.
- Java/XML Android UI and Media3, following `AGENTS.md`.
- English interface; audio and subtitle language preferences are configurable.
- Complete functional verification in the local Android TV emulator.

Accounts, cloud synchronization, a browser companion, casting into the new app, remote transcoding, offline full-file downloads, Usenet, archive extraction, DRM acquisition, and app-store publication are outside this application’s defined scope.

### Device and testing constraints

The intended physical device is a Fire TV Stick HD, first generation: 1 GB RAM, a 32-bit application ABI, Fire OS 7/Android 9, and roughly 3 GB of free storage in the supplied screenshot. Its supported hardware video formats include H.264, HEVC, and VP9 at up to 1080p. [Amazon specifications](https://developer.amazon.com/docs/device-specs/device-specifications-fire-tv-streaming-media-player.html)

- Ship `armeabi-v7a` support for that device and `arm64-v8a` support for the local emulator. Also build `x86_64` for Linux emulator CI.
- Retain minimum API 26 and compile/target API 36.
- Develop and test on **`DaniilCast_Local_TV`, `emulator-5556`, Android TV 16/API 36**.
- Before emulator operations, verify the explicit serial, `ro.kernel.qemu=1`, and the exact AVD name.
- Preserve the older `DaniilCast_TV` AVD and all physical-TV data and preferences.
- Do not connect to, install on, control, reboot, or modify the physical Fire TV. Physical testing needs separate explicit authorization.
- Keep TLS verification enabled.
- The existing API 36 AVD has 4 GB RAM and an ARM64 image. Emulator results establish functionality and measured emulator resource use, not physical Fire TV performance or ARM32 runtime compatibility.

## 2. Existing project analysis and reuse

The repository is clean on `main` at planning time. The current receiver uses Java/XML, Media3 **1.11.1**, Java 17 source compatibility, minimum API 26, and target/compile API 36.

The receiver already handles HTTP media playback, audio and subtitle selection, subtitles after seeks, playback speed, adaptive quality, episode navigation, remote controls, mouse/touch interaction, and error presentation.

Its `PlayerActivity` combines these features with casting commands, pairing, and desktop-conversion coordination. It should not become the new application’s catalogue or torrent controller.

The existing `Catalog` represents browser-supplied seasons and episodes with existing HTTP stream addresses. It discards episodes without playable URLs. The standalone app needs a different model because episodes must exist before their sources are resolved.

| Existing component | Reuse decision |
|---|---|
| `SubtitlePlayback`, `PausedSubtitleCues`, subtitle parsers and styling | Extract into a shared Android playback library; preserve existing cue regressions |
| `AudioTracks`, `SubtitleTracks`, `VideoQualities` | Share track selection and labels, with host-specific preferences |
| `ControlsOverlay`, `MenuPanel`, player visuals and accessibility helpers | Share playback controls, layout resources, pointer handling, and remote behavior |
| `PlayerActivity` | Extract general playback responsibilities; retain a casting adapter in Daniil Cast |
| `Capabilities` | Reuse its foundation, extending checks beyond decoder-name availability |
| `PlaybackErrors` | Share generic classifications; keep “needs Mac” behavior in the casting application |
| Existing `Catalog` | Retain for casting; build independent catalogue, episode, and source models |
| Simulator identity checks, live screen, generated media and input injection | Reuse mechanisms, with an independent standalone-app launcher and fixture environment |
| Rust desktop helper | No new-app runtime dependency; its FFmpeg discovery can help generate test fixtures |
| Chrome extension and side panel | Preserve; no new-app dependency |
| Pairing, discovery, boot receiver and casting HTTP service | Keep in Daniil Cast; exclude from Daniil TV |
| Fire TV maintenance scripts, backups and CleanYourTV | Preserve; do not use for app development or runtime configuration |

Create a shared library under **`android/playback/`** and the new application under **`streaming/android/`**. Both Gradle builds consume the same playback library. Place the native engine, build inputs, patches, host test tools, and native tests under **`streaming/native/`**.

The shared extraction must preserve Daniil Cast’s application ID, existing command/status protocol, pairing storage, subtitle preference keys, and user-visible playback behavior.

One existing test-safety issue must be corrected during implementation: the receiver’s standalone test runner checks the emulator flag but does not check the expected AVD name. Add that check before installation or instrumentation.

Historical review documents contain older implementation descriptions. Actual sources, current Gradle configuration, and newly executed checks take precedence.

## 3. Complete implementation specification

### Shared playback boundary

Extract a reusable playback component rather than copying the casting activity into the new application.

Introduce these host-facing interfaces:

- **`PlaybackRequest`**: immutable media identity, title, source, request headers, resume position, subtitle configuration, and source-specific data-source factory.
- **`PlaybackSession`**: load, play, pause, seek, change tracks, change speed, stop, release, and observe playback state.
- **`PlaybackHost` callbacks**: exit, next/previous episode, source selection, progress changes, and unsupported-format handling.
- **`PlaybackSnapshot`**: readiness, first-frame rendering, position, duration, buffering, selected tracks, supported qualities, and structured errors.

Keep Media3 operations on its application thread. Provider requests, subtitle fetching/parsing, persistence, native reads, and hashing must run off the UI thread.

Daniil Cast translates its existing JSON commands into this interface. Daniil TV uses typed requests directly. Casting transport, pairing, and conversion must not enter the shared library.

Preserve:

- Full-timeline downloaded subtitle handling and paused cue lookup.
- Long VTT/SRT/ASS cues after seeking, resumed loads, and late selection.
- Caption clearance above the complete playback toolbar and timeline touch target.
- Accessible labels, pointer actions, remote shortcuts, and correct Back behavior.
- Playback speed, audio/subtitle switching, and HLS/DASH adaptive quality.

During timeline dragging, show the selected position and commit the actual seek on release. Coalesce repeated remote seek input. Avoid triggering native re-downloads for every intermediate pointer movement.

### Navigation and complete application UI

Use Java/XML with RecyclerView-based catalogue rows/grids. Adapt the existing dark visual language and playback controls.

Provide working screens and states for:

- **Onboarding:** explain local playback, offer Cinemeta, add a source add-on or import a torrent, and set language preferences. Skipping external services must leave manual imports usable.
- **Home:** Continue Watching, favourites, and enabled catalogue rows.
- **Search:** search supported installed catalogues and local imports; retain partial results when one provider fails.
- **Title details:** artwork, available metadata, watched/favourite controls, Play/Resume, source selection, and series seasons/episodes.
- **Sources:** provider, resolution/format hints, language and size when supplied, compatibility status, refresh, and selection.
- **Imports:** magnet or torrent URL entry, file import, metadata-loading status, video-file selection, rename, and removal.
- **Library/history:** favourites, imported items, Continue Watching, watched items, removal and watched-state controls.
- **Player:** shared controls plus source changes, torrent buffering states, and episode navigation.
- **Settings:** add-on installation/order/enabling/removal, catalogue visibility, preferred audio/subtitle languages, subtitle styling, playback/cache preferences, storage cleanup, and Developer diagnostics.

Each screen must support D-pad navigation and actual mouse/touch input. Restore focus when returning from details or playback. Include loading, empty, offline, partial-result, error, retry, and cancellation states.

Show technical statistics and raw diagnostic detail only in Developer views.

### Add-on integration

Implement an HTTP client for the supported Stremio-compatible protocol resources: `manifest`, `catalog`, `meta`, `stream`, and `subtitles`. These are remote JSON services; the TV does not execute add-on JavaScript. [Protocol documentation](https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/protocol.md)

- Support manifest resources expressed as strings or objects, declared types, ID prefixes, required extras, search, catalogue filters, and pagination.
- Preserve resource IDs, episode IDs, and configured URL path segments. Do not reconstruct every series episode as an IMDb-style ID.
- Treat catalogue entries as metadata, independently of whether playable sources currently exist.
- Support direct HTTP(S) media, torrent `infoHash` with optional `fileIdx` and discovery sources, and magnet sources.
- Respect explicit zero-based file indexes. An invalid index is an error, not permission to select another file.
- Apply supplied media request headers only to the appropriate source requests.
- Support subtitle URLs supplied by streams and subtitle resources, including available filename/hash/size hints.
- Clearly mark unsupported external-browser, YouTube-only, DRM-dependent, archive, and Usenet results.
- Do not interpret “not web ready” as “unplayable by Android.” [Stream response definitions](https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/api/responses/stream.md)

Normalize these domain types:

- Add-on installation and advertised resources.
- Title identity, metadata and catalogue entry.
- Season and episode, retaining provider video IDs.
- Direct media source or torrent source.
- Subtitle source.
- Library entry and playback progress.

Scope internal IDs to their provider unless an explicit common external identifier supports deduplication.

Use bounded network concurrency: at most four provider requests concurrently, ten-second individual timeouts, and a fifteen-second aggregate discovery deadline. Cancel obsolete searches and requests after navigation or configuration changes.

Cap decoded response sizes, validate URLs and fields, and ignore malformed optional entries without losing valid results. Cache catalogue/metadata data locally; playable addresses must be re-resolved when resuming or retrying.

Store credential-bearing add-on URLs encrypted using Android Keystore-backed encryption. Redact secrets from diagnostics. Disable application backup.

Offer `https://v3-cinemeta.strem.io/manifest.json` during onboarding. Do not install playback-source add-ons automatically or require a debrid account.

### Source selection and device compatibility

The default Play/Resume action resolves sources and selects the highest-ranked compatible candidate. A separate Sources action allows explicit selection.

- Use a maximum 1080p target for this application’s initial device profile.
- Prefer supported hardware video decoding and compatible audio.
- Combine runtime decoder/profile/size checks with supplied source hints.
- Treat missing codec information as unknown, rather than claiming compatibility.
- Validate actual extracted formats after preparation.
- If a candidate fails before its first frame, try at most two additional compatible candidates, then show source selection.
- Do not silently switch to another release after playback has started.
- Keep adaptive quality controls separate from switching to a different torrent.
- For series sources without a file index, select an unambiguous matching episode; otherwise require file selection.
- Do not start torrent sessions merely because a user browses a catalogue, highlights a card, or opens title details.

Unlike Daniil Cast, Daniil TV must immediately offer another source when the device cannot decode a format. It must not wait for a desktop converter.

Use Media3 with SurfaceView for playback. [Surface guidance](https://developer.android.com/media/media3/ui/surface)

### Native torrent engine and JNI interface

Use **libtorrent 2.1.2**, pinned to commit:

`6da363d2994f17c0b3c0450d124cf73a31a73847`

Build it statically into the application’s native library. Pin NDK **29.0.14206865**, CMake **3.31.6**, Boost **1.90.0**, and OpenSSL **3.5.9**. Record source URLs, revisions/checksums, licenses, and local patches in a dependency lock manifest. Native dependencies are build inputs, never runtime downloads.

The Mac currently lacks NDK and CMake installations; the development setup command must install the pinned SDK packages. End users receive compiled libraries and need no toolchains.

Expose a narrow JNI API covering:

- Open a torrent session from metainfo, magnet, or info hash.
- Await metadata and enumerate files.
- Select a file and open a positional reader.
- Read into a caller-provided byte array.
- Cancel/close a reader.
- Observe metadata, peer, transfer, cache, and error state.
- Close the session and release its resources.

Use 64-bit file lengths and offsets on every ABI. Give handles explicit ownership and generation IDs so stale callbacks cannot access a replacement session.

Implement a Media3 `TorrentDataSource` for an internal torrent URI scheme:

- `open` maps the requested byte range to the selected file.
- `read` runs on a loader thread and waits for verified pieces.
- Missing pieces cause waiting/downloading, never fabricated zero bytes or premature EOF.
- Cancellation promptly wakes blocked reads.
- Close/reopen operations support Media3 extractor seeks, including reads near the file’s end.
- Native exceptions become structured Java errors.

Use this direct data-source bridge as the selected architecture. Reuse buffers and batch reads; do not claim it provides a completely copy-free pipeline.

### Rolling cache and eviction integration

The application must **never require storage for the entire movie**.

Implement a custom libtorrent disk subsystem holding pieces in a bounded private cache, independent of the torrent’s original filesystem layout. Libtorrent provides the disk subsystem customization boundary. [Custom storage documentation](https://libtorrent.org/reference-Custom_Storage.html)

Default operating limits:

| Resource | Default |
|---|---|
| Active payload torrents | One |
| Payload disk cache | 256 MiB; user-selectable 128/256/512 MiB |
| Free-storage reserve | 512 MiB |
| Media3 compressed-data target | 24 MiB |
| Native payload/write-buffer budget | 32 MiB |
| Decoded image memory cache | 8 MiB |
| Peer connections | 40 |
| Hashing workers | One |
| Disk I/O workers | One |

Reserve capacity for incoming writes before accepting them. Account for verified, incomplete, reserved and in-flight data. Apply backpressure rather than exceeding the cache budget.

If there is insufficient storage for the minimum cache and reserve, explain the condition and offer new-app cache cleanup. Never delete files outside this application’s directories.

- Maintain a moving read-ahead window targeting approximately 30–60 seconds, constrained by byte budgets and available bitrate information.
- Give requested extractor ranges and near-term playback pieces the highest urgency.
- Pin active reads, verification work, and required container headers/index data.
- Retain recently used pieces for short rewinds when capacity allows.
- Evict old, unpinned pieces in batches.
- Reset obsolete read-ahead priorities after a seek.
- Stop additional payload downloading when the allowed window is full.
- Keep piece-hash verification enabled.
- Bound metainfo parsing and support piece sizes up to 16 MiB; reject larger requirements with an actionable message.
- Support BitTorrent v1, hybrid, and pure v2 metainfo through the pinned engine.

**Eviction requires a maintained engine integration.** A custom disk backend alone is insufficient because libtorrent can still regard discarded pieces as complete.

Add a narrowly scoped, version-pinned eviction operation that:

1. Runs changes on the engine’s owning thread and quiesces affected I/O.
2. Clears the relevant completion, picker and verification state without losing immutable metadata/hash information.
3. Updates torrent progress and download eligibility, including after a previously finished torrent loses a piece.
4. Rejects affected pending upload requests as required.
5. Sends `lt_donthave` to supporting peers; disconnects non-supporting peers once per eviction batch so availability is resynchronized.
6. Frees storage only after affected readers and engine operations release it.
7. Allows an evicted region to be downloaded, verified, and read again.

BEP 54 explicitly describes availability changes when cached pieces are discarded and the connection-reset requirement without that extension. [BEP 54](https://www.bittorrent.org/beps/bep_0054.html)

Compile-time and runtime checks must confirm that the eviction integration exists. Do not silently substitute a no-op, full-file storage, disabled verification, or repeated full-torrent rechecks.

Do not copy unreviewed private-field manipulation from another project. Keep the integration and its native regressions together, and pin upgrades to passing those regressions.

### Persistence, lifecycle, errors, and trust

Use SQLiteOpenHelper with versioned migrations for add-ons, metadata/cache entries, imported torrent descriptors, favourites, history/progress, and preferences.

- Persist progress periodically and on pause, seek completion, episode change, stop, and lifecycle transitions.
- Continue Watching contains unfinished media with at least thirty seconds watched; mark watched at natural completion or explicit user action.
- Re-resolve sources when resuming; do not depend on an expired saved media URL.
- Remember audio/subtitle preferences by language, not transient track indexes.
- Keep a ten-second cancellable next-episode prompt and resolve the next episode independently.
- Fetch selected/preferred external subtitles first; optional subtitle failure must not stop video.
- Namespace subtitle temporary files by playback generation to prevent old requests deleting a new session’s files.

The standalone app owns playback while its player is visible. On leaving playback or entering the background, save state, release Media3 and native sessions, cancel readers/provider work, and remove payload cache data. Resume explicitly reconstructs the session from saved descriptors. No boot-started daemon, overlay permission, or persistent background downloader is required.

Recover app-owned temporary payload files after a crash; preserve library and history.

Handle distinct provider, certificate, network, metadata, no-peer, low-storage, hash-failure, unsupported-format, cancellation, and native-engine errors. After prolonged no-progress buffering, show Retry and Choose Source rather than an indefinite spinner.

Preserve Java platform TLS verification. Explicitly configure native OpenSSL trust and hostname verification using the application’s accepted Android system trust roots. Do not assume native OpenSSL automatically discovers Android’s trust store. Never fall back to unchecked HTTPS.

Use actual allocated memory and filesystem usage for diagnostics, including native allocations. The Fire Stick profile’s initial total-app memory target is **250 MiB**; measurements and limitations must be reported rather than treating this as a guarantee about physical hardware. [Android TV memory guidance](https://developer.android.com/training/tv/playback/memory)

## 4. Emulator, native, and regression verification

### Independent development environment

Provide:

```sh
./streaming/simulator.sh up
./streaming/simulator.sh install
./streaming/simulator.sh open
./streaming/simulator.sh test
./streaming/simulator.sh smoke
./streaming/simulator.sh profile
./streaming/simulator.sh status
./streaming/simulator.sh logs
```

The new launcher must build/install the standalone application without installing the Chrome extension or desktop helper.

Reuse the existing emulator startup and identity-check mechanisms. Every entrypoint must enforce the expected serial and AVD name; custom AVD/port settings require both values.

Use distinct local ports:

- `58133`: standalone fixture service/test room.
- `58134`: real BitTorrent test seeder, forwarded through the explicitly verified emulator.

Refuse ports belonging to another process/AVD. Do not stop another owner’s emulator or server.

The standalone test room must show the real emulator screen and inject bounded pointer/remote input. Retain same-origin JSON restrictions on control requests.

### Deterministic test fixtures

Required tests must work without public torrents, production accounts, LAN pairing records, public tracker availability, or third-party media.

- Reuse existing generated MP4/HLS/DASH media and long VTT/SRT/ASS cues.
- Add MKV, multiple-video-file torrents, embedded subtitle/audio cases, and MP4 files with metadata at the end.
- Generate a playable video materially larger than a reduced test cache.
- Generate v1, hybrid, and pure-v2 torrents.
- Build a host fixture tool from the native project to create torrents and run real seed sessions.
- Provide deterministic metadata, catalogue, stream and subtitle add-on endpoints, including malformed, slow and failing responses.
- Disable public DHT, tracker bootstrap, local discovery, UPnP/NAT-PMP and unconfigured peers in required tests.
- Use explicit local peers through ADB reverse. Test metainfo transfer and payload transfer over real BitTorrent, rather than replacing the engine with HTTP file playback.
- Keep fixture services outside the target app process so their allocations do not inflate application memory measurements.

Fixture configuration must be debug/test-only. The production app must not contain fabricated catalogue results or fake torrent-engine success paths.

### Required coverage

| Area | Required scenarios |
|---|---|
| Add-ons | Install/enable/order/remove; manifest variants; search/extras/pagination; preserved IDs; partial failures; stale-request cancellation; credential redaction |
| UI | First run; empty/offline states; home/search/details; movies/series; favourites/history; imports; all settings; focus restoration |
| Imports | Magnet, URL and file; multi-file selection; invalid metainfo/index; absent metadata/peers; large file offsets |
| Playback | HTTP MP4/MKV/HLS/DASH; real torrent playback; first frame and advancing position; pause/resume; source changes; episode transitions |
| Rolling cache | At least three cache turnovers; forward and backward seeks outside retained data; accurate re-download; bounded allocation and disk use |
| Native integrity | Corrupted piece; interrupted reads; cancellation; eviction/I/O races; v2 state; eviction after completion; unsupported `lt_donthave` peers |
| Tracks/subtitles | Audio switching; VTT/SRT/ASS and embedded tracks; paused seeks; resumed load; late selection; long-cue expiry; style and clearance |
| Lifecycle | Home/Back; stop/reopen; process death; persisted resume; cache recovery; no leaked sessions or callbacks |
| Safety | Wrong serial/AVD rejected using mocked command tests; no physical fallback; invalid certificates rejected; file-path traversal rejected |

Run native tests with sanitizers on supported host builds and JNI CheckJNI in debug validation. Build all three Android ABIs.

Required emulator smoke proof:

- The app installs separately from Daniil Cast.
- A catalogue title resolves to a real local torrent.
- Playback renders and advances before the whole file is transferred.
- The payload exceeds the test cache while allocated cache storage stays within its configured bound.
- A rewind into evicted data re-fetches and plays correct bytes.
- No out-of-bounds native access, deadlock, fabricated EOF, or uncontrolled cache growth occurs.
- Repeated seeks and episode changes leave stable resources.
- A thirty-minute playback/seek soak completes with diagnostic evidence.

Measure first-frame time, cached/uncached seek recovery, buffering, memory, dropped frames, transfer volume and allocated cache bytes on the controlled fixture workload. Record hardware/image/build details. These results must remain explicitly labelled emulator measurements.

Run the existing receiver’s eight instrumentation regressions and casting playback smoke after shared playback extraction. Run the existing side-panel checks/build integrity and relevant Rust checks to confirm repository compatibility. Preserve documentation of the unrelated open Linux conversion failure.

Instrumentation runners must inspect `OK (...)` output and failure markers; process exit status alone is insufficient. Never use `connectedAndroidTest` to select an attached device implicitly.

The older API 28 emulator may be used for additional offline compatibility checks with its explicit serial/name and existing data preserved. Its results must be reported separately. ARM32 cross-compilation and API 28 emulator checks do not establish physical Fire OS playback performance.

## 5. Execution, deliverables, and completion criteria

Implement directly on the repository's primary branch (`main` in this checkout)
after checking for intervening user changes.

Perform the work in dependency order—shared playback extraction, native streaming/cache integration, providers/persistence, complete application UI, then full verification—but deliver the entire defined scope together.

Required deliverables:

- Separate standalone application with every screen and behavior above working.
- Shared Java playback library consumed by both applications.
- Reproducible native build, pinned dependencies, eviction integration, licenses and regression tests.
- Guarded emulator launcher, fixture add-ons, real BitTorrent seeder, live test room, smoke tests and profiling.
- Debug APK, instrumentation APK, and successfully built release variant with native code retained correctly through shrinking.
- Updated root/component documentation and agent instructions for new-app development.
- A validation report recording commands, outcomes, runtime evidence, measurements and remaining platform limitations.
- Ignored local evidence containing screenshots, first-frame/position records, cache measurements, test results and redacted diagnostics.

Update CI to cover the shared library, both applications, native tests, ABI builds and deterministic emulator tests. Dependency changes must trigger both consumers’ validation.

Preserve Gradle wrappers, lockfiles, maintenance scripts, the pinned CleanYourTV submodule, existing owner records and saved pairings. Do not commit APKs, native build output, SDK paths, caches, tokens, logs or private evidence.

Completion requires all defined app capabilities and required local verification to pass. Missing cache eviction, unsupported backward seeks, mock playback, an unbounded download directory, or dependence on the desktop helper does not satisfy this plan.

Report local emulator verification, API compatibility checks, ABI build verification and physical-device verification as separate facts. This scope completes without operating the owner’s physical Fire TV.
