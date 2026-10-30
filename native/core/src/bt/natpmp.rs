//! NAT-PMP port mapping (RFC 6886), an explicit opt-in. The app names the gateway; nothing is discovered or
//! broadcast. The listen port is mapped for TCP and uTP with a short lease, renewed while the session runs and
//! deleted when it stops; a lease left by a process that died expires on its own.

use std::net::{IpAddr, Ipv4Addr, SocketAddr, SocketAddrV4};
use std::sync::Arc;
use std::time::Duration;
use tokio::net::UdpSocket;
use tokio::sync::watch;

pub const PORT: u16 = 5351;
/// Ten minutes: long enough to renew calmly, short enough that a crash leaves nothing for long.
pub const LIFETIME: u32 = 600;
const TRIES: u32 = 4;
const FIRST_WAIT: Duration = Duration::from_millis(250);
const RETRY_AFTER_FAILURE: Duration = Duration::from_secs(300);

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Protocol {
    Udp = 1,
    Tcp = 2,
}

impl Protocol {
    fn name(self) -> &'static str {
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
    /// Seconds the gateway granted; zero confirms a deletion.
    pub lifetime: u32,
}

/// The gateway's address: an IPv4 address, optionally with a port (for tests' loopback gateway). Only private and
/// loopback addresses are accepted, so a misconfiguration can't send mapping requests across the internet.
pub fn parse_gateway(text: &str) -> Result<SocketAddrV4, String> {
    let text = text.trim();
    let address = match text.parse::<SocketAddr>() {
        Ok(SocketAddr::V4(a)) => a,
        Ok(SocketAddr::V6(_)) => return Err("NAT-PMP needs an IPv4 gateway".into()),
        Err(_) => match text.parse::<IpAddr>() {
            Ok(IpAddr::V4(ip)) => SocketAddrV4::new(ip, PORT),
            Ok(IpAddr::V6(_)) => return Err("NAT-PMP needs an IPv4 gateway".into()),
            Err(_) => return Err(format!("Not a gateway address: {text:?}")),
        },
    };
    let ip = address.ip();
    if !(ip.is_private() || ip.is_loopback() || ip.is_link_local()) || address.port() == 0 {
        return Err(format!("Not a local gateway: {address}"));
    }
    Ok(address)
}

/// A mapping request (RFC 6886 §3.3): version 0, opcode, reserved, internal port, suggested external port, lifetime.
pub fn request(protocol: Protocol, internal: u16, external: u16, lifetime: u32) -> [u8; 12] {
    let mut r = [0u8; 12];
    r[1] = protocol as u8;
    r[4..6].copy_from_slice(&internal.to_be_bytes());
    r[6..8].copy_from_slice(&external.to_be_bytes());
    r[8..12].copy_from_slice(&lifetime.to_be_bytes());
    r
}

/// The gateway's answer to [`request`] for `protocol` and `internal`, or why it is not one.
pub fn parse_response(data: &[u8], protocol: Protocol, internal: u16) -> Result<Mapping, String> {
    if data.len() < 16 || data[0] != 0 {
        return Err("Malformed NAT-PMP response".into());
    }
    if data[1] != 128 + protocol as u8 {
        return Err(format!("Unexpected NAT-PMP opcode {}", data[1]));
    }
    let code = u16::from_be_bytes([data[2], data[3]]);
    if code != 0 {
        let reason = match code {
            1 => "unsupported version",
            2 => "not authorized (mapping is disabled on the router)",
            3 => "network failure",
            4 => "out of resources",
            5 => "unsupported opcode",
            _ => "unknown result",
        };
        return Err(format!("Gateway refused the {} mapping: {reason} ({code})", protocol.name()));
    }
    let mapped_internal = u16::from_be_bytes([data[8], data[9]]);
    if mapped_internal != internal {
        return Err(format!("NAT-PMP response for port {mapped_internal}, not {internal}"));
    }
    Ok(Mapping {
        protocol,
        internal,
        external: u16::from_be_bytes([data[10], data[11]]),
        lifetime: u32::from_be_bytes([data[12], data[13], data[14], data[15]]),
    })
}

/// Sends one request, retrying from 250 ms with doubling waits (RFC 6886 §3.1), bounded to a few seconds.
async fn exchange(socket: &UdpSocket, protocol: Protocol, internal: u16, lifetime: u32, tries: u32) -> Result<Mapping, String> {
    let message = request(protocol, internal, internal, lifetime);
    let mut wait = FIRST_WAIT;
    let mut buf = [0u8; 64];
    for _ in 0..tries {
        socket.send(&message).await.map_err(|e| format!("NAT-PMP request: {e}"))?;
        let deadline = tokio::time::Instant::now() + wait;
        // The socket is connected to the gateway: the kernel drops datagrams from anyone else.
        while let Ok(received) = tokio::time::timeout_at(deadline, socket.recv(&mut buf)).await {
            match received {
                Ok(n) => match parse_response(&buf[..n], protocol, internal) {
                    Ok(m) => return Ok(m),
                    Err(e) if e.starts_with("Gateway refused") => return Err(e),
                    // An answer to the other protocol's request, or noise: keep waiting for ours.
                    Err(_) => continue,
                },
                // ICMP port unreachable surfaces as a receive error: no NAT-PMP service there.
                Err(e) => return Err(format!("NAT-PMP gateway unreachable: {e}")),
            }
        }
        wait *= 2;
    }
    Err(format!("No NAT-PMP answer from the gateway for {}", protocol.name()))
}

/// Keeps the listen port mapped until `stop` turns true, then deletes the mappings it made.
pub struct Mapper {
    stop: watch::Sender<bool>,
    task: tokio::task::JoinHandle<()>,
}

pub type Log = Arc<dyn Fn(String) + Send + Sync>;
/// Hears the external TCP port the gateway granted, and 0 when the mapping is gone.
pub type External = Arc<dyn Fn(u16) + Send + Sync>;

impl Mapper {
    pub fn start(gateway: SocketAddrV4, port: u16, protocols: Vec<Protocol>, log: Log, external: External) -> Mapper {
        let (stop, mut stopped) = watch::channel(false);
        let task = tokio::spawn(async move {
            let socket = match UdpSocket::bind(SocketAddrV4::new(Ipv4Addr::UNSPECIFIED, 0)).await {
                Ok(s) => s,
                Err(e) => return log(format!("NAT-PMP unavailable: {e}")),
            };
            if let Err(e) = socket.connect(gateway).await {
                return log(format!("NAT-PMP unavailable: {e}"));
            }
            let mut mapped: Vec<Protocol> = Vec::new();
            loop {
                let mut renew = Duration::from_secs(u64::from(LIFETIME) / 2);
                let mut failed = false;
                for &protocol in &protocols {
                    match exchange(&socket, protocol, port, LIFETIME, TRIES).await {
                        Ok(m) => {
                            if !mapped.contains(&protocol) {
                                log(format!("NAT-PMP mapped {} port {} to external port {} for {} s",
                                    protocol.name(), m.internal, m.external, m.lifetime));
                                mapped.push(protocol);
                            }
                            if protocol == Protocol::Tcp {
                                external(m.external);
                            }
                            renew = renew.min(Duration::from_secs(u64::from(m.lifetime.max(2)) / 2));
                        }
                        Err(e) => {
                            log(e);
                            failed = true;
                        }
                    }
                }
                let wait = if failed && mapped.is_empty() { RETRY_AFTER_FAILURE } else { renew };
                tokio::select! {
                    _ = tokio::time::sleep(wait) => {}
                    _ = stopped.changed() => break,
                }
            }
            external(0);
            for protocol in mapped {
                // One try each: the lease expires on its own if the gateway misses the deletion.
                match exchange(&socket, protocol, port, 0, 1).await {
                    Ok(_) => log(format!("NAT-PMP removed the {} mapping", protocol.name())),
                    Err(e) => log(e),
                }
            }
        });
        Mapper { stop, task }
    }

    /// Deletes the mappings and waits briefly for that to finish.
    pub async fn stop(self) {
        let _ = self.stop.send(true);
        let _ = tokio::time::timeout(Duration::from_secs(1), self.task).await;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn requests_follow_rfc_6886() {
        assert_eq!(request(Protocol::Tcp, 6881, 6881, 600), [0, 2, 0, 0, 0x1a, 0xe1, 0x1a, 0xe1, 0, 0, 2, 0x58]);
        assert_eq!(request(Protocol::Udp, 1, 2, 0), [0, 1, 0, 0, 0, 1, 0, 2, 0, 0, 0, 0]);
    }

    #[test]
    fn responses_are_checked() {
        let mut ok = [0u8; 16];
        ok[1] = 130;
        ok[8..10].copy_from_slice(&6881u16.to_be_bytes());
        ok[10..12].copy_from_slice(&40000u16.to_be_bytes());
        ok[12..16].copy_from_slice(&600u32.to_be_bytes());
        assert_eq!(parse_response(&ok, Protocol::Tcp, 6881).unwrap(),
            Mapping { protocol: Protocol::Tcp, internal: 6881, external: 40000, lifetime: 600 });
        assert!(parse_response(&ok, Protocol::Udp, 6881).is_err(), "opcode must match the request");
        assert!(parse_response(&ok, Protocol::Tcp, 6882).is_err(), "internal port must match");
        assert!(parse_response(&ok[..15], Protocol::Tcp, 6881).is_err());
        let mut refused = ok;
        refused[3] = 2;
        assert!(parse_response(&refused, Protocol::Tcp, 6881).unwrap_err().contains("not authorized"));
    }

    #[test]
    fn only_local_ipv4_gateways() {
        assert_eq!(parse_gateway("192.168.1.1").unwrap(), "192.168.1.1:5351".parse().unwrap());
        assert_eq!(parse_gateway("127.0.0.1:40123").unwrap(), "127.0.0.1:40123".parse().unwrap());
        assert!(parse_gateway("8.8.8.8").is_err());
        assert!(parse_gateway("fe80::1").is_err());
        assert!(parse_gateway("").is_err());
        assert!(parse_gateway("10.0.0.1:0").is_err());
    }
}
