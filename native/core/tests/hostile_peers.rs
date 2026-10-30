//! Peers that break the protocol's expectations, scripted over raw sockets on loopback: requests dropped silently,
//! data that doesn't hash, sockets that are never read. None of them may stall a stream for long or grow memory.

use std::collections::VecDeque;
use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use stream_core::bt::metainfo::{self, Version};
use stream_core::bt::mse::Policy;
use stream_core::bt::wire::{self, BlockRef, ExtHandshake, Handshake, Message, MetadataMessage, Reserved};
use stream_core::engine::{Config, Engine, WOULD_BLOCK};
use stream_core::fixtures::{fill_content, first_mismatch, make_torrent, write_content, Seeder, SeederOptions, TempDir};
use stream_core::swarm::Source;

const THEIR_METADATA: u8 = 9;
const PIECE: i32 = 64 << 10;
const SEED: u32 = 91;

fn config(dir: &std::path::Path) -> Config {
    Config {
        cache_dir: dir.to_string_lossy().into_owned(),
        cache_bytes: 2 << 20,
        reserve_bytes: 0,
        listen_interfaces: "127.0.0.1:0".into(),
        dht: false,
        lsd: false,
        pex: false,
        utp: false,
        encryption: Policy::Disabled,
        multiple_connections_per_ip: true,
        log: std::env::var_os("STREAM_TEST_LOG").map(|_| Arc::new(|l: &str| println!("LOG {l}")) as _),
        ..Config::default()
    }
}

fn film(dir: &TempDir, size: i64, piece: i32) -> Vec<u8> {
    write_content(&dir.file("film.bin"), size, SEED, 0).unwrap();
    make_torrent(&dir.file("film.bin"), piece, Version::V1)
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
    Message::decode(&body)
}

/// A piece of the film's content, as a seed would send it.
fn block(b: &BlockRef) -> Message {
    let mut data = vec![0u8; b.length as usize];
    fill_content(SEED, i64::from(b.piece) * i64::from(PIECE) + i64::from(b.offset), &mut data);
    Message::Piece { piece: b.piece, offset: b.offset, data }
}

/// A listening peer run by a script: it answers the handshake for `hash`, says it has everything, unchokes, then calls
/// `script` with each message. Every message received is kept in `log`.
struct Scripted {
    port: u16,
    log: Arc<Mutex<Vec<Message>>>,
    connections: Arc<AtomicUsize>,
    drop_connections: Arc<AtomicBool>,
}

impl Scripted {
    fn start(
        hash: [u8; 20],
        metadata_size: Option<usize>,
        script: impl FnMut(&Message, &mut TcpStream) + Send + 'static,
    ) -> Scripted {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let log: Arc<Mutex<Vec<Message>>> = Arc::default();
        let connections: Arc<AtomicUsize> = Arc::default();
        let drop_connections: Arc<AtomicBool> = Arc::default();
        let script = Arc::new(Mutex::new(script));
        let (peer_log, count, dropping) = (log.clone(), connections.clone(), drop_connections.clone());
        std::thread::spawn(move || {
            for stream in listener.incoming().flatten() {
                count.fetch_add(1, Ordering::SeqCst);
                let (log, script, dropping) = (peer_log.clone(), script.clone(), dropping.clone());
                std::thread::spawn(move || {
                    let mut stream = stream;
                    let mut hello = [0u8; 68];
                    if stream.read_exact(&mut hello).is_err() {
                        return;
                    }
                    let mut reserved = Reserved([0; 8]);
                    reserved.0[5] |= 0x10;
                    reserved.0[7] |= 0x04;
                    let peer_id = wire::new_peer_id();
                    let _ = stream.write_all(&Handshake { reserved, info_hash: hash, peer_id }.encode());
                    let ext = ExtHandshake { metadata: Some(THEIR_METADATA), metadata_size, ..ExtHandshake::default() };
                    let _ = send(&mut stream, &Message::Extended { id: 0, payload: ext.encode() });
                    if metadata_size.is_none() {
                        let _ = send(&mut stream, &Message::HaveAll);
                    }
                    let _ = send(&mut stream, &Message::Unchoke);
                    stream.set_read_timeout(Some(Duration::from_millis(50))).unwrap();
                    let mut pending = VecDeque::new();
                    loop {
                        if dropping.load(Ordering::SeqCst) {
                            return;
                        }
                        match receive(&mut stream) {
                            Ok(m) => pending.push_back(m),
                            // Scripts also run when nothing arrives, with a message that means nothing.
                            Err(e) if matches!(e.kind(), std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut) => {
                                (script.lock().unwrap())(&Message::KeepAlive, &mut stream);
                            }
                            Err(_) => return,
                        }
                        while let Some(m) = pending.pop_front() {
                            log.lock().unwrap().push(m.clone());
                            (script.lock().unwrap())(&m, &mut stream);
                        }
                    }
                });
            }
        });
        Scripted { port, log, connections, drop_connections }
    }

    fn address(&self) -> String {
        format!("127.0.0.1:{}", self.port)
    }

    fn saw(&self, f: impl Fn(&Message) -> bool) -> bool {
        self.log.lock().unwrap().iter().any(f)
    }

    fn count(&self, f: impl Fn(&Message) -> bool) -> usize {
        self.log.lock().unwrap().iter().filter(|m| f(m)).count()
    }
}

fn wait_for(timeout: Duration, what: &str, mut done: impl FnMut() -> bool) {
    let deadline = Instant::now() + timeout;
    while !done() {
        assert!(Instant::now() < deadline, "timed out waiting for {what}");
        std::thread::sleep(Duration::from_millis(10));
    }
}

fn hash_of(torrent: &[u8]) -> [u8; 20] {
    metainfo::parse_torrent(torrent).unwrap().info.hashes.v1.unwrap()
}

fn read_range(e: &Engine, reader: u64, from: i64, to: i64, limit: Duration) {
    let mut buf = vec![0u8; 64 << 10];
    let mut pos = from;
    let deadline = Instant::now() + limit;
    while pos < to {
        let want = (buf.len() as i64).min(to - pos) as usize;
        let n = e.read(reader, pos, &mut buf[..want], Duration::from_millis(100)).unwrap();
        if n == WOULD_BLOCK {
            assert!(Instant::now() < deadline, "stalled at {pos}");
            continue;
        }
        assert!(first_mismatch(SEED, pos, &buf[..n as usize]).is_none(), "wrong byte near {pos}");
        pos += i64::from(n);
    }
}

#[test]
fn a_request_a_lone_seed_dropped_is_asked_again_long_before_the_timeout() {
    let dir = TempDir::new("stream-lost-request");
    let size = 1 << 20;
    let torrent = film(&dir, size, PIECE);
    let lost = BlockRef { piece: 1, offset: 16384, length: 16384 };
    let mut dropped = false;
    let seed = Scripted::start(hash_of(&torrent), None, move |m, stream| {
        if let Message::Request(b) = m {
            if *b == lost && !dropped {
                dropped = true;
                return;
            }
            let _ = send(stream, &block(b));
        }
    });
    let e = Engine::new(config(&dir.file("cache"))).unwrap();
    let t = e.add(&Source { metainfo: torrent, peers: vec![seed.address()], ..Source::default() }).unwrap();
    e.wait_metadata(t, Duration::from_secs(5), None).unwrap();
    e.select_file(t, 0).unwrap();
    let r = e.open_reader(t, 0).unwrap();
    let started = Instant::now();
    read_range(&e, r, 0, size, Duration::from_secs(25));
    assert!(started.elapsed() < Duration::from_secs(12), "took {:?}; the request timeout is 30 s", started.elapsed());
    assert!(seed.count(|m| *m == Message::Request(lost)) >= 2);
}

#[test]
fn requests_for_pieces_that_left_the_window_are_cancelled() {
    let dir = TempDir::new("stream-cancel");
    let size = 40 * i64::from(PIECE);
    let torrent = film(&dir, size, PIECE);
    // Only each piece's first block ever arrives: the rest stay in flight, as on a slow peer.
    let seed = Scripted::start(hash_of(&torrent), None, |m, stream| {
        if let Message::Request(b) = m {
            if b.offset == 0 {
                let _ = send(stream, &block(b));
            }
        }
    });
    let e = Engine::new(config(&dir.file("cache"))).unwrap();
    let t = e.add(&Source { metainfo: torrent, peers: vec![seed.address()], ..Source::default() }).unwrap();
    e.wait_metadata(t, Duration::from_secs(5), None).unwrap();
    e.select_file(t, 0).unwrap();
    let r = e.open_reader(t, 0).unwrap();
    assert_eq!(e.read(r, i64::from(PIECE) * 2, &mut [0u8; 1], Duration::from_millis(100)).unwrap(), WOULD_BLOCK);
    let inflight = BlockRef { piece: 2, offset: 16384, length: 16384 };
    wait_for(Duration::from_secs(5), "requests at the first position", || seed.saw(|m| *m == Message::Request(inflight)));
    assert!(!seed.saw(|m| matches!(m, Message::Cancel(_))), "nothing has left the window yet");

    let target = 30 * i64::from(PIECE);
    assert_eq!(e.read(r, target, &mut [0u8; 1], Duration::from_millis(100)).unwrap(), WOULD_BLOCK);
    wait_for(Duration::from_secs(5), "the old requests to be cancelled", || seed.saw(|m| *m == Message::Cancel(inflight)));
    wait_for(Duration::from_secs(5), "requests at the new position", || {
        seed.saw(|m| matches!(m, Message::Request(b) if b.piece == 30))
    });
    // Nothing still asked for at the new position was cancelled.
    assert!(!seed.saw(|m| matches!(m, Message::Cancel(b) if b.piece == 30)));
}

/// A metadata script: garbage from `liar`-style peers, the real thing from honest ones.
fn metadata_reply(raw: &[u8], piece: u32, honest: bool) -> Message {
    let start = piece as usize * wire::METADATA_PIECE;
    if start >= raw.len() {
        return Message::Extended { id: wire::EXT_METADATA, payload: MetadataMessage::Reject(piece).encode() };
    }
    let end = (start + wire::METADATA_PIECE).min(raw.len());
    let data = if honest { raw[start..end].to_vec() } else { vec![0x5a; end - start] };
    let reply = MetadataMessage::Data { piece, total_size: raw.len(), data };
    Message::Extended { id: wire::EXT_METADATA, payload: reply.encode() }
}

fn metadata_request(m: &Message) -> Option<u32> {
    let Message::Extended { id, payload } = m else { return None };
    if *id != THEIR_METADATA {
        return None;
    }
    match MetadataMessage::decode(payload)? {
        MetadataMessage::Request(p) => Some(p),
        _ => None,
    }
}

fn large_info_torrent(dir: &TempDir) -> (Vec<u8>, Vec<u8>) {
    // 1,024 pieces make more than one 16 KiB metadata piece. Mostly a hole, so it hashes quickly.
    write_content(&dir.file("film.bin"), 16 << 20, SEED, (16 << 20) - (1 << 20)).unwrap();
    let torrent = make_torrent(&dir.file("film.bin"), 16 << 10, Version::V1);
    let raw = metainfo::parse_torrent(&torrent).unwrap().info.raw.to_vec();
    assert!(raw.len() > wire::METADATA_PIECE);
    (torrent, raw)
}

#[test]
fn metadata_that_fails_its_hash_is_fetched_again_from_a_peer_already_connected() {
    let dir = TempDir::new("stream-metadata-liar");
    let (torrent, raw) = large_info_torrent(&dir);
    let hash = hash_of(&torrent);
    let release = Arc::new(AtomicBool::new(false));
    let held: Arc<Mutex<Vec<u32>>> = Arc::default();
    let liar_raw = raw.clone();
    let (liar_release, liar_held) = (release.clone(), held.clone());
    // The liar takes the requests, and answers them with garbage only once the honest peer has connected.
    let liar = Scripted::start(hash, Some(raw.len()), move |m, stream| {
        if let Some(p) = metadata_request(m) {
            liar_held.lock().unwrap().push(p);
        }
        if liar_release.load(Ordering::SeqCst) {
            for p in liar_held.lock().unwrap().drain(..) {
                let _ = send(stream, &metadata_reply(&liar_raw, p, false));
            }
        }
    });
    let honest_raw = raw.clone();
    let honest = Scripted::start(hash, Some(raw.len()), move |m, stream| {
        if let Some(p) = metadata_request(m) {
            let _ = send(stream, &metadata_reply(&honest_raw, p, true));
        }
    });
    let e = Engine::new(config(&dir.file("cache"))).unwrap();
    let source = Source { magnet: format!("magnet:?xt=urn:btih:{}", metainfo::hex(&hash)), peers: vec![liar.address()], ..Source::default() };
    let t = e.add(&source).unwrap();
    wait_for(Duration::from_secs(5), "the liar to be asked", || held.lock().unwrap().len() == 2);
    e.add_peers(t, &[honest.address()]).unwrap();
    wait_for(Duration::from_secs(5), "the honest peer to connect", || honest.connections.load(Ordering::SeqCst) == 1);
    std::thread::sleep(Duration::from_millis(300));
    release.store(true, Ordering::SeqCst);
    let files = e.wait_metadata(t, Duration::from_secs(20), None).expect("metadata from the honest peer");
    assert_eq!(files[0].size, 16 << 20);
}

#[test]
fn a_peer_claiming_the_wrong_metadata_size_doesnt_block_the_right_one() {
    let dir = TempDir::new("stream-metadata-size");
    let (torrent, raw) = large_info_torrent(&dir);
    let hash = hash_of(&torrent);
    // It claims a larger size and never answers.
    let liar = Scripted::start(hash, Some(raw.len() + wire::METADATA_PIECE), |_, _| {});
    let honest_raw = raw.clone();
    let honest = Scripted::start(hash, Some(raw.len()), move |m, stream| {
        if let Some(p) = metadata_request(m) {
            let _ = send(stream, &metadata_reply(&honest_raw, p, true));
        }
    });
    let e = Engine::new(config(&dir.file("cache"))).unwrap();
    let source = Source { magnet: format!("magnet:?xt=urn:btih:{}", metainfo::hex(&hash)), peers: vec![liar.address()], ..Source::default() };
    let t = e.add(&source).unwrap();
    wait_for(Duration::from_secs(5), "the liar to be asked", || liar.saw(|m| metadata_request(m).is_some()));
    e.add_peers(t, &[honest.address()]).unwrap();
    let files = e.wait_metadata(t, Duration::from_secs(25), None).expect("metadata from the honest peer");
    assert_eq!(files[0].size, 16 << 20);
}

#[test]
fn metadata_requests_a_peer_ignored_dont_use_up_its_allowance() {
    let dir = TempDir::new("stream-metadata-lost");
    let (torrent, raw) = large_info_torrent(&dir);
    let hash = hash_of(&torrent);
    let mut ignored = 0;
    let seed = Scripted::start(hash, Some(raw.len()), move |m, stream| {
        if let Some(p) = metadata_request(m) {
            if ignored < 2 {
                ignored += 1;
                return;
            }
            let _ = send(stream, &metadata_reply(&raw, p, true));
        }
    });
    let e = Engine::new(config(&dir.file("cache"))).unwrap();
    let source = Source { magnet: format!("magnet:?xt=urn:btih:{}", metainfo::hex(&hash)), peers: vec![seed.address()], ..Source::default() };
    let t = e.add(&source).unwrap();
    let files = e.wait_metadata(t, Duration::from_secs(25), None).expect("metadata after the lost requests");
    assert_eq!(files[0].size, 16 << 20);
}

#[test]
fn a_connection_refused_because_of_another_on_the_same_address_is_tried_again() {
    let dir = TempDir::new("stream-refused");
    let torrent = film(&dir, 1 << 20, PIECE);
    let hash = hash_of(&torrent);
    let (a, b) = (Scripted::start(hash, None, |_, _| {}), Scripted::start(hash, None, |_, _| {}));
    // One connection per address: the second to finish its handshake is refused.
    let e = Engine::new(Config { multiple_connections_per_ip: false, ..config(&dir.file("cache")) }).unwrap();
    let t = e.add(&Source { metainfo: torrent, peers: vec![a.address(), b.address()], ..Source::default() }).unwrap();
    wait_for(Duration::from_secs(5), "both peers to be dialled", || {
        a.connections.load(Ordering::SeqCst) == 1 && b.connections.load(Ordering::SeqCst) == 1
    });
    std::thread::sleep(Duration::from_millis(300));
    assert_eq!(e.stats(t).unwrap().peers, 1);
    a.drop_connections.store(true, Ordering::SeqCst);
    b.drop_connections.store(true, Ordering::SeqCst);
    wait_for(Duration::from_secs(2), "the connection to drop", || e.stats(t).unwrap().peers == 0);
    a.drop_connections.store(false, Ordering::SeqCst);
    b.drop_connections.store(false, Ordering::SeqCst);
    // Both are worth trying again, including the one refused earlier.
    wait_for(Duration::from_secs(20), "both peers to be dialled again", || {
        a.connections.load(Ordering::SeqCst) >= 2 && b.connections.load(Ordering::SeqCst) >= 2
    });
}

/// Connects to a seeder as a peer that asks for things and never reads the answers.
fn non_reading_peer(port: u16, hash: [u8; 20]) -> TcpStream {
    let socket = socket2::Socket::new(socket2::Domain::IPV4, socket2::Type::STREAM, None).unwrap();
    socket.set_recv_buffer_size(4096).unwrap();
    let address: std::net::SocketAddr = format!("127.0.0.1:{port}").parse().unwrap();
    socket.connect(&address.into()).unwrap();
    let mut stream: TcpStream = socket.into();
    stream.set_write_timeout(Some(Duration::from_secs(20))).unwrap();
    let mut reserved = Reserved([0; 8]);
    reserved.0[5] |= 0x10;
    reserved.0[7] |= 0x04;
    stream.write_all(&Handshake { reserved, info_hash: hash, peer_id: wire::new_peer_id() }.encode()).unwrap();
    let ext = ExtHandshake { metadata: Some(THEIR_METADATA), ..ExtHandshake::default() };
    send(&mut stream, &Message::Extended { id: 0, payload: ext.encode() }).unwrap();
    send(&mut stream, &Message::Interested).unwrap();
    stream
}

#[test]
fn a_peer_that_asks_for_replies_and_never_reads_them_is_disconnected() {
    let dir = TempDir::new("stream-never-reads");
    let torrent = film(&dir, 8 << 20, 16 << 10);
    let seed = Seeder::single(&torrent, dir.path(), &SeederOptions { encryption: Policy::Disabled, ..SeederOptions::default() });
    let mut peer = non_reading_peer(seed.port(), hash_of(&torrent));
    let request = Message::Extended { id: wire::EXT_METADATA, payload: MetadataMessage::Request(0).encode() };
    let mut encoded = Vec::new();
    request.encode(&mut encoded);
    // Each request is answered by about 10 KiB nobody reads. Without a limit this would queue a gigabyte.
    let mut closed = false;
    for _ in 0..100_000 {
        if peer.write_all(&encoded).is_err() {
            closed = true;
            break;
        }
    }
    assert!(closed, "the seeder should have given up on this peer");
    wait_for(Duration::from_secs(2), "the peer to be forgotten", || seed.torrents[0].lock().peers.is_empty());
}

#[test]
fn removing_a_torrent_doesnt_wait_for_a_peer_that_stopped_reading() {
    let dir = TempDir::new("stream-stuck-writer");
    let torrent = film(&dir, 16 << 20, PIECE);
    let seed = Seeder::single(&torrent, dir.path(), &SeederOptions { encryption: Policy::Disabled, ..SeederOptions::default() });
    let mut peer = non_reading_peer(seed.port(), hash_of(&torrent));
    wait_for(Duration::from_secs(5), "the peer to attach", || !seed.torrents[0].lock().peers.is_empty());
    std::thread::sleep(Duration::from_millis(1100)); // A choke round may be needed; choke_round runs on Interested.
    for piece in 0..(16 << 20) / PIECE as u32 {
        for offset in (0..PIECE as u32).step_by(16384) {
            if send(&mut peer, &Message::Request(BlockRef { piece, offset, length: 16384 })).is_err() {
                break;
            }
        }
    }
    std::thread::sleep(Duration::from_millis(500));
    let t = &seed.torrents[0];
    seed.session.remove(t);
    let started = Instant::now();
    wait_for(Duration::from_secs(2), "the stuck peer to be dropped", || t.lock().peers.is_empty());
    assert!(started.elapsed() < Duration::from_secs(1), "took {:?}", started.elapsed());
}
