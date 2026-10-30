//! Can librqbit replace libtorrent for Stream? Downloads each film fixture (v1, hybrid, pure v2) from the local
//! loopback seeder only — no DHT, trackers or LAN discovery — and compares the bytes with the seeder's source file.
//!
//!   cargo run --release -- <simulator/.cache> [seeder address]

use anyhow::{Context, Result};
use librqbit::{AddTorrent, AddTorrentOptions, Session, SessionOptions};
use sha2::{Digest, Sha256};
use std::net::SocketAddr;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

const FIXTURES: [&str; 3] = ["film-v1", "film-hybrid", "film-v2"];
const FILE: &str = "Stream Test Film (2026).mp4";

fn sha256(path: &Path) -> Result<String> {
    let bytes = std::fs::read(path).with_context(|| format!("read {}", path.display()))?;
    Ok(Sha256::digest(bytes).iter().map(|b| format!("{b:02x}")).collect())
}

async fn download(session: &std::sync::Arc<Session>, torrent: &Path, out: &Path, seeder: SocketAddr) -> Result<PathBuf> {
    let bytes = std::fs::read(torrent)?;
    let options = AddTorrentOptions {
        output_folder: Some(out.to_string_lossy().into_owned()),
        overwrite: true,
        disable_trackers: true,
        initial_peers: Some(vec![seeder]),
        ..Default::default()
    };
    let handle = session
        .add_torrent(AddTorrent::from_bytes(bytes), Some(options))
        .await?
        .into_handle()
        .context("no torrent handle")?;
    tokio::time::timeout(Duration::from_secs(120), handle.wait_until_completed())
        .await
        .context("not complete within 120 s")??;
    Ok(out.join(FILE))
}

#[tokio::main]
async fn main() -> Result<()> {
    let mut args = std::env::args().skip(1);
    let cache = PathBuf::from(args.next().context("usage: stream-eval-rqbit <simulator/.cache> [seeder]")?);
    let seeder: SocketAddr = args.next().unwrap_or_else(|| "127.0.0.1:58134".into()).parse()?;
    let expected = sha256(&cache.join("media").join(FILE))?;
    let work = std::env::temp_dir().join(format!("stream-eval-rqbit-{}", std::process::id()));

    let mut failures = 0;
    for name in FIXTURES {
        let out = work.join(name);
        std::fs::create_dir_all(&out)?;
        // A fresh session per fixture: no state carries over between versions.
        let options = SessionOptions {
            dht: None,
            disable_trackers: true,
            disable_local_service_discovery: true,
            persistence: None,
            fastresume: false,
            ..Default::default()
        };
        let session = Session::new_with_opts(out.clone(), options).await?;
        let started = Instant::now();
        let result = download(&session, &cache.join("torrents").join(format!("{name}.torrent")), &out, seeder).await;
        let verdict = match result.and_then(|file| sha256(&file)) {
            Ok(hash) if hash == expected => format!("PASS bytes match ({:.1} s)", started.elapsed().as_secs_f64()),
            Ok(hash) => {
                failures += 1;
                format!("FAIL wrong bytes: {hash}")
            }
            Err(e) => {
                failures += 1;
                format!("FAIL {e:#}")
            }
        };
        println!("{name:12} {verdict}");
        session.stop().await;
    }
    let _ = std::fs::remove_dir_all(&work);
    println!("{} of {} fixtures downloaded intact", FIXTURES.len() - failures, FIXTURES.len());
    Ok(())
}
