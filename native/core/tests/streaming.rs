//! The engine against real BitTorrent seeders on loopback: metadata transfer, file selection, bounded caching with
//! eviction and re-download, seeks, cancellation, corrupt peers, trackers and 64-bit offsets.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};
use stream_core::bt::metainfo::{self, Version};
use stream_core::cache::STORAGE_OVERHEAD;
use stream_core::engine::{Config, Engine, ErrorKind, WOULD_BLOCK};
use stream_core::fixtures::{first_mismatch, make_torrent, write_content, Seeder, SeederOptions, TempDir};
use stream_core::swarm::Source;

fn local_config(cache_dir: &std::path::Path, cache_bytes: i64) -> Config {
    Config {
        cache_dir: cache_dir.to_string_lossy().into_owned(),
        cache_bytes,
        reserve_bytes: 0,
        listen_interfaces: "127.0.0.1:0".into(),
        dht: false,
        lsd: false,
        upnp: false,
        natpmp: false,
        pex: false,
        multiple_connections_per_ip: true,
        log: std::env::var_os("STREAM_TEST_LOG").map(|_| Arc::new(|l: &str| println!("LOG {l}")) as _),
        ..Config::default()
    }
}

fn open(e: &Engine, torrent: &[u8], peers: &[String]) -> u64 {
    e.add(&Source { metainfo: torrent.to_vec(), peers: peers.to_vec(), ..Source::default() }).unwrap()
}

/// Reads `[offset, offset + size)` of the reader's file, checking every byte against the content.
fn read_checked(e: &Engine, reader: u64, seed: u32, offset: i64, size: i64, mut each_mib: impl FnMut()) {
    let mut buf = vec![0u8; 64 << 10];
    let deadline = Instant::now() + Duration::from_secs(60);
    let mut pos = offset;
    let mut next_mib = offset + (1 << 20);
    while pos < offset + size {
        let want = (buf.len() as i64).min(offset + size - pos) as usize;
        let n = e.read(reader, pos, &mut buf[..want], Duration::from_millis(200)).unwrap();
        if n == WOULD_BLOCK {
            assert!(Instant::now() < deadline, "read stalled at {pos}");
            continue;
        }
        assert!(n > 0, "unexpected end at {pos}");
        if let Some(bad) = first_mismatch(seed, pos, &buf[..n as usize]) {
            panic!("wrong byte at {}", pos + bad as i64);
        }
        pos += i64::from(n);
        if pos >= next_mib {
            each_mib();
            next_mib += 1 << 20;
        }
    }
}

fn wait_until(timeout: Duration, mut done: impl FnMut() -> bool) -> bool {
    let deadline = Instant::now() + timeout;
    while Instant::now() < deadline {
        if done() {
            return true;
        }
        std::thread::sleep(Duration::from_millis(20));
    }
    done()
}

#[test]
fn public_network_sessions_reject_an_explicit_loopback_seeder() {
    let dir = TempDir::new("stream-public-network");
    write_content(&dir.file("movie.bin"), 1 << 20, 4, 0).unwrap();
    let torrent = make_torrent(&dir.file("movie.bin"), 256 << 10, Version::V1);
    let seed = Seeder::single(&torrent, dir.path(), &SeederOptions::default());
    let mut config = local_config(&dir.file("cache"), 4 << 20);
    config.public_network_only = true;
    let e = Engine::new(config).unwrap();
    let hash = metainfo::parse_torrent(&torrent).unwrap().info.hashes.v1.unwrap();
    let t = e
        .add(&Source { info_hash: metainfo::hex(&hash), peers: vec![seed.address()], ..Source::default() })
        .unwrap();
    assert_eq!(e.wait_metadata(t, Duration::from_millis(1500), None).unwrap_err().kind, ErrorKind::Timeout);
    assert_eq!(e.stats(t).unwrap().peers, 0);
}

#[test]
fn sequential_read_turns_the_cache_over_within_its_bound() {
    let dir = TempDir::new("stream-sequential");
    let size: i64 = 24 << 20;
    write_content(&dir.file("movie.bin"), size, 1, 0).unwrap();
    let torrent = make_torrent(&dir.file("movie.bin"), 256 << 10, Version::V1);
    let seed = Seeder::single(&torrent, dir.path(), &SeederOptions::default());
    let cache: i64 = 4 << 20;
    let e = Engine::new(local_config(&dir.file("cache"), cache)).unwrap();
    let t = open(&e, &torrent, &[seed.address()]);
    let files = e.wait_metadata(t, Duration::from_secs(10), None).unwrap();
    assert_eq!(files.len(), 1);
    assert_eq!(files[0].size, size);
    e.select_file(t, 0).unwrap();
    let r = e.open_reader(t, 0).unwrap();
    assert_eq!(e.file_size(r).unwrap(), size);
    let (mut most_file, mut most_allocated, mut most_used) = (0, 0, 0);
    read_checked(&e, r, 1, 0, size, || {
        let st = e.stats(t).unwrap();
        most_file = most_file.max(st.cache.file_bytes);
        most_allocated = most_allocated.max(st.cache.allocated_bytes);
        most_used = most_used.max(st.cache.used);
    });
    let st = e.stats(t).unwrap();
    // 96 pieces through 15 slots, leaving filesystem overhead inside the total budget.
    assert_eq!(st.cache.slots, 15);
    assert!(st.cache.evicted >= 3 * i64::from(st.cache.slots));
    assert!(most_used <= st.cache.slots);
    // The file is preallocated and slots are reused in place.
    assert!(most_file <= cache);
    assert!(most_allocated <= cache);
    // Sequential playback downloads each piece about once.
    assert!(st.total_download < size + size / 4, "downloaded {}", st.total_download);
    // Reading at the end of the file reports the end, not an error.
    let mut c = [0u8; 1];
    assert_eq!(e.read(r, size, &mut c, Duration::from_millis(10)).unwrap(), 0);
}

#[test]
fn rewind_into_evicted_data_downloads_it_again() {
    let dir = TempDir::new("stream-rewind");
    let size: i64 = 12 << 20;
    write_content(&dir.file("movie.bin"), size, 2, 0).unwrap();
    let torrent = make_torrent(&dir.file("movie.bin"), 256 << 10, Version::V1);
    let seed = Seeder::single(&torrent, dir.path(), &SeederOptions::default());
    let e = Engine::new(local_config(&dir.file("cache"), 2 << 20)).unwrap();
    let t = open(&e, &torrent, &[seed.address()]);
    e.wait_metadata(t, Duration::from_secs(10), None).unwrap();
    e.select_file(t, 0).unwrap();
    let r = e.open_reader(t, 0).unwrap();
    read_checked(&e, r, 2, 0, size, || {});
    // The seeder was told of every eviction, so it never mistakes the client for a seed and drops it.
    std::thread::sleep(Duration::from_millis(500));
    assert_eq!(e.stats(t).unwrap().peers, 1);
    let before = e.stats(t).unwrap();
    read_checked(&e, r, 2, 0, 3 << 20, || {});
    let after = e.stats(t).unwrap();
    assert!(after.total_download >= before.total_download + (2 << 20));
    assert!(after.cache.evicted > before.cache.evicted);
}

#[test]
fn seeks_forward_and_back_read_the_right_bytes() {
    let dir = TempDir::new("stream-seeks");
    let size: i64 = 16 << 20;
    write_content(&dir.file("movie.bin"), size, 3, 0).unwrap();
    let torrent = make_torrent(&dir.file("movie.bin"), 128 << 10, Version::V1);
    let seed = Seeder::single(&torrent, dir.path(), &SeederOptions::default());
    let e = Engine::new(local_config(&dir.file("cache"), 2 << 20)).unwrap();
    let t = open(&e, &torrent, &[seed.address()]);
    e.wait_metadata(t, Duration::from_secs(10), None).unwrap();
    e.select_file(t, 0).unwrap();
    let r = e.open_reader(t, 0).unwrap();
    for start in [0, size * 9 / 10, size * 4 / 10 + 12345, 100] {
        read_checked(&e, r, 3, start, 1 << 20, || {});
    }
    // Only around the four positions was anything downloaded.
    assert!(e.stats(t).unwrap().total_download < 10 << 20);
}

/// Three files, so the selected one starts and ends inside pieces (v1) or after padding (v2, hybrid).
fn stream_middle_file(v: Version) {
    let dir = TempDir::new("stream-middle");
    let root = dir.file("series");
    std::fs::create_dir_all(&root).unwrap();
    let sizes: [i64; 3] = [3 << 20, (5 << 20) + 1234, (2 << 20) + 77];
    for (i, s) in sizes.iter().enumerate() {
        write_content(&root.join(format!("e{}.bin", i + 1)), *s, 10 + i as u32, 0).unwrap();
    }
    let torrent = make_torrent(&root, 64 << 10, v);
    let seed = Seeder::single(&torrent, dir.path(), &SeederOptions::default());
    let e = Engine::new(local_config(&dir.file("cache"), 1 << 20)).unwrap();
    let t = open(&e, &torrent, &[seed.address()]);
    let files = e.wait_metadata(t, Duration::from_secs(10), None).unwrap();
    // Pad files are not offered.
    assert_eq!(files.len(), 3);
    let middle = files.iter().find(|f| f.path.ends_with("e2.bin")).unwrap();
    assert_eq!(middle.size, sizes[1]);
    e.select_file(t, middle.index).unwrap();
    let r = e.open_reader(t, middle.index).unwrap();
    read_checked(&e, r, 11, 0, sizes[1], || {});
    read_checked(&e, r, 11, 4096, 1 << 20, || {});
    let st = e.stats(t).unwrap();
    assert!(st.cache.evicted > 0);
    // The other files' pieces were not downloaded, beyond the pieces shared at the edges.
    assert!(st.total_download < sizes[1] + (2 << 20));
}

#[test]
fn v1_multi_file_torrent_streams_one_file() {
    stream_middle_file(Version::V1);
}

#[test]
fn v2_torrent_streams_one_file() {
    stream_middle_file(Version::V2);
}

#[test]
fn hybrid_torrent_streams_one_file() {
    stream_middle_file(Version::Hybrid);
}

/// The metadata of a torrent known only by its hash arrives over BitTorrent; for v2, so do its piece layers.
fn metadata_over_bittorrent(v: Version, source: impl Fn(&metainfo::InfoHashes, &str) -> Source) {
    let dir = TempDir::new("stream-magnet");
    write_content(&dir.file("clip.bin"), 3 << 20, 4, 0).unwrap();
    let torrent = make_torrent(&dir.file("clip.bin"), 64 << 10, v);
    let seed = Seeder::single(&torrent, dir.path(), &SeederOptions::default());
    let hashes = metainfo::parse_torrent(&torrent).unwrap().info.hashes;
    let e = Engine::new(local_config(&dir.file("cache"), 1 << 20)).unwrap();
    let t = e.add(&source(&hashes, &seed.address())).unwrap();
    let files = e.wait_metadata(t, Duration::from_secs(15), None).unwrap();
    assert_eq!(files.len(), 1);
    e.select_file(t, files[0].index).unwrap();
    let r = e.open_reader(t, files[0].index).unwrap();
    read_checked(&e, r, 4, 0, 3 << 20, || {});
    // The torrent written back carries the piece layers that arrived, so it loads without peers.
    let again = metainfo::parse_torrent(&e.metainfo(t).unwrap()).unwrap();
    assert_eq!(again.info.hashes, hashes);
    if v == Version::V2 {
        assert_eq!(again.layers.len(), 1);
    }
}

#[test]
fn magnet_metadata_arrives_over_bittorrent() {
    // Only the v1 hash: the v2 hashes arrive with the metadata.
    metadata_over_bittorrent(Version::Hybrid, |h, peer| Source {
        info_hash: metainfo::hex(&h.v1.unwrap()),
        peers: vec![peer.into()],
        ..Source::default()
    });
    metadata_over_bittorrent(Version::V1, |h, peer| Source {
        magnet: format!("magnet:?xt=urn:btih:{}&x.pe={peer}", metainfo::hex(&h.v1.unwrap())),
        ..Source::default()
    });
}

#[test]
fn v2_magnet_fetches_metadata_and_piece_layers_from_peers() {
    metadata_over_bittorrent(Version::V2, |h, peer| Source {
        magnet: format!("magnet:?xt=urn:btmh:1220{}&x.pe={peer}", metainfo::hex(&h.v2.unwrap())),
        ..Source::default()
    });
}

#[test]
fn metadata_wait_times_out_without_peers() {
    let dir = TempDir::new("stream-timeout");
    let e = Engine::new(local_config(&dir.file("cache"), 1 << 20)).unwrap();
    let t = e.add(&Source { info_hash: "a".repeat(40), ..Source::default() }).unwrap();
    assert_eq!(e.wait_metadata(t, Duration::from_millis(300), None).unwrap_err().kind, ErrorKind::Timeout);
    let cancelled = AtomicBool::new(true);
    assert_eq!(e.wait_metadata(t, Duration::from_secs(10), Some(&cancelled)).unwrap_err().kind, ErrorKind::Cancelled);
    assert_eq!(e.stats(t).unwrap().state, "metadata");
}

#[test]
fn blocked_reads_wake_on_cancel_and_on_close() {
    let dir = TempDir::new("stream-cancel");
    write_content(&dir.file("movie.bin"), 2 << 20, 5, 0).unwrap();
    let torrent = make_torrent(&dir.file("movie.bin"), 64 << 10, Version::V1);
    let e = Arc::new(Engine::new(local_config(&dir.file("cache"), 1 << 20)).unwrap());
    // No peers: the data never arrives.
    let t = open(&e, &torrent, &[]);
    e.wait_metadata(t, Duration::from_secs(1), None).unwrap();
    e.select_file(t, 0).unwrap();
    let r = e.open_reader(t, 0).unwrap();
    let blocked_read = |reader: u64| loop {
        let mut buf = [0u8; 16];
        match e.read(reader, 0, &mut buf, Duration::from_secs(5)) {
            Ok(WOULD_BLOCK) => continue,
            other => return other,
        }
    };
    let start = Instant::now();
    let e2 = e.clone();
    let canceller = std::thread::spawn(move || {
        std::thread::sleep(Duration::from_millis(100));
        e2.cancel(r);
    });
    assert_eq!(blocked_read(r).unwrap_err().kind, ErrorKind::Cancelled);
    canceller.join().unwrap();
    assert!(start.elapsed() < Duration::from_secs(2));
    // A cancelled reader stays cancelled; another reader is unaffected until the torrent closes.
    assert_eq!(blocked_read(r).unwrap_err().kind, ErrorKind::Cancelled);
    let r2 = e.open_reader(t, 0).unwrap();
    let e2 = e.clone();
    let closer = std::thread::spawn(move || {
        std::thread::sleep(Duration::from_millis(100));
        e2.remove(t).unwrap();
    });
    assert_eq!(blocked_read(r2).unwrap_err().kind, ErrorKind::Closed);
    closer.join().unwrap();
    assert_eq!(e.stats(t).unwrap_err().kind, ErrorKind::InvalidHandle);
    // Removing the torrent deleted its cache file.
    assert!(wait_until(Duration::from_secs(2), || std::fs::read_dir(dir.file("cache")).unwrap().next().is_none()));
}

#[test]
fn shutdown_with_inflight_hashes_drains_callbacks() {
    let dir = TempDir::new("stream-shutdown");
    write_content(&dir.file("movie.bin"), 8 << 20, 15, 0).unwrap();
    let torrent = make_torrent(&dir.file("movie.bin"), 256 << 10, Version::Hybrid);
    let seed = Seeder::single(&torrent, dir.path(), &SeederOptions::default());
    for _ in 0..12 {
        let e = Engine::new(local_config(&dir.file("cache"), 2 << 20)).unwrap();
        let t = open(&e, &torrent, &[seed.address()]);
        e.wait_metadata(t, Duration::from_secs(5), None).unwrap();
        e.select_file(t, 0).unwrap();
        let r = e.open_reader(t, 0).unwrap();
        let mut byte = [0u8; 1];
        let deadline = Instant::now() + Duration::from_secs(15);
        while e.read(r, 0, &mut byte, Duration::from_secs(1)).unwrap() == WOULD_BLOCK {
            assert!(Instant::now() < deadline);
        }
        // Other pieces are being written and hashed when the engine is dropped.
    }
}

#[test]
fn corrupt_peer_data_fails_the_hash_and_comes_again_from_a_good_peer() {
    let dir = TempDir::new("stream-corrupt");
    let size: i64 = 4 << 20;
    std::fs::create_dir_all(dir.file("good")).unwrap();
    std::fs::create_dir_all(dir.file("bad")).unwrap();
    write_content(&dir.file("good/movie.bin"), size, 6, 0).unwrap();
    let torrent = make_torrent(&dir.file("good/movie.bin"), 64 << 10, Version::V1);
    // The corrupt copy differs in one byte of every piece, and its seeder serves it unchecked.
    let mut data = std::fs::read(dir.file("good/movie.bin")).unwrap();
    let mut i = 1000;
    while i < data.len() {
        data[i] ^= 0x5a;
        i += 64 << 10;
    }
    std::fs::write(dir.file("bad/movie.bin"), data).unwrap();
    let bad = Seeder::single(&torrent, &dir.file("bad"), &SeederOptions { verify: false, ..SeederOptions::default() });
    let good = Seeder::single(&torrent, &dir.file("good"), &SeederOptions::default());
    let e = Arc::new(Engine::new(local_config(&dir.file("cache"), 1 << 20)).unwrap());
    let t = open(&e, &torrent, &[bad.address()]);
    e.wait_metadata(t, Duration::from_secs(10), None).unwrap();
    e.select_file(t, 0).unwrap();
    let r = e.open_reader(t, 0).unwrap();
    let e2 = e.clone();
    let reader = std::thread::spawn(move || read_checked(&e2, r, 6, 0, size, || {}));
    assert!(wait_until(Duration::from_secs(20), || e.stats(t).unwrap().hash_failures > 0));
    // Only a good peer can complete the file; the reader never sees a corrupt byte.
    e.add_peers(t, &[good.address()]).unwrap();
    reader.join().expect("the reader saw only good bytes");
}

#[test]
fn unusable_torrents_are_refused_with_a_reason() {
    let dir = TempDir::new("stream-refused");
    write_content(&dir.file("movie.bin"), 1 << 20, 7, 0).unwrap();
    let e = Engine::new(local_config(&dir.file("cache"), 1 << 20)).unwrap();
    let kind = |s: Source| e.add(&s).unwrap_err().kind;
    assert_eq!(kind(Source { metainfo: b"d4:infoi1ee".to_vec(), ..Source::default() }), ErrorKind::BadSource);
    assert_eq!(kind(Source::default()), ErrorKind::BadSource);
    assert_eq!(kind(Source { info_hash: "1234".into(), ..Source::default() }), ErrorKind::BadSource);
    let huge = make_torrent(&dir.file("movie.bin"), 32 << 20, Version::V1);
    assert_eq!(kind(Source { metainfo: huge, ..Source::default() }), ErrorKind::PieceTooLarge);
    // Four slots of 512 KiB don't fit a 1 MiB cache.
    let big = make_torrent(&dir.file("movie.bin"), 512 << 10, Version::V1);
    assert_eq!(kind(Source { metainfo: big, ..Source::default() }), ErrorKind::CacheTooSmall);
    let fine = make_torrent(&dir.file("movie.bin"), 64 << 10, Version::V1);
    let t = open(&e, &fine, &[]);
    e.wait_metadata(t, Duration::from_secs(1), None).unwrap();
    assert_eq!(e.select_file(t, 3).unwrap_err().kind, ErrorKind::InvalidFile);
    assert_eq!(e.select_file(t, -1).unwrap_err().kind, ErrorKind::InvalidFile);
    assert_eq!(e.open_reader(t, 1).unwrap_err().kind, ErrorKind::InvalidFile);
    assert_eq!(e.read(12345, 0, &mut [], Duration::from_millis(1)).unwrap_err().kind, ErrorKind::InvalidHandle);
}

#[test]
fn offsets_beyond_4_gib_map_to_the_right_bytes() {
    let dir = TempDir::new("stream-large");
    let root = dir.file("large");
    std::fs::create_dir_all(&root).unwrap();
    // A sparse first file pushes the second past 4 GiB, starting inside a piece.
    let first: i64 = (17 << 28) + 123;
    write_content(&root.join("a.bin"), first, 8, first).unwrap();
    write_content(&root.join("b.bin"), 3 << 20, 9, 0).unwrap();
    // Hashing 4 GiB of zeros takes a while; the torrent is made once per run.
    let torrent = make_torrent(&root, 4 << 20, Version::V1);
    let seed = Seeder::single(&torrent, dir.path(), &SeederOptions::default());
    let e = Engine::new(local_config(&dir.file("cache"), (16 << 20) + STORAGE_OVERHEAD)).unwrap();
    let t = open(&e, &torrent, &[seed.address()]);
    let files = e.wait_metadata(t, Duration::from_secs(10), None).unwrap();
    let b = files.iter().find(|f| f.path.ends_with("b.bin")).unwrap();
    e.select_file(t, b.index).unwrap();
    let r = e.open_reader(t, b.index).unwrap();
    read_checked(&e, r, 9, 0, 3 << 20, || {});
}

#[test]
fn concurrent_readers_and_seeks_keep_data_and_resources_consistent() {
    let dir = TempDir::new("stream-concurrent");
    let size: i64 = 16 << 20;
    write_content(&dir.file("movie.bin"), size, 12, 0).unwrap();
    let torrent = make_torrent(&dir.file("movie.bin"), 64 << 10, Version::V1);
    let seed = Seeder::single(&torrent, dir.path(), &SeederOptions::default());
    let e = Arc::new(Engine::new(local_config(&dir.file("cache"), 2 << 20)).unwrap());
    let t = open(&e, &torrent, &[seed.address()]);
    e.wait_metadata(t, Duration::from_secs(10), None).unwrap();
    e.select_file(t, 0).unwrap();
    let stop = Arc::new(AtomicBool::new(false));
    let (e1, e2, e3, stop3) = (e.clone(), e.clone(), e.clone(), stop.clone());
    let sequential = std::thread::spawn(move || read_checked(&e1, e1.open_reader(t, 0).unwrap(), 12, 0, size, || {}));
    let seeking = std::thread::spawn(move || {
        let r = e2.open_reader(t, 0).unwrap();
        let mut x: u64 = 42;
        for _ in 0..40 {
            x = x.wrapping_mul(6_364_136_223_846_793_005).wrapping_add(1_442_695_040_888_963_407);
            let pos = ((x >> 16) % (size - (256 << 10)) as u64) as i64;
            read_checked(&e2, r, 12, pos, 200 << 10, || {});
        }
        e2.close_reader(r);
    });
    let watcher = std::thread::spawn(move || {
        while !stop3.load(Ordering::SeqCst) {
            let st = e3.stats(t).unwrap();
            assert!(st.cache.used <= st.cache.slots, "more slots used than exist");
            std::thread::sleep(Duration::from_millis(5));
        }
    });
    sequential.join().unwrap();
    seeking.join().unwrap();
    stop.store(true, Ordering::SeqCst);
    watcher.join().unwrap();
    // Once idle, nothing stays pinned and storage stays within the budget.
    std::thread::sleep(Duration::from_millis(300));
    let st = e.stats(t).unwrap();
    assert_eq!(st.cache.pinned, 0);
    assert!(st.cache.allocated_bytes <= 2 << 20);
}

#[test]
fn tracker_fallback_preserves_embedded_and_private_sources() {
    use stream_core::bt::bencode::{self, Value};
    let dir = TempDir::new("stream-trackers");
    write_content(&dir.file("movie.bin"), 2 << 20, 41, 0).unwrap();
    let original = make_torrent(&dir.file("movie.bin"), 256 << 10, Version::V1);
    let Value::Dict(mut top) = bencode::decode(&original).unwrap() else { panic!() };
    top.insert(b"announce".to_vec(), Value::Bytes(b"http://127.0.0.1:9/embedded".to_vec()));
    if let Some(Value::Dict(info)) = top.get_mut(&b"info"[..]) {
        info.insert(b"private".to_vec(), Value::Int(1));
    }
    let private_torrent = bencode::encode(&Value::Dict(top));
    let e = Engine::new(local_config(&dir.file("cache"), (4 * 256 + 64) << 10)).unwrap();
    let id = e
        .add(&Source {
            metainfo: private_torrent,
            fallback_trackers: vec!["http://127.0.0.1:9/fallback".into()],
            ..Source::default()
        })
        .unwrap();
    e.wait_metadata(id, Duration::from_secs(2), None).unwrap();
    let loaded = metainfo::parse_torrent(&e.metainfo(id).unwrap()).unwrap();
    assert!(loaded.info.private);
    assert_eq!(loaded.trackers, ["http://127.0.0.1:9/embedded"]);
    e.remove(id).unwrap();
    let id = e
        .add(&Source {
            magnet: "magnet:?xt=urn:btih:1111111111111111111111111111111111111111&tr=http%3A%2F%2F127.0.0.1%3A9%2Fembedded".into(),
            fallback_trackers: vec!["http://127.0.0.1:9/fallback".into()],
            ..Source::default()
        })
        .unwrap();
    e.remove(id).unwrap();
}

/// A loopback HTTP tracker that hands out one peer and counts announces.
fn http_tracker(peer: std::net::SocketAddrV4) -> (String, Arc<std::sync::atomic::AtomicUsize>) {
    use std::io::{Read, Write};
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let url = format!("http://127.0.0.1:{}/announce", listener.local_addr().unwrap().port());
    let count = Arc::new(std::sync::atomic::AtomicUsize::new(0));
    let c = count.clone();
    std::thread::spawn(move || {
        for stream in listener.incoming() {
            let Ok(mut s) = stream else { return };
            let mut buf = [0u8; 4096];
            let n = s.read(&mut buf).unwrap_or(0);
            if !String::from_utf8_lossy(&buf[..n]).contains("info_hash=") {
                continue;
            }
            c.fetch_add(1, Ordering::SeqCst);
            let mut compact = peer.ip().octets().to_vec();
            compact.extend_from_slice(&peer.port().to_be_bytes());
            let mut body = b"d8:intervali1800e5:peers6:".to_vec();
            body.extend_from_slice(&compact);
            body.push(b'e');
            let head = format!("HTTP/1.1 200 OK\r\nContent-Length: {}\r\n\r\n", body.len());
            let _ = s.write_all(head.as_bytes());
            let _ = s.write_all(&body);
        }
    });
    (url, count)
}

#[test]
fn http_trackers_supply_peers() {
    let dir = TempDir::new("stream-http-tracker");
    write_content(&dir.file("movie.bin"), 2 << 20, 42, 0).unwrap();
    let torrent = make_torrent(&dir.file("movie.bin"), 64 << 10, Version::Hybrid);
    let seed = Seeder::single(&torrent, dir.path(), &SeederOptions::default());
    let (url, announces) = http_tracker(format!("127.0.0.1:{}", seed.port()).parse().unwrap());
    let hashes = metainfo::parse_torrent(&torrent).unwrap().info.hashes;
    let e = Engine::new(local_config(&dir.file("cache"), 1 << 20)).unwrap();
    // A bare info hash with no trackers uses the fallback ones; the tracker is the only way to find the seeder.
    let t = e
        .add(&Source { info_hash: metainfo::hex(&hashes.v1.unwrap()), fallback_trackers: vec![url], ..Source::default() })
        .unwrap();
    let files = e.wait_metadata(t, Duration::from_secs(15), None).unwrap();
    e.select_file(t, files[0].index).unwrap();
    let r = e.open_reader(t, files[0].index).unwrap();
    read_checked(&e, r, 42, 0, 2 << 20, || {});
    assert!(announces.load(Ordering::SeqCst) >= 1);
}

#[test]
fn shutdown_wakes_a_native_read_before_its_timeout() {
    let dir = TempDir::new("stream-close");
    write_content(&dir.file("movie.bin"), 2 << 20, 41, 0).unwrap();
    let torrent = make_torrent(&dir.file("movie.bin"), 256 << 10, Version::V1);
    let e = Arc::new(Engine::new(local_config(&dir.file("cache"), (4 * 256 + 64) << 10)).unwrap());
    let id = open(&e, &torrent, &[]);
    e.wait_metadata(id, Duration::from_secs(2), None).unwrap();
    e.select_file(id, 0).unwrap();
    let reader = e.open_reader(id, 0).unwrap();
    let e2 = e.clone();
    let blocked = std::thread::spawn(move || e2.read(reader, 0, &mut [0u8; 1], Duration::from_secs(30)).is_err());
    std::thread::sleep(Duration::from_millis(100));
    let start = Instant::now();
    e.request_stop();
    assert!(blocked.join().unwrap());
    assert!(start.elapsed() < Duration::from_secs(1));
}

/// A real loopback peer that supplies one block per piece and withholds the others. This deterministically leaves
/// obsolete partial pieces after seeks, without corrupting bytes or hashes.
struct PartialSeeder {
    port: u16,
    stop: Arc<AtomicBool>,
}

impl PartialSeeder {
    fn new(pieces: usize, piece_length: i64, seed: u32) -> PartialSeeder {
        use std::io::{Read, Write};
        use stream_core::bt::wire::Message;
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let stop = Arc::new(AtomicBool::new(false));
        let stopped = stop.clone();
        std::thread::spawn(move || {
            let Ok((mut s, _)) = listener.accept() else { return };
            s.set_read_timeout(Some(Duration::from_millis(100))).unwrap();
            let mut hs = [0u8; 68];
            if s.read_exact(&mut hs).is_err() {
                return;
            }
            hs[20..28].fill(0);
            hs[48..68].copy_from_slice(&stream_core::bt::wire::new_peer_id());
            let mut out = hs.to_vec();
            let mut bits = vec![0xffu8; pieces.div_ceil(8)];
            if pieces % 8 != 0 {
                *bits.last_mut().unwrap() = 0xff << (8 - pieces % 8);
            }
            Message::Bitfield(bits).encode(&mut out);
            Message::Unchoke.encode(&mut out);
            if s.write_all(&out).is_err() {
                return;
            }
            let mut sent = std::collections::HashSet::new();
            let mut inbox: Vec<u8> = Vec::new();
            let mut buf = [0u8; 4096];
            while !stopped.load(Ordering::SeqCst) {
                match s.read(&mut buf) {
                    Ok(0) => return,
                    Ok(n) => inbox.extend_from_slice(&buf[..n]),
                    Err(e) if matches!(e.kind(), std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut) => continue,
                    Err(_) => return,
                }
                while inbox.len() >= 4 {
                    let len = u32::from_be_bytes(inbox[..4].try_into().unwrap()) as usize;
                    if inbox.len() < 4 + len {
                        break;
                    }
                    let msg = Message::decode(&inbox[4..4 + len]);
                    inbox.drain(..4 + len);
                    let Ok(Message::Request(b)) = msg else { continue };
                    if !sent.insert(b.piece) {
                        continue;
                    }
                    let mut data = vec![0u8; b.length as usize];
                    stream_core::fixtures::fill_content(seed, i64::from(b.piece) * piece_length + i64::from(b.offset), &mut data);
                    let mut reply = Vec::new();
                    Message::Piece { piece: b.piece, offset: b.offset, data }.encode(&mut reply);
                    if s.write_all(&reply).is_err() {
                        return;
                    }
                }
            }
        });
        PartialSeeder { port, stop }
    }

    fn address(&self) -> String {
        format!("127.0.0.1:{}", self.port)
    }
}

impl Drop for PartialSeeder {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::SeqCst);
    }
}

#[test]
fn partial_piece_pressure_recovers_without_hash_bypass_or_extra_cache() {
    let dir = TempDir::new("stream-partials");
    write_content(&dir.file("movie.bin"), 8 << 20, 41, 0).unwrap();
    let piece: i64 = 256 << 10;
    let torrent = make_torrent(&dir.file("movie.bin"), piece as i32, Version::V1);
    let mut config = local_config(&dir.file("cache"), (4 * 256 + 64) << 10);
    config.read_ahead_seconds = 1;
    let e = Engine::new(config).unwrap();
    let mut stalled = vec![PartialSeeder::new(32, piece, 41)];
    let id = open(&e, &torrent, &[stalled[0].address()]);
    e.wait_metadata(id, Duration::from_secs(2), None).unwrap();
    e.select_file(id, 0).unwrap();
    let reader = e.open_reader(id, 0).unwrap();
    let mut pressure = false;
    let mut byte = [0u8; 1];
    for seek in 0..6i64 {
        if pressure {
            break;
        }
        if seek > 0 {
            stalled.push(PartialSeeder::new(32, piece, 41));
            e.add_peers(id, &[stalled.last().unwrap().address()]).unwrap();
        }
        let before = e.stats(id).unwrap().total_download;
        let until = Instant::now() + Duration::from_secs(10);
        while Instant::now() < until {
            assert_eq!(e.read(reader, seek * 5 * piece, &mut byte, Duration::from_millis(20)).unwrap(), WOULD_BLOCK);
            let st = e.stats(id).unwrap();
            pressure = st.urgent_reservation_failures > 0 && st.obsolete_partials > 0;
            if pressure || st.total_download > before {
                break;
            }
        }
    }
    assert!(pressure, "{}", e.stats(id).unwrap().to_json());
    assert_eq!(e.stats(id).unwrap().hash_failures, 0);
    let metadata = e.metainfo(id).unwrap();
    e.remove(id).unwrap();
    assert!(std::fs::read_dir(dir.file("cache")).unwrap().next().is_none());

    let healthy = Seeder::single(&torrent, dir.path(), &SeederOptions::default());
    let id = open(&e, &metadata, &[healthy.address()]);
    e.wait_metadata(id, Duration::from_secs(2), None).unwrap();
    e.select_file(id, 0).unwrap();
    e.set_demand(id, 4_000_000, 1).unwrap();
    let reader = e.open_reader(id, 0).unwrap();
    let until = Instant::now() + Duration::from_secs(10);
    let count = loop {
        let n = e.read(reader, 6 << 20, &mut byte, Duration::from_millis(200)).unwrap();
        if n != WOULD_BLOCK {
            break n;
        }
        assert!(Instant::now() < until);
    };
    assert_eq!(count, 1);
    assert!(first_mismatch(41, 6 << 20, &byte).is_none());
    assert_eq!(e.stats(id).unwrap().verified_read_bytes, 1);
    assert_eq!(e.stats(id).unwrap().hash_failures, 0);
}
