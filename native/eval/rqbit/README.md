# librqbit evaluation

Can [librqbit](https://github.com/ikatson/rqbit) replace libtorrent as the
engine's BitTorrent backend? This crate downloads each film fixture from the
local loopback seeder only (no DHT, trackers or LAN discovery) and compares the
bytes with the seeder's source file. It is never part of the app.

```sh
./simulator.sh services
(cd native/eval/rqbit && cargo run --release -- ../../../simulator/.cache)
```

## Result with librqbit 9.1.0 (October 2026)

| Requirement | Result |
| --- | --- |
| v1 torrent (`film-v1`) | Pass: bytes match |
| Hybrid v1+v2 torrent (`film-hybrid`) | Pass, through its v1 piece hashes |
| Pure v2 torrent (`film-v2`) | Fail: refused while parsing (`missing field pieces`). BEP 52 support upstream is error types only, with no Merkle verification ([issue 546](https://github.com/ikatson/rqbit/issues/546)) |
| Piece eviction: forget a verified piece, re-download it, tell peers (BEP 54) | Missing: no API to forget a piece; storage is per-file `pread`/`pwrite`, not per piece |

librqbit does not meet the engine's requirements, so libtorrent stays. Rerun this
evaluation when librqbit ships v2 verification; eviction would still need a
patch of its piece tracking, like `patches/libtorrent`.
