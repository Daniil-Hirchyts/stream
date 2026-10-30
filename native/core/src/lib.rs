//! Stream's streaming engine core.
//!
//! - [`cache`]: the bounded piece cache every torrent downloads into.
//! - [`engine`]: torrents, readers, the read-ahead window and eviction, over a [`swarm::Swarm`] backend.
//! - [`swarm`]: the BitTorrent backend interface; [`libtorrent`] implements it through the C++ shim.
//! - [`api`]: the handle-based C ABI; [`cache_ffi`]: the cache's C ABI for libtorrent's disk subsystem.
//! - `jni`: `tv.stream.torrent.TorrentEngine`'s native methods.

pub mod api;
pub mod cache;
pub mod cache_ffi;
pub mod engine;
#[cfg(feature = "libtorrent")]
mod jni_bridge;
#[cfg(feature = "libtorrent")]
pub mod libtorrent;
pub mod swarm;
