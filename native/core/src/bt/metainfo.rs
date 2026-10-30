//! Torrent metadata: info dictionaries (v1, v2 and hybrid, BEP 3/47/52), torrent files with their trackers and
//! piece layers, magnet links (BEP 9/53), and creating torrents for the test fixtures.

use super::bencode::{self, bytes, dict, Value};
use super::merkle::{self, Hash};
use crate::cache::PadRanges;
use crate::swarm::{FileEntry, Metadata};
use sha1::{Digest, Sha1};
use std::collections::{BTreeMap, HashMap};
use std::fmt;
use std::path::{Path, PathBuf};

pub type Id20 = [u8; 20];

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Error(pub String);

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl From<bencode::Error> for Error {
    fn from(e: bencode::Error) -> Self {
        Error(e.0)
    }
}

pub type Result<T> = std::result::Result<T, Error>;

fn invalid<T>(message: impl Into<String>) -> Result<T> {
    Err(Error(message.into()))
}

/// Metadata larger than this is refused, from a file or from peers.
pub const MAX_METADATA: usize = 8 << 20;
const MAX_PIECE_LENGTH: i64 = 128 << 20;

pub fn sha1(data: &[u8]) -> Id20 {
    Sha1::digest(data).into()
}

pub fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

pub fn from_hex(text: &str) -> Option<Vec<u8>> {
    if text.len() % 2 != 0 {
        return None;
    }
    (0..text.len()).step_by(2).map(|i| u8::from_str_radix(text.get(i..i + 2)?, 16).ok()).collect()
}

fn from_base32(text: &str) -> Option<Vec<u8>> {
    let mut out = Vec::new();
    let (mut buffer, mut bits) = (0u32, 0);
    for c in text.bytes() {
        let v = match c.to_ascii_uppercase() {
            c @ b'A'..=b'Z' => c - b'A',
            c @ b'2'..=b'7' => c - b'2' + 26,
            _ => return None,
        };
        buffer = (buffer << 5) | u32::from(v);
        bits += 5;
        if bits >= 8 {
            bits -= 8;
            out.push((buffer >> bits) as u8);
        }
    }
    Some(out)
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
pub struct InfoHashes {
    pub v1: Option<Id20>,
    pub v2: Option<Hash>,
}

impl InfoHashes {
    /// The 20-byte hash a swarm knows the torrent by: v1's, or v2's truncated (BEP 52).
    pub fn id(&self) -> Id20 {
        self.v1.unwrap_or_else(|| self.v2_id().unwrap_or_default())
    }
    pub fn v2_id(&self) -> Option<Id20> {
        self.v2.map(|h| h[..20].try_into().unwrap())
    }
    /// Every 20-byte hash peers may use for this torrent: a hybrid has two swarms.
    pub fn ids(&self) -> Vec<Id20> {
        self.v1.into_iter().chain(self.v2_id()).collect()
    }
    pub fn matches(&self, id: &Id20) -> bool {
        self.ids().contains(id)
    }
    pub fn of(raw_info: &[u8], v1: bool, v2: bool) -> Self {
        InfoHashes { v1: v1.then(|| sha1(raw_info)), v2: v2.then(|| merkle::sha256(raw_info)) }
    }
    /// 40 hex digits (v1) or 64 (v2).
    pub fn parse_hex(text: &str) -> Result<Self> {
        match (text.len(), from_hex(text)) {
            (40, Some(b)) => Ok(InfoHashes { v1: Some(b.try_into().unwrap()), v2: None }),
            (64, Some(b)) => Ok(InfoHashes { v1: None, v2: Some(b.try_into().unwrap()) }),
            _ => invalid("An info hash has 40 or 64 hexadecimal digits"),
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Version {
    V1,
    V2,
    Hybrid,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FileSpec {
    /// Includes the torrent's name as its first component when the torrent has a directory.
    pub path: String,
    pub components: Vec<String>,
    pub size: i64,
    /// Offset in the piece space, pad files included.
    pub offset: i64,
    pub pad: bool,
    pub pieces_root: Option<Hash>,
}

/// A parsed info dictionary. Immutable once made.
#[derive(Debug)]
pub struct Info {
    pub raw: Vec<u8>,
    pub hashes: InfoHashes,
    pub name: String,
    pub piece_length: i32,
    pub files: Vec<FileSpec>,
    pub total: i64,
    pub num_pieces: i32,
    pub v1_pieces: Option<Vec<Id20>>,
    pub version: Version,
    pub private: bool,
}

fn component(v: &Value) -> Result<String> {
    let s = v.str().ok_or_else(|| Error("A file path is not text".into()))?;
    if s.is_empty() || s == "." || s == ".." || s.contains('/') || s.contains('\\') || s.contains('\0') {
        return invalid(format!("Unsafe file path component {s:?}"));
    }
    Ok(s.to_owned())
}

fn checked_length(v: Option<i64>) -> Result<i64> {
    match v {
        Some(n) if (0..1 << 50).contains(&n) => Ok(n),
        _ => invalid("Invalid file length"),
    }
}

impl Info {
    /// Parses an info dictionary's exact bytes, checking it against `expected` hashes when given.
    pub fn parse(raw: &[u8], expected: Option<&InfoHashes>) -> Result<Info> {
        if raw.len() > MAX_METADATA {
            return invalid("The torrent's metadata is too large");
        }
        let v = bencode::decode(raw)?;
        if v.dict().is_none() {
            return invalid("The info dictionary is not a dictionary");
        }
        let name = match v.get("name").map(component) {
            Some(Ok(n)) => n,
            _ => return invalid("The torrent has no valid name"),
        };
        let piece_length = v.get_int("piece length").unwrap_or(0);
        if !(1..=MAX_PIECE_LENGTH).contains(&piece_length) {
            return invalid("Invalid piece length");
        }
        let pl = piece_length;
        let meta_version = v.get_int("meta version");
        let has_v2 = meta_version == Some(2);
        let has_v1 = v.get("pieces").is_some();
        if !has_v1 && !has_v2 {
            return invalid("The torrent has no piece hashes");
        }
        if has_v2 && (pl < merkle::BLOCK as i64 || !(pl as u64).is_power_of_two()) {
            return invalid("A v2 torrent's piece length must be a power of two of at least 16 KiB");
        }
        let hashes = InfoHashes::of(raw, has_v1, has_v2);
        if let Some(e) = expected {
            let ok = e.v1.is_none_or(|h| hashes.v1 == Some(h)) && e.v2.is_none_or(|h| hashes.v2 == Some(h));
            if !ok || (e.v1.is_none() && e.v2.is_none()) {
                return invalid("The metadata does not match the info hash");
            }
        }
        let private = v.get_int("private") == Some(1);

        // v2's file tree, in its (sorted) order.
        let mut tree_files: Vec<(Vec<String>, i64, Option<Hash>)> = Vec::new();
        if has_v2 {
            let tree = v.get("file tree").ok_or_else(|| Error("A v2 torrent has no file tree".into()))?;
            fn walk(node: &Value, path: &mut Vec<String>, out: &mut Vec<(Vec<String>, i64, Option<Hash>)>, depth: usize) -> Result<()> {
                let d = node.dict().ok_or_else(|| Error("Invalid file tree".into()))?;
                if depth > 64 || out.len() > 100_000 {
                    return invalid("The file tree is too large");
                }
                for (k, child) in d {
                    if k.is_empty() {
                        let size = checked_length(child.get_int("length"))?;
                        let root = match child.get_bytes("pieces root") {
                            Some(r) if r.len() == 32 => Some(<Hash>::try_from(r).unwrap()),
                            None if size == 0 => None,
                            _ => return invalid("A file has no valid pieces root"),
                        };
                        if path.is_empty() || d.len() != 1 {
                            return invalid("Invalid file tree");
                        }
                        out.push((path.clone(), size, root));
                    } else {
                        path.push(component(&Value::Bytes(k.clone()))?);
                        walk(child, path, out, depth + 1)?;
                        path.pop();
                    }
                }
                Ok(())
            }
            walk(tree, &mut Vec::new(), &mut tree_files, 0)?;
            if tree_files.is_empty() {
                return invalid("The torrent has no files");
            }
        }

        let mut files = Vec::new();
        let mut offset = 0i64;
        let full_path = |components: &[String], multi: bool| -> String {
            if multi {
                std::iter::once(name.as_str()).chain(components.iter().map(String::as_str)).collect::<Vec<_>>().join("/")
            } else {
                name.clone()
            }
        };
        if has_v1 {
            let multi = v.get("files").is_some();
            if let Some(list) = v.get("files").and_then(Value::list) {
                if list.is_empty() {
                    return invalid("The torrent has no files");
                }
                for f in list {
                    let size = checked_length(f.get_int("length"))?;
                    let comps = f
                        .get("path")
                        .and_then(Value::list)
                        .filter(|p| !p.is_empty())
                        .ok_or_else(|| Error("A file has no path".into()))?;
                    let pad = f.get_str("attr").is_some_and(|a| a.contains('p'));
                    let components = if pad {
                        comps.iter().map(|c| c.str().unwrap_or("pad").to_owned()).collect()
                    } else {
                        comps.iter().map(component).collect::<Result<Vec<_>>>()?
                    };
                    files.push(FileSpec { path: full_path(&components, true), components, size, offset, pad, pieces_root: None });
                    offset += size;
                }
            } else {
                let size = checked_length(v.get_int("length"))?;
                files.push(FileSpec { path: name.clone(), components: vec![], size, offset, pad: false, pieces_root: None });
                offset = size;
            }
            if has_v2 {
                // Hybrid: the v1 file list, pads aside, must be the file tree's.
                let real: Vec<&mut FileSpec> = files.iter_mut().filter(|f| !f.pad).collect();
                if real.len() != tree_files.len() {
                    return invalid("The v1 and v2 file lists differ");
                }
                for (f, (comps, size, root)) in real.into_iter().zip(&tree_files) {
                    let tree_path = if multi { full_path(comps, true) } else { comps.join("/") };
                    if f.size != *size || f.path != tree_path {
                        return invalid("The v1 and v2 file lists differ");
                    }
                    f.pieces_root = *root;
                }
            }
        } else {
            let multi = !(tree_files.len() == 1 && tree_files[0].0 == [name.clone()]);
            let count = tree_files.len();
            for (i, (comps, size, root)) in tree_files.into_iter().enumerate() {
                files.push(FileSpec { path: full_path(&comps, multi), components: comps, size, offset, pad: false, pieces_root: root });
                offset += size;
                let tail = offset % pl;
                if tail != 0 && i + 1 < count {
                    // Every v2 file starts on a piece boundary.
                    let pad = pl - tail;
                    files.push(FileSpec {
                        path: format!(".pad/{pad}"),
                        components: vec![".pad".into(), pad.to_string()],
                        size: pad,
                        offset,
                        pad: true,
                        pieces_root: None,
                    });
                    offset += pad;
                }
            }
        }
        let total = offset;
        if total <= 0 {
            return invalid("The torrent is empty");
        }
        let num_pieces = (total + pl - 1) / pl;
        if num_pieces > i64::from(i32::MAX) / 2 {
            return invalid("The torrent has too many pieces");
        }
        let v1_pieces = if has_v1 {
            let p = v.get_bytes("pieces").ok_or_else(|| Error("Invalid piece hashes".into()))?;
            if p.len() % 20 != 0 || (p.len() / 20) as i64 != num_pieces {
                return invalid("The piece hashes don't match the files");
            }
            Some(p.chunks(20).map(|c| c.try_into().unwrap()).collect())
        } else {
            None
        };
        if has_v2 && files.iter().any(|f| !f.pad && f.size > 0 && f.offset % pl != 0) {
            return invalid("A v2 file does not start on a piece boundary");
        }
        Ok(Info {
            raw: raw.to_vec(),
            hashes,
            name,
            piece_length: pl as i32,
            files,
            total,
            num_pieces: num_pieces as i32,
            v1_pieces,
            version: match (has_v1, has_v2) {
                (true, true) => Version::Hybrid,
                (true, false) => Version::V1,
                _ => Version::V2,
            },
            private,
        })
    }

    pub fn piece_size(&self, p: i32) -> i32 {
        if p < 0 || p >= self.num_pieces {
            return 0;
        }
        let start = i64::from(p) * i64::from(self.piece_length);
        (self.total - start).min(i64::from(self.piece_length)) as i32
    }

    pub fn metadata(&self) -> Metadata {
        Metadata {
            piece_length: self.piece_length,
            piece_sizes: (0..self.num_pieces).map(|p| self.piece_size(p)).collect(),
            files: self
                .files
                .iter()
                .map(|f| FileEntry { path: f.path.clone(), size: f.size, offset: f.offset, pad: f.pad })
                .collect(),
        }
    }

    /// Byte ranges of one piece that belong to pad files.
    pub fn piece_pads(&self, p: i32) -> PadRanges {
        let pl = i64::from(self.piece_length);
        let (start, end) = (i64::from(p) * pl, i64::from(p) * pl + i64::from(self.piece_size(p)));
        self.files
            .iter()
            .filter(|f| f.pad && f.size > 0)
            .filter_map(|f| {
                let (lo, hi) = (start.max(f.offset), end.min(f.offset + f.size));
                (lo < hi).then(|| ((lo - start) as i32, (hi - lo) as i32))
            })
            .collect()
    }

    /// Byte ranges of each piece that belong to pad files.
    pub fn pad_ranges(&self) -> Vec<PadRanges> {
        let pl = i64::from(self.piece_length);
        let mut out = vec![PadRanges::new(); self.num_pieces as usize];
        for f in self.files.iter().filter(|f| f.pad && f.size > 0) {
            let (mut at, end) = (f.offset, f.offset + f.size);
            while at < end {
                let p = at / pl;
                let piece_end = ((p + 1) * pl).min(end);
                out[p as usize].push(((at - p * pl) as i32, (piece_end - at) as i32));
                at = piece_end;
            }
        }
        out
    }

    /// For v2 verification: the real file a piece holds data of, the piece's index in that file's layer, and how
    /// many of the piece's bytes are file data.
    pub fn v2_piece(&self, p: i32) -> Option<(usize, usize, usize)> {
        let pl = i64::from(self.piece_length);
        let start = i64::from(p) * pl;
        self.files.iter().enumerate().find_map(|(i, f)| {
            (!f.pad && f.size > 0 && start >= f.offset && start < f.offset + f.size)
                .then(|| (i, ((start - f.offset) / pl) as usize, (f.offset + f.size - start).min(pl) as usize))
        })
    }

    /// A v2 file's pieces: (first, count).
    pub fn file_pieces(&self, file: usize) -> (i32, i32) {
        let f = &self.files[file];
        let pl = i64::from(self.piece_length);
        ((f.offset / pl) as i32, ((f.size + pl - 1) / pl) as i32)
    }

    /// v2 files whose verification needs a piece layer (those larger than a piece).
    pub fn layered_files(&self) -> impl Iterator<Item = usize> + '_ {
        let pl = i64::from(self.piece_length);
        self.files
            .iter()
            .enumerate()
            .filter(move |(_, f)| !f.pad && f.size > pl && f.pieces_root.is_some())
            .map(|(i, _)| i)
    }
}

/// Validated piece layers, by pieces root.
pub type Layers = HashMap<Hash, Vec<Hash>>;

/// Checks a received piece layer against its file's root.
pub fn check_layer(info: &Info, file: usize, layer: &[Hash]) -> bool {
    let f = &info.files[file];
    let (_, count) = info.file_pieces(file);
    layer.len() == count as usize
        && f.pieces_root.is_some_and(|root| merkle::layer_root(layer, info.piece_length as usize) == root)
}

pub struct Torrent {
    pub info: Info,
    pub trackers: Vec<String>,
    pub layers: Layers,
    /// HTTP(S) web seeds (BEP 19 `url-list`).
    pub web_seeds: Vec<String>,
}

/// Parses a torrent file. Piece layers that fail their root are dropped; peers can supply them again.
pub fn parse_torrent(data: &[u8]) -> Result<Torrent> {
    if data.len() > MAX_METADATA {
        return invalid("The torrent file is too large");
    }
    let v = bencode::decode(data)?;
    let range = bencode::raw_entry(data, b"info")?.ok_or_else(|| Error("The torrent has no info dictionary".into()))?;
    let info = Info::parse(&data[range], None)?;
    let mut trackers: Vec<String> = Vec::new();
    let mut push = |url: &str| {
        if !url.is_empty() && !trackers.iter().any(|t| t == url) {
            trackers.push(url.to_owned());
        }
    };
    if let Some(tiers) = v.get("announce-list").and_then(Value::list) {
        for tier in tiers {
            for url in tier.list().unwrap_or_default() {
                if let Some(u) = url.str() {
                    push(u);
                }
            }
        }
    }
    if let Some(u) = v.get_str("announce") {
        push(u);
    }
    let mut layers = Layers::new();
    if let Some(d) = v.get("piece layers").and_then(Value::dict) {
        let index: HashMap<Hash, usize> =
            info.layered_files().filter_map(|i| info.files[i].pieces_root.map(|r| (r, i))).collect();
        for (root, value) in d {
            let (Ok(root), Some(b)) = (<Hash>::try_from(root.as_slice()), value.bytes()) else { continue };
            let Some(&file) = index.get(&root) else { continue };
            if b.len() % 32 != 0 {
                continue;
            }
            let layer: Vec<Hash> = b.chunks(32).map(|c| c.try_into().unwrap()).collect();
            if check_layer(&info, file, &layer) {
                layers.insert(root, layer);
            }
        }
    }
    let mut web_seeds: Vec<String> = Vec::new();
    match v.get("url-list") {
        Some(Value::Bytes(b)) => web_seeds.extend(std::str::from_utf8(b).ok().map(str::to_owned)),
        Some(Value::List(l)) => web_seeds.extend(l.iter().filter_map(|u| u.str().map(str::to_owned))),
        _ => {}
    }
    web_seeds.retain(|u| u.starts_with("http://") || u.starts_with("https://"));
    web_seeds.dedup();
    Ok(Torrent { info, trackers, layers, web_seeds })
}

/// A torrent file for metadata we hold, with trackers, web seeds and whichever piece layers are known.
pub fn write_torrent(info: &Info, trackers: &[String], layers: &Layers, web_seeds: &[String]) -> Vec<u8> {
    let mut out = Vec::with_capacity(info.raw.len() + 256);
    out.push(b'd');
    let entry = |key: &str, value: &Value, out: &mut Vec<u8>| {
        bencode::encode_into(&bytes(key), out);
        bencode::encode_into(value, out);
    };
    if let Some(first) = trackers.first() {
        entry("announce", &bytes(first.as_bytes()), &mut out);
        let tiers = trackers.iter().map(|t| Value::List(vec![bytes(t.as_bytes())])).collect();
        entry("announce-list", &Value::List(tiers), &mut out);
    }
    // Keys stay sorted: "info" before "piece layers".
    bencode::encode_into(&bytes("info"), &mut out);
    out.extend_from_slice(&info.raw);
    if !layers.is_empty() {
        let d = layers.iter().map(|(root, layer)| (root.to_vec(), Value::Bytes(layer.concat()))).collect();
        entry("piece layers", &Value::Dict(d), &mut out);
    }
    if !web_seeds.is_empty() {
        entry("url-list", &Value::List(web_seeds.iter().map(|u| bytes(u.as_bytes())).collect()), &mut out);
    }
    out.push(b'e');
    out
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Magnet {
    pub hashes: InfoHashes,
    pub name: Option<String>,
    pub trackers: Vec<String>,
    pub peers: Vec<String>,
    pub web_seeds: Vec<String>,
}

fn percent_decode(s: &str) -> String {
    let b = s.as_bytes();
    let mut out = Vec::with_capacity(b.len());
    let mut i = 0;
    while i < b.len() {
        match b[i] {
            b'%' if i + 2 < b.len() => {
                match s.get(i + 1..i + 3).map(|h| u8::from_str_radix(h, 16)) {
                    Some(Ok(v)) => {
                        out.push(v);
                        i += 3;
                        continue;
                    }
                    _ => out.push(b'%'),
                }
            }
            b'+' => out.push(b' '),
            c => out.push(c),
        }
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

pub fn parse_magnet(uri: &str) -> Result<Magnet> {
    let query = uri
        .strip_prefix("magnet:?")
        .or_else(|| uri.strip_prefix("MAGNET:?"))
        .ok_or_else(|| Error("Not a magnet link".into()))?;
    let mut m = Magnet::default();
    for pair in query.split('&') {
        let (key, value) = pair.split_once('=').unwrap_or((pair, ""));
        let value = percent_decode(value);
        // Numbered keys (xt.1, tr.2) mean the same.
        let key = key.split('.').next().unwrap_or(key);
        match key {
            "xt" => {
                if let Some(h) = value.strip_prefix("urn:btih:") {
                    let decoded = match h.len() {
                        40 => from_hex(h),
                        32 => from_base32(h),
                        _ => None,
                    };
                    m.hashes.v1 = Some(decoded.and_then(|d| d.try_into().ok()).ok_or_else(|| Error("Invalid magnet info hash".into()))?);
                } else if let Some(h) = value.strip_prefix("urn:btmh:") {
                    // A multihash: 0x12 (SHA-256), 0x20 (32 bytes), then the hash.
                    let d = from_hex(h).filter(|d| d.len() == 34 && d[0] == 0x12 && d[1] == 0x20);
                    m.hashes.v2 = Some(d.ok_or_else(|| Error("Invalid magnet v2 info hash".into()))?[2..].try_into().unwrap());
                }
            }
            "dn" => m.name = Some(value),
            "tr" if !m.trackers.contains(&value) => m.trackers.push(value),
            "x" if pair.starts_with("x.pe=") => m.peers.push(value),
            "ws" if value.starts_with("http://") || value.starts_with("https://") => m.web_seeds.push(value),
            _ => {}
        }
    }
    if m.hashes.v1.is_none() && m.hashes.v2.is_none() {
        return invalid("The magnet link has no info hash");
    }
    Ok(m)
}

/// Collects the files to put in a torrent: one file, or a directory's regular files in sorted order.
fn collect(path: &Path) -> std::io::Result<Vec<(Vec<String>, PathBuf, i64)>> {
    let meta = std::fs::metadata(path)?;
    if meta.is_file() {
        return Ok(vec![(vec![], path.to_path_buf(), meta.len() as i64)]);
    }
    let mut out = Vec::new();
    fn walk(dir: &Path, prefix: &mut Vec<String>, out: &mut Vec<(Vec<String>, PathBuf, i64)>) -> std::io::Result<()> {
        let mut entries: Vec<_> = std::fs::read_dir(dir)?.collect::<std::io::Result<_>>()?;
        entries.sort_by_key(|e| e.file_name());
        for e in entries {
            let name = e.file_name().to_string_lossy().into_owned();
            let t = e.file_type()?;
            prefix.push(name);
            if t.is_dir() {
                walk(&e.path(), prefix, out)?;
            } else if t.is_file() {
                out.push((prefix.clone(), e.path(), e.metadata()?.len() as i64));
            }
            prefix.pop();
        }
        Ok(())
    }
    walk(path, &mut Vec::new(), &mut out)?;
    Ok(out)
}

/// Makes a torrent file, every piece hashed, for a file or a directory. Hybrids pad each file to a piece boundary.
pub fn create(path: &Path, piece_length: i32, version: Version) -> std::io::Result<Vec<u8>> {
    use std::io::{Error as IoError, ErrorKind, Read};
    let pl = piece_length as usize;
    if (version != Version::V1 && (pl < merkle::BLOCK || !pl.is_power_of_two())) || pl == 0 {
        return Err(IoError::new(ErrorKind::InvalidInput, "Invalid piece length"));
    }
    let name = path
        .file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .ok_or_else(|| IoError::new(ErrorKind::InvalidInput, "No name"))?;
    let files = collect(path)?;
    let single = files.len() == 1 && files[0].0.is_empty();
    let v1 = version != Version::V2;
    let v2 = version != Version::V1;
    let pads = version == Version::Hybrid;

    let mut v1_hasher_buf: Vec<u8> = Vec::with_capacity(pl);
    let mut v1_pieces: Vec<u8> = Vec::new();
    let mut v1_files: Vec<Value> = Vec::new();
    let mut tree: BTreeMap<Vec<u8>, Value> = BTreeMap::new();
    let mut layers: BTreeMap<Vec<u8>, Value> = BTreeMap::new();
    let count = files.len();
    for (i, (components, file, size)) in files.iter().enumerate() {
        let mut data = Vec::with_capacity(*size as usize);
        std::fs::File::open(file)?.read_to_end(&mut data)?;
        if v1 {
            for chunk in data.chunks(pl) {
                let mut rest = chunk;
                while !rest.is_empty() {
                    let take = (pl - v1_hasher_buf.len()).min(rest.len());
                    v1_hasher_buf.extend_from_slice(&rest[..take]);
                    rest = &rest[take..];
                    if v1_hasher_buf.len() == pl {
                        v1_pieces.extend_from_slice(&sha1(&v1_hasher_buf));
                        v1_hasher_buf.clear();
                    }
                }
            }
            let path_list = Value::List(components.iter().map(|c| bytes(c.as_bytes())).collect());
            v1_files.push(dict([("length", Value::Int(*size)), ("path", path_list)]));
            let tail = data.len() % pl;
            if pads && tail != 0 && i + 1 < count {
                let pad = pl - tail;
                v1_hasher_buf.extend(std::iter::repeat_n(0u8, pad));
                if v1_hasher_buf.len() == pl {
                    v1_pieces.extend_from_slice(&sha1(&v1_hasher_buf));
                    v1_hasher_buf.clear();
                }
                let path_list = Value::List(vec![bytes(".pad"), bytes(pad.to_string())]);
                v1_files.push(dict([("attr", bytes("p")), ("length", Value::Int(pad as i64)), ("path", path_list)]));
            }
        }
        if v2 {
            let mut leaf = BTreeMap::new();
            leaf.insert(b"length".to_vec(), Value::Int(*size));
            if *size > 0 {
                let root = merkle::file_root(&data, pl);
                leaf.insert(b"pieces root".to_vec(), bytes(root.to_vec()));
                if data.len() > pl {
                    let layer: Vec<u8> = data.chunks(pl).flat_map(|p| merkle::piece_hash(p, pl)).collect();
                    layers.insert(root.to_vec(), Value::Bytes(layer));
                }
            }
            let mut node = &mut tree;
            let path: Vec<String> = if single { vec![name.clone()] } else { components.clone() };
            for c in &path {
                let entry = node.entry(c.as_bytes().to_vec()).or_insert_with(|| Value::Dict(BTreeMap::new()));
                let Value::Dict(d) = entry else { unreachable!() };
                node = d;
            }
            node.insert(Vec::new(), Value::Dict(leaf));
        }
    }
    if v1 && !v1_hasher_buf.is_empty() {
        v1_pieces.extend_from_slice(&sha1(&v1_hasher_buf));
    }
    let mut info = BTreeMap::new();
    info.insert(b"name".to_vec(), bytes(name.as_bytes()));
    info.insert(b"piece length".to_vec(), Value::Int(i64::from(piece_length)));
    if v1 {
        info.insert(b"pieces".to_vec(), Value::Bytes(v1_pieces));
        if single {
            info.insert(b"length".to_vec(), Value::Int(files[0].2));
        } else {
            info.insert(b"files".to_vec(), Value::List(v1_files));
        }
    }
    if v2 {
        info.insert(b"meta version".to_vec(), Value::Int(2));
        info.insert(b"file tree".to_vec(), Value::Dict(tree));
    }
    let mut top = BTreeMap::new();
    top.insert(b"created by".to_vec(), bytes("Stream"));
    top.insert(b"info".to_vec(), Value::Dict(info));
    if v2 && !layers.is_empty() {
        top.insert(b"piece layers".to_vec(), Value::Dict(layers));
    }
    Ok(bencode::encode(&Value::Dict(top)))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::cache::tests::temp_dir;

    fn content(path: &Path, size: usize, seed: u8) {
        let data: Vec<u8> = (0..size).map(|i| (i as u32).wrapping_mul(2_654_435_761).to_le_bytes()[0] ^ seed).collect();
        std::fs::write(path, data).unwrap();
    }

    #[test]
    fn created_torrents_parse_in_every_version() {
        let dir = temp_dir("metainfo");
        let d = dir.join("show");
        std::fs::create_dir_all(d.join("extras")).unwrap();
        content(&d.join("a.mkv"), 100_000, 1);
        content(&d.join("extras/b.txt"), 10, 2);
        content(&d.join("c.mkv"), 70_000, 3);
        for version in [Version::V1, Version::V2, Version::Hybrid] {
            let t = parse_torrent(&create(&d, 32 * 1024, version).unwrap()).unwrap();
            let real: Vec<_> = t.info.files.iter().filter(|f| !f.pad).map(|f| (f.path.as_str(), f.size)).collect();
            assert_eq!(real, [("show/a.mkv", 100_000), ("show/c.mkv", 70_000), ("show/extras/b.txt", 10)], "{version:?}");
            assert_eq!(t.info.version, version);
            if version != Version::V1 {
                assert!(t.info.files.iter().filter(|f| !f.pad).all(|f| f.offset % 32768 == 0));
                assert_eq!(t.layers.len(), 2, "both multi-piece files have layers");
                assert_eq!(t.info.layered_files().count(), 2);
            }
            let pads: i64 = t.info.files.iter().filter(|f| f.pad).map(|f| f.size).sum();
            assert_eq!(t.info.total, 170_010 + pads);
            // Written back, the torrent keeps its info hash.
            let seeds = ["https://seed.example/files/".to_string()];
            let again = parse_torrent(&write_torrent(&t.info, &["udp://t.example:1".into()], &t.layers, &seeds)).unwrap();
            assert_eq!(again.web_seeds, seeds);
            assert_eq!(again.info.hashes, t.info.hashes);
            assert_eq!(again.trackers, ["udp://t.example:1"]);
            assert_eq!(again.layers, t.layers);
        }
    }

    #[test]
    fn single_file_v2_paths_and_pieces() {
        let dir = temp_dir("metainfo-single");
        content(&dir.join("film.mp4"), 3 * 16384 + 1, 4);
        let t = parse_torrent(&create(&dir.join("film.mp4"), 16384, Version::V2).unwrap()).unwrap();
        assert_eq!(t.info.files[0].path, "film.mp4");
        assert_eq!(t.info.num_pieces, 4);
        assert_eq!(t.info.v2_piece(3), Some((0, 3, 1)));
        assert_eq!(t.info.piece_size(3), 1);
    }

    #[test]
    fn magnets_carry_hashes_trackers_and_peers() {
        let m = parse_magnet(
            "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=A+film&tr=http%3A%2F%2Ft.example%2Fa&x.pe=127.0.0.1:6881",
        )
        .unwrap();
        assert_eq!(hex(&m.hashes.v1.unwrap()), "0123456789abcdef0123456789abcdef01234567");
        assert_eq!(m.name.as_deref(), Some("A film"));
        assert_eq!(m.trackers, ["http://t.example/a"]);
        assert_eq!(m.peers, ["127.0.0.1:6881"]);
        let b32 = parse_magnet("magnet:?xt=urn:btih:AERUKZ4JVPG66AJDIVTYTK6N54ASGRLH").unwrap();
        assert_eq!(hex(&b32.hashes.v1.unwrap()), "0123456789abcdef0123456789abcdef01234567");
        let v2 = format!("magnet:?xt=urn:btmh:1220{}", "ab".repeat(32));
        assert_eq!(parse_magnet(&v2).unwrap().hashes.v2, Some([0xab; 32]));
        assert!(parse_magnet("magnet:?dn=x").is_err());
    }

    #[test]
    fn unsafe_paths_and_mismatched_hashes_are_refused() {
        let info = bencode::encode(&dict([
            ("files", Value::List(vec![dict([("length", Value::Int(1)), ("path", Value::List(vec![bytes("..")]))])])),
            ("name", bytes("x")),
            ("piece length", Value::Int(16384)),
            ("pieces", Value::Bytes(vec![0; 20])),
        ]));
        assert!(Info::parse(&info, None).is_err());
        let ok = bencode::encode(&dict([
            ("length", Value::Int(1)),
            ("name", bytes("x")),
            ("piece length", Value::Int(16384)),
            ("pieces", Value::Bytes(vec![0; 20])),
        ]));
        let wrong = InfoHashes { v1: Some([1; 20]), v2: None };
        assert!(Info::parse(&ok, Some(&wrong)).is_err());
        assert!(Info::parse(&ok, Some(&InfoHashes::of(&ok, true, false))).is_ok());
    }
}
