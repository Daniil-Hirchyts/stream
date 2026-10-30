//! The libtorrent backend, through the C shim in `native/shim`. Torrents are tagged with the engine's handle so
//! events come back addressed; caches are the Rust [`PieceCache`]s libtorrent's disk subsystem writes into.

use crate::cache::PieceCache;
use crate::engine::{Config, EngineError, ErrorKind, Result, Wake};
use crate::swarm::{
    DiskStats, EvictDone, Event, FileEntry, Metadata, PieceLengthCheck, Source, Swarm, SwarmTorrent, TorrentStatus,
};
use std::ffi::{c_char, c_int, c_void, CStr, CString};
use std::net::SocketAddr;
use std::sync::{Arc, Mutex};

#[allow(non_camel_case_types)]
mod ffi {
    use std::ffi::{c_char, c_int, c_void};

    #[repr(C)]
    pub struct lts_session {
        _private: [u8; 0],
    }
    #[repr(C)]
    pub struct lts_params {
        _private: [u8; 0],
    }
    #[repr(C)]
    pub struct lts_torrent {
        _private: [u8; 0],
    }
    #[repr(C)]
    pub struct lts_info {
        _private: [u8; 0],
    }

    #[repr(C)]
    pub struct lts_config {
        pub cache_dir: *const c_char,
        pub cache_bytes: i64,
        pub connections_limit: c_int,
        pub listen_interfaces: *const c_char,
        pub dht: bool,
        pub lsd: bool,
        pub upnp: bool,
        pub natpmp: bool,
        pub public_network_only: bool,
        pub multiple_connections_per_ip: bool,
        pub log_peers: bool,
        pub ca_file: *const c_char,
        pub user_agent: *const c_char,
        pub notify: extern "C" fn(*mut c_void),
        pub notify_context: *mut c_void,
    }

    #[repr(C)]
    #[derive(Default)]
    pub struct lts_status {
        pub peers: c_int,
        pub seeds: c_int,
        pub download_rate: c_int,
        pub upload_rate: c_int,
        pub total_download: i64,
        pub total_upload: i64,
    }

    #[repr(C)]
    #[derive(Default)]
    pub struct lts_disk_stats {
        pub buffer_bytes: i64,
        pub queued_write_bytes: i64,
        pub blocked_writes: c_int,
    }

    #[repr(C)]
    pub struct lts_event {
        pub kind: c_int,
        pub tag: u64,
        pub piece: c_int,
        pub message: *const c_char,
    }

    extern "C" {
        pub fn lts_session_create(config: *const lts_config, error: *mut c_char, size: usize) -> *mut lts_session;
        pub fn lts_session_destroy(session: *mut lts_session);
        pub fn lts_version() -> *const c_char;

        pub fn lts_params_parse(
            metainfo: *const u8,
            metainfo_size: usize,
            magnet: *const c_char,
            info_hash: *const c_char,
            error: *mut c_char,
            size: usize,
        ) -> *mut lts_params;
        pub fn lts_params_piece_length(params: *const lts_params) -> c_int;
        pub fn lts_params_add_tracker(params: *mut lts_params, url: *const c_char);
        pub fn lts_params_tracker_count(params: *const lts_params) -> usize;
        pub fn lts_params_add_peer(params: *mut lts_params, address: *const c_char, port: u16);
        pub fn lts_params_free(params: *mut lts_params);

        pub fn lts_add(
            session: *mut lts_session,
            params: *mut lts_params,
            tag: u64,
            dht: bool,
            lsd: bool,
            pex: bool,
            error: *mut c_char,
            size: usize,
        ) -> *mut lts_torrent;
        pub fn lts_torrent_free(torrent: *mut lts_torrent);
        pub fn lts_remove(torrent: *mut lts_torrent);
        pub fn lts_pause(torrent: *mut lts_torrent);

        pub fn lts_torrent_info(torrent: *mut lts_torrent) -> *mut lts_info;
        pub fn lts_info_piece_length(info: *const lts_info) -> c_int;
        pub fn lts_info_num_pieces(info: *const lts_info) -> c_int;
        pub fn lts_info_piece_size(info: *const lts_info, piece: c_int) -> c_int;
        pub fn lts_info_num_files(info: *const lts_info) -> c_int;
        pub fn lts_info_file(info: *const lts_info, file: c_int, size: *mut i64, offset: *mut i64, pad: *mut bool) -> *const c_char;
        pub fn lts_info_free(info: *mut lts_info);

        pub fn lts_torrent_cache(torrent: *mut lts_torrent) -> *const c_void;

        pub fn lts_prioritize_pieces(torrent: *mut lts_torrent, priorities: *const u8, count: c_int);
        pub fn lts_clear_piece_deadlines(torrent: *mut lts_torrent);
        pub fn lts_set_piece_deadline(torrent: *mut lts_torrent, piece: c_int, milliseconds: c_int);
        pub fn lts_have_piece(torrent: *mut lts_torrent, piece: c_int) -> bool;
        pub fn lts_connect_peer(torrent: *mut lts_torrent, address: *const c_char, port: u16);
        pub fn lts_evict_pieces(
            torrent: *mut lts_torrent,
            pieces: *const c_int,
            count: c_int,
            done: extern "C" fn(*mut c_void, *const c_int, c_int),
            drop: extern "C" fn(*mut c_void),
            context: *mut c_void,
        );
        pub fn lts_torrent_status(torrent: *mut lts_torrent, out: *mut lts_status) -> bool;
        pub fn lts_metainfo(
            torrent: *mut lts_torrent,
            sink: extern "C" fn(*mut c_void, *const u8, usize),
            context: *mut c_void,
            error: *mut c_char,
            size: usize,
        ) -> bool;
        pub fn lts_session_disk_stats(session: *mut lts_session, out: *mut lts_disk_stats);
        pub fn lts_poll(session: *mut lts_session, sink: extern "C" fn(*mut c_void, *const lts_event), context: *mut c_void);
    }
}

const ERROR_SIZE: usize = 1024;

fn error_text(buffer: &[c_char]) -> String {
    // SAFETY: the shim always terminates what it writes, and the buffer starts zeroed.
    unsafe { CStr::from_ptr(buffer.as_ptr()) }.to_string_lossy().into_owned()
}

/// Strings with an interior NUL can't reach C; such input is a bad source.
fn c_string(text: &str) -> Result<CString> {
    CString::new(text).map_err(|_| EngineError::new(ErrorKind::BadSource, "Text contains a NUL character"))
}

pub fn version() -> String {
    // SAFETY: lts_version returns a static string.
    unsafe { CStr::from_ptr(ffi::lts_version()) }.to_string_lossy().into_owned()
}

/// The session pointer, shared by the swarm and its torrents; destroyed after the last of them.
struct Session {
    raw: *mut ffi::lts_session,
    // Keeps the notify target alive for as long as libtorrent may call it.
    _wake: Box<Arc<Wake>>,
}

// SAFETY: the shim's session functions are thread-safe (libtorrent's session is).
unsafe impl Send for Session {}
unsafe impl Sync for Session {}

impl Drop for Session {
    fn drop(&mut self) {
        // SAFETY: the last reference; every torrent holding an Arc<Session> is gone.
        unsafe { ffi::lts_session_destroy(self.raw) };
    }
}

extern "C" fn notify(context: *mut c_void) {
    // SAFETY: the context is the session's boxed Arc<Wake>, alive until the session is destroyed.
    let wake = unsafe { &*(context as *const Arc<Wake>) };
    wake.wake();
}

pub struct LibtorrentSwarm {
    session: Arc<Session>,
    dht: bool,
    lsd: bool,
    pex: bool,
}

impl LibtorrentSwarm {
    pub fn create(config: &Config, wake: Arc<Wake>) -> Result<Self> {
        let failed = |e: EngineError| EngineError::new(ErrorKind::EngineFailed, e.message);
        let cache_dir = c_string(&config.cache_dir).map_err(failed)?;
        let listen = c_string(&config.listen_interfaces).map_err(failed)?;
        let ca_file = c_string(&config.ca_file).map_err(failed)?;
        let user_agent = c_string(&config.user_agent).map_err(failed)?;
        let wake = Box::new(wake);
        let c = ffi::lts_config {
            cache_dir: cache_dir.as_ptr(),
            cache_bytes: config.cache_bytes,
            connections_limit: config.connections_limit,
            listen_interfaces: listen.as_ptr(),
            dht: config.dht,
            lsd: config.lsd,
            upnp: config.upnp,
            natpmp: config.natpmp,
            public_network_only: config.public_network_only,
            multiple_connections_per_ip: config.multiple_connections_per_ip,
            log_peers: config.log.is_some(),
            ca_file: ca_file.as_ptr(),
            user_agent: user_agent.as_ptr(),
            notify,
            notify_context: &*wake as *const Arc<Wake> as *mut c_void,
        };
        let mut error = [0 as c_char; ERROR_SIZE];
        // SAFETY: every pointer in `c` outlives the call; the notify context lives in the Session.
        let raw = unsafe { ffi::lts_session_create(&c, error.as_mut_ptr(), error.len()) };
        if raw.is_null() {
            return Err(EngineError::new(ErrorKind::EngineFailed, error_text(&error)));
        }
        Ok(LibtorrentSwarm { session: Arc::new(Session { raw, _wake: wake }), dht: config.dht, lsd: config.lsd, pex: config.pex })
    }
}

/// Parsed but not yet added; freed unless lts_add consumed it.
struct Params(*mut ffi::lts_params);

impl Drop for Params {
    fn drop(&mut self) {
        if !self.0.is_null() {
            // SAFETY: not consumed by lts_add.
            unsafe { ffi::lts_params_free(self.0) };
        }
    }
}

impl Swarm for LibtorrentSwarm {
    fn add(&self, source: &Source, tag: u64, check: PieceLengthCheck<'_>) -> Result<Box<dyn SwarmTorrent>> {
        let peers = source.peers.iter().map(|p| crate::swarm::parse_peer(p)).collect::<Result<Vec<_>>>()?;
        let magnet = c_string(&source.magnet)?;
        let info_hash = c_string(&source.info_hash)?;
        let mut error = [0 as c_char; ERROR_SIZE];
        let (metainfo, magnet_ptr, hash_ptr) = if !source.metainfo.is_empty() {
            (source.metainfo.as_slice(), std::ptr::null(), std::ptr::null())
        } else if !source.magnet.is_empty() {
            (&[][..], magnet.as_ptr(), std::ptr::null())
        } else {
            (&[][..], std::ptr::null(), info_hash.as_ptr())
        };
        // SAFETY: the buffers outlive the call.
        let params = Params(unsafe {
            ffi::lts_params_parse(metainfo.as_ptr(), metainfo.len(), magnet_ptr, hash_ptr, error.as_mut_ptr(), error.len())
        });
        if params.0.is_null() {
            return Err(EngineError::new(ErrorKind::BadSource, error_text(&error)));
        }
        // SAFETY: params is valid until consumed.
        let piece_length = unsafe { ffi::lts_params_piece_length(params.0) };
        if piece_length > 0 {
            check(piece_length)?;
        }
        for url in &source.trackers {
            let url = c_string(url)?;
            // SAFETY: as above; the shim skips duplicates.
            unsafe { ffi::lts_params_add_tracker(params.0, url.as_ptr()) };
        }
        // Raw info hashes alone may use the public fallback trackers, and only when they have none.
        let raw_hash = !source.info_hash.is_empty() && source.metainfo.is_empty() && source.magnet.is_empty();
        // SAFETY: as above.
        if raw_hash && unsafe { ffi::lts_params_tracker_count(params.0) } == 0 {
            for url in &source.fallback_trackers {
                let url = c_string(url)?;
                // SAFETY: as above.
                unsafe { ffi::lts_params_add_tracker(params.0, url.as_ptr()) };
            }
        }
        for peer in peers {
            let address = c_string(&peer.ip().to_string())?;
            // SAFETY: as above.
            unsafe { ffi::lts_params_add_peer(params.0, address.as_ptr(), peer.port()) };
        }
        let consumed = std::mem::ManuallyDrop::new(params);
        // SAFETY: lts_add consumes params whatever the outcome.
        let raw = unsafe {
            ffi::lts_add(self.session.raw, consumed.0, tag, self.dht, self.lsd, self.pex, error.as_mut_ptr(), error.len())
        };
        if raw.is_null() {
            return Err(EngineError::new(ErrorKind::BadSource, error_text(&error)));
        }
        Ok(Box::new(LibtorrentTorrent { raw, _session: self.session.clone(), metadata: Mutex::new(None) }))
    }

    fn poll(&self, out: &mut Vec<Event>) {
        extern "C" fn sink(context: *mut c_void, event: *const ffi::lts_event) {
            // SAFETY: the shim passes a valid event for the callback's duration; context is our Vec.
            let (out, event) = unsafe { (&mut *(context as *mut Vec<Event>), &*event) };
            let message = if event.message.is_null() {
                String::new()
            } else {
                // SAFETY: a NUL-terminated message valid during the callback.
                unsafe { CStr::from_ptr(event.message) }.to_string_lossy().into_owned()
            };
            let tag = event.tag;
            out.push(match event.kind {
                0 => Event::Log(message),
                1 => Event::MetadataReceived { tag },
                2 => Event::PieceFinished { tag, piece: event.piece },
                3 => Event::HashFailed { tag },
                4 => Event::MetadataFailed { tag, message },
                5 => Event::TorrentError { tag, message },
                6 => Event::FileError { tag, message },
                7 => Event::Removed { tag },
                _ => return,
            });
        }
        // SAFETY: the sink only runs during the call.
        unsafe { ffi::lts_poll(self.session.raw, sink, out as *mut Vec<Event> as *mut c_void) };
    }

    fn disk_stats(&self) -> DiskStats {
        let mut s = ffi::lts_disk_stats::default();
        // SAFETY: writes the struct.
        unsafe { ffi::lts_session_disk_stats(self.session.raw, &mut s) };
        DiskStats { buffer_bytes: s.buffer_bytes, queued_write_bytes: s.queued_write_bytes, blocked_writes: s.blocked_writes }
    }

    fn version(&self) -> String {
        version()
    }
}

pub struct LibtorrentTorrent {
    raw: *mut ffi::lts_torrent,
    _session: Arc<Session>,
    metadata: Mutex<Option<Arc<Metadata>>>,
}

// SAFETY: lt::torrent_handle is thread-safe; the shim object is immutable after creation.
unsafe impl Send for LibtorrentTorrent {}
unsafe impl Sync for LibtorrentTorrent {}

impl Drop for LibtorrentTorrent {
    fn drop(&mut self) {
        // SAFETY: owned; freed before the session (it holds an Arc<Session>).
        unsafe { ffi::lts_torrent_free(self.raw) };
    }
}

fn read_metadata(info: *const ffi::lts_info) -> Metadata {
    // SAFETY: info is valid for these calls.
    unsafe {
        let pieces = ffi::lts_info_num_pieces(info);
        let files = (0..ffi::lts_info_num_files(info))
            .map(|f| {
                let (mut size, mut offset, mut pad) = (0i64, 0i64, false);
                let path = ffi::lts_info_file(info, f, &mut size, &mut offset, &mut pad);
                FileEntry { path: CStr::from_ptr(path).to_string_lossy().into_owned(), size, offset, pad }
            })
            .collect();
        Metadata {
            piece_length: ffi::lts_info_piece_length(info),
            piece_sizes: (0..pieces).map(|p| ffi::lts_info_piece_size(info, p)).collect(),
            files,
        }
    }
}

impl SwarmTorrent for LibtorrentTorrent {
    fn metadata(&self) -> Option<Arc<Metadata>> {
        let mut known = self.metadata.lock().unwrap_or_else(|e| e.into_inner());
        if known.is_none() {
            // SAFETY: the torrent is alive; the info is freed right after reading.
            let info = unsafe { ffi::lts_torrent_info(self.raw) };
            if !info.is_null() {
                *known = Some(Arc::new(read_metadata(info)));
                // SAFETY: obtained above.
                unsafe { ffi::lts_info_free(info) };
            }
        }
        known.clone()
    }

    fn cache(&self) -> Option<Arc<PieceCache>> {
        // SAFETY: the shim retains the cache for us; from_raw takes over that reference.
        let raw = unsafe { ffi::lts_torrent_cache(self.raw) };
        if raw.is_null() {
            None
        } else {
            Some(unsafe { Arc::from_raw(raw as *const PieceCache) })
        }
    }

    fn prioritize_pieces(&self, priorities: &[u8]) {
        // SAFETY: the slice outlives the call.
        unsafe { ffi::lts_prioritize_pieces(self.raw, priorities.as_ptr(), priorities.len() as c_int) };
    }

    fn clear_piece_deadlines(&self) {
        // SAFETY: the torrent is alive.
        unsafe { ffi::lts_clear_piece_deadlines(self.raw) };
    }

    fn set_piece_deadline(&self, piece: i32, milliseconds: i32) {
        // SAFETY: the torrent is alive.
        unsafe { ffi::lts_set_piece_deadline(self.raw, piece, milliseconds) };
    }

    fn have_piece(&self, piece: i32) -> bool {
        // SAFETY: the torrent is alive.
        unsafe { ffi::lts_have_piece(self.raw, piece) }
    }

    fn evict_pieces(&self, pieces: Vec<i32>, done: EvictDone) {
        extern "C" fn finished(context: *mut c_void, evicted: *const c_int, count: c_int) {
            // SAFETY: the context is the boxed callback slot; `evicted` holds `count` pieces during the call.
            let slot = unsafe { &mut *(context as *mut Option<EvictDone>) };
            let evicted = if count > 0 { unsafe { std::slice::from_raw_parts(evicted, count as usize) }.to_vec() } else { Vec::new() };
            if let Some(done) = slot.take() {
                done(evicted);
            }
        }
        extern "C" fn release(context: *mut c_void) {
            // SAFETY: called exactly once by the shim, after which nothing uses the context.
            drop(unsafe { Box::from_raw(context as *mut Option<EvictDone>) });
        }
        let context = Box::into_raw(Box::new(Some(done))) as *mut c_void;
        // SAFETY: the shim owns the context until it calls `release`.
        unsafe { ffi::lts_evict_pieces(self.raw, pieces.as_ptr(), pieces.len() as c_int, finished, release, context) };
    }

    fn connect_peer(&self, address: SocketAddr) {
        if let Ok(ip) = CString::new(address.ip().to_string()) {
            // SAFETY: the string outlives the call.
            unsafe { ffi::lts_connect_peer(self.raw, ip.as_ptr(), address.port()) };
        }
    }

    fn status(&self) -> Option<TorrentStatus> {
        let mut s = ffi::lts_status::default();
        // SAFETY: writes the struct.
        if !unsafe { ffi::lts_torrent_status(self.raw, &mut s) } {
            return None;
        }
        Some(TorrentStatus {
            peers: s.peers,
            seeds: s.seeds,
            download_rate: s.download_rate,
            upload_rate: s.upload_rate,
            total_download: s.total_download,
            total_upload: s.total_upload,
        })
    }

    fn metainfo(&self) -> Result<Vec<u8>> {
        extern "C" fn sink(context: *mut c_void, data: *const u8, size: usize) {
            // SAFETY: context is our Vec; data holds `size` bytes during the call.
            let out = unsafe { &mut *(context as *mut Vec<u8>) };
            out.extend_from_slice(unsafe { std::slice::from_raw_parts(data, size) });
        }
        let mut out = Vec::new();
        let mut error = [0 as c_char; ERROR_SIZE];
        // SAFETY: the sink only runs during the call.
        let ok = unsafe {
            ffi::lts_metainfo(self.raw, sink, &mut out as *mut Vec<u8> as *mut c_void, error.as_mut_ptr(), error.len())
        };
        if ok {
            Ok(out)
        } else {
            let message = error_text(&error);
            let kind = if message.contains("too large") { ErrorKind::BadSource } else { ErrorKind::MetadataFailed };
            Err(EngineError::new(kind, message))
        }
    }

    fn pause(&self) {
        // SAFETY: the torrent is alive.
        unsafe { ffi::lts_pause(self.raw) };
    }

    fn remove(&self) {
        // SAFETY: the torrent is alive; removal is asynchronous.
        unsafe { ffi::lts_remove(self.raw) };
    }
}
