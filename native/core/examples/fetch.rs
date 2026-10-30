//! Streams a whole file from given peers through the engine and prints its SHA-256: an interoperability check
//! against other BitTorrent implementations on loopback.
//!
//!   cargo run --example fetch -- <file.torrent | magnet> <peer | -> [file-index]
//!
//! STREAM_TCP=0 connects over uTP only; STREAM_ENCRYPTION=forced|disabled sets MSE (default: tried first).
//! STREAM_CA_FILE trusts a PEM file's roots for HTTPS (trackers and web seeds) instead of the bundled ones; certificates
//! are always verified. STREAM_STALL_SECONDS (default 30) is how long metadata or data may take to arrive. `-` as the
//! peer contacts no peer, for web-seed-only checks.

use sha2::{Digest, Sha256};
use std::time::{Duration, Instant};
use stream_core::engine::{Config, Engine, WOULD_BLOCK};
use stream_core::swarm::Source;

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let (metainfo, magnet) = match args[0].starts_with("magnet:") {
        true => (Vec::new(), args[0].clone()),
        false => (std::fs::read(&args[0]).expect("torrent"), String::new()),
    };
    let file: i32 = args.get(2).and_then(|f| f.parse().ok()).unwrap_or(0);
    let stall = Duration::from_secs(std::env::var("STREAM_STALL_SECONDS").ok().and_then(|s| s.parse().ok()).unwrap_or(30));
    let peers = if args[1] == "-" { Vec::new() } else { vec![args[1].clone()] };
    let dir = std::env::temp_dir().join(format!("stream-fetch-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let config = Config {
        cache_dir: dir.to_string_lossy().into_owned(),
        cache_bytes: 32 << 20,
        reserve_bytes: 0,
        listen_interfaces: "127.0.0.1:0".into(),
        dht: false,
        lsd: false,
        pex: false,
        multiple_connections_per_ip: true,
        log: Some(std::sync::Arc::new(|l: &str| eprintln!("log: {l}"))),
        tcp: std::env::var("STREAM_TCP").map_or(true, |v| v != "0"),
        encryption: match std::env::var("STREAM_ENCRYPTION").as_deref() {
            Ok("forced") => stream_core::bt::mse::Policy::Forced,
            Ok("disabled") => stream_core::bt::mse::Policy::Disabled,
            _ => stream_core::bt::mse::Policy::Enabled,
        },
        ca_file: std::env::var("STREAM_CA_FILE").unwrap_or_default(),
        ..Config::default()
    };
    let engine = Engine::new(config).expect("engine");
    let started = Instant::now();
    let t = engine.add(&Source { metainfo, magnet, peers, ..Source::default() }).expect("add");
    let files = match engine.wait_metadata(t, stall, None) {
        Ok(files) => files,
        Err(e) => {
            eprintln!("no metadata: {e}");
            std::process::exit(1);
        }
    };
    let size = files.iter().find(|f| f.index == file).expect("file").size;
    engine.select_file(t, file).unwrap();
    engine.set_bitrate(t, 20 << 20).unwrap();
    let r = engine.open_reader(t, file).unwrap();
    let mut hasher = Sha256::new();
    let mut pos = 0i64;
    let mut buf = vec![0u8; 1 << 20];
    let mut progress = Instant::now();
    while pos < size {
        let n = engine.read(r, pos, &mut buf, Duration::from_secs(5)).expect("read");
        if n == WOULD_BLOCK {
            // As the app does: wait again, giving up only when nothing arrives for a while.
            if progress.elapsed() > stall {
                eprintln!("stalled at {pos}: {}", engine.stats(t).unwrap().to_json());
                std::process::exit(1);
            }
            continue;
        }
        progress = Instant::now();
        hasher.update(&buf[..n as usize]);
        pos += i64::from(n);
    }
    let digest: String = hasher.finalize().iter().map(|b| format!("{b:02x}")).collect();
    println!("{digest} {size} bytes in {:.1} s", started.elapsed().as_secs_f64());
    println!("{}", engine.stats(t).unwrap().to_json());
    engine.close_reader(r);
    engine.remove(t).unwrap();
    drop(engine);
    let _ = std::fs::remove_dir_all(dir);
}
