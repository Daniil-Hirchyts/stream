# Playback preparation, startup and recovery

The playback path remains Java/Media3 → the pinned NextLib renderer extension
and JNI/libtorrent. It needs no additional player, desktop conversion service,
or persistent cache of playable provider addresses.

## Discovery and navigation

Home and Search publish local/cached content before remote requests finish.
Details publishes its controls and cached context immediately, then enriches
metadata. A process-local `PlaybackPreparation.Entry` resolves metadata and
streams concurrently, publishes immutable partial results, expires after 60
seconds, and is invalidated by provider configuration changes. It is keyed by
title, episode and configuration. Series source discovery waits for a real
episode ID. Browsing never creates a native engine or downloads torrent payload.

Four provider requests can run concurrently, with at most 64 queued jobs.
Playback outranks visible navigation, which outranks preparation. Play promotes
its existing request scope and cancels unrelated speculative/navigation work.
Metadata discovery occupies at most two sockets and returns its first useful
result. Ready sources can reach playback before slow providers finish; after
the first usable result, automatic Play waits at most 750 ms for better ones. Pending discovery may supply alternatives
later. Addresses and torrent metadata snapshots stay in RAM.

Play claims its entry through a bounded token handoff. Opening another Details
page or cancelling speculative work cannot cancel a claimed entry. Configuration
changes still invalidate it. Explicit Refresh invalidates the matching prepared
result, and Sources uses the same 60-second freshness window. Metadata enrichment
keeps the original catalogue identity; inline episode streams retain their own
provider provenance. Empty stream responses do not finish discovery while episode
metadata is still pending.

Play and Resume start automatically. Sources retains explicit selection, and
an explicitly selected source never switches to another source automatically.
Ranking prefers supported hardware codecs, then software, with resolution,
size and seed hints secondary. Recent process-local success/failure observations
influence ranking separately from durable Played marks. The device profile
remains capped at 1080p; HDR additionally requires a matching hardware decoder
and display. Source labels are hints; Media3 validates the actual track.

## Loading and recovery

`PlaybackCoordinator` owns native lifetime on the application playback worker.
A torrent gets a distinct payload directory. Switching removes the old torrent,
waits for removal/storage drain, and keeps the engine warm. If drain fails,
the whole engine closes before replacement. JNI close rejects new calls,
wakes blocked operations, waits for outstanding leases, and destroys the
session on the owning worker. No second payload cache is allocated while the
old one is retiring.

Startup has a 60-second overall budget. Where another candidate exists,
no-peer/metadata/attempt thresholds are 8/12/25 seconds. Buffer stalls use a
6-second threshold with an alternative, otherwise 30 seconds. User pause is
excluded. Recovery prefers another provider and deduplicates equivalent payloads;
it is bounded to three candidates per incident. Immediate failures after an
alternate renders do not reset that incident's allowance. A later incident
can receive a fresh allowance. HTTP 401/403/410 permits one provider refresh
per source identity, including failures during format discovery.

If the first source fails before a slower provider returns, recovery waits for
an unattempted source within the remaining overall budget. It releases the old
payload while waiting. Manual selection remains explicit. Activity recreation,
manual source changes and same-source recovery retain position, speed and pause
state; a paused replacement is prepared with playWhenReady disabled.

Verified bytes delivered to the reader measure useful torrent progress;
network download counters alone no longer extend startup. If obsolete partial
pieces occupy cache slots, urgent reservation fails, and useful reads stall,
one reset of that payload uses its RAM metadata snapshot. It preserves position
and pause state, drains the old storage first, and keeps piece hash checks on.
Fallback trackers apply only to a bare info hash with no explicit trackers,
never to magnets or metainfo/private descriptors.
Numeric magnet bootstrap peers also survive the metadata-snapshot handoff, so
a rebuilt engine does not lose its only known peer.

Initial torrent demand is five seconds. Once duration is available, average
container bytes/duration seeds the bitrate estimate. A bounded moving estimate
of compressed bytes per media time refines it, excludes seek discontinuities,
and applies playback speed once. Read-ahead shrinks by Media3's existing buffered
seconds. Renderer counters detect stalled picture output and sustained excessive
drops even if audio still advances.
Telemetry runs on a dedicated application worker so provider or subtitle waits
cannot starve it. Source reranking runs off the UI thread. Each buffering incident
has its own elapsed timer; a brief rebuffer after a long film does not inherit
the film's elapsed time as a stall.

Extensionless HTTP sources use a two-second, 32-KiB maximum content probe for
HLS/DASH. The first ordinary loader reuses the opened response and prefix rather
than issuing a second GET. Explicit range requests reopen normally. Known
container/manifest suffixes bypass probing. TLS verification and supplied headers
remain intact. Deliberately misleading known suffixes and unsupported container
extractors are still limitations; adding a decoder cannot fix demuxing by itself.
The probe deadline ends at handoff. The socket has the normal 30-second read
timeout from connection creation, because Android does not reliably apply a
changed read timeout to an existing connection. HTTP status failures remain
typed so the provider-refresh path can recognize expired authorization.

Preferred external subtitles resolve after video startup. Parsed cue timelines
attach to the existing session without replacing media, decoders or playback
clock. Selection revisions protect explicit Off/manual choices; paused seeks
and cue boundaries update immediately. At most eight late tracks remain in
memory; failed/evicted downloads are available again. The existing shared
VTT/SRT/ASS seek compatibility path is retained for loaded sidecar tracks.
Selection intent is recorded when a download starts, and eviction protects the
newly selected track, including when several tracks arrive together.

## Focused verification

Use test filters; a full suite is unnecessary for these changes:

```sh
./simulator.sh data-test 'tv.stream.app.StartupPipelineTest'
./simulator.sh decoder-test
./simulator.sh data-test 'tv.stream.app.StartupTimingTest'
./native/build-host.sh asan startup_ blocked_reads_wake shutdown_with_inflight_hashes
./native/build-host.sh tsan startup_ blocked_reads_wake shutdown_with_inflight_hashes
```

`StartupTimingTest` records 20 prepared HTTP and 20 cold torrent player/engine
lifetimes on generated loopback fixtures. It measures control availability and
Play-to-first-video-plus-audio (the later timestamp), with individual traces and
p50/p95 in private `files/validation-startup-timing.json`. Healthy fixture targets
are Details p95 ≤500 ms, HTTP first A/V p95 ≤3 seconds, and cold torrent first
A/V p95 ≤5 seconds. These targets do not imply comparable public-provider or
swarm timings. Diagnostics include first source, torrent metadata, first verified
read, first video, first audio and stable playback stages. Device identity checks
remain mandatory; no physical TV or public torrent is used by required tests.

The last local run (25 October 2026, 20 starts per transport) met all three:
HTTP first A/V p95 1.4 s, cold torrent p95 4.4 s, Details controls p95 under
0.4 s. See `android/DECODERS.md` for format coverage, codec build inputs and
limitations.
