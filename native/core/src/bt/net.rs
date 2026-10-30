//! Address policy and listen interfaces.

use std::net::{IpAddr, SocketAddr};

/// Optional live validation may use internet peers, but must never contact loopback or the owner's LAN. With
/// `public_network_only` this applies to peers, trackers and DHT nodes; deterministic tests use loopback peers.
pub fn is_public(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => {
            let o = v4.octets();
            !(o[0] == 0
                || o[0] == 10
                || (o[0] == 100 && (64..=127).contains(&o[1]))
                || o[0] == 127
                || (o[0] == 169 && o[1] == 254)
                || (o[0] == 172 && (16..=31).contains(&o[1]))
                || (o[0] == 192 && o[1] == 0 && o[2] == 0)
                || (o[0] == 192 && o[1] == 0 && o[2] == 2)
                || (o[0] == 192 && o[1] == 168)
                || (o[0] == 198 && (18..=19).contains(&o[1]))
                || (o[0] == 198 && o[1] == 51 && o[2] == 100)
                || (o[0] == 203 && o[1] == 0 && o[2] == 113)
                || o[0] >= 224)
        }
        IpAddr::V6(v6) => {
            // Only global unicast (2000::/3) is eligible; mapped IPv4, ULA, link-local and multicast are not.
            let s = v6.segments();
            (0x2000..=0x3fff).contains(&s[0]) && !(s[0] == 0x2001 && s[1] == 0x0db8)
        }
    }
}

#[derive(Clone, Copy, Debug, Default)]
pub struct Policy {
    pub public_only: bool,
}

impl Policy {
    pub fn allows(&self, address: &SocketAddr) -> bool {
        address.port() != 0 && (!self.public_only || is_public(address.ip()))
    }
}

/// libtorrent-style "address:port" entries, comma separated. Device names are not supported.
pub fn parse_listen(text: &str) -> Vec<SocketAddr> {
    text.split(',')
        .filter_map(|entry| {
            let entry = entry.trim().trim_end_matches('s');
            let (host, port) = entry.rsplit_once(':')?;
            let host = host.trim_start_matches('[').trim_end_matches(']');
            Some(SocketAddr::new(host.parse().ok()?, port.parse().ok()?))
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn public_filter_blocks_private_reserved_and_mapped_addresses() {
        for blocked in [
            "0.1.2.3", "10.0.0.1", "100.64.0.1", "127.0.0.1", "169.254.1.1", "172.16.0.1", "172.31.255.255",
            "192.0.0.1", "192.0.2.1", "192.168.1.1", "198.18.0.1", "198.51.100.1", "203.0.113.1", "224.0.0.1",
            "255.255.255.255", "::1", "::ffff:8.8.8.8", "fc00::1", "fe80::1", "ff02::1", "2001:db8::1",
        ] {
            assert!(!is_public(blocked.parse().unwrap()), "{blocked}");
        }
        for allowed in ["8.8.8.8", "1.1.1.1", "172.32.0.1", "2606:4700::1111"] {
            assert!(is_public(allowed.parse().unwrap()), "{allowed}");
        }
    }

    #[test]
    fn listen_interfaces_parse() {
        assert_eq!(
            parse_listen("0.0.0.0:0,[::]:6881, 127.0.0.1:58134s"),
            vec!["0.0.0.0:0".parse().unwrap(), "[::]:6881".parse().unwrap(), "127.0.0.1:58134".parse().unwrap()]
        );
        assert!(parse_listen("eth0:6881").is_empty());
    }
}
