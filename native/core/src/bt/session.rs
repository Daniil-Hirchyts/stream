//! A BitTorrent session: its own network runtime, listening sockets, the torrents and their discovery (trackers,
//! DHT, local service discovery and peer exchange).

use super::dht::Dht;
use super::lsd::Lsd;
use super::metainfo::{Id20, Info, InfoHashes, Layers};
use super::mse::{self, Policy as Encryption};
use super::net::{parse_listen, Policy};
use super::peer::{self, UploadLimit};
use super::torrent::Torrent;
use super::tracker::{self, Announce};
use crate::swarm::Event;
use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::atomic::{AtomicBool, AtomicI32, AtomicU16, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{Duration, Instant};
use tokio::net::TcpListener;

#[derive(Clone, Debug)]
pub struct SessionConfig {
    pub listen: String,
    pub connections_limit: usize,
    pub multiple_per_ip: bool,
    pub public_only: bool,
    pub dht: bool,
    pub lsd: bool,
    pub pex: bool,
    pub user_agent: String,
    pub ca_file: String,
    /// Bytes per second; 0 is unlimited.
    pub upload_rate: u64,
    pub log: bool,
    /// uTP (BEP 29) on the listen port, for incoming peers and when TCP can't connect.
    pub utp: bool,
    /// TCP listening and dialling; off only to test uTP alone.
    pub tcp: bool,
    pub encryption: Encryption,
}

impl Default for SessionConfig {
    fn default() -> Self {
        SessionConfig {
            listen: "0.0.0.0:0,[::]:0".into(),
            connections_limit: 40,
            multiple_per_ip: false,
            public_only: false,
            dht: false,
            lsd: false,
            pex: true,
            user_agent: "Stream".into(),
            ca_file: String::new(),
            upload_rate: 0,
            log: false,
            utp: true,
            tcp: true,
            encryption: Encryption::Enabled,
        }
    }
}

pub type Notify = Arc<dyn Fn() + Send + Sync>;

pub struct SessionShared {
    pub runtime: tokio::runtime::Handle,
    pub peer_id: Id20,
    pub policy: Policy,
    pub pex: bool,
    pub user_agent: String,
    pub connections_limit: usize,
    pub multiple_per_ip: bool,
    pub connections: AtomicUsize,
    pub blocked_writes: AtomicI32,
    pub upload_limit: UploadLimit,
    pub encryption: Encryption,
    pub utp: Option<Arc<librqbit_utp::UtpSocketUdp>>,
    pub tcp: bool,
    port: AtomicU16,
    log_enabled: bool,
    lsd_enabled: bool,
    torrents: Mutex<Vec<Arc<Torrent>>>,
    events: Mutex<Vec<Event>>,
    notify: Option<Notify>,
    tracker: tracker::Client,
    dht: Mutex<Option<Arc<Dht>>>,
    lsd: Mutex<Option<Arc<Lsd>>>,
    stopping: AtomicBool,
}

impl SessionShared {
    pub fn emit(&self, event: Event) {
        self.events.lock().unwrap_or_else(|e| e.into_inner()).push(event);
        if let Some(n) = &self.notify {
            n();
        }
    }

    pub fn log(&self, line: String) {
        if self.log_enabled {
            self.emit(Event::Log(line));
        }
    }

    pub fn port(&self) -> u16 {
        self.port.load(Ordering::Relaxed)
    }

    pub fn dht_enabled(&self) -> bool {
        self.dht.lock().unwrap_or_else(|e| e.into_inner()).is_some()
    }

    fn torrents(&self) -> MutexGuard<'_, Vec<Arc<Torrent>>> {
        self.torrents.lock().unwrap_or_else(|e| e.into_inner())
    }

    pub fn find(&self, id: &Id20) -> Option<Arc<Torrent>> {
        self.torrents().iter().find(|t| t.matches(id)).cloned()
    }

    /// The 20-byte hash of the torrent an MSE initiator named by `HASH('req2', SKEY)`.
    pub fn find_obfuscated(&self, obfuscated: &Id20) -> Option<Id20> {
        self.torrents().iter().flat_map(|t| t.lock().hashes.ids()).find(|id| mse::obfuscated_id(id) == *obfuscated)
    }

    pub fn drain(&self, out: &mut Vec<Event>) {
        out.append(&mut self.events.lock().unwrap_or_else(|e| e.into_inner()));
    }
}

pub struct Session {
    pub shared: Arc<SessionShared>,
    runtime: Option<tokio::runtime::Runtime>,
}

impl Session {
    pub fn new(config: SessionConfig, notify: Option<Notify>) -> Result<Session, String> {
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .max_blocking_threads(4)
            .thread_name("stream-net")
            .enable_all()
            .build()
            .map_err(|e| format!("Network runtime: {e}"))?;
        let tls = tracker::tls_config(&config.ca_file)?;
        let policy = Policy { public_only: config.public_only };
        let mut shared = SessionShared {
            runtime: runtime.handle().clone(),
            peer_id: super::wire::new_peer_id(),
            policy,
            pex: config.pex,
            user_agent: config.user_agent.clone(),
            connections_limit: config.connections_limit.max(1),
            multiple_per_ip: config.multiple_per_ip,
            connections: AtomicUsize::new(0),
            blocked_writes: AtomicI32::new(0),
            upload_limit: UploadLimit::new(config.upload_rate),
            encryption: config.encryption,
            utp: None,
            tcp: config.tcp,
            port: AtomicU16::new(0),
            log_enabled: config.log,
            lsd_enabled: config.lsd && !config.public_only,
            torrents: Mutex::new(Vec::new()),
            events: Mutex::new(Vec::new()),
            notify,
            tracker: tracker::Client { tls, user_agent: config.user_agent.clone(), policy },
            dht: Mutex::new(None),
            lsd: Mutex::new(None),
            stopping: AtomicBool::new(false),
        };

        let interfaces = parse_listen(&config.listen);
        if interfaces.is_empty() {
            return Err(format!("No usable listen interface in {:?}", config.listen));
        }
        let mut listeners = Vec::new();
        let mut errors = Vec::new();
        for mut address in interfaces.iter().copied().filter(|_| config.tcp) {
            // Every interface listens on the same port: peers learn only one.
            if address.port() == 0 && shared.port() != 0 {
                address.set_port(shared.port());
            }
            match runtime.block_on(async { TcpListener::bind(address).await }) {
                Ok(l) => {
                    if shared.port() == 0 {
                        shared.port.store(l.local_addr().map(|a| a.port()).unwrap_or(0), Ordering::Relaxed);
                    }
                    listeners.push(l);
                }
                // An IPv6 interface on a host without IPv6 is not fatal while another interface works.
                Err(e) => errors.push(format!("{address}: {e}")),
            }
        }
        if listeners.is_empty() && config.tcp {
            return Err(format!("Cannot listen: {}", errors.join("; ")));
        }
        if config.utp {
            // uTP shares the TCP port, on the first interface; the DHT then takes another UDP port.
            let mut address = interfaces[0];
            address.set_port(shared.port());
            let opts = librqbit_utp::SocketOpts {
                max_live_vsocks: std::num::NonZeroUsize::new(config.connections_limit.max(1)),
                ..Default::default()
            };
            match runtime.block_on(librqbit_utp::UtpSocketUdp::new_udp_with_opts(address, opts, Default::default())) {
                Ok(socket) => {
                    if shared.port() == 0 {
                        shared.port.store(socket.bind_addr().port(), Ordering::Relaxed);
                    }
                    shared.utp = Some(socket);
                }
                Err(e) => shared.log(format!("uTP unavailable: {e}")),
            }
        }
        let shared = Arc::new(shared);
        for l in listeners {
            let s = shared.clone();
            runtime.spawn(async move {
                loop {
                    match l.accept().await {
                        Ok((stream, addr)) => {
                            let _ = stream.set_nodelay(true);
                            tokio::spawn(peer::accept(s.clone(), Box::new(stream), addr));
                        }
                        Err(_) => tokio::time::sleep(Duration::from_millis(100)).await,
                    }
                }
            });
        }
        if let Some(socket) = shared.utp.clone() {
            let s = Arc::downgrade(&shared);
            runtime.spawn(async move {
                loop {
                    let accepted = socket.accept().await;
                    let Some(s) = s.upgrade() else { return };
                    match accepted {
                        Ok(stream) => {
                            let addr = stream.remote_addr();
                            tokio::spawn(peer::accept(s, Box::new(stream), addr));
                        }
                        Err(_) => tokio::time::sleep(Duration::from_millis(100)).await,
                    }
                }
            });
        }

        if config.dht {
            let s = shared.clone();
            let port = shared.port();
            match runtime.block_on(Dht::start(port, policy)) {
                Ok(dht) => *s.dht.lock().unwrap_or_else(|e| e.into_inner()) = Some(dht),
                Err(e) => shared.log(format!("DHT unavailable: {e}")),
            }
        }
        if shared.lsd_enabled {
            match runtime.block_on(Lsd::start(Arc::downgrade(&shared))) {
                Ok(lsd) => *shared.lsd.lock().unwrap_or_else(|e| e.into_inner()) = Some(lsd),
                Err(e) => shared.log(format!("Local discovery unavailable: {e}")),
            }
        }
        Ok(Session { shared, runtime: Some(runtime) })
    }

    pub fn port(&self) -> u16 {
        self.shared.port()
    }

    /// Starts a torrent; it runs until [`Session::remove`].
    pub fn add(
        &self,
        tag: u64,
        hashes: InfoHashes,
        info: Option<Info>,
        layers: Layers,
        trackers: Vec<String>,
        peers: Vec<SocketAddr>,
    ) -> Arc<Torrent> {
        let t = Torrent::new(tag, Arc::downgrade(&self.shared), hashes, info, layers, trackers);
        self.shared.torrents().push(t.clone());
        t.add_candidates(peers);
        self.shared.runtime.spawn(torrent_loop(self.shared.clone(), t.clone()));
        t
    }

    /// Disconnects the torrent's peers and forgets it; [`Event::Removed`] follows once its storage is released.
    pub fn remove(&self, t: &Arc<Torrent>) {
        t.remove();
        let shared = self.shared.clone();
        let t = t.clone();
        self.shared.runtime.spawn(async move {
            let deadline = Instant::now() + Duration::from_secs(3);
            while !t.lock().peers.is_empty() && Instant::now() < deadline {
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
            shared.torrents().retain(|x| !Arc::ptr_eq(x, &t));
            shared.emit(Event::Removed { tag: t.tag });
        });
    }
}

impl Drop for Session {
    fn drop(&mut self) {
        self.shared.stopping.store(true, Ordering::SeqCst);
        for t in self.shared.torrents().iter() {
            t.remove();
        }
        self.shared.torrents().clear();
        if let Some(rt) = self.runtime.take() {
            rt.shutdown_timeout(Duration::from_secs(2));
        }
    }
}

struct TrackerState {
    next: Instant,
    interval: Duration,
    failures: u32,
    started: bool,
}

/// Drives one torrent: maintenance once a second, connections, and announces.
async fn torrent_loop(session: Arc<SessionShared>, t: Arc<Torrent>) {
    let mut tick: u64 = 0;
    let mut trackers: HashMap<String, TrackerState> = HashMap::new();
    let mut next_dht = Instant::now();
    let mut next_lsd = Instant::now();
    let mut web_seeds_started: Vec<String> = Vec::new();
    let key = u32::from_be_bytes(session.peer_id[16..20].try_into().unwrap());
    let (results_tx, mut results) = tokio::sync::mpsc::unbounded_channel::<(String, TrackerOutcome)>();
    loop {
        tokio::select! {
            _ = tokio::time::sleep(Duration::from_secs(1)) => { tick += 1; t.maintain(tick); }
            _ = t.wake.notified() => {}
        }
        if t.lock().removed || session.stopping.load(Ordering::SeqCst) {
            return;
        }
        if !t.wants_peers() {
            continue;
        }
        // One worker per web seed, once the layout is known.
        if t.info().is_some() {
            for url in t.web_seeds() {
                if !web_seeds_started.contains(&url) {
                    web_seeds_started.push(url.clone());
                    tokio::spawn(web_seed(session.clone(), t.clone(), url));
                }
            }
        }
        // New connections, a few at a time.
        let in_flight = session.connections.load(Ordering::SeqCst);
        let room = session.connections_limit.saturating_sub(in_flight).min(8);
        for addr in t.take_dials(room, session.multiple_per_ip) {
            tokio::spawn(peer::connect(session.clone(), t.clone(), addr));
        }

        let now = Instant::now();
        let ids = t.lock().hashes.ids();
        for url in t.trackers() {
            let st = trackers.entry(url.clone()).or_insert(TrackerState {
                next: now,
                interval: Duration::from_secs(1800),
                failures: 0,
                started: false,
            });
            if st.next > now {
                continue;
            }
            st.next = now + Duration::from_secs(3600);
            let event = if st.started { tracker::Event::None } else { tracker::Event::Started };
            let (s, t2, url2) = (session.clone(), t.clone(), url.clone());
            let ids = ids.clone();
            let results_tx = results_tx.clone();
            let left = t.left();
            let (down, up) = {
                let st = t.lock();
                (st.total_down, st.total_up)
            };
            tokio::spawn(async move {
                let mut result: TrackerOutcome = Ok(None);
                for id in ids {
                    let a = Announce {
                        info_hash: id,
                        peer_id: s.peer_id,
                        port: s.port(),
                        uploaded: up,
                        downloaded: down,
                        left,
                        event,
                        numwant: 50,
                        key,
                    };
                    match s.tracker.announce(&url2, &a).await {
                        Ok(r) => {
                            t2.add_candidates(r.peers);
                            result = Ok(r.interval.or(result.clone().ok().flatten()));
                        }
                        Err(e) => result = Err(e),
                    }
                }
                let _ = results_tx.send((url2, result));
                t2.wake.notify_one();
            });
        }
        while let Ok((url, outcome)) = results.try_recv() {
            if let Some(st) = trackers.get_mut(&url) {
                match outcome {
                    Ok(interval) => {
                        st.started = true;
                        st.failures = 0;
                        st.interval = interval.unwrap_or(st.interval);
                        // Few peers: ask again sooner than the tracker's interval.
                        let few = t.lock().peers.len() < 5;
                        st.next = Instant::now() + if few { st.interval.min(Duration::from_secs(120)) } else { st.interval };
                    }
                    Err(e) => {
                        st.failures += 1;
                        st.next = Instant::now() + Duration::from_secs(15 << st.failures.min(6));
                        session.log(format!("tracker {}: {e}", redact(&url)));
                    }
                }
            }
        }

        if !t.private() {
            let dht = session.dht.lock().unwrap_or_else(|e| e.into_inner()).clone();
            if let Some(dht) = dht {
                if now >= next_dht {
                    let few = t.lock().peers.len() < 10;
                    next_dht = now + if few { Duration::from_secs(60) } else { Duration::from_secs(900) };
                    let (t2, port) = (t.clone(), session.port());
                    let id = t.id();
                    tokio::spawn(async move {
                        let peers = dht.get_peers(id, Some(port)).await;
                        t2.add_candidates(peers);
                    });
                }
            }
            let lsd = session.lsd.lock().unwrap_or_else(|e| e.into_inner()).clone();
            if let Some(lsd) = lsd {
                if now >= next_lsd {
                    next_lsd = now + Duration::from_secs(300);
                    lsd.announce(&t.id(), session.port()).await;
                }
            }
        }
    }
}

/// A file's address under a web seed (BEP 19): a single-file torrent's seed may name the file itself; otherwise the
/// torrent's paths follow the seed's address.
pub fn web_seed_url(base: &str, path: &str, single_file: bool) -> String {
    if single_file && !base.ends_with('/') {
        return base.to_owned();
    }
    let mut url = base.to_owned();
    if !url.ends_with('/') {
        url.push('/');
    }
    let encoded: Vec<String> = path
        .split('/')
        .map(|c| {
            c.bytes()
                .map(|b| {
                    if b.is_ascii_alphanumeric() || b"-_.~".contains(&b) {
                        (b as char).to_string()
                    } else {
                        format!("%{b:02X}")
                    }
                })
                .collect()
        })
        .collect();
    url + &encoded.join("/")
}

/// Downloads whole pieces from one web seed, one at a time, backing off after errors.
async fn web_seed(session: Arc<SessionShared>, t: Arc<Torrent>, base: String) {
    let mut failures: u32 = 0;
    loop {
        if t.lock().removed || session.stopping.load(Ordering::SeqCst) || !t.web_seeds().contains(&base) {
            return;
        }
        let Some((p, size, ranges)) = t.claim_web_piece() else {
            tokio::select! {
                _ = tokio::time::sleep(Duration::from_millis(500)) => {}
                _ = t.wake.notified() => {}
            }
            continue;
        };
        let Some(info) = t.info() else { return };
        let single = info.files.len() == 1 && info.files[0].path == info.name;
        let mut data = vec![0u8; size as usize];
        let mut error = None;
        for (file, offset, length, at) in ranges {
            let url = web_seed_url(&base, &info.files[file].path, single);
            match session.tracker.fetch_range(&url, offset, offset + length as u64).await {
                Ok(bytes) => data[at..at + length].copy_from_slice(&bytes),
                Err(e) => {
                    error = Some(e);
                    break;
                }
            }
        }
        match error {
            None => {
                failures = 0;
                t.web_piece_done(p, Some(data));
            }
            Some(e) => {
                t.web_piece_done(p, None);
                failures += 1;
                session.log(format!("web seed {}: {e}", redact(&base)));
                tokio::time::sleep(Duration::from_secs(5u64 << failures.min(6))).await;
            }
        }
    }
}

/// Tracker addresses may carry private keys in their path or query; diagnostics show only the host.
pub fn redact(url: &str) -> String {
    match url.split_once("://") {
        Some((scheme, rest)) => format!("{scheme}://{}", rest.split(['/', '?']).next().unwrap_or("")),
        None => "tracker".into(),
    }
}

type TrackerOutcome = Result<Option<Duration>, String>;
