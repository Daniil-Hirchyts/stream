//! The engine's [`Swarm`] over Stream's own BitTorrent session.

use super::metainfo::{self, InfoHashes, Layers};
use super::session::{Session, SessionConfig};
use super::torrent::Torrent;
use crate::cache::PieceCache;
use crate::engine::{Config, EngineError, ErrorKind, Result, Wake};
use crate::swarm::{parse_peer, DiskStats, Event, EvictDone, Metadata, PieceLengthCheck, Source, Swarm, SwarmTorrent, TorrentStatus};
use std::net::SocketAddr;
use std::sync::atomic::Ordering;
use std::sync::Arc;

pub const VERSION: &str = concat!("Stream BitTorrent ", env!("CARGO_PKG_VERSION"), " (Rust)");

pub struct RustSwarm {
    session: Arc<Session>,
    cache_dir: String,
    cache_bytes: i64,
}

impl RustSwarm {
    pub fn create(config: &Config, wake: Arc<Wake>) -> Result<RustSwarm> {
        if config.upnp || config.natpmp {
            // Port mapping is not implemented; the app never enables it.
            if let Some(log) = &config.log {
                log("port mapping (UPnP/NAT-PMP) is not supported and stays off");
            }
        }
        let session = Session::new(
            SessionConfig {
                listen: config.listen_interfaces.clone(),
                connections_limit: config.connections_limit.max(1) as usize,
                multiple_per_ip: config.multiple_connections_per_ip,
                public_only: config.public_network_only,
                dht: config.dht,
                lsd: config.lsd,
                pex: config.pex,
                user_agent: config.user_agent.clone(),
                ca_file: config.ca_file.clone(),
                upload_rate: 0,
                log: config.log.is_some(),
            },
            Some(Arc::new(move || wake.wake())),
        )
        .map_err(|e| EngineError::new(ErrorKind::EngineFailed, e))?;
        Ok(RustSwarm { session: Arc::new(session), cache_dir: config.cache_dir.clone(), cache_bytes: config.cache_bytes })
    }
}

fn bad_source(e: impl std::fmt::Display) -> EngineError {
    EngineError::new(ErrorKind::BadSource, format!("Invalid torrent: {e}"))
}

impl Swarm for RustSwarm {
    fn add(&self, source: &Source, tag: u64, check: PieceLengthCheck<'_>) -> Result<Box<dyn SwarmTorrent>> {
        let mut trackers: Vec<String> = Vec::new();
        let mut peers: Vec<SocketAddr> = Vec::new();
        let (hashes, info, layers) = if !source.metainfo.is_empty() {
            let t = metainfo::parse_torrent(&source.metainfo).map_err(bad_source)?;
            check(t.info.piece_length)?;
            trackers = t.trackers;
            (t.info.hashes, Some(t.info), t.layers)
        } else if !source.magnet.is_empty() {
            let m = metainfo::parse_magnet(&source.magnet).map_err(bad_source)?;
            trackers = m.trackers;
            for p in &m.peers {
                if let Ok(a) = parse_peer(p) {
                    peers.push(a);
                }
            }
            (m.hashes, None, Layers::new())
        } else if !source.info_hash.is_empty() {
            let h = InfoHashes::parse_hex(&source.info_hash).map_err(|e| EngineError::new(ErrorKind::BadSource, e.0))?;
            (h, None, Layers::new())
        } else {
            return Err(EngineError::new(ErrorKind::BadSource, "No torrent, magnet link or info hash"));
        };
        for url in &source.trackers {
            if !trackers.contains(url) {
                trackers.push(url.clone());
            }
        }
        // Only a bare public info hash may use the fallback trackers.
        if !source.info_hash.is_empty() && source.metainfo.is_empty() && source.magnet.is_empty() && trackers.is_empty() {
            trackers.extend(source.fallback_trackers.iter().cloned());
        }
        for p in &source.peers {
            peers.push(parse_peer(p)?);
        }
        let t = self.session.add(tag, hashes, info, layers, trackers, peers);
        t.use_cache(&self.cache_dir, self.cache_bytes);
        Ok(Box::new(RustTorrent { torrent: t, session: self.session.clone() }))
    }

    fn poll(&self, out: &mut Vec<Event>) {
        self.session.shared.drain(out);
    }

    fn disk_stats(&self) -> DiskStats {
        // Blocks that found no slot since the last look; the engine frees one more slot while there are any.
        DiskStats { buffer_bytes: 0, queued_write_bytes: 0, blocked_writes: self.session.shared.blocked_writes.swap(0, Ordering::Relaxed) }
    }

    fn version(&self) -> String {
        VERSION.into()
    }
}

pub struct RustTorrent {
    torrent: Arc<Torrent>,
    session: Arc<Session>,
}

impl SwarmTorrent for RustTorrent {
    fn metadata(&self) -> Option<Arc<Metadata>> {
        self.torrent.metadata()
    }
    fn cache(&self) -> Option<Arc<PieceCache>> {
        self.torrent.cache()
    }
    fn prioritize_pieces(&self, priorities: &[u8]) {
        self.torrent.prioritize(priorities);
    }
    fn clear_piece_deadlines(&self) {
        self.torrent.clear_deadlines();
    }
    fn set_piece_deadline(&self, piece: i32, milliseconds: i32) {
        self.torrent.set_deadline(piece, milliseconds);
    }
    fn have_piece(&self, piece: i32) -> bool {
        self.torrent.have_piece(piece)
    }
    fn evict_pieces(&self, pieces: Vec<i32>, done: EvictDone) {
        self.torrent.evict(pieces, done);
    }
    fn connect_peer(&self, address: SocketAddr) {
        self.torrent.add_candidates([address]);
    }
    fn status(&self) -> Option<TorrentStatus> {
        Some(self.torrent.status())
    }
    fn metainfo(&self) -> Result<Vec<u8>> {
        let bytes = self
            .torrent
            .metainfo()
            .ok_or_else(|| EngineError::new(ErrorKind::MetadataFailed, "Metadata is not available"))?;
        if bytes.len() > metainfo::MAX_METADATA {
            return Err(EngineError::new(ErrorKind::BadSource, "Metadata is too large"));
        }
        Ok(bytes)
    }
    fn pause(&self) {
        self.torrent.pause();
    }
    fn remove(&self) {
        self.session.remove(&self.torrent);
    }
}
