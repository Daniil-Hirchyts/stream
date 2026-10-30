//! Peer connections: the handshake, then a reader that hands messages to the torrent and a writer fed by the
//! torrent through a channel.

use super::session::SessionShared;
use super::torrent::{Out, Torrent};
use super::wire::{self, Handshake, Reserved};
use std::net::SocketAddr;
use std::sync::atomic::Ordering;
use std::sync::Arc;
use std::time::Duration;
use tokio::io::AsyncWriteExt;
use tokio::net::tcp::OwnedWriteHalf;
use tokio::net::TcpStream;
use tokio::sync::{mpsc, Notify};
use tokio::time::timeout;

const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(10);
/// Peers send keep-alives every two minutes at the latest.
const IDLE_TIMEOUT: Duration = Duration::from_secs(150);

/// Counts a connection for the session's limit while it lives.
struct Slot(Arc<SessionShared>);

impl Drop for Slot {
    fn drop(&mut self) {
        self.0.connections.fetch_sub(1, Ordering::SeqCst);
    }
}

pub async fn connect(session: Arc<SessionShared>, torrent: Arc<Torrent>, addr: SocketAddr) {
    let slot = Slot(session.clone());
    session.connections.fetch_add(1, Ordering::SeqCst);
    let hashes = torrent.lock().hashes;
    let id = hashes.id();
    let result = async {
        let mut stream = timeout(HANDSHAKE_TIMEOUT, TcpStream::connect(addr)).await.map_err(|_| "connect timed out".to_string())?.map_err(|e| e.to_string())?;
        let _ = stream.set_nodelay(true);
        let ours = Handshake { reserved: Reserved::ours(session.dht_enabled(), hashes.v2.is_some()), info_hash: id, peer_id: session.peer_id };
        stream.write_all(&ours.encode()).await.map_err(|e| e.to_string())?;
        let (reserved, hash) = timeout(HANDSHAKE_TIMEOUT, Handshake::read_start(&mut stream)).await.map_err(|_| "handshake timed out".to_string())?.map_err(|e| e.to_string())?;
        // A hybrid's peer may answer with the v2 hash: the upgrade of BEP 52.
        if !hashes.matches(&hash) {
            return Err("peer answered for another torrent".into());
        }
        let peer_id = timeout(HANDSHAKE_TIMEOUT, Handshake::read_peer_id(&mut stream)).await.map_err(|_| "handshake timed out".to_string())?.map_err(|e| e.to_string())?;
        Ok::<_, String>((stream, reserved, peer_id, hashes.v2_id() == Some(hash)))
    }
    .await;
    match result {
        Ok((stream, reserved, peer_id, v2)) if peer_id != session.peer_id => {
            run(session, torrent, stream, addr, reserved, v2, false, slot).await;
        }
        Ok(_) => torrent.dial_failed(addr),
        Err(e) => {
            torrent.dial_failed(addr);
            session.log(format!("peer {addr} unreachable: {e}"));
        }
    }
}

pub async fn accept(session: Arc<SessionShared>, mut stream: TcpStream, addr: SocketAddr) {
    if !session.policy.allows(&addr) || session.connections.load(Ordering::SeqCst) >= session.connections_limit {
        return;
    }
    let slot = Slot(session.clone());
    session.connections.fetch_add(1, Ordering::SeqCst);
    let _ = stream.set_nodelay(true);
    let Ok(Ok((reserved, hash))) = timeout(HANDSHAKE_TIMEOUT, Handshake::read_start(&mut stream)).await else { return };
    let Some(torrent) = session.find(&hash) else { return };
    let hashes = torrent.lock().hashes;
    // A peer that supports v2 and connects to a hybrid by its v1 hash is answered with the v2 hash (BEP 52).
    let answer = match hashes.v2_id() {
        Some(v2) if reserved.v2() => v2,
        _ => hash,
    };
    let ours = Handshake { reserved: Reserved::ours(session.dht_enabled(), hashes.v2.is_some()), info_hash: answer, peer_id: session.peer_id };
    if stream.write_all(&ours.encode()).await.is_err() {
        return;
    }
    let Ok(Ok(peer_id)) = timeout(HANDSHAKE_TIMEOUT, Handshake::read_peer_id(&mut stream)).await else { return };
    if peer_id == session.peer_id {
        return;
    }
    let v2 = hashes.v2_id() == Some(answer);
    run(session, torrent, stream, addr, reserved, v2, true, slot).await;
}

async fn run(
    session: Arc<SessionShared>,
    torrent: Arc<Torrent>,
    stream: TcpStream,
    addr: SocketAddr,
    reserved: Reserved,
    v2: bool,
    incoming: bool,
    _slot: Slot,
) {
    let (tx, rx) = mpsc::unbounded_channel();
    let limits = (session.connections_limit, session.multiple_per_ip);
    let Some(key) = torrent.attach(addr, reserved, v2, incoming, tx, limits) else { return };
    let (mut reader, writer) = stream.into_split();
    let closed = Arc::new(Notify::new());
    let writer_task = tokio::spawn(write_loop(session.clone(), writer, rx, closed.clone()));
    let mut buf = Vec::new();
    let reason = loop {
        tokio::select! {
            m = timeout(IDLE_TIMEOUT, wire::read_message(&mut reader, &mut buf)) => match m {
                Err(_) => break "idle".to_string(),
                Ok(Err(e)) => break e.to_string(),
                Ok(Ok(msg)) => {
                    if let Err(e) = torrent.on_message(key, msg) {
                        break e;
                    }
                }
            },
            _ = closed.notified() => break String::new(),
        }
    };
    torrent.detach(key, &reason);
    writer_task.abort();
}

async fn write_loop(session: Arc<SessionShared>, mut w: OwnedWriteHalf, mut rx: mpsc::UnboundedReceiver<Out>, closed: Arc<Notify>) {
    let mut out = Vec::with_capacity(64 * 1024);
    'outer: while let Some(first) = rx.recv().await {
        let mut next = Some(first);
        while let Some(item) = next.take() {
            match item {
                Out::Msg(m) => m.encode(&mut out),
                Out::Upload(m) => {
                    if !out.is_empty() && w.write_all(&out).await.is_err() {
                        break 'outer;
                    }
                    out.clear();
                    m.encode(&mut out);
                    session.upload_limit.acquire(out.len()).await;
                }
                Out::Close(_) => {
                    let _ = w.write_all(&out).await;
                    break 'outer;
                }
            }
            if out.len() < 64 * 1024 {
                next = rx.try_recv().ok();
            }
        }
        if w.write_all(&out).await.is_err() {
            break;
        }
        out.clear();
    }
    let _ = w.shutdown().await;
    closed.notify_one();
}

/// Session-wide upload limit, a token bucket refilled every 100 ms. Zero means unlimited.
pub struct UploadLimit {
    rate: u64,
    tokens: tokio::sync::Mutex<(f64, tokio::time::Instant)>,
}

impl UploadLimit {
    pub fn new(rate: u64) -> Self {
        UploadLimit { rate, tokens: tokio::sync::Mutex::new((rate as f64, tokio::time::Instant::now())) }
    }

    pub async fn acquire(&self, bytes: usize) {
        if self.rate == 0 {
            return;
        }
        let mut t = self.tokens.lock().await;
        loop {
            let now = tokio::time::Instant::now();
            let elapsed = now.duration_since(t.1).as_secs_f64();
            t.0 = (t.0 + elapsed * self.rate as f64).min(self.rate as f64 + bytes as f64);
            t.1 = now;
            if t.0 >= bytes as f64 {
                t.0 -= bytes as f64;
                return;
            }
            let wait = (bytes as f64 - t.0) / self.rate as f64;
            tokio::time::sleep(Duration::from_secs_f64(wait.min(1.0))).await;
        }
    }
}
