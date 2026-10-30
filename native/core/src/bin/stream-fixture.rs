//! stream-fixture: test fixtures for Stream, from the same Rust code as the app.
//!
//!   stream-fixture content <path> <bytes> <seed>
//!   stream-fixture create <out.torrent> <file-or-dir> <piece-bytes> [v1|v2|hybrid]
//!   stream-fixture info <file.torrent>
//!   stream-fixture seed [--listen 127.0.0.1:58134] [--rate bytes/s] [--no-tcp|--no-utp] [--super-seed] [--peer address:port] <file.torrent> <save-path> [...]
//!
//! `seed` runs a real BitTorrent seeder with DHT, local discovery, port mapping and trackers off, until SIGINT or
//! SIGTERM; peers reach it only at the address given, or it dials the `--peer` given (for a client that can't be told
//! about a peer on loopback).

use std::path::{Path, PathBuf};
use std::process::ExitCode;
use stream_core::bt::metainfo;
use stream_core::fixtures::{self, Seeder, SeederOptions};

fn usage() -> ExitCode {
    eprintln!(
        "usage:\n  stream-fixture content <path> <bytes> <seed>\n  stream-fixture create <out.torrent> <file-or-dir> <piece-bytes> [v1|v2|hybrid]\n  stream-fixture info <file.torrent>\n  stream-fixture seed [--listen 127.0.0.1:58134] [--rate bytes/s] [--no-tcp|--no-utp] [--super-seed] [--peer address:port] <file.torrent> <save-path> [...]"
    );
    ExitCode::from(2)
}

fn info(path: &Path) -> Result<(), String> {
    let data = std::fs::read(path).map_err(|e| format!("cannot read {}: {e}", path.display()))?;
    println!("{}", fixtures::info_json(&data)?);
    Ok(())
}

fn seed(args: &[String]) -> Result<(), String> {
    let mut options = SeederOptions { listen: "127.0.0.1:58134".into(), ..SeederOptions::default() };
    let mut torrents = Vec::new();
    let mut peers = Vec::new();
    let mut i = 0;
    while i < args.len() {
        match args[i].as_str() {
            "--listen" if i + 1 < args.len() => {
                options.listen = args[i + 1].clone();
                i += 2;
            }
            "--rate" if i + 1 < args.len() => {
                options.upload_rate = args[i + 1].parse().map_err(|_| "invalid rate")?;
                i += 2;
            }
            // One transport only: a client then has to use the other one to reach this seeder.
            "--no-tcp" => {
                options.tcp = false;
                i += 1;
            }
            "--super-seed" => {
                options.super_seed = true;
                i += 1;
            }
            "--no-utp" => {
                options.utp = false;
                i += 1;
            }
            "--peer" if i + 1 < args.len() => {
                peers.push(stream_core::swarm::parse_peer(&args[i + 1]).map_err(|e| e.message)?);
                i += 2;
            }
            _ if i + 1 < args.len() => {
                let data = std::fs::read(&args[i]).map_err(|e| format!("cannot read {}: {e}", args[i]))?;
                torrents.push((data, PathBuf::from(&args[i + 1])));
                i += 2;
            }
            _ => return Err("usage".into()),
        }
    }
    if torrents.is_empty() {
        return Err("usage".into());
    }
    let seeder = Seeder::new(&torrents, &options)?;
    for ((_, save), t) in torrents.iter().zip(&seeder.torrents) {
        let hashes = t.lock().hashes;
        let hash = hashes.v1.map(|h| metainfo::hex(&h)).or(hashes.v2.map(|h| metainfo::hex(&h))).unwrap_or_default();
        println!("seeding {hash} from {}", save.display());
        t.add_candidates(peers.iter().copied());
    }
    println!("listening on port {}", seeder.port());
    // Runs until SIGINT or SIGTERM.
    let mut set: libc::sigset_t = unsafe { std::mem::zeroed() };
    let mut signal = 0;
    // SAFETY: plain signal-set calls on a local set; blocking the signals lets sigwait receive them.
    unsafe {
        libc::sigemptyset(&mut set);
        libc::sigaddset(&mut set, libc::SIGINT);
        libc::sigaddset(&mut set, libc::SIGTERM);
        libc::pthread_sigmask(libc::SIG_BLOCK, &set, std::ptr::null_mut());
        libc::sigwait(&set, &mut signal);
    }
    drop(seeder);
    Ok(())
}

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let result = match args.first().map(String::as_str) {
        Some("content") if args.len() == 4 => {
            let size: i64 = args[2].parse().unwrap_or(-1);
            let seed: u32 = args[3].parse().unwrap_or(0);
            if size < 0 {
                return usage();
            }
            fixtures::write_content(Path::new(&args[1]), size, seed, 0).map_err(|e| e.to_string())
        }
        Some("create") if args.len() == 4 || args.len() == 5 => {
            let version = fixtures::parse_version(args.get(4).map(String::as_str).unwrap_or("hybrid"));
            let (Some(version), Ok(piece)) = (version, args[3].parse::<i32>()) else { return usage() };
            metainfo::create(Path::new(&args[2]), piece, version)
                .map_err(|e| e.to_string())
                .and_then(|data| std::fs::write(&args[1], data).map_err(|e| e.to_string()))
                .and_then(|()| info(Path::new(&args[1])))
        }
        Some("info") if args.len() == 2 => info(Path::new(&args[1])),
        Some("seed") => seed(&args[1..]),
        _ => return usage(),
    };
    match result {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) if e == "usage" => usage(),
        Err(e) => {
            eprintln!("stream-fixture: {e}");
            ExitCode::FAILURE
        }
    }
}
