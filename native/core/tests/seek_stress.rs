//! A host model of the emulator's thirty-minute soak (`simulator.sh soak`): many seeks through a small cache against
//! a rate-limited loopback seeder, with readers closed and reopened on every seek as Media3 does. Each seek must
//! deliver verified bytes well within the soak's 30-second limit.
//!
//! The defaults match the simulator: 1 MiB pieces, a 16 MiB cache (15 slots), a 4 MiB/s seeder and a 12 Mbit/s film.
//! `STREAM_SEEK_STRESS_SEEKS` overrides the number of seeks (200 by default for the ignored full run).

use std::sync::Arc;
use std::time::{Duration, Instant};
use stream_core::bt::metainfo::Version;
use stream_core::engine::{Config, Engine, WOULD_BLOCK};
use stream_core::fixtures::{first_mismatch, make_torrent, write_content, Seeder, SeederOptions, TempDir};
use stream_core::swarm::Source;

const PIECE: i32 = 1 << 20;
/// The soak's per-seek limit.
const SEEK_LIMIT: Duration = Duration::from_secs(30);

fn config(cache_dir: &std::path::Path) -> Config {
    Config {
        cache_dir: cache_dir.to_string_lossy().into_owned(),
        cache_bytes: 16 << 20,
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

struct Outcome {
    /// Time from opening the reader to its first verified byte, per seek.
    first_byte: Vec<Duration>,
    evicted: i64,
    slots: i32,
    most_allocated: i64,
}

/// Seeks `seeks` times through a `size`-byte film, reading `play` bytes after each, and checks every byte.
fn soak(version: Version, size: i64, seeks: usize, play: i64, rate: u64) -> Outcome {
    let dir = TempDir::new("stream-seek-stress");
    let seed_value = 77;
    write_content(&dir.file("film.mp4"), size, seed_value, 0).unwrap();
    let torrent = make_torrent(&dir.file("film.mp4"), PIECE, version);
    let seeder = Seeder::single(&torrent, dir.path(), &SeederOptions { upload_rate: rate, ..SeederOptions::default() });
    let e = Engine::new(config(&dir.file("cache"))).unwrap();
    let t = e.add(&Source { metainfo: torrent, peers: vec![seeder.address()], ..Source::default() }).unwrap();
    e.wait_metadata(t, Duration::from_secs(10), None).unwrap();
    e.select_file(t, 0).unwrap();
    // The app reports the film's bitrate once known: 12 Mbit/s.
    e.set_bitrate(t, 1_500_000).unwrap();

    let mut x: u64 = 0x5eed;
    let mut outcome = Outcome { first_byte: Vec::new(), evicted: 0, slots: 0, most_allocated: 0 };
    let mut buf = vec![0u8; 64 << 10];
    let mut reader = e.open_reader(t, 0).unwrap();
    for seek in 0..seeks {
        // The soak's pattern (35 s, 1 s and 15 s of a 45 s film) alternating with pseudo-random positions.
        let target = if seek % 2 == 0 {
            [35 * size / 45, size / 45, 15 * size / 45][(seek / 2) % 3]
        } else {
            x = x.wrapping_mul(6_364_136_223_846_793_005).wrapping_add(1_442_695_040_888_963_407);
            ((x >> 16) % (size - play) as u64) as i64
        };
        // Media3 seeks by closing its data source and opening another at the new position.
        e.close_reader(reader);
        reader = e.open_reader(t, 0).unwrap();
        let opened = Instant::now();
        let mut pos = target;
        let end = (target + play).min(size);
        let mut first = None;
        while pos < end {
            let want = (buf.len() as i64).min(end - pos) as usize;
            let n = e.read(reader, pos, &mut buf[..want], Duration::from_millis(250)).unwrap();
            if n == WOULD_BLOCK {
                let st = e.stats(t).unwrap();
                assert!(
                    opened.elapsed() < SEEK_LIMIT,
                    "seek {seek} to {target} stalled at {pos} for {:?}: {}",
                    opened.elapsed(),
                    st.to_json()
                );
                continue;
            }
            assert!(n > 0, "unexpected end at {pos}");
            if let Some(bad) = first_mismatch(seed_value, pos, &buf[..n as usize]) {
                panic!("seek {seek}: wrong byte at {}", pos + bad as i64);
            }
            first.get_or_insert_with(|| opened.elapsed());
            pos += i64::from(n);
        }
        outcome.first_byte.push(first.unwrap_or_default());
        let st = e.stats(t).unwrap();
        assert!(st.cache.used <= st.cache.slots, "{}", st.to_json());
        assert!(st.cache.allocated_bytes <= 16 << 20, "{}", st.to_json());
        assert_eq!(st.hash_failures, 0);
        outcome.most_allocated = outcome.most_allocated.max(st.cache.allocated_bytes);
    }
    e.close_reader(reader);
    let st = e.stats(t).unwrap();
    outcome.evicted = st.cache.evicted;
    outcome.slots = st.cache.slots;
    assert_eq!(st.peers, 1, "the seeder stays connected through every eviction: {}", st.to_json());
    e.remove(t).unwrap();
    outcome
}

fn report(name: &str, o: &Outcome) {
    let mut sorted = o.first_byte.clone();
    sorted.sort();
    let at = |q: f64| sorted[((sorted.len() - 1) as f64 * q) as usize];
    println!(
        "{name}: {} seeks, first byte p50 {:?} p95 {:?} max {:?}; {} evictions through {} slots, at most {} bytes allocated",
        sorted.len(),
        at(0.5),
        at(0.95),
        at(1.0),
        o.evicted,
        o.slots,
        o.most_allocated
    );
}

fn seeks(default: usize) -> usize {
    std::env::var("STREAM_SEEK_STRESS_SEEKS").ok().and_then(|s| s.parse().ok()).unwrap_or(default)
}

/// Part of the routine suite: enough seeks to turn the cache over many times.
#[test]
fn many_seeks_through_a_small_cache_never_stall() {
    let o = soak(Version::V1, 64 << 20, seeks(40), 2 << 20, 16 << 20);
    report("v1", &o);
    assert!(o.evicted >= 3 * i64::from(o.slots));
    assert!(o.first_byte.iter().all(|d| *d < Duration::from_secs(10)));
}

#[test]
fn many_seeks_in_v2_and_hybrid_torrents_never_stall() {
    for v in [Version::V2, Version::Hybrid] {
        let o = soak(v, 48 << 20, seeks(15), 2 << 20, 16 << 20);
        report(if v == Version::V2 { "v2" } else { "hybrid" }, &o);
        assert!(o.evicted >= i64::from(o.slots));
    }
}

/// The emulator soak's shape at the simulator seeder's 4 MiB/s: about 200 seeks of a 45-second, 12 Mbit/s film,
/// three seconds of playback after each. Takes several minutes, so it runs only when asked:
/// `./native/build-host.sh release --ignored soak_shaped`.
#[test]
#[ignore]
fn soak_shaped_seeks_at_the_simulator_rate() {
    let o = soak(Version::Hybrid, 68 << 20, seeks(200), 4_500_000, 4 << 20);
    report("soak-shaped", &o);
    assert!(o.first_byte.iter().all(|d| *d < SEEK_LIMIT));
}

/// Media3 seeks by closing its reader and opening another, which reads at the new position a moment later. Until it
/// reads (or seeks), the new reader must not pull the read-ahead window back to the start of the file: that would
/// queue requests for the wrong pieces ahead of the new position's and evict pieces the player still wants.
#[test]
fn a_reopened_reader_keeps_the_window_until_it_reads() {
    let dir = TempDir::new("stream-reopen");
    let size: i64 = 40 << 20;
    write_content(&dir.file("film.mp4"), size, 78, 0).unwrap();
    let torrent = make_torrent(&dir.file("film.mp4"), PIECE, Version::V1);
    let seeder = Seeder::single(&torrent, dir.path(), &SeederOptions::default());
    let e = Engine::new(config(&dir.file("cache"))).unwrap();
    let t = e.add(&Source { metainfo: torrent, peers: vec![seeder.address()], ..Source::default() }).unwrap();
    e.wait_metadata(t, Duration::from_secs(10), None).unwrap();
    e.select_file(t, 0).unwrap();
    e.set_bitrate(t, 1_500_000).unwrap();
    let r = e.open_reader(t, 0).unwrap();
    let middle = 24 << 20;
    let mut byte = [0u8; 1];
    let deadline = Instant::now() + Duration::from_secs(20);
    while e.read(r, middle, &mut byte, Duration::from_millis(250)).unwrap() == WOULD_BLOCK {
        assert!(Instant::now() < deadline, "no data at {middle}");
    }
    // Let the window around the reader fill, until nothing more downloads.
    let mut last = e.stats(t).unwrap().total_download;
    let mut quiet = Instant::now();
    while quiet.elapsed() < Duration::from_millis(800) {
        assert!(Instant::now() < deadline + Duration::from_secs(20), "the window never settled");
        std::thread::sleep(Duration::from_millis(100));
        let now = e.stats(t).unwrap().total_download;
        if now != last {
            last = now;
            quiet = Instant::now();
        }
    }
    e.close_reader(r);
    let reopened = e.open_reader(t, 0).unwrap();
    std::thread::sleep(Duration::from_millis(1500));
    let st = e.stats(t).unwrap();
    assert!(
        st.total_download - last < i64::from(PIECE),
        "an unread reader moved the window: {} more bytes downloaded, {}",
        st.total_download - last,
        st.to_json()
    );
    // Its first read starts the window where it reads.
    let deadline = Instant::now() + Duration::from_secs(20);
    while e.read(reopened, 4 << 20, &mut byte, Duration::from_millis(250)).unwrap() == WOULD_BLOCK {
        assert!(Instant::now() < deadline);
    }
    assert!(first_mismatch(78, 4 << 20, &byte).is_none());
}
