//! Request scheduling against slow and silent peers on loopback: endgame for every piece, urgent pieces kept from
//! peers too slow for their deadline, and a peer that stalls on urgent requests dropped and dialled again.

use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use stream_core::bt::metainfo::{self, Version};
use stream_core::bt::session::{Session, SessionConfig};
use stream_core::bt::torrent::Torrent;
use stream_core::bt::wire::{self, BlockRef, Handshake, Message, Reserved};
use stream_core::cache::PieceCache;
use stream_core::engine::{Config, Engine, WOULD_BLOCK};
use stream_core::fixtures::{fill_content, first_mismatch, make_torrent, write_content, Seeder, SeederOptions, TempDir};
use stream_core::swarm::{Event, Source, DEFAULT_PRIORITY, DONT_DOWNLOAD};

const PIECE: i32 = 64 << 10;
const SEED: u32 = 77;

fn film(dir: &TempDir, size: i64) -> Vec<u8> {
    write_content(&dir.file("film.bin"), size, SEED, 0).unwrap();
    make_torrent(&dir.file("film.bin"), PIECE, Version::V1)
}

fn send(stream: &mut TcpStream, m: &Message) -> std::io::Result<()> {
    let mut out = Vec::new();
    m.encode(&mut out);
    stream.write_all(&out)
}

fn receive(stream: &mut TcpStream) -> std::io::Result<Message> {
    let mut len = [0u8; 4];
    stream.read_exact(&mut len)?;
    let mut body = vec![0u8; u32::from_be_bytes(len) as usize];
    stream.read_exact(&mut body)?;
    Message::decode(&body).map_err(|e| std::io::Error::new(std::io::ErrorKind::InvalidData, e.to_string()))
}

fn block(b: &BlockRef) -> Message {
    let mut data = vec![0u8; b.length as usize];
    fill_content(SEED, i64::from(b.piece) * i64::from(PIECE) + i64::from(b.offset), &mut data);
    Message::Piece { piece: b.piece, offset: b.offset, data }
}

/// A raw seed: it handshakes, has everything, unchokes, and answers each request as `answer(connection, request)`
/// says: `Some(delay)` sends the block after the delay, `None` never answers. Every request is recorded.
struct RawSeed {
    port: u16,
    requests: Arc<Mutex<Vec<(usize, BlockRef)>>>,
    /// Connections that completed a plaintext BitTorrent handshake.
    handshakes: Arc<AtomicUsize>,
}

impl RawSeed {
    fn start(hash: [u8; 20], answer: impl Fn(usize, &BlockRef) -> Option<Duration> + Send + Sync + 'static) -> RawSeed {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let requests: Arc<Mutex<Vec<(usize, BlockRef)>>> = Arc::default();
        let connections: Arc<AtomicUsize> = Arc::default();
        let handshakes: Arc<AtomicUsize> = Arc::default();
        let answer = Arc::new(answer);
        let (log, count, shaken) = (requests.clone(), connections.clone(), handshakes.clone());
        std::thread::spawn(move || {
            for stream in listener.incoming().flatten() {
                let n = count.fetch_add(1, Ordering::SeqCst) + 1;
                let (log, answer, shaken) = (log.clone(), answer.clone(), shaken.clone());
                std::thread::spawn(move || {
                    let mut stream = stream;
                    let mut hello = [0u8; 68];
                    // An encryption attempt is not a BitTorrent handshake; the client then reconnects in plaintext.
                    if stream.read_exact(&mut hello).is_err() || hello[0] != 19 {
                        return;
                    }
                    let mut reserved = Reserved([0; 8]);
                    reserved.0[7] |= 0x04;
                    let _ = stream.write_all(&Handshake { reserved, info_hash: hash, peer_id: wire::new_peer_id() }.encode());
                    let _ = send(&mut stream, &Message::HaveAll);
                    let _ = send(&mut stream, &Message::Unchoke);
                    shaken.fetch_add(1, Ordering::SeqCst);
                    while let Ok(m) = receive(&mut stream) {
                        if let Message::Request(b) = m {
                            log.lock().unwrap().push((n, b));
                            if let Some(delay) = answer(n, &b) {
                                std::thread::sleep(delay);
                                if send(&mut stream, &block(&b)).is_err() {
                                    return;
                                }
                            }
                        }
                    }
                });
            }
        });
        RawSeed { port, requests, handshakes }
    }

    fn address(&self) -> String {
        format!("127.0.0.1:{}", self.port)
    }
}

/// A session downloading one torrent into its cache, marking finished pieces verified as the engine does.
struct Client {
    session: Session,
    torrent: Arc<Torrent>,
    cache: Arc<PieceCache>,
}

impl Client {
    fn new(dir: &TempDir, torrent: &[u8], peers: Vec<String>) -> Client {
        let session = Session::new(
            SessionConfig { listen: "127.0.0.1:0".into(), multiple_per_ip: true, pex: false, ..SessionConfig::default() },
            None,
        )
        .unwrap();
        let t = metainfo::parse_torrent(torrent).unwrap();
        let peers = peers.iter().map(|p| p.parse().unwrap()).collect();
        let torrent = session.add(1, t.info.hashes, Some(t.info), t.layers, Vec::new(), peers);
        std::fs::create_dir_all(dir.file("cache")).unwrap();
        torrent.use_cache(&dir.file("cache").to_string_lossy(), 64 << 20);
        let cache = torrent.cache().unwrap();
        Client { session, torrent, cache }
    }

    fn want(&self, pieces: impl IntoIterator<Item = i32>) {
        let mut prio = vec![DONT_DOWNLOAD; self.cache.num_pieces() as usize];
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

    fn has(&self, pieces: impl IntoIterator<Item = i32>) -> bool {
        pieces.into_iter().all(|p| self.torrent.have_piece(p))
    }
}

fn hash_of(torrent: &[u8]) -> [u8; 20] {
    metainfo::parse_torrent(torrent).unwrap().info.hashes.v1.unwrap()
}

#[test]
fn endgame_finishes_without_waiting_for_a_silent_peer() {
    // No deadlines: before endgame covered every piece, blocks asked of the silent peer waited for the 30 s timeout.
    let dir = TempDir::new("stream-endgame");
    let torrent = film(&dir, 2 << 20);
    let silent = RawSeed::start(hash_of(&torrent), |_, _| None);
    let seed = Seeder::single(&torrent, dir.path(), &SeederOptions::default());
    let c = Client::new(&dir, &torrent, vec![silent.address(), seed.address()]);
    let deadline = Instant::now() + Duration::from_secs(10);
    while silent.handshakes.load(Ordering::SeqCst) == 0 {
        assert!(Instant::now() < deadline, "the silent peer never connected");
        std::thread::sleep(Duration::from_millis(10));
    }
    std::thread::sleep(Duration::from_millis(300));
    let started = Instant::now();
    c.want(0..32);
    assert!(c.pump(Duration::from_secs(25), || c.has(0..32)), "the download stalled");
    assert!(started.elapsed() < Duration::from_secs(12), "took {:?}; the request timeout is 30 s", started.elapsed());
    assert!(!silent.requests.lock().unwrap().is_empty(), "the silent peer was asked for blocks, so endgame was needed");
}

#[test]
fn urgent_pieces_go_to_the_peer_that_delivers_them_soonest() {
    // Small urgent bursts that the fast peer has room for. Peers are refilled in no particular order, so in some
    // bursts the slow peer is offered the urgent blocks first; it must leave them to the fast peer, which delivers
    // them sooner. (With the fast peer's queue full, an idle slow peer may rightly take one.)
    let dir = TempDir::new("stream-deadline");
    let torrent = film(&dir, 8 << 20);
    // 300 ms a block: about 53 KiB/s.
    let slow = RawSeed::start(hash_of(&torrent), |_, _| Some(Duration::from_millis(300)));
    let seed = Seeder::single(&torrent, dir.path(), &SeederOptions { upload_rate: 8 << 20, ..SeederOptions::default() });
    let c = Client::new(&dir, &torrent, vec![slow.address(), seed.address()]);
    // Warm up both peers' rates.
    let mut wanted: Vec<i32> = (0..16).collect();
    c.want(wanted.iter().copied());
    assert!(c.pump(Duration::from_secs(20), || c.has(0..16)));
    assert!(slow.requests.lock().unwrap().len() >= 2, "the slow peer took part in the warm-up");
    let mut urgent_to_slow = 0;
    for round in 0..8 {
        // Let the slow peer's queue drain, so it has room when the burst arrives.
        let idle = Instant::now() + Duration::from_secs(3);
        c.pump(Duration::from_secs(4), || Instant::now() >= idle);
        // A peer we have no use for chokes us, rightly, and only unchokes after we are interested again; meanwhile
        // the slow peer is the only one that can deliver. Ask for one ordinary piece first and wait until both
        // peers unchoke us, so the burst finds the fast peer ready.
        let bait = 100 + round;
        wanted.push(bait);
        c.want(wanted.iter().copied());
        assert!(c.pump(Duration::from_secs(15), || c.torrent.lock().peers.values().all(|p| !p.peer_choking)), "peers unchoke us");
        let before = slow.requests.lock().unwrap().len();
        let burst: Vec<i32> = (40 + round * 4..44 + round * 4).collect();
        wanted.extend(&burst);
        // As the engine does: deadlines and priorities together.
        let deadlines: Vec<(i32, i32)> = burst.iter().map(|&p| (p, 2000)).collect();
        let mut prio = vec![DONT_DOWNLOAD; c.cache.num_pieces() as usize];
        for &p in &wanted {
            assert!(c.cache.reserve(p));
            prio[p as usize] = DEFAULT_PRIORITY;
        }
        c.torrent.update_window(Some(&prio), Some(&deadlines));
        assert!(c.pump(Duration::from_secs(10), || c.has(burst.iter().copied())), "burst {round} late");
        urgent_to_slow += slow.requests.lock().unwrap()[before..].iter().filter(|(_, b)| burst.contains(&(b.piece as i32))).count();
    }
    assert_eq!(urgent_to_slow, 0, "urgent blocks went to the slow peer while the fast one had room");
}

#[test]
fn a_peer_stalled_on_urgent_requests_is_dropped_and_dialled_again() {
    // One connection answers ten blocks, then goes silent without closing; later connections answer everything.
    // Before, the urgent requests were re-sent on the silent connection forever.
    let dir = TempDir::new("stream-stall");
    let size = 1 << 20;
    let torrent = film(&dir, size);
    // The connection that carries the first requests (an earlier one may be an encryption attempt this peer can't
    // answer) stalls after ten blocks.
    let first = Arc::new(AtomicUsize::new(0));
    let answered = Arc::new(AtomicUsize::new(0));
    let (f, a) = (first.clone(), answered.clone());
    let seed = RawSeed::start(hash_of(&torrent), move |connection, _| {
        let _ = f.compare_exchange(0, connection, Ordering::SeqCst, Ordering::SeqCst);
        if connection == f.load(Ordering::SeqCst) && a.fetch_add(1, Ordering::SeqCst) >= 10 {
            None
        } else {
            Some(Duration::ZERO)
        }
    });
    let e = Engine::new(Config {
        cache_dir: dir.file("cache").to_string_lossy().into_owned(),
        cache_bytes: 2 << 20,
        reserve_bytes: 0,
        listen_interfaces: "127.0.0.1:0".into(),
        dht: false,
        lsd: false,
        pex: false,
        utp: false,
        multiple_connections_per_ip: true,
        ..Config::default()
    })
    .unwrap();
    let t = e.add(&Source { metainfo: torrent, peers: vec![seed.address()], ..Source::default() }).unwrap();
    e.wait_metadata(t, Duration::from_secs(5), None).unwrap();
    e.select_file(t, 0).unwrap();
    let r = e.open_reader(t, 0).unwrap();
    let started = Instant::now();
    let mut buf = vec![0u8; 64 << 10];
    let mut pos = 0i64;
    while pos < size {
        let n = e.read(r, pos, &mut buf, Duration::from_millis(100)).unwrap();
        if n == WOULD_BLOCK {
            assert!(started.elapsed() < Duration::from_secs(45), "stalled at {pos} after {:?}", started.elapsed());
            continue;
        }
        assert!(first_mismatch(SEED, pos, &buf[..n as usize]).is_none());
        pos += i64::from(n);
    }
    let stalled = first.load(Ordering::SeqCst);
    assert!(seed.requests.lock().unwrap().iter().any(|(c, _)| *c > stalled), "pieces came on a new connection after the stall");
    assert!(started.elapsed() >= Duration::from_secs(15), "the stall was exercised ({:?})", started.elapsed());
}
