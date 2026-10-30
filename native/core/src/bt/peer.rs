//! Peer connections over TCP or uTP, optionally behind MSE: the handshake, then a reader that hands messages to the
//! torrent and a writer fed by the torrent through a channel.

use super::metainfo::{Id20, InfoHashes};
use super::mse::{self, Policy as Encryption};
use super::session::SessionShared;
use super::torrent::{Out, Torrent};
use super::wire::{self, Handshake, Reserved, PROTOCOL};
use std::net::SocketAddr;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::Duration;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, WriteHalf};
use tokio::net::TcpStream;
use tokio::sync::{mpsc, Notify};
use tokio::time::timeout;

const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(10);
/// Peers send keep-alives every two minutes at the latest.
const IDLE_TIMEOUT: Duration = Duration::from_secs(150);

/// Any transport a peer speaks over: TCP or uTP, with or without MSE.
pub trait Conn: AsyncRead + AsyncWrite + Unpin + Send + 'static {}
impl<T: AsyncRead + AsyncWrite + Unpin + Send + 'static> Conn for T {}
pub type BoxConn = Box<dyn Conn>;

/// Counts a connection for the session's limit while it lives.
struct Slot(Arc<SessionShared>);

impl Drop for Slot {
    fn drop(&mut self) {
        self.0.connections.fetch_sub(1, Ordering::SeqCst);
    }
}

async fn tcp(addr: SocketAddr) -> Result<BoxConn, String> {
    let stream = timeout(HANDSHAKE_TIMEOUT, TcpStream::connect(addr))
        .await
        .map_err(|_| "connect timed out".to_string())?
        .map_err(|e| e.to_string())?;
    let _ = stream.set_nodelay(true);
    Ok(Box::new(stream))
}

async fn utp(session: &SessionShared, addr: SocketAddr) -> Result<BoxConn, String> {
    let socket = session.utp.clone().ok_or("uTP is off")?;
    let stream = timeout(HANDSHAKE_TIMEOUT, socket.connect(addr))
        .await
        .map_err(|_| "uTP connect timed out".to_string())?
        .map_err(|e| format!("uTP: {e}"))?;
    Ok(Box::new(stream))
}

/// Opens a transport: TCP first, then uTP when TCP can't connect.
async fn dial(session: &SessionShared, addr: SocketAddr) -> Result<(BoxConn, &'static str), String> {
    if !session.tcp {
        return utp(session, addr).await.map(|c| (c, "utp"));
    }
    match tcp(addr).await {
        Ok(c) => Ok((c, "tcp")),
        Err(tcp_error) if session.utp.is_some() => match utp(session, addr).await {
            Ok(c) => Ok((c, "utp")),
            Err(utp_error) => Err(format!("{tcp_error}; {utp_error}")),
        },
        Err(e) => Err(e),
    }
}

/// Sends our handshake (inside MSE when `encrypted`) and reads the peer's.
async fn handshake_out(
    session: &SessionShared,
    conn: BoxConn,
    hashes: &InfoHashes,
    encrypted: bool,
) -> Result<(BoxConn, Reserved, Id20, bool), String> {
    let id = hashes.id();
    let ours = Handshake { reserved: Reserved::ours(session.dht_enabled(), hashes.v2.is_some()), info_hash: id, peer_id: session.peer_id };
    let mut conn: BoxConn = if encrypted {
        let allow_plain = session.encryption != Encryption::Forced;
        Box::new(mse::initiate(conn, &id, &ours.encode(), allow_plain).await.map_err(|e| format!("MSE: {e}"))?)
    } else {
        let mut conn = conn;
        conn.write_all(&ours.encode()).await.map_err(|e| e.to_string())?;
        conn
    };
    let (reserved, hash) = timeout(HANDSHAKE_TIMEOUT, Handshake::read_start(&mut conn))
        .await
        .map_err(|_| "handshake timed out".to_string())?
        .map_err(|e| e.to_string())?;
    // A hybrid's peer may answer with the v2 hash: the upgrade of BEP 52.
    if !hashes.matches(&hash) {
        return Err("peer answered for another torrent".into());
    }
    let peer_id = timeout(HANDSHAKE_TIMEOUT, Handshake::read_peer_id(&mut conn))
        .await
        .map_err(|_| "handshake timed out".to_string())?
        .map_err(|e| e.to_string())?;
    Ok((conn, reserved, peer_id, hashes.v2_id() == Some(hash)))
}

pub async fn connect(session: Arc<SessionShared>, torrent: Arc<Torrent>, addr: SocketAddr) {
    let slot = Slot(session.clone());
    session.connections.fetch_add(1, Ordering::SeqCst);
    let hashes = torrent.lock().hashes;
    // MSE first when enabled, as libtorrent does; a peer that refuses it gets a plaintext connection.
    let attempts: &[bool] = match session.encryption {
        Encryption::Disabled => &[false],
        Encryption::Enabled => &[true, false],
        Encryption::Forced => &[true],
    };
    let mut errors = Vec::new();
    for &encrypted in attempts {
        let result = match dial(&session, addr).await {
            Ok((conn, _)) => handshake_out(&session, conn, &hashes, encrypted).await,
            Err(e) => {
                errors.push(e);
                break;
            }
        };
        match result {
            Ok((_, _, peer_id, _)) if peer_id == session.peer_id => {
                torrent.dial_failed(addr);
                return;
            }
            Ok((conn, reserved, _, v2)) => {
                run(session, torrent, conn, addr, reserved, v2, false, slot).await;
                return;
            }
            Err(e) => errors.push(e),
        }
    }
    torrent.dial_failed(addr);
    session.log(format!("peer {addr} unreachable: {}", errors.join("; ")));
}

/// An incoming connection on TCP or uTP: a plaintext handshake or an MSE one, told apart by the first bytes.
pub async fn accept(session: Arc<SessionShared>, mut conn: BoxConn, addr: SocketAddr) {
    if !session.policy.allows(&addr) || session.connections.load(Ordering::SeqCst) >= session.connections_limit {
        return;
    }
    let slot = Slot(session.clone());
    session.connections.fetch_add(1, Ordering::SeqCst);
    let mut first = [0u8; 20];
    if !matches!(timeout(HANDSHAKE_TIMEOUT, conn.read_exact(&mut first)).await, Ok(Ok(_))) {
        return;
    }
    let plaintext = first[0] == 19 && &first[1..20] == PROTOCOL;
    let mut conn: BoxConn = if plaintext {
        if session.encryption == Encryption::Forced {
            return;
        }
        Box::new(mse::Stream::plain(conn, first.to_vec()))
    } else {
        if session.encryption == Encryption::Disabled {
            return;
        }
        let s = session.clone();
        let find = move |obfuscated: &Id20| s.find_obfuscated(obfuscated);
        let allow_plain = session.encryption != Encryption::Forced;
        match mse::respond(conn, &first, find, allow_plain).await {
            Ok((stream, _)) => Box::new(stream),
            Err(_) => return,
        }
    };
    let Ok(Ok((reserved, hash))) = timeout(HANDSHAKE_TIMEOUT, Handshake::read_start(&mut conn)).await else { return };
    let Some(torrent) = session.find(&hash) else { return };
    let hashes = torrent.lock().hashes;
    // A peer that supports v2 and connects to a hybrid by its v1 hash is answered with the v2 hash (BEP 52).
    let answer = match hashes.v2_id() {
        Some(v2) if reserved.v2() => v2,
        _ => hash,
    };
    let ours = Handshake { reserved: Reserved::ours(session.dht_enabled(), hashes.v2.is_some()), info_hash: answer, peer_id: session.peer_id };
    if conn.write_all(&ours.encode()).await.is_err() {
        return;
    }
    let Ok(Ok(peer_id)) = timeout(HANDSHAKE_TIMEOUT, Handshake::read_peer_id(&mut conn)).await else { return };
    if peer_id == session.peer_id {
        return;
    }
    let v2 = hashes.v2_id() == Some(answer);
    run(session, torrent, conn, addr, reserved, v2, true, slot).await;
}

#[allow(clippy::too_many_arguments)]
async fn run(
    session: Arc<SessionShared>,
    torrent: Arc<Torrent>,
    conn: BoxConn,
    addr: SocketAddr,
    reserved: Reserved,
    v2: bool,
    incoming: bool,
    _slot: Slot,
) {
    let (tx, rx) = mpsc::unbounded_channel();
    let limits = (session.connections_limit, session.multiple_per_ip);
    let queued = Arc::new(AtomicUsize::new(0));
    let Some(key) = torrent.attach(addr, reserved, v2, incoming, tx, queued.clone(), limits) else { return };
    let (mut reader, writer) = tokio::io::split(conn);
    let closed = Arc::new(Notify::new());
    let writer_task = tokio::spawn(write_loop(session.clone(), torrent.clone(), key, writer, rx, queued, closed.clone()));
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

async fn write_loop(
    session: Arc<SessionShared>,
    torrent: Arc<Torrent>,
    key: super::torrent::PeerKey,
    mut w: WriteHalf<BoxConn>,
    mut rx: mpsc::UnboundedReceiver<Out>,
    queued: Arc<AtomicUsize>,
    closed: Arc<Notify>,
) {
    let mut out = Vec::with_capacity(64 * 1024);
    'outer: while let Some(first) = rx.recv().await {
        let mut next = Some(first);
        while let Some(item) = next.take() {
            match item {
                Out::Msg(m) => m.encode(&mut out),
                Out::Upload(m, bytes) => {
                    if !out.is_empty() && w.write_all(&out).await.is_err() {
                        break 'outer;
                    }
                    out.clear();
                    m.encode(&mut out);
                    session.upload_limit.acquire(out.len()).await;
                    // Flushed at once: an encrypting stream may hold bytes until then.
                    if w.write_all(&out).await.is_err() || w.flush().await.is_err() {
                        break 'outer;
                    }
                    out.clear();
                    queued.fetch_sub(bytes, Ordering::SeqCst);
                    torrent.upload_sent(key);
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
        if w.write_all(&out).await.is_err() || w.flush().await.is_err() {
            break;
        }
        out.clear();
    }
    let _ = w.shutdown().await;
    closed.notify_one();
}

/// Session-wide upload limit, a token bucket. Zero means unlimited.
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
