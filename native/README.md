# Stream streaming engine

Rust code that lets the TV play a torrent while it downloads, keeping only a
bounded window of it on disk. It is its own BitTorrent implementation: there is
no C++ and no libtorrent. Built into `libstream.so` for the app (through
`android/torrent`) and into the `stream-fixture` tool and the tests for this
computer.

| Path | Role |
| --- | --- |
| `core/src/engine.rs` | Torrents, readers, the read-ahead window controller, eviction, errors and statistics, over a pluggable backend (`swarm.rs`) |
| `core/src/cache.rs` | The piece cache: one preallocated file per torrent, divided into piece-sized slots; pins, verification, LRU eviction candidates |
| `core/src/bt/` | The BitTorrent backend: wire protocol, peers, piece picker, hashing, uploads, metadata and piece-layer exchange, trackers, DHT, local discovery |
| `core/src/api.rs`, `core/src/jni_bridge.rs` | The engine handles and `tv.stream.torrent.TorrentEngine`'s native methods |
| `core/src/fixtures.rs`, `core/src/bin/stream-fixture.rs` | Reproducible content, torrents and a real loopback seeder for the tests and the simulator |
| `core/tests/` | Integration tests against real seeders on loopback |
| `core/examples/fetch.rs` | Streams a file from given peers and prints its SHA-256, for checks against other BitTorrent implementations |
| `core/notices.py` | Writes the app's notice for the crates linked into `libstream.so` |
| `eval/rqbit/` | Evaluation of librqbit against the fixtures; never shipped (see its README) |

## Building

The toolchain is pinned in `core/rust-toolchain.toml` (Rust 1.92.0 and the
three Android targets, which rustup installs on first use) and every crate in
`core/Cargo.lock`; builds use `--locked`.

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
  1 MiB of piece data waits per peer; further requests are rejected.
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
- **Not implemented**: port mapping (UPnP/NAT-PMP; the app never enables it and
  the engine logs that it stays off), BEP 17 (Hoffman-style) seeds, the DHT over
  IPv6 (BEP 32), IPv6 local discovery, uTP on more than the first listen
  interface, BitTorrent v2 block-level hash requests after a failed piece (the
  piece is downloaded again whole), seeding to v2 peers that need piece layers
  for files we only know by their root, I2P, SOCKS/HTTP proxies, and SSL
  torrents. This is not libtorrent feature parity.

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

`build-host.sh` runs the unit tests and `core/tests/` against real BitTorrent
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
- cache units: slots, reservation, pads, waits and cancellation, 64-bit offsets;
- eviction: v1, v2 and hybrid seeds re-download evicted pieces; a finished
  torrent stays finished until it wants a piece; a peer learns by lt_donthave,
  stays connected and has requests for evicted pieces rejected; downloading
  peers without lt_donthave are disconnected and seeds are not;
- streaming through the engine: several cache turnovers within the bound,
  rewind into evicted data, seeks, multi-file v1/v2/hybrid torrents, metadata
  from magnets and info hashes (v2 piece layers included), an HTTP tracker,
  timeouts, cancellation, shutdown during transfers, corrupt peers, refusals,
  offsets beyond 4 GiB, and concurrent readers.

Interoperability with other implementations is checked by hand: `core/examples/fetch.rs`
streams the simulator's libtorrent-made v1, v2 and hybrid torrents (and their
magnets) from a libtorrent seeder, also over uTP only (`STREAM_TCP=0`) and with
MSE forced (`STREAM_ENCRYPTION=forced`); `eval/rqbit` downloads them from
`stream-fixture seed`.

On the emulator, `./simulator.sh test` runs the same engine through JNI and
Media3 against the host's `stream-fixture` seeder. The seeder is limited to
4 MiB/s, so the short generated film can prove first-frame playback before its
full payload arrives even on fast loopback. Runner output is retained in the
ignored `simulator/.cache/evidence/` directory.
