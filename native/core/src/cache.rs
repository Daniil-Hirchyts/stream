//! A bounded store for one torrent's pieces: a single preallocated file divided into slots of one piece each, never
//! more slots than the cache budget allows. A piece moves from reserved (a slot, no data) to partial, complete (every
//! block written) and verified (the torrent passed its hash). Disk jobs and readers pin a slot while they use it; a
//! slot is freed only once its piece is released and unpinned, and the next piece overwrites it in place. The file is
//! deleted with the cache, so it never occupies more than the slots' storage.
//!
//! All methods are thread-safe; file I/O runs outside the lock.

use std::fs::{File, OpenOptions};
use std::io;
use std::os::unix::fs::{FileExt, MetadataExt, OpenOptionsExt};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex, MutexGuard};
use std::time::Instant;

/// libtorrent's transfer unit (`lt::default_block_size`).
pub const BLOCK_SIZE: i32 = 0x4000;
/// Room left for filesystem allocation metadata (ext4 includes extent blocks in `st_blocks`).
pub const STORAGE_OVERHEAD: i64 = 64 << 10;

/// Slots a cache budget holds for this piece length.
pub fn cache_slots(bytes: i64, piece_length: i32) -> i32 {
    if piece_length <= 0 {
        return 0;
    }
    ((bytes - STORAGE_OVERHEAD).max(0) / i64::from(piece_length)) as i32
}

/// Byte ranges `(offset, length)` of a piece that belong to pad files (BEP 47). They are never written and read as zeros.
pub type PadRanges = Vec<(i32, i32)>;

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
#[repr(C)]
pub struct CacheStats {
    pub slots: i32,
    pub used: i32,
    pub verified: i32,
    pub pinned: i32,
    pub evicted: i64,
    /// The cache file's length, never more than slots times the piece length.
    pub file_bytes: i64,
    /// Bytes the file occupies on disk. Holes don't count; filesystem allocation may add a few blocks.
    pub allocated_bytes: i64,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(i32)]
pub enum WaitResult {
    Ready = 0,
    Timeout = 1,
    Cancelled = 2,
    Closed = 3,
}

/// How far a piece holding a slot got.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(i32)]
pub enum PieceKind {
    Reserved = 0,
    Partial = 1,
    Complete = 2,
    Verified = 3,
}

/// Why a disk operation failed, in terms the disk subsystem maps to its own error codes.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum IoError {
    Aborted,
    InvalidArgument,
    Eof,
    Os(i32),
}

impl IoError {
    fn from_io(e: &io::Error) -> Self {
        IoError::Os(e.raw_os_error().unwrap_or(libc::EIO))
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum State {
    Free,
    Reserved,
    Partial,
    Complete,
    Verified,
}

#[derive(Debug)]
struct Slot {
    piece: i32,
    st: State,
    releasing: bool,
    release_requested: bool,
    pins: i32,
    written_blocks: i32,
    required: i32,
    last_use: u64,
    written: Vec<bool>,
}

impl Slot {
    fn free() -> Self {
        Slot {
            piece: -1,
            st: State::Free,
            releasing: false,
            release_requested: false,
            pins: 0,
            written_blocks: 0,
            required: 0,
            last_use: 0,
            written: Vec::new(),
        }
    }
    fn has_data(&self) -> bool {
        matches!(self.st, State::Complete | State::Verified)
    }
}

type SlotFreed = Arc<dyn Fn() + Send + Sync>;

struct Inner {
    slots: Vec<Slot>,
    slot_of: Vec<i32>,
    free: Vec<i32>,
    tick: u64,
    evicted: i64,
    closed: bool,
    on_slot_freed: Option<SlotFreed>,
}

pub struct PieceCache {
    path: PathBuf,
    piece_length: i32,
    piece_sizes: Vec<i32>,
    pads: Vec<PadRanges>,
    file: File,
    inner: Mutex<Inner>,
    verified_cv: Condvar,
}

impl std::fmt::Debug for PieceCache {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("PieceCache").field("path", &self.path).field("slots", &self.slot_count()).finish()
    }
}

/// Reserves the file's storage before accepting payload writes. A failed reservation must not silently become
/// sparse storage.
fn preallocate(file: &File, size: i64) -> io::Result<()> {
    #[cfg(target_vendor = "apple")]
    {
        use std::os::fd::AsRawFd;
        let mut store = libc::fstore_t {
            fst_flags: libc::F_ALLOCATEALL,
            fst_posmode: libc::F_PEOFPOSMODE,
            fst_offset: 0,
            fst_length: size as libc::off_t,
            fst_bytesalloc: 0,
        };
        // SAFETY: the descriptor is open for the call's duration and `store` is a valid fstore_t.
        if unsafe { libc::fcntl(file.as_raw_fd(), libc::F_PREALLOCATE, &mut store) } != 0 {
            return Err(with_context(io::Error::last_os_error(), "preallocate cache"));
        }
    }
    #[cfg(any(target_os = "linux", target_os = "android"))]
    {
        use std::os::fd::AsRawFd;
        // SAFETY: the descriptor is open for the call's duration.
        if unsafe { libc::fallocate(file.as_raw_fd(), 0, 0, size as libc::off_t) } != 0 {
            return Err(with_context(io::Error::last_os_error(), "preallocate cache"));
        }
    }
    file.set_len(size as u64).map_err(|e| with_context(e, "size cache"))
}

fn with_context(e: io::Error, what: &str) -> io::Error {
    io::Error::new(e.kind(), format!("{what}: {e}"))
}

/// pread may transfer less than asked. A slot may extend past the end of the file, where nothing was written yet:
/// that reads as zeros, like a hole.
fn full_read(file: &File, mut out: &mut [u8], mut offset: u64) -> Result<(), IoError> {
    while !out.is_empty() {
        match file.read_at(out, offset) {
            Ok(0) => {
                out.fill(0);
                return Ok(());
            }
            Ok(n) => {
                out = &mut out[n..];
                offset += n as u64;
            }
            Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
            Err(e) => return Err(IoError::from_io(&e)),
        }
    }
    Ok(())
}

fn full_write(file: &File, mut data: &[u8], mut offset: u64) -> Result<(), IoError> {
    while !data.is_empty() {
        match file.write_at(data, offset) {
            Ok(0) => return Err(IoError::Os(libc::EIO)),
            Ok(n) => {
                data = &data[n..];
                offset += n as u64;
            }
            Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
            Err(e) => return Err(IoError::from_io(&e)),
        }
    }
    Ok(())
}

impl PieceCache {
    /// `piece_sizes` holds the size of every piece (the last is usually short), `pads` the pad ranges of every
    /// piece. With `preallocate`, the file gets its full size and storage at once: it never grows, so filesystems
    /// can't add speculative blocks, and a full disk shows at the start rather than during playback.
    pub fn new(
        path: impl AsRef<Path>,
        piece_length: i32,
        piece_sizes: Vec<i32>,
        mut pads: Vec<PadRanges>,
        slots: i32,
        preallocate_file: bool,
    ) -> io::Result<Self> {
        let path = path.as_ref().to_path_buf();
        pads.resize(piece_sizes.len(), Vec::new());
        let file = OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(true)
            .mode(0o600)
            .open(&path)
            .map_err(|e| with_context(e, &format!("open {}", path.display())))?;
        if preallocate_file {
            if let Err(e) = preallocate(&file, i64::from(slots.max(0)) * i64::from(piece_length)) {
                drop(file);
                let _ = std::fs::remove_file(&path);
                return Err(e);
            }
        }
        let slot_count = slots.max(0) as usize;
        let inner = Inner {
            slots: (0..slot_count).map(|_| Slot::free()).collect(),
            slot_of: vec![-1; piece_sizes.len()],
            // Lower slots first, so a sparse file stays as short as the pieces in use allow.
            free: (0..slots.max(0)).rev().collect(),
            tick: 0,
            evicted: 0,
            closed: false,
            on_slot_freed: None,
        };
        Ok(PieceCache { path, piece_length, piece_sizes, pads, file, inner: Mutex::new(inner), verified_cv: Condvar::new() })
    }

    pub fn piece_length(&self) -> i32 {
        self.piece_length
    }
    pub fn num_pieces(&self) -> i32 {
        self.piece_sizes.len() as i32
    }
    /// 0 for an index outside the torrent.
    pub fn piece_size(&self, p: i32) -> i32 {
        if self.valid(p) {
            self.piece_sizes[p as usize]
        } else {
            0
        }
    }
    pub fn slot_count(&self) -> i32 {
        self.lock().slots.len() as i32
    }

    fn valid(&self, p: i32) -> bool {
        p >= 0 && (p as usize) < self.piece_sizes.len()
    }

    fn lock(&self) -> MutexGuard<'_, Inner> {
        // A panic while the lock was held leaves consistent slot bookkeeping (no multi-step updates span a panic
        // point), so a poisoned lock is still usable.
        self.inner.lock().unwrap_or_else(|e| e.into_inner())
    }

    fn slot_of(&self, inner: &Inner, p: i32) -> i32 {
        if self.valid(p) {
            inner.slot_of[p as usize]
        } else {
            -1
        }
    }

    fn slot_offset(&self, s: i32) -> u64 {
        s as u64 * self.piece_length as u64
    }

    fn blocks_in(&self, p: i32) -> i32 {
        (self.piece_size(p) + BLOCK_SIZE - 1) / BLOCK_SIZE
    }

    /// Blocks holding at least one byte of file data; a piece is complete once all of them are written.
    fn required_blocks(&self, p: i32) -> i32 {
        let pads = &self.pads[p as usize];
        if pads.is_empty() {
            return self.blocks_in(p);
        }
        let size = self.piece_size(p);
        (0..self.blocks_in(p))
            .filter(|b| {
                let start = b * BLOCK_SIZE;
                let end = (start + BLOCK_SIZE).min(size);
                !pads.iter().any(|&(offset, length)| offset <= start && end <= offset + length)
            })
            .count() as i32
    }

    fn zero_pads(&self, p: i32, offset: i32, out: &mut [u8]) {
        for &(pad_offset, pad_length) in &self.pads[p as usize] {
            let start = offset.max(pad_offset);
            let end = (offset + out.len() as i32).min(pad_offset + pad_length);
            if start < end {
                out[(start - offset) as usize..(end - offset) as usize].fill(0);
            }
        }
    }

    fn take_slot_locked(&self, inner: &mut Inner, p: i32) -> i32 {
        if !self.valid(p) || inner.closed {
            return -1;
        }
        let Some(s) = inner.free.pop() else { return -1 };
        let required = self.required_blocks(p);
        let blocks = self.blocks_in(p) as usize;
        inner.tick += 1;
        let tick = inner.tick;
        let sl = &mut inner.slots[s as usize];
        sl.piece = p;
        sl.st = State::Reserved;
        sl.releasing = false;
        sl.release_requested = false;
        sl.pins = 0;
        sl.written_blocks = 0;
        sl.required = required;
        sl.written = vec![false; blocks];
        // A piece made only of padding has nothing to download.
        if required == 0 {
            sl.st = State::Complete;
        }
        sl.last_use = tick;
        inner.slot_of[p as usize] = s;
        s
    }

    /// Gives the piece a slot ahead of its data; false when none is free, or while its previous slot is still being
    /// released. Reserving a piece twice is harmless.
    pub fn reserve(&self, p: i32) -> bool {
        let mut inner = self.lock();
        let s = self.slot_of(&inner, p);
        if s >= 0 {
            return !inner.slots[s as usize].releasing;
        }
        self.take_slot_locked(&mut inner, p) >= 0
    }

    /// Frees a reserved slot no data reached, such as a piece that left the read-ahead window.
    pub fn unreserve(&self, p: i32) {
        let freed = {
            let mut inner = self.lock();
            let s = self.slot_of(&inner, p);
            if s < 0 {
                return;
            }
            let sl = &mut inner.slots[s as usize];
            if sl.st != State::Reserved || sl.pins > 0 {
                return;
            }
            sl.st = State::Free;
            sl.piece = -1;
            inner.slot_of[p as usize] = -1;
            inner.free.push(s);
            inner.on_slot_freed.clone()
        };
        if let Some(f) = freed {
            f();
        }
    }

    pub fn has_slot(&self, p: i32) -> bool {
        let inner = self.lock();
        self.slot_of(&inner, p) >= 0
    }

    pub fn has_data(&self, p: i32) -> bool {
        let inner = self.lock();
        let s = self.slot_of(&inner, p);
        s >= 0 && inner.slots[s as usize].st != State::Reserved
    }

    /// Every block holding data is written (complete or verified).
    pub fn complete(&self, p: i32) -> bool {
        let inner = self.lock();
        let s = self.slot_of(&inner, p);
        s >= 0 && inner.slots[s as usize].has_data()
    }

    pub fn block_written(&self, p: i32, offset: i32) -> bool {
        let inner = self.lock();
        let s = self.slot_of(&inner, p);
        if s < 0 {
            return false;
        }
        let sl = &inner.slots[s as usize];
        if sl.has_data() {
            return true;
        }
        if offset < 0 {
            return false;
        }
        let b = (offset / BLOCK_SIZE) as usize;
        b < sl.written.len() && sl.written[b]
    }

    pub fn free_slots(&self) -> i32 {
        self.lock().free.len() as i32
    }

    /// Disk thread: takes a slot if the piece has none. `Ok(false)` without error means none is free, or the piece's
    /// slot is still being released, so the caller holds the block until then.
    pub fn write(&self, p: i32, offset: i32, data: &[u8]) -> Result<bool, IoError> {
        let s = {
            let mut inner = self.lock();
            if inner.closed {
                return Err(IoError::Aborted);
            }
            if !self.valid(p) || offset < 0 || i64::from(offset) + data.len() as i64 > i64::from(self.piece_size(p)) {
                return Err(IoError::InvalidArgument);
            }
            let mut s = self.slot_of(&inner, p);
            // A slot being released belongs to the evicted copy; the new one waits for a fresh slot.
            if s >= 0 && inner.slots[s as usize].releasing {
                return Ok(false);
            }
            if s < 0 {
                s = self.take_slot_locked(&mut inner, p);
            }
            if s < 0 {
                return Ok(false);
            }
            inner.slots[s as usize].pins += 1;
            s
        };
        let result = full_write(&self.file, data, self.slot_offset(s) + offset as u64);
        if result.is_ok() {
            let mut inner = self.lock();
            inner.tick += 1;
            let tick = inner.tick;
            let sl = &mut inner.slots[s as usize];
            let first = offset / BLOCK_SIZE;
            let last = (offset + data.len() as i32 + BLOCK_SIZE - 1) / BLOCK_SIZE;
            for b in first..last.min(sl.written.len() as i32) {
                if !sl.written[b as usize] {
                    sl.written[b as usize] = true;
                    sl.written_blocks += 1;
                }
            }
            if sl.st == State::Reserved {
                sl.st = State::Partial;
            }
            if sl.st == State::Partial && sl.written_blocks >= sl.required {
                sl.st = State::Complete;
            }
            sl.last_use = tick;
        }
        self.unpin(s);
        result.map(|()| true)
    }

    /// Reads written data of a complete or verified piece.
    pub fn read(&self, p: i32, offset: i32, out: &mut [u8]) -> Result<(), IoError> {
        let s = {
            let mut inner = self.lock();
            let s = self.slot_of(&inner, p);
            if s < 0
                || !inner.slots[s as usize].has_data()
                || offset < 0
                || i64::from(offset) + out.len() as i64 > i64::from(self.piece_size(p))
            {
                // libtorrent only reads pieces it has, which are never freed before it forgets them.
                return Err(IoError::Eof);
            }
            inner.slots[s as usize].pins += 1;
            s
        };
        let result = full_read(&self.file, out, self.slot_offset(s) + offset as u64);
        if result.is_ok() {
            self.zero_pads(p, offset, out);
        }
        self.unpin(s);
        result
    }

    /// The hash failed: forget the blocks so they are written again, keeping the slot.
    pub fn clear(&self, p: i32) {
        let mut inner = self.lock();
        let s = self.slot_of(&inner, p);
        if s < 0 {
            return;
        }
        let sl = &mut inner.slots[s as usize];
        sl.written.fill(false);
        sl.written_blocks = 0;
        if sl.st != State::Free {
            sl.st = State::Reserved;
        }
    }

    /// Frees the piece's slot as soon as nothing pins it. Readers stopped pinning it at `begin_release`.
    pub fn release(&self, p: i32) {
        let (s, detached) = {
            let mut inner = self.lock();
            let s = self.slot_of(&inner, p);
            if s < 0 {
                return;
            }
            let sl = &mut inner.slots[s as usize];
            sl.releasing = true;
            sl.release_requested = true;
            (s, Self::maybe_free_locked(&mut inner, s))
        };
        if detached {
            self.return_slot(s);
        }
    }

    /// Returns whether the slot was freed; the caller then returns it to the free list.
    fn maybe_free_locked(inner: &mut Inner, s: i32) -> bool {
        let sl = &inner.slots[s as usize];
        if !sl.release_requested || sl.pins > 0 {
            return false;
        }
        if sl.st == State::Verified {
            inner.evicted += 1;
        }
        let piece = sl.piece;
        inner.slot_of[piece as usize] = -1;
        let sl = &mut inner.slots[s as usize];
        sl.piece = -1;
        sl.st = State::Free;
        sl.releasing = false;
        sl.release_requested = false;
        sl.written.clear();
        sl.written_blocks = 0;
        // The slot is reused in place: the file never holds more than its slots, and no write can grow it beyond them.
        true
    }

    fn return_slot(&self, s: i32) {
        let freed = {
            let mut inner = self.lock();
            inner.free.push(s);
            inner.on_slot_freed.clone()
        };
        if let Some(f) = freed {
            f();
        }
    }

    fn unpin(&self, s: i32) {
        let detached = {
            let mut inner = self.lock();
            inner.slots[s as usize].pins -= 1;
            Self::maybe_free_locked(&mut inner, s)
        };
        if detached {
            self.return_slot(s);
        }
    }

    pub fn set_verified(&self, p: i32) {
        {
            let mut inner = self.lock();
            let s = self.slot_of(&inner, p);
            if s < 0 {
                return;
            }
            inner.tick += 1;
            let tick = inner.tick;
            let sl = &mut inner.slots[s as usize];
            if sl.st != State::Complete {
                return;
            }
            sl.st = State::Verified;
            sl.last_use = tick;
        }
        self.verified_cv.notify_all();
    }

    pub fn verified(&self, p: i32) -> bool {
        let inner = self.lock();
        let s = self.slot_of(&inner, p);
        s >= 0 && inner.slots[s as usize].st == State::Verified && !inner.slots[s as usize].releasing
    }

    /// Readers: waits until the piece is verified, the deadline passes, or the reader is cancelled or the cache closed.
    pub fn wait_verified(&self, p: i32, deadline: Instant, cancelled: &AtomicBool) -> WaitResult {
        let mut inner = self.lock();
        loop {
            if inner.closed {
                return WaitResult::Closed;
            }
            if cancelled.load(Ordering::Acquire) {
                return WaitResult::Cancelled;
            }
            let s = self.slot_of(&inner, p);
            if s >= 0 && inner.slots[s as usize].st == State::Verified && !inner.slots[s as usize].releasing {
                return WaitResult::Ready;
            }
            let now = Instant::now();
            if now >= deadline {
                return WaitResult::Timeout;
            }
            inner = self.verified_cv.wait_timeout(inner, deadline - now).unwrap_or_else(|e| e.into_inner()).0;
        }
    }

    /// Copies verified data; `Ok(false)` when the piece is not verified (anymore).
    pub fn read_verified(&self, p: i32, offset: i32, out: &mut [u8]) -> Result<bool, IoError> {
        let s = {
            let mut inner = self.lock();
            let s = self.slot_of(&inner, p);
            if s < 0
                || inner.slots[s as usize].st != State::Verified
                || inner.slots[s as usize].releasing
                || offset < 0
                || i64::from(offset) + out.len() as i64 > i64::from(self.piece_size(p))
            {
                return Ok(false);
            }
            inner.tick += 1;
            let tick = inner.tick;
            let sl = &mut inner.slots[s as usize];
            sl.pins += 1;
            sl.last_use = tick;
            s
        };
        let result = full_read(&self.file, out, self.slot_offset(s) + offset as u64);
        if result.is_ok() {
            self.zero_pads(p, offset, out);
        }
        self.unpin(s);
        result.map(|()| true)
    }

    /// Wakes waiting readers, for example after a reader was cancelled or the torrent failed.
    pub fn wake_all(&self) {
        // Taking the lock orders the wake-up after any waiter's check of its flags.
        drop(self.lock());
        self.verified_cv.notify_all();
    }

    pub fn close(&self) {
        self.lock().closed = true;
        self.verified_cv.notify_all();
    }

    /// Eviction: verified pieces nothing pins, least recently used first, skipping protected ones.
    pub fn eviction_candidates(&self, count: i32, is_protected: impl Fn(i32) -> bool) -> Vec<i32> {
        let mut found: Vec<(u64, i32)> = {
            let inner = self.lock();
            inner
                .slots
                .iter()
                .filter(|sl| sl.st == State::Verified && sl.pins == 0 && !sl.releasing && !is_protected(sl.piece))
                .map(|sl| (sl.last_use, sl.piece))
                .collect()
        };
        found.sort_unstable();
        found.into_iter().take(count.max(0) as usize).map(|(_, p)| p).collect()
    }

    /// Stops new reader pins before the torrent forgets the pieces.
    pub fn begin_release(&self, pieces: &[i32]) {
        let mut inner = self.lock();
        for &p in pieces {
            let s = self.slot_of(&inner, p);
            if s >= 0 {
                inner.slots[s as usize].releasing = true;
            }
        }
    }

    /// Undoes `begin_release` for a piece the torrent did not evict.
    pub fn cancel_release(&self, p: i32) {
        let mut inner = self.lock();
        let s = self.slot_of(&inner, p);
        if s >= 0 && !inner.slots[s as usize].release_requested {
            inner.slots[s as usize].releasing = false;
        }
    }

    /// Every piece holding a slot, and how far it got.
    pub fn pieces(&self) -> Vec<(i32, PieceKind)> {
        let inner = self.lock();
        inner
            .slots
            .iter()
            .filter_map(|sl| {
                let kind = match sl.st {
                    State::Free => return None,
                    State::Reserved => PieceKind::Reserved,
                    State::Partial => PieceKind::Partial,
                    State::Complete => PieceKind::Complete,
                    State::Verified => PieceKind::Verified,
                };
                Some((sl.piece, kind))
            })
            .collect()
    }

    /// Called whenever a slot becomes free, outside the lock.
    pub fn on_slot_freed(&self, f: Option<SlotFreed>) {
        self.lock().on_slot_freed = f;
    }

    pub fn stats(&self) -> CacheStats {
        let mut ret = {
            let inner = self.lock();
            let mut ret = CacheStats { slots: inner.slots.len() as i32, evicted: inner.evicted, ..CacheStats::default() };
            for sl in inner.slots.iter().filter(|sl| sl.st != State::Free) {
                ret.used += 1;
                if sl.st == State::Verified {
                    ret.verified += 1;
                }
                if sl.pins > 0 {
                    ret.pinned += 1;
                }
            }
            ret
        };
        if let Ok(meta) = self.file.metadata() {
            ret.file_bytes = meta.len() as i64;
            ret.allocated_bytes = meta.blocks() as i64 * 512;
        }
        ret
    }
}

impl Drop for PieceCache {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.path);
    }
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use std::time::Duration;

    pub(crate) fn temp_dir(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("stream-core-{}-{}", name, std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn cache(name: &str, sizes: Vec<i32>, slots: i32) -> PieceCache {
        let dir = temp_dir(name);
        let pads = vec![Vec::new(); sizes.len()];
        PieceCache::new(dir.join("c.cache"), 32 * 1024, sizes, pads, slots, true).unwrap()
    }

    fn fill(c: &PieceCache, p: i32, byte: u8) {
        let size = c.piece_size(p) as usize;
        assert!(c.write(p, 0, &vec![byte; size]).unwrap());
    }

    #[test]
    fn slots_follow_the_budget_and_storage_overhead() {
        assert_eq!(cache_slots(4 << 20, 1 << 20), 3);
        assert_eq!(cache_slots((4 << 20) + STORAGE_OVERHEAD, 1 << 20), 4);
        assert_eq!(cache_slots(0, 1 << 20), 0);
    }

    #[test]
    fn a_piece_moves_from_reserved_to_verified_and_reads_back() {
        let c = cache("states", vec![32 * 1024, 32 * 1024, 1000], 2);
        assert!(c.reserve(0));
        assert!(!c.has_data(0));
        assert!(c.write(0, 0, &[7u8; BLOCK_SIZE as usize]).unwrap());
        assert!(c.block_written(0, 0) && !c.block_written(0, BLOCK_SIZE));
        assert!(!c.complete(0));
        assert!(c.write(0, BLOCK_SIZE, &[8u8; BLOCK_SIZE as usize]).unwrap());
        assert!(c.complete(0) && !c.verified(0));
        c.set_verified(0);
        assert!(c.verified(0));
        let mut out = vec![0u8; 4];
        assert!(c.read_verified(0, BLOCK_SIZE - 2, &mut out).unwrap());
        assert_eq!(out, [7, 7, 8, 8]);
        // The short last piece completes with its single block.
        fill(&c, 2, 9);
        assert!(c.complete(2));
    }

    #[test]
    fn writes_wait_for_a_free_slot_and_eviction_frees_one() {
        let c = cache("full", vec![32 * 1024; 4], 2);
        fill(&c, 0, 1);
        fill(&c, 1, 2);
        c.set_verified(0);
        c.set_verified(1);
        assert_eq!(c.free_slots(), 0);
        assert!(!c.write(2, 0, &[3u8; 16]).unwrap(), "no slot: the block waits");
        let freed = Arc::new(AtomicBool::new(false));
        let flag = freed.clone();
        c.on_slot_freed(Some(Arc::new(move || flag.store(true, Ordering::SeqCst))));
        let victims = c.eviction_candidates(1, |_| false);
        assert_eq!(victims, vec![0], "least recently used first");
        c.begin_release(&victims);
        assert!(!c.verified(0), "readers stop at begin_release");
        c.release(0);
        assert!(freed.load(Ordering::SeqCst));
        assert_eq!(c.stats().evicted, 1);
        assert!(c.write(2, 0, &[3u8; 16]).unwrap());
    }

    #[test]
    fn protected_and_pinned_pieces_are_not_evicted() {
        let c = cache("protect", vec![32 * 1024; 3], 3);
        for p in 0..3 {
            fill(&c, p, p as u8);
            c.set_verified(p);
        }
        assert_eq!(c.eviction_candidates(3, |p| p == 1), vec![0, 2]);
        c.begin_release(&[0]);
        c.cancel_release(0);
        assert!(c.verified(0), "a piece the torrent kept is readable again");
    }

    #[test]
    fn waits_end_on_verification_cancellation_and_close() {
        let c = Arc::new(cache("wait", vec![32 * 1024; 2], 2));
        let cancelled = AtomicBool::new(false);
        let soon = Instant::now() + Duration::from_millis(20);
        assert_eq!(c.wait_verified(0, soon, &cancelled), WaitResult::Timeout);
        let writer = {
            let c = c.clone();
            std::thread::spawn(move || {
                std::thread::sleep(Duration::from_millis(20));
                fill(&c, 0, 5);
                c.set_verified(0);
            })
        };
        assert_eq!(c.wait_verified(0, Instant::now() + Duration::from_secs(5), &cancelled), WaitResult::Ready);
        writer.join().unwrap();
        cancelled.store(true, Ordering::SeqCst);
        assert_eq!(c.wait_verified(1, Instant::now() + Duration::from_secs(5), &cancelled), WaitResult::Cancelled);
        c.close();
        assert_eq!(c.wait_verified(1, Instant::now() + Duration::from_secs(5), &AtomicBool::new(false)), WaitResult::Closed);
        assert_eq!(c.write(1, 0, &[1]).unwrap_err(), IoError::Aborted);
    }

    #[test]
    fn pad_ranges_read_as_zeros_and_need_no_writes() {
        let dir = temp_dir("pads");
        let pads = vec![vec![(BLOCK_SIZE, BLOCK_SIZE)], vec![(0, 32 * 1024)]];
        let c = PieceCache::new(dir.join("p.cache"), 32 * 1024, vec![32 * 1024; 2], pads, 2, true).unwrap();
        assert!(c.write(0, 0, &[4u8; BLOCK_SIZE as usize]).unwrap());
        assert!(c.complete(0), "the pad block is never written");
        c.set_verified(0);
        let mut out = vec![9u8; 4];
        assert!(c.read_verified(0, BLOCK_SIZE - 2, &mut out).unwrap());
        assert_eq!(out, [4, 4, 0, 0]);
        assert!(c.reserve(1));
        assert!(c.complete(1), "an all-pad piece has nothing to download");
    }

    #[test]
    fn the_file_never_outgrows_its_slots_and_is_deleted_with_the_cache() {
        let dir = temp_dir("bound");
        let path = dir.join("b.cache");
        {
            let c = PieceCache::new(&path, 32 * 1024, vec![32 * 1024; 8], vec![Vec::new(); 8], 2, true).unwrap();
            for round in 0..4 {
                for p in [round * 2, round * 2 + 1] {
                    fill(&c, p, p as u8);
                    c.set_verified(p);
                }
                assert!(c.stats().file_bytes <= 2 * 32 * 1024);
                for p in [round * 2, round * 2 + 1] {
                    c.begin_release(&[p]);
                    c.release(p);
                }
            }
            assert_eq!(c.stats().evicted, 8);
            assert!(path.exists());
        }
        assert!(!path.exists());
    }

    #[test]
    fn a_failed_hash_forgets_written_blocks() {
        let c = cache("clear", vec![32 * 1024], 1);
        fill(&c, 0, 1);
        assert!(c.complete(0));
        c.clear(0);
        assert!(!c.complete(0) && c.has_slot(0) && !c.has_data(0));
        assert_eq!(c.pieces(), vec![(0, PieceKind::Reserved)]);
    }

    #[test]
    fn offsets_past_the_piece_are_refused() {
        let c = cache("bounds", vec![1000], 1);
        assert_eq!(c.write(0, 900, &[0u8; 200]).unwrap_err(), IoError::InvalidArgument);
        assert_eq!(c.write(5, 0, &[0u8; 1]).unwrap_err(), IoError::InvalidArgument);
        assert_eq!(c.read(0, 0, &mut [0u8; 1]).unwrap_err(), IoError::Eof);
    }
}
