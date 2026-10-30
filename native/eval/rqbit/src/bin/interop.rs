//! librqbit as an independent peer for loopback interoperability checks with Stream's engine (`native/eval/interop.sh`).
//! DHT, trackers, local discovery and port mapping are always off; peers are only the addresses given.
//!
//!   interop fetch <file.torrent | magnet> <peer> <out-dir> [tcp|utp]   download, print the first file's SHA-256
//!   interop seed <file.torrent> <save-dir> <listen 127.0.0.1:port> [tcp|utp|both]   seed until killed

use anyhow::{bail, Context, Result};
use librqbit::{AddTorrent, AddTorrentOptions, ConnectionOptions, ListenerMode, ListenerOptions, Session, SessionOptions};
use sha2::{Digest, Sha256};
use std::net::SocketAddr;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{Duration, Instant};

fn mode(arg: Option<&String>) -> Result<ListenerMode> {
    Ok(match arg.map(String::as_str) {
        None | Some("tcp") => ListenerMode::TcpOnly,
        Some("utp") => ListenerMode::UtpOnly,
        Some("both") => ListenerMode::TcpAndUtp,
        Some(other) => bail!("unknown transport {other}"),
    })
}

async fn session(out: &Path, listen: Option<SocketAddr>, mode: ListenerMode) -> Result<Arc<Session>> {
    let options = SessionOptions {
        dht: None,
        disable_trackers: true,
        disable_local_service_discovery: true,
        persistence: None,
        fastresume: false,
        ipv4_only: true,
        listen: Some(ListenerOptions {
            mode,
            listen_addr: listen.unwrap_or_else(|| "127.0.0.1:0".parse().unwrap()),
            enable_upnp_port_forwarding: false,
            ipv4_only: true,
            ..Default::default()
        }),
        connect: Some(ConnectionOptions { enable_tcp: mode.tcp_enabled(), ..Default::default() }),
        ..Default::default()
    };
    Session::new_with_opts(out.to_path_buf(), options).await
}

fn sha256(path: &Path) -> Result<String> {
    let bytes = std::fs::read(path).with_context(|| format!("read {}", path.display()))?;
    Ok(Sha256::digest(bytes).iter().map(|b| format!("{b:02x}")).collect())
}

/// The first regular file under a directory, depth first, for single- and multi-file torrents alike.
fn first_file(dir: &Path) -> Option<PathBuf> {
    let mut entries: Vec<_> = std::fs::read_dir(dir).ok()?.flatten().map(|e| e.path()).collect();
    entries.sort();
    for p in &entries {
        if p.is_file() {
            return Some(p.clone());
        }
    }
    entries.iter().filter(|p| p.is_dir()).find_map(|p| first_file(p))
}

async fn fetch(source: &str, peer: SocketAddr, out: &Path, mode: ListenerMode) -> Result<()> {
    std::fs::create_dir_all(out)?;
    let s = session(out, None, mode).await?;
    let add = if source.starts_with("magnet:") {
        AddTorrent::from_url(source)
    } else {
        AddTorrent::from_bytes(std::fs::read(source)?)
    };
    let options = AddTorrentOptions {
        output_folder: Some(out.to_string_lossy().into_owned()),
        overwrite: true,
        disable_trackers: true,
        initial_peers: Some(vec![peer]),
        ..Default::default()
    };
    let started = Instant::now();
    let handle = s.add_torrent(add, Some(options)).await?.into_handle().context("no torrent handle")?;
    tokio::time::timeout(Duration::from_secs(120), handle.wait_until_completed())
        .await
        .context("not complete within 120 s")??;
    let file = first_file(out).context("no file downloaded")?;
    println!("{} {} bytes in {:.1} s", sha256(&file)?, std::fs::metadata(&file)?.len(), started.elapsed().as_secs_f64());
    s.stop().await;
    Ok(())
}

async fn seed(torrent: &Path, save: &Path, listen: SocketAddr, mode: ListenerMode) -> Result<()> {
    let s = session(save, Some(listen), mode).await?;
    let options = AddTorrentOptions {
        output_folder: Some(save.to_string_lossy().into_owned()),
        overwrite: true,
        disable_trackers: true,
        ..Default::default()
    };
    let handle = s
        .add_torrent(AddTorrent::from_bytes(std::fs::read(torrent)?), Some(options))
        .await?
        .into_handle()
        .context("no torrent handle")?;
    handle.wait_until_initialized().await?;
    let stats = handle.stats();
    if stats.progress_bytes != stats.total_bytes {
        bail!("seed data incomplete: {} of {} bytes verified", stats.progress_bytes, stats.total_bytes);
    }
    println!("seeding on {}", s.listen_addr().map(|a| a.to_string()).unwrap_or_default());
    // Until the harness kills the process.
    loop {
        tokio::time::sleep(Duration::from_secs(3600)).await;
    }
}

#[tokio::main]
async fn main() -> Result<()> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    match args.first().map(String::as_str) {
        Some("fetch") if args.len() >= 4 => fetch(&args[1], args[2].parse()?, Path::new(&args[3]), mode(args.get(4))?).await,
        Some("seed") if args.len() >= 4 => seed(Path::new(&args[1]), Path::new(&args[2]), args[3].parse()?, mode(args.get(4))?).await,
        _ => bail!("usage: interop fetch <torrent|magnet> <peer> <out-dir> [tcp|utp] | seed <torrent> <save-dir> <listen> [tcp|utp|both]"),
    }
}
