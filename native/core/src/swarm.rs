//! The BitTorrent backend the engine drives. The engine owns policy (which pieces, when, what to evict) and every
//! handle; a backend only speaks the protocol and writes verified-or-not pieces into the torrent's [`PieceCache`].
//! [`crate::bt`] implements it; the engine's tests use an in-memory fake.

use crate::cache::PieceCache;
use crate::engine::{EngineError, ErrorKind};
use std::net::SocketAddr;
use std::sync::Arc;

/// Piece priorities, numbered as BitTorrent clients commonly do (0 skips a piece, 7 is the most urgent).
pub const DONT_DOWNLOAD: u8 = 0;
pub const LOW_PRIORITY: u8 = 1;
pub const DEFAULT_PRIORITY: u8 = 4;
pub const TOP_PRIORITY: u8 = 7;

/// Where a torrent comes from: exactly one of metainfo, magnet or info hash, plus optional peers to contact.
#[derive(Clone, Debug, Default)]
pub struct Source {
    pub metainfo: Vec<u8>,
    pub magnet: String,
    /// 40 hex digits (v1) or 64 (v2).
    pub info_hash: String,
    pub trackers: Vec<String>,
    /// Only explicitly public raw info hashes may use these when no trackers exist.
    pub fallback_trackers: Vec<String>,
    /// "address:port", contacted directly.
    pub peers: Vec<String>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FileEntry {
    pub path: String,
    pub size: i64,
    /// Offset in the torrent's piece space, pad files included.
    pub offset: i64,
    pub pad: bool,
}

/// A torrent's layout once its metadata is known. Immutable.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Metadata {
    pub piece_length: i32,
    pub piece_sizes: Vec<i32>,
    pub files: Vec<FileEntry>,
}

impl Metadata {
    pub fn num_pieces(&self) -> i32 {
        self.piece_sizes.len() as i32
    }
    pub fn piece_size(&self, p: i32) -> i32 {
        self.piece_sizes.get(p as usize).copied().unwrap_or(0)
    }
    /// A real (non-pad) file at this index.
    pub fn file(&self, index: i32) -> Option<&FileEntry> {
        usize::try_from(index).ok().and_then(|i| self.files.get(i)).filter(|f| !f.pad)
    }
    /// The piece and offset within it of a byte of a file.
    pub fn map_file(&self, file: &FileEntry, position: i64) -> (i32, i32) {
        let absolute = file.offset + position;
        ((absolute / i64::from(self.piece_length)) as i32, (absolute % i64::from(self.piece_length)) as i32)
    }
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct TorrentStatus {
    pub peers: i32,
    pub seeds: i32,
    pub download_rate: i32,
    pub upload_rate: i32,
    pub total_download: i64,
    pub total_upload: i64,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
#[repr(C)]
pub struct DiskStats {
    pub buffer_bytes: i64,
    pub queued_write_bytes: i64,
    pub blocked_writes: i32,
}

/// What happened in the swarm since the last poll. `tag` is the engine's torrent handle given to [`Swarm::add`].
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Event {
    /// Diagnostics for the Developer view: peer disconnections and errors.
    Log(String),
    MetadataReceived { tag: u64 },
    PieceFinished { tag: u64, piece: i32 },
    HashFailed { tag: u64 },
    MetadataFailed { tag: u64, message: String },
    TorrentError { tag: u64, message: String },
    FileError { tag: u64, message: String },
    Removed { tag: u64 },
}

/// Called once the torrent no longer has the pieces, with those it actually evicted. Their slots are released
/// after every disk job queued before then; the engine undoes the release for the others.
pub type EvictDone = Box<dyn FnOnce(Vec<i32>) + Send>;

/// Checks a known piece length before the torrent is added (a magnet's is checked once its metadata arrives).
pub type PieceLengthCheck<'a> = &'a dyn Fn(i32) -> Result<(), EngineError>;

pub trait Swarm: Send + Sync {
    /// Starts a torrent with nothing to download until the engine prioritizes pieces.
    fn add(&self, source: &Source, tag: u64, check: PieceLengthCheck<'_>) -> Result<Box<dyn SwarmTorrent>, EngineError>;
    fn poll(&self, out: &mut Vec<Event>);
    fn disk_stats(&self) -> DiskStats;
    fn version(&self) -> String;
}

pub trait SwarmTorrent: Send + Sync {
    fn metadata(&self) -> Option<Arc<Metadata>>;
    /// The torrent's cache, which exists once the backend created its storage (after metadata).
    fn cache(&self) -> Option<Arc<PieceCache>>;
    /// One priority per piece.
    fn prioritize_pieces(&self, priorities: &[u8]);
    fn clear_piece_deadlines(&self);
    fn set_piece_deadline(&self, piece: i32, milliseconds: i32);
    /// New priorities and/or deadlines (piece, milliseconds), replacing all earlier deadlines, applied together: a
    /// peer must not be handed a soon-urgent piece as an ordinary one between the two.
    fn update_window(&self, priorities: Option<&[u8]>, deadlines: Option<&[(i32, i32)]>) {
        if let Some(d) = deadlines {
            self.clear_piece_deadlines();
            for &(p, ms) in d {
                self.set_piece_deadline(p, ms);
            }
        }
        if let Some(p) = priorities {
            self.prioritize_pieces(p);
        }
    }
    fn have_piece(&self, piece: i32) -> bool;
    fn evict_pieces(&self, pieces: Vec<i32>, done: EvictDone);
    fn connect_peer(&self, address: SocketAddr);
    fn status(&self) -> Option<TorrentStatus>;
    /// The torrent file, with its trackers, for re-adding the same torrent later.
    fn metainfo(&self) -> Result<Vec<u8>, EngineError>;
    fn pause(&self);
    /// Starts removing the torrent from its swarm; [`Event::Removed`] follows.
    fn remove(&self);
}

/// "address:port" or "[v6]:port".
pub fn parse_peer(text: &str) -> Result<SocketAddr, EngineError> {
    let colon = text
        .rfind(':')
        .ok_or_else(|| EngineError::new(ErrorKind::BadSource, format!("Peer without a port: {text}")))?;
    let host = &text[..colon];
    let host = host.strip_prefix('[').and_then(|h| h.strip_suffix(']')).unwrap_or(host);
    let invalid = || EngineError::new(ErrorKind::BadSource, format!("Invalid peer: {text}"));
    let address: std::net::IpAddr = host.parse().map_err(|_| invalid())?;
    let port: u16 = text[colon + 1..].parse().map_err(|_| invalid())?;
    if port == 0 {
        return Err(invalid());
    }
    Ok(SocketAddr::new(address, port))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn peers_parse_with_brackets_and_reject_bad_ports() {
        assert_eq!(parse_peer("127.0.0.1:58134").unwrap().port(), 58134);
        assert!(parse_peer("[::1]:6881").unwrap().is_ipv6());
        assert_eq!(parse_peer("127.0.0.1").unwrap_err().kind, ErrorKind::BadSource);
        assert!(parse_peer("127.0.0.1:0").is_err());
        assert!(parse_peer("127.0.0.1:70000").is_err());
        assert!(parse_peer("example.org:80").is_err());
    }

    #[test]
    fn file_positions_map_through_pad_aligned_offsets() {
        let meta = Metadata {
            piece_length: 16,
            piece_sizes: vec![16, 16, 16, 4],
            files: vec![
                FileEntry { path: "a".into(), size: 10, offset: 0, pad: false },
                FileEntry { path: ".pad/6".into(), size: 6, offset: 10, pad: true },
                FileEntry { path: "b".into(), size: 36, offset: 16, pad: false },
            ],
        };
        assert!(meta.file(1).is_none(), "pad files are not files");
        let b = meta.file(2).unwrap();
        assert_eq!(meta.map_file(b, 0), (1, 0));
        assert_eq!(meta.map_file(b, 35), (3, 3));
    }
}
