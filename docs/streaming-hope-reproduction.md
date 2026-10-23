# Hope (2026): playback and remote-control reproduction

Date: 23 October 2026. Target: verified `DaniilCast_Local_TV`, `emulator-5556`, Android TV 16/API 36. IMDb ID: `tt27369017`.

## Findings and fixes

1. The emulator could reach public IP `1.1.1.1`, but resolving the Meteor hostname failed. Restarting only this AVD with `-dns-server 1.1.1.1,8.8.8.8` restored resolution and real peer discovery. User data was preserved; TLS verification stayed enabled. The standalone launcher now accepts the optional `DANIIL_CAST_DNS_SERVERS` environment setting for reproducing this repair on a future cold start.
2. After DNS recovery, the first selected Meteor and MediaFusion torrents downloaded real data but failed actual format validation because this emulator cannot decode their E-AC-3 audio. The first TorrentsDB sample failed actual HEVC format validation. Source selection checked DTS/TrueHD hints but missed EAC3, DDP5.1, DD+5.1 and AC3 labels. These labels now participate in the existing device decoder checks. A release advertising an AAC/Opus/MP3 alternative remains eligible; actual track/format validation still applies.
3. The shared transport handler consumed directional keys as transport shortcuts. Daniil TV now handles its own visible focus graph first: Up reaches Audio & subtitles; Down reaches the centre transport and then the timeline; OK activates the focused options button. Left/right and media-key transport shortcuts remain usable. Focused controls stay visible, and Back can dismiss them without the focus keeper reopening them.
4. Touch removes keyboard-only focus behaviour before handling a click, preserving first-tap activation after remote navigation.
5. Close is hidden from the playback toolbar; Back exits playback. Error-dialog actions remain available.
6. The app overrides the play vector with an optically centred triangle. Pause is unchanged. A rendered-vector test compares each glyph's alpha-weighted centre with the button centre; the centre transport also has a visible focus ring.

The exact original torrent was not identified, so these are the failures observed during this reproduction, not proof that every original error had the same cause.

## Final installed-provider results

Only the owner's already accepted, enabled descriptors were copied into isolated test data. The owner store, configured addresses and account data were not changed. No new providers were accepted.

| Provider/configuration | Purpose | Final result for Hope |
| --- | --- | --- |
| Cinemeta | Metadata and catalogue | No streams advertised; normal for a metadata provider |
| Meteor | Torrent sources | Played; first frame 7.675 s; 33 results, 14 eligible on this emulator |
| MediaFusion / Midnight | Torrent sources | One result, no compatible source on this emulator; E-AC-3 sample rejected before playback |
| Peerflix | Torrent sources | HTTP request failure; external provider failure remains |
| TorrentsDB | Torrent sources | Played; first frame 13.265 s; 15 results, 7 eligible on this emulator |
| All installed, automatic Play | Combined source discovery and automatic selection | Played on the first selected candidate; first frame 7.395 s; 49 results, 21 eligible; Up + OK opened options; playback advanced after seeking to 30 minutes |

Durations reported by the successful samples were approximately 157 minutes. This checked real downloaded torrent pieces, an actual rendered video frame and advancing playback. It did not watch the entire film. Provider counts and timings are this run's observations, not permanent availability guarantees. The native public-network filter excluded LAN/reserved peers and discovery; no physical TV was contacted.

## Verification and evidence

- The final requested-title audit completed with `OK (1 test)` and `complete: true`. Individual provider failures are recorded separately; that runner line does not mean every provider played.
- Thirteen player/source-policy/acceptance instrumentation tests passed, including subtitle selection using actual arrow/OK events, pointer tracks/style/timeline controls, Dolby hint checks, imports and bounded source failover.
- Twenty-one JVM tests passed and debug lint completed successfully.
- A final three-test player rerun passed (`OK (3 tests)`), including explicit remote-to-touch and Back-hide checks.
- The previous broad-run thirty-minute endurance failure remains unresolved and was not rerun for this targeted change.

Private, ignored evidence: `streaming/simulator/.cache/evidence/hope-2026/` contains `before-audio-filter.json`, `after-audio-filter-providers.json`, `final.json`, runner/build logs, `player-paused.png` and `hope-after-seek.png`. The latter image was captured after the combined test's 30-minute seek. No configured manifest URLs, cookies or API keys are included in this report.

Reproduce explicitly with `./streaming/simulator.sh requested-title tt27369017`. This opt-in command uses the currently accepted providers, so results may change if that list changes. Required fixture tests remain local-only.
