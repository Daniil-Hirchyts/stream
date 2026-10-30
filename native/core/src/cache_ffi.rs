//! The piece cache's C ABI, for libtorrent's disk subsystem (`cache_disk_io`), which stays C++ because it implements
//! libtorrent's `disk_interface`. A cache pointer is an `Arc<PieceCache>` reference: `stream_cache_new` returns one,
//! `stream_cache_retain` adds one, `stream_cache_release` drops one. Declared in `native/include/stream_core.h`.

use crate::cache::{cache_slots, CacheStats, IoError, PieceCache, PieceKind};
use std::ffi::{c_char, c_int, c_void, CStr};
use std::sync::atomic::AtomicBool;
use std::sync::Arc;
use std::time::{Duration, Instant};

/// A disk operation's outcome: kind 0 ok, 1 aborted, 2 invalid argument, 3 end of file, 4 OS error (`os_error`).
#[repr(C)]
pub struct StreamIo {
    pub kind: c_int,
    pub os_error: c_int,
}

fn report(io: *mut StreamIo, result: Result<(), IoError>) {
    if io.is_null() {
        return;
    }
    let (kind, os_error) = match result {
        Ok(()) => (0, 0),
        Err(IoError::Aborted) => (1, 0),
        Err(IoError::InvalidArgument) => (2, 0),
        Err(IoError::Eof) => (3, 0),
        Err(IoError::Os(errno)) => (4, errno),
    };
    // SAFETY: the caller passes a writable StreamIo.
    unsafe { *io = StreamIo { kind, os_error } };
}

/// # Safety
/// `cache` must be a live reference from this module.
unsafe fn cache<'a>(cache: *const PieceCache) -> &'a PieceCache {
    &*cache
}

unsafe fn slice<'a>(data: *const u8, size: usize) -> &'a [u8] {
    if size == 0 { &[] } else { std::slice::from_raw_parts(data, size) }
}

unsafe fn slice_mut<'a>(data: *mut u8, size: usize) -> &'a mut [u8] {
    if size == 0 { &mut [] } else { std::slice::from_raw_parts_mut(data, size) }
}

#[no_mangle]
pub extern "C" fn stream_cache_slots(bytes: i64, piece_length: c_int) -> c_int {
    cache_slots(bytes, piece_length)
}

/// Pads are given as parallel arrays: the piece, offset and length of each range. Null with `errno_out` and a message
/// when the file can't be created or preallocated.
///
/// # Safety
/// Arrays hold the given counts; `path` is NUL-terminated; `message` holds `message_size` bytes.
#[no_mangle]
pub unsafe extern "C" fn stream_cache_new(
    path: *const c_char,
    piece_length: c_int,
    piece_sizes: *const c_int,
    num_pieces: c_int,
    pad_pieces: *const c_int,
    pad_offsets: *const c_int,
    pad_lengths: *const c_int,
    num_pads: c_int,
    slots: c_int,
    preallocate: bool,
    errno_out: *mut c_int,
    message: *mut c_char,
    message_size: usize,
) -> *const PieceCache {
    let path = CStr::from_ptr(path).to_string_lossy().into_owned();
    let count = num_pieces.max(0) as usize;
    let sizes = if count == 0 { Vec::new() } else { std::slice::from_raw_parts(piece_sizes, count).to_vec() };
    let mut pads = vec![Vec::new(); count];
    for i in 0..num_pads.max(0) as usize {
        let piece = *pad_pieces.add(i);
        if let Some(ranges) = usize::try_from(piece).ok().and_then(|p| pads.get_mut(p)) {
            ranges.push((*pad_offsets.add(i), *pad_lengths.add(i)));
        }
    }
    match PieceCache::new(path, piece_length, sizes, pads, slots, preallocate) {
        Ok(c) => Arc::into_raw(Arc::new(c)),
        Err(e) => {
            if !errno_out.is_null() {
                *errno_out = e.raw_os_error().unwrap_or(libc::EIO);
            }
            write_message(message, message_size, &e.to_string());
            std::ptr::null()
        }
    }
}

pub(crate) unsafe fn write_message(out: *mut c_char, size: usize, text: &str) {
    if out.is_null() || size == 0 {
        return;
    }
    let bytes = text.as_bytes();
    let n = bytes.len().min(size - 1);
    std::ptr::copy_nonoverlapping(bytes.as_ptr() as *const c_char, out, n);
    *out.add(n) = 0;
}

/// # Safety
/// `c` is a live cache reference.
#[no_mangle]
pub unsafe extern "C" fn stream_cache_retain(c: *const PieceCache) {
    Arc::increment_strong_count(c);
}

/// # Safety
/// `c` is a live cache reference, not used again by the caller.
#[no_mangle]
pub unsafe extern "C" fn stream_cache_release(c: *const PieceCache) {
    Arc::decrement_strong_count(c);
}

macro_rules! getter {
    ($name:ident, $ret:ty, |$c:ident $(, $arg:ident: $ty:ty)*| $body:expr) => {
        /// # Safety
        /// `c` is a live cache reference.
        #[no_mangle]
        pub unsafe extern "C" fn $name(c: *const PieceCache $(, $arg: $ty)*) -> $ret {
            let $c = cache(c);
            $body
        }
    };
}

getter!(stream_cache_piece_length, c_int, |c| c.piece_length());
getter!(stream_cache_num_pieces, c_int, |c| c.num_pieces());
getter!(stream_cache_piece_size, c_int, |c, p: c_int| c.piece_size(p));
getter!(stream_cache_slot_count, c_int, |c| c.slot_count());
getter!(stream_cache_reserve, bool, |c, p: c_int| c.reserve(p));
getter!(stream_cache_unreserve, (), |c, p: c_int| c.unreserve(p));
getter!(stream_cache_has_slot, bool, |c, p: c_int| c.has_slot(p));
getter!(stream_cache_has_data, bool, |c, p: c_int| c.has_data(p));
getter!(stream_cache_complete, bool, |c, p: c_int| c.complete(p));
getter!(stream_cache_block_written, bool, |c, p: c_int, offset: c_int| c.block_written(p, offset));
getter!(stream_cache_free_slots, c_int, |c| c.free_slots());
getter!(stream_cache_clear, (), |c, p: c_int| c.clear(p));
getter!(stream_cache_release_piece, (), |c, p: c_int| c.release(p));
getter!(stream_cache_set_verified, (), |c, p: c_int| c.set_verified(p));
getter!(stream_cache_verified, bool, |c, p: c_int| c.verified(p));
getter!(stream_cache_wake_all, (), |c| c.wake_all());
getter!(stream_cache_close, (), |c| c.close());
getter!(stream_cache_cancel_release, (), |c, p: c_int| c.cancel_release(p));

/// False without error when the block must wait for a slot.
///
/// # Safety
/// `c` is live; `data` holds `size` bytes; `io` is writable.
#[no_mangle]
pub unsafe extern "C" fn stream_cache_write(
    c: *const PieceCache,
    p: c_int,
    offset: c_int,
    data: *const u8,
    size: usize,
    io: *mut StreamIo,
) -> bool {
    match cache(c).write(p, offset, slice(data, size)) {
        Ok(accepted) => {
            report(io, Ok(()));
            accepted
        }
        Err(e) => {
            report(io, Err(e));
            true
        }
    }
}

/// # Safety
/// `c` is live; `out` holds `size` bytes; `io` is writable.
#[no_mangle]
pub unsafe extern "C" fn stream_cache_read(c: *const PieceCache, p: c_int, offset: c_int, out: *mut u8, size: usize, io: *mut StreamIo) {
    report(io, cache(c).read(p, offset, slice_mut(out, size)));
}

/// False when the piece is not verified (anymore).
///
/// # Safety
/// `c` is live; `out` holds `size` bytes; `io` is writable.
#[no_mangle]
pub unsafe extern "C" fn stream_cache_read_verified(
    c: *const PieceCache,
    p: c_int,
    offset: c_int,
    out: *mut u8,
    size: usize,
    io: *mut StreamIo,
) -> bool {
    match cache(c).read_verified(p, offset, slice_mut(out, size)) {
        Ok(read) => {
            report(io, Ok(()));
            read
        }
        Err(e) => {
            report(io, Err(e));
            true
        }
    }
}

/// Returns 0 ready, 1 timeout, 2 cancelled, 3 closed. `cancelled` points at a `std::atomic<bool>` (same layout as
/// `AtomicBool`).
///
/// # Safety
/// `c` is live; `cancelled` is valid for the wait.
#[no_mangle]
pub unsafe extern "C" fn stream_cache_wait_verified(c: *const PieceCache, p: c_int, timeout_ns: i64, cancelled: *const AtomicBool) -> c_int {
    let deadline = Instant::now() + Duration::from_nanos(timeout_ns.max(0) as u64);
    let never = AtomicBool::new(false);
    let cancelled = if cancelled.is_null() { &never } else { &*cancelled };
    cache(c).wait_verified(p, deadline, cancelled) as c_int
}

/// Writes up to `capacity` least recently used verified pieces nothing pins or protects; returns how many.
///
/// # Safety
/// `c` is live; `out` holds `capacity` ints; `is_protected` may be null.
#[no_mangle]
pub unsafe extern "C" fn stream_cache_eviction_candidates(
    c: *const PieceCache,
    count: c_int,
    is_protected: Option<unsafe extern "C" fn(*mut c_void, c_int) -> bool>,
    context: *mut c_void,
    out: *mut c_int,
    capacity: c_int,
) -> c_int {
    let found = cache(c).eviction_candidates(count.min(capacity), |p| is_protected.is_some_and(|f| f(context, p)));
    for (i, p) in found.iter().enumerate() {
        *out.add(i) = *p;
    }
    found.len() as c_int
}

/// # Safety
/// `c` is live; `pieces` holds `count` ints.
#[no_mangle]
pub unsafe extern "C" fn stream_cache_begin_release(c: *const PieceCache, pieces: *const c_int, count: c_int) {
    let pieces = if count <= 0 { &[][..] } else { std::slice::from_raw_parts(pieces, count as usize) };
    cache(c).begin_release(pieces);
}

/// Writes up to `capacity` pieces holding a slot and their kinds (0 reserved, 1 partial, 2 complete, 3 verified).
///
/// # Safety
/// `c` is live; both arrays hold `capacity` ints.
#[no_mangle]
pub unsafe extern "C" fn stream_cache_pieces(c: *const PieceCache, pieces: *mut c_int, kinds: *mut c_int, capacity: c_int) -> c_int {
    let all = cache(c).pieces();
    let n = all.len().min(capacity.max(0) as usize);
    for (i, (p, kind)) in all.into_iter().take(n).enumerate() {
        *pieces.add(i) = p;
        *kinds.add(i) = kind as c_int;
    }
    debug_assert_eq!(PieceKind::Verified as c_int, 3);
    n as c_int
}

/// A C callback and its context; the context is released with `drop` once the cache no longer holds the callback.
struct Callback {
    f: unsafe extern "C" fn(*mut c_void),
    drop: Option<unsafe extern "C" fn(*mut c_void)>,
    context: *mut c_void,
}
// SAFETY: the C side's callback may run on any thread; its context lives until `drop`.
unsafe impl Send for Callback {}
unsafe impl Sync for Callback {}

impl Drop for Callback {
    fn drop(&mut self) {
        if let Some(drop) = self.drop {
            // SAFETY: the last use of the context.
            unsafe { drop(self.context) };
        }
    }
}

/// Called whenever a slot becomes free, outside the cache's lock, on the freeing thread. Null clears it. The cache
/// calls `drop` with the context once nothing can call `f` any more, including a call already in progress.
///
/// # Safety
/// `c` is live; `context` stays valid until `drop` runs with it.
#[no_mangle]
pub unsafe extern "C" fn stream_cache_on_slot_freed(
    c: *const PieceCache,
    f: Option<unsafe extern "C" fn(*mut c_void)>,
    drop: Option<unsafe extern "C" fn(*mut c_void)>,
    context: *mut c_void,
) {
    let callback = f.map(|f| {
        let cb = Callback { f, drop, context };
        Arc::new(move || {
            let cb = &cb;
            // SAFETY: see Callback.
            unsafe { (cb.f)(cb.context) }
        }) as Arc<dyn Fn() + Send + Sync>
    });
    cache(c).on_slot_freed(callback);
}

/// # Safety
/// `c` is live; `out` is writable.
#[no_mangle]
pub unsafe extern "C" fn stream_cache_get_stats(c: *const PieceCache, out: *mut CacheStats) {
    *out = cache(c).stats();
}
