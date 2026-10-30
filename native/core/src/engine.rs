//! One swarm that streams torrents through bounded caches. Handles are 64-bit ids that are never reused, so a stale
//! id is rejected rather than reaching a newer torrent or reader. Every method is thread-safe.
//!
//! Locking: the registry lock is taken before a stream's state lock, never the other way round, and neither is held
//! while calling into the swarm, so the swarm's threads never wait for the engine.

use crate::cache::{cache_slots, CacheStats, PieceCache, PieceKind, WaitResult, STORAGE_OVERHEAD};
use crate::swarm::{
    parse_peer, DiskStats, Event, Metadata, Source, Swarm, SwarmTorrent, DEFAULT_PRIORITY, DONT_DOWNLOAD,
    LOW_PRIORITY, TOP_PRIORITY,
};
use std::collections::BTreeMap;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::Path;
use std::sync::atomic::{AtomicBool, AtomicI32, AtomicI64, Ordering};
use std::sync::{Arc, Condvar, Mutex, MutexGuard, OnceLock, Weak};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

/// Errors the app tells apart. The numbers are shared with the Kotlin side (`TorrentException`).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(i32)]
pub enum ErrorKind {
    InvalidHandle = 1,
    Cancelled = 2,
    Closed = 3,
    Timeout = 4,
    BadSource = 5,
    MetadataFailed = 6,
    PieceTooLarge = 7,
    CacheTooSmall = 8,
    LowStorage = 9,
    InvalidFile = 10,
    TorrentFailed = 11,
    EngineFailed = 12,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct EngineError {
    pub kind: ErrorKind,
    pub message: String,
}

impl EngineError {
    pub fn new(kind: ErrorKind, message: impl Into<String>) -> Self {
        EngineError { kind, message: message.into() }
    }
}

impl std::fmt::Display for EngineError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.message)
    }
}

impl std::error::Error for EngineError {}

pub type Result<T> = std::result::Result<T, EngineError>;

pub type LogSink = Arc<dyn Fn(&str) + Send + Sync>;

#[derive(Clone)]
pub struct Config {
    /// Private directory for payload cache files. Every `*.cache` file in it is deleted at startup: a previous
    /// process may have left files behind.
    pub cache_dir: String,
    pub cache_bytes: i64,
    /// Free space that must remain on the cache's filesystem beyond the cache itself.
    pub reserve_bytes: i64,
    pub max_piece_bytes: i32,
    /// A cache with fewer slots than this can't hold a read-ahead window and rewind margin.
    pub min_slots: i32,
    pub connections_limit: i32,
    pub listen_interfaces: String,
    pub dht: bool,
    pub lsd: bool,
    pub upnp: bool,
    pub natpmp: bool,
    pub pex: bool,
    /// uTP for incoming peers and when TCP can't connect.
    pub utp: bool,
    /// TCP for peers; off only to test uTP alone.
    pub tcp: bool,
    /// Message stream encryption: tried first on outgoing connections and accepted on incoming ones by default.
    pub encryption: crate::bt::mse::Policy,
    /// Optional live-test networking: disable LAN discovery and reject non-public peer/tracker/DHT addresses.
    pub public_network_only: bool,
    /// Tests run several local peers on one address.
    pub multiple_connections_per_ip: bool,
    /// PEM file with the trust roots for HTTPS trackers; empty uses the bundled Mozilla roots.
    pub ca_file: String,
    pub user_agent: String,
    pub read_ahead_seconds: i32,
    pub min_read_ahead_pieces: i32,
    /// Assumed until the app knows the media's bitrate (bytes per second).
    pub default_bitrate: i64,
    /// Receives diagnostics for the Developer view: peer disconnections and errors. Called on the engine thread.
    pub log: Option<LogSink>,
}

impl Default for Config {
    fn default() -> Self {
        Config {
            cache_dir: String::new(),
            cache_bytes: 256 << 20,
            reserve_bytes: 512 << 20,
            max_piece_bytes: 16 << 20,
            min_slots: 4,
            connections_limit: 40,
            listen_interfaces: "0.0.0.0:0,[::]:0".into(),
            dht: true,
            lsd: true,
            upnp: false,
            natpmp: false,
            pex: true,
            utp: true,
            tcp: true,
            encryption: crate::bt::mse::Policy::Enabled,
            public_network_only: false,
            multiple_connections_per_ip: false,
            ca_file: String::new(),
            user_agent: "Stream".into(),
            read_ahead_seconds: 45,
            min_read_ahead_pieces: 2,
            default_bitrate: 1_250_000,
            log: None,
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FileInfo {
    pub index: i32,
    pub path: String,
    pub size: i64,
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Stats {
    pub state: &'static str,
    pub error: String,
    pub error_kind: i32,
    pub peers: i32,
    pub seeds: i32,
    pub download_rate: i32,
    pub upload_rate: i32,
    pub total_download: i64,
    pub total_upload: i64,
    pub piece_length: i32,
    pub num_pieces: i32,
    pub hash_failures: i32,
    pub evictions_requested: i32,
    pub urgent_reservation_failures: i32,
    pub obsolete_partials: i32,
    pub verified_read_bytes: i64,
    pub cache: CacheStats,
    pub disk: DiskStats,
}

impl Stats {
    /// The JSON the app's diagnostics read.
    pub fn to_json(&self) -> String {
        format!(
            concat!(
                "{{\"state\":\"{}\",\"error\":\"{}\",\"errorKind\":{},\"peers\":{},\"seeds\":{},",
                "\"downloadRate\":{},\"uploadRate\":{},\"totalDownload\":{},\"totalUpload\":{},",
                "\"verifiedReadBytes\":{},\"urgentReservationFailures\":{},\"obsoletePartials\":{},",
                "\"pieceLength\":{},\"pieces\":{},\"hashFailures\":{},\"evictionsRequested\":{},",
                "\"cache\":{{\"slots\":{},\"used\":{},\"verified\":{},\"pinned\":{},\"evicted\":{},",
                "\"fileBytes\":{},\"allocatedBytes\":{}}},",
                "\"disk\":{{\"bufferBytes\":{},\"queuedWriteBytes\":{},\"blockedWrites\":{}}}}}"
            ),
            json_escape(self.state),
            json_escape(&self.error),
            self.error_kind,
            self.peers,
            self.seeds,
            self.download_rate,
            self.upload_rate,
            self.total_download,
            self.total_upload,
            self.verified_read_bytes,
            self.urgent_reservation_failures,
            self.obsolete_partials,
            self.piece_length,
            self.num_pieces,
            self.hash_failures,
            self.evictions_requested,
            self.cache.slots,
            self.cache.used,
            self.cache.verified,
            self.cache.pinned,
            self.cache.evicted,
            self.cache.file_bytes,
            self.cache.allocated_bytes,
            self.disk.buffer_bytes,
            self.disk.queued_write_bytes,
            self.disk.blocked_writes,
        )
    }
}

pub fn json_escape(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out
}

pub fn files_json(files: &[FileInfo]) -> String {
    let items: Vec<String> = files
        .iter()
        .map(|f| format!("{{\"index\":{},\"path\":\"{}\",\"size\":{}}}", f.index, json_escape(&f.path), f.size))
        .collect();
    format!("[{}]", items.join(","))
}

fn mib(bytes: i64) -> String {
    format!("{} MiB", (bytes + (1 << 20) - 1) >> 20)
}

fn now_ms() -> i64 {
    static EPOCH: OnceLock<Instant> = OnceLock::new();
    EPOCH.get_or_init(Instant::now).elapsed().as_millis() as i64
}

/// Deletes cache files a previous process left. Only `*.cache` files directly in the directory are touched.
fn clear_cache_files(dir: &Path) {
    let _ = std::fs::create_dir_all(dir);
    let Ok(entries) = std::fs::read_dir(dir) else { return };
    for entry in entries.flatten() {
        let path = entry.path();
        if entry.file_type().map(|t| t.is_file()).unwrap_or(false) && path.extension().is_some_and(|e| e == "cache") {
            let _ = std::fs::remove_file(path);
        }
    }
}

fn available_bytes(dir: &str) -> Option<i64> {
    let path = std::ffi::CString::new(dir).ok()?;
    // SAFETY: statvfs fills the zeroed struct; the path is a valid C string for the call.
    unsafe {
        let mut fs: libc::statvfs = std::mem::zeroed();
        if libc::statvfs(path.as_ptr(), &mut fs) != 0 {
            return None;
        }
        Some(fs.f_bavail as i64 * fs.f_frsize as i64)
    }
}

/// Wakes the engine thread. Called from the swarm's own threads: it only signals and takes no lock they could wait on.
pub struct Wake {
    woken: Mutex<bool>,
    cv: Condvar,
    stop: AtomicBool,
}

impl Wake {
    fn new() -> Self {
        Wake { woken: Mutex::new(false), cv: Condvar::new(), stop: AtomicBool::new(false) }
    }
    pub fn wake(&self) {
        *self.woken.lock().unwrap_or_else(|e| e.into_inner()) = true;
        self.cv.notify_one();
    }
    fn stop(&self) {
        let _guard = self.woken.lock().unwrap_or_else(|e| e.into_inner());
        self.stop.store(true, Ordering::SeqCst);
        self.cv.notify_all();
    }
    fn stopped(&self) -> bool {
        self.stop.load(Ordering::SeqCst)
    }
}

struct StreamState {
    info: Option<Arc<Metadata>>,
    cache: Option<Arc<PieceCache>>,
    error: String,
    error_type: ErrorKind,
    failed: bool,
    removed: bool,
    file: i32,
    bitrate: i64,
    ahead_seconds: i32,
    /// Where the read-ahead window starts when no reader is active: the last reading position.
    anchor: i64,
    readers: Vec<Weak<Reader>>,
}

/// What the controller last applied; only the engine thread touches it.
#[derive(Default)]
struct Applied {
    priorities: Vec<u8>,
    deadlines: Vec<i32>,
}

struct Stream {
    torrent: Box<dyn SwarmTorrent>,
    state: Mutex<StreamState>,
    changed: Condvar,
    applied: Mutex<Applied>,
    verified_read_bytes: AtomicI64,
    urgent_reservation_failures: AtomicI32,
    obsolete_partials: AtomicI32,
    evicting: AtomicBool,
    /// After an eviction the priorities are applied again, so the evicted pieces are wanted afresh where needed.
    priorities_stale: AtomicBool,
    hash_failures: AtomicI32,
    evictions: AtomicI32,
}

impl Stream {
    fn lock(&self) -> MutexGuard<'_, StreamState> {
        self.state.lock().unwrap_or_else(|e| e.into_inner())
    }
    fn failure(st: &StreamState) -> EngineError {
        EngineError::new(st.error_type, st.error.clone())
    }
}

struct Reader {
    torrent: Weak<Stream>,
    file: i32,
    cancelled: AtomicBool,
    /// [`UNPOSITIONED`] until the first read or seek: a reader opened for a seek must not pull the window back to
    /// the start of the file before it reads where it was opened for.
    position: AtomicI64,
    waiting: AtomicI32,
    last_read: AtomicI64,
}

#[derive(Default)]
struct Registry {
    streams: BTreeMap<u64, Arc<Stream>>,
    retiring: BTreeMap<u64, Arc<Stream>>,
    readers: BTreeMap<u64, Arc<Reader>>,
    next_id: u64,
}

struct Shared {
    config: Config,
    // Declared before the swarm: torrents are dropped before the session they belong to.
    registry: Mutex<Registry>,
    swarm: Box<dyn Swarm>,
    wake: Arc<Wake>,
}

pub struct Engine {
    shared: Arc<Shared>,
    thread: Mutex<Option<JoinHandle<()>>>,
}

/// A read found nothing within its wait; call again.
pub const WOULD_BLOCK: i32 = -1;

/// A reader's position before its first read or seek.
const UNPOSITIONED: i64 = -1;

impl Engine {
    /// An engine over Stream's own BitTorrent session.
    pub fn new(config: Config) -> Result<Engine> {
        Engine::with_swarm(config, |config, wake| {
            crate::bt::backend::RustSwarm::create(config, wake).map(|s| Box::new(s) as Box<dyn Swarm>)
        })
    }

    /// An engine over any backend; `make` receives the wake-up the swarm calls when it has events.
    pub fn with_swarm(
        config: Config,
        make: impl FnOnce(&Config, Arc<Wake>) -> Result<Box<dyn Swarm>>,
    ) -> Result<Engine> {
        if config.cache_dir.is_empty() || config.cache_dir == "/" {
            return Err(EngineError::new(ErrorKind::EngineFailed, "No private cache directory"));
        }
        clear_cache_files(Path::new(&config.cache_dir));
        if let Some(available) = available_bytes(&config.cache_dir) {
            let needed = config.cache_bytes + config.reserve_bytes;
            if available < needed {
                return Err(EngineError::new(
                    ErrorKind::LowStorage,
                    format!(
                        "Streaming needs {} of free storage ({} cache and {} to spare); {} is free. Free up space or choose a smaller cache.",
                        mib(needed),
                        mib(config.cache_bytes),
                        mib(config.reserve_bytes),
                        mib(available)
                    ),
                ));
            }
        }
        let wake = Arc::new(Wake::new());
        let swarm = make(&config, wake.clone())?;
        let shared = Arc::new(Shared {
            config,
            registry: Mutex::new(Registry { next_id: 1, ..Registry::default() }),
            swarm,
            wake,
        });
        let worker = shared.clone();
        let thread = std::thread::Builder::new()
            .name("stream-engine".into())
            .spawn(move || worker.run())
            .map_err(|e| EngineError::new(ErrorKind::EngineFailed, format!("Engine thread: {e}")))?;
        Ok(Engine { shared, thread: Mutex::new(Some(thread)) })
    }

    pub fn version(&self) -> String {
        self.shared.swarm.version()
    }

    /// Wakes every blocked call and refuses new work; dropping the engine finishes the shutdown.
    pub fn request_stop(&self) {
        self.shared.request_stop();
    }

    pub fn add(&self, source: &Source) -> Result<u64> {
        self.shared.add(source)
    }

    /// Waits for the file list; fails with timeout, cancelled, or the torrent's error.
    pub fn wait_metadata(&self, torrent: u64, timeout: Duration, cancelled: Option<&AtomicBool>) -> Result<Vec<FileInfo>> {
        self.shared.wait_metadata(torrent, timeout, cancelled)
    }

    /// Starts downloading the beginning of this file; other files stay untouched.
    pub fn select_file(&self, torrent: u64, file: i32) -> Result<()> {
        self.shared.select_file(torrent, file)
    }

    pub fn set_bitrate(&self, torrent: u64, bytes_per_second: i64) -> Result<()> {
        let t = self.shared.find_stream(torrent)?;
        if bytes_per_second > 0 {
            t.lock().bitrate = bytes_per_second;
        }
        Ok(())
    }

    pub fn set_demand(&self, torrent: u64, bytes_per_second: i64, ahead_seconds: i32) -> Result<()> {
        let t = self.shared.find_stream(torrent)?;
        {
            let mut st = t.lock();
            st.bitrate = bytes_per_second.clamp(16_000, 125_000_000);
            st.ahead_seconds = ahead_seconds.clamp(1, self.shared.config.read_ahead_seconds.max(1));
        }
        self.shared.wake.wake();
        Ok(())
    }

    pub fn metainfo(&self, torrent: u64) -> Result<Vec<u8>> {
        self.shared.find_stream(torrent)?.torrent.metainfo()
    }

    /// More peers to contact, "address:port".
    pub fn add_peers(&self, torrent: u64, peers: &[String]) -> Result<()> {
        let t = self.shared.find_stream(torrent)?;
        let addresses = peers.iter().map(|p| parse_peer(p)).collect::<Result<Vec<_>>>()?;
        for address in addresses {
            t.torrent.connect_peer(address);
        }
        Ok(())
    }

    pub fn stats(&self, torrent: u64) -> Result<Stats> {
        self.shared.stats(torrent)
    }

    pub fn remove(&self, torrent: u64) -> Result<()> {
        self.shared.remove(torrent)
    }

    pub fn open_reader(&self, torrent: u64, file: i32) -> Result<u64> {
        self.shared.open_reader(torrent, file)
    }

    pub fn file_size(&self, reader: u64) -> Result<i64> {
        let r = self.shared.find_reader(reader)?;
        let t = r.torrent.upgrade().ok_or_else(closed)?;
        let st = t.lock();
        let info = st.info.as_ref().ok_or_else(closed)?;
        Ok(info.file(r.file).map(|f| f.size).unwrap_or(0))
    }

    /// Copies verified data at a file position. Returns the bytes copied (at most to the end of a piece), 0 at the
    /// end of the file, or [`WOULD_BLOCK`] when nothing arrived within `wait`. Missing data is waited for and
    /// downloaded, never invented.
    pub fn read(&self, reader: u64, position: i64, out: &mut [u8], wait: Duration) -> Result<i32> {
        self.shared.read(reader, position, out, wait)
    }

    /// Moves a reader before its next read, so the window starts downloading there at once.
    pub fn seek(&self, reader: u64, position: i64) -> Result<()> {
        self.shared.seek(reader, position)
    }

    /// Wakes a blocked read, which then fails with cancelled; later reads fail too.
    pub fn cancel(&self, reader: u64) {
        self.shared.cancel(reader);
    }

    pub fn close_reader(&self, reader: u64) {
        self.shared.cancel(reader);
        self.shared.registry().readers.remove(&reader);
        self.shared.wake.wake();
    }
}

impl Drop for Engine {
    fn drop(&mut self) {
        self.shared.request_stop();
        let thread = self.thread.lock().unwrap_or_else(|e| e.into_inner()).take();
        if let Some(thread) = thread {
            let _ = thread.join();
        }
    }
}

fn closed() -> EngineError {
    EngineError::new(ErrorKind::Closed, "The torrent was closed")
}

fn cancelled_error() -> EngineError {
    EngineError::new(ErrorKind::Cancelled, "The read was cancelled")
}

impl Shared {
    fn registry(&self) -> MutexGuard<'_, Registry> {
        self.registry.lock().unwrap_or_else(|e| e.into_inner())
    }

    fn find_stream(&self, id: u64) -> Result<Arc<Stream>> {
        self.registry()
            .streams
            .get(&id)
            .cloned()
            .ok_or_else(|| EngineError::new(ErrorKind::InvalidHandle, "No torrent with this handle"))
    }

    fn find_reader(&self, id: u64) -> Result<Arc<Reader>> {
        self.registry()
            .readers
            .get(&id)
            .cloned()
            .ok_or_else(|| EngineError::new(ErrorKind::InvalidHandle, "No reader with this handle"))
    }

    fn request_stop(&self) {
        self.wake.stop();
        let reg = self.registry();
        for r in reg.readers.values() {
            r.cancelled.store(true, Ordering::SeqCst);
        }
        for t in reg.streams.values().chain(reg.retiring.values()) {
            let mut st = t.lock();
            st.failed = true;
            st.error_type = ErrorKind::Closed;
            t.changed.notify_all();
            if let Some(cache) = &st.cache {
                cache.close();
            }
        }
    }

    fn check_piece_length(&self, piece_length: i32) -> Result<()> {
        if piece_length > self.config.max_piece_bytes {
            return Err(EngineError::new(
                ErrorKind::PieceTooLarge,
                format!(
                    "This torrent uses {} pieces; streaming supports up to {}. Choose another source.",
                    mib(i64::from(piece_length)),
                    mib(i64::from(self.config.max_piece_bytes))
                ),
            ));
        }
        if cache_slots(self.config.cache_bytes, piece_length) < self.config.min_slots {
            return Err(EngineError::new(
                ErrorKind::CacheTooSmall,
                format!(
                    "This torrent's {} pieces need a cache of at least {}.",
                    mib(i64::from(piece_length)),
                    mib(i64::from(piece_length) * i64::from(self.config.min_slots) + STORAGE_OVERHEAD)
                ),
            ));
        }
        Ok(())
    }

    fn add(&self, source: &Source) -> Result<u64> {
        if self.wake.stopped() {
            return Err(EngineError::new(ErrorKind::Closed, "The engine is closed"));
        }
        if source.metainfo.is_empty() && source.magnet.is_empty() && source.info_hash.is_empty() {
            return Err(EngineError::new(ErrorKind::BadSource, "No torrent, magnet link or info hash"));
        }
        let id = {
            let mut reg = self.registry();
            let id = reg.next_id;
            reg.next_id += 1;
            id
        };
        let torrent = self.swarm.add(source, id, &|length| self.check_piece_length(length))?;
        let has_metadata = torrent.metadata().is_some();
        let t = Arc::new(Stream {
            torrent,
            state: Mutex::new(StreamState {
                info: None,
                cache: None,
                error: String::new(),
                error_type: ErrorKind::TorrentFailed,
                failed: false,
                removed: false,
                file: -1,
                bitrate: self.config.default_bitrate,
                ahead_seconds: 0,
                anchor: 0,
                readers: Vec::new(),
            }),
            changed: Condvar::new(),
            applied: Mutex::new(Applied::default()),
            verified_read_bytes: AtomicI64::new(0),
            urgent_reservation_failures: AtomicI32::new(0),
            obsolete_partials: AtomicI32::new(0),
            evicting: AtomicBool::new(false),
            priorities_stale: AtomicBool::new(false),
            hash_failures: AtomicI32::new(0),
            evictions: AtomicI32::new(0),
        });
        self.registry().streams.insert(id, t.clone());
        if has_metadata {
            self.on_metadata(&t);
        }
        self.wake.wake();
        Ok(id)
    }

    fn on_metadata(&self, t: &Arc<Stream>) {
        let Some(info) = t.torrent.metadata() else { return };
        if let Err(e) = self.check_piece_length(info.piece_length) {
            self.fail(t, e.kind, e.message);
            return;
        }
        let mut st = t.lock();
        if st.info.is_some() {
            return;
        }
        st.info = Some(info);
        t.changed.notify_all();
    }

    fn fail(&self, t: &Stream, kind: ErrorKind, message: String) {
        let cache = {
            let mut st = t.lock();
            if st.failed {
                return;
            }
            st.failed = true;
            st.error = message;
            st.error_type = kind;
            t.changed.notify_all();
            st.cache.clone()
        };
        if let Some(cache) = cache {
            cache.wake_all();
        }
        t.torrent.pause();
    }

    fn wait_metadata(&self, torrent: u64, timeout: Duration, cancelled: Option<&AtomicBool>) -> Result<Vec<FileInfo>> {
        let t = self.find_stream(torrent)?;
        let deadline = Instant::now() + timeout;
        let info = {
            let mut st = t.lock();
            while st.info.is_none() && !st.failed {
                if cancelled.is_some_and(|c| c.load(Ordering::SeqCst)) {
                    return Err(EngineError::new(ErrorKind::Cancelled, "Cancelled"));
                }
                let now = Instant::now();
                if now >= deadline {
                    return Err(EngineError::new(ErrorKind::Timeout, "No peer sent the torrent's metadata in time"));
                }
                // Short waits, so a cancellation is noticed.
                let slice = (deadline - now).min(Duration::from_millis(200));
                st = t.changed.wait_timeout(st, slice).unwrap_or_else(|e| e.into_inner()).0;
            }
            if st.failed {
                return Err(Stream::failure(&st));
            }
            st.info.clone().expect("metadata present")
        };
        Ok(info
            .files
            .iter()
            .enumerate()
            .filter(|(_, f)| !f.pad)
            .map(|(i, f)| FileInfo { index: i as i32, path: f.path.clone(), size: f.size })
            .collect())
    }

    fn valid_file(info: &Metadata, file: i32) -> Result<()> {
        info.file(file)
            .map(|_| ())
            .ok_or_else(|| EngineError::new(ErrorKind::InvalidFile, format!("The torrent has no file {file}")))
    }

    fn select_file(&self, torrent: u64, file: i32) -> Result<()> {
        let t = self.find_stream(torrent)?;
        {
            let mut st = t.lock();
            if st.failed {
                return Err(Stream::failure(&st));
            }
            let info = st
                .info
                .clone()
                .ok_or_else(|| EngineError::new(ErrorKind::InvalidFile, "The torrent's files aren't known yet"))?;
            // An explicit index must exist; it is never replaced by another file.
            Self::valid_file(&info, file)?;
            st.file = file;
            st.anchor = 0;
        }
        self.wake.wake();
        Ok(())
    }

    fn remove(&self, torrent: u64) -> Result<()> {
        let t = {
            let mut reg = self.registry();
            let Some(t) = reg.streams.remove(&torrent) else { return Ok(()) };
            reg.retiring.insert(torrent, t.clone());
            t
        };
        let retired = {
            let mut st = t.lock();
            st.failed = true;
            st.error = "The torrent was closed".into();
            st.error_type = ErrorKind::Closed;
            for r in st.readers.iter().filter_map(Weak::upgrade) {
                r.cancelled.store(true, Ordering::SeqCst);
            }
            t.changed.notify_all();
            if let Some(cache) = &st.cache {
                cache.close();
            }
            st.cache.take().map(|c| Arc::downgrade(&c))
        };
        t.torrent.remove();
        let deadline = Instant::now() + Duration::from_secs(5);
        {
            let mut st = t.lock();
            while !st.removed && !self.wake.stopped() {
                let now = Instant::now();
                if now >= deadline {
                    return Err(EngineError::new(ErrorKind::Timeout, "Torrent removal did not finish"));
                }
                st = t.changed.wait_timeout(st, deadline - now).unwrap_or_else(|e| e.into_inner()).0;
            }
        }
        self.registry().retiring.remove(&torrent);
        if let Some(retired) = retired {
            while retired.strong_count() > 0 {
                if Instant::now() >= deadline {
                    return Err(EngineError::new(ErrorKind::Timeout, "Torrent storage did not drain"));
                }
                std::thread::sleep(Duration::from_millis(5));
            }
        }
        Ok(())
    }

    fn stats(&self, torrent: u64) -> Result<Stats> {
        let t = self.find_stream(torrent)?;
        let mut ret = Stats::default();
        let cache = {
            let st = t.lock();
            ret.state = if st.failed {
                "failed"
            } else if st.info.is_none() {
                "metadata"
            } else if st.file < 0 {
                "ready"
            } else {
                "streaming"
            };
            ret.error = st.error.clone();
            ret.error_kind = if st.failed { st.error_type as i32 } else { 0 };
            if let Some(info) = &st.info {
                ret.piece_length = info.piece_length;
                ret.num_pieces = info.num_pieces();
            }
            st.cache.clone()
        };
        ret.hash_failures = t.hash_failures.load(Ordering::Relaxed);
        ret.evictions_requested = t.evictions.load(Ordering::Relaxed);
        ret.urgent_reservation_failures = t.urgent_reservation_failures.load(Ordering::Relaxed);
        ret.obsolete_partials = t.obsolete_partials.load(Ordering::Relaxed);
        ret.verified_read_bytes = t.verified_read_bytes.load(Ordering::Relaxed);
        if let Some(status) = t.torrent.status() {
            ret.peers = status.peers;
            ret.seeds = status.seeds;
            ret.download_rate = status.download_rate;
            ret.upload_rate = status.upload_rate;
            ret.total_download = status.total_download;
            ret.total_upload = status.total_upload;
        }
        if let Some(cache) = cache {
            ret.cache = cache.stats();
        }
        ret.disk = self.swarm.disk_stats();
        Ok(ret)
    }

    fn open_reader(&self, torrent: u64, file: i32) -> Result<u64> {
        let t = self.find_stream(torrent)?;
        {
            let st = t.lock();
            if st.failed {
                return Err(Stream::failure(&st));
            }
            let info =
                st.info.as_ref().ok_or_else(|| EngineError::new(ErrorKind::InvalidFile, "The torrent's files aren't known yet"))?;
            Self::valid_file(info, file)?;
        }
        let r = Arc::new(Reader {
            torrent: Arc::downgrade(&t),
            file,
            cancelled: AtomicBool::new(false),
            position: AtomicI64::new(UNPOSITIONED),
            waiting: AtomicI32::new(-1),
            last_read: AtomicI64::new(now_ms()),
        });
        let id = {
            let mut reg = self.registry();
            let id = reg.next_id;
            reg.next_id += 1;
            reg.readers.insert(id, r.clone());
            id
        };
        t.lock().readers.push(Arc::downgrade(&r));
        Ok(id)
    }

    /// The reader's torrent, layout and file, refusing cancelled readers and failed torrents.
    fn reader_context(&self, id: u64) -> Result<(Arc<Reader>, Arc<Stream>, Arc<Metadata>, Option<Arc<PieceCache>>)> {
        let r = self.find_reader(id)?;
        if r.cancelled.load(Ordering::SeqCst) {
            return Err(cancelled_error());
        }
        let t = r.torrent.upgrade().ok_or_else(closed)?;
        let (info, cache) = {
            let st = t.lock();
            if st.failed {
                return Err(Stream::failure(&st));
            }
            (st.info.clone().ok_or_else(closed)?, st.cache.clone())
        };
        Ok((r, t, info, cache))
    }

    fn seek(&self, id: u64, position: i64) -> Result<()> {
        let (r, _t, info, _cache) = self.reader_context(id)?;
        if position < 0 {
            return Err(EngineError::new(ErrorKind::InvalidFile, "Invalid seek position"));
        }
        let file = info.file(r.file).ok_or_else(closed)?;
        r.position.store(position, Ordering::SeqCst);
        r.last_read.store(now_ms(), Ordering::SeqCst);
        if position < file.size {
            let (piece, _) = info.map_file(file, position);
            if r.waiting.swap(piece, Ordering::SeqCst) != piece {
                self.wake.wake();
            }
        }
        Ok(())
    }

    fn read(&self, id: u64, position: i64, out: &mut [u8], wait: Duration) -> Result<i32> {
        let (r, t, info, cache) = self.reader_context(id)?;
        if position < 0 {
            return Err(EngineError::new(ErrorKind::InvalidFile, "Invalid read position"));
        }
        let file = info.file(r.file).ok_or_else(closed)?;
        let size = file.size;
        if position >= size || out.is_empty() {
            return Ok(0);
        }
        let wanted = (out.len() as i64).min(size - position) as i32;
        let (piece, start) = info.map_file(file, position);
        let chunk = wanted.min(info.piece_size(piece) - start);

        r.position.store(position, Ordering::SeqCst);
        r.last_read.store(now_ms(), Ordering::SeqCst);
        if r.waiting.swap(piece, Ordering::SeqCst) != piece {
            self.wake.wake();
        }

        let deadline = Instant::now() + wait;
        let cache = match cache {
            Some(cache) => cache,
            None => {
                // The torrent's storage appears right after its metadata; wait for the controller to find it, then
                // for the data in the rest of the wait.
                let st = t.lock();
                let (st, _) = t
                    .changed
                    .wait_timeout_while(st, wait, |st| st.cache.is_none() && !st.failed && !r.cancelled.load(Ordering::SeqCst))
                    .unwrap_or_else(|e| e.into_inner());
                if st.failed {
                    return Err(Stream::failure(&st));
                }
                if r.cancelled.load(Ordering::SeqCst) {
                    return Err(cancelled_error());
                }
                match st.cache.clone() {
                    Some(cache) => cache,
                    None => return Ok(WOULD_BLOCK),
                }
            }
        };

        match cache.wait_verified(piece, deadline, &r.cancelled) {
            WaitResult::Cancelled => return Err(cancelled_error()),
            WaitResult::Closed => {
                let st = t.lock();
                return Err(if st.failed { Stream::failure(&st) } else { closed() });
            }
            WaitResult::Timeout => {
                let st = t.lock();
                return if st.failed { Err(Stream::failure(&st)) } else { Ok(WOULD_BLOCK) };
            }
            WaitResult::Ready => {}
        }
        // The piece may have been evicted since; the next call waits for it again.
        match cache.read_verified(piece, start, &mut out[..chunk as usize]) {
            Ok(true) => {}
            Ok(false) => return Ok(WOULD_BLOCK),
            Err(e) => {
                return Err(EngineError::new(ErrorKind::EngineFailed, format!("Cache read failed: {e:?}")));
            }
        }
        r.waiting.store(-1, Ordering::SeqCst);
        t.verified_read_bytes.fetch_add(i64::from(chunk), Ordering::Relaxed);
        r.position.store(position + i64::from(chunk), Ordering::SeqCst);
        // The window follows the reader; moving into the next piece is worth an immediate update.
        if start + chunk == info.piece_size(piece) {
            self.wake.wake();
        }
        Ok(chunk)
    }

    fn cancel(&self, id: u64) {
        let Ok(r) = self.find_reader(id) else { return };
        r.cancelled.store(true, Ordering::SeqCst);
        if let Some(t) = r.torrent.upgrade() {
            let cache = {
                let st = t.lock();
                t.changed.notify_all();
                st.cache.clone()
            };
            if let Some(cache) = cache {
                cache.wake_all();
            }
        }
    }

    fn run(&self) {
        let mut next = Instant::now();
        let mut events = Vec::new();
        loop {
            {
                let woken = self.wake.woken.lock().unwrap_or_else(|e| e.into_inner());
                let timeout = next.saturating_duration_since(Instant::now());
                let (mut woken, _) = self
                    .wake
                    .cv
                    .wait_timeout_while(woken, timeout, |w| !*w && !self.wake.stopped())
                    .unwrap_or_else(|e| e.into_inner());
                if self.wake.stopped() {
                    return;
                }
                *woken = false;
            }
            // A torrent removed mid-update can make a backend call fail; the next pass no longer sees it.
            let _ = catch_unwind(AssertUnwindSafe(|| {
                self.handle_events(&mut events);
                let streams: Vec<Arc<Stream>> = self.registry().streams.values().cloned().collect();
                for t in &streams {
                    self.update(t);
                }
            }));
            next = Instant::now() + Duration::from_millis(100);
        }
    }

    fn find_tagged(&self, tag: u64) -> Option<Arc<Stream>> {
        let reg = self.registry();
        reg.streams.get(&tag).or_else(|| reg.retiring.get(&tag)).cloned()
    }

    fn handle_events(&self, events: &mut Vec<Event>) {
        events.clear();
        self.swarm.poll(events);
        for event in events.drain(..) {
            match event {
                Event::Log(line) => {
                    if let Some(log) = &self.config.log {
                        log(&line);
                    }
                }
                Event::Removed { tag } => {
                    if let Some(t) = self.find_tagged(tag) {
                        t.lock().removed = true;
                        t.changed.notify_all();
                    }
                }
                Event::MetadataReceived { tag } => {
                    if let Some(t) = self.find_tagged(tag) {
                        self.on_metadata(&t);
                    }
                }
                Event::PieceFinished { tag, piece } => {
                    let Some(t) = self.find_tagged(tag) else { continue };
                    let cache = {
                        let mut st = t.lock();
                        // A queued completion must not reattach storage while remove() is draining it.
                        if st.failed {
                            continue;
                        }
                        if st.cache.is_none() && st.info.is_some() {
                            st.cache = t.torrent.cache();
                        }
                        t.changed.notify_all();
                        st.cache.clone()
                    };
                    if let Some(cache) = cache {
                        cache.set_verified(piece);
                    }
                }
                Event::HashFailed { tag } => {
                    if let Some(t) = self.find_tagged(tag) {
                        t.hash_failures.fetch_add(1, Ordering::Relaxed);
                    }
                }
                Event::MetadataFailed { tag, message } => {
                    if let Some(t) = self.find_tagged(tag) {
                        self.fail(&t, ErrorKind::MetadataFailed, format!("The torrent's metadata is invalid: {message}"));
                    }
                }
                Event::TorrentError { tag, message } => {
                    if let Some(t) = self.find_tagged(tag) {
                        self.fail(&t, ErrorKind::TorrentFailed, message);
                    }
                }
                Event::FileError { tag, message } => {
                    if let Some(t) = self.find_tagged(tag) {
                        self.fail(&t, ErrorKind::TorrentFailed, format!("Cache error: {message}"));
                    }
                }
            }
        }
    }

    /// The read-ahead controller. From the active reader's position it keeps a window of pieces covering roughly
    /// `read_ahead_seconds` of media, bounded by the cache: pieces a reader is waiting for come first and get
    /// deadlines, partial pieces outside the window finish at the lowest priority, and everything else stays
    /// undownloaded. Slots are reserved before a piece may download; when none is free, the least recently used
    /// verified pieces outside the window and its margins are evicted.
    fn update(&self, t: &Arc<Stream>) {
        let mut waiting: Vec<i32> = Vec::new();
        let (cache, info, file, bitrate, ahead_seconds, anchor) = {
            let mut st = t.lock();
            if st.failed || st.info.is_none() || st.file < 0 {
                return;
            }
            if st.cache.is_none() {
                st.cache = t.torrent.cache();
                if st.cache.is_some() {
                    t.changed.notify_all();
                }
            }
            let Some(cache) = st.cache.clone() else { return };
            let file = st.file;
            let mut primary: Option<Arc<Reader>> = None;
            st.readers.retain(|weak| {
                let Some(r) = weak.upgrade() else { return false };
                if r.cancelled.load(Ordering::SeqCst) {
                    return false;
                }
                // A reader that hasn't read or seeked yet doesn't place the window; the last position still does.
                if r.file == file && r.position.load(Ordering::SeqCst) != UNPOSITIONED {
                    if primary.as_ref().is_none_or(|p| r.last_read.load(Ordering::SeqCst) > p.last_read.load(Ordering::SeqCst)) {
                        primary = Some(r.clone());
                    }
                    let w = r.waiting.load(Ordering::SeqCst);
                    if w >= 0 {
                        waiting.push(w);
                    }
                }
                true
            });
            if let Some(primary) = primary {
                st.anchor = primary.position.load(Ordering::SeqCst);
            }
            let ahead = if st.ahead_seconds > 0 { st.ahead_seconds } else { self.config.read_ahead_seconds };
            (cache, st.info.clone().unwrap(), file, st.bitrate, ahead, st.anchor)
        };

        let Some(entry) = info.file(file) else { return };
        let size = entry.size;
        if size <= 0 {
            return;
        }
        let first = info.map_file(entry, 0).0;
        let last = info.map_file(entry, size - 1).0;
        let head = info.map_file(entry, anchor.clamp(0, size - 1)).0;
        let num_pieces = info.num_pieces() as usize;
        let slots = cache.slot_count();
        // Large caches also keep the file's first and last pieces, where containers keep headers and indexes.
        let keep_edges = slots >= 8;
        let margin = 1 /* free spare */ + 1 /* behind the reader */ + if keep_edges { 2 } else { 0 };
        let max_ahead = (slots - margin - waiting.len() as i32).max(1);
        let for_time = (f64::from(ahead_seconds) * bitrate as f64 / f64::from(info.piece_length)).ceil() as i32;
        let ahead = max_ahead.min(self.config.min_read_ahead_pieces.max(for_time));

        let mut wanted = vec![false; num_pieces];
        let mut keep = vec![false; num_pieces];
        let mut desired: Vec<i32> = Vec::new();
        let mut add = |p: i32, desired: &mut Vec<i32>| {
            let i = p as usize;
            if i >= num_pieces || wanted[i] {
                return;
            }
            wanted[i] = true;
            keep[i] = true;
            desired.push(p);
        };
        for &w in &waiting {
            add(w, &mut desired);
        }
        for i in 0..ahead {
            let p = head + i;
            if p > last {
                break;
            }
            add(p, &mut desired);
        }
        // Waited-for pieces, the reader's piece and the next are urgent.
        let urgent_count = waiting.len() + 2;
        if head > first {
            keep[(head - 1) as usize] = true;
        }
        if keep_edges {
            keep[first as usize] = true;
            keep[last as usize] = true;
        }

        let mut priorities = vec![DONT_DOWNLOAD; num_pieces];
        let mut urgent: Vec<i32> = Vec::new();
        let (mut need, mut urgent_need, mut obsolete_partials) = (0, 0, 0);
        for (i, &p) in desired.iter().enumerate() {
            if cache.verified(p) {
                continue;
            }
            let is_urgent = i < urgent_count;
            if !cache.reserve(p) {
                need += 1;
                if is_urgent {
                    urgent_need += 1;
                }
                continue;
            }
            priorities[p as usize] = if is_urgent { TOP_PRIORITY } else { DEFAULT_PRIORITY };
            if is_urgent {
                urgent.push(p);
            }
        }
        for (p, kind) in cache.pieces() {
            let i = p as usize;
            if kind == PieceKind::Complete && t.torrent.have_piece(p) {
                // A dropped piece-finished event must not leave a reader waiting.
                cache.set_verified(p);
                continue;
            }
            if priorities[i] != DONT_DOWNLOAD {
                continue;
            }
            if kind == PieceKind::Partial && !keep[i] {
                obsolete_partials += 1;
            }
            match kind {
                // Finish what was started; then it can be evicted.
                PieceKind::Partial | PieceKind::Complete => priorities[i] = LOW_PRIORITY,
                PieceKind::Reserved if !keep[i] => cache.unreserve(p),
                _ => {}
            }
        }

        t.urgent_reservation_failures.store(urgent_need, Ordering::Relaxed);
        t.obsolete_partials.store(obsolete_partials, Ordering::Relaxed);
        {
            let mut applied = t.applied.lock().unwrap_or_else(|e| e.into_inner());
            if t.priorities_stale.swap(false, Ordering::SeqCst) {
                applied.priorities.clear();
            }
            if priorities != applied.priorities {
                t.torrent.prioritize_pieces(&priorities);
                applied.priorities = priorities;
            }
            if urgent != applied.deadlines {
                // A seek leaves old deadlines behind; clear them so they don't compete with the new position.
                t.torrent.clear_piece_deadlines();
                for (i, &p) in urgent.iter().enumerate() {
                    t.torrent.set_piece_deadline(p, i as i32 * 500);
                }
                applied.deadlines = urgent;
            }
        }

        let blocked = self.swarm.disk_stats().blocked_writes;
        let missing = need + 1 + i32::from(blocked > 0) - cache.free_slots();
        if missing > 0 && !t.evicting.load(Ordering::SeqCst) {
            let candidates = cache.eviction_candidates(missing, |p| keep.get(p as usize).copied().unwrap_or(false));
            if !candidates.is_empty() {
                self.evict(t, cache, candidates);
            }
        }
    }

    fn evict(&self, t: &Arc<Stream>, cache: Arc<PieceCache>, pieces: Vec<i32>) {
        t.evicting.store(true, Ordering::SeqCst);
        t.evictions.fetch_add(1, Ordering::Relaxed);
        cache.begin_release(&pieces);
        let weak = Arc::downgrade(t);
        let wake = self.wake.clone();
        let requested = pieces.clone();
        // Runs on the swarm's thread once the torrent no longer has the pieces. Disk jobs for them queued before
        // then still complete: the backend queues their release behind them.
        t.torrent.evict_pieces(
            pieces,
            Box::new(move |evicted: Vec<i32>| {
                for p in requested.iter().filter(|p| !evicted.contains(p)) {
                    cache.cancel_release(*p);
                }
                if let Some(s) = weak.upgrade() {
                    s.priorities_stale.store(true, Ordering::SeqCst);
                    s.evicting.store(false, Ordering::SeqCst);
                }
                wake.wake();
            }),
        );
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::cache::tests::temp_dir;
    use crate::swarm::{EvictDone, FileEntry, PieceLengthCheck, TorrentStatus};
    use std::net::SocketAddr;

    const PIECE: i32 = 32 * 1024;
    const PIECES: i32 = 40;
    const SIZE: i64 = PIECES as i64 * PIECE as i64 - 100;

    type Queue = Arc<Mutex<Vec<Event>>>;
    /// Writes that found no free slot, retried at every poll as a real swarm requests the blocks again.
    type Retries = Arc<Mutex<Vec<(u64, Arc<PieceCache>, i32, Vec<u8>)>>>;

    /// A swarm whose torrents "download" a piece the moment it is prioritized, from deterministic bytes.
    struct FakeSwarm {
        events: Queue,
        retries: Retries,
        wake: Arc<Wake>,
        dir: std::path::PathBuf,
        slots: i32,
    }

    struct FakeTorrent {
        tag: u64,
        meta: Arc<Metadata>,
        /// The backend's own reference, dropped at removal like the swarm's storage.
        storage: Mutex<Option<Arc<PieceCache>>>,
        retries: Retries,
        events: Queue,
        wake: Arc<Wake>,
    }

    fn byte(p: i64, i: i64) -> u8 {
        (p * 31 + i) as u8
    }

    impl FakeTorrent {
        fn storage(&self) -> Option<Arc<PieceCache>> {
            self.storage.lock().unwrap().clone()
        }
    }

    impl SwarmTorrent for FakeTorrent {
        fn metadata(&self) -> Option<Arc<Metadata>> {
            Some(self.meta.clone())
        }
        fn cache(&self) -> Option<Arc<PieceCache>> {
            self.storage()
        }
        fn prioritize_pieces(&self, priorities: &[u8]) {
            let Some(cache) = self.storage() else { return };
            for (p, &prio) in priorities.iter().enumerate() {
                let p = p as i32;
                if prio == DONT_DOWNLOAD || cache.complete(p) {
                    continue;
                }
                let data: Vec<u8> = (0..self.meta.piece_size(p) as i64).map(|i| byte(i64::from(p), i)).collect();
                if cache.write(p, 0, &data).unwrap() {
                    self.events.lock().unwrap().push(Event::PieceFinished { tag: self.tag, piece: p });
                } else {
                    self.retries.lock().unwrap().push((self.tag, cache.clone(), p, data));
                }
            }
            self.wake.wake();
        }
        fn clear_piece_deadlines(&self) {}
        fn set_piece_deadline(&self, _piece: i32, _milliseconds: i32) {}
        fn have_piece(&self, piece: i32) -> bool {
            self.storage().is_some_and(|c| c.complete(piece))
        }
        fn evict_pieces(&self, pieces: Vec<i32>, done: EvictDone) {
            if let Some(cache) = self.storage() {
                for &p in &pieces {
                    cache.release(p);
                }
            }
            done(pieces);
        }
        fn connect_peer(&self, _address: SocketAddr) {}
        fn status(&self) -> Option<TorrentStatus> {
            Some(TorrentStatus::default())
        }
        fn metainfo(&self) -> Result<Vec<u8>> {
            Ok(Vec::new())
        }
        fn pause(&self) {}
        fn remove(&self) {
            drop(self.storage.lock().unwrap().take());
            self.retries.lock().unwrap().retain(|r| r.0 != self.tag);
            self.events.lock().unwrap().push(Event::Removed { tag: self.tag });
            self.wake.wake();
        }
    }

    impl Swarm for FakeSwarm {
        fn add(&self, _source: &Source, tag: u64, check: PieceLengthCheck<'_>) -> Result<Box<dyn SwarmTorrent>> {
            check(PIECE)?;
            let mut sizes = vec![PIECE; PIECES as usize];
            *sizes.last_mut().unwrap() -= 100;
            let meta = Arc::new(Metadata {
                piece_length: PIECE,
                piece_sizes: sizes.clone(),
                files: vec![FileEntry { path: "film.mkv".into(), size: SIZE, offset: 0, pad: false }],
            });
            let path = self.dir.join(format!("{tag}.cache"));
            let cache = Arc::new(PieceCache::new(path, PIECE, sizes, Vec::new(), self.slots, true).unwrap());
            Ok(Box::new(FakeTorrent {
                tag,
                meta,
                storage: Mutex::new(Some(cache)),
                retries: self.retries.clone(),
                events: self.events.clone(),
                wake: self.wake.clone(),
            }))
        }
        fn poll(&self, out: &mut Vec<Event>) {
            self.retries.lock().unwrap().retain(|(tag, cache, p, data)| {
                if cache.complete(*p) {
                    return false;
                }
                let written = cache.write(*p, 0, data).unwrap_or(false);
                if written {
                    self.events.lock().unwrap().push(Event::PieceFinished { tag: *tag, piece: *p });
                }
                !written
            });
            out.append(&mut self.events.lock().unwrap());
        }
        fn disk_stats(&self) -> DiskStats {
            DiskStats::default()
        }
        fn version(&self) -> String {
            "fake".into()
        }
    }

    fn engine_with(name: &str, cache_bytes: i64) -> Engine {
        let dir = temp_dir(name);
        let config = Config { cache_dir: dir.to_string_lossy().into(), cache_bytes, reserve_bytes: 0, ..Config::default() };
        let cache_dir = temp_dir(&format!("{name}-storage"));
        Engine::with_swarm(config, move |config, wake| {
            let slots = cache_slots(config.cache_bytes, PIECE);
            Ok(Box::new(FakeSwarm { events: Arc::default(), retries: Arc::default(), wake, dir: cache_dir, slots }) as Box<dyn Swarm>)
        })
        .unwrap()
    }

    fn engine(name: &str) -> Engine {
        // Ten slots for forty pieces.
        engine_with(name, 10 * i64::from(PIECE) + STORAGE_OVERHEAD)
    }

    fn hash_source(byte: &str) -> Source {
        Source { info_hash: byte.repeat(20), ..Source::default() }
    }

    fn read_range(engine: &Engine, reader: u64, from: i64, to: i64) -> Vec<u8> {
        let mut data = Vec::new();
        let mut position = from;
        let mut buf = vec![0u8; 50_000];
        while position < to {
            let want = ((to - position) as usize).min(buf.len());
            let n = engine.read(reader, position, &mut buf[..want], Duration::from_secs(5)).unwrap();
            assert!(n > 0, "a prioritized piece arrives at once in the fake swarm, got {n}");
            data.extend_from_slice(&buf[..n as usize]);
            position += i64::from(n);
        }
        data
    }

    fn expected(from: i64, to: i64) -> Vec<u8> {
        let pl = i64::from(PIECE);
        (from..to).map(|i| byte(i / pl, i % pl)).collect()
    }

    #[test]
    fn reads_turn_a_small_cache_over_and_rewind_into_evicted_data() {
        let engine = engine("turnover");
        let t = engine.add(&hash_source("00")).unwrap();
        let files = engine.wait_metadata(t, Duration::from_secs(1), None).unwrap();
        assert_eq!(files, vec![FileInfo { index: 0, path: "film.mkv".into(), size: SIZE }]);
        engine.select_file(t, 0).unwrap();
        let reader = engine.open_reader(t, 0).unwrap();
        assert_eq!(engine.file_size(reader).unwrap(), SIZE);
        assert_eq!(read_range(&engine, reader, 0, SIZE), expected(0, SIZE));
        let stats = engine.stats(t).unwrap();
        assert_eq!(stats.state, "streaming");
        assert!(stats.cache.evicted > 0, "forty pieces passed through ten slots");
        assert!(stats.cache.used <= stats.cache.slots);
        assert_eq!(stats.verified_read_bytes, SIZE);
        assert_eq!(read_range(&engine, reader, 0, 100_000), expected(0, 100_000), "rewind downloads again");
        assert_eq!(engine.read(reader, SIZE, &mut [0u8; 4], Duration::ZERO).unwrap(), 0, "end of file");
        engine.close_reader(reader);
        engine.remove(t).unwrap();
        assert_eq!(engine.stats(t).unwrap_err().kind, ErrorKind::InvalidHandle);
    }

    #[test]
    fn seek_starts_the_window_before_the_first_read() {
        let engine = engine("seek");
        let t = engine.add(&hash_source("11")).unwrap();
        engine.wait_metadata(t, Duration::from_secs(1), None).unwrap();
        engine.select_file(t, 0).unwrap();
        let reader = engine.open_reader(t, 0).unwrap();
        let target = 30 * i64::from(PIECE) + 5;
        engine.seek(reader, target).unwrap();
        let deadline = Instant::now() + Duration::from_secs(5);
        while engine.stats(t).unwrap().cache.verified == 0 {
            assert!(Instant::now() < deadline, "the window starts at the seek target");
            std::thread::sleep(Duration::from_millis(5));
        }
        assert_eq!(read_range(&engine, reader, target, target + 10), expected(target, target + 10));
        assert_eq!(engine.seek(reader, -1).unwrap_err().kind, ErrorKind::InvalidFile);
    }

    #[test]
    fn cancel_wakes_a_blocked_read_and_stale_handles_are_refused() {
        let engine = Arc::new(engine("cancel"));
        let t = engine.add(&hash_source("22")).unwrap();
        engine.wait_metadata(t, Duration::from_secs(1), None).unwrap();
        // No file selected: nothing downloads, so the read waits until cancelled.
        let reader = engine.open_reader(t, 0).unwrap();
        let blocked = {
            let engine = engine.clone();
            std::thread::spawn(move || engine.read(reader, 0, &mut [0u8; 1], Duration::from_secs(30)))
        };
        std::thread::sleep(Duration::from_millis(100));
        engine.cancel(reader);
        assert_eq!(blocked.join().unwrap().unwrap_err().kind, ErrorKind::Cancelled);
        assert_eq!(engine.read(reader, 0, &mut [0u8; 1], Duration::ZERO).unwrap_err().kind, ErrorKind::Cancelled);
        assert_eq!(engine.open_reader(t, 3).unwrap_err().kind, ErrorKind::InvalidFile);
        assert_eq!(engine.read(999, 0, &mut [0u8; 1], Duration::ZERO).unwrap_err().kind, ErrorKind::InvalidHandle);
    }

    #[test]
    fn stopping_wakes_blocked_reads_and_refuses_new_torrents() {
        let engine = Arc::new(engine("stop"));
        let t = engine.add(&hash_source("33")).unwrap();
        engine.wait_metadata(t, Duration::from_secs(1), None).unwrap();
        let reader = engine.open_reader(t, 0).unwrap();
        let blocked = {
            let engine = engine.clone();
            std::thread::spawn(move || engine.read(reader, 0, &mut [0u8; 1], Duration::from_secs(30)))
        };
        std::thread::sleep(Duration::from_millis(100));
        let started = Instant::now();
        engine.request_stop();
        assert!(blocked.join().unwrap().is_err());
        assert!(started.elapsed() < Duration::from_secs(5));
        assert_eq!(engine.add(&hash_source("44")).unwrap_err().kind, ErrorKind::Closed);
    }

    #[test]
    fn sources_and_budgets_are_checked() {
        // 32 KiB pieces need four slots: three are too few.
        let engine = engine_with("refuse", 3 * i64::from(PIECE) + STORAGE_OVERHEAD);
        assert_eq!(engine.add(&Source::default()).unwrap_err().kind, ErrorKind::BadSource);
        let error = engine.add(&hash_source("55")).unwrap_err();
        assert_eq!(error.kind, ErrorKind::CacheTooSmall);
        assert!(error.message.contains("need a cache of at least"), "{}", error.message);
        let none = Config { cache_dir: String::new(), ..Config::default() };
        assert_eq!(Engine::with_swarm(none, |_, _| unreachable!()).err().unwrap().kind, ErrorKind::EngineFailed);
    }

    #[test]
    fn port_mapping_is_off_unless_asked_for() {
        // Not implemented at all (see native/README.md); the defaults must never ask for it either.
        let config = Config::default();
        assert!(!config.upnp && !config.natpmp);
    }

    #[test]
    fn stats_and_files_serialize_as_the_app_reads_them() {
        let stats = Stats { state: "failed", error: "bad \"name\"\n".into(), error_kind: 11, ..Stats::default() };
        let json = stats.to_json();
        assert!(json.starts_with("{\"state\":\"failed\",\"error\":\"bad \\\"name\\\"\\n\",\"errorKind\":11,"), "{json}");
        assert!(json.ends_with("\"blockedWrites\":0}}"), "{json}");
        let files = files_json(&[FileInfo { index: 2, path: "dir/ünï 🎬.mkv".into(), size: 7 }]);
        assert_eq!(files, "[{\"index\":2,\"path\":\"dir/ünï 🎬.mkv\",\"size\":7}]");
    }
}
