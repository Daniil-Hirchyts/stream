# Third-party code in the streaming engine

Everything below is compiled into `libdaniiltv.so` (and the host tools) from the
sources pinned in [`deps.lock`](deps.lock). `fetch-deps.sh` verifies each
archive's SHA-256 against upstream's published value, or each repository's
commit, before anything is built. Nothing is downloaded at runtime.

| Component | Version | Pin | License | License text |
| --- | --- | --- | --- | --- |
| libtorrent (rasterbar) | 2.1.2 | commit `6da363d2994f17c0b3c0450d124cf73a31a73847` | BSD-3-Clause, with bundled parts under zlib-style, Boost and public-domain terms | `libtorrent/COPYING`, `libtorrent/LICENSE` |
| try_signal (libtorrent submodule) | at libtorrent's recorded commit `105cce59972f925a33aa6b1c3109e4cd3caf583d` | — | BSD-3-Clause | `libtorrent/deps/try_signal/LICENSE` |
| ed25519 (in libtorrent) | — | — | Public domain | `libtorrent/src/ed25519/LICENSE` |
| Boost (headers only) | 1.90.0 | SHA-256 `5e93d582aff26868d581a52ae78c7d8edf3f3064742c6e77901a1f18a437eea9` | BSL-1.0 | `boost/LICENSE_1_0.txt` |
| OpenSSL (libssl, libcrypto) | 3.5.9 | SHA-256 `603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a` | Apache-2.0 | `openssl/LICENSE.txt` |

Paths are relative to `.deps/`. Build tools, not shipped: Android NDK
29.0.14206865 and CMake 3.31.6, installed through `sdkmanager`.

## Local changes

`patches/libtorrent/0001-evict-pieces.patch` adds piece eviction
(`torrent_handle::evict_pieces`); its header explains the change. It is applied
to a clean checkout by `fetch-deps.sh`, and the build refuses a libtorrent
without it. Moving to another libtorrent version means re-porting the patch and
passing the regressions in `tests/test_eviction.cpp` under every
`build-host.sh` variant first.

## Distribution

An app shipping `libdaniiltv.so` must show these licenses and copyright
notices; the Daniil TV app bundles them in its licenses screen.
