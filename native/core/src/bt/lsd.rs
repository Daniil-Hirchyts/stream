//! Local service discovery (BEP 14): multicast announces on the LAN, and peers found from others' announces.

use super::metainfo::{from_hex, hex, Id20};
use super::session::SessionShared;
use std::net::{Ipv4Addr, SocketAddr, SocketAddrV4};
use std::sync::{Arc, Weak};
use tokio::net::UdpSocket;

const GROUP: Ipv4Addr = Ipv4Addr::new(239, 192, 152, 143);
const PORT: u16 = 6771;

pub struct Lsd {
    socket: UdpSocket,
    cookie: String,
}

fn bind() -> std::io::Result<std::net::UdpSocket> {
    let socket = socket2::Socket::new(socket2::Domain::IPV4, socket2::Type::DGRAM, Some(socket2::Protocol::UDP))?;
    // Other BitTorrent clients on this device listen on the same port.
    socket.set_reuse_address(true)?;
    #[cfg(unix)]
    socket.set_reuse_port(true)?;
    socket.bind(&SocketAddrV4::new(Ipv4Addr::UNSPECIFIED, PORT).into())?;
    socket.join_multicast_v4(&GROUP, &Ipv4Addr::UNSPECIFIED)?;
    socket.set_multicast_loop_v4(true)?;
    socket.set_nonblocking(true)?;
    Ok(socket.into())
}

/// A peer announcing a torrent: its info hash, port and cookie.
fn parse(message: &str) -> Option<(Vec<Id20>, u16, Option<&str>)> {
    let mut lines = message.split("\r\n");
    if !lines.next()?.starts_with("BT-SEARCH * HTTP/1.1") {
        return None;
    }
    let (mut hashes, mut port, mut cookie) = (Vec::new(), None, None);
    for line in lines {
        let Some((k, v)) = line.split_once(':') else { continue };
        match k.trim().to_ascii_lowercase().as_str() {
            "port" => port = v.trim().parse().ok(),
            "infohash" => hashes.extend(from_hex(v.trim()).and_then(|b| <Id20>::try_from(b).ok())),
            "cookie" => cookie = Some(v.trim()),
            _ => {}
        }
    }
    Some((hashes, port?, cookie))
}

impl Lsd {
    pub async fn start(session: Weak<SessionShared>) -> std::io::Result<Arc<Lsd>> {
        let socket = UdpSocket::from_std(bind()?)?;
        let mut random = [0u8; 6];
        let _ = getrandom::fill(&mut random);
        let lsd = Arc::new(Lsd { socket, cookie: hex(&random) });
        let weak = Arc::downgrade(&lsd);
        tokio::spawn(async move {
            let mut buf = vec![0u8; 1500];
            loop {
                let Some(l) = weak.upgrade() else { return };
                let received = tokio::time::timeout(std::time::Duration::from_secs(5), l.socket.recv_from(&mut buf)).await;
                let Ok(Ok((n, SocketAddr::V4(from)))) = received else { continue };
                let Some(s) = session.upgrade() else { return };
                let text = String::from_utf8_lossy(&buf[..n]);
                let Some((hashes, port, cookie)) = parse(&text) else { continue };
                if cookie == Some(l.cookie.as_str()) || port == 0 {
                    continue;
                }
                for h in hashes {
                    if let Some(t) = s.find(&h).filter(|t| !t.private()) {
                        t.add_candidates([SocketAddr::new((*from.ip()).into(), port)]);
                    }
                }
            }
        });
        Ok(lsd)
    }

    pub async fn announce(&self, info_hash: &Id20, port: u16) {
        let message = format!(
            "BT-SEARCH * HTTP/1.1\r\nHost: {GROUP}:{PORT}\r\nPort: {port}\r\nInfohash: {}\r\ncookie: {}\r\n\r\n\r\n",
            hex(info_hash),
            self.cookie
        );
        let _ = self.socket.send_to(message.as_bytes(), (GROUP, PORT)).await;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn announces_parse() {
        let m = "BT-SEARCH * HTTP/1.1\r\nHost: 239.192.152.143:6771\r\nPort: 6881\r\nInfohash: 0123456789abcdef0123456789abcdef01234567\r\ncookie: x\r\n\r\n\r\n";
        let (hashes, port, cookie) = parse(m).unwrap();
        assert_eq!((hashes.len(), port, cookie), (1, 6881, Some("x")));
        assert!(parse("GET / HTTP/1.1\r\n\r\n").is_none());
    }
}
