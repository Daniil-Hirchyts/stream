//! Transports and sources beyond plain TCP peers, on loopback: MSE/PE encryption (forced, refused, negotiated), uTP
//! alone, and HTTP/HTTPS web seeds, with every piece still hash-checked and HTTPS certificates still verified.

use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};
use stream_core::bt::metainfo::{self, Version};
use stream_core::bt::mse::Policy;
use stream_core::engine::{Config, Engine, ErrorKind, WOULD_BLOCK};
use stream_core::fixtures::{first_mismatch, make_torrent, write_content, Seeder, SeederOptions, TempDir};
use stream_core::swarm::Source;

fn config(dir: &Path) -> Config {
    Config {
        cache_dir: dir.to_string_lossy().into_owned(),
        cache_bytes: 2 << 20,
        reserve_bytes: 0,
        listen_interfaces: "127.0.0.1:0".into(),
        dht: false,
        lsd: false,
        pex: false,
        multiple_connections_per_ip: true,
        log: std::env::var_os("STREAM_TEST_LOG").map(|_| Arc::new(|l: &str| println!("LOG {l}")) as _),
        ..Config::default()
    }
}

fn read_all(e: &Engine, t: u64, seed: u32, size: i64) {
    let files = e.wait_metadata(t, Duration::from_secs(15), None).unwrap();
    e.select_file(t, files[0].index).unwrap();
    let r = e.open_reader(t, files[0].index).unwrap();
    let mut buf = vec![0u8; 64 << 10];
    let mut pos = 0;
    let deadline = Instant::now() + Duration::from_secs(60);
    while pos < size {
        let want = (buf.len() as i64).min(size - pos) as usize;
        let n = e.read(r, pos, &mut buf[..want], Duration::from_millis(200)).unwrap();
        if n == WOULD_BLOCK {
            assert!(Instant::now() < deadline, "stalled at {pos}: {}", e.stats(t).unwrap().to_json());
            continue;
        }
        assert!(first_mismatch(seed, pos, &buf[..n as usize]).is_none(), "wrong byte near {pos}");
        pos += i64::from(n);
    }
}

fn film(dir: &TempDir, v: Version, seed: u32) -> Vec<u8> {
    write_content(&dir.file("film.bin"), 3 << 20, seed, 0).unwrap();
    make_torrent(&dir.file("film.bin"), 64 << 10, v)
}

#[test]
fn forced_encryption_downloads_from_a_seeder_that_offers_it() {
    for v in [Version::V1, Version::V2, Version::Hybrid] {
        let dir = TempDir::new("stream-mse");
        let torrent = film(&dir, v, 51);
        // The seeder requires encryption too, so a plaintext connection could not have worked.
        let seed = Seeder::single(&torrent, dir.path(), &SeederOptions { encryption: Policy::Forced, ..SeederOptions::default() });
        let e = Engine::new(Config { encryption: Policy::Forced, ..config(&dir.file("cache")) }).unwrap();
        let t = e.add(&Source { metainfo: torrent, peers: vec![seed.address()], ..Source::default() }).unwrap();
        read_all(&e, t, 51, 3 << 20);
    }
}

#[test]
fn plaintext_clients_are_refused_by_a_seeder_that_requires_encryption() {
    let dir = TempDir::new("stream-mse-refused");
    let torrent = film(&dir, Version::V1, 52);
    let seed = Seeder::single(&torrent, dir.path(), &SeederOptions { encryption: Policy::Forced, ..SeederOptions::default() });
    let e = Engine::new(Config { encryption: Policy::Disabled, ..config(&dir.file("cache")) }).unwrap();
    let hash = metainfo::parse_torrent(&torrent).unwrap().info.hashes.v1.unwrap();
    let t = e.add(&Source { info_hash: metainfo::hex(&hash), peers: vec![seed.address()], ..Source::default() }).unwrap();
    assert_eq!(e.wait_metadata(t, Duration::from_secs(2), None).unwrap_err().kind, ErrorKind::Timeout);
}

#[test]
fn encryption_enabled_on_both_sides_negotiates_and_falls_back() {
    let dir = TempDir::new("stream-mse-plain");
    let torrent = film(&dir, Version::Hybrid, 53);
    // The seeder refuses MSE: the client's encrypted attempt fails and it reconnects in plaintext.
    let seed = Seeder::single(&torrent, dir.path(), &SeederOptions { encryption: Policy::Disabled, ..SeederOptions::default() });
    let e = Engine::new(config(&dir.file("cache"))).unwrap();
    let t = e.add(&Source { metainfo: torrent, peers: vec![seed.address()], ..Source::default() }).unwrap();
    read_all(&e, t, 53, 3 << 20);
}

#[test]
fn utp_alone_carries_metadata_and_pieces() {
    for (v, encryption) in [(Version::V1, Policy::Disabled), (Version::V2, Policy::Forced), (Version::Hybrid, Policy::Enabled)] {
        let dir = TempDir::new("stream-utp");
        let torrent = film(&dir, v, 54);
        let seed = Seeder::single(&torrent, dir.path(), &SeederOptions { tcp: false, encryption, ..SeederOptions::default() });
        let e = Engine::new(Config { tcp: false, encryption, ..config(&dir.file("cache")) }).unwrap();
        let hashes = metainfo::parse_torrent(&torrent).unwrap().info.hashes;
        let magnet = match hashes.v1 {
            Some(h) => format!("magnet:?xt=urn:btih:{}&x.pe={}", metainfo::hex(&h), seed.address()),
            None => format!("magnet:?xt=urn:btmh:1220{}&x.pe={}", metainfo::hex(&hashes.v2.unwrap()), seed.address()),
        };
        let t = e.add(&Source { magnet, ..Source::default() }).unwrap();
        read_all(&e, t, 54, 3 << 20);
        assert_eq!(e.stats(t).unwrap().peers, 1, "{v:?}");
    }
}

/// A loopback HTTP server answering ranged GETs for files under `root`, counting requests. With `corrupt`, every
/// response has one byte flipped.
struct WebSeed {
    port: u16,
    requests: Arc<AtomicUsize>,
}

fn decode_path(path: &str) -> String {
    let b = path.as_bytes();
    let mut out = Vec::new();
    let mut i = 0;
    while i < b.len() {
        if b[i] == b'%' && i + 2 < b.len() {
            out.push(u8::from_str_radix(&path[i + 1..i + 3], 16).unwrap());
            i += 3;
        } else {
            out.push(b[i]);
            i += 1;
        }
    }
    String::from_utf8(out).unwrap()
}

fn respond(mut s: impl Read + Write, root: &Path, corrupt: bool, delay: Duration) {
    let mut request = Vec::new();
    let mut byte = [0u8; 1];
    while !request.ends_with(b"\r\n\r\n") {
        if s.read(&mut byte).unwrap_or(0) == 0 {
            return;
        }
        request.push(byte[0]);
    }
    let text = String::from_utf8_lossy(&request).into_owned();
    let path = text.split_whitespace().nth(1).unwrap_or("/");
    let file = root.join(decode_path(path.trim_start_matches('/')));
    let range = text
        .lines()
        .find_map(|l| l.strip_prefix("Range: bytes="))
        .and_then(|r| r.trim().split_once('-'))
        .map(|(a, b)| (a.parse::<usize>().unwrap(), b.parse::<usize>().unwrap() + 1));
    let Ok(data) = std::fs::read(&file) else {
        let _ = s.write_all(b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n");
        return;
    };
    let Some((a, b)) = range.filter(|(a, b)| a < b && *b <= data.len()) else {
        let _ = s.write_all(b"HTTP/1.1 416 Range Not Satisfiable\r\nContent-Length: 0\r\n\r\n");
        return;
    };
    let mut body = data[a..b].to_vec();
    if corrupt {
        body[0] ^= 0x5a;
    }
    std::thread::sleep(delay);
    let head = format!("HTTP/1.1 206 Partial Content\r\nContent-Length: {}\r\nContent-Range: bytes {a}-{}/{}\r\n\r\n", body.len(), b - 1, data.len());
    let _ = s.write_all(head.as_bytes());
    let _ = s.write_all(&body);
}

impl WebSeed {
    fn http(root: PathBuf, corrupt: bool) -> WebSeed {
        WebSeed::delayed(root, corrupt, Duration::ZERO)
    }

    /// Every response waits `delay` before it is sent.
    fn delayed(root: PathBuf, corrupt: bool, delay: Duration) -> WebSeed {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let requests = Arc::new(AtomicUsize::new(0));
        let count = requests.clone();
        std::thread::spawn(move || {
            for stream in listener.incoming().flatten() {
                count.fetch_add(1, Ordering::SeqCst);
                let root = root.clone();
                std::thread::spawn(move || respond(stream, &root, corrupt, delay));
            }
        });
        WebSeed { port, requests }
    }

    /// HTTPS with a certificate for `localhost` from `key`; returns the seed and the certificate's PEM.
    fn https(root: PathBuf) -> (WebSeed, String) {
        let key = rcgen::generate_simple_self_signed(vec!["localhost".to_string()]).unwrap();
        let pem = key.cert.pem();
        let cert = key.cert.der().clone();
        let private = rustls::pki_types::PrivateKeyDer::Pkcs8(key.key_pair.serialize_der().into());
        let config = rustls::ServerConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
            .with_safe_default_protocol_versions()
            .unwrap()
            .with_no_client_auth()
            .with_single_cert(vec![cert], private)
            .unwrap();
        let config = Arc::new(config);
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let requests = Arc::new(AtomicUsize::new(0));
        let count = requests.clone();
        std::thread::spawn(move || {
            for stream in listener.incoming().flatten() {
                count.fetch_add(1, Ordering::SeqCst);
                let (root, config) = (root.clone(), config.clone());
                std::thread::spawn(move || {
                    let conn = rustls::ServerConnection::new(config).unwrap();
                    respond(rustls::StreamOwned::new(conn, stream), &root, false, Duration::ZERO);
                });
            }
        });
        (WebSeed { port, requests }, pem)
    }
}

#[test]
fn http_web_seeds_serve_single_and_multi_file_torrents() {
    for v in [Version::V1, Version::V2, Version::Hybrid] {
        let dir = TempDir::new("stream-webseed");
        let torrent = film(&dir, v, 55);
        let seed = WebSeed::http(dir.path().to_path_buf(), false);
        let e = Engine::new(config(&dir.file("cache"))).unwrap();
        // A single-file seed may name the file itself.
        let magnet_free = Source { metainfo: with_web_seeds(&torrent, &[format!("http://127.0.0.1:{}/film.bin", seed.port)]), ..Source::default() };
        let t = e.add(&magnet_free).unwrap();
        read_all(&e, t, 55, 3 << 20);
        assert!(seed.requests.load(Ordering::SeqCst) > 0);
    }
    // A directory torrent: the seed's address is followed by the torrent's name and paths, percent-encoded.
    let dir = TempDir::new("stream-webseed-multi");
    let root = dir.file("Сериал 1");
    std::fs::create_dir_all(root.join("season one")).unwrap();
    write_content(&root.join("season one/e1.bin"), 1 << 20, 56, 0).unwrap();
    write_content(&root.join("season one/e2 & more.bin"), (2 << 20) + 5, 57, 0).unwrap();
    let torrent = make_torrent(&root, 64 << 10, Version::Hybrid);
    let seed = WebSeed::http(dir.path().to_path_buf(), false);
    let e = Engine::new(config(&dir.file("cache"))).unwrap();
    let t = e.add(&Source { metainfo: with_web_seeds(&torrent, &[format!("http://127.0.0.1:{}/", seed.port)]), ..Source::default() }).unwrap();
    let files = e.wait_metadata(t, Duration::from_secs(5), None).unwrap();
    let second = files.iter().find(|f| f.path.ends_with("e2 & more.bin")).unwrap();
    e.select_file(t, second.index).unwrap();
    let r = e.open_reader(t, second.index).unwrap();
    let mut buf = vec![0u8; (2 << 20) + 5];
    let mut pos = 0usize;
    let deadline = Instant::now() + Duration::from_secs(30);
    while pos < buf.len() {
        let n = e.read(r, pos as i64, &mut buf[pos..], Duration::from_millis(200)).unwrap();
        if n == WOULD_BLOCK {
            assert!(Instant::now() < deadline);
            continue;
        }
        pos += n as usize;
    }
    assert!(first_mismatch(57, 0, &buf).is_none());
}

#[test]
fn corrupt_web_seeds_fail_the_hash_and_peers_complete_the_file() {
    let dir = TempDir::new("stream-webseed-corrupt");
    let torrent = film(&dir, Version::V1, 58);
    let bad = WebSeed::http(dir.path().to_path_buf(), true);
    let good = Seeder::single(&torrent, dir.path(), &SeederOptions::default());
    let e = Engine::new(config(&dir.file("cache"))).unwrap();
    let t = e
        .add(&Source { metainfo: with_web_seeds(&torrent, &[format!("http://127.0.0.1:{}/film.bin", bad.port)]), ..Source::default() })
        .unwrap();
    e.wait_metadata(t, Duration::from_secs(5), None).unwrap();
    e.select_file(t, 0).unwrap();
    let r = e.open_reader(t, 0).unwrap();
    let deadline = Instant::now() + Duration::from_secs(20);
    while e.stats(t).unwrap().hash_failures < 3 {
        let _ = e.read(r, 0, &mut [0u8; 1], Duration::from_millis(100));
        assert!(Instant::now() < deadline, "the corrupt seed's pieces must fail their hashes");
    }
    e.add_peers(t, &[good.address()]).unwrap();
    read_all(&e, t, 58, 3 << 20);
}

#[test]
fn a_late_corrupt_web_seed_response_cannot_change_a_verified_piece() {
    let dir = TempDir::new("stream-webseed-late");
    let torrent = film(&dir, Version::V1, 61);
    // The web seed claims the first piece at once and answers it, wrongly, long after a peer has delivered it.
    let bad = WebSeed::delayed(dir.path().to_path_buf(), true, Duration::from_secs(4));
    let good = Seeder::single(&torrent, dir.path(), &SeederOptions::default());
    let e = Engine::new(config(&dir.file("cache"))).unwrap();
    let t = e
        .add(&Source { metainfo: with_web_seeds(&torrent, &[format!("http://127.0.0.1:{}/film.bin", bad.port)]), ..Source::default() })
        .unwrap();
    e.wait_metadata(t, Duration::from_secs(5), None).unwrap();
    e.select_file(t, 0).unwrap();
    let r = e.open_reader(t, 0).unwrap();
    let mut piece = vec![0u8; 64 << 10];
    let deadline = Instant::now() + Duration::from_secs(5);
    while bad.requests.load(Ordering::SeqCst) == 0 {
        assert_eq!(e.read(r, 0, &mut piece, Duration::from_millis(50)).unwrap(), WOULD_BLOCK, "only the web seed is known");
        assert!(Instant::now() < deadline, "the web seed should have been asked");
    }
    let asked = Instant::now();
    e.add_peers(t, &[good.address()]).unwrap();
    // The urgent piece is asked of the peer too after two seconds, and passes its hash.
    let mut n = WOULD_BLOCK;
    while n == WOULD_BLOCK {
        n = e.read(r, 0, &mut piece, Duration::from_millis(200)).unwrap();
        assert!(asked.elapsed() < Duration::from_secs(20));
    }
    assert!(first_mismatch(61, 0, &piece[..n as usize]).is_none());
    assert!(asked.elapsed() < Duration::from_secs(4), "the peer should have delivered before the web seed answered");
    // After the web seed's answer the reader, still at the same place, must see the same verified bytes.
    std::thread::sleep(Duration::from_secs(5).saturating_sub(asked.elapsed()));
    let mut n = WOULD_BLOCK;
    while n == WOULD_BLOCK {
        n = e.read(r, 0, &mut piece, Duration::from_millis(200)).unwrap();
        assert!(asked.elapsed() < Duration::from_secs(30));
    }
    assert!(first_mismatch(61, 0, &piece[..n as usize]).is_none(), "unverified bytes reached a reader");
}

#[test]
fn https_web_seeds_verify_certificates() {
    let dir = TempDir::new("stream-webseed-tls");
    let torrent = film(&dir, Version::Hybrid, 59);
    let (seed, pem) = WebSeed::https(dir.path().to_path_buf());
    let url = format!("https://localhost:{}/film.bin", seed.port);
    // Without the certificate among the trust roots nothing is downloaded.
    let untrusted = Engine::new(config(&dir.file("cache-untrusted"))).unwrap();
    let t = untrusted.add(&Source { metainfo: with_web_seeds(&torrent, &[url.clone()]), ..Source::default() }).unwrap();
    untrusted.wait_metadata(t, Duration::from_secs(5), None).unwrap();
    untrusted.select_file(t, 0).unwrap();
    let r = untrusted.open_reader(t, 0).unwrap();
    assert_eq!(untrusted.read(r, 0, &mut [0u8; 1], Duration::from_secs(3)).unwrap(), WOULD_BLOCK);
    assert_eq!(untrusted.stats(t).unwrap().total_download, 0);
    // Trusting it, the same seed serves the whole file.
    std::fs::write(dir.file("roots.pem"), pem).unwrap();
    let trusted = Engine::new(Config { ca_file: dir.file("roots.pem").to_string_lossy().into_owned(), ..config(&dir.file("cache")) }).unwrap();
    let t = trusted.add(&Source { metainfo: with_web_seeds(&torrent, &[url]), ..Source::default() }).unwrap();
    read_all(&trusted, t, 59, 3 << 20);
}

fn with_web_seeds(torrent: &[u8], seeds: &[String]) -> Vec<u8> {
    let t = metainfo::parse_torrent(torrent).unwrap();
    metainfo::write_torrent(&t.info, &t.trackers, &t.layers, seeds)
}

#[test]
fn rate_limited_seeders_keep_serving_queued_requests() {
    // A slow seeder fills each peer's upload queue; requests beyond it must wait and be served, not dropped, with
    // RC4 too (encrypted bytes must be flushed, not held).
    for encryption in [Policy::Disabled, Policy::Forced] {
        let dir = TempDir::new("stream-rate");
        let torrent = film(&dir, Version::V1, 60);
        let seed = Seeder::single(&torrent, dir.path(), &SeederOptions { upload_rate: 1 << 20, encryption, ..SeederOptions::default() });
        let e = Engine::new(Config { encryption, ..config(&dir.file("cache")) }).unwrap();
        let t = e.add(&Source { metainfo: torrent, peers: vec![seed.address()], ..Source::default() }).unwrap();
        // A wide read-ahead asks for far more than 1 MiB at once.
        e.set_bitrate(t, 8 << 20).unwrap();
        let started = Instant::now();
        read_all(&e, t, 60, 3 << 20);
        assert!(started.elapsed() >= Duration::from_millis(1500), "the limit applied");
    }
}
