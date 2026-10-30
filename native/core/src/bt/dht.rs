//! A Mainline DHT node (BEP 5) over IPv4: bootstraps from the well-known routers, finds peers for info hashes by
//! iterative lookup, announces itself, and answers other nodes' queries.

use super::bencode::{self, bytes, dict, Value};
use super::metainfo::{sha1, Id20};
use super::net::Policy;
use std::collections::{HashMap, HashSet};
use std::net::{SocketAddr, SocketAddrV4};
use std::sync::atomic::{AtomicU16, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tokio::net::UdpSocket;
use tokio::sync::oneshot;

const BOOTSTRAP: [&str; 4] =
    ["router.bittorrent.com:6881", "dht.transmissionbt.com:6881", "router.utorrent.com:6881", "dht.libtorrent.org:25401"];
const K: usize = 8;
const MAX_NODES: usize = 400;
const QUERY_TIMEOUT: Duration = Duration::from_secs(3);
const MAX_STORED: usize = 2000;

#[derive(Clone, Copy, Debug)]
struct Node {
    id: Id20,
    addr: SocketAddrV4,
    last_seen: Instant,
    failures: u32,
}

fn distance(a: &Id20, b: &Id20) -> Id20 {
    let mut d = [0u8; 20];
    for i in 0..20 {
        d[i] = a[i] ^ b[i];
    }
    d
}

fn compact_nodes(nodes: &[Node]) -> Vec<u8> {
    let mut out = Vec::with_capacity(nodes.len() * 26);
    for n in nodes {
        out.extend_from_slice(&n.id);
        out.extend_from_slice(&n.addr.ip().octets());
        out.extend_from_slice(&n.addr.port().to_be_bytes());
    }
    out
}

fn parse_nodes(b: &[u8]) -> Vec<(Id20, SocketAddrV4)> {
    b.chunks_exact(26)
        .map(|c| {
            let id: Id20 = c[..20].try_into().unwrap();
            let addr = SocketAddrV4::new([c[20], c[21], c[22], c[23]].into(), u16::from_be_bytes([c[24], c[25]]));
            (id, addr)
        })
        .filter(|(_, a)| a.port() != 0)
        .collect()
}

pub struct Dht {
    socket: UdpSocket,
    id: Id20,
    policy: Policy,
    nodes: Mutex<Vec<Node>>,
    pending: Mutex<HashMap<u16, oneshot::Sender<Value>>>,
    next_tx: AtomicU16,
    secret: [u8; 16],
    stored: Mutex<HashMap<Id20, Vec<(SocketAddr, Instant)>>>,
}

fn random<const N: usize>() -> [u8; N] {
    let mut b = [0u8; N];
    let _ = getrandom::fill(&mut b);
    b
}

impl Dht {
    /// Binds the DHT's UDP socket, on the TCP listen port when it's free, and starts bootstrapping.
    pub async fn start(port: u16, policy: Policy) -> Result<Arc<Dht>, String> {
        let socket = match UdpSocket::bind(("0.0.0.0", port)).await {
            Ok(s) => s,
            Err(_) => UdpSocket::bind(("0.0.0.0", 0)).await.map_err(|e| e.to_string())?,
        };
        let dht = Arc::new(Dht {
            socket,
            id: random(),
            policy,
            nodes: Mutex::new(Vec::new()),
            pending: Mutex::new(HashMap::new()),
            next_tx: AtomicU16::new(u16::from_be_bytes(random())),
            secret: random(),
            stored: Mutex::new(HashMap::new()),
        });
        let weak = Arc::downgrade(&dht);
        tokio::spawn(async move {
            let mut buf = vec![0u8; 2048];
            loop {
                let Some(d) = weak.upgrade() else { return };
                let received = tokio::time::timeout(Duration::from_secs(5), d.socket.recv_from(&mut buf)).await;
                if let Ok(Ok((n, from))) = received {
                    d.handle(&buf[..n], from).await;
                }
            }
        });
        let d = dht.clone();
        tokio::spawn(async move { d.bootstrap().await });
        Ok(dht)
    }

    fn allows(&self, addr: &SocketAddrV4) -> bool {
        self.policy.allows(&SocketAddr::V4(*addr))
    }

    fn see(&self, id: Id20, addr: SocketAddrV4) {
        if !self.allows(&addr) || id == self.id {
            return;
        }
        let mut nodes = self.nodes.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(n) = nodes.iter_mut().find(|n| n.addr == addr) {
            n.id = id;
            n.last_seen = Instant::now();
            n.failures = 0;
            return;
        }
        if nodes.len() >= MAX_NODES {
            // Replace the stalest node.
            if let Some(i) = (0..nodes.len()).max_by_key(|&i| (nodes[i].failures, std::cmp::Reverse(nodes[i].last_seen))) {
                nodes.swap_remove(i);
            }
        }
        nodes.push(Node { id, addr, last_seen: Instant::now(), failures: 0 });
    }

    fn failed(&self, addr: SocketAddrV4) {
        let mut nodes = self.nodes.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(i) = nodes.iter().position(|n| n.addr == addr) {
            nodes[i].failures += 1;
            if nodes[i].failures >= 3 {
                nodes.swap_remove(i);
            }
        }
    }

    fn closest(&self, target: &Id20, count: usize) -> Vec<Node> {
        let mut nodes = self.nodes.lock().unwrap_or_else(|e| e.into_inner()).clone();
        nodes.sort_by_key(|n| distance(&n.id, target));
        nodes.truncate(count);
        nodes
    }

    fn token(&self, ip: &std::net::Ipv4Addr) -> Vec<u8> {
        let mut input = self.secret.to_vec();
        input.extend_from_slice(&ip.octets());
        sha1(&input)[..8].to_vec()
    }

    async fn query(&self, addr: SocketAddrV4, q: &str, mut args: Value) -> Option<Value> {
        if !self.allows(&addr) {
            return None;
        }
        let tx = self.next_tx.fetch_add(1, Ordering::Relaxed);
        let (send, recv) = oneshot::channel();
        self.pending.lock().unwrap_or_else(|e| e.into_inner()).insert(tx, send);
        if let Value::Dict(d) = &mut args {
            d.insert(b"id".to_vec(), bytes(self.id.to_vec()));
        }
        let msg = dict([("a", args), ("q", bytes(q)), ("t", bytes(tx.to_be_bytes().to_vec())), ("y", bytes("q"))]);
        let _ = self.socket.send_to(&bencode::encode(&msg), addr).await;
        let reply = tokio::time::timeout(QUERY_TIMEOUT, recv).await.ok().and_then(Result::ok);
        self.pending.lock().unwrap_or_else(|e| e.into_inner()).remove(&tx);
        match &reply {
            Some(r) => {
                if let Some(id) = r.get("r").and_then(|r| r.get_bytes("id")).and_then(|b| <Id20>::try_from(b).ok()) {
                    self.see(id, addr);
                }
            }
            None => self.failed(addr),
        }
        reply.and_then(|r| r.get("r").cloned())
    }

    async fn handle(&self, data: &[u8], from: SocketAddr) {
        let SocketAddr::V4(from) = from else { return };
        let Ok(msg) = bencode::decode(data) else { return };
        let Some(t) = msg.get_bytes("t") else { return };
        match msg.get_str("y") {
            Some("r") | Some("e") => {
                if t.len() == 2 {
                    let tx = u16::from_be_bytes([t[0], t[1]]);
                    if let Some(send) = self.pending.lock().unwrap_or_else(|e| e.into_inner()).remove(&tx) {
                        let _ = send.send(msg.clone());
                    }
                }
            }
            Some("q") => {
                if !self.allows(&from) {
                    return;
                }
                let args = msg.get("a").cloned().unwrap_or(Value::Dict(Default::default()));
                if let Some(id) = args.get_bytes("id").and_then(|b| <Id20>::try_from(b).ok()) {
                    self.see(id, from);
                }
                let mut r: std::collections::BTreeMap<Vec<u8>, Value> = Default::default();
                r.insert(b"id".to_vec(), bytes(self.id.to_vec()));
                match msg.get_str("q") {
                    Some("ping") => {}
                    Some("find_node") => {
                        let target = args.get_bytes("target").and_then(|b| <Id20>::try_from(b).ok()).unwrap_or(self.id);
                        r.insert(b"nodes".to_vec(), Value::Bytes(compact_nodes(&self.closest(&target, K))));
                    }
                    Some("get_peers") => {
                        let Some(hash) = args.get_bytes("info_hash").and_then(|b| <Id20>::try_from(b).ok()) else { return };
                        r.insert(b"token".to_vec(), Value::Bytes(self.token(from.ip())));
                        let stored = self.stored.lock().unwrap_or_else(|e| e.into_inner());
                        match stored.get(&hash).filter(|v| !v.is_empty()) {
                            Some(peers) => {
                                let values = peers
                                    .iter()
                                    .filter_map(|(a, _)| match a {
                                        SocketAddr::V4(v4) => {
                                            let mut c = v4.ip().octets().to_vec();
                                            c.extend_from_slice(&v4.port().to_be_bytes());
                                            Some(Value::Bytes(c))
                                        }
                                        _ => None,
                                    })
                                    .take(50)
                                    .collect();
                                r.insert(b"values".to_vec(), Value::List(values));
                            }
                            None => {
                                r.insert(b"nodes".to_vec(), Value::Bytes(compact_nodes(&self.closest(&hash, K))));
                            }
                        }
                    }
                    Some("announce_peer") => {
                        let Some(hash) = args.get_bytes("info_hash").and_then(|b| <Id20>::try_from(b).ok()) else { return };
                        if args.get_bytes("token") != Some(&self.token(from.ip())[..]) {
                            return;
                        }
                        let port = if args.get_int("implied_port") == Some(1) {
                            from.port()
                        } else {
                            args.get_int("port").and_then(|p| u16::try_from(p).ok()).unwrap_or(0)
                        };
                        let peer = SocketAddr::new((*from.ip()).into(), port);
                        if port != 0 {
                            let mut stored = self.stored.lock().unwrap_or_else(|e| e.into_inner());
                            if stored.len() < MAX_STORED || stored.contains_key(&hash) {
                                let list = stored.entry(hash).or_default();
                                list.retain(|(a, at)| *a != peer && at.elapsed() < Duration::from_secs(1800));
                                list.push((peer, Instant::now()));
                                list.truncate(100);
                            }
                        }
                    }
                    _ => {
                        let e = dict([("e", Value::List(vec![Value::Int(204), bytes("Method Unknown")])), ("t", bytes(t.to_vec())), ("y", bytes("e"))]);
                        let _ = self.socket.send_to(&bencode::encode(&e), from).await;
                        return;
                    }
                }
                let reply = dict([("r", Value::Dict(r)), ("t", bytes(t.to_vec())), ("y", bytes("r"))]);
                let _ = self.socket.send_to(&bencode::encode(&reply), from).await;
            }
            _ => {}
        }
    }

    async fn bootstrap(self: Arc<Self>) {
        loop {
            if self.nodes.lock().unwrap_or_else(|e| e.into_inner()).len() < K {
                for router in BOOTSTRAP {
                    if let Ok(addrs) = tokio::net::lookup_host(router).await {
                        for a in addrs {
                            if let SocketAddr::V4(v4) = a {
                                let target = dict([("target", bytes(self.id.to_vec()))]);
                                if let Some(r) = self.query(v4, "find_node", target).await {
                                    for (id, addr) in parse_nodes(r.get_bytes("nodes").unwrap_or_default()) {
                                        self.see(id, addr);
                                    }
                                }
                            }
                        }
                    }
                }
                let _ = self.lookup(self.id, false).await;
            }
            tokio::time::sleep(Duration::from_secs(60)).await;
            if Arc::strong_count(&self) == 1 {
                return;
            }
        }
    }

    /// Iterative lookup towards `target`: with `peers`, a get_peers lookup that returns the peers found and the
    /// closest nodes that gave a token.
    async fn lookup(&self, target: Id20, peers: bool) -> (Vec<SocketAddr>, Vec<(SocketAddrV4, Vec<u8>)>) {
        let mut queried: HashSet<SocketAddrV4> = HashSet::new();
        let mut candidates: Vec<(Id20, SocketAddrV4)> = self.closest(&target, K * 2).iter().map(|n| (n.id, n.addr)).collect();
        let mut found: Vec<SocketAddr> = Vec::new();
        let mut tokens: Vec<(Id20, SocketAddrV4, Vec<u8>)> = Vec::new();
        let deadline = Instant::now() + Duration::from_secs(15);
        for _round in 0..8 {
            candidates.sort_by_key(|(id, _)| distance(id, &target));
            candidates.dedup_by_key(|(_, a)| *a);
            let batch: Vec<(Id20, SocketAddrV4)> =
                candidates.iter().filter(|(_, a)| !queried.contains(a)).take(K).copied().collect();
            if batch.is_empty() || Instant::now() > deadline {
                break;
            }
            let futures = batch.iter().map(|(id, addr)| {
                queried.insert(*addr);
                let args = if peers {
                    dict([("info_hash", bytes(target.to_vec()))])
                } else {
                    dict([("target", bytes(target.to_vec()))])
                };
                let (id, addr) = (*id, *addr);
                async move { (id, addr, self.query(addr, if peers { "get_peers" } else { "find_node" }, args).await) }
            });
            for (id, addr, reply) in futures_join(futures).await {
                let Some(r) = reply else { continue };
                for (nid, naddr) in parse_nodes(r.get_bytes("nodes").unwrap_or_default()) {
                    if self.allows(&naddr) {
                        candidates.push((nid, naddr));
                    }
                }
                if let Some(values) = r.get("values").and_then(Value::list) {
                    for v in values {
                        found.extend(super::wire::parse_compact_v4(v.bytes().unwrap_or_default()));
                    }
                }
                if let Some(token) = r.get_bytes("token") {
                    tokens.push((id, addr, token.to_vec()));
                }
            }
        }
        tokens.sort_by_key(|(id, _, _)| distance(id, &target));
        tokens.truncate(K);
        found.sort();
        found.dedup();
        (found, tokens.into_iter().map(|(_, a, t)| (a, t)).collect())
    }

    /// Peers for a torrent, announcing ourselves on `port` to the closest nodes.
    pub async fn get_peers(&self, info_hash: Id20, port: Option<u16>) -> Vec<SocketAddr> {
        let (found, tokens) = self.lookup(info_hash, true).await;
        if let Some(port) = port {
            let announces = tokens.into_iter().map(|(addr, token)| {
                let args = dict([
                    ("implied_port", Value::Int(0)),
                    ("info_hash", bytes(info_hash.to_vec())),
                    ("port", Value::Int(i64::from(port))),
                    ("token", Value::Bytes(token)),
                ]);
                async move { self.query(addr, "announce_peer", args).await }
            });
            futures_join(announces).await;
        }
        found.into_iter().filter(|a| self.policy.allows(a)).collect()
    }
}

/// Runs futures concurrently and collects their results in order.
async fn futures_join<F: std::future::Future>(futures: impl IntoIterator<Item = F>) -> Vec<F::Output> {
    let mut pending: Vec<(usize, std::pin::Pin<Box<F>>)> = futures.into_iter().map(Box::pin).enumerate().collect();
    let mut done: Vec<Option<F::Output>> = (0..pending.len()).map(|_| None).collect();
    std::future::poll_fn(|cx| {
        pending.retain_mut(|(i, f)| match f.as_mut().poll(cx) {
            std::task::Poll::Ready(v) => {
                done[*i] = Some(v);
                false
            }
            std::task::Poll::Pending => true,
        });
        if pending.is_empty() {
            std::task::Poll::Ready(())
        } else {
            std::task::Poll::Pending
        }
    })
    .await;
    done.into_iter().flatten().collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn nodes_answer_lookups_and_store_announced_peers() {
        let local = Policy { public_only: false };
        let a = Dht::start(0, local).await.unwrap();
        let b = Dht::start(0, local).await.unwrap();
        let b_addr = SocketAddrV4::new([127, 0, 0, 1].into(), b.socket.local_addr().unwrap().port());
        // a learns about b by pinging it, then finds no peers, announces, and finds itself.
        assert!(a.query(b_addr, "ping", dict([])).await.is_some());
        let hash = [7u8; 20];
        assert!(a.get_peers(hash, Some(6881)).await.is_empty());
        let peers = a.get_peers(hash, None).await;
        assert_eq!(peers, vec!["127.0.0.1:6881".parse::<SocketAddr>().unwrap()]);
    }
}
