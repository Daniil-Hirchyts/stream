//! Where a torrent's pieces live: the bounded [`PieceCache`] when streaming, or complete files on disk when seeding
//! a fixture.

use super::metainfo::Info;
use crate::cache::{IoError, PieceCache};
use std::fs::File;
use std::os::unix::fs::FileExt;
use std::path::{Path, PathBuf};
use std::sync::Arc;

pub enum Storage {
    Cache(Arc<PieceCache>),
    Files(Arc<FileStorage>),
}

fn describe(e: IoError) -> String {
    match e {
        IoError::Aborted => "aborted".into(),
        IoError::InvalidArgument => "invalid argument".into(),
        IoError::Eof => "no data".into(),
        IoError::Os(errno) => std::io::Error::from_raw_os_error(errno).to_string(),
    }
}

impl Storage {
    /// Reads data of a piece we have (or that is complete and waiting for its hash).
    pub fn read(&self, p: i32, offset: i32, out: &mut [u8]) -> Result<(), String> {
        match self {
            Storage::Cache(c) => c.read(p, offset, out).map_err(describe),
            Storage::Files(f) => f.read(p, offset, out),
        }
    }

    /// `Ok(false)`: no slot for the block yet; it comes again later.
    pub fn write(&self, p: i32, offset: i32, data: &[u8]) -> Result<bool, String> {
        match self {
            Storage::Cache(c) => c.write(p, offset, data).map_err(describe),
            Storage::Files(_) => Ok(false),
        }
    }

    /// Whether a piece may be downloaded into storage now.
    pub fn accepts(&self, p: i32) -> bool {
        match self {
            Storage::Cache(c) => c.has_slot(p),
            Storage::Files(_) => false,
        }
    }

    /// The piece failed its hash: forget its blocks.
    pub fn clear(&self, p: i32) {
        if let Storage::Cache(c) = self {
            c.clear(p);
        }
    }

    /// The torrent forgot the piece; its slot is freed once no read pins it.
    pub fn release(&self, p: i32) {
        if let Storage::Cache(c) = self {
            c.release(p);
        }
    }

    pub fn cache(&self) -> Option<Arc<PieceCache>> {
        match self {
            Storage::Cache(c) => Some(c.clone()),
            Storage::Files(_) => None,
        }
    }
}

/// A torrent's files under a save path, read-only.
pub struct FileStorage {
    piece_length: i64,
    total: i64,
    files: Vec<(Option<File>, i64, i64)>,
}

impl FileStorage {
    pub fn open(info: &Info, save_path: &Path) -> std::io::Result<FileStorage> {
        let mut files = Vec::new();
        for f in &info.files {
            let handle = if f.pad || f.size == 0 {
                None
            } else {
                let mut path = PathBuf::from(save_path);
                for c in f.path.split('/') {
                    path.push(c);
                }
                Some(File::open(&path)?)
            };
            files.push((handle, f.offset, f.size));
        }
        Ok(FileStorage { piece_length: i64::from(info.piece_length), total: info.total, files })
    }

    pub fn read(&self, p: i32, offset: i32, out: &mut [u8]) -> Result<(), String> {
        let start = i64::from(p) * self.piece_length + i64::from(offset);
        let end = start + out.len() as i64;
        if offset < 0 || end > self.total || i64::from(offset) + out.len() as i64 > self.piece_length {
            return Err("read beyond the piece".into());
        }
        out.fill(0);
        for (file, f_offset, size) in &self.files {
            let (lo, hi) = (start.max(*f_offset), end.min(f_offset + size));
            if lo >= hi {
                continue;
            }
            if let Some(file) = file {
                let dst = &mut out[(lo - start) as usize..(hi - start) as usize];
                file.read_exact_at(dst, (lo - f_offset) as u64).map_err(|e| e.to_string())?;
            }
        }
        Ok(())
    }
}
