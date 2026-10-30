# Stream streaming engine

Rust code that lets the TV play a torrent while it downloads, keeping only a
bounded window of it on disk. It is its own BitTorrent implementation: there is
no C++ and no libtorrent. Built into `libstream.so` for the app (through
`android/torrent`) and into the `stream-fixture` tool and the tests for this
computer.

| Path | Role |
| --- | --- |
| `native/core/src/engine.rs` | Torrents, readers, the read-ahead window controller, eviction, errors and statistics, over a pluggable backend (`swarm.rs`) |
| `native/core/src/cache.rs` | The piece cache: one preallocated file per torrent, divided into piece-sized slots; pins, verification, LRU eviction candidates |
| `native/core/src/bt/` | The BitTorrent backend: wire protocol, peers, piece picker, hashing, uploads, metadata and piece-layer exchange, trackers, DHT, local discovery |
| `native/core/src/api.rs`, `native/core/src/jni_bridge.rs` | The engine handles and `tv.stream.torrent.TorrentEngine`'s native methods |
| `native/core/src/fixtures.rs`, `native/core/src/bin/stream-fixture.rs` | Reproducible content, torrents and a real loopback seeder for the tests and the simulator |
| `native/core/tests/` | Integration tests against real seeders on loopback |
| `native/core/examples/fetch.rs` | Streams a file from given peers and prints its SHA-256, for checks against other BitTorrent implementations |
| `native/core/notices.py` | Writes the app's notice for the crates linked into `libstream.so` |
| `native/eval/interop.sh` | Loopback interoperability checks against independent implementations (librqbit, optional libtorrent/aria2/Transmission/WebTorrent, HTTP/HTTPS) |
| `native/eval/rqbit/` | Evaluation of librqbit against the fixtures, and `src/bin/interop.rs`, the librqbit peer `interop.sh` uses; never shipped (see its README) |
| [soak investigation](soak-investigation.md) | What is and isn't known about the emulator soak's `Timed out: soak seek 159` |

## Building

The toolchain is pinned in `native/core/rust-toolchain.toml` (Rust 1.92.0 and the
three Android targets, which rustup installs on first use) and every crate in
`native/core/Cargo.lock`; builds use `--locked`.

```sh
./native/build-host.sh dev        # debug build of stream-fixture, then all tests
./native/build-host.sh release    # optimized, as the app ships
./native/build-host.sh dev v2_    # only tests whose names contain "v2_"
```

Android builds go through Gradle in `android`: `:torrent` runs Cargo for each
ABI with the pinned NDK's clang as linker and copies the result in as
`libstream.so`. The NDK comes from the SDK:

```sh
sdkmanager --install "ndk;29.0.14206865"
```

## The BitTorrent implementation

- **Torrents**: v1, v2 and hybrid (BEP 3, 47, 52), with bounded parsing of
  torrent files and peer data. v2 pieces are checked against their file's
  piece layer (SHA-256 Merkle trees over 16 KiB blocks); hybrids by their v1
  hashes. Torrent files written back carry the piece layers known.
- **Sources**: torrent files, magnet links (`btih` in hex or base32, `btmh`,
  `tr`, `x.pe`) and bare info hashes. Metadata comes from peers by ut_metadata
  (BEP 9), checked against the info hash; a v2 torrent's piece layers come by
  hash requests (BEP 52) and are checked against each file's root.
- **Peers**: TCP and uTP (BEP 29, through `librqbit-utp`, on the listen port;
  tried when TCP can't connect, and accepted), message stream encryption
  (MSE/PE: 768-bit Diffie-Hellman and RC4, tried first on outgoing connections
  with a plaintext retry, both accepted incoming; `Forced` and `Disabled` are
  available), the fast extension (BEP 6), the extension protocol (BEP 10), peer
  exchange (BEP 11) and lt_donthave (BEP 54). A hybrid's peers may upgrade to v2
  in the handshake. Peers that send data failing its hash are banned. At most
  1 MiB of piece data waits per peer; further requests are rejected. Control
  replies are bounded separately; peers that ask without reading are disconnected,
  including when their writer is blocked.
- **Web seeds**: HTTP and HTTPS (BEP 19 `url-list`, and `ws=` in magnets). A
  seed downloads whole pieces no peer is fetching, by ranged GETs of the files
  that make them up, and its data is hash-checked like any peer's; a seed whose
  data fails three checks is dropped. Redirects are followed; a server that
  ignores ranges is refused rather than read whole.
- **Discovery**: HTTP, HTTPS and UDP trackers (BEP 3, 15), the DHT (BEP 5,
  IPv4), and local service discovery (BEP 14). HTTPS uses rustls with the trust
  roots the app writes from Android's store; certificates are always verified.
  `public_network_only` keeps peers, trackers and DHT nodes off loopback and
  private networks.
- **Not implemented**: BEP 17
  (Hoffman-style) seeds, the DHT over IPv6 (BEP 32), IPv6 local discovery, uTP
  on more than the first listen interface, BitTorrent v2 block-level hash
  requests after a failed piece (the piece is downloaded again whole), seeding to
  v2 peers that need piece layers for files we only know by their root, I2P,
  SOCKS/HTTP proxies, and SSL torrents.

## Compatibility and limitations

This is a streaming client with the parts of BitTorrent a TV player needs. It
does not aim at, and does not have, libtorrent's feature set or its years of
exposure to real swarms; nothing here should be read as parity with libtorrent
or any other client.

**Checked against independent implementations** (`native/eval/interop.sh`, loopback
only, generated content, results from October 2026):

| Direction | Passed | Failed as expected (the other side lacks it) |
| --- | --- | --- |
| Stream seeder -> librqbit 9.1.0 | v1 and hybrid torrents over TCP; v1 and hybrid magnets (metadata by BEP 9); v1 over uTP only; a multi-file hybrid | pure v2 (librqbit has no BEP 52) |
| librqbit 9.1.0 seeder -> Stream | v1 with MSE tried then plaintext, and with MSE off; v1 magnet; v1 over uTP only; hybrid torrent and btih+btmh magnet; the middle file of a multi-file hybrid | MSE forced (librqbit has no MSE/PE); pure v2 can't be seeded by librqbit |
| Node HTTP web seed -> Stream | v1, v2 and hybrid, single file and the middle file of multi-file torrents, no peers at all | |
| Node HTTPS web seed -> Stream | hybrid, with a locally generated CA given as the trust root | the same seed without that root is never used |

The expanded matrix also checks libtorrent 2.1.1.0, aria2 1.37.0 and
Transmission 4.1.4. libtorrent supplies an independent BEP 52 and MSE check:
pure-v2 and hybrid torrents, magnets with piece layers, single/multi-file
transfers, TCP/uTP and forced MSE are checked in both directions. aria2 and
Transmission cover v1/hybrid-as-v1 downloads in both directions, magnets and
MSE; Transmission also supplies an independent libutp seeder. Transmission
ignores loopback peers named by a tracker or PEX (its `is_martian_addr`) but
keeps those from its resume file, so `interop.sh` also writes Stream's address
into the leecher's resume file (`tr_resume_peer.py`) and requires every piece to
arrive on the connection Transmission itself opened: over TCP, over its libutp
uTP, with encryption required, for a hybrid torrent and for a magnet (metadata by
BEP 9 from Stream). Those rows found two engine defects, both fixed with
regressions: a uTP-only session ignored the configured listen port, and a peer
reconnecting from the endpoint of a connection not yet seen closed (Transmission
does so after a magnet's metadata) was refused instead of replacing it.

Caveats: librqbit's uTP is `librqbit-utp` 0.7.0, the same library the engine
uses. aria2 does not supply uTP or BEP 52, Transmission does not supply BEP 52,
and librqbit does not supply MSE or BEP 52. WebTorrent 3.0.21 passes v1 and
hybrid-as-v1 leecher checks, v1 and hybrid-as-v1 seeding and magnet metadata,
including Stream forcing MSE. It has no BEP 52: its pure-v2 row is an expected
failure only when WebTorrent rejects the torrent as such ("Invalid torrent
identifier"), passes if a later WebTorrent downloads the right bytes, and fails
otherwise; libtorrent supplies the independent pure-v2 check. Its native Node runtime dependency
must load before those optional rows run. The separately packaged npm
`http-server` supplies an additional HTTP range-server check in every torrent
version. Real public swarms are deliberately never used by these checks.

**Checked only against this repository's own code** (tests in `native/core/tests/`):
lt_donthave and eviction behaviour, hostile peers and dropped requests,
metadata recovery after corrupt or missing answers, bounded peer/control
queues, trackers, and concurrent cache/reader lifecycle behaviour.

**Scheduling, choking and seeding** (`native/core/tests/scheduling.rs`, `native/core/tests/super_seed.rs`, `native/core/src/bt/choker.rs`):

- Urgent pieces go to the peers that deliver them soonest: a peer leaves an urgent piece to one with the piece that
  serves blocks at least twice as fast (measured per block, not by average rate) and in time for the deadline, as
  libtorrent's time-critical picking does. An urgent block outstanding 2 s is also asked of a second peer, and with a
  lone peer it is re-sent after at least 5 s (three times its queue's drain time for a slow peer).
- Endgame covers every piece: once nothing new is left to ask a peer for, blocks outstanding for a second are asked
  of it too; whichever copy arrives first cancels the other.
- A peer that sends nothing for 20 s while holding urgent requests is disconnected and dialled again.
- Choking follows the mainline scheme: seven regular slots by the rate a peer sends to us (or, once nothing is
  wanted, the rate we send to it), snubbed peers (silent 60 s while we wait) without a regular slot, and one
  optimistic slot rotated every 30 s, three times as likely to go to a peer connected less than a minute.
- Super-seeding (BEP 16) is available to a full seed (`SeederOptions::super_seed`, `stream-fixture seed --super-seed`):
  each peer is shown one rare piece at a time, only shown pieces are served, and a piece seen at another peer brings
  the next. Two downloaders finish from such a seed while it uploads under one and a half copies (a plain seed sends
  two).

**Known limitations besides the list above**:

- Requests that drop from the active window to low/no priority are cancelled;
  partial pieces still finish later at the lowest priority when nothing more
  urgent is wanted. Cancellation cannot retract bytes already in flight.
- Streaming torrents never report themselves complete and never seed whole
  files; uploads are only of pieces currently in the cache. Super-seeding
  therefore applies to full seeds (the fixture seeder), not to the app.
- A slow lone peer still limits playback to its speed: no scheduling can make
  one peer faster than it is.
- The engine has passed the thirty-minute emulator soak; see
  [the soak investigation](soak-investigation.md) for the earlier libtorrent-era timeout, the eviction
  defect found, and what a failing soak now records. It has not been validated
  on a physical TV or across platforms.
- None of this is parity with libtorrent's feature set or its years of exposure
  to real swarms.

**Port mapping** is off by default in the engine (`Config::default()`, tested) and
the app, and is an explicit opt-in: Settings → Playback → Router port mapping.
With it on, and only outside local-only and public-network-only sessions, the app
passes the active network's default IPv4 gateway (private, link-local or loopback
addresses only). `native/core/src/bt/portmap.rs` maps the listen port for TCP and uTP with a
ten-minute lease, renews it at half the granted lease, announces a differing
external TCP port to trackers, the DHT and peers, and deletes its mappings when
the session closes:

1. **PCP** (RFC 6887, `natpmp.rs`): unicast MAP requests to the gateway's port
   5351, matched to their replies by nonce, opcode and port.
2. **NAT-PMP** (RFC 6886) when the gateway answers PCP with NAT-PMP's
   "unsupported version".
3. **UPnP IGD** (`upnp.rs`) when neither answers or the gateway refuses:
   discovery asks the gateway by unicast SSDP first, then by the standard
   multicast search; only answers from the gateway's address are accepted, and
   its description and control URLs must name that address, so no other device
   is contacted. WANIPConnection:2/1 or WANPPPConnection:1 via SOAP
   AddPortMapping/DeletePortMapping. A gateway that grants only permanent
   mappings (error 725) gets one, which is deleted on shutdown; a process that
   dies would leave that one behind until the router forgets it.

`native/core/tests/portmap.rs` checks each method against fake gateways on loopback (PCP
renewal and nonce-matched deletion, a NAT-PMP-only gateway, a refusing gateway,
UPnP leases, a permanent-only IGD, and a description pointing elsewhere); the
multicast search is skipped for a loopback gateway. Real routers have not been
tried. A streaming client mostly dials its peers, so mapping helps uploads and
reciprocity more than playback.

## How streaming works

1. A torrent is added with nothing to download. For a magnet link or info
   hash, its metadata comes from peers.
2. Choosing a file starts the read-ahead controller. From the most recent
   reader's position it keeps about 45 seconds of media (from the bitrate the app
   reports, bounded by the cache) at download priority; pieces a reader is
   waiting for, and the next two, get deadlines and may be asked of a second
   peer when the first is slow. Partial pieces that leave the window finish at
   the lowest priority, then become evictable. Nothing outside the window
   downloads.
3. A piece needs a cache slot before it may download. When none is free the
   least recently used verified pieces are evicted, except the window, the piece
   behind the reader, and (in caches of 8+ slots) the file's first and last
   pieces, where containers keep their headers and indexes.
4. Eviction makes the torrent forget the pieces: requests for them are rejected,
   peers supporting lt_donthave are told, and downloading peers without it are
   disconnected so they reconnect with a fresh bitfield; seeds stay. Readers
   stop using a piece before the torrent forgets it, and its slot is freed once
   no read pins it.
5. A reader waits until the piece it needs is verified, then copies from the
   cache. Missing data is waited for and downloaded: never zeros, never an early
   end of file.

The cache file is preallocated, so it never grows past `slots × piece length`.
Slot calculation leaves 64 KiB for filesystem metadata inside the configured
budget: Android's ext4 includes extent blocks in allocated-byte accounting.
Allocation failures are reported before payload writes. A streaming torrent
never announces itself as finished, so seeds keep it as a peer while the window
moves on.

The network runs on a small Tokio runtime per engine; its locks are never held
across an await or while hashing, and hashing runs on blocking threads.

## Testing

`./native/build-host.sh` runs the unit tests and `native/core/tests/` against real BitTorrent
seeders on loopback (DHT, local discovery, port mapping and trackers off unless
a test runs its own loopback tracker), with no internet or LAN:

- protocol units: bencoding limits, torrent and magnet parsing in every
  version, Merkle trees and hash-request answers, wire messages, the address
  policy, tracker responses and refusal of untrusted HTTPS, RC4 against RFC 6229
  and MSE handshakes in each negotiation;
- transports: forced MSE in v1, v2 and hybrid; plaintext refused by a seeder
  that requires MSE; MSE fallback to plaintext; uTP alone (with and without MSE,
  from magnets); HTTP web seeds for single- and multi-file torrents, a corrupt
  web seed failing its hashes while peers finish the file, and an HTTPS web
  seed refused until its certificate is trusted;
- hostile peers: a dropped urgent request retried, abandoned window requests
  cancelled, corrupt/stalled metadata retried with another peer, unsolicited
  metadata unable to cancel requests or refresh progress, bounded queues, and
  torrent removal while a writer is blocked;
- cache units: slots, reservation, pads, waits and cancellation, 64-bit offsets;
- eviction: v1, v2 and hybrid seeds re-download evicted pieces; a finished
  torrent stays finished until it wants a piece; a peer learns by lt_donthave,
  stays connected and has requests for evicted pieces rejected; downloading
  peers without lt_donthave are disconnected and seeds are not;
- seek stress (`seek_stress.rs`): dozens of seeks through a 15-slot cache with
  readers reopened as Media3 does, every byte checked, each seek within the
  soak's 30 s; an ignored 200-seek run at the simulator's 4 MiB/s
  (`./native/build-host.sh release --ignored soak_shaped`); and a reopened
  reader leaving the read-ahead window where it was until it reads;
- streaming through the engine: several cache turnovers within the bound,
  rewind into evicted data, seeks, multi-file v1/v2/hybrid torrents, metadata
  from magnets and info hashes (v2 piece layers included), an HTTP tracker,
  timeouts, cancellation, shutdown during transfers, corrupt peers, refusals,
  offsets beyond 4 GiB, and concurrent readers.

Interoperability with independent implementations is checked by
`native/eval/interop.sh` (see above; not part of `./native/build-host.sh`): it builds
`stream-fixture`, `native/core/examples/fetch.rs` and the librqbit peer in `native/eval/rqbit`
(`CARGO_OFFLINE=1` uses only the local Cargo cache), then runs each direction on
127.0.0.1. `native/eval/setup-tools.sh <directory-outside-repository>` optionally
installs the independent clients; `STREAM_INTEROP_TOOLS=<directory>` enables
them. They are evaluation tools and are never linked into the app. Unavailable
optional clients are reported as skipped. `STREAM_INTEROP_EVIDENCE=<directory>`
retains per-row and build logs, including on failure; this can point into the
ignored `simulator/.cache/evidence/` tree. `STREAM_INTEROP_CLIENTS` selects
space-separated groups (`rqbit webseed libtorrent aria2 transmission webtorrent
http-server`) for focused follow-ups. Every selected transfer still checks the
complete file's SHA-256; forced transport/encryption rows also check the peer's
recorded connection mode where that client exposes it. The libtorrent helper
waits for its disk-cache flush before reading back the file: progress counters
alone can become complete before the asynchronous writes reach disk.

On the emulator, `./simulator.sh test` runs the same engine through JNI and
Media3 against the host's `stream-fixture` seeder. The seeder is limited to
4 MiB/s, so the short generated film can prove first-frame playback before its
full payload arrives even on fast loopback. Runner output is retained in the
ignored `simulator/.cache/evidence/` directory.
