# Playback verification — 2026-10-25

This review covers the decoder, source-discovery, HTTP, torrent, recovery and
player-state changes described in [the implementation](PLAYBACK_IMPLEMENTATION.md).
It combines source inspection, focused regressions, native sanitizers, Android
lint, debug/release builds and real playback on `DaniilCast_Local_TV`
(`emulator-5556`, Android TV 16/API 36). It does not certify the entire repository
or every physical TV. Only relevant tests were selected.

## Findings fixed

| Area | Failure found | Change and regression evidence |
| --- | --- | --- |
| Preparation ownership | Opening another Details page could cancel an entry already handed to Play. A late navigation result could restart obsolete preparation. | Claimed entries have a bounded independent handoff; navigation cancellation is checked before publishing/preparing. A regression claims one title, opens another, and consumes the original entry successfully. |
| Metadata and refresh | Enriched metadata could change the title's provider identity. A completed empty stream response could hide later inline episode sources. Explicit refresh could reuse prepared results. | Preserve catalogue identity and stream provenance, wait for pending episode metadata, invalidate explicit refresh, and use a consistent 60-second source freshness window. |
| Slow-provider recovery | A fast bad source could show a terminal error while a slower provider was still resolving a usable source. | Wait for an unattempted candidate within the remaining overall budget, drain the failed payload, then resume automatic selection. The regression observes the waiting state and actual advancing output from the late provider. |
| HTTP probe handoff | A two-second probe timeout could remain on Android's already-connected socket and break ordinary progressive playback later. Cleanup could obscure an HTTP status failure. | Set the normal read timeout before connection creation and bound only discovery with a cancellable deadline. Regressions cover a 2.5-second body gap with one GET, timeout followed by another request, typed 401, TLS rejection, redirected DASH and ranged reopens. |
| False stall detection | A brief rebuffer after a long playback could inherit elapsed time from much earlier and trigger failover immediately. | Require a continuous buffering incident as well as quiet useful-data progress. Pauses are excluded from the watchdog and attempt/overall budgets. |
| Viewer state | Manual source changes or activity recreation could lose position, pause or speed. A paused replacement could briefly start audio. | Retain viewer state, prepare paused replacements with playWhenReady disabled, and test paused position/speed plus continuing frames and native telemetry after recreation. |
| Subtitle intent and eviction | Initial language selection could undo explicit Off. A slower download could override a newer choice. Batch eviction could remove the track about to be selected. | Record selection intent when requested, clear pending initial selection for manual choices, and choose before trimming to eight late tracks. Real subtitle regressions retain the decoder/media session. |
| Telemetry and UI latency | Provider/subtitle waits could occupy the workers needed for native stats. Obsolete callbacks could leave polling disabled across lifecycle changes. Source updates ranked on the main thread. | Use a dedicated telemetry worker, generation-aware polling/ranking and background source ranking. Recreation checks assert telemetry resumes. |
| Torrent demand | Duration arriving after the first tick did not replace the provisional bitrate estimate promptly. | Apply the container average as soon as duration is known, then refine with bounded compressed-byte samples. JVM coverage includes late duration. |
| Native retirement | A queued piece-completion callback could reattach a cache to a stream being retired. | Ignore failed/retired streams under the second lock before attaching storage. Focused ASan/UBSan and TSan lifetime/pressure checks pass; the libtorrent patch is unchanged. |
| Recovery discovery | Replacing a magnet with its RAM metadata snapshot could lose the magnet's explicit bootstrap peers. | Preserve validated numeric x.pe hints without DNS. A fresh-engine JNI regression obtains data using only those hints, with trackers/DHT/discovery disabled. |
| Native audio safety | Resampler allocation was unchecked, failed initialization could leave ownership unclear, buffer-size arithmetic could overflow, and fixed channel pointers were unsafe for high-channel planar input. | Add allocation/size guards, immediate resampler ownership and extended_data access in a separate tracked NextLib patch; all three ABI builds and the audio matrix pass. |

Cancellation still closes provider/probe work. JNI lifetime remains guarded by
leases, and create/remove/close stays on the application's serial playback worker.
A replacement payload cannot overlap the old bounded cache. Configuration changes
invalidate discovery. TLS trust, torrent hashes, private descriptor policy and
encrypted persistence are preserved.

## Verification results

| Selected check | Result |
| --- | --- |
| DecoderPlaybackTest + StartupPipelineTest + ProgressiveRecoveryTest + direct MP4/MKV/HLS/DASH acceptance | 20 passed |
| Source recovery, manual selection, payload reset and activity recreation | 5 passed |
| JNI metadata hash versions, repeated close/reopen, cancellation and snapshot bootstrap recovery | 4 passed |
| Shared receiver VTT/SRT/ASS seek and late-selection regressions | 3 passed |
| Shrunk release JNI, verified torrent bytes and exception construction | 1 passed; debug APK restored |
| Startup timing, isolated from other builds/device tests | 1 passed; 20 HTTP and 20 new-engine torrent starts |
| Focused JVM policy, demand, budgets, video watchdog and provider executor | 16 passed, 2 existing ignored checks |
| ASan/UBSan startup/lifetime/pressure and adjacent v2 eviction callback | 6 passed |
| TSan startup/lifetime/pressure | 5 passed |
| App and shared playback lint | 0 errors; 40 app and 4 shared warnings |
| Debug/release builds | All three ABIs built |
| Release packaging | ZIP alignment passed; all 14 libraries across both 64-bit ABIs have PT_LOAD alignment of at least 16 KiB |

The codec check exercises ten audio encodings, E-AC-3 5.1 over BitTorrent,
stereo PCM fallback, modern and 10-bit software video, four legacy AVI codecs,
1080p software H.264, fresh frames after seeks and bounded runtime decoder
recovery. See [decoder coverage](android/DECODERS.md) for the supported build inputs.
The native checks use generated fixtures and genuine loopback BitTorrent
transfers. A runner's `OK (...)` result is required; an ADB exit code is not used
as proof of success.

Lint warnings concern dependency updates, inlined SDK constants, TV orientation,
HTTP media policy, resources/layouts, accessibility tooling and English UI text.
They are retained for follow-up; dependency upgrades and unrelated UI restyling
were not mixed into the playback fixes. HTTP media remains allowed because
providers may supply it; HTTPS certificate validation remains enabled.

Startup timing was measured separately, after builds and all other device tests,
using 20 prepared HTTP and 20 empty-cache/new-engine torrent starts. First A/V
p95 was 1,432 ms for HTTP and 4,373 ms for torrents. Details controls p95 was
369 ms and 170 ms respectively, meeting the local fixture targets. Full
percentiles and measurement conditions are recorded in
[the implementation evidence](PLAYBACK_IMPLEMENTATION.md#local-evidence-2026-10-25).
Private raw reports and runner outputs remain under
`simulator/.cache/evidence/`, outside version control.

## Practical limits and follow-up

- Actual VC-1 decoding still needs a suitable legal/generated fixture; current
  coverage verifies decoder discovery only.
- Full-resolution software throughput and audio/display capabilities depend on
  the TV. The app keeps its 1080p budget. Small 10-bit fixtures prove decode/seek
  correctness, not universal throughput, HDR display output or Dolby Vision.
- Unsupported extractors, deliberately misleading known URL suffixes, damaged
  files and DRM can still fail. Bundling a codec cannot supply a missing extractor
  or authorization.
- Provider latency, expiring links, unavailable peers and sustained public-swarm
  throughput remain external variables. No public providers or swarms were used
  for this verification; loopback percentiles do not predict internet startup.
- Physical-TV compatibility, long soak and every OS/CPU combination remain
  separate checks. No physical TV was accessed.

These limits are explicit boundaries of the evidence. The selected checks show
that the repaired paths work and remain bounded under their tested conditions;
they cannot establish that arbitrary future media or devices will have no bugs.
