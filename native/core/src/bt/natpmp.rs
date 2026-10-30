//! The gateway's port-control protocols on UDP 5351: PCP (RFC 6887) and its predecessor NAT-PMP (RFC 6886). Both
//! are unicast to the gateway the app names; [`super::portmap`] tries PCP first and falls back to NAT-PMP when the
//! gateway answers that it only speaks version 0.

use super::portmap::{Failure, Mapping, Protocol};
use std::net::{IpAddr, Ipv4Addr, SocketAddr, SocketAddrV4};
use std::time::Duration;
use tokio::net::UdpSocket;

pub const PORT: u16 = 5351;
const FIRST_WAIT: Duration = Duration::from_millis(250);

/// The gateway's address: an IPv4 address, optionally with a port (for tests' loopback gateway). Only private,
/// link-local and loopback addresses are accepted, so a misconfiguration can't send requests across the internet.
pub fn parse_gateway(text: &str) -> Result<SocketAddrV4, String> {
    let text = text.trim();
    let address = match text.parse::<SocketAddr>() {
        Ok(SocketAddr::V4(a)) => a,
        Ok(SocketAddr::V6(_)) => return Err("Port mapping needs an IPv4 gateway".into()),
        Err(_) => match text.parse::<IpAddr>() {
            Ok(IpAddr::V4(ip)) => SocketAddrV4::new(ip, PORT),
            Ok(IpAddr::V6(_)) => return Err("Port mapping needs an IPv4 gateway".into()),
            Err(_) => return Err(format!("Not a gateway address: {text:?}")),
        },
    };
    let ip = address.ip();
    if !(ip.is_private() || ip.is_loopback() || ip.is_link_local()) || address.port() == 0 {
        return Err(format!("Not a local gateway: {address}"));
    }
    Ok(address)
}

/// A NAT-PMP mapping request (RFC 6886 §3.3): version 0, opcode, reserved, internal port, suggested external port,
/// lifetime.
pub fn request(protocol: Protocol, internal: u16, external: u16, lifetime: u32) -> [u8; 12] {
    let mut r = [0u8; 12];
    r[1] = natpmp_opcode(protocol);
    r[4..6].copy_from_slice(&internal.to_be_bytes());
    r[6..8].copy_from_slice(&external.to_be_bytes());
    r[8..12].copy_from_slice(&lifetime.to_be_bytes());
    r
}

fn natpmp_opcode(protocol: Protocol) -> u8 {
    match protocol {
        Protocol::Udp => 1,
        Protocol::Tcp => 2,
    }
}

/// The gateway's answer to [`request`] for `protocol` and `internal`, or why it is not one.
pub fn parse_response(data: &[u8], protocol: Protocol, internal: u16) -> Result<Mapping, Failure> {
    if data.len() < 4 || data[0] != 0 {
        return Err(Failure::Noise);
    }
    if data[1] != 128 + natpmp_opcode(protocol) {
        return Err(Failure::Noise);
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
        return Err(Failure::Refused(format!("NAT-PMP gateway refused the {} mapping: {reason} ({code})", protocol.name())));
    }
    if data.len() < 16 {
        return Err(Failure::Noise);
    }
    if u16::from_be_bytes([data[8], data[9]]) != internal {
        return Err(Failure::Noise);
    }
    Ok(Mapping {
        protocol,
        internal,
        external: u16::from_be_bytes([data[10], data[11]]),
        lifetime: u32::from_be_bytes([data[12], data[13], data[14], data[15]]),
    })
}

/// A PCP MAP request (RFC 6887 §7.1, §11.1): version 2, opcode MAP, lifetime, our address as IPv4-mapped IPv6, then
/// the mapping nonce, protocol, internal port, suggested external port and an unspecified suggested external address.
pub fn pcp_request(client: Ipv4Addr, nonce: [u8; 12], protocol: Protocol, internal: u16, lifetime: u32) -> [u8; 60] {
    let mut r = [0u8; 60];
    r[0] = 2;
    r[1] = 1;
    r[4..8].copy_from_slice(&lifetime.to_be_bytes());
    r[8..24].copy_from_slice(&client.to_ipv6_mapped().octets());
    r[24..36].copy_from_slice(&nonce);
    r[36] = pcp_protocol(protocol);
    r[40..42].copy_from_slice(&internal.to_be_bytes());
    r[42..44].copy_from_slice(&internal.to_be_bytes());
    r[44..60].copy_from_slice(&Ipv4Addr::UNSPECIFIED.to_ipv6_mapped().octets());
    r
}

fn pcp_protocol(protocol: Protocol) -> u8 {
    match protocol {
        Protocol::Udp => 17,
        Protocol::Tcp => 6,
    }
}

/// The answer to [`pcp_request`]. A NAT-PMP-only gateway answers a version-2 request with NAT-PMP's "unsupported
/// version" (RFC 6887 Appendix A), which is [`Failure::Unsupported`]: the caller then speaks NAT-PMP.
pub fn parse_pcp_response(data: &[u8], nonce: [u8; 12], protocol: Protocol, internal: u16) -> Result<Mapping, Failure> {
    if data.len() >= 4 && data[0] == 0 && u16::from_be_bytes([data[2], data[3]]) == 1 {
        return Err(Failure::Unsupported);
    }
    if data.len() < 60 || data[0] != 2 || data[1] != 0x81 {
        return Err(Failure::Noise);
    }
    // A reply to another of our mappings, or to a request we no longer wait for.
    if data[24..36] != nonce || data[36] != pcp_protocol(protocol) || u16::from_be_bytes([data[40], data[41]]) != internal {
        return Err(Failure::Noise);
    }
    let code = data[3];
    if code != 0 {
        let reason = match code {
            1 => return Err(Failure::Unsupported),
            2 => "not authorized (mapping is disabled on the router)",
            3 => "malformed request",
            4 => "unsupported opcode",
            7 => "network failure",
            8 => "out of resources",
            9 => "unsupported protocol",
            10 => "mapping quota exceeded",
            11 => "cannot provide an external port",
            12 => "address mismatch (behind another NAT)",
            _ => "unknown result",
        };
        return Err(Failure::Refused(format!("PCP gateway refused the {} mapping: {reason} ({code})", protocol.name())));
    }
    Ok(Mapping {
        protocol,
        internal,
        external: u16::from_be_bytes([data[42], data[43]]),
        lifetime: u32::from_be_bytes([data[4], data[5], data[6], data[7]]),
    })
}

/// Sends `message`, retrying from 250 ms with doubling waits (RFC 6886 §3.1, RFC 6887 §8.1.1), bounded to `tries`.
/// The socket is connected to the gateway, so the kernel drops datagrams from anyone else.
pub async fn exchange(
    socket: &UdpSocket,
    message: &[u8],
    tries: u32,
    parse: impl Fn(&[u8]) -> Result<Mapping, Failure>,
) -> Result<Mapping, Failure> {
    let mut wait = FIRST_WAIT;
    let mut buf = [0u8; 1100];
    for _ in 0..tries {
        socket.send(message).await.map_err(|e| Failure::NoAnswer(format!("request: {e}")))?;
        let deadline = tokio::time::Instant::now() + wait;
        while let Ok(received) = tokio::time::timeout_at(deadline, socket.recv(&mut buf)).await {
            match received {
                Ok(n) => match parse(&buf[..n]) {
                    Err(Failure::Noise) => continue,
                    other => return other,
                },
                // ICMP port unreachable surfaces as a receive error: nothing listens on the gateway's port.
                Err(e) => return Err(Failure::NoAnswer(format!("gateway unreachable: {e}"))),
            }
        }
        wait *= 2;
    }
    Err(Failure::NoAnswer("no answer from the gateway".into()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn natpmp_requests_follow_rfc_6886() {
        assert_eq!(request(Protocol::Tcp, 6881, 6881, 600), [0, 2, 0, 0, 0x1a, 0xe1, 0x1a, 0xe1, 0, 0, 2, 0x58]);
        assert_eq!(request(Protocol::Udp, 1, 2, 0), [0, 1, 0, 0, 0, 1, 0, 2, 0, 0, 0, 0]);
    }

    #[test]
    fn natpmp_responses_are_checked() {
        let mut ok = [0u8; 16];
        ok[1] = 130;
        ok[8..10].copy_from_slice(&6881u16.to_be_bytes());
        ok[10..12].copy_from_slice(&40000u16.to_be_bytes());
        ok[12..16].copy_from_slice(&600u32.to_be_bytes());
        assert_eq!(parse_response(&ok, Protocol::Tcp, 6881).unwrap(),
            Mapping { protocol: Protocol::Tcp, internal: 6881, external: 40000, lifetime: 600 });
        assert_eq!(parse_response(&ok, Protocol::Udp, 6881), Err(Failure::Noise), "opcode must match the request");
        assert_eq!(parse_response(&ok, Protocol::Tcp, 6882), Err(Failure::Noise), "internal port must match");
        assert_eq!(parse_response(&ok[..15], Protocol::Tcp, 6881), Err(Failure::Noise));
        let mut refused = ok;
        refused[3] = 2;
        assert!(matches!(parse_response(&refused, Protocol::Tcp, 6881), Err(Failure::Refused(e)) if e.contains("not authorized")));
    }

    #[test]
    fn pcp_requests_follow_rfc_6887() {
        let nonce = [9u8; 12];
        let r = pcp_request(Ipv4Addr::new(192, 168, 1, 20), nonce, Protocol::Tcp, 6881, 600);
        assert_eq!(&r[..8], &[2, 1, 0, 0, 0, 0, 2, 0x58]);
        assert_eq!(&r[8..24], &Ipv4Addr::new(192, 168, 1, 20).to_ipv6_mapped().octets());
        assert_eq!(&r[24..36], &nonce);
        assert_eq!(r[36], 6);
        assert_eq!(&r[40..44], &[0x1a, 0xe1, 0x1a, 0xe1]);
        assert_eq!(&r[44..60], &Ipv4Addr::UNSPECIFIED.to_ipv6_mapped().octets());
        assert_eq!(pcp_request(Ipv4Addr::LOCALHOST, nonce, Protocol::Udp, 1, 0)[36], 17);
    }

    #[test]
    fn pcp_responses_are_checked_and_natpmp_gateways_recognised() {
        let nonce = [3u8; 12];
        let mut ok = [0u8; 60];
        ok[0] = 2;
        ok[1] = 0x81;
        ok[4..8].copy_from_slice(&120u32.to_be_bytes());
        ok[24..36].copy_from_slice(&nonce);
        ok[36] = 6;
        ok[40..42].copy_from_slice(&6881u16.to_be_bytes());
        ok[42..44].copy_from_slice(&50000u16.to_be_bytes());
        assert_eq!(parse_pcp_response(&ok, nonce, Protocol::Tcp, 6881).unwrap(),
            Mapping { protocol: Protocol::Tcp, internal: 6881, external: 50000, lifetime: 120 });
        assert_eq!(parse_pcp_response(&ok, [4u8; 12], Protocol::Tcp, 6881), Err(Failure::Noise), "nonce must match");
        assert_eq!(parse_pcp_response(&ok, nonce, Protocol::Udp, 6881), Err(Failure::Noise));
        let mut refused = ok;
        refused[3] = 2;
        assert!(matches!(parse_pcp_response(&refused, nonce, Protocol::Tcp, 6881), Err(Failure::Refused(_))));
        // A NAT-PMP gateway's reply to a version-2 request: version 0, opcode 128 + 1, result 1.
        assert_eq!(parse_pcp_response(&[0, 129, 0, 1, 0, 0, 0, 0], nonce, Protocol::Tcp, 6881), Err(Failure::Unsupported));
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
