//! Stream's streaming engine: a BitTorrent client that streams through a bounded cache, in Rust.
//!
//! - [`cache`]: the bounded piece cache every torrent downloads into.
//! - [`engine`]: torrents, readers, the read-ahead window and eviction, over a [`swarm::Swarm`] backend.
//! - [`swarm`]: the backend interface; [`bt`] implements it with Stream's own BitTorrent session.
//! - [`api`]: the registry of engines behind the app's handles; `jni_bridge`: `tv.stream.torrent.TorrentEngine`'s
//!   native methods.
//! - [`fixtures`]: reproducible content, torrents and loopback seeders for tests and the simulator (host only).

pub mod api;
pub mod bt;
pub mod cache;
pub mod engine;
#[cfg(not(target_os = "android"))]
pub mod fixtures;
mod jni_bridge;
pub mod swarm;
