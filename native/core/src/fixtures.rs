//! Host-only helpers shared by the tests and the `stream-fixture` tool: reproducible content, torrents made from it,
//! and a real BitTorrent seeder on loopback. Nothing here reaches the internet or the LAN.

use crate::bt::metainfo::{self, Version};
use crate::bt::session::{Session, SessionConfig};
use crate::bt::storage::{FileStorage, Storage};
use crate::bt::torrent::Torrent;
use std::os::unix::fs::FileExt;
use std::path::{Path, PathBuf};
use std::sync::Arc;

fn mix(mut x: u64) -> u64 {
    // splitmix64: a fast, well-distributed function of the position.
    x = x.wrapping_add(0x9e37_79b9_7f4a_7c15);
    x = (x ^ (x >> 30)).wrapping_mul(0xbf58_476d_1ce4_e5b9);
    x = (x ^ (x >> 27)).wrapping_mul(0x94d0_49bb_1331_11eb);
    x ^ (x >> 31)
}

/// Content anyone can recompute: byte `offset` of a file made with `seed`.
pub fn fill_content(seed: u32, offset: i64, out: &mut [u8]) {
    for (i, b) in out.iter_mut().enumerate() {
        let pos = offset as u64 + i as u64;
        let word = mix((u64::from(seed) << 40) ^ (pos >> 3));
        *b = (word >> ((pos & 7) * 8)) as u8;
    }
}

/// Index of the first byte that differs from the content, if any.
pub fn first_mismatch(seed: u32, offset: i64, data: &[u8]) -> Option<usize> {
    let mut expected = vec![0u8; data.len()];
    fill_content(seed, offset, &mut expected);
    expected.iter().zip(data).position(|(a, b)| a != b)
}

/// Writes `size` bytes of content. The first `hole` bytes stay a sparse hole of zeros, so a test can reach offsets
/// beyond 4 GiB without writing them.
pub fn write_content(path: &Path, size: i64, seed: u32, hole: i64) -> std::io::Result<()> {
    let file = std::fs::File::create(path)?;
    file.set_len(size as u64)?;
    let mut buf = vec![0u8; 1 << 20];
    let mut pos = hole;
    while pos < size {
        let n = (buf.len() as i64).min(size - pos) as usize;
        fill_content(seed, pos, &mut buf[..n]);
        file.write_all_at(&buf[..n], pos as u64)?;
        pos += n as i64;
    }
    Ok(())
}

/// A new directory under the system's temporary directory, removed with everything in it.
pub struct TempDir(PathBuf);

impl TempDir {
    pub fn new(prefix: &str) -> TempDir {
        let mut random = [0u8; 6];
        let _ = getrandom::fill(&mut random);
        let path = std::env::temp_dir().join(format!("{prefix}-{}", metainfo::hex(&random)));
        std::fs::create_dir_all(&path).expect("temporary directory");
        TempDir(path)
    }
    pub fn path(&self) -> &Path {
        &self.0
    }
    pub fn file(&self, name: &str) -> PathBuf {
        self.0.join(name)
    }
}

impl Drop for TempDir {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

/// The .torrent for a file or directory, every piece hashed.
pub fn make_torrent(path: &Path, piece_length: i32, version: Version) -> Vec<u8> {
    metainfo::create(path, piece_length, version).expect("torrent")
}

pub fn parse_version(text: &str) -> Option<Version> {
    match text {
        "v1" => Some(Version::V1),
        "v2" => Some(Version::V2),
        "hybrid" => Some(Version::Hybrid),
        _ => None,
    }
}

#[derive(Clone, Debug)]
pub struct SeederOptions {
    pub listen: String,
    /// Bytes per second; 0 is unlimited.
    pub upload_rate: u64,
    /// Check each piece before serving it; off, corrupted content is served as it is.
    pub verify: bool,
}

impl Default for SeederOptions {
    fn default() -> Self {
        SeederOptions { listen: "127.0.0.1:0".into(), upload_rate: 0, verify: true }
    }
}

/// A session seeding torrents from their files, with DHT, local discovery, port mapping and trackers off: a peer
/// finds it only when given its address.
pub struct Seeder {
    pub session: Session,
    pub torrents: Vec<Arc<Torrent>>,
}

impl Seeder {
    /// Each torrent with the directory holding its files.
    pub fn new(torrents: &[(Vec<u8>, PathBuf)], options: &SeederOptions) -> Result<Seeder, String> {
        let session = Session::new(
            SessionConfig {
                listen: options.listen.clone(),
                multiple_per_ip: true,
                dht: false,
                lsd: false,
                pex: false,
                upload_rate: options.upload_rate,
                ..SessionConfig::default()
            },
            None,
        )?;
        let mut out = Vec::new();
        for (i, (data, save)) in torrents.iter().enumerate() {
            let t = metainfo::parse_torrent(data).map_err(|e| e.0)?;
            let files = FileStorage::open(&t.info, save).map_err(|e| format!("{}: {e}", save.display()))?;
            let torrent = session.add(i as u64, t.info.hashes, Some(t.info), t.layers, Vec::new(), Vec::new());
            torrent.seed_from(Storage::Files(Arc::new(files)), options.verify);
            out.push(torrent);
        }
        Ok(Seeder { session, torrents: out })
    }

    pub fn single(torrent: &[u8], save: &Path, options: &SeederOptions) -> Seeder {
        Seeder::new(&[(torrent.to_vec(), save.to_path_buf())], options).expect("seeder")
    }

    pub fn port(&self) -> u16 {
        self.session.port()
    }

    pub fn address(&self) -> String {
        format!("127.0.0.1:{}", self.port())
    }
}

/// The JSON `stream-fixture info` prints, which the fixture service reads.
pub fn info_json(torrent: &[u8]) -> Result<String, String> {
    let t = metainfo::parse_torrent(torrent).map_err(|e| e.0)?;
    let i = &t.info;
    let files: Vec<String> = i
        .files
        .iter()
        .enumerate()
        .filter(|(_, f)| !f.pad)
        .map(|(n, f)| format!("{{\"index\":{n},\"path\":\"{}\",\"size\":{}}}", crate::engine::json_escape(&f.path), f.size))
        .collect();
    Ok(format!(
        "{{\"name\":\"{}\",\"pieceLength\":{},\"pieces\":{},\"v1\":\"{}\",\"v2\":\"{}\",\"files\":[{}]}}",
        crate::engine::json_escape(&i.name),
        i.piece_length,
        i.num_pieces,
        i.hashes.v1.map(|h| metainfo::hex(&h)).unwrap_or_default(),
        i.hashes.v2.map(|h| metainfo::hex(&h)).unwrap_or_default(),
        files.join(",")
    ))
}
