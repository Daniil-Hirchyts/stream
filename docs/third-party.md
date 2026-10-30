# Third-party code in the streaming engine

`libstream.so` is Stream's own Rust code (GPL-3.0-or-later) built with crates from
crates.io, each pinned with its checksum in [`native/core/Cargo.lock`](../native/core/Cargo.lock)
and built with `--locked`. Nothing is downloaded at runtime.

The main ones:

| Crate | Role | License |
| --- | --- | --- |
| tokio, mio, socket2 | The network runtime and sockets | MIT; MIT OR Apache-2.0 |
| rustls, tokio-rustls, rustls-webpki, rustls-pki-types | TLS for HTTPS trackers and web seeds, always verifying certificates | Apache-2.0 OR ISC OR MIT; ISC; MIT OR Apache-2.0 |
| ring | Cryptography under rustls (includes C and assembly, built with the NDK's clang) | Apache-2.0 AND ISC |
| webpki-roots | Mozilla's roots, used only when the app gives no trust roots | CDLA-Permissive-2.0 |
| sha1, sha2 | Piece and info hashes | MIT OR Apache-2.0 |
| librqbit-utp | uTP (BEP 29) | Apache-2.0 |
| num-bigint | MSE's Diffie-Hellman exchange | MIT OR Apache-2.0 |
| jni | The Java bridge | MIT OR Apache-2.0 |
| libc, getrandom | System calls and random numbers | MIT OR Apache-2.0 |

`native/core/notices.py` lists every crate linked into the Android library (normal
dependencies only: build scripts and procedural macros don't ship) with its
license and license files, writing
`android/app/src/main/assets/notices/streaming-engine.txt`. Run it after any
change to `Cargo.lock`.

Build tools, not shipped: the Rust toolchain pinned in
`native/core/rust-toolchain.toml`, and Android NDK 29.0.14206865 installed through
`sdkmanager`.

## Distribution

An app shipping `libstream.so` must show these licenses and copyright notices;
the Stream app bundles the generated notice in its open source notices screen.
