//! One torrent's swarm: which pieces we have and want, the piece picker, every connected peer's state, hashing,
//! eviction, uploads, and fetching metadata (BEP 9) and v2 piece layers (BEP 52) from peers.
//!
//! Peer tasks and the engine thread share [`State`] behind one mutex that is never held across an await or while
//! hashing; messages to peers go through their writer channels.

use super::merkle::{self, Hash};
use super::metainfo::{self, check_layer, Id20, Info, InfoHashes, Layers, Version, MAX_METADATA};
use super::session::SessionShared;
use super::storage::Storage;
use super::wire::{self, Bitfield, BlockRef, ExtHandshake, HashRequest, Message, MetadataMessage, Reserved};
use crate::cache::PieceCache;
use crate::swarm::{EvictDone, Event, Metadata, TorrentStatus, DONT_DOWNLOAD, LOW_PRIORITY};
use std::collections::{HashMap, HashSet, VecDeque};
use std::net::{IpAddr, SocketAddr};
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex, MutexGuard, Weak};
use std::time::{Duration, Instant};
use tokio::sync::{mpsc, Notify};

pub type PeerKey = u64;

/// To a peer's writer.
pub enum Out {
    /// A message, and what it counts for in [`PeerLink::control`] until it is written.
    Msg(Message, usize),
    /// Piece data and its size, sent through the session's upload limit; the size leaves the peer's queue once sent.
    Upload(Message, usize),
}

/// What a connection's reader, its writer and the torrent share.
#[derive(Default)]
pub struct PeerLink {
    /// Piece data read for the peer and not yet sent.
    pub upload: AtomicUsize,
    /// Everything else queued for the peer and not yet written.
    pub control: AtomicUsize,
    /// Ends the connection at once, even with the writer stuck on a peer that stopped reading.
    pub closed: Notify,
}

const MAX_REQUESTS: usize = 250;
const MIN_REQUESTS: usize = 8;
const REQUEST_TIMEOUT: Duration = Duration::from_secs(30);
/// An urgent block another peer was asked for this long ago may be asked of a second peer.
const URGENT_DUPLICATE: Duration = Duration::from_secs(2);
/// An urgent block unanswered for this long, and well past what the peer's queue explains, is asked of the same peer
/// again: a request lost without a reject must not stall a lone seed's stream until [REQUEST_TIMEOUT].
const URGENT_RETRY: Duration = Duration::from_secs(5);
const MAX_UNCHOKED: usize = 8;
const RECONNECT: Duration = Duration::from_secs(5);
const MAX_CANDIDATES: usize = 1000;
const LAYER_CHUNK: usize = 512;
/// A metadata fetch with no new piece for this long gives up on the peers it was asking.
const METADATA_STALL: Duration = Duration::from_secs(10);
/// `have` messages kept from a peer before the metadata is known; further ones are ignored.
const MAX_EARLY_HAVES: usize = 1 << 16;
/// The key web seeds download under; real peers' keys start at 1.
pub const WEB_SEED: PeerKey = 0;
/// A web seed whose data fails this many hash checks is no longer used.
const WEB_SEED_FAILURES: u32 = 3;
/// Piece data read for a peer and not yet sent. Requests beyond it wait unread, up to [MAX_WAITING_REQUESTS] (the
/// `reqq` we advertise), and are served as the writer drains; beyond that they are rejected. This bounds memory per
/// slow or rate-limited peer without ever dropping a request silently.
const MAX_QUEUED_UPLOAD: usize = 1 << 20;
const MAX_WAITING_REQUESTS: usize = 500;
/// Rejects, metadata and hash answers wait in a peer's queue like any message. A peer that keeps asking while this
/// much is unwritten isn't reading what it asked for, and is disconnected.
const MAX_QUEUED_CONTROL: usize = 4 << 20;

/// Rolling transfer rate over the last five seconds.
#[derive(Default)]
pub struct Rate {
    total: i64,
    last_total: i64,
    samples: VecDeque<i64>,
}

impl Rate {
    pub fn add(&mut self, bytes: usize) {
        self.total += bytes as i64;
    }
    fn tick(&mut self) {
        self.samples.push_back(self.total - self.last_total);
        self.last_total = self.total;
        if self.samples.len() > 5 {
            self.samples.pop_front();
        }
    }
    pub fn per_second(&self) -> i64 {
        if self.samples.is_empty() {
            return 0;
        }
        self.samples.iter().sum::<i64>() / self.samples.len() as i64
    }
}

pub struct Peer {
    pub addr: SocketAddr,
    tx: mpsc::UnboundedSender<Out>,
    link: Arc<PeerLink>,
    waiting: VecDeque<BlockRef>,
    pub reserved: Reserved,
    /// The connection uses the v2 info hash, so v2 hash messages are allowed (BEP 52).
    pub v2: bool,
    pub ext: ExtHandshake,
    pub ext_received: bool,
    have: Bitfield,
    /// A bitfield or have-all that arrived before the metadata.
    early_bitfield: Option<Vec<u8>>,
    early_have_all: bool,
    early_haves: Vec<u32>,
    pub peer_choking: bool,
    pub peer_interested: bool,
    pub am_choking: bool,
    pub am_interested: bool,
    requests: Vec<Request>,
    metadata_requests: usize,
    /// How many metadata fetches this peer contributed to that failed, or that it left without data.
    metadata_failures: u32,
    layer_requests: usize,
    pub down: Rate,
    pub up: Rate,
    pex_sent: HashSet<SocketAddr>,
    last_pex: Option<Instant>,
    pub incoming: bool,
    hash_failures: u32,
}

/// A block asked of a peer.
#[derive(Clone, Copy)]
struct Request {
    block: BlockRef,
    /// When it was first asked; it times out from here.
    at: Instant,
    /// When it was last sent.
    sent: Instant,
}

/// Roughly what a queued message occupies; the small fixed part also bounds a flood of tiny messages.
fn queue_cost(m: &Message) -> usize {
    64 + match m {
        Message::Bitfield(b) => b.len(),
        Message::Extended { payload, .. } => payload.len(),
        Message::Hashes { hashes, .. } => hashes.len() * 32,
        _ => 0,
    }
}

impl Peer {
    fn send(&self, m: Message) {
        let cost = queue_cost(&m);
        self.link.control.fetch_add(cost, Ordering::SeqCst);
        let _ = self.tx.send(Out::Msg(m, cost));
    }
    /// Ends the connection without waiting for what is queued: its writer may be stuck on a peer that stopped reading.
    fn close(&self) {
        self.link.closed.notify_one();
    }
    pub fn is_seed(&self) -> bool {
        (!self.have.is_empty() && self.have.all()) || self.early_have_all
    }
    /// The address it accepts connections on, for peer exchange.
    fn listen_addr(&self) -> Option<SocketAddr> {
        if !self.incoming {
            return Some(self.addr);
        }
        self.ext.listen_port.map(|p| SocketAddr::new(self.addr.ip(), p))
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Block {
    Missing,
    Requested { first: PeerKey, second: Option<PeerKey>, at: Instant },
    Received,
}

struct Progress {
    blocks: Vec<Block>,
    contributors: HashSet<PeerKey>,
}

struct MetadataFetch {
    size: usize,
    data: Vec<u8>,
    received: Vec<bool>,
    requested: Vec<Option<(PeerKey, Instant)>>,
    /// Who sent each piece, to know whom to doubt if the result fails its hash.
    senders: HashSet<PeerKey>,
    /// After a failed fetch only one peer is asked at a time, so a bad one can't spoil the next attempt as well.
    only: Option<PeerKey>,
    progress: Instant,
}

struct LayerFetch {
    file: usize,
    root: Hash,
    count: usize,
    chunk: usize,
    chunks: Vec<Option<Vec<Hash>>>,
    requested: Vec<Option<(PeerKey, Instant)>>,
}

struct Candidate {
    next_try: Instant,
    failures: u32,
    connected: bool,
    connecting: bool,
}

pub struct State {
    pub hashes: InfoHashes,
    pub info: Option<Arc<Info>>,
    metadata: Option<Arc<Metadata>>,
    pub layers: Layers,
    storage: Option<Arc<Storage>>,
    /// The streaming cache is made on first use, after the engine accepted the piece length.
    cache_dir: Option<String>,
    cache_bytes: i64,
    pub trackers: Vec<String>,
    pub web_seeds: Vec<String>,
    web_seed_hash_failures: u32,
    pub have: Bitfield,
    priorities: Vec<u8>,
    deadlines: HashMap<i32, Instant>,
    /// Pieces we want, in picking order.
    order: Vec<i32>,
    progress: HashMap<i32, Progress>,
    hashing: HashSet<i32>,
    pub peers: HashMap<PeerKey, Peer>,
    candidates: HashMap<SocketAddr, Candidate>,
    metadata_fetch: Option<MetadataFetch>,
    /// A metadata fetch has failed since the torrent was added.
    metadata_failed: bool,
    layer_fetch: Vec<LayerFetch>,
    /// Peers that sent data failing its hash: by address when several peers may share an IP (tests), else by IP.
    banned: HashSet<IpAddr>,
    banned_addrs: HashSet<SocketAddr>,
    pub paused: bool,
    pub removed: bool,
    /// Seeding fixtures: check each piece once before serving it, and serve everything.
    pub verify_uploads: bool,
    verified_uploads: HashSet<i32>,
    pub down: Rate,
    pub up: Rate,
    pub total_down: i64,
    pub total_up: i64,
}

impl State {
    fn is_banned(&self, a: &SocketAddr) -> bool {
        self.banned.contains(&a.ip()) || self.banned_addrs.contains(a)
    }
}

pub struct Torrent {
    pub tag: u64,
    pub session: Weak<SessionShared>,
    state: Mutex<State>,
    pub wake: Notify,
    next_key: AtomicU64,
}

fn block_count(size: i32) -> usize {
    (size as usize).div_ceil(wire::BLOCK as usize)
}

fn block_len(size: i32, b: usize) -> u32 {
    (size as u32 - b as u32 * wire::BLOCK).min(wire::BLOCK)
}

impl Torrent {
    pub fn new(
        tag: u64,
        session: Weak<SessionShared>,
        hashes: InfoHashes,
        info: Option<Info>,
        layers: Layers,
        trackers: Vec<String>,
    ) -> Arc<Torrent> {
        let t = Arc::new(Torrent {
            tag,
            session,
            state: Mutex::new(State {
                hashes,
                info: None,
                metadata: None,
                layers: Layers::new(),
                storage: None,
                cache_dir: None,
                cache_bytes: 0,
                trackers,
                web_seeds: Vec::new(),
                web_seed_hash_failures: 0,
                have: Bitfield::new(0),
                priorities: Vec::new(),
                deadlines: HashMap::new(),
                order: Vec::new(),
                progress: HashMap::new(),
                hashing: HashSet::new(),
                peers: HashMap::new(),
                candidates: HashMap::new(),
                metadata_fetch: None,
                metadata_failed: false,
                layer_fetch: Vec::new(),
                banned: HashSet::new(),
                banned_addrs: HashSet::new(),
                paused: false,
                removed: false,
                verify_uploads: false,
                verified_uploads: HashSet::new(),
                down: Rate::default(),
                up: Rate::default(),
                total_down: 0,
                total_up: 0,
            }),
            wake: Notify::new(),
            next_key: AtomicU64::new(1),
        });
        if let Some(info) = info {
            let mut st = t.lock();
            st.layers = layers;
            t.install(&mut st, info);
        }
        t
    }

    pub fn lock(&self) -> MutexGuard<'_, State> {
        self.state.lock().unwrap_or_else(|e| e.into_inner())
    }

    fn emit(&self, event: Event) {
        if let Some(s) = self.session.upgrade() {
            s.emit(event);
        }
    }

    fn log(&self, line: String) {
        if let Some(s) = self.session.upgrade() {
            s.log(line);
        }
    }

    /// Streaming storage: a bounded cache created when the engine first asks for it.
    pub fn use_cache(&self, dir: &str, cache_bytes: i64) {
        let mut st = self.lock();
        st.cache_dir = Some(dir.to_owned());
        st.cache_bytes = cache_bytes;
    }

    /// Seeding storage: complete files, every piece available.
    pub fn seed_from(&self, storage: Storage, verify: bool) {
        let mut st = self.lock();
        st.storage = Some(Arc::new(storage));
        st.verify_uploads = verify;
        let n = st.have.len();
        st.have = Bitfield::full(n);
        recompute(&mut st);
    }

    pub fn matches(&self, id: &Id20) -> bool {
        self.lock().hashes.matches(id)
    }

    pub fn id(&self) -> Id20 {
        self.lock().hashes.id()
    }

    /// Metadata became known: from the torrent file or from peers.
    fn install(&self, st: &mut State, info: Info) {
        let n = info.num_pieces as usize;
        st.hashes = info.hashes;
        st.metadata = Some(Arc::new(info.metadata()));
        st.have = Bitfield::new(n);
        st.priorities = vec![DONT_DOWNLOAD; n];
        let info = Arc::new(info);
        st.info = Some(info.clone());
        st.metadata_fetch = None;
        // v2 files larger than a piece need their piece layer before any of their pieces can be checked.
        if info.version == Version::V2 {
            for file in info.layered_files() {
                let root = info.files[file].pieces_root.unwrap();
                if st.layers.contains_key(&root) {
                    continue;
                }
                let count = info.file_pieces(file).1 as usize;
                let width = count.next_power_of_two();
                let chunk = width.min(LAYER_CHUNK);
                let chunks = width / chunk;
                st.layer_fetch.push(LayerFetch {
                    file,
                    root,
                    count,
                    chunk,
                    chunks: vec![None; chunks],
                    requested: vec![None; chunks],
                });
            }
        }
        for peer in st.peers.values_mut() {
            peer.have = match (&peer.early_bitfield, peer.early_have_all) {
                (_, true) => Bitfield::full(n),
                (Some(b), _) => Bitfield::from_bytes(b, n).unwrap_or_else(|| Bitfield::new(n)),
                _ => Bitfield::new(n),
            };
            for p in peer.early_haves.drain(..) {
                peer.have.set(p as usize, true);
            }
        }
    }

    pub fn metadata(&self) -> Option<Arc<Metadata>> {
        self.lock().metadata.clone()
    }

    pub fn info(&self) -> Option<Arc<Info>> {
        self.lock().info.clone()
    }

    pub fn cache(&self) -> Option<Arc<PieceCache>> {
        let mut st = self.lock();
        if st.removed {
            return None;
        }
        if let Some(s) = &st.storage {
            return s.cache();
        }
        let (Some(info), Some(dir)) = (st.info.clone(), st.cache_dir.clone()) else { return None };
        let slots = crate::cache::cache_slots(st.cache_bytes, info.piece_length);
        static NEXT_FILE: AtomicU64 = AtomicU64::new(0);
        let path = format!("{dir}/{}-{}.cache", metainfo::hex(&info.hashes.id()), NEXT_FILE.fetch_add(1, Ordering::Relaxed));
        let sizes = (0..info.num_pieces).map(|p| info.piece_size(p)).collect();
        match PieceCache::new(&path, info.piece_length, sizes, info.pad_ranges(), slots, true) {
            Ok(cache) => {
                let cache = Arc::new(cache);
                st.storage = Some(Arc::new(Storage::Cache(cache.clone())));
                Some(cache)
            }
            Err(e) => {
                drop(st);
                self.emit(Event::FileError { tag: self.tag, message: e.to_string() });
                None
            }
        }
    }

    // ---- The engine's controls ----

    pub fn prioritize(&self, priorities: &[u8]) {
        let mut st = self.lock();
        if st.priorities.len() != priorities.len() {
            return;
        }
        cancel_departed(&mut st, priorities);
        st.priorities.copy_from_slice(priorities);
        recompute(&mut st);
        drop(st);
        self.refill_all();
    }

    pub fn set_deadline(&self, piece: i32, ms: i32) {
        let mut st = self.lock();
        st.deadlines.insert(piece, Instant::now() + Duration::from_millis(ms.max(0) as u64));
        recompute(&mut st);
        drop(st);
        self.refill_all();
    }

    pub fn clear_deadlines(&self) {
        let mut st = self.lock();
        st.deadlines.clear();
        recompute(&mut st);
    }

    pub fn have_piece(&self, p: i32) -> bool {
        self.lock().have.get(p as usize)
    }

    /// Forgets pieces we have. Peers that know about lt_donthave are told; peers downloading from us that don't are
    /// disconnected (they'd keep asking for pieces we no longer have); seeds need nothing from us and stay.
    pub fn evict(&self, pieces: Vec<i32>, done: EvictDone) {
        let mut evicted = Vec::new();
        let storage = {
            let mut st = self.lock();
            for &p in &pieces {
                if p >= 0 && st.have.get(p as usize) && !st.hashing.contains(&p) {
                    st.have.set(p as usize, false);
                    st.verified_uploads.remove(&p);
                    evicted.push(p);
                }
            }
            if !evicted.is_empty() {
                for peer in st.peers.values() {
                    if let Some(id) = peer.ext.donthave {
                        for &p in &evicted {
                            peer.send(Message::Extended { id, payload: (p as u32).to_be_bytes().to_vec() });
                        }
                    } else if peer.peer_interested && !peer.is_seed() {
                        peer.close();
                    }
                }
                recompute(&mut st);
            }
            st.storage.clone()
        };
        if let Some(storage) = storage {
            for &p in &evicted {
                storage.release(p);
            }
        }
        done(evicted);
        self.refill_all();
    }

    pub fn status(&self) -> TorrentStatus {
        let st = self.lock();
        TorrentStatus {
            peers: st.peers.len() as i32,
            seeds: st.peers.values().filter(|p| p.is_seed()).count() as i32,
            download_rate: st.down.per_second().min(i64::from(i32::MAX)) as i32,
            upload_rate: st.up.per_second().min(i64::from(i32::MAX)) as i32,
            total_download: st.total_down,
            total_upload: st.total_up,
        }
    }

    pub fn metainfo(&self) -> Option<Vec<u8>> {
        let st = self.lock();
        let info = st.info.as_ref()?;
        Some(metainfo::write_torrent(info, &st.trackers, &st.layers, &st.web_seeds))
    }

    pub fn pause(&self) {
        let mut st = self.lock();
        st.paused = true;
        for p in st.peers.values() {
            p.close();
        }
    }

    pub fn remove(&self) {
        let mut st = self.lock();
        st.removed = true;
        st.paused = true;
        for p in st.peers.values() {
            p.close();
        }
        st.storage = None;
        st.cache_dir = None;
        drop(st);
        self.wake.notify_one();
    }

    pub fn add_candidates(&self, peers: impl IntoIterator<Item = SocketAddr>) {
        let policy = self.session.upgrade().map(|s| s.policy).unwrap_or_default();
        let mut st = self.lock();
        let mut added = false;
        for a in peers {
            if !policy.allows(&a) || st.is_banned(&a) || st.candidates.len() >= MAX_CANDIDATES {
                continue;
            }
            st.candidates.entry(a).or_insert_with(|| {
                added = true;
                Candidate { next_try: Instant::now(), failures: 0, connected: false, connecting: false }
            });
        }
        drop(st);
        if added {
            self.wake.notify_one();
        }
    }

    /// Candidates to dial now, marked as connecting.
    pub fn take_dials(&self, limit: usize, multiple_per_ip: bool) -> Vec<SocketAddr> {
        let mut st = self.lock();
        if st.paused || st.removed || limit == 0 {
            return Vec::new();
        }
        let now = Instant::now();
        let connected_ips: HashSet<IpAddr> = st.peers.values().map(|p| p.addr.ip()).collect();
        let connected: HashSet<SocketAddr> = st.peers.values().map(|p| p.addr).collect();
        let mut out = Vec::new();
        for (addr, c) in st.candidates.iter_mut() {
            if out.len() >= limit {
                break;
            }
            if c.connected || c.connecting || c.next_try > now || connected.contains(addr) {
                continue;
            }
            if !multiple_per_ip && connected_ips.contains(&addr.ip()) {
                continue;
            }
            c.connecting = true;
            out.push(*addr);
        }
        out
    }

    /// The connection worked but the torrent didn't take it, as when another came first on the same address.
    pub fn dial_refused(&self, addr: SocketAddr) {
        let mut st = self.lock();
        if let Some(c) = st.candidates.get_mut(&addr) {
            c.connecting = false;
            c.next_try = Instant::now() + RECONNECT;
        }
    }

    pub fn dial_failed(&self, addr: SocketAddr) {
        let mut st = self.lock();
        if let Some(c) = st.candidates.get_mut(&addr) {
            c.connecting = false;
            c.failures += 1;
            c.next_try = Instant::now() + RECONNECT * (1u32 << c.failures.min(6));
        }
    }

    // ---- Peers ----

    /// A connection finished its handshake. None when the torrent doesn't take it.
    pub fn attach(
        &self,
        addr: SocketAddr,
        reserved: Reserved,
        v2: bool,
        incoming: bool,
        tx: mpsc::UnboundedSender<Out>,
        link: Arc<PeerLink>,
        limits: (usize, bool),
    ) -> Option<PeerKey> {
        let mut st = self.lock();
        let (max_peers, multiple_per_ip) = limits;
        if st.removed || st.paused || st.is_banned(&addr) || st.peers.len() >= max_peers {
            return None;
        }
        if st.peers.values().any(|p| p.addr == addr || (!multiple_per_ip && p.addr.ip() == addr.ip())) {
            return None;
        }
        let key = self.next_key.fetch_add(1, Ordering::Relaxed);
        let n = st.have.len();
        let peer = Peer {
            addr,
            tx,
            link,
            waiting: VecDeque::new(),
            reserved,
            v2,
            ext: ExtHandshake::default(),
            ext_received: false,
            have: Bitfield::new(n),
            early_bitfield: None,
            early_have_all: false,
            early_haves: Vec::new(),
            peer_choking: true,
            peer_interested: false,
            am_choking: true,
            am_interested: false,
            requests: Vec::new(),
            metadata_requests: 0,
            metadata_failures: 0,
            layer_requests: 0,
            down: Rate::default(),
            up: Rate::default(),
            pex_sent: HashSet::new(),
            last_pex: None,
            incoming,
            hash_failures: 0,
        };
        // Our side of the opening: extension handshake, then what we have.
        if reserved.extensions() {
            let session = self.session.upgrade();
            let ext = ExtHandshake {
                metadata: Some(wire::EXT_METADATA),
                pex: session.as_ref().filter(|s| s.pex && !st.info.as_ref().is_some_and(|i| i.private)).map(|_| wire::EXT_PEX),
                donthave: Some(wire::EXT_DONTHAVE),
                metadata_size: st.info.as_ref().map(|i| i.raw.len()),
                listen_port: session.as_ref().map(|s| s.port()).filter(|&p| p != 0),
                client: session.as_ref().map(|s| s.user_agent.clone()),
                upload_only: false,
            };
            peer.send(Message::Extended { id: 0, payload: ext.encode() });
        }
        if !st.have.is_empty() && st.have.count() > 0 {
            if st.have.all() && reserved.fast() {
                peer.send(Message::HaveAll);
            } else {
                peer.send(Message::Bitfield(st.have.bytes().to_vec()));
            }
        } else if reserved.fast() {
            peer.send(Message::HaveNone);
        }
        st.peers.insert(key, peer);
        if st.candidates.len() < MAX_CANDIDATES || st.candidates.contains_key(&addr) {
            let c = st.candidates.entry(addr).or_insert(Candidate {
                next_try: Instant::now(),
                failures: 0,
                connected: false,
                connecting: false,
            });
            c.connected = true;
            c.connecting = false;
            c.failures = 0;
        }
        Some(key)
    }

    pub fn detach(&self, key: PeerKey, reason: &str) {
        let mut st = self.lock();
        let Some(peer) = st.peers.remove(&key) else { return };
        // Its requests go back to the pool.
        for r in &peer.requests {
            unrequest(&mut st, key, &r.block);
        }
        for f in st.metadata_fetch.iter_mut() {
            for r in f.requested.iter_mut() {
                if r.is_some_and(|(k, _)| k == key) {
                    *r = None;
                }
            }
        }
        for f in st.layer_fetch.iter_mut() {
            for r in f.requested.iter_mut() {
                if r.is_some_and(|(k, _)| k == key) {
                    *r = None;
                }
            }
        }
        let addr = peer.listen_addr().unwrap_or(peer.addr);
        for a in [peer.addr, addr] {
            if let Some(c) = st.candidates.get_mut(&a) {
                c.connected = false;
                c.connecting = false;
                c.next_try = Instant::now() + RECONNECT;
            }
        }
        drop(st);
        if !reason.is_empty() {
            self.log(format!("peer {} disconnected: {reason}", peer.addr));
        }
        self.refill_all();
        self.wake.notify_one();
    }

    fn refill_all(&self) {
        let keys: Vec<PeerKey> = self.lock().peers.keys().copied().collect();
        for k in keys {
            self.refill(k);
        }
    }

    /// Updates our interest in a peer and tops up its request queue.
    pub fn refill(&self, key: PeerKey) {
        let mut st = self.lock();
        let st = &mut *st;
        if st.removed {
            return;
        }
        let Some(peer) = st.peers.get(&key) else { return };
        let interesting = st.order.iter().any(|&p| peer.have.get(p as usize))
            || (st.info.is_none() && peer.ext.metadata.is_some());
        let peer = st.peers.get_mut(&key).unwrap();
        if interesting != peer.am_interested {
            peer.am_interested = interesting;
            peer.send(if interesting { Message::Interested } else { Message::NotInterested });
        }
        if peer.peer_choking || !interesting || st.info.is_none() {
            return;
        }
        let rate_blocks = (peer.down.per_second() as usize * 3) / wire::BLOCK as usize;
        let target = rate_blocks.clamp(MIN_REQUESTS, MAX_REQUESTS);
        if peer.requests.len() >= target {
            return;
        }
        let picked = pick(st, key, target);
        let peer = st.peers.get_mut(&key).unwrap();
        let now = Instant::now();
        for block in picked {
            peer.requests.push(Request { block, at: now, sent: now });
            peer.send(Message::Request(block));
        }
    }

    /// Handles one message from a peer. An error disconnects it.
    pub fn on_message(self: &Arc<Self>, key: PeerKey, msg: Message) -> Result<(), String> {
        if self.lock().peers.get(&key).is_some_and(|p| p.link.control.load(Ordering::SeqCst) > MAX_QUEUED_CONTROL) {
            return Err("it isn't reading what it asks for".into());
        }
        match msg {
            Message::KeepAlive | Message::Port(_) | Message::Suggest(_) | Message::AllowedFast(_) => {}
            Message::Choke => {
                let mut st = self.lock();
                let peer = st.peers.get_mut(&key).ok_or("gone")?;
                peer.peer_choking = true;
                // Without the fast extension a choke discards every pending request.
                if !peer.reserved.fast() {
                    let requests = std::mem::take(&mut peer.requests);
                    for r in requests {
                        unrequest(&mut st, key, &r.block);
                    }
                }
            }
            Message::Unchoke => {
                self.lock().peers.get_mut(&key).ok_or("gone")?.peer_choking = false;
                self.refill(key);
            }
            Message::Interested | Message::NotInterested => {
                let mut st = self.lock();
                let interested = matches!(msg, Message::Interested);
                st.peers.get_mut(&key).ok_or("gone")?.peer_interested = interested;
                if interested {
                    choke_round(&mut st, false);
                }
            }
            Message::Have(p) => {
                {
                    let mut st = self.lock();
                    let has_info = st.info.is_some();
                    let n = st.have.len();
                    let peer = st.peers.get_mut(&key).ok_or("gone")?;
                    if !has_info {
                        if peer.early_haves.len() < MAX_EARLY_HAVES {
                            peer.early_haves.push(p);
                        }
                    } else if (p as usize) < n {
                        peer.have.set(p as usize, true);
                    } else {
                        return Err("have for a piece that doesn't exist".into());
                    }
                }
                self.refill(key);
            }
            Message::Bitfield(bits) => {
                {
                    let mut st = self.lock();
                    let n = st.have.len();
                    let has_info = st.info.is_some();
                    let peer = st.peers.get_mut(&key).ok_or("gone")?;
                    if has_info {
                        peer.have = Bitfield::from_bytes(&bits, n).ok_or("invalid bitfield")?;
                    } else {
                        peer.early_bitfield = Some(bits);
                    }
                }
                self.refill(key);
            }
            Message::HaveAll | Message::HaveNone => {
                {
                    let mut st = self.lock();
                    let n = st.have.len();
                    let has_info = st.info.is_some();
                    let all = matches!(msg, Message::HaveAll);
                    let peer = st.peers.get_mut(&key).ok_or("gone")?;
                    if has_info {
                        peer.have = if all { Bitfield::full(n) } else { Bitfield::new(n) };
                    } else {
                        peer.early_have_all = all;
                    }
                }
                self.refill(key);
            }
            Message::Request(b) => self.serve(key, b),
            Message::Cancel(b) => {
                if let Some(peer) = self.lock().peers.get_mut(&key) {
                    peer.waiting.retain(|w| *w != b);
                }
            }
            Message::Reject(b) => {
                let mut st = self.lock();
                let peer = st.peers.get_mut(&key).ok_or("gone")?;
                if let Some(i) = peer.requests.iter().position(|r| r.block == b) {
                    peer.requests.swap_remove(i);
                    unrequest(&mut st, key, &b);
                }
            }
            Message::Piece { piece, offset, data } => {
                self.on_block(key, piece, offset, data)?;
                self.refill(key);
            }
            Message::Extended { id, payload } => self.on_extended(key, id, &payload)?,
            Message::HashRequest(r) => {
                let st = self.lock();
                let reply = answer_hashes(&st, &r);
                if let Some(peer) = st.peers.get(&key) {
                    peer.send(reply);
                }
            }
            Message::Hashes { request, hashes } => self.on_hashes(key, request, hashes),
            Message::HashReject(r) => {
                let mut st = self.lock();
                for f in st.layer_fetch.iter_mut().filter(|f| f.root == r.root) {
                    let c = r.index as usize / f.chunk.max(1);
                    if f.requested.get(c).is_some_and(|x| x.is_some_and(|(k, _)| k == key)) {
                        f.requested[c] = None;
                    }
                }
                if let Some(p) = st.peers.get_mut(&key) {
                    p.layer_requests = p.layer_requests.saturating_sub(1);
                }
            }
        }
        Ok(())
    }

    fn serve(&self, key: PeerKey, b: BlockRef) {
        let (storage, verify, check) = {
            let mut st = self.lock();
            let ok = b.length > 0
                && b.length <= wire::BLOCK
                && st.have.get(b.piece as usize)
                && st.info.as_ref().is_some_and(|i| b.offset as u64 + b.length as u64 <= i.piece_size(b.piece as i32) as u64);
            let Some(peer) = st.peers.get_mut(&key) else { return };
            let full = peer.link.upload.load(Ordering::SeqCst) + b.length as usize > MAX_QUEUED_UPLOAD;
            if ok && !peer.am_choking && full && peer.waiting.len() < MAX_WAITING_REQUESTS {
                peer.waiting.push_back(b);
                return;
            }
            if peer.am_choking || !ok || full {
                if peer.reserved.fast() {
                    peer.send(Message::Reject(b));
                }
                return;
            }
            let check = st.verify_uploads && !st.verified_uploads.contains(&(b.piece as i32));
            (st.storage.clone(), st.info.clone(), check)
        };
        let Some(storage) = storage else { return };
        if check {
            if let Some(info) = &verify {
                let layers = self.lock().layers.clone();
                if expected_hash(info, &layers, b.piece as i32).is_some_and(|e| verify_piece(&storage, info, b.piece as i32, &e)) {
                    self.lock().verified_uploads.insert(b.piece as i32);
                } else {
                    let st = self.lock();
                    if let Some(peer) = st.peers.get(&key) {
                        if peer.reserved.fast() {
                            peer.send(Message::Reject(b));
                        }
                    }
                    return;
                }
            }
        }
        let mut data = vec![0u8; b.length as usize];
        let read = storage.read(b.piece as i32, b.offset as i32, &mut data);
        let mut st = self.lock();
        let Some(peer) = st.peers.get_mut(&key) else { return };
        match read {
            Ok(()) => {
                peer.up.add(data.len());
                peer.link.upload.fetch_add(data.len(), Ordering::SeqCst);
                let _ = peer.tx.send(Out::Upload(Message::Piece { piece: b.piece, offset: b.offset, data }, b.length as usize));
                st.up.add(b.length as usize);
                st.total_up += i64::from(b.length);
            }
            Err(_) => {
                // Evicted since the check.
                if peer.reserved.fast() {
                    peer.send(Message::Reject(b));
                }
            }
        }
    }

    /// The writer sent a peer's piece data: serve the requests that waited for room.
    pub fn upload_sent(&self, key: PeerKey) {
        loop {
            let next = {
                let mut st = self.lock();
                let Some(peer) = st.peers.get_mut(&key) else { return };
                if peer.link.upload.load(Ordering::SeqCst) + wire::BLOCK as usize > MAX_QUEUED_UPLOAD {
                    return;
                }
                match peer.waiting.pop_front() {
                    Some(b) => b,
                    None => return,
                }
            };
            self.serve(key, next);
        }
    }

    fn on_block(self: &Arc<Self>, key: PeerKey, piece: u32, offset: u32, data: Vec<u8>) -> Result<(), String> {
        let b = BlockRef { piece, offset, length: data.len() as u32 };
        let mut st = self.lock();
        let peer = st.peers.get_mut(&key).ok_or("gone")?;
        let Some(i) = peer.requests.iter().position(|r| r.block == b) else {
            // Unrequested or already cancelled: ignore it.
            return Ok(());
        };
        peer.requests.swap_remove(i);
        peer.down.add(data.len());
        st.down.add(data.len());
        st.total_down += data.len() as i64;
        let p = piece as i32;
        let index = (offset / wire::BLOCK) as usize;
        let Some(storage) = st.storage.clone() else { return Ok(()) };
        let wanted = st.progress.get(&p).is_some_and(|pr| pr.blocks.get(index).is_some_and(|s| *s != Block::Received));
        if !wanted || st.have.get(p as usize) || st.hashing.contains(&p) {
            return Ok(());
        }
        // Other peers asked for the same block can stop.
        if let Some(Block::Requested { first, second, .. }) = st.progress.get(&p).map(|pr| pr.blocks[index]) {
            for other in [Some(first), second].into_iter().flatten().filter(|&k| k != key) {
                if let Some(o) = st.peers.get_mut(&other) {
                    if let Some(j) = o.requests.iter().position(|r| r.block == b) {
                        o.requests.swap_remove(j);
                        o.send(Message::Cancel(b));
                    }
                }
            }
        }
        drop(st);
        let written = storage.write(p, offset as i32, &data);
        let mut st = self.lock();
        let Some(pr) = st.progress.get_mut(&p) else { return Ok(()) };
        match written {
            Ok(true) => {
                pr.blocks[index] = Block::Received;
                pr.contributors.insert(key);
            }
            Ok(false) => {
                pr.blocks[index] = Block::Missing;
                if let Some(s) = self.session.upgrade() {
                    s.blocked_writes.fetch_add(1, Ordering::Relaxed);
                }
                return Ok(());
            }
            Err(e) => {
                pr.blocks[index] = Block::Missing;
                drop(st);
                self.emit(Event::FileError { tag: self.tag, message: e });
                return Ok(());
            }
        }
        if pr.blocks.iter().all(|s| *s == Block::Received) {
            self.start_hash(&mut st, p);
        }
        Ok(())
    }

    fn start_hash(self: &Arc<Self>, st: &mut State, p: i32) {
        let (Some(info), Some(storage)) = (st.info.clone(), st.storage.clone()) else { return };
        let Some(expected) = expected_hash(&info, &st.layers, p) else { return };
        // A session shutting down hashes nothing more; the piece must not stay marked as hashing either way.
        let Some(session) = self.session.upgrade() else { return };
        st.hashing.insert(p);
        let t = self.clone();
        session.runtime.spawn_blocking(move || {
            let ok = verify_piece(&storage, &info, p, &expected);
            drop(storage);
            t.hash_done(p, ok);
        });
    }

    fn hash_done(&self, p: i32, ok: bool) {
        let mut st = self.lock();
        st.hashing.remove(&p);
        if st.removed {
            return;
        }
        let progress = st.progress.remove(&p);
        if ok {
            st.have.set(p as usize, true);
            st.deadlines.remove(&p);
            recompute(&mut st);
            for peer in st.peers.values() {
                peer.send(Message::Have(p as u32));
            }
            drop(st);
            self.emit(Event::PieceFinished { tag: self.tag, piece: p });
        } else {
            if let Some(s) = &st.storage {
                s.clear(p);
            }
            // A sole contributor is banned at once; others after repeated failures.
            if let Some(progress) = progress {
                let sole = progress.contributors.len() == 1;
                for k in progress.contributors {
                    if let Some(peer) = st.peers.get_mut(&k) {
                        peer.hash_failures += 1;
                        if sole || peer.hash_failures >= 3 {
                            let addr = peer.addr;
                            peer.close();
                            if self.session.upgrade().is_some_and(|s| s.multiple_per_ip) {
                                st.banned_addrs.insert(addr);
                            } else {
                                st.banned.insert(addr.ip());
                            }
                        }
                    }
                }
            }
            recompute(&mut st);
            drop(st);
            self.log(format!("piece {p} failed its hash check"));
            self.emit(Event::HashFailed { tag: self.tag });
        }
        self.refill_all();
    }

    fn on_extended(self: &Arc<Self>, key: PeerKey, id: u8, payload: &[u8]) -> Result<(), String> {
        match id {
            0 => {
                let ext = ExtHandshake::decode(payload).ok_or("invalid extension handshake")?;
                let mut st = self.lock();
                if let (Some(port), Some(peer)) = (ext.listen_port, st.peers.get(&key)) {
                    if peer.incoming {
                        let a = SocketAddr::new(peer.addr.ip(), port);
                        if st.candidates.len() < MAX_CANDIDATES {
                            st.candidates.entry(a).or_insert(Candidate {
                                next_try: Instant::now() + Duration::from_secs(3600),
                                failures: 0,
                                connected: true,
                                connecting: false,
                            });
                        }
                    }
                }
                let peer = st.peers.get_mut(&key).ok_or("gone")?;
                peer.ext = ext;
                peer.ext_received = true;
                start_metadata_fetch(&mut st);
                request_metadata(&mut st);
                drop(st);
                self.refill(key);
            }
            wire::EXT_METADATA => {
                let m = MetadataMessage::decode(payload).ok_or("invalid ut_metadata message")?;
                self.on_metadata_message(key, m);
            }
            wire::EXT_PEX => {
                let peers = wire::decode_pex(payload);
                if self.session.upgrade().is_some_and(|s| s.pex) {
                    self.add_candidates(peers);
                }
            }
            wire::EXT_DONTHAVE => {
                if payload.len() == 4 {
                    let p = u32::from_be_bytes(payload.try_into().unwrap()) as usize;
                    let mut st = self.lock();
                    if let Some(peer) = st.peers.get_mut(&key) {
                        peer.have.set(p, false);
                    }
                }
            }
            _ => {}
        }
        Ok(())
    }

    fn on_metadata_message(self: &Arc<Self>, key: PeerKey, m: MetadataMessage) {
        let mut st = self.lock();
        match m {
            MetadataMessage::Request(piece) => {
                let reply = match &st.info {
                    Some(info) if (piece as usize) * wire::METADATA_PIECE < info.raw.len() => {
                        let start = piece as usize * wire::METADATA_PIECE;
                        let end = (start + wire::METADATA_PIECE).min(info.raw.len());
                        MetadataMessage::Data { piece, total_size: info.raw.len(), data: info.raw[start..end].to_vec() }
                    }
                    _ => MetadataMessage::Reject(piece),
                };
                if let Some(peer) = st.peers.get(&key) {
                    if let Some(id) = peer.ext.metadata {
                        peer.send(Message::Extended { id, payload: reply.encode() });
                    }
                }
            }
            MetadataMessage::Reject(piece) => {
                let Some(f) = st.metadata_fetch.as_mut() else { return };
                let Some(r) = f.requested.get_mut(piece as usize) else { return };
                // An unsolicited reject must not cancel another peer's request or free an allowance.
                if !r.is_some_and(|(owner, _)| owner == key) {
                    return;
                }
                *r = None;
                if let Some(peer) = st.peers.get_mut(&key) {
                    peer.metadata_requests = peer.metadata_requests.saturating_sub(1);
                }
            }
            MetadataMessage::Data { piece, total_size, data } => {
                let Some(f) = st.metadata_fetch.as_mut() else { return };
                if !f.requested.get(piece as usize).is_some_and(|r| r.is_some_and(|(owner, _)| owner == key)) {
                    // Replays cannot keep a stalled fetch alive, overwrite a received piece, or consume allowances.
                    return;
                }
                let i = piece as usize;
                let start = i * wire::METADATA_PIECE;
                let expected = f.size.saturating_sub(start).min(wire::METADATA_PIECE);
                if total_size != f.size || i >= f.received.len() || data.len() != expected {
                    return;
                }
                f.data[start..start + data.len()].copy_from_slice(&data);
                f.received[i] = true;
                f.requested[i] = None;
                f.senders.insert(key);
                f.progress = Instant::now();
                if let Some(peer) = st.peers.get_mut(&key) {
                    peer.metadata_requests = peer.metadata_requests.saturating_sub(1);
                }
                let f = st.metadata_fetch.as_mut().unwrap();
                if f.received.iter().all(|&r| r) {
                    let raw = std::mem::take(&mut f.data);
                    let senders = std::mem::take(&mut f.senders);
                    let hashes = st.hashes;
                    match Info::parse(&raw, Some(&hashes)) {
                        Ok(info) => {
                            self.install(&mut st, info);
                            recompute(&mut st);
                            drop(st);
                            self.emit(Event::MetadataReceived { tag: self.tag });
                            self.refill_all();
                            return;
                        }
                        Err(e) => {
                            st.metadata_fetch = None;
                            st.metadata_failed = true;
                            for peer in st.peers.values_mut() {
                                peer.metadata_requests = 0;
                            }
                            for k in senders {
                                if let Some(peer) = st.peers.get_mut(&k) {
                                    peer.metadata_failures += 1;
                                }
                            }
                            start_metadata_fetch(&mut st);
                            request_metadata(&mut st);
                            drop(st);
                            self.log(format!("metadata from peers was invalid: {e}"));
                            return;
                        }
                    }
                }
                request_metadata(&mut st);
            }
        }
    }

    fn on_hashes(&self, key: PeerKey, request: HashRequest, hashes: Vec<Hash>) {
        let mut st = self.lock();
        if let Some(p) = st.peers.get_mut(&key) {
            p.layer_requests = p.layer_requests.saturating_sub(1);
        }
        let Some(info) = st.info.clone() else { return };
        let Some(fi) = st.layer_fetch.iter().position(|f| f.root == request.root) else { return };
        let f = &mut st.layer_fetch[fi];
        let c = request.index as usize / f.chunk;
        if request.length as usize != f.chunk || request.index as usize % f.chunk != 0 || c >= f.chunks.len() || hashes.len() < f.chunk {
            return;
        }
        f.chunks[c] = Some(hashes[..f.chunk].to_vec());
        f.requested[c] = None;
        if f.chunks.iter().all(Option::is_some) {
            let mut layer: Vec<Hash> = f.chunks.iter().flatten().flatten().copied().collect();
            layer.truncate(f.count);
            let (file, root) = (f.file, f.root);
            if check_layer(&info, file, &layer) {
                st.layer_fetch.remove(fi);
                st.layers.insert(root, layer);
                recompute(&mut st);
                drop(st);
                self.refill_all();
            } else {
                let f = &mut st.layer_fetch[fi];
                f.chunks.iter_mut().for_each(|c| *c = None);
                drop(st);
                self.log("a peer sent a piece layer that doesn't match its file".into());
            }
        }
    }

    /// Once a second: rates, request timeouts, unchoking, peer exchange, metadata and layer requests.
    pub fn maintain(&self, tick: u64) {
        let mut guard = self.lock();
        let st = &mut *guard;
        st.down.tick();
        st.up.tick();
        let now = Instant::now();
        let mut expired: Vec<(PeerKey, BlockRef)> = Vec::new();
        for (k, peer) in st.peers.iter_mut() {
            peer.down.tick();
            peer.up.tick();
            peer.requests.retain(|r| {
                let keep = now.duration_since(r.at) < REQUEST_TIMEOUT;
                if !keep {
                    expired.push((*k, r.block));
                }
                keep
            });
            retry_urgent(peer, &st.deadlines, now);
            if tick % 30 == 0 {
                peer.send(Message::KeepAlive);
            }
        }
        for (k, b) in expired {
            unrequest(st, k, &b);
        }
        // A request that timed out stops counting against its peer, or a peer ignoring two would never be asked again.
        for f in st.metadata_fetch.iter_mut() {
            for r in f.requested.iter_mut() {
                if let Some((k, at)) = *r {
                    if now.duration_since(at) > Duration::from_secs(10) {
                        *r = None;
                        if let Some(p) = st.peers.get_mut(&k) {
                            p.metadata_requests = p.metadata_requests.saturating_sub(1);
                        }
                    }
                }
            }
        }
        for f in st.layer_fetch.iter_mut() {
            for r in f.requested.iter_mut() {
                if let Some((k, at)) = *r {
                    if now.duration_since(at) > Duration::from_secs(10) {
                        *r = None;
                        if let Some(p) = st.peers.get_mut(&k) {
                            p.layer_requests = p.layer_requests.saturating_sub(1);
                        }
                    }
                }
            }
        }
        // Nobody is delivering this fetch: its size may be a lie, or its only peer gone. Try another's word.
        if let Some(f) = &st.metadata_fetch {
            let gone = f.only.is_some_and(|only| !st.peers.contains_key(&only));
            if gone || now.duration_since(f.progress) > METADATA_STALL {
                let size = f.size;
                let only = f.only;
                for (k, peer) in st.peers.iter_mut() {
                    if peer.ext.metadata_size == Some(size) && only.is_none_or(|o| o == *k) {
                        peer.metadata_failures += 1;
                    }
                    peer.metadata_requests = 0;
                }
                st.metadata_fetch = None;
                st.metadata_failed = true;
                start_metadata_fetch(st);
            }
        }
        request_metadata(st);
        request_layers(st);
        if tick % 10 == 0 {
            choke_round(st, true);
        }
        let pex = self.session.upgrade().is_some_and(|s| s.pex) && !st.info.as_ref().is_some_and(|i| i.private);
        if pex {
            let connected: Vec<SocketAddr> = st.peers.values().filter_map(Peer::listen_addr).collect();
            for peer in st.peers.values_mut() {
                let Some(id) = peer.ext.pex else { continue };
                if peer.last_pex.is_some_and(|t| now.duration_since(t) < Duration::from_secs(60)) {
                    continue;
                }
                let added: Vec<SocketAddr> =
                    connected.iter().filter(|a| **a != peer.addr && !peer.pex_sent.contains(a)).take(50).copied().collect();
                let dropped: Vec<SocketAddr> = peer.pex_sent.iter().filter(|a| !connected.contains(a)).copied().collect();
                if added.is_empty() && dropped.is_empty() {
                    continue;
                }
                for a in &added {
                    peer.pex_sent.insert(*a);
                }
                for a in &dropped {
                    peer.pex_sent.remove(a);
                }
                peer.last_pex = Some(now);
                peer.send(Message::Extended { id, payload: wire::encode_pex(&added, &dropped) });
            }
        }
        drop(guard);
        // Any peer whose requests were rejected, timed out or never sent asks again.
        self.refill_all();
    }

    /// A wanted piece no peer is downloading, claimed for a web seed, with the file ranges that make it up:
    /// `(file index, offset in the file, length, offset in the piece)`. Pad ranges are left out and stay zero.
    pub fn claim_web_piece(&self) -> Option<(i32, i32, Vec<(usize, u64, usize, usize)>)> {
        let mut st = self.lock();
        let st = &mut *st;
        if st.removed || st.paused || st.web_seeds.is_empty() {
            return None;
        }
        let info = st.info.clone()?;
        let storage = st.storage.clone()?;
        let now = Instant::now();
        let p = st.order.iter().copied().find(|p| {
            !st.hashing.contains(p)
                && storage.accepts(*p)
                && st.progress.get(p).is_none_or(|pr| pr.blocks.iter().all(|b| *b == Block::Missing || *b == Block::Received))
        })?;
        let size = info.piece_size(p);
        let pads = info.piece_pads(p);
        let pr = st.progress.entry(p).or_insert_with(|| Progress {
            blocks: vec![Block::Missing; block_count(size)],
            contributors: HashSet::new(),
        });
        for b in pr.blocks.iter_mut() {
            *b = Block::Requested { first: WEB_SEED, second: None, at: now };
        }
        let start = i64::from(p) * i64::from(info.piece_length);
        let end = start + i64::from(size);
        let mut ranges = Vec::new();
        for (i, f) in info.files.iter().enumerate() {
            let (lo, hi) = (start.max(f.offset), end.min(f.offset + f.size));
            if lo >= hi || f.pad {
                continue;
            }
            let at = (lo - start) as i32;
            if pads.iter().any(|&(o, l)| o <= at && at + (hi - lo) as i32 <= o + l) {
                continue;
            }
            ranges.push((i, (lo - f.offset) as u64, (hi - lo) as usize, at as usize));
        }
        Some((p, size, ranges))
    }

    /// A web seed's piece arrived (or didn't): write it and check its hash, or give its blocks back.
    pub fn web_piece_done(self: &Arc<Self>, p: i32, data: Option<Vec<u8>>) {
        let (storage, wanted) = {
            let st = self.lock();
            // A peer may have finished the piece meanwhile, or it may have been evicted: then this copy isn't needed.
            (st.storage.clone(), !st.have.get(p as usize) && !st.hashing.contains(&p) && st.progress.contains_key(&p))
        };
        let written = match (data, storage) {
            (Some(data), Some(storage)) if wanted => {
                let ok = data
                    .chunks(wire::BLOCK as usize)
                    .enumerate()
                    .all(|(i, block)| storage.write(p, (i * wire::BLOCK as usize) as i32, block) == Ok(true));
                {
                    let mut st = self.lock();
                    st.down.add(data.len());
                    st.total_down += data.len() as i64;
                }
                ok
            }
            _ => false,
        };
        let mut st = self.lock();
        let Some(pr) = st.progress.get_mut(&p) else { return };
        for b in pr.blocks.iter_mut() {
            if matches!(b, Block::Requested { first: WEB_SEED, .. }) {
                *b = if written { Block::Received } else { Block::Missing };
            }
        }
        if written {
            if pr.blocks.iter().all(|b| *b == Block::Received) && !st.have.get(p as usize) && !st.hashing.contains(&p) {
                self.start_hash(&mut st, p);
            }
        }
        drop(st);
        self.refill_all();
    }

    /// Whether a web seed's copy of a piece hashes as it should. Checked before any of it is written: the cache only
    /// ever receives good bytes from web seeds, however late they arrive and whatever happened to the piece meanwhile.
    pub fn web_piece_good(&self, p: i32, data: &[u8]) -> bool {
        let (info, layers) = {
            let st = self.lock();
            (st.info.clone(), st.layers.clone())
        };
        let Some(info) = info else { return false };
        expected_hash(&info, &layers, p).is_some_and(|e| matches_hash(&info, data, &e))
    }

    /// A web seed's copy of a piece failed its hash: its blocks go back, and a seed that keeps doing this is dropped.
    pub fn web_piece_bad(self: &Arc<Self>, p: i32) {
        let mut st = self.lock();
        st.web_seed_hash_failures += 1;
        if st.web_seed_hash_failures >= WEB_SEED_FAILURES && !st.web_seeds.is_empty() {
            st.web_seeds.clear();
            self.log("web seeds sent data that failed the hash check; they are no longer used".into());
        }
        if let Some(pr) = st.progress.get_mut(&p) {
            for b in pr.blocks.iter_mut() {
                if matches!(b, Block::Requested { first: WEB_SEED, .. }) {
                    *b = Block::Missing;
                }
            }
        }
        drop(st);
        self.log(format!("piece {p} failed its hash check"));
        self.emit(Event::HashFailed { tag: self.tag });
        self.refill_all();
    }

    pub fn web_seeds(&self) -> Vec<String> {
        self.lock().web_seeds.clone()
    }

    pub fn set_web_seeds(&self, seeds: Vec<String>) {
        self.lock().web_seeds = seeds;
    }

    /// Bytes still wanted, for tracker announces.
    pub fn left(&self) -> i64 {
        let st = self.lock();
        match &st.info {
            Some(info) => st.order.iter().map(|&p| i64::from(info.piece_size(p))).sum(),
            None => 1 << 20,
        }
    }

    pub fn wants_peers(&self) -> bool {
        let st = self.lock();
        !st.paused && !st.removed
    }

    pub fn private(&self) -> bool {
        self.lock().info.as_ref().is_some_and(|i| i.private)
    }

    pub fn trackers(&self) -> Vec<String> {
        self.lock().trackers.clone()
    }
}

/// Requests for pieces that just dropped to the lowest priority or out of the wanted list are cancelled, so a seek's
/// pieces don't wait behind them. Whatever a partial piece still needs is asked again once nothing better is wanted.
fn cancel_departed(st: &mut State, priorities: &[u8]) {
    let departed = |p: u32, old: &[u8]| {
        let at = |list: &[u8]| list.get(p as usize).copied().unwrap_or(DONT_DOWNLOAD);
        at(old) > LOW_PRIORITY && at(priorities) <= LOW_PRIORITY
    };
    let mut cancelled = Vec::new();
    for (k, peer) in st.peers.iter_mut() {
        let mut i = 0;
        while i < peer.requests.len() {
            let b = peer.requests[i].block;
            if departed(b.piece, &st.priorities) {
                peer.requests.swap_remove(i);
                peer.send(Message::Cancel(b));
                cancelled.push((*k, b));
            } else {
                i += 1;
            }
        }
    }
    for (k, b) in cancelled {
        unrequest(st, k, &b);
    }
}

/// Asks a peer again for urgent blocks it should have sent by now. The duplicate costs at most one block if the first
/// request wasn't lost after all: the second copy finds no request and is ignored.
fn retry_urgent(peer: &mut Peer, deadlines: &HashMap<i32, Instant>, now: Instant) {
    if peer.peer_choking || deadlines.is_empty() {
        return;
    }
    // Three times what this peer's queue explains at its current rate, for a peer that is merely slow.
    let rate = peer.down.per_second().max(1) as u128;
    let queue_time = Duration::from_millis((peer.requests.len() as u128 * u128::from(wire::BLOCK) * 3000 / rate).min(60_000) as u64);
    let patience = URGENT_RETRY.max(queue_time);
    let mut again = Vec::new();
    for r in peer.requests.iter_mut() {
        if deadlines.contains_key(&(r.block.piece as i32)) && now.duration_since(r.sent) >= patience {
            r.sent = now;
            again.push(r.block);
        }
    }
    for b in again {
        peer.send(Message::Request(b));
    }
}

/// Rebuilds the wanted list: deadline pieces by deadline, then by priority, then in file order.
fn recompute(st: &mut State) {
    let Some(info) = st.info.clone() else {
        st.order.clear();
        return;
    };
    let mut wanted: Vec<i32> = (0..info.num_pieces)
        .filter(|&p| st.priorities.get(p as usize).copied().unwrap_or(0) > 0 && !st.have.get(p as usize))
        .filter(|&p| verifiable(&info, &st.layers, p))
        .collect();
    let far = Instant::now() + Duration::from_secs(1 << 30);
    wanted.sort_by_key(|&p| (st.deadlines.get(&p).copied().unwrap_or(far), std::cmp::Reverse(st.priorities[p as usize]), p));
    st.order = wanted;
}

fn verifiable(info: &Info, layers: &Layers, p: i32) -> bool {
    if info.version != Version::V2 {
        return true;
    }
    match info.v2_piece(p) {
        Some((file, _, _)) => {
            let f = &info.files[file];
            f.size <= i64::from(info.piece_length) || f.pieces_root.is_some_and(|r| layers.contains_key(&r))
        }
        // A piece of padding only.
        None => true,
    }
}

fn unrequest(st: &mut State, key: PeerKey, b: &BlockRef) {
    let Some(pr) = st.progress.get_mut(&(b.piece as i32)) else { return };
    let i = (b.offset / wire::BLOCK) as usize;
    if let Some(Block::Requested { first, second, at }) = pr.blocks.get(i).copied() {
        pr.blocks[i] = match (first == key, second) {
            (true, Some(s)) => Block::Requested { first: s, second: None, at },
            (true, None) => Block::Missing,
            (false, Some(s)) if s == key => Block::Requested { first, second: None, at },
            _ => Block::Requested { first, second, at },
        };
    }
}

/// Blocks to ask this peer for, up to its target queue.
fn pick(st: &mut State, key: PeerKey, target: usize) -> Vec<BlockRef> {
    let Some(info) = st.info.clone() else { return Vec::new() };
    let Some(storage) = st.storage.clone() else { return Vec::new() };
    let peer = &st.peers[&key];
    let mut room = target.saturating_sub(peer.requests.len());
    let mut out = Vec::new();
    let now = Instant::now();
    for pass in 0..2 {
        for &p in &st.order {
            if room == 0 {
                return out;
            }
            let peer = &st.peers[&key];
            if !peer.have.get(p as usize) || st.hashing.contains(&p) {
                continue;
            }
            if !st.progress.contains_key(&p) {
                if pass == 1 || !storage.accepts(p) {
                    continue;
                }
                let size = info.piece_size(p);
                let pads = &info.piece_pads(p);
                let blocks = (0..block_count(size))
                    .map(|b| {
                        // Blocks entirely inside pad files need no download.
                        let (start, len) = (b as i32 * wire::BLOCK as i32, block_len(size, b) as i32);
                        let padded = pads.iter().any(|&(o, l)| o <= start && start + len <= o + l);
                        if padded {
                            Block::Received
                        } else {
                            Block::Missing
                        }
                    })
                    .collect();
                st.progress.insert(p, Progress { blocks, contributors: HashSet::new() });
            }
            let urgent = st.deadlines.contains_key(&p);
            let size = info.piece_size(p);
            let pr = st.progress.get_mut(&p).unwrap();
            for (i, block) in pr.blocks.iter_mut().enumerate() {
                if room == 0 {
                    break;
                }
                let b = BlockRef { piece: p as u32, offset: i as u32 * wire::BLOCK, length: block_len(size, i) };
                match *block {
                    Block::Missing if pass == 0 => {
                        *block = Block::Requested { first: key, second: None, at: now };
                    }
                    // Endgame for urgent pieces: a slow peer must not hold up playback.
                    Block::Requested { first, second: None, at }
                        if pass == 1 && urgent && first != key && now.duration_since(at) >= URGENT_DUPLICATE =>
                    {
                        *block = Block::Requested { first, second: Some(key), at };
                    }
                    _ => continue,
                }
                out.push(b);
                room -= 1;
            }
        }
    }
    out
}

/// Unchokes up to [`MAX_UNCHOKED`] interested peers, those sending us the most first.
fn choke_round(st: &mut State, rotate: bool) {
    let mut interested: Vec<(i64, PeerKey)> =
        st.peers.iter().filter(|(_, p)| p.peer_interested).map(|(k, p)| (p.down.per_second(), *k)).collect();
    interested.sort_by(|a, b| b.cmp(a));
    let unchoked_now = st.peers.values().filter(|p| !p.am_choking).count();
    if !rotate && unchoked_now >= MAX_UNCHOKED {
        return;
    }
    let keep: HashSet<PeerKey> = interested.iter().take(MAX_UNCHOKED).map(|(_, k)| *k).collect();
    for (k, peer) in st.peers.iter_mut() {
        let unchoke = keep.contains(k);
        if unchoke && peer.am_choking {
            peer.am_choking = false;
            peer.send(Message::Unchoke);
        } else if !unchoke && !peer.am_choking && rotate {
            peer.am_choking = true;
            peer.send(Message::Choke);
        }
    }
}

/// Starts fetching metadata when it's wanted and a peer offers it, trusting the peer with the fewest failures for its
/// size. After a failure only that peer is asked.
fn start_metadata_fetch(st: &mut State) {
    if st.info.is_some() || st.metadata_fetch.is_some() {
        return;
    }
    let best = st
        .peers
        .iter()
        .filter(|(_, p)| p.ext.metadata.is_some())
        .filter_map(|(k, p)| Some((p.metadata_failures, *k, p.ext.metadata_size.filter(|s| *s > 0 && *s <= MAX_METADATA)?)))
        .min();
    let Some((_, key, size)) = best else { return };
    let pieces = size.div_ceil(wire::METADATA_PIECE);
    st.metadata_fetch = Some(MetadataFetch {
        size,
        data: vec![0; size],
        received: vec![false; pieces],
        requested: vec![None; pieces],
        senders: HashSet::new(),
        only: st.metadata_failed.then_some(key),
        progress: Instant::now(),
    });
}

fn request_metadata(st: &mut State) {
    let Some(f) = st.metadata_fetch.as_mut() else { return };
    let now = Instant::now();
    for (k, peer) in st.peers.iter_mut() {
        let Some(id) = peer.ext.metadata else { continue };
        // Only those agreeing on the size can contribute.
        if peer.ext.metadata_size != Some(f.size) || f.only.is_some_and(|only| only != *k) {
            continue;
        }
        while peer.metadata_requests < 2 {
            let Some(i) = (0..f.received.len()).find(|&i| !f.received[i] && f.requested[i].is_none()) else { return };
            f.requested[i] = Some((*k, now));
            peer.metadata_requests += 1;
            peer.send(Message::Extended { id, payload: MetadataMessage::Request(i as u32).encode() });
        }
    }
}

fn request_layers(st: &mut State) {
    if st.layer_fetch.is_empty() {
        return;
    }
    let Some(info) = st.info.clone() else { return };
    let base = (info.piece_length as usize / merkle::BLOCK).trailing_zeros();
    let now = Instant::now();
    for (k, peer) in st.peers.iter_mut() {
        if !peer.v2 {
            continue;
        }
        for f in st.layer_fetch.iter_mut() {
            let (first, _) = info.file_pieces(f.file);
            if !peer.have.get(first as usize) && !peer.is_seed() {
                continue;
            }
            let width = f.count.next_power_of_two();
            let proof = (width / f.chunk).trailing_zeros();
            for c in 0..f.chunks.len() {
                if peer.layer_requests >= 4 {
                    break;
                }
                if f.chunks[c].is_some() || f.requested[c].is_some() {
                    continue;
                }
                f.requested[c] = Some((*k, now));
                peer.layer_requests += 1;
                peer.send(Message::HashRequest(HashRequest {
                    root: f.root,
                    base_layer: base,
                    index: (c * f.chunk) as u32,
                    length: f.chunk as u32,
                    proof_layers: proof,
                }));
            }
        }
    }
}

fn answer_hashes(st: &State, r: &HashRequest) -> Message {
    let reject = Message::HashReject(*r);
    let Some(info) = &st.info else { return reject };
    let base = (info.piece_length as usize / merkle::BLOCK).trailing_zeros();
    if r.base_layer != base || r.length > 8192 {
        return reject;
    }
    let Some(layer) = st.layers.get(&r.root) else { return reject };
    match merkle::answer(layer, info.piece_length as usize, r.index as usize, r.length as usize, r.proof_layers as usize) {
        Some(hashes) => Message::Hashes { request: *r, hashes },
        None => reject,
    }
}

/// What a piece must hash to.
pub enum Expected {
    Sha1(Id20),
    /// v2: the file data's length in this piece, the hash, and whether it is the root of a single-piece file.
    Merkle { data: usize, hash: Hash, whole_file: bool },
    /// Padding only: nothing to check.
    Nothing,
}

pub fn expected_hash(info: &Info, layers: &Layers, p: i32) -> Option<Expected> {
    if let Some(pieces) = &info.v1_pieces {
        return pieces.get(p as usize).map(|h| Expected::Sha1(*h));
    }
    let Some((file, index, data)) = info.v2_piece(p) else { return Some(Expected::Nothing) };
    let f = &info.files[file];
    let root = f.pieces_root?;
    if f.size <= i64::from(info.piece_length) {
        return Some(Expected::Merkle { data, hash: root, whole_file: true });
    }
    let layer = layers.get(&root)?;
    layer.get(index).map(|h| Expected::Merkle { data, hash: *h, whole_file: false })
}

pub fn verify_piece(storage: &Storage, info: &Info, p: i32, expected: &Expected) -> bool {
    let mut data = vec![0u8; info.piece_size(p) as usize];
    if storage.read(p, 0, &mut data).is_err() {
        return false;
    }
    matches_hash(info, &data, expected)
}

fn matches_hash(info: &Info, data: &[u8], expected: &Expected) -> bool {
    match expected {
        Expected::Sha1(h) => metainfo::sha1(data) == *h,
        Expected::Merkle { data: len, hash, whole_file } => {
            let file_data = &data[..*len];
            let got = if *whole_file {
                merkle::file_root(file_data, info.piece_length as usize)
            } else {
                merkle::piece_hash(file_data, info.piece_length as usize)
            };
            got == *hash
        }
        Expected::Nothing => true,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::Ipv4Addr;

    fn peer(t: &Torrent, port: u16) -> (PeerKey, mpsc::UnboundedReceiver<Out>) {
        let (tx, rx) = mpsc::unbounded_channel();
        let addr = SocketAddr::new(Ipv4Addr::LOCALHOST.into(), port);
        let key = t.attach(addr, Reserved::ours(false, false), false, true, tx, Arc::default(), (usize::MAX, true)).unwrap();
        (key, rx)
    }

    fn torrent() -> Arc<Torrent> {
        Torrent::new(1, Weak::new(), InfoHashes { v1: Some([7; 20]), v2: None }, None, Layers::new(), Vec::new())
    }

    #[test]
    fn unsolicited_metadata_cannot_cancel_requests_or_refresh_the_stall_timer() {
        let t = torrent();
        let (owner, _owner_rx) = peer(&t, 1000);
        let (other, _other_rx) = peer(&t, 1001);
        let size = wire::METADATA_PIECE + 1;
        let ext = ExtHandshake { metadata: Some(9), metadata_size: Some(size), ..ExtHandshake::default() };
        t.on_message(owner, Message::Extended { id: 0, payload: ext.encode() }).unwrap();
        let started = t.lock().metadata_fetch.as_ref().unwrap().progress;
        t.on_metadata_message(other, MetadataMessage::Reject(0));
        let reply = MetadataMessage::Data { piece: 0, total_size: size, data: vec![0; wire::METADATA_PIECE] };
        t.on_metadata_message(other, reply.clone());
        {
            let st = t.lock();
            let f = st.metadata_fetch.as_ref().unwrap();
            assert_eq!(f.requested[0].unwrap().0, owner);
            assert_eq!(f.progress, started);
            assert!(!f.received[0]);
            assert_eq!(st.peers[&owner].metadata_requests, 2);
        }
        t.on_metadata_message(owner, reply.clone());
        let progressed = t.lock().metadata_fetch.as_ref().unwrap().progress;
        t.on_metadata_message(owner, reply);
        let st = t.lock();
        let f = st.metadata_fetch.as_ref().unwrap();
        assert!(f.received[0]);
        assert_eq!(f.progress, progressed);
        assert_eq!(st.peers[&owner].metadata_requests, 1);
    }

    #[test]
    fn haves_before_the_metadata_are_bounded() {
        let t = torrent();
        let (key, _rx) = peer(&t, 1000);
        for p in 0..MAX_EARLY_HAVES as u32 + 1000 {
            t.on_message(key, Message::Have(p)).unwrap();
        }
        assert_eq!(t.lock().peers[&key].early_haves.len(), MAX_EARLY_HAVES);
    }

    #[test]
    fn peer_addresses_from_connections_and_handshakes_are_bounded() {
        let t = torrent();
        let mut keep = Vec::new();
        for port in 1..=2000u16 {
            let (key, rx) = peer(&t, port);
            keep.push(rx);
            let ext = ExtHandshake { listen_port: Some(port + 10_000), ..ExtHandshake::default() };
            t.on_message(key, Message::Extended { id: 0, payload: ext.encode() }).unwrap();
        }
        assert!(t.lock().candidates.len() <= MAX_CANDIDATES);
    }
}
