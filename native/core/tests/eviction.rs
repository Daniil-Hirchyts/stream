//! Eviction in the swarm, on sessions using the bounded cache: a finished torrent forgets pieces and downloads them
//! again, other peers learn about it by lt_donthave or by being disconnected, and uploads of evicted pieces are
//! rejected.

use std::io::{Read, Write};
use std::net::TcpStream;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use stream_core::bt::metainfo::{self, Version};
use stream_core::bt::session::{Session, SessionConfig};
use stream_core::bt::torrent::Torrent;
use stream_core::bt::wire::{self, BlockRef, ExtHandshake, Handshake, Message, Reserved};
use stream_core::cache::PieceCache;
use stream_core::fixtures::{first_mismatch, make_torrent, write_content, Seeder, SeederOptions, TempDir};
use stream_core::swarm::{Event, DEFAULT_PRIORITY, DONT_DOWNLOAD};

const PIECE: i32 = 64 << 10;

/// A streaming client: a session, one torrent and its cache, with every finished piece marked verified as the
/// engine does.
struct Client {
    session: Session,
    torrent: Arc<Torrent>,
    cache: Arc<PieceCache>,
}

impl Client {
    fn new(dir: &TempDir, torrent: &[u8], peers: Vec<std::net::SocketAddr>) -> Client {
        let session = Session::new(
            SessionConfig { listen: "127.0.0.1:0".into(), multiple_per_ip: true, pex: false, ..SessionConfig::default() },
            None,
        )
        .unwrap();
        let t = metainfo::parse_torrent(torrent).unwrap();
        let torrent = session.add(1, t.info.hashes, Some(t.info), t.layers, Vec::new(), peers);
        std::fs::create_dir_all(dir.file("cache")).unwrap();
        torrent.use_cache(&dir.file("cache").to_string_lossy(), 64 << 20);
        let cache = torrent.cache().unwrap();
        Client { session, torrent, cache }
    }

    fn want(&self, pieces: impl IntoIterator<Item = i32>) {
        let n = self.cache.num_pieces() as usize;
        let mut prio = vec![DONT_DOWNLOAD; n];
        for p in pieces {
            assert!(self.cache.reserve(p));
            prio[p as usize] = DEFAULT_PRIORITY;
        }
        self.torrent.prioritize(&prio);
    }

    fn pump(&self, timeout: Duration, mut done: impl FnMut() -> bool) -> bool {
        let deadline = Instant::now() + timeout;
        let mut events = Vec::new();
        loop {
            self.session.shared.drain(&mut events);
            for e in events.drain(..) {
                if let Event::PieceFinished { piece, .. } = e {
                    self.cache.set_verified(piece);
                }
            }
            if done() {
                return true;
            }
            if Instant::now() > deadline {
                return false;
            }
            std::thread::sleep(Duration::from_millis(10));
        }
    }

    fn evict(&self, pieces: Vec<i32>) -> Vec<i32> {
        self.cache.begin_release(&pieces);
        let result = Arc::new(Mutex::new(None));
        let r = result.clone();
        self.torrent.evict(pieces, Box::new(move |evicted| *r.lock().unwrap() = Some(evicted)));
        let evicted = result.lock().unwrap().take().expect("eviction finished");
        evicted
    }

    fn piece_matches(&self, p: i32, seed: u32) -> bool {
        let mut buf = vec![0u8; PIECE as usize];
        self.cache.read_verified(p, 0, &mut buf).unwrap_or(false) && first_mismatch(seed, i64::from(p) * i64::from(PIECE), &buf).is_none()
    }

    fn has_all(&self) -> bool {
        (0..self.cache.num_pieces()).all(|p| self.torrent.have_piece(p))
    }
}

fn seed_evicts_and_downloads_again(v: Version) {
    let dir = TempDir::new("stream-evict");
    write_content(&dir.file("movie.bin"), 2 << 20, 21, 0).unwrap();
    let torrent = make_torrent(&dir.file("movie.bin"), PIECE, v);
    let seed = Seeder::single(&torrent, dir.path(), &SeederOptions::default());
    let c = Client::new(&dir, &torrent, vec![seed.address().parse().unwrap()]);
    c.want(0..32);
    assert!(c.pump(Duration::from_secs(20), || c.has_all() && c.cache.verified(3) && c.cache.verified(10)));
    let downloaded = c.torrent.status().total_download;

    assert_eq!(c.evict(vec![3, 10, 9999]), vec![3, 10]);
    assert!(!c.torrent.have_piece(3));
    assert!(c.torrent.have_piece(4));
    assert!(!c.cache.verified(3));

    // Wanted again, the pieces come back and pass their hashes again.
    c.want([3, 10]);
    assert!(c.pump(Duration::from_secs(20), || c.has_all() && c.cache.verified(3) && c.cache.verified(10)));
    assert!(c.piece_matches(3, 21));
    assert!(c.piece_matches(10, 21));
    assert!(c.torrent.status().total_download >= downloaded + 2 * i64::from(PIECE));
}

#[test]
fn v1_seed_downloads_evicted_pieces_again() {
    seed_evicts_and_downloads_again(Version::V1);
}

#[test]
fn v2_seed_downloads_evicted_pieces_again() {
    seed_evicts_and_downloads_again(Version::V2);
}

#[test]
fn hybrid_seed_downloads_evicted_pieces_again() {
    seed_evicts_and_downloads_again(Version::Hybrid);
}

#[test]
fn eviction_of_unwanted_pieces_keeps_a_finished_torrent_finished() {
    let dir = TempDir::new("stream-evict-unwanted");
    write_content(&dir.file("movie.bin"), 2 << 20, 22, 0).unwrap();
    let torrent = make_torrent(&dir.file("movie.bin"), PIECE, Version::V1);
    let seed = Seeder::single(&torrent, dir.path(), &SeederOptions::default());
    let c = Client::new(&dir, &torrent, vec![seed.address().parse().unwrap()]);
    c.want(0..8);
    assert!(c.pump(Duration::from_secs(20), || (0..8).all(|p| c.cache.verified(p))));
    let mut prio = vec![DONT_DOWNLOAD; 32];
    for p in [0, 1, 3, 4, 6, 7] {
        prio[p] = DEFAULT_PRIORITY;
    }
    c.torrent.prioritize(&prio);
    assert_eq!(c.evict(vec![2, 5]).len(), 2);
    let downloaded = c.torrent.status().total_download;
    c.pump(Duration::from_millis(500), || false);
    assert!(!c.torrent.have_piece(2));
    assert_eq!(c.torrent.status().total_download, downloaded, "nothing unwanted comes back");

    // Wanting a piece again resumes downloading it.
    assert!(c.cache.reserve(2));
    prio[2] = DEFAULT_PRIORITY;
    c.torrent.prioritize(&prio);
    assert!(c.pump(Duration::from_secs(20), || c.torrent.have_piece(2) && c.cache.verified(2)));
    assert!(c.piece_matches(2, 22));
    assert!(!c.torrent.have_piece(5));
}

/// Just enough of the wire protocol to act as a peer the client didn't expect.
struct RawPeer {
    stream: TcpStream,
    inbox: Vec<u8>,
    closed: bool,
}

const THEIR_DONTHAVE: u8 = 7;

impl RawPeer {
    fn connect(port: u16, info_hash: [u8; 20], donthave: bool, seed: bool) -> RawPeer {
        let mut stream = TcpStream::connect(("127.0.0.1", port)).unwrap();
        stream.set_read_timeout(Some(Duration::from_millis(100))).unwrap();
        let mut reserved = Reserved([0; 8]);
        reserved.0[7] |= 0x04;
        if donthave {
            reserved.0[5] |= 0x10;
        }
        let peer_id = wire::new_peer_id();
        stream.write_all(&Handshake { reserved, info_hash, peer_id }.encode()).unwrap();
        let mut p = RawPeer { stream, inbox: Vec::new(), closed: false };
        if donthave {
            let ext = ExtHandshake { donthave: Some(THEIR_DONTHAVE), ..ExtHandshake::default() };
            p.send(Message::Extended { id: 0, payload: ext.encode() });
        }
        p.send(if seed { Message::HaveAll } else { Message::HaveNone });
        p
    }

    fn send(&mut self, m: Message) {
        let mut out = Vec::new();
        m.encode(&mut out);
        let _ = self.stream.write_all(&out);
    }

    fn messages(&self) -> Vec<Message> {
        let mut out = Vec::new();
        let mut pos = 68;
        while pos + 4 <= self.inbox.len() {
            let len = u32::from_be_bytes(self.inbox[pos..pos + 4].try_into().unwrap()) as usize;
            if pos + 4 + len > self.inbox.len() {
                break;
            }
            if len > 0 {
                out.push(Message::decode(&self.inbox[pos + 4..pos + 4 + len]).unwrap());
            }
            pos += 4 + len;
        }
        out
    }

    fn receive_until(&mut self, timeout: Duration, done: impl Fn(&[Message]) -> bool) -> bool {
        let deadline = Instant::now() + timeout;
        let mut buf = vec![0u8; 1 << 16];
        while !done(&self.messages()) && !self.closed && Instant::now() < deadline {
            match self.stream.read(&mut buf) {
                Ok(0) => self.closed = true,
                Ok(n) => self.inbox.extend_from_slice(&buf[..n]),
                Err(e) if matches!(e.kind(), std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut) => {}
                Err(_) => self.closed = true,
            }
        }
        done(&self.messages())
    }
}

fn finished_client(dir: &TempDir, seed_value: u32) -> (Client, Seeder, [u8; 20]) {
    write_content(&dir.file("movie.bin"), 2 << 20, seed_value, 0).unwrap();
    let torrent = make_torrent(&dir.file("movie.bin"), PIECE, Version::V1);
    let seed = Seeder::single(&torrent, dir.path(), &SeederOptions::default());
    let c = Client::new(dir, &torrent, vec![seed.address().parse().unwrap()]);
    c.want(0..32);
    assert!(c.pump(Duration::from_secs(20), || c.has_all()));
    let hash = metainfo::parse_torrent(&torrent).unwrap().info.hashes.v1.unwrap();
    (c, seed, hash)
}

#[test]
fn eviction_is_announced_to_peers_supporting_lt_donthave_and_their_requests_rejected() {
    let dir = TempDir::new("stream-evict-donthave");
    let (c, _seed, hash) = finished_client(&dir, 23);
    let mut leecher = RawPeer::connect(c.session.port(), hash, true, false);
    leecher.send(Message::Interested);
    assert!(leecher.receive_until(Duration::from_secs(10), |m| m.contains(&Message::Unchoke)));
    assert!(leecher.messages().iter().any(|m| matches!(m, Message::Bitfield(_) | Message::HaveAll)));

    assert_eq!(c.evict(vec![7]), vec![7]);
    let donthave = Message::Extended { id: THEIR_DONTHAVE, payload: 7u32.to_be_bytes().to_vec() };
    assert!(leecher.receive_until(Duration::from_secs(10), |m| m.contains(&donthave)));
    // An evicted piece is refused; others are still served.
    let evicted = BlockRef { piece: 7, offset: 0, length: 16384 };
    let kept = BlockRef { piece: 8, offset: 0, length: 16384 };
    leecher.send(Message::Request(evicted));
    leecher.send(Message::Request(kept));
    assert!(leecher.receive_until(Duration::from_secs(10), |m| {
        m.contains(&Message::Reject(evicted)) && m.iter().any(|x| matches!(x, Message::Piece { piece: 8, .. }))
    }));
    assert!(!leecher.closed, "a peer that understands lt_donthave stays connected");
}

#[test]
fn eviction_disconnects_downloading_peers_without_lt_donthave_but_not_seeds() {
    let dir = TempDir::new("stream-evict-disconnect");
    let (c, _seed, hash) = finished_client(&dir, 24);
    let mut leecher = RawPeer::connect(c.session.port(), hash, false, false);
    leecher.send(Message::Interested);
    let mut seed_peer = RawPeer::connect(c.session.port(), hash, false, true);
    assert!(leecher.receive_until(Duration::from_secs(10), |m| m.contains(&Message::Unchoke)));
    assert!(seed_peer.receive_until(Duration::from_secs(10), |m| !m.is_empty()));

    assert_eq!(c.evict(vec![5]), vec![5]);
    leecher.receive_until(Duration::from_secs(5), |_| false);
    assert!(leecher.closed, "a downloading peer that can't learn of the eviction is disconnected");
    seed_peer.receive_until(Duration::from_millis(500), |_| false);
    assert!(!seed_peer.closed, "a seed needs nothing from us and stays");
}
