//! The engine's handle-based C ABI (`native/include/stream_core.h`): create, add, select, read, seek, cancel, close
//! and stats. Engines, torrents and readers are 64-bit handles that are never reused. Every call leases its engine,
//! so closing an engine while a read blocks in another thread can't free it underneath; close waits for the leases.
//!
//! Functions return 0 or an error kind (`ErrorKind`'s numbers); `stream_last_error` gives the calling thread's
//! last message.

use crate::cache::CacheStats;
use crate::cache_ffi::write_message;
use crate::engine::{Config, Engine, EngineError, ErrorKind, FileInfo, Result, Stats};
use crate::swarm::{DiskStats, Source};
use std::cell::RefCell;
use std::collections::{BTreeMap, VecDeque};
use std::ffi::{c_char, c_int, c_void, CStr, CString};
use std::sync::atomic::AtomicBool;
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::Duration;

/// Recent diagnostics kept per engine for the Developer view.
const LOG_LINES: usize = 200;

pub(crate) struct Entry {
    pub engine: Engine,
    /// Read by the JNI bridge's diagnostics.
    #[cfg_attr(not(feature = "libtorrent"), allow(dead_code))]
    pub log: Arc<Mutex<VecDeque<String>>>,
}

struct Registry {
    engines: BTreeMap<u64, Arc<Entry>>,
    next: u64,
}

static REGISTRY: Mutex<Registry> = Mutex::new(Registry { engines: BTreeMap::new(), next: 1 });

fn registry() -> MutexGuard<'static, Registry> {
    REGISTRY.lock().unwrap_or_else(|e| e.into_inner())
}

/// Creates an engine; its diagnostics go to its log ring and to `forward`, if any.
pub(crate) fn create_engine(mut config: Config) -> Result<u64> {
    let log: Arc<Mutex<VecDeque<String>>> = Arc::default();
    let ring = Arc::downgrade(&log);
    let forward = config.log.take();
    config.log = Some(Arc::new(move |line: &str| {
        if let Some(ring) = ring.upgrade() {
            let mut ring = ring.lock().unwrap_or_else(|e| e.into_inner());
            ring.push_back(line.to_owned());
            while ring.len() > LOG_LINES {
                ring.pop_front();
            }
        }
        if let Some(forward) = &forward {
            forward(line);
        }
    }));
    let engine = new_engine(config)?;
    let mut reg = registry();
    let id = reg.next;
    reg.next += 1;
    reg.engines.insert(id, Arc::new(Entry { engine, log }));
    Ok(id)
}

#[cfg(feature = "libtorrent")]
fn new_engine(config: Config) -> Result<Engine> {
    Engine::new(config)
}

#[cfg(not(feature = "libtorrent"))]
fn new_engine(_config: Config) -> Result<Engine> {
    Err(EngineError::new(ErrorKind::EngineFailed, "Built without a BitTorrent backend"))
}

/// The engine for one call.
pub(crate) fn lease(id: u64) -> Result<Arc<Entry>> {
    registry()
        .engines
        .get(&id)
        .cloned()
        .ok_or_else(|| EngineError::new(ErrorKind::InvalidHandle, "The torrent engine is closed"))
}

pub(crate) fn request_stop(id: u64) {
    if let Ok(entry) = lease(id) {
        entry.engine.request_stop();
    }
}

/// No new leases can start. Wakes blocked calls, waits for the current ones, then shuts the engine down.
pub(crate) fn destroy(id: u64) {
    let Some(entry) = registry().engines.remove(&id) else { return };
    entry.engine.request_stop();
    while Arc::strong_count(&entry) > 1 {
        std::thread::sleep(Duration::from_millis(2));
    }
    drop(entry);
}

thread_local! {
    static LAST_ERROR: RefCell<(c_int, String)> = const { RefCell::new((0, String::new())) };
}

fn status(result: Result<()>) -> c_int {
    match result {
        Ok(()) => 0,
        Err(e) => {
            let kind = e.kind as c_int;
            LAST_ERROR.with(|last| *last.borrow_mut() = (kind, e.message));
            kind
        }
    }
}

/// Copies the calling thread's last error message (truncated, NUL-terminated) and returns its kind.
///
/// # Safety
/// `out` holds `size` bytes, or is null.
#[no_mangle]
pub unsafe extern "C" fn stream_last_error(out: *mut c_char, size: usize) -> c_int {
    LAST_ERROR.with(|last| {
        let last = last.borrow();
        write_message(out, size, &last.1);
        last.0
    })
}

/// Copies the engine's version string; returns its full length.
///
/// # Safety
/// `out` holds `size` bytes, or is null.
#[no_mangle]
pub unsafe extern "C" fn stream_version(out: *mut c_char, size: usize) -> usize {
    #[cfg(feature = "libtorrent")]
    let version = crate::libtorrent::version();
    #[cfg(not(feature = "libtorrent"))]
    let version = String::from("no backend");
    write_message(out, size, &version);
    version.len()
}

#[repr(C)]
pub struct StreamConfig {
    pub cache_dir: *const c_char,
    pub cache_bytes: i64,
    pub reserve_bytes: i64,
    pub max_piece_bytes: c_int,
    pub min_slots: c_int,
    pub connections_limit: c_int,
    pub listen_interfaces: *const c_char,
    pub dht: bool,
    pub lsd: bool,
    pub upnp: bool,
    pub natpmp: bool,
    pub pex: bool,
    pub public_network_only: bool,
    pub multiple_connections_per_ip: bool,
    pub ca_file: *const c_char,
    pub user_agent: *const c_char,
    pub read_ahead_seconds: c_int,
    pub min_read_ahead_pieces: c_int,
    pub default_bitrate: i64,
    /// Called on the engine thread with each diagnostic line.
    pub log: Option<unsafe extern "C" fn(*mut c_void, *const c_char)>,
    pub log_context: *mut c_void,
}

/// Fills the defaults; null strings keep them.
///
/// # Safety
/// `out` is writable.
#[no_mangle]
pub unsafe extern "C" fn stream_config_default(out: *mut StreamConfig) {
    let d = Config::default();
    *out = StreamConfig {
        cache_dir: std::ptr::null(),
        cache_bytes: d.cache_bytes,
        reserve_bytes: d.reserve_bytes,
        max_piece_bytes: d.max_piece_bytes,
        min_slots: d.min_slots,
        connections_limit: d.connections_limit,
        listen_interfaces: std::ptr::null(),
        dht: d.dht,
        lsd: d.lsd,
        upnp: d.upnp,
        natpmp: d.natpmp,
        pex: d.pex,
        public_network_only: d.public_network_only,
        multiple_connections_per_ip: d.multiple_connections_per_ip,
        ca_file: std::ptr::null(),
        user_agent: std::ptr::null(),
        read_ahead_seconds: d.read_ahead_seconds,
        min_read_ahead_pieces: d.min_read_ahead_pieces,
        default_bitrate: d.default_bitrate,
        log: None,
        log_context: std::ptr::null_mut(),
    };
}

unsafe fn text(s: *const c_char) -> Option<String> {
    (!s.is_null()).then(|| CStr::from_ptr(s).to_string_lossy().into_owned())
}

unsafe fn texts(items: *const *const c_char, count: usize) -> Vec<String> {
    if items.is_null() {
        return Vec::new();
    }
    (0..count).filter_map(|i| text(*items.add(i))).collect()
}

struct LogTarget {
    f: unsafe extern "C" fn(*mut c_void, *const c_char),
    context: *mut c_void,
}
// SAFETY: the caller's log function may be called from the engine thread for the engine's lifetime.
unsafe impl Send for LogTarget {}
unsafe impl Sync for LogTarget {}

/// # Safety
/// `config` is valid; `out` is writable.
#[no_mangle]
pub unsafe extern "C" fn stream_engine_create(config: *const StreamConfig, out: *mut u64) -> c_int {
    let c = &*config;
    let d = Config::default();
    let log = c.log.map(|f| {
        let target = LogTarget { f, context: c.log_context };
        Arc::new(move |line: &str| {
            let target = &target;
            if let Ok(line) = CString::new(line) {
                // SAFETY: see LogTarget.
                unsafe { (target.f)(target.context, line.as_ptr()) };
            }
        }) as crate::engine::LogSink
    });
    let config = Config {
        cache_dir: text(c.cache_dir).unwrap_or_default(),
        cache_bytes: c.cache_bytes,
        reserve_bytes: c.reserve_bytes,
        max_piece_bytes: c.max_piece_bytes,
        min_slots: c.min_slots,
        connections_limit: c.connections_limit,
        listen_interfaces: text(c.listen_interfaces).unwrap_or(d.listen_interfaces),
        dht: c.dht,
        lsd: c.lsd,
        upnp: c.upnp,
        natpmp: c.natpmp,
        pex: c.pex,
        public_network_only: c.public_network_only,
        multiple_connections_per_ip: c.multiple_connections_per_ip,
        ca_file: text(c.ca_file).unwrap_or_default(),
        user_agent: text(c.user_agent).unwrap_or(d.user_agent),
        read_ahead_seconds: c.read_ahead_seconds,
        min_read_ahead_pieces: c.min_read_ahead_pieces,
        default_bitrate: c.default_bitrate,
        log,
    };
    status(create_engine(config).map(|id| *out = id))
}

#[no_mangle]
pub extern "C" fn stream_engine_request_stop(engine: u64) {
    request_stop(engine);
}

#[no_mangle]
pub extern "C" fn stream_engine_destroy(engine: u64) {
    destroy(engine);
}

#[repr(C)]
pub struct StreamSource {
    pub metainfo: *const u8,
    pub metainfo_size: usize,
    pub magnet: *const c_char,
    pub info_hash: *const c_char,
    pub trackers: *const *const c_char,
    pub num_trackers: usize,
    pub fallback_trackers: *const *const c_char,
    pub num_fallback_trackers: usize,
    pub peers: *const *const c_char,
    pub num_peers: usize,
}

/// # Safety
/// `source` and its arrays are valid; `out` is writable.
#[no_mangle]
pub unsafe extern "C" fn stream_add(engine: u64, source: *const StreamSource, out: *mut u64) -> c_int {
    let s = &*source;
    let source = Source {
        metainfo: if s.metainfo.is_null() || s.metainfo_size == 0 {
            Vec::new()
        } else {
            std::slice::from_raw_parts(s.metainfo, s.metainfo_size).to_vec()
        },
        magnet: text(s.magnet).unwrap_or_default(),
        info_hash: text(s.info_hash).unwrap_or_default(),
        trackers: texts(s.trackers, s.num_trackers),
        fallback_trackers: texts(s.fallback_trackers, s.num_fallback_trackers),
        peers: texts(s.peers, s.num_peers),
    };
    status(lease(engine).and_then(|e| e.engine.add(&source)).map(|id| *out = id))
}

#[repr(C)]
pub struct StreamFile {
    pub index: c_int,
    pub size: i64,
    /// UTF-8, freed with the list.
    pub path: *mut c_char,
}

fn file_list(files: Vec<FileInfo>) -> (*mut StreamFile, usize) {
    let list: Box<[StreamFile]> = files
        .into_iter()
        .map(|f| StreamFile {
            index: f.index,
            size: f.size,
            path: CString::new(f.path.replace('\0', "")).unwrap_or_default().into_raw(),
        })
        .collect();
    let n = list.len();
    (Box::into_raw(list) as *mut StreamFile, n)
}

/// Waits for the file list. `cancelled` (a `std::atomic<bool>`, or null) ends the wait early.
///
/// # Safety
/// Out pointers are writable; free the list with `stream_files_free`.
#[no_mangle]
pub unsafe extern "C" fn stream_wait_metadata(
    engine: u64,
    torrent: u64,
    timeout_ms: i64,
    cancelled: *const AtomicBool,
    files: *mut *mut StreamFile,
    count: *mut usize,
) -> c_int {
    let cancelled = (!cancelled.is_null()).then(|| &*cancelled);
    status(
        lease(engine)
            .and_then(|e| e.engine.wait_metadata(torrent, Duration::from_millis(timeout_ms.max(0) as u64), cancelled))
            .map(|list| (*files, *count) = file_list(list)),
    )
}

/// # Safety
/// `files` came from `stream_wait_metadata` with this count.
#[no_mangle]
pub unsafe extern "C" fn stream_files_free(files: *mut StreamFile, count: usize) {
    if files.is_null() {
        return;
    }
    let list = Box::from_raw(std::ptr::slice_from_raw_parts_mut(files, count));
    for f in list.iter() {
        drop(CString::from_raw(f.path));
    }
}

#[no_mangle]
pub extern "C" fn stream_select_file(engine: u64, torrent: u64, file: c_int) -> c_int {
    status(lease(engine).and_then(|e| e.engine.select_file(torrent, file)))
}

#[no_mangle]
pub extern "C" fn stream_set_bitrate(engine: u64, torrent: u64, bytes_per_second: i64) -> c_int {
    status(lease(engine).and_then(|e| e.engine.set_bitrate(torrent, bytes_per_second)))
}

#[no_mangle]
pub extern "C" fn stream_set_demand(engine: u64, torrent: u64, bytes_per_second: i64, ahead_seconds: c_int) -> c_int {
    status(lease(engine).and_then(|e| e.engine.set_demand(torrent, bytes_per_second, ahead_seconds)))
}

/// # Safety
/// Out pointers are writable; free the bytes with `stream_bytes_free`.
#[no_mangle]
pub unsafe extern "C" fn stream_metainfo(engine: u64, torrent: u64, data: *mut *mut u8, size: *mut usize) -> c_int {
    status(lease(engine).and_then(|e| e.engine.metainfo(torrent)).map(|bytes| {
        let bytes = bytes.into_boxed_slice();
        *size = bytes.len();
        *data = Box::into_raw(bytes) as *mut u8;
    }))
}

/// # Safety
/// `data` came from `stream_metainfo` with this size.
#[no_mangle]
pub unsafe extern "C" fn stream_bytes_free(data: *mut u8, size: usize) {
    if !data.is_null() {
        drop(Box::from_raw(std::ptr::slice_from_raw_parts_mut(data, size)));
    }
}

/// # Safety
/// `peers` holds `count` strings.
#[no_mangle]
pub unsafe extern "C" fn stream_add_peers(engine: u64, torrent: u64, peers: *const *const c_char, count: usize) -> c_int {
    let peers = texts(peers, count);
    status(lease(engine).and_then(|e| e.engine.add_peers(torrent, &peers)))
}

#[repr(C)]
pub struct StreamStats {
    /// 0 waiting for metadata, 1 ready, 2 streaming, 3 failed.
    pub state: c_int,
    /// Freed with `stream_stats_free`.
    pub error: *mut c_char,
    pub error_kind: c_int,
    pub peers: c_int,
    pub seeds: c_int,
    pub download_rate: c_int,
    pub upload_rate: c_int,
    pub total_download: i64,
    pub total_upload: i64,
    pub piece_length: c_int,
    pub num_pieces: c_int,
    pub hash_failures: c_int,
    pub evictions_requested: c_int,
    pub urgent_reservation_failures: c_int,
    pub obsolete_partials: c_int,
    pub verified_read_bytes: i64,
    pub cache: CacheStats,
    pub disk: DiskStats,
}

fn state_code(stats: &Stats) -> c_int {
    match stats.state {
        "ready" => 1,
        "streaming" => 2,
        "failed" => 3,
        _ => 0,
    }
}

/// # Safety
/// `out` is writable; free it with `stream_stats_free`.
#[no_mangle]
pub unsafe extern "C" fn stream_get_stats(engine: u64, torrent: u64, out: *mut StreamStats) -> c_int {
    status(lease(engine).and_then(|e| e.engine.stats(torrent)).map(|s| {
        *out = StreamStats {
            state: state_code(&s),
            error: CString::new(s.error.replace('\0', "")).unwrap_or_default().into_raw(),
            error_kind: s.error_kind,
            peers: s.peers,
            seeds: s.seeds,
            download_rate: s.download_rate,
            upload_rate: s.upload_rate,
            total_download: s.total_download,
            total_upload: s.total_upload,
            piece_length: s.piece_length,
            num_pieces: s.num_pieces,
            hash_failures: s.hash_failures,
            evictions_requested: s.evictions_requested,
            urgent_reservation_failures: s.urgent_reservation_failures,
            obsolete_partials: s.obsolete_partials,
            verified_read_bytes: s.verified_read_bytes,
            cache: s.cache,
            disk: s.disk,
        };
    }))
}

/// # Safety
/// `stats` was filled by `stream_get_stats`.
#[no_mangle]
pub unsafe extern "C" fn stream_stats_free(stats: *mut StreamStats) {
    let s = &mut *stats;
    if !s.error.is_null() {
        drop(CString::from_raw(s.error));
        s.error = std::ptr::null_mut();
    }
}

#[no_mangle]
pub extern "C" fn stream_remove(engine: u64, torrent: u64) -> c_int {
    status(lease(engine).and_then(|e| e.engine.remove(torrent)))
}

/// # Safety
/// `out` is writable.
#[no_mangle]
pub unsafe extern "C" fn stream_open_reader(engine: u64, torrent: u64, file: c_int, out: *mut u64) -> c_int {
    status(lease(engine).and_then(|e| e.engine.open_reader(torrent, file)).map(|id| *out = id))
}

/// # Safety
/// `out` is writable.
#[no_mangle]
pub unsafe extern "C" fn stream_file_size(engine: u64, reader: u64, out: *mut i64) -> c_int {
    status(lease(engine).and_then(|e| e.engine.file_size(reader)).map(|size| *out = size))
}

/// Copies verified bytes at `position`: `*read` is the count (at most to the end of a piece), 0 at the end of the
/// file, or -1 when nothing arrived within `wait_ms`.
///
/// # Safety
/// `buffer` holds `size` bytes; `read` is writable.
#[no_mangle]
pub unsafe extern "C" fn stream_read(
    engine: u64,
    reader: u64,
    position: i64,
    buffer: *mut u8,
    size: usize,
    wait_ms: i64,
    read: *mut c_int,
) -> c_int {
    let out = if size == 0 { &mut [][..] } else { std::slice::from_raw_parts_mut(buffer, size.min(i32::MAX as usize)) };
    status(
        lease(engine)
            .and_then(|e| e.engine.read(reader, position, out, Duration::from_millis(wait_ms.max(0) as u64)))
            .map(|n| *read = n),
    )
}

#[no_mangle]
pub extern "C" fn stream_seek(engine: u64, reader: u64, position: i64) -> c_int {
    status(lease(engine).and_then(|e| e.engine.seek(reader, position)))
}

#[no_mangle]
pub extern "C" fn stream_cancel(engine: u64, reader: u64) -> c_int {
    status(lease(engine).map(|e| e.engine.cancel(reader)))
}

#[no_mangle]
pub extern "C" fn stream_close_reader(engine: u64, reader: u64) -> c_int {
    status(lease(engine).map(|e| e.engine.close_reader(reader)))
}
