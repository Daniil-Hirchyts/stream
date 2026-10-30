//! Super-seeding (BEP 16) on loopback: a full seed shows each peer one rare piece at a time, serves only what it
//! showed, shows another once a piece has spread, and two downloaders finish from it and each other while it
//! uploads little more than one copy.

use std::io::{Read, Write};
use std::net::TcpStream;
use std::sync::Arc;
use std::time::{Duration, Instant};
use stream_core::bt::metainfo::{self, Version};
use stream_core::bt::mse::Policy;
use stream_core::bt::session::{Session, SessionConfig};
use stream_core::bt::torrent::Torrent;
use stream_core::bt::wire::{self, BlockRef, Handshake, Message, Reserved};
use stream_core::cache::PieceCache;
use stream_core::fixtures::{make_torrent, write_content, Seeder, SeederOptions, TempDir};
use stream_core::swarm::{Event, DEFAULT_PRIORITY};

const PIECE: i32 = 64 << 10;

fn film(dir: &TempDir, size: i64) -> Vec<u8> {
    write_content(&dir.file("film.bin"), size, 5, 0).unwrap();
    make_torrent(&dir.file("film.bin"), PIECE, Version::V1)
}

fn super_seed(dir: &TempDir, torrent: &[u8]) -> Seeder {
    Seeder::single(torrent, dir.path(), &SeederOptions { super_seed: true, encryption: Policy::Disabled, ..SeederOptions::default() })
}

/// A raw peer that speaks the fast extension and reads the seed's messages.
struct Raw {
    stream: TcpStream,
}

impl Raw {
    fn connect(address: &str, torrent: &[u8]) -> Raw {
        let hash = metainfo::parse_torrent(torrent).unwrap().info.hashes.v1.unwrap();
        let mut stream = TcpStream::connect(address).unwrap();
        stream.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
        let mut reserved = Reserved([0; 8]);
        reserved.0[7] |= 0x04;
        stream.write_all(&Handshake { reserved, info_hash: hash, peer_id: wire::new_peer_id() }.encode()).unwrap();
        let mut answer = [0u8; 68];
        stream.read_exact(&mut answer).unwrap();
        Raw { stream }
    }

    fn send(&mut self, m: &Message) {
        let mut out = Vec::new();
        m.encode(&mut out);
        self.stream.write_all(&out).unwrap();
    }

    fn receive(&mut self) -> Message {
        let mut len = [0u8; 4];
        self.stream.read_exact(&mut len).unwrap();
        let mut body = vec![0u8; u32::from_be_bytes(len) as usize];
        self.stream.read_exact(&mut body).unwrap();
        Message::decode(&body).unwrap()
    }

    /// The next message other than keep-alives, unchokes and the extension handshake.
    fn next(&mut self) -> Message {
        loop {
            match self.receive() {
                Message::KeepAlive | Message::Unchoke | Message::Extended { .. } => continue,
                m => return m,
            }
        }
    }

    fn shown(&mut self) -> u32 {
        match self.next() {
            Message::Have(p) => p,
            m => panic!("expected a piece shown, got {m:?}"),
        }
    }

    /// Becomes interested and waits to be unchoked.
    fn unchoked(&mut self) {
        self.send(&Message::Interested);
        loop {
            if self.receive() == Message::Unchoke {
                return;
            }
        }
    }
}

#[test]
fn a_super_seed_shows_one_piece_and_serves_only_that() {
    let dir = TempDir::new("stream-super-one");
    let torrent = film(&dir, 1 << 20);
    let seed = super_seed(&dir, &torrent);
    let mut a = Raw::connect(&seed.address(), &torrent);
    assert_eq!(a.next(), Message::HaveNone, "a super-seed claims nothing at first");
    let p = a.shown();
    a.unchoked();
    let other = (p + 1) % 16;
    let refused = BlockRef { piece: other, offset: 0, length: 16384 };
    a.send(&Message::Request(refused));
    assert_eq!(a.next(), Message::Reject(refused), "a piece not shown is not served");
    let wanted = BlockRef { piece: p, offset: 0, length: 16384 };
    a.send(&Message::Request(wanted));
    assert!(matches!(a.next(), Message::Piece { piece, offset: 0, .. } if piece == p));
}

#[test]
fn peers_are_shown_different_pieces_and_a_spread_piece_brings_another() {
    let dir = TempDir::new("stream-super-spread");
    let torrent = film(&dir, 1 << 20);
    let seed = super_seed(&dir, &torrent);
    let mut a = Raw::connect(&seed.address(), &torrent);
    assert_eq!(a.next(), Message::HaveNone);
    let pa = a.shown();
    let mut b = Raw::connect(&seed.address(), &torrent);
    assert_eq!(b.next(), Message::HaveNone);
    let pb = b.shown();
    assert_ne!(pa, pb, "the second peer gets the rarest piece, not the first peer's");
    // B now has A's piece, as if A shared it: it has spread, so A is shown another.
    b.send(&Message::Have(pa));
    let next = a.shown();
    assert!(next != pa && next != pb, "a new rare piece: {next}");
}

/// A downloading session, marking finished pieces verified as the engine does.
struct Client {
    session: Session,
    torrent: Arc<Torrent>,
    cache: Arc<PieceCache>,
}

impl Client {
    fn new(dir: &TempDir, name: &str, torrent: &[u8], peers: Vec<String>) -> Client {
        let session = Session::new(
            SessionConfig {
                listen: "127.0.0.1:0".into(),
                multiple_per_ip: true,
                pex: false,
                encryption: Policy::Disabled,
                ..SessionConfig::default()
            },
            None,
        )
        .unwrap();
        let t = metainfo::parse_torrent(torrent).unwrap();
        let peers = peers.iter().map(|p| p.parse().unwrap()).collect();
        let torrent = session.add(1, t.info.hashes, Some(t.info), t.layers, Vec::new(), peers);
        std::fs::create_dir_all(dir.file(name)).unwrap();
        torrent.use_cache(&dir.file(name).to_string_lossy(), 64 << 20);
        let cache = torrent.cache().unwrap();
        let prio = vec![DEFAULT_PRIORITY; cache.num_pieces() as usize];
        for p in 0..cache.num_pieces() {
            assert!(cache.reserve(p));
        }
        torrent.prioritize(&prio);
        Client { session, torrent, cache }
    }

    fn pump(&self) {
        let mut events = Vec::new();
        self.session.shared.drain(&mut events);
        for e in events {
            if let Event::PieceFinished { piece, .. } = e {
                self.cache.set_verified(piece);
            }
        }
    }

    fn done(&self) -> bool {
        (0..self.cache.num_pieces()).all(|p| self.torrent.have_piece(p))
    }
}

#[test]
fn two_downloaders_finish_from_a_super_seed_that_uploads_about_one_copy() {
    let dir = TempDir::new("stream-super-swarm");
    let size: i64 = 2 << 20;
    let torrent = film(&dir, size);
    let seed = super_seed(&dir, &torrent);
    let a = Client::new(&dir, "a", &torrent, vec![seed.address()]);
    let a_address = format!("127.0.0.1:{}", a.session.port());
    let b = Client::new(&dir, "b", &torrent, vec![seed.address(), a_address]);
    let deadline = Instant::now() + Duration::from_secs(60);
    while !(a.done() && b.done()) {
        assert!(Instant::now() < deadline, "the swarm didn't finish");
        a.pump();
        b.pump();
        std::thread::sleep(Duration::from_millis(10));
    }
    let uploaded = seed.torrents[0].lock().total_up;
    // A plain seed would send most of the file to each; a super-seed sends each piece about once.
    assert!(uploaded < size * 3 / 2, "the seed uploaded {uploaded} bytes for a {size}-byte file");
}
