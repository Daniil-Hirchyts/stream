//! Router port mapping, an explicit opt-in: the listen port is mapped for TCP and uTP while the session runs and
//! deleted when it stops. PCP is tried first; a gateway that only speaks NAT-PMP is then asked by NAT-PMP; a gateway
//! that answers neither, or refuses, is asked by UPnP IGD when that is allowed. Every request goes to the gateway the
//! app names (see [`super::natpmp::parse_gateway`] and [`super::upnp`]). Leases are short and renewed, so a process
//! that dies leaves nothing for long, except on a UPnP gateway that only grants permanent mappings.

use super::natpmp;
use super::upnp::{self, Igd};
use std::net::{Ipv4Addr, SocketAddrV4};
use std::sync::Arc;
use std::time::Duration;
use tokio::net::UdpSocket;
use tokio::sync::watch;

/// Ten minutes: long enough to renew calmly, short enough that a crash leaves nothing for long.
pub const LIFETIME: u32 = 600;
const TRIES: u32 = 4;
const RETRY_AFTER_FAILURE: Duration = Duration::from_secs(300);
/// Renewal failures in a row before the method is chosen again (the router may have been replaced or restarted).
const FAILURES_BEFORE_RESELECT: u32 = 3;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Protocol {
    Udp,
    Tcp,
}

impl Protocol {
    pub fn name(self) -> &'static str {
        match self {
            Protocol::Udp => "UDP",
            Protocol::Tcp => "TCP",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Mapping {
    pub protocol: Protocol,
    pub internal: u16,
    pub external: u16,
    /// Seconds the gateway granted; zero confirms a deletion, or (UPnP) a permanent mapping.
    pub lifetime: u32,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Failure {
    /// Not an answer to this request; keep waiting.
    Noise,
    /// The gateway does not speak this protocol version (a NAT-PMP-only gateway asked by PCP).
    Unsupported,
    Refused(String),
    NoAnswer(String),
}

impl Failure {
    fn message(&self) -> String {
        match self {
            Failure::Noise => "unrecognised answer".into(),
            Failure::Unsupported => "unsupported protocol version".into(),
            Failure::Refused(e) | Failure::NoAnswer(e) => e.clone(),
        }
    }
}

#[derive(Clone, Debug)]
pub struct Options {
    /// The gateway's PCP/NAT-PMP address (port 5351, or a test's loopback port).
    pub gateway: SocketAddrV4,
    /// PCP, falling back to NAT-PMP.
    pub pcp: bool,
    pub upnp: bool,
    /// Where unicast SSDP searches go (the gateway's port 1900, or a test's loopback port).
    pub ssdp: SocketAddrV4,
    /// Also search the SSDP multicast group; never for a loopback gateway.
    pub multicast: bool,
}

#[derive(Clone, Debug)]
enum Method {
    /// One nonce per mapping: PCP matches renewals and deletions to the mapping by it.
    Pcp { tcp: [u8; 12], udp: [u8; 12] },
    NatPmp,
    Upnp(Igd),
}

impl Method {
    fn name(&self) -> &'static str {
        match self {
            Method::Pcp { .. } => "PCP",
            Method::NatPmp => "NAT-PMP",
            Method::Upnp(_) => "UPnP",
        }
    }
}

struct Link {
    socket: UdpSocket,
    client: Ipv4Addr,
    port: u16,
}

async fn request(link: &Link, method: &Method, protocol: Protocol, lifetime: u32, tries: u32) -> Result<Mapping, Failure> {
    let port = link.port;
    match method {
        Method::Pcp { tcp, udp } => {
            let nonce = if protocol == Protocol::Tcp { *tcp } else { *udp };
            let message = natpmp::pcp_request(link.client, nonce, protocol, port, lifetime);
            natpmp::exchange(&link.socket, &message, tries, |d| natpmp::parse_pcp_response(d, nonce, protocol, port)).await
        }
        Method::NatPmp => {
            let message = natpmp::request(protocol, port, port, lifetime);
            natpmp::exchange(&link.socket, &message, tries, |d| natpmp::parse_response(d, protocol, port)).await
        }
        Method::Upnp(igd) => {
            if lifetime == 0 {
                upnp::delete(igd, protocol, port).await?;
                Ok(Mapping { protocol, internal: port, external: port, lifetime: 0 })
            } else {
                let granted = upnp::add(igd, protocol, port, link.client, lifetime).await?;
                Ok(Mapping { protocol, internal: port, external: port, lifetime: granted })
            }
        }
    }
}

fn nonce() -> [u8; 12] {
    let mut n = [0u8; 12];
    let _ = getrandom::fill(&mut n);
    n
}

/// The first method that maps `first`: PCP, NAT-PMP when the gateway only speaks that, then UPnP.
async fn choose(link: &Link, options: &Options, first: Protocol, log: &Log) -> Option<(Method, Mapping)> {
    if options.pcp {
        let pcp = Method::Pcp { tcp: nonce(), udp: nonce() };
        match request(link, &pcp, first, LIFETIME, TRIES).await {
            Ok(m) => return Some((pcp, m)),
            Err(Failure::Unsupported) => match request(link, &Method::NatPmp, first, LIFETIME, TRIES).await {
                Ok(m) => return Some((Method::NatPmp, m)),
                Err(e) => log(format!("NAT-PMP: {}", e.message())),
            },
            Err(e) => log(format!("PCP: {}", e.message())),
        }
    }
    if options.upnp {
        match upnp::discover(*options.gateway.ip(), options.ssdp, options.multicast).await {
            Ok(igd) => {
                let method = Method::Upnp(igd);
                match request(link, &method, first, LIFETIME, 1).await {
                    Ok(m) => return Some((method, m)),
                    Err(e) => log(format!("UPnP: {}", e.message())),
                }
            }
            Err(e) => log(format!("UPnP: {}", e.message())),
        }
    }
    None
}

/// Keeps the listen port mapped until stopped, then deletes the mappings it made.
pub struct Mapper {
    stop: watch::Sender<bool>,
    task: tokio::task::JoinHandle<()>,
}

pub type Log = Arc<dyn Fn(String) + Send + Sync>;
/// Hears the external TCP port the gateway granted, and 0 when the mapping is gone.
pub type External = Arc<dyn Fn(u16) + Send + Sync>;

impl Mapper {
    pub fn start(options: Options, port: u16, protocols: Vec<Protocol>, log: Log, external: External) -> Mapper {
        let (stop, mut stopped) = watch::channel(false);
        let task = tokio::spawn(async move {
            let Some(&first) = protocols.first() else { return };
            let socket = match UdpSocket::bind(SocketAddrV4::new(Ipv4Addr::UNSPECIFIED, 0)).await {
                Ok(s) => s,
                Err(e) => return log(format!("Port mapping unavailable: {e}")),
            };
            if let Err(e) = socket.connect(options.gateway).await {
                return log(format!("Port mapping unavailable: {e}"));
            }
            let client = match socket.local_addr() {
                Ok(std::net::SocketAddr::V4(a)) => *a.ip(),
                _ => return log("Port mapping unavailable: no local IPv4 address".into()),
            };
            let link = Link { socket, client, port };
            let mut method: Option<Method> = None;
            let mut mapped: Vec<Protocol> = Vec::new();
            let mut failures = 0u32;
            loop {
                let mut renew = Duration::from_secs(u64::from(LIFETIME) / 2);
                let mut granted: Vec<Mapping> = Vec::new();
                if method.is_none() {
                    if let Some((m, first_mapping)) = choose(&link, &options, first, &log).await {
                        method = Some(m);
                        granted.push(first_mapping);
                    }
                }
                let mut failed = method.is_none();
                if let Some(m) = &method {
                    for &protocol in &protocols {
                        if granted.iter().any(|g| g.protocol == protocol) {
                            continue;
                        }
                        match request(&link, m, protocol, LIFETIME, TRIES).await {
                            Ok(g) => granted.push(g),
                            Err(e) => {
                                log(format!("{}: {}", m.name(), e.message()));
                                failed = true;
                            }
                        }
                    }
                    for g in &granted {
                        if !mapped.contains(&g.protocol) {
                            let lease = if g.lifetime == 0 { "permanently (the gateway grants no leases)".to_string() } else { format!("for {} s", g.lifetime) };
                            log(format!("{} mapped {} port {} to external port {} {lease}", m.name(), g.protocol.name(), g.internal, g.external));
                            mapped.push(g.protocol);
                        }
                        if g.protocol == Protocol::Tcp {
                            external(g.external);
                        }
                        if g.lifetime > 0 {
                            renew = renew.min(Duration::from_secs(u64::from(g.lifetime.max(2)) / 2));
                        }
                    }
                }
                failures = if failed { failures + 1 } else { 0 };
                if failures >= FAILURES_BEFORE_RESELECT && method.is_some() {
                    log(format!("{} keeps failing; choosing the port mapping method again", method.as_ref().map_or("", |m| m.name())));
                    // The old method's leases expire on their own.
                    method = None;
                    failures = 0;
                    mapped.clear();
                    external(0);
                }
                let wait = if failed && granted.is_empty() { RETRY_AFTER_FAILURE } else { renew };
                tokio::select! {
                    _ = tokio::time::sleep(wait) => {}
                    _ = stopped.changed() => break,
                }
            }
            external(0);
            let Some(m) = method else { return };
            for protocol in mapped {
                // One try each: a lease expires on its own if the gateway misses the deletion.
                match request(&link, &m, protocol, 0, 1).await {
                    Ok(_) => log(format!("{} removed the {} mapping", m.name(), protocol.name())),
                    Err(e) => log(format!("{}: {}", m.name(), e.message())),
                }
            }
        });
        Mapper { stop, task }
    }

    /// Deletes the mappings and waits a few seconds at most for that to finish.
    pub async fn stop(self) {
        let _ = self.stop.send(true);
        let _ = tokio::time::timeout(Duration::from_secs(4), self.task).await;
    }
}
