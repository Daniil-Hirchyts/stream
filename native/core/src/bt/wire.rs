//! The peer wire protocol (BEP 3) with the fast extension (BEP 6), the extension protocol (BEP 10) and v2 hash
//! messages (BEP 52), plus the extension payloads Stream speaks: ut_metadata (BEP 9), ut_pex (BEP 11) and
//! lt_donthave (BEP 54).

use super::bencode::{self, bytes, Value};
use super::merkle::Hash;
use super::metainfo::Id20;
use std::collections::BTreeMap;
use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

pub const PROTOCOL: &[u8; 19] = b"BitTorrent protocol";
/// Larger messages end the connection; the largest legitimate ones are bitfields of huge torrents and hash lists.
pub const MAX_MESSAGE: usize = 2 << 20;
pub const BLOCK: u32 = 16 * 1024;

/// Our extension message ids, as announced in the extension handshake.
pub const EXT_METADATA: u8 = 1;
pub const EXT_PEX: u8 = 2;
pub const EXT_DONTHAVE: u8 = 3;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Reserved(pub [u8; 8]);

impl Reserved {
    /// `v2` only for torrents with a v2 info hash: libtorrent disconnects peers claiming v2 on a v1 torrent.
    pub fn ours(dht: bool, v2: bool) -> Self {
        let mut r = [0u8; 8];
        r[5] |= 0x10; // extension protocol
        r[7] |= 0x04; // fast extension
        if v2 {
            r[7] |= 0x10; // v2 upgrade (BEP 52)
        }
        if dht {
            r[7] |= 0x01;
        }
        Reserved(r)
    }
    pub fn extensions(&self) -> bool {
        self.0[5] & 0x10 != 0
    }
    pub fn fast(&self) -> bool {
        self.0[7] & 0x04 != 0
    }
    pub fn v2(&self) -> bool {
        self.0[7] & 0x10 != 0
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Handshake {
    pub reserved: Reserved,
    pub info_hash: Id20,
    pub peer_id: Id20,
}

impl Handshake {
    pub fn encode(&self) -> [u8; 68] {
        let mut out = [0u8; 68];
        out[0] = 19;
        out[1..20].copy_from_slice(PROTOCOL);
        out[20..28].copy_from_slice(&self.reserved.0);
        out[28..48].copy_from_slice(&self.info_hash);
        out[48..68].copy_from_slice(&self.peer_id);
        out
    }

    /// Reads the protocol, reserved bits and info hash; the peer id follows separately so an incoming connection
    /// can answer after learning the torrent.
    pub async fn read_start<R: AsyncRead + Unpin>(r: &mut R) -> io::Result<(Reserved, Id20)> {
        let mut buf = [0u8; 48];
        r.read_exact(&mut buf).await?;
        if buf[0] != 19 || &buf[1..20] != PROTOCOL {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "Not a BitTorrent peer"));
        }
        Ok((Reserved(buf[20..28].try_into().unwrap()), buf[28..48].try_into().unwrap()))
    }

    pub async fn read_peer_id<R: AsyncRead + Unpin>(r: &mut R) -> io::Result<Id20> {
        let mut id = [0u8; 20];
        r.read_exact(&mut id).await?;
        Ok(id)
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct BlockRef {
    pub piece: u32,
    pub offset: u32,
    pub length: u32,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct HashRequest {
    pub root: Hash,
    pub base_layer: u32,
    pub index: u32,
    pub length: u32,
    pub proof_layers: u32,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Message {
    KeepAlive,
    Choke,
    Unchoke,
    Interested,
    NotInterested,
    Have(u32),
    Bitfield(Vec<u8>),
    Request(BlockRef),
    Piece { piece: u32, offset: u32, data: Vec<u8> },
    Cancel(BlockRef),
    Port(u16),
    HaveAll,
    HaveNone,
    Reject(BlockRef),
    AllowedFast(u32),
    Suggest(u32),
    Extended { id: u8, payload: Vec<u8> },
    HashRequest(HashRequest),
    Hashes { request: HashRequest, hashes: Vec<Hash> },
    HashReject(HashRequest),
}

fn u32_at(b: &[u8], at: usize) -> io::Result<u32> {
    b.get(at..at + 4)
        .map(|s| u32::from_be_bytes(s.try_into().unwrap()))
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "Truncated message"))
}

fn block_ref(b: &[u8]) -> io::Result<BlockRef> {
    if b.len() != 12 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "Invalid block message"));
    }
    Ok(BlockRef { piece: u32_at(b, 0)?, offset: u32_at(b, 4)?, length: u32_at(b, 8)? })
}

fn hash_request(b: &[u8]) -> io::Result<HashRequest> {
    if b.len() < 48 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "Invalid hash message"));
    }
    Ok(HashRequest {
        root: b[..32].try_into().unwrap(),
        base_layer: u32_at(b, 32)?,
        index: u32_at(b, 36)?,
        length: u32_at(b, 40)?,
        proof_layers: u32_at(b, 44)?,
    })
}

fn put_request(out: &mut Vec<u8>, r: &HashRequest) {
    out.extend_from_slice(&r.root);
    for v in [r.base_layer, r.index, r.length, r.proof_layers] {
        out.extend_from_slice(&v.to_be_bytes());
    }
}

impl Message {
    pub fn decode(body: &[u8]) -> io::Result<Message> {
        let Some((&id, p)) = body.split_first() else { return Ok(Message::KeepAlive) };
        let exact = |n: usize| {
            if p.len() == n {
                Ok(())
            } else {
                Err(io::Error::new(io::ErrorKind::InvalidData, format!("Message {id} has the wrong length")))
            }
        };
        Ok(match id {
            0 => exact(0).map(|_| Message::Choke)?,
            1 => exact(0).map(|_| Message::Unchoke)?,
            2 => exact(0).map(|_| Message::Interested)?,
            3 => exact(0).map(|_| Message::NotInterested)?,
            4 => exact(4).and_then(|_| u32_at(p, 0)).map(Message::Have)?,
            5 => Message::Bitfield(p.to_vec()),
            6 => Message::Request(block_ref(p)?),
            7 => {
                if p.len() < 8 {
                    return Err(io::Error::new(io::ErrorKind::InvalidData, "Truncated piece"));
                }
                Message::Piece { piece: u32_at(p, 0)?, offset: u32_at(p, 4)?, data: p[8..].to_vec() }
            }
            8 => Message::Cancel(block_ref(p)?),
            9 => {
                exact(2)?;
                Message::Port(u16::from_be_bytes([p[0], p[1]]))
            }
            0x0d => exact(4).and_then(|_| u32_at(p, 0)).map(Message::Suggest)?,
            0x0e => exact(0).map(|_| Message::HaveAll)?,
            0x0f => exact(0).map(|_| Message::HaveNone)?,
            0x10 => Message::Reject(block_ref(p)?),
            0x11 => exact(4).and_then(|_| u32_at(p, 0)).map(Message::AllowedFast)?,
            20 => {
                let (&ext, payload) =
                    p.split_first().ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "Empty extended message"))?;
                Message::Extended { id: ext, payload: payload.to_vec() }
            }
            21 => exact(48).and_then(|_| hash_request(p)).map(Message::HashRequest)?,
            22 => {
                let request = hash_request(p)?;
                let rest = &p[48..];
                if rest.len() % 32 != 0 {
                    return Err(io::Error::new(io::ErrorKind::InvalidData, "Invalid hashes"));
                }
                Message::Hashes { request, hashes: rest.chunks(32).map(|c| c.try_into().unwrap()).collect() }
            }
            23 => exact(48).and_then(|_| hash_request(p)).map(Message::HashReject)?,
            // Unknown messages are ignored, as the protocol asks.
            _ => Message::KeepAlive,
        })
    }

    pub fn encode(&self, out: &mut Vec<u8>) {
        let start = out.len();
        out.extend_from_slice(&[0; 4]);
        let block = |out: &mut Vec<u8>, id: u8, b: &BlockRef| {
            out.push(id);
            for v in [b.piece, b.offset, b.length] {
                out.extend_from_slice(&v.to_be_bytes());
            }
        };
        match self {
            Message::KeepAlive => {}
            Message::Choke => out.push(0),
            Message::Unchoke => out.push(1),
            Message::Interested => out.push(2),
            Message::NotInterested => out.push(3),
            Message::Have(p) => {
                out.push(4);
                out.extend_from_slice(&p.to_be_bytes());
            }
            Message::Bitfield(b) => {
                out.push(5);
                out.extend_from_slice(b);
            }
            Message::Request(b) => block(out, 6, b),
            Message::Piece { piece, offset, data } => {
                out.push(7);
                out.extend_from_slice(&piece.to_be_bytes());
                out.extend_from_slice(&offset.to_be_bytes());
                out.extend_from_slice(data);
            }
            Message::Cancel(b) => block(out, 8, b),
            Message::Port(port) => {
                out.push(9);
                out.extend_from_slice(&port.to_be_bytes());
            }
            Message::Suggest(p) => {
                out.push(0x0d);
                out.extend_from_slice(&p.to_be_bytes());
            }
            Message::HaveAll => out.push(0x0e),
            Message::HaveNone => out.push(0x0f),
            Message::Reject(b) => block(out, 0x10, b),
            Message::AllowedFast(p) => {
                out.push(0x11);
                out.extend_from_slice(&p.to_be_bytes());
            }
            Message::Extended { id, payload } => {
                out.push(20);
                out.push(*id);
                out.extend_from_slice(payload);
            }
            Message::HashRequest(r) => {
                out.push(21);
                put_request(out, r);
            }
            Message::Hashes { request, hashes } => {
                out.push(22);
                put_request(out, request);
                for h in hashes {
                    out.extend_from_slice(h);
                }
            }
            Message::HashReject(r) => {
                out.push(23);
                put_request(out, r);
            }
        }
        let len = (out.len() - start - 4) as u32;
        out[start..start + 4].copy_from_slice(&len.to_be_bytes());
    }
}

/// Reads one length-prefixed message.
pub async fn read_message<R: AsyncRead + Unpin>(r: &mut R, buf: &mut Vec<u8>) -> io::Result<Message> {
    let len = r.read_u32().await? as usize;
    if len > MAX_MESSAGE {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "Message too large"));
    }
    buf.resize(len, 0);
    r.read_exact(buf).await?;
    Message::decode(buf)
}

pub async fn write_messages<W: AsyncWrite + Unpin>(w: &mut W, messages: &[Message]) -> io::Result<()> {
    let mut out = Vec::new();
    for m in messages {
        m.encode(&mut out);
    }
    w.write_all(&out).await
}

/// A set of pieces, as bitfield messages carry them.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Bitfield {
    bits: Vec<u8>,
    len: usize,
    count: usize,
}

impl Bitfield {
    pub fn new(len: usize) -> Self {
        Bitfield { bits: vec![0; len.div_ceil(8)], len, count: 0 }
    }
    pub fn full(len: usize) -> Self {
        let mut b = Bitfield::new(len);
        for i in 0..len {
            b.set(i, true);
        }
        b
    }
    /// From a bitfield message; spare bits are ignored.
    pub fn from_bytes(bytes: &[u8], len: usize) -> Option<Self> {
        if bytes.len() != len.div_ceil(8) {
            return None;
        }
        let mut b = Bitfield { bits: bytes.to_vec(), len, count: 0 };
        if len % 8 != 0 {
            if let Some(last) = b.bits.last_mut() {
                *last &= 0xffu8 << (8 - len % 8);
            }
        }
        b.count = b.bits.iter().map(|x| x.count_ones() as usize).sum();
        Some(b)
    }
    pub fn len(&self) -> usize {
        self.len
    }
    pub fn is_empty(&self) -> bool {
        self.len == 0
    }
    pub fn get(&self, i: usize) -> bool {
        i < self.len && self.bits[i / 8] & (0x80 >> (i % 8)) != 0
    }
    pub fn set(&mut self, i: usize, value: bool) {
        if i >= self.len || self.get(i) == value {
            return;
        }
        self.bits[i / 8] ^= 0x80 >> (i % 8);
        if value {
            self.count += 1;
        } else {
            self.count -= 1;
        }
    }
    pub fn count(&self) -> usize {
        self.count
    }
    pub fn all(&self) -> bool {
        self.count == self.len
    }
    pub fn bytes(&self) -> &[u8] {
        &self.bits
    }
}

/// The extension handshake's fields Stream uses.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct ExtHandshake {
    pub metadata: Option<u8>,
    pub pex: Option<u8>,
    pub donthave: Option<u8>,
    pub metadata_size: Option<usize>,
    pub listen_port: Option<u16>,
    pub client: Option<String>,
    pub upload_only: bool,
}

impl ExtHandshake {
    pub fn encode(&self) -> Vec<u8> {
        let mut m = BTreeMap::new();
        for (name, id) in [("ut_metadata", self.metadata), ("ut_pex", self.pex), ("lt_donthave", self.donthave)] {
            if let Some(id) = id {
                m.insert(name.as_bytes().to_vec(), Value::Int(i64::from(id)));
            }
        }
        let mut d = BTreeMap::new();
        d.insert(b"m".to_vec(), Value::Dict(m));
        if let Some(size) = self.metadata_size {
            d.insert(b"metadata_size".to_vec(), Value::Int(size as i64));
        }
        if let Some(port) = self.listen_port {
            d.insert(b"p".to_vec(), Value::Int(i64::from(port)));
        }
        if let Some(v) = &self.client {
            d.insert(b"v".to_vec(), bytes(v.as_bytes()));
        }
        d.insert(b"reqq".to_vec(), Value::Int(500));
        bencode::encode(&Value::Dict(d))
    }

    pub fn decode(payload: &[u8]) -> Option<Self> {
        let v = bencode::decode(payload).ok()?;
        let m = v.get("m");
        let id = |name: &str| m.and_then(|m| m.get_int(name)).and_then(|i| u8::try_from(i).ok()).filter(|&i| i != 0);
        Some(ExtHandshake {
            metadata: id("ut_metadata"),
            pex: id("ut_pex"),
            donthave: id("lt_donthave"),
            metadata_size: v.get_int("metadata_size").and_then(|s| usize::try_from(s).ok()),
            listen_port: v.get_int("p").and_then(|p| u16::try_from(p).ok()).filter(|&p| p != 0),
            client: v.get_str("v").map(str::to_owned),
            upload_only: v.get_int("upload_only").unwrap_or(0) != 0,
        })
    }
}

/// ut_metadata messages.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum MetadataMessage {
    Request(u32),
    Data { piece: u32, total_size: usize, data: Vec<u8> },
    Reject(u32),
}

pub const METADATA_PIECE: usize = 16 * 1024;

impl MetadataMessage {
    pub fn encode(&self) -> Vec<u8> {
        let (kind, piece) = match self {
            MetadataMessage::Request(p) => (0, *p),
            MetadataMessage::Data { piece, .. } => (1, *piece),
            MetadataMessage::Reject(p) => (2, *p),
        };
        let mut d = BTreeMap::new();
        d.insert(b"msg_type".to_vec(), Value::Int(kind));
        d.insert(b"piece".to_vec(), Value::Int(i64::from(piece)));
        if let MetadataMessage::Data { total_size, .. } = self {
            d.insert(b"total_size".to_vec(), Value::Int(*total_size as i64));
        }
        let mut out = bencode::encode(&Value::Dict(d));
        if let MetadataMessage::Data { data, .. } = self {
            out.extend_from_slice(data);
        }
        out
    }

    pub fn decode(payload: &[u8]) -> Option<Self> {
        let (v, used) = bencode::decode_prefix(payload).ok()?;
        let piece = u32::try_from(v.get_int("piece")?).ok()?;
        match v.get_int("msg_type")? {
            0 => Some(MetadataMessage::Request(piece)),
            1 => Some(MetadataMessage::Data {
                piece,
                total_size: usize::try_from(v.get_int("total_size")?).ok()?,
                data: payload[used..].to_vec(),
            }),
            2 => Some(MetadataMessage::Reject(piece)),
            _ => None,
        }
    }
}

pub fn compact_peers(peers: &[SocketAddr]) -> (Vec<u8>, Vec<u8>) {
    let (mut v4, mut v6) = (Vec::new(), Vec::new());
    for p in peers {
        match p.ip() {
            IpAddr::V4(ip) => {
                v4.extend_from_slice(&ip.octets());
                v4.extend_from_slice(&p.port().to_be_bytes());
            }
            IpAddr::V6(ip) => {
                v6.extend_from_slice(&ip.octets());
                v6.extend_from_slice(&p.port().to_be_bytes());
            }
        }
    }
    (v4, v6)
}

pub fn parse_compact_v4(b: &[u8]) -> Vec<SocketAddr> {
    b.chunks_exact(6)
        .map(|c| SocketAddr::new(Ipv4Addr::new(c[0], c[1], c[2], c[3]).into(), u16::from_be_bytes([c[4], c[5]])))
        .filter(|a| a.port() != 0)
        .collect()
}

pub fn parse_compact_v6(b: &[u8]) -> Vec<SocketAddr> {
    b.chunks_exact(18)
        .map(|c| {
            let ip: [u8; 16] = c[..16].try_into().unwrap();
            SocketAddr::new(Ipv6Addr::from(ip).into(), u16::from_be_bytes([c[16], c[17]]))
        })
        .filter(|a| a.port() != 0)
        .collect()
}

/// A ut_pex message announcing peers we're connected to.
pub fn encode_pex(added: &[SocketAddr], dropped: &[SocketAddr]) -> Vec<u8> {
    let (a4, a6) = compact_peers(added);
    let (d4, d6) = compact_peers(dropped);
    let mut d = BTreeMap::new();
    d.insert(b"added".to_vec(), Value::Bytes(a4.clone()));
    d.insert(b"added.f".to_vec(), Value::Bytes(vec![0; a4.len() / 6]));
    d.insert(b"added6".to_vec(), Value::Bytes(a6.clone()));
    d.insert(b"added6.f".to_vec(), Value::Bytes(vec![0; a6.len() / 18]));
    d.insert(b"dropped".to_vec(), Value::Bytes(d4));
    d.insert(b"dropped6".to_vec(), Value::Bytes(d6));
    bencode::encode(&Value::Dict(d))
}

/// Peers a ut_pex message adds.
pub fn decode_pex(payload: &[u8]) -> Vec<SocketAddr> {
    let Ok(v) = bencode::decode(payload) else { return Vec::new() };
    let mut out = parse_compact_v4(v.get_bytes("added").unwrap_or_default());
    out.extend(parse_compact_v6(v.get_bytes("added6").unwrap_or_default()));
    out.truncate(200);
    out
}

pub fn new_peer_id() -> Id20 {
    let mut id = *b"-ST0100-000000000000";
    let mut random = [0u8; 12];
    let _ = getrandom::fill(&mut random);
    for (i, b) in random.iter().enumerate() {
        id[8 + i] = b"0123456789abcdefghijklmnopqrstuvwxyz"[*b as usize % 36];
    }
    id
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn messages_round_trip() {
        let r = HashRequest { root: [3; 32], base_layer: 2, index: 0, length: 4, proof_layers: 1 };
        let all = [
            Message::KeepAlive,
            Message::Choke,
            Message::Unchoke,
            Message::Interested,
            Message::NotInterested,
            Message::Have(7),
            Message::Bitfield(vec![0xa0]),
            Message::Request(BlockRef { piece: 1, offset: 16384, length: 16384 }),
            Message::Piece { piece: 1, offset: 0, data: vec![9; 10] },
            Message::Cancel(BlockRef { piece: 1, offset: 0, length: 5 }),
            Message::Port(6881),
            Message::HaveAll,
            Message::HaveNone,
            Message::Reject(BlockRef { piece: 2, offset: 0, length: 5 }),
            Message::AllowedFast(3),
            Message::Extended { id: 3, payload: vec![0, 0, 0, 4] },
            Message::HashRequest(r),
            Message::Hashes { request: r, hashes: vec![[1; 32], [2; 32]] },
            Message::HashReject(r),
        ];
        for m in all {
            let mut out = Vec::new();
            m.encode(&mut out);
            assert_eq!(u32::from_be_bytes(out[..4].try_into().unwrap()) as usize, out.len() - 4);
            assert_eq!(Message::decode(&out[4..]).unwrap(), m);
        }
        assert!(Message::decode(&[4, 0, 0]).is_err());
    }

    #[test]
    fn bitfields_ignore_spare_bits_and_count() {
        let b = Bitfield::from_bytes(&[0xff, 0xff], 10).unwrap();
        assert_eq!(b.count(), 10);
        assert!(b.all());
        assert!(Bitfield::from_bytes(&[0xff], 10).is_none());
        let mut b = Bitfield::new(10);
        b.set(9, true);
        b.set(9, true);
        assert_eq!((b.count(), b.bytes()), (1, &[0u8, 0x40][..]));
    }

    #[test]
    fn extension_payloads_round_trip() {
        let h = ExtHandshake {
            metadata: Some(1),
            pex: None,
            donthave: Some(3),
            metadata_size: Some(1234),
            listen_port: Some(6881),
            client: Some("Stream".into()),
            upload_only: false,
        };
        assert_eq!(ExtHandshake::decode(&h.encode()).unwrap(), h);
        let d = MetadataMessage::Data { piece: 2, total_size: 40000, data: vec![1, 2, 3] };
        assert_eq!(MetadataMessage::decode(&d.encode()).unwrap(), d);
        let peers: Vec<SocketAddr> = vec!["1.2.3.4:5".parse().unwrap(), "[2001:db8::1]:7".parse().unwrap()];
        assert_eq!(decode_pex(&encode_pex(&peers, &[])), peers);
    }
}
