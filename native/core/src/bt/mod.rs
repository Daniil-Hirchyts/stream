//! Stream's own BitTorrent implementation behind [`crate::swarm::Swarm`]: v1, v2 and hybrid torrents (BEP 3/52),
//! magnet links and metadata exchange (BEP 9), peer exchange (BEP 11), the fast extension (BEP 6), lt_donthave
//! (BEP 54), HTTP/HTTPS/UDP trackers (BEP 3/15), the DHT (BEP 5) and local service discovery (BEP 14).

pub mod backend;
pub mod bencode;
pub mod dht;
pub mod lsd;
pub mod merkle;
pub mod metainfo;
pub mod mse;
pub mod net;
pub mod peer;
pub mod session;
pub mod storage;
pub mod torrent;
pub mod tracker;
pub mod wire;
